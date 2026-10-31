package pro.ikili.kibicomputers.poc.fuel

import run.endive.compiler.InterpreterFallback
import run.endive.compiler.MachineFactoryCompiler
import run.endive.runtime.HostFunction
import run.endive.runtime.ImportValues
import run.endive.runtime.Instance
import run.endive.wasi.WasiExitException
import run.endive.wasi.WasiOptions
import run.endive.wasi.WasiPreview1
import run.endive.wasm.Parser
import run.endive.wasm.types.FunctionType
import run.endive.wasm.types.ValType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Random

/** 実行方式（ランタイム）。 */
enum class Runtime(val label: String) {
    INTERPRETER("インタプリタ"),
    COMPILER("ランタイムコンパイラ"),
}

/** 燃料の計測方式。 */
enum class Metering(val label: String) {
    /** 計測しない（基準） */
    NONE("なし"),

    /** A: 読み込み時に計測コードを挿入する（[FuelInjector]）。残りは毎回グローバルを読み書きする */
    INJECTED_GLOBAL("A: 挿入（グローバル）"),

    /** A: 読み込み時に計測コードを挿入する（[FuelInjector]）。関数の中ではローカル変数に写す */
    INJECTED("A: 挿入（ローカル）"),

    /** B: ランタイム側で命令を数える。Endive で使える口はインタプリタの `ExecutionListener` だけ */
    LISTENER("B: ランタイムで数える"),
}

/** 測る WASM モジュール。 */
enum class Wasm(val resource: String) {
    BENCH("/kibicomputers/wasm/bench.wasm"),
    LUA("/kibicomputers/wasm/lua.wasm"),
    ;

    val bytes: ByteArray by lazy { requireNotNull(Wasm::class.java.getResourceAsStream(resource)) { "$resource がない" }.use { it.readBytes() } }
}

/**
 * 燃料の残り。ホストから見た1台分の計測器。
 *
 * 残りが負になると [onExhausted] が呼ばれ、新しい残りを返す（次の tick まで止まる、例外で止める、など）。
 * A では残りは WASM のグローバルにあり、確かめる点（関数の入口と `loop` の先頭）でだけ負かどうかを見る。
 * B では命令ごとに見る。
 */
class FuelMeter(var onExhausted: Refuel = Refuel { it }) {
    fun interface Refuel {
        /** [remaining]（負。行き過ぎた分）を受け取り、新しい残りを返す */
        fun refuel(remaining: Long): Long
    }

    /** これまでに配った燃料の合計（最初の分を含む） */
    var granted: Long = 0
        private set

    /** 燃料切れの回数 */
    var exhaustions: Long = 0
        private set

    /** 燃料切れに気づいたときの行き過ぎの最大（命令数） */
    var maxOvershoot: Long = 0
        private set

    /** B の残り（A ではグローバルにある） */
    internal var counter: Long = 0

    fun start(initial: Long) {
        granted = initial
        counter = initial
        exhaustions = 0
        maxOvershoot = 0
    }

    internal fun exhausted(remaining: Long): Long {
        exhaustions++
        maxOvershoot = maxOf(maxOvershoot, -remaining)
        val next = onExhausted.refuel(remaining)
        granted += next - remaining
        return next
    }
}

/** 1つのモジュールを、1つの実行方式・計測方式で動かす準備をしたもの。 */
class Engine(val wasm: Wasm, val runtime: Runtime, val metering: Metering) {
    val injected: Boolean get() = metering == Metering.INJECTED || metering == Metering.INJECTED_GLOBAL

    private val module = Parser.parse(
        when (metering) {
            Metering.INJECTED -> FuelInjector.inject(wasm.bytes, FuelInjector.Storage.LOCAL).bytes
            Metering.INJECTED_GLOBAL -> FuelInjector.inject(wasm.bytes, FuelInjector.Storage.GLOBAL).bytes
            else -> wasm.bytes
        },
    )

    // JVM のメソッドの上限を超える関数があれば失敗させる（インタプリタに回ると測定が混ざるため）
    private val compiled = if (runtime == Runtime.COMPILER) {
        MachineFactoryCompiler.builder(module).withInterpreterFallback(InterpreterFallback.FAIL).compileModule()
    } else {
        null
    }

    /** インスタンスを作る。[meter] の残りは [FuelMeter.start] で決めておく */
    fun newInstance(meter: FuelMeter, extra: List<HostFunction> = emptyList()): Instance {
        val builder = if (compiled != null) Instance.builder(compiled) else Instance.builder(module)
        val imports = ImportValues.builder()
        for (f in extra) imports.addFunction(f)
        if (injected) {
            imports.addFunction(
                HostFunction(FuelInjector.IMPORT_MODULE, FuelInjector.IMPORT_NAME, FunctionType.of(listOf(ValType.I64), listOf(ValType.I64))) { _, args ->
                    longArrayOf(meter.exhausted(args[0]))
                },
            )
        }
        if (metering == Metering.LISTENER) {
            // インスタンスごとに1つ。命令を1つ実行するたびに呼ばれる（コンパイルした実行では呼ばれない）
            builder.withUnsafeExecutionListener { _, _ ->
                if (--meter.counter < 0) meter.counter = meter.exhausted(meter.counter)
            }
        }
        val instance = builder.withImportValues(imports.build()).withStart(false).build()
        if (injected) {
            instance.exports().global(FuelInjector.EXPORT_GLOBAL).setValue(meter.counter)
        }
        return instance
    }

    /** 同じインスタンスで測り直すため、燃料を [initial] に戻す */
    fun restart(instance: Instance, meter: FuelMeter, initial: Long) {
        meter.start(initial)
        if (injected) instance.exports().global(FuelInjector.EXPORT_GLOBAL).setValue(initial)
    }

    /** 実行後の残り */
    fun remaining(instance: Instance, meter: FuelMeter): Long = when (metering) {
        Metering.INJECTED, Metering.INJECTED_GLOBAL -> instance.exports().global(FuelInjector.EXPORT_GLOBAL).getValue()
        else -> meter.counter
    }

    /** 使った燃料（命令数）。計測しない方式では 0 */
    fun used(instance: Instance, meter: FuelMeter): Long = if (metering == Metering.NONE) 0 else meter.granted - remaining(instance, meter)

    /**
     * `lua -` でスクリプトを標準入力から読ませて最後まで実行し、標準出力と使った燃料を返す。
     * Lua はハッシュの種に時刻（`time()`）を混ぜるので、時計と乱数は固定する（でないと実行のたびに命令数が変わる）
     */
    fun runLua(script: String, meter: FuelMeter): Pair<String, Long> {
        check(wasm == Wasm.LUA)
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val options = WasiOptions.builder()
            .withArguments(listOf("lua", "-"))
            .withStdin(ByteArrayInputStream(script.toByteArray()))
            .withStdout(stdout)
            .withStderr(stderr)
            .withClock(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))
            .withRandom(Random(0))
            .build()
        WasiPreview1.builder().withOptions(options).build().use { wasi ->
            val instance = newInstance(meter, wasi.toHostFunctions().toList())
            val exit = try {
                instance.export("_start").apply()
                0
            } catch (e: WasiExitException) {
                e.exitCode()
            }
            check(exit == 0) { "lua が $exit で終了した: $stderr" }
            return stdout.toString(Charsets.UTF_8) to used(instance, meter)
        }
    }
}

/** CPU ベンチマークの関数を呼ぶ */
fun Instance.call(name: String, vararg args: Long): Long = export(name).apply(*args)[0]

fun luaScript(name: String): String = requireNotNull(Engine::class.java.getResourceAsStream("/bench/$name.lua")) { "$name.lua がない" }
    .use { it.readBytes().toString(Charsets.UTF_8) }
