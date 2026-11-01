package pro.ikili.kibicomputers.poc.threadlesswait

import run.endive.compiler.InterpreterFallback
import run.endive.compiler.MachineFactoryCompiler
import run.endive.runtime.CompiledModule
import run.endive.runtime.Instance
import run.endive.wasm.Parser
import run.endive.wasm.WasmModule
import java.util.concurrent.ConcurrentHashMap

/** 検証に使う WASM（どちらも普通のコマンド形式。`_start` から動き、fd_read で標準入力を待つ）。 */
enum class WasmProgram(private val resource: String) {
    /** C の CLI（`src/main/wasm/prompt.c`） */
    PROMPT("/kibicomputers/wasm/prompt.wasm"),

    /** #91 の lua.wasm（`lua.c` の main。REPL は fd_read で待つ。例外処理と setjmp/longjmp を exnref で使う） */
    LUA("/kibicomputers/wasm/lua.wasm"),
    ;

    val bytes: ByteArray by lazy {
        requireNotNull(WasmProgram::class.java.getResourceAsStream(resource)) { "$resource がない" }.use { it.readBytes() }
    }

    /** 方式 A の変換をかけたもの */
    val asyncified: Asyncify.Result by lazy { Asyncify.transform(bytes, P1Kernel.ASYNC_IMPORTS) }
}

/** 実行方式。 */
enum class Mode(val label: String) {
    INTERPRETER("インタプリタ"),
    RUNTIME_COMPILER("ランタイムコンパイラ"),
}

/** 読み込んだモジュール（変換の有無・実行方式ごとに1回だけ作る） */
class LoadedModule(val bytes: ByteArray, val mode: Mode) {
    val module: WasmModule = Parser.parse(bytes)
    val compiled: CompiledModule? = if (mode == Mode.RUNTIME_COMPILER) {
        MachineFactoryCompiler.builder(module).withInterpreterFallback(InterpreterFallback.WARN).compileModule()
    } else {
        null
    }

    fun instantiate(kernel: P1Kernel): Instance {
        val builder = compiled?.let { Instance.builder(it) } ?: Instance.builder(module)
        return builder.withImportValues(kernel.imports(module)).withStart(false).build()
    }

    companion object {
        private val cache = ConcurrentHashMap<Triple<WasmProgram, Boolean, Mode>, LoadedModule>()

        fun of(program: WasmProgram, async: Boolean, mode: Mode): LoadedModule = cache.computeIfAbsent(Triple(program, async, mode)) {
            LoadedModule(if (async) program.asyncified.bytes else program.bytes, mode)
        }
    }
}

/** プロセスの状態。 */
enum class ProcState { RUNNING, WAITING, EXITED, FAILED }

/**
 * 方式 A のプロセス: asyncify した `_start` を、待つたびに戻ってくる形で動かす。スレッドは持たない。
 *
 * [step] は呼んだスレッドで、次の待ち（か終わり）まで動かす。戻ったときは WASM のスタックが空（待機点）。
 */
class AsyncProcess(program: WasmProgram, mode: Mode, args: List<String>) {
    val kernel: P1Kernel = P1Kernel(args, WaitStyle.UNWIND)
    var instance: Instance = LoadedModule.of(program, true, mode).instantiate(kernel)
        private set
    private val loaded = LoadedModule.of(program, true, mode)
    var state: ProcState = ProcState.RUNNING
        private set
    var exitCode: Int = -1
        private set
    private var started = false

    init {
        kernel.asyncState = instance.exports().global(Asyncify.STATE_EXPORT)
    }

    /** 次の待ちか終わりまで動かす */
    fun step(): ProcState {
        check(state != ProcState.EXITED && state != ProcState.FAILED)
        val g = kernel.asyncState!!
        if (started) {
            check(state == ProcState.WAITING)
            g.setValue(Asyncify.REWINDING.toLong())
        }
        started = true
        state = ProcState.RUNNING
        try {
            instance.export("_start").apply()
            check(kernel.frameTop == 0 || g.getValue().toInt() == Asyncify.UNWINDING)
            if (g.getValue().toInt() == Asyncify.UNWINDING) {
                g.setValue(Asyncify.NORMAL.toLong())
                state = ProcState.WAITING
                kernel.onWait?.invoke()
            } else {
                state = ProcState.EXITED
                exitCode = 0
            }
        } catch (e: Throwable) {
            val exit = generateSequence(e) { it.cause }.filterIsInstance<ProcExit>().firstOrNull()
            if (exit != null) {
                state = ProcState.EXITED
                exitCode = exit.code
            } else {
                state = ProcState.FAILED
                throw e
            }
        }
        return state
    }

    /** 待っているなら入力を渡して、次の待ちまで動かす */
    fun feed(line: String): ProcState {
        kernel.send(line.toByteArray())
        return step()
    }

    /**
     * 待機点保存（#30）の形で、別のインスタンスに移す: 線形メモリ・可変のグローバル・退避したフレーム（カーネルの状態）を写す。
     * 新しいインスタンスは start も `_initialize` も呼ばない。戻り値は写したバイト数（メモリ、フレーム）
     */
    fun moveToNewInstance(): Pair<Int, Int> {
        check(state == ProcState.WAITING) { "待機点でない: $state" }
        val old = instance
        val fresh = loaded.instantiate(kernel)
        val mem = old.memory()
        val pages = mem.pages()
        val newMem = fresh.memory()
        if (newMem.pages() < pages) newMem.grow(pages - newMem.pages())
        val bytes = mem.readBytes(0, pages * 65536)
        newMem.write(0, bytes)
        for (i in 0 until old.globalCount()) {
            val g = old.global(i)
            if (g.mutabilityType == run.endive.wasm.types.MutabilityType.Var) {
                fresh.global(i).setValueLow(g.valueLow)
                fresh.global(i).setValueHigh(g.valueHigh)
            }
        }
        instance = fresh
        kernel.asyncState = fresh.exports().global(Asyncify.STATE_EXPORT)
        return bytes.size to kernel.frameTop * 8
    }
}

/**
 * 方式 B・C のプロセス: 普通の `_start` を1本のスレッドで動かし、fd_read の中でスレッドを止めて待つ（今の `BLOCKING` と同じ）。
 */
class BlockingProcess(program: WasmProgram, mode: Mode, args: List<String>, private val threads: ThreadKind) {
    val kernel: P1Kernel = P1Kernel(args, WaitStyle.BLOCK)
    private val loaded = LoadedModule.of(program, false, mode)

    @Volatile var state: ProcState = ProcState.RUNNING
        private set
    var thread: Thread? = null
        private set

    @Volatile var failure: Throwable? = null

    fun start() {
        val t = threads.create("p1") {
            try {
                loaded.instantiate(kernel).export("_start").apply()
                state = ProcState.EXITED
            } catch (e: Throwable) {
                state = if (generateSequence(e) { it.cause }.any { it is ProcExit }) ProcState.EXITED else ProcState.FAILED
                if (state == ProcState.FAILED) failure = e
            }
        }
        thread = t
        t.start()
    }

    fun feed(line: String) = kernel.send(line.toByteArray())
}

/** スレッドの作り方。 */
enum class ThreadKind(val label: String) {
    /** プラットフォームスレッド（今の BLOCKING。スタック 16 MiB を予約） */
    PLATFORM("プラットフォームスレッド"),

    /** 仮想スレッド（Java 21 以上。リフレクションで作る） */
    VIRTUAL("仮想スレッド"),
    ;

    fun create(name: String, body: Runnable): Thread = when (this) {
        PLATFORM -> Thread(null, body, name, 16L shl 20).also { it.isDaemon = true }
        VIRTUAL -> {
            val builder = Thread::class.java.getMethod("ofVirtual").invoke(null)
            val cls = Class.forName("java.lang.Thread\$Builder")
            val named = cls.getMethod("name", String::class.java).invoke(builder, name)
            cls.getMethod("unstarted", Runnable::class.java).invoke(named, body) as Thread
        }
    }

    companion object {
        val virtualAvailable: Boolean by lazy { runCatching { Thread::class.java.getMethod("ofVirtual") }.isSuccess }
    }
}
