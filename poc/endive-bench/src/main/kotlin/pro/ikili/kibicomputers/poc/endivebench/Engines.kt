package pro.ikili.kibicomputers.poc.endivebench

import pro.ikili.kibicomputers.poc.endivebench.aot.Bench
import pro.ikili.kibicomputers.poc.endivebench.aot.Lua
import run.endive.compiler.InterpreterFallback
import run.endive.compiler.MachineFactoryCompiler
import run.endive.runtime.ImportValues
import run.endive.runtime.Instance
import run.endive.runtime.Machine
import run.endive.wasi.WasiExitException
import run.endive.wasi.WasiOptions
import run.endive.wasi.WasiPreview1
import run.endive.wasm.Parser
import run.endive.wasm.WasmModule
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.function.Function

/** 比べる実行方式。 */
enum class Mode(val label: String) {
    /** Endive の既定。WASM の命令を1つずつ解釈する */
    INTERPRETER("インタプリタ"),

    /** 実行時に WASM を JVM のバイトコードに変換する（`run.endive:compiler`、ASM を使う） */
    RUNTIME_COMPILER("ランタイムコンパイラ"),

    /** ビルド時に WASM を JVM のバイトコードに変換しておく（build-time compiler） */
    AOT("ビルド時AOT"),

    /** ビルド時に Cranelift でネイティブコードにしておく（Redline、実験的。jffi か Panama で呼ぶ） */
    REDLINE("Redline"),
}

/** 測る WASM モジュール。 */
enum class Wasm(val resource: String) {
    BENCH("/kibicomputers/wasm/bench.wasm"),
    LUA("/kibicomputers/wasm/lua.wasm"),
    ;

    fun bytes(): ByteArray = requireNotNull(Wasm::class.java.getResourceAsStream(resource)) { "$resource がない" }.use { it.readBytes() }

    /** この方式で動かせるか。Redline は例外処理（exnref）に未対応なので、Lua は動かせない */
    fun supports(mode: Mode): Boolean = when (mode) {
        Mode.REDLINE -> this == BENCH && Bench.nativeProvider().isPresent
        else -> true
    }
}

/**
 * 1つのモジュールを1つの方式で動かす準備をしたもの。
 * 作るまでの時間（パース・ランタイムコンパイル・クラスの読み込み）が「準備」、[newInstance] がインスタンス生成。
 */
class Engine(val wasm: Wasm, val mode: Mode) {
    private val newBuilder: () -> Instance.Builder

    init {
        newBuilder = when (mode) {
            Mode.INTERPRETER -> {
                val module = Parser.parse(wasm.bytes())
                ({ Instance.builder(module) })
            }
            Mode.RUNTIME_COMPILER -> {
                val module = Parser.parse(wasm.bytes())
                // JVM のメソッドの上限を超える関数があれば失敗させる（インタプリタに回ると測定が混ざるため）。
                // bench.wasm と lua.wasm には該当する関数はない
                val compiled = MachineFactoryCompiler.builder(module)
                    .withInterpreterFallback(InterpreterFallback.FAIL)
                    .compileModule()
                ({ Instance.builder(compiled) })
            }
            Mode.AOT -> when (wasm) {
                Wasm.BENCH -> aotBuilder(Bench.load(), Bench::create)
                Wasm.LUA -> aotBuilder(Lua.load(), Lua::create)
            }
            Mode.REDLINE -> {
                check(wasm.supports(mode)) { "Redline は $wasm を動かせない" }
                ({ Bench.builder() })
            }
        }
    }

    private fun aotBuilder(module: WasmModule, create: Function<Instance, Machine>): () -> Instance.Builder = { Instance.builder(module).withMachineFactory(create) }

    /** インスタンスを作る。WASI の `_start` は呼ばない（[runLua] で呼ぶ） */
    fun newInstance(imports: ImportValues? = null): Instance {
        val builder = newBuilder().withStart(false)
        if (imports != null) builder.withImportValues(imports)
        return builder.build()
    }

    /** `lua -` でスクリプトを標準入力から読ませて実行し、標準出力を返す。（`-e` はスクリプトが `-` で始まると受け付けない） */
    fun runLua(script: String): String {
        check(wasm == Wasm.LUA)
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val options = WasiOptions.builder()
            .withArguments(listOf("lua", "-"))
            .withStdin(ByteArrayInputStream(script.toByteArray()))
            .withStdout(stdout)
            .withStderr(stderr)
            .build()
        WasiPreview1.builder().withOptions(options).build().use { wasi ->
            val instance = newInstance(ImportValues.builder().addFunction(*wasi.toHostFunctions()).build())
            val exit = try {
                instance.export("_start").apply()
                0
            } catch (e: WasiExitException) {
                e.exitCode()
            }
            check(exit == 0) { "lua が $exit で終了した: $stderr" }
        }
        return stdout.toString(Charsets.UTF_8)
    }
}

/** CPU ベンチマークの関数を呼ぶ。戻り値は確認用（i32 / f64 をビット列のまま返す） */
fun Instance.call(name: String, vararg args: Long): Long = export(name).apply(*args)[0]

/** Lua のベンチマークのスクリプト（`src/main/resources/bench/`）。 */
fun luaScript(name: String): String = requireNotNull(Engine::class.java.getResourceAsStream("/bench/$name.lua")) { "$name.lua がない" }
    .use { it.readBytes().toString(Charsets.UTF_8) }
