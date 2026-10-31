package pro.ikili.kibicomputers.poc.cm

import run.endive.cm.parser.ComponentParser
import run.endive.cm.runtime.ComponentStore
import run.endive.cm.tools.ComponentValidate
import run.endive.cm.types.WasmComponent
import run.endive.cm.wasi.p2.WasiPreview2
import run.endive.runtime.CompiledModule
import run.endive.compiler.InterpreterFallback
import run.endive.compiler.MachineFactoryCompiler
import run.endive.runtime.ImportValues
import run.endive.runtime.Instance
import run.endive.wasi.WasiExitException
import run.endive.wasi.WasiOptions
import run.endive.wasi.WasiPreview1
import run.endive.wasm.Parser
import run.endive.wasm.WasmModule
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.IdentityHashMap

// #261 の測定。p1 のモジュール（lua.wasm）を
//   A: p1 のホスト関数で直接動かす（Endive の WasiPreview1）
//   B: 公式の wasi_snapshot_preview1 アダプタでコンポーネントに包み、p2 の実装で動かす（endive-cm の WasiPreview2）
// で比べる。どちらもコアモジュールはランタイムコンパイラ（#29）で動かし、コンパイルは1回だけ（結果を使い回す）。
//
// 1. 包む: wasm-tools component new --adapt の時間・大きさ、検証・パースの時間
// 2. インスタンス生成の時間（_start を呼ばない）
// 3. 実行時間（毎回インスタンスを作って lua - を最後まで動かす）。syscalls.lua はホスト呼び出しの多いもの
// 4. ホスト呼び出しの数（A の p1 呼び出し数）
// 5. 状態: 実行後のコアインスタンス・線形メモリ・グローバルの数
// 6. p1 の独自 import（#73 の「p1 向けに同じ機能を import でも出す」）を持つモジュールを包めるか

private val luaScripts = listOf("fib", "tables", "strings")

fun main() {
    val lua = File(requireNotNull(System.getProperty("kibi.luaWasm")) { "kibi.luaWasm がない" }).readBytes()
    val adapterFile = File(requireNotNull(System.getProperty("kibi.adapter")) { "kibi.adapter がない" })
    val adapter = adapterFile.readBytes()
    println("java.version=${System.getProperty("java.version")}")
    println("lua.wasm ${"%.1f".format(lua.size / 1024.0)} KiB / アダプタ ${adapterFile.name} ${"%.1f".format(adapter.size / 1024.0)} KiB")

    println()
    println("== 1. 包む（wasm-tools component new --adapt）")
    var component = ByteArray(0)
    val adaptMs = (1..3).map { timeMs { component = WasmTools.adapt(lua, adapter) } }
    println("component new: 1回目 %.0f ms、2・3回目 %.0f / %.0f ms".format(adaptMs[0], adaptMs[1], adaptMs[2]))
    println("コンポーネント ${"%.1f".format(component.size / 1024.0)} KiB（lua.wasm より +${"%.1f".format((component.size - lua.size) / 1024.0)} KiB）")
    val validateMs = (1..3).map { timeMs { ComponentValidate.validate(ByteArrayInputStream(component), "cm-implements") } }
    println("validate: 1回目 %.0f ms、2・3回目 %.0f / %.0f ms".format(validateMs[0], validateMs[1], validateMs[2]))
    var parsed: WasmComponent? = null
    val parseMs = median(3, 9) { parsed = parseComponent(component) }
    val cm = parsed!!
    println("コンポーネントのパース（中央値）: %.1f ms".format(parseMs))
    val p1ParseMs = median(3, 9) { Parser.parse(lua) }
    println("比較: lua.wasm のパース（中央値）: %.1f ms".format(p1ParseMs))

    // コンパイル（1回だけ）
    val luaModule = Parser.parse(lua)
    var compiledLua: CompiledModule? = null
    val compileA = timeMs { compiledLua = compile(luaModule) }
    val cache = IdentityHashMap<WasmModule, CompiledModule>()
    val compiledStore = {
        ComponentStore.withCoreInstances { m -> Instance.builder(cache.getOrPut(m) { compile(m) }).withStart(false) }
    }
    val compileB = timeMs { runB(cm, compiledStore(), "") }
    println("ランタイムコンパイル: A（lua.wasm）%.0f ms / B（コンポーネント内のコアモジュール ${cache.size} 個、初回の実行を含む）%.0f ms".format(compileA, compileB))
    println("B のコアモジュール: " + cache.keys.joinToString { "関数 ${it.functionSection().functionCount()}" })

    val engineA = { imports: ImportValues -> Instance.builder(compiledLua!!).withImportValues(imports).withStart(false).build() }

    println()
    println("== 2. インスタンス生成（_start を呼ばない。中央値）")
    val instA = median(10, 41) {
        WasiPreview1.builder().withOptions(WasiOptions.builder().build()).build().use { wasi ->
            engineA(ImportValues.builder().addFunction(*wasi.toHostFunctions()).build())
        }
    }
    val instB = median(10, 41) {
        WasiPreview2.builder().build().use { wasi -> compiledStore().use { wasi.instantiate(it, cm) } }
    }
    println("A: %.3f ms / B: %.3f ms（%.1f 倍）".format(instA, instB, instB / instA))

    println()
    println("== 3. 実行時間（毎回インスタンスを作って lua - を最後まで実行。中央値）")
    println("| スクリプト | A: p1 を直接 [ms] | B: アダプタ + p2 [ms] | B / A |")
    println("| --- | ---: | ---: | ---: |")
    val scripts = listOf("（空のスクリプト）" to "") +
        luaScripts.map { "$it.lua" to resource("/bench/$it.lua") } +
        listOf("syscalls.lua" to resource("/p1/syscalls.lua"))
    for ((name, script) in scripts) {
        val outA = runA(engineA, script).first
        val outB = runB(cm, compiledStore(), script)
        check(outA == outB) { "$name: 出力が違う A=$outA B=$outB" }
        val a = median(5, 21) { runA(engineA, script) }
        val b = median(5, 21) { runB(cm, compiledStore(), script) }
        println("| $name | %.2f | %.2f | %.2f |".format(a, b, b / a))
    }

    println()
    println("== 4. ホスト呼び出しの数（A の p1 呼び出し）")
    for ((name, script) in scripts) {
        val calls = runA(engineA, script).second
        println("$name: 合計 ${calls.values.sum()} 回 " + calls.entries.sortedByDescending { it.value }.take(4).joinToString { "${it.key}=${it.value}" })
    }

    println()
    println("== 5. 状態（実行後）")
    WasiPreview1.builder().withOptions(WasiOptions.builder().withArguments(listOf("lua", "-")).withStdin(ByteArrayInputStream(ByteArray(0))).build()).build().use { wasi ->
        val inst = engineA(ImportValues.builder().addFunction(*wasi.toHostFunctions()).build())
        runCatching { inst.export("_start").apply() }
        println("A: コアインスタンス 1 個、線形メモリ ${inst.memory().pages()} ページ、グローバル ${inst.globalCount()} 個（ほかはホストの fd 表）")
    }
    val store = compiledStore()
    WasiPreview2.builder().withArguments(listOf("lua", "-")).withStdin(ByteArrayInputStream(ByteArray(0))).withStdout(ByteArrayOutputStream()).build().use { wasi ->
        val world = wasi.instantiate(store, cm)
        runCatching { callRun(world.instance()) }
        val cores = store.coreInstances()
        val memories = cores.mapNotNull { runCatching { it.memory() }.getOrNull() }.distinct()
        println(
            "B: コアインスタンス ${cores.size} 個、線形メモリ ${memories.size} 個（${memories.joinToString { "${it.pages()} ページ" }}）、" +
                "グローバル ${cores.map { it.globalCount() }}、テーブル ${cores.count { runCatching { it.table(0) }.isSuccess }} 個を持つ",
        )
    }
    store.close()

    println()
    println("== 6. p1 の独自 import（#73）を持つモジュールを包む")
    for (probe in CustomImportProbes.all) {
        val r = runCatching {
            var module = WasmTools.parseCore(probe.wat)
            if (probe.wit != null) module = WasmTools.embed(module, probe.wit, "probe")
            WasmTools.adapt(module, adapter)
        }
        val detail = r.fold(
            { "包めた（${it.size} バイト）" },
            { "包めない ← " + it.message?.lineSequence()?.map { l -> l.trim() }?.filter { l -> l.isNotEmpty() && l != "Caused by:" }?.joinToString(" / ") },
        )
        println("${probe.label}: $detail")
    }
}

/** A: lua.wasm を p1 のホスト関数で直接動かす。標準出力と、p1 の関数ごとの呼び出し数を返す */
private fun runA(engine: (ImportValues) -> Instance, script: String): Pair<String, Map<String, Int>> {
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val options = WasiOptions.builder()
        .withArguments(listOf("lua", "-"))
        .withStdin(ByteArrayInputStream(script.toByteArray()))
        .withStdout(stdout)
        .withStderr(stderr)
        .build()
    val calls = HashMap<String, Int>()
    WasiPreview1.builder().withOptions(options).build().use { wasi ->
        val functions = wasi.toHostFunctions().map { f ->
            run.endive.runtime.HostFunction(f.module(), f.name(), f.functionType()) { inst, args ->
                calls.merge(f.name(), 1, Int::plus)
                f.handle().apply(inst, *args)
            }
        }
        val instance = engine(ImportValues.builder().addFunction(*functions.toTypedArray()).build())
        val exit = try {
            instance.export("_start").apply()
            0
        } catch (e: WasiExitException) {
            e.exitCode()
        }
        check(exit == 0) { "lua (A) が $exit で終了した: $stderr" }
    }
    return stdout.toString(Charsets.UTF_8) to calls
}

/** B: アダプタで包んだコンポーネントを p2 の実装で動かす。標準出力を返す */
private fun runB(component: WasmComponent, store: ComponentStore, script: String): String {
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val exit = WasiPreview2.builder()
        .withArguments(listOf("lua", "-"))
        .withStdin(ByteArrayInputStream(script.toByteArray()))
        .withStdout(stdout)
        .withStderr(stderr)
        .build()
        .use { wasi -> store.use { wasi.run(it, component) } }
    check(exit == 0) { "lua (B) が $exit で終了した: $stderr" }
    return stdout.toString(Charsets.UTF_8)
}

private fun callRun(instance: run.endive.cm.runtime.ComponentInstance) {
    val name = instance.exportNames().single { it.startsWith("wasi:cli/run@0.2") }
    instance.exportedInstance(name).export("run").apply()
}

private fun compile(module: WasmModule): CompiledModule =
    MachineFactoryCompiler.builder(module).withInterpreterFallback(InterpreterFallback.FAIL).compileModule()

private fun parseComponent(bytes: ByteArray): WasmComponent =
    ComponentParser.builder().withValidation(false).build().parse { ByteArrayInputStream(bytes) }

private fun resource(path: String): String =
    requireNotNull(P1AdapterResources::class.java.getResourceAsStream(path)) { "$path がない" }.use { it.readBytes().toString(Charsets.UTF_8) }

private object P1AdapterResources

private fun timeMs(block: () -> Unit): Double {
    val t = System.nanoTime()
    block()
    return (System.nanoTime() - t) / 1e6
}

private fun median(warmup: Int, runs: Int, block: () -> Unit): Double {
    repeat(warmup) { block() }
    return (1..runs).map { timeMs(block) }.sorted()[runs / 2]
}

/**
 * p1 の独自 import を持つ最小のモジュール。#73 では、WIT の `kibi:*` と同じ機能を p1 向けに core の import でも出す。
 * その形のモジュールを、アダプタでコンポーネントにできるか（できなければ B では #73 の p1 向け import を出せない）。
 */
private object CustomImportProbes {
    private fun module(extraImport: String, call: String) = """
        (module
          (import "wasi_snapshot_preview1" "proc_exit" (func ${'$'}exit (param i32)))
          $extraImport
          (memory (export "memory") 1)
          (func (export "_start") $call (call ${'$'}exit (i32.const 0))))
    """.trimIndent()

    class Probe(val label: String, val wat: String, val wit: String? = null)

    private const val BEEP_WIT = """
        package kibi:computer@0.1.0;
        interface beep { beep: func(freq: u32); }
        world probe { import beep; }
    """

    val all = listOf(
        Probe("基準（wasi_snapshot_preview1 だけ）", module("", "")),
        Probe(
            "p1 の独自モジュール名（kibi_computer.beep）",
            module("(import \"kibi_computer\" \"beep\" (func ${'$'}beep (param i32)))", "(call ${'$'}beep (i32.const 440))"),
        ),
        Probe(
            "WIT のインターフェース名（kibi:computer/beep@0.1.0）、WIT の埋め込みなし",
            module("(import \"kibi:computer/beep@0.1.0\" \"beep\" (func ${'$'}beep (param i32)))", "(call ${'$'}beep (i32.const 440))"),
        ),
        Probe(
            "WIT のインターフェース名、WIT を埋め込む（component embed）",
            module("(import \"kibi:computer/beep@0.1.0\" \"beep\" (func ${'$'}beep (param i32)))", "(call ${'$'}beep (i32.const 440))"),
            BEEP_WIT.trimIndent(),
        ),
    )
}
