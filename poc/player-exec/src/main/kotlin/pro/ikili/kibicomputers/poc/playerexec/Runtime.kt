package pro.ikili.kibicomputers.poc.playerexec

import pro.ikili.kibicomputers.poc.fuel.FuelInjector
import pro.ikili.kibicomputers.poc.fuel.Wasm
import run.endive.compiler.Cache
import run.endive.compiler.InterpreterFallback
import run.endive.compiler.MachineFactoryCompiler
import run.endive.runtime.CallResult
import run.endive.runtime.CompiledModule
import run.endive.runtime.HostFunction
import run.endive.runtime.ImportValues
import run.endive.runtime.Instance
import run.endive.runtime.InterpreterMachine
import run.endive.runtime.Machine
import run.endive.wasm.Parser
import run.endive.wasm.WasmModule
import run.endive.wasm.types.FunctionType
import run.endive.wasm.types.ValType
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 比べる実行方式。 */
enum class Policy(val label: String) {
    /** A: 常にインタプリタ */
    INTERPRETER("A: インタプリタ"),

    /** B: 常にランタイムコンパイラ。初めて読み込んだ WASM は、コンパイルが終わるまで起動を待つ */
    COMPILER("B: コンパイラ"),

    /**
     * C: インタプリタで始め、よく使われる WASM だけ別スレッドでコンパイルする。
     * コンパイルが終わったら、次の起動と、次に export を呼ぶとき（呼び出しの外側で）からコンパイルしたコードで動かす
     */
    TIERED("C: 段階的"),
}

/** プレイヤーの WASM（読み込む前のバイト列）。同じ中身なら同じ [hash] になる。 */
class Program(val name: String, val bytes: ByteArray) {
    val hash: String = sha256(bytes)

    /**
     * 中身（コード）は同じで、ハッシュだけ違うもの。末尾にカスタムセクションを足す。
     * 「サーバーに別々のプログラムがたくさんある」状態を、同じ大きさのコードで作るために使う
     */
    fun variant(index: Int): Program {
        val name = "kibi-poc-variant".toByteArray()
        val payload = index.toString().toByteArray()
        val content = leb128(name.size) + name + payload
        return Program("${this.name}#$index", bytes + byteArrayOf(0) + leb128(content.size) + content)
    }

    companion object {
        fun of(wasm: Wasm): Program = Program(wasm.name.lowercase(), wasm.bytes)

        private fun leb128(value: Int): ByteArray {
            val out = ArrayList<Byte>()
            var v = value
            do {
                var b = v and 0x7f
                v = v ushr 7
                if (v != 0) b = b or 0x80
                out += b.toByte()
            } while (v != 0)
            return out.toByteArray()
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/**
 * 読み込んだ WASM（燃料の計測コードを挿入して、パースしたもの）。内容のハッシュで共有する。
 * コンパイル結果（[compiled]）も、これに1つだけ持つ。
 */
class LoadedModule internal constructor(val hash: String, module: WasmModule, val injectNanos: Long, val parseNanos: Long) {
    /**
     * パースした WASM（インタプリタで動かすときと、コンパイルするときに使う）。コンパイルが終わったら捨てる（null にする）。
     * Endive のコンパイル結果は関数の本体を除いた別の WasmModule を持つので、捨てるとヒープが大きく減る
     * （挿入後の lua.wasm で約 18 MB → 約 0.5 MB）
     */
    @Volatile
    var parsed: WasmModule? = module
        internal set

    /** これまでに使った燃料（命令数）の合計。C でコンパイルするかどうかを決める */
    val fuelUsed = AtomicLong()

    /** 起動した回数 */
    val launches = AtomicInteger()

    /** コンパイル結果。まだなければ null */
    @Volatile
    var compiled: CompiledModule? = null
        internal set

    internal var compiling: CompletableFuture<CompiledModule>? = null
}

/** 数えたもの。 */
class Stats {
    /** コンパイルした回数 */
    val compiles = AtomicInteger()

    /** コンパイルに使った CPU 時間（コンパイルのスレッドのもの） */
    val compileCpuNanos = AtomicLong()

    /** C で、実行中のインスタンスをコンパイルしたコードに切り替えた回数 */
    val upgrades = AtomicInteger()
}

/**
 * プレイヤーの WASM を動かすランタイム（サーバーに1つ）。
 *
 * - 読み込みでは、燃料の計測コードを挿入してパースする（#28）。結果は内容のハッシュで共有する（[share] が false なら共有しない）
 * - コンパイルは別のスレッド（[compilerThreads] 本）で行う。メインスレッドでは行わない（#26）
 * - C では、使った燃料が [hotFuel] を超えた WASM をコンパイルする
 */
class PlayerRuntime(
    val policy: Policy,
    val hotFuel: Long = DEFAULT_HOT_FUEL,
    val share: Boolean = true,
    compilerThreads: Int = 1,
    private val cache: Cache? = null,
) : AutoCloseable {
    private val modules = ConcurrentHashMap<String, LoadedModule>()
    private val compiler: ExecutorService = Executors.newFixedThreadPool(compilerThreads) { r ->
        Thread(r, "kibi-wasm-compiler").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    val stats = Stats()

    /** 読み込む（燃料の計測コードを挿入してパースする）。同じ中身なら、前に読み込んだものを返す */
    fun load(program: Program): LoadedModule {
        if (!share) return doLoad(program)
        return modules.computeIfAbsent(program.hash) { doLoad(program) }
    }

    private fun doLoad(program: Program): LoadedModule {
        val t0 = System.nanoTime()
        val injected = FuelInjector.inject(program.bytes, FuelInjector.Storage.LOCAL).bytes
        val t1 = System.nanoTime()
        val module = Parser.parse(injected)
        val t2 = System.nanoTime()
        return LoadedModule(program.hash, module, t1 - t0, t2 - t1)
    }

    /** 共有している WASM を捨てる（実行中のコンピューターがなくなったとき）。コンパイルしたクラスは GC で解放される */
    fun unloadAll() {
        modules.clear()
    }

    /** コンパイルを頼む（1つの WASM につき1回だけ）。終わると [LoadedModule.compiled] が入る */
    fun compileAsync(lm: LoadedModule): CompletableFuture<CompiledModule> = synchronized(lm) {
        lm.compiling ?: CompletableFuture.supplyAsync({
            val threads = ManagementFactory.getThreadMXBean()
            val c0 = threads.currentThreadCpuTime
            val builder = MachineFactoryCompiler.builder(checkNotNull(lm.parsed)).withInterpreterFallback(InterpreterFallback.WARN)
            if (cache != null) builder.withCache(cache)
            val compiled = builder.compileModule()
            stats.compileCpuNanos.addAndGet(threads.currentThreadCpuTime - c0)
            stats.compiles.incrementAndGet()
            lm.compiled = compiled
            // 実行中のインタプリタのインスタンスは、自分の WasmModule を持っているので影響しない
            lm.parsed = null
            compiled
        }, compiler).also { lm.compiling = it }
    }

    /**
     * インスタンスを作る（起動）。[extra] は WASI などの import。
     * 燃料は [Launch.SLICE] ずつ配る（tick ごとに配る代わり。#27・#47）
     */
    fun launch(lm: LoadedModule, extra: List<HostFunction> = emptyList()): Launch {
        lm.launches.incrementAndGet()
        val launch = Launch(this, lm)
        val builder = when (policy) {
            Policy.INTERPRETER -> Instance.builder(checkNotNull(lm.parsed))
            Policy.COMPILER -> Instance.builder(compileAsync(lm).join())
            Policy.TIERED -> {
                // compiled を先に入れてから parsed を捨てるので、この順に読めばどちらかは必ずある
                val compiled = lm.compiled
                val parsed = lm.parsed
                when {
                    compiled != null -> Instance.builder(compiled)
                    parsed != null -> Instance.builder(parsed).withMachineFactory { TieredMachine(it, lm, stats) }
                    else -> Instance.builder(checkNotNull(lm.compiled))
                }
            }
        }
        val imports = ImportValues.builder()
        for (f in extra) imports.addFunction(f)
        imports.addFunction(
            HostFunction(FuelInjector.IMPORT_MODULE, FuelInjector.IMPORT_NAME, FunctionType.of(listOf(ValType.I64), listOf(ValType.I64))) { _, args ->
                longArrayOf(launch.refuel(args[0]))
            },
        )
        launch.instance = builder.withImportValues(imports.build()).withStart(false).build()
        launch.instance.exports().global(FuelInjector.EXPORT_GLOBAL).setValue(Launch.SLICE)
        return launch
    }

    internal fun fuelUsed(lm: LoadedModule, amount: Long) {
        val total = lm.fuelUsed.addAndGet(amount)
        if (policy == Policy.TIERED && total >= hotFuel && lm.compiled == null) compileAsync(lm)
    }

    /** 頼んだコンパイルがすべて終わるまで待つ（測定で、無駄になったコンパイルの CPU も数えるため） */
    fun awaitCompiles() {
        modules.values.mapNotNull { synchronized(it) { it.compiling } }.forEach { it.join() }
    }

    override fun close() {
        compiler.shutdownNow()
    }

    companion object {
        /**
         * C でコンパイルする閾値（命令数）。インタプリタで約 0.4 秒分。
         * lua.wasm（挿入後）のコンパイルにかかる時間と同じくらいのところで、コンパイルの元が取れはじめる
         */
        const val DEFAULT_HOT_FUEL: Long = 20_000_000
    }
}

/** 1回の起動（1つのインスタンス）。燃料を数える。 */
class Launch internal constructor(private val runtime: PlayerRuntime, val module: LoadedModule) {
    lateinit var instance: Instance
        internal set
    private var granted = SLICE
    private var tallied = 0L

    /** 燃料切れ。次の分を配り、使った分をモジュールに足す */
    internal fun refuel(remaining: Long): Long {
        granted += SLICE
        tally(granted - SLICE - remaining)
        return remaining + SLICE
    }

    private fun tally(usedSoFar: Long) {
        runtime.fuelUsed(module, usedSoFar - tallied)
        tallied = usedSoFar
    }

    /** これまでに使った燃料 */
    fun used(): Long = granted - instance.exports().global(FuelInjector.EXPORT_GLOBAL).getValue()

    /** 実行の区切り（export の呼び出しが終わったとき）に、使った分をモジュールに足す */
    fun finish() {
        tally(used())
    }

    /** 今どのコードで動くか */
    val tier: String
        get() = when (val m = instance.getMachine()) {
            is TieredMachine -> if (m.upgraded) "コンパイル（切り替え）" else "インタプリタ"
            is InterpreterMachine -> "インタプリタ"
            else -> "コンパイル"
        }

    companion object {
        /** 1回に配る燃料。tick ごとに配る量の代わり */
        const val SLICE: Long = 1_000_000
    }
}

/**
 * C のマシン。インタプリタで始め、コンパイルが終わっていたら、export の呼び出しの外側（入れ子でないとき）で
 * コンパイルしたコードに切り替える。実行中の呼び出しの途中では切り替えられない（Endive にスタックを移す仕組みがない）。
 * メモリ・グローバル・テーブルはインスタンスにあるので、どちらのマシンからも同じものが見える。
 */
internal class TieredMachine(private val instance: Instance, private val lm: LoadedModule, private val stats: Stats) : Machine {
    @Volatile
    private var current: Machine = InterpreterMachine(instance)
    private var depth = 0

    @Volatile
    var upgraded: Boolean = false
        private set

    private fun enter(): Machine {
        if (depth == 0 && !upgraded) {
            val compiled = lm.compiled
            if (compiled != null) {
                current = compiled.machineFactory().apply(instance)
                upgraded = true
                stats.upgrades.incrementAndGet()
            }
        }
        depth++
        return current
    }

    override fun call(funcId: Int, args: LongArray?): LongArray? = try {
        enter().call(funcId, args)
    } finally {
        depth--
    }

    override fun call(funcId: Int, args: LongArray?, refArgs: Array<Any?>?): LongArray? = try {
        enter().call(funcId, args, refArgs)
    } finally {
        depth--
    }

    override fun callWithRefs(funcId: Int, args: LongArray?, refArgs: Array<Any?>?): CallResult? = try {
        enter().callWithRefs(funcId, args, refArgs)
    } finally {
        depth--
    }

    override fun close() {
        current.close()
    }
}

/** Endive のコンパイラのキャッシュ（生成したクラスのバイト列）。測定ではメモリに置く。実装ではディスクに置ける */
class MapCache : Cache {
    private val map = ConcurrentHashMap<String, ByteArray>()
    val size: Int get() = map.size
    val bytes: Long get() = map.values.sumOf { it.size.toLong() }

    override fun get(key: String): ByteArray? = map[key]

    override fun putIfAbsent(key: String, value: ByteArray) {
        map.putIfAbsent(key, value)
    }
}
