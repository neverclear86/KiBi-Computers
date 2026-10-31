package pro.ikili.kibicomputers.poc.waitpointsave

import run.endive.compiler.InterpreterFallback
import run.endive.compiler.MachineFactoryCompiler
import run.endive.runtime.CompiledModule
import run.endive.runtime.Instance
import run.endive.wasm.Parser
import run.endive.wasm.WasmModule
import java.nio.ByteBuffer
import java.security.MessageDigest

/** 検証に使う WASM。 */
enum class WasmProgram(private val resource: String) {
    /** C だけの最小の reactor（`src/main/wasm/waiter.c`） */
    WAITER("/kibicomputers/wasm/waiter.wasm"),

    /** Lua 5.4 とリンクした検証用の reactor（`src/main/wasm/lua_host.c`） */
    LUA_HOST("/kibicomputers/wasm/lua_host.wasm"),

    /** #91 の lua.wasm（`lua.c` の main を持つコマンド形式。core のリソース） */
    LUA_COMMAND("/kibicomputers/wasm/lua.wasm"),
    ;

    val bytes: ByteArray by lazy { requireNotNull(WasmProgram::class.java.getResourceAsStream(resource)) { "$resource がない" }.use { it.readBytes() } }

    /** モジュールの SHA-256。保存データを同じモジュールにしか戻さないために使う */
    val sha256: ByteArray by lazy { MessageDigest.getInstance("SHA-256").digest(bytes) }

    val module: WasmModule by lazy { Parser.parse(bytes) }

    /** ランタイムコンパイラでの変換結果（作るのに時間がかかるので1回だけ） */
    val compiled: CompiledModule by lazy {
        MachineFactoryCompiler.builder(module).withInterpreterFallback(InterpreterFallback.FAIL).compileModule()
    }
}

/** 実行方式。 */
enum class Mode(val label: String) {
    INTERPRETER("インタプリタ"),
    RUNTIME_COMPILER("ランタイムコンパイラ"),
}

/** WASM の中にいるので（待機点でないので）保存できない。 */
class NotAtWaitPointException(message: String) : IllegalStateException(message)

/**
 * 1つのプロセス（WASM のインスタンスと、そのカーネルの状態）。
 *
 * export の呼び出し中は [MiniKernel.inWasm] が立つ。呼び出しから戻ったときが待機点。
 */
class Process(val program: WasmProgram, val mode: Mode, val kernel: MiniKernel, val instance: Instance) {
    val wasi: MiniWasi get() = wasiRef

    private lateinit var wasiRef: MiniWasi

    /** export を呼ぶ。呼んでいる間は WASM の中にいる扱いになる。 */
    fun call(name: String, vararg args: Long): LongArray {
        check(!kernel.inWasm) { "入れ子の呼び出し" }
        kernel.inWasm = true
        try {
            return instance.export(name).apply(*args) ?: LongArray(0)
        } catch (e: Throwable) {
            val exit = generateSequence(e) { it.cause }.filterIsInstance<ProcExit>().firstOrNull()
            if (exit != null) {
                kernel.state = ProcessState.EXITED
                kernel.exitCode = exit.code
                return LongArray(0)
            }
            kernel.state = ProcessState.FAILED
            throw e
        } finally {
            kernel.inWasm = false
        }
    }

    /** 線形メモリにバイト列を置く（`poc_alloc` で確保する。解放は WASM 側） */
    fun put(bytes: ByteArray): Int {
        val ptr = call("poc_alloc", bytes.size.toLong())[0].toInt()
        instance.memory().write(ptr, bytes)
        return ptr
    }

    /** reactor の初期化（`_initialize`）。新しいプロセスで1回だけ呼ぶ。復元したプロセスでは呼ばない */
    fun initialize() {
        check(kernel.state == ProcessState.NEW)
        call("_initialize")
        kernel.state = ProcessState.WAITING
    }

    /** waiter: イベントを1つ処理する */
    fun step(kind: Int, arg: Int): Int {
        val r = call("poc_step", kind.toLong(), arg.toLong())[0].toInt()
        kernel.state = ProcessState.WAITING
        return r
    }

    /** lua_host: スクリプトを読み、最初の待ちまで動かす */
    fun load(script: String): Int = luaResult(call("poc_load", put(script.toByteArray()).toLong(), script.toByteArray().size.toLong())[0].toInt())

    /** lua_host: イベントを1つ渡して、次の待ちまで動かす */
    fun resume(event: ByteArray): Int {
        check(kernel.state == ProcessState.WAITING) { "待っていない: ${kernel.state}" }
        return luaResult(call("poc_resume", put(event).toLong(), event.size.toLong())[0].toInt())
    }

    private fun luaResult(r: Int): Int {
        kernel.state = when (r) {
            1 -> ProcessState.WAITING
            0 -> ProcessState.EXITED
            else -> ProcessState.FAILED
        }
        check(r >= 0) { "Lua のエラー: ${kernel.stderrText()}" }
        return r
    }

    /** イベントを1つ渡す。waiter は [waiterEvent] の形、lua_host は文字列 */
    fun deliver(event: ByteArray): Int = when (program) {
        WasmProgram.WAITER -> ByteBuffer.wrap(event).let { step(it.int, it.int) }
        WasmProgram.LUA_HOST -> resume(event)
        WasmProgram.LUA_COMMAND -> error("コマンド形式にはイベントを渡せない")
    }

    /** キューに積まれたイベントを、プロセスが待っている間すべて渡す */
    fun pump(): Int {
        var n = 0
        while (kernel.state == ProcessState.WAITING && kernel.events.isNotEmpty()) {
            deliver(kernel.events.removeFirst())
            n++
        }
        return n
    }

    /** コマンド形式（`_start`）を最後まで動かす */
    fun runCommand() {
        call("_start")
        if (kernel.state != ProcessState.FAILED) kernel.state = ProcessState.EXITED
    }

    companion object {
        /** 新しいインスタンスを作る。start セクションも `_initialize` も呼ばない */
        fun instantiate(program: WasmProgram, mode: Mode, kernel: MiniKernel): Process {
            val wasi = MiniWasi(kernel)
            val builder = when (mode) {
                Mode.INTERPRETER -> Instance.builder(program.module)
                Mode.RUNTIME_COMPILER -> Instance.builder(program.compiled)
            }
            val instance = builder.withImportValues(wasi.imports(program.module)).withStart(false).build()
            return Process(program, mode, kernel, instance).also { it.wasiRef = wasi }
        }

        fun start(program: WasmProgram, mode: Mode, args: List<String> = emptyList()): Process = instantiate(program, mode, MiniKernel(args))
    }
}

/** waiter のイベント（kind, arg）をバイト列にする */
fun waiterEvent(kind: Int, arg: Int): ByteArray = ByteBuffer.allocate(8).putInt(kind).putInt(arg).array()
