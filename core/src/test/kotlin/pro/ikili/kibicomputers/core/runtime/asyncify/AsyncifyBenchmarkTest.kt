package pro.ikili.kibicomputers.core.runtime.asyncify

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import pro.ikili.kibicomputers.core.runtime.Instrumentation
import pro.ikili.kibicomputers.core.runtime.TestWasiP1
import pro.ikili.kibicomputers.core.runtime.WasmAsyncStack
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.endive.isCompiled
import pro.ikili.kibicomputers.core.runtime.fuel.FuelInjector
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1
import run.endive.wasm.Parser

/**
 * 待機点への変換の速さを測る（#338。ADR 0329 の「#338 での改善」）。ふつうの `:core:test` では動かさない
 * （`-Pkibi.asyncifyBenchmark=true` のときだけ）。
 *
 * lua.wasm を、燃料の計測コードの挿入だけ・挿入と変換（すべてを退避する今までの形 [AsyncifyDebug.SAVE_ALL]）・挿入と変換（生きているものだけ）の
 * 3 つで読み、待たない計算（Lua の数値のループ・`table.sort`・文字列の処理）の時間を、インタプリタとランタイムコンパイラで比べる。
 * 変換の大きさ（退避するローカル、コード、いちばん大きい関数の命令の数）と、変換・読み込みの時間も出す
 */
@EnabledIfSystemProperty(named = "kibi.asyncifyBenchmark", matches = "true")
class AsyncifyBenchmarkTest {
    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    private val scripts = linkedMapOf(
        "数値のループ（100 万回）" to "local s = 0 for i = 1, 1000000 do s = s + i % 7 end print(s)",
        "table.sort（20 万要素）" to "local t = {} for i = 1, 200000 do t[i] = (i * 7919) % 100003 end table.sort(t) print(t[1])",
        "文字列（3 万回）" to "local u = '' for i = 1, 30000 do u = string.format('%s%d', string.sub(u, -20), i) end print(#u)",
    )

    private val noStack = object : WasmAsyncStack {
        override fun push(value: Long) = throw AssertionError("巻き戻さない")

        override fun pop(): Long = throw AssertionError("巻き戻さない")
    }

    /** `lua -e <script>` を動かした時間（ns）。巻き戻さない */
    private fun timeLua(module: WasmModule, script: String): Long {
        var nanos = 0L
        var failure: Throwable? = null
        val thread = Thread(null, {
            try {
                val wasi = TestWasiP1(listOf("lua", "-e", script))
                val t0 = System.nanoTime()
                module.instantiate(wasi.config(module) { fuel(Long.MAX_VALUE / 2, null); if (module.isAsyncified) asyncStack(noStack) }).use { instance ->
                    try {
                        instance.call("_start")
                    } catch (e: TestWasiP1.Exit) {
                        assertEquals(0, e.code, wasi.stderr.toString(Charsets.UTF_8))
                    }
                }
                nanos = System.nanoTime() - t0
            } catch (e: Throwable) {
                failure = e
            }
        }, "asyncify-benchmark", 16L * 1024 * 1024)
        thread.start()
        thread.join()
        failure?.let { throw it }
        return nanos
    }

    private fun maxInstructions(wasm: ByteArray): Int {
        val code = Parser.parse(wasm).codeSection()
        return (0 until code.functionBodyCount()).maxOf { code.getFunctionBody(it).instructions().size }
    }

    private fun median(xs: List<Long>): Long = xs.sorted()[xs.size / 2]

    @Test
    fun `lua_wasmの変換の大きさと待たない計算の速さを測る`() {
        val lua = resource("lua.wasm")
        val rounds = Integer.getInteger("kibi.asyncifyBenchmark.rounds", 5)
        // 変換の時間（温めてから）
        val metered = FuelInjector.inject(lua)
        fun transform(debug: AsyncifyDebug) =
            Asyncify.transform(metered.bytes, WasiP1.WAIT_IMPORTS, Instrumentation.YIELD_IMPORT, metered::originalFunc, debug = debug)
        val variants = linkedMapOf<String, ByteArray>("挿入だけ" to metered.bytes)
        for (debug in listOf(AsyncifyDebug.SAVE_ALL, AsyncifyDebug.NONE)) {
            repeat(3) { transform(debug) }
            val times = (1..rounds).map {
                val t0 = System.nanoTime()
                transform(debug)
                System.nanoTime() - t0
            }
            val r = transform(debug)
            val label = if (debug == AsyncifyDebug.SAVE_ALL) "すべて退避（前）" else "生きているものだけ（後）"
            variants[label] = r.bytes
            println(
                "#338 $label: 変換 ${r.instrumented} / ${r.functions} 関数、止まりうる呼び出し ${r.sites}（燃料切れ ${r.yieldSites}）、" +
                    "退避するローカル ${r.savedLocals} / ${r.allLocals}（関数あたり最大 ${r.maxLocals}）、コード ${r.codeBefore} → ${r.codeAfter} バイト、" +
                    "モジュール ${r.bytes.size} バイト、最大の関数の命令 ${maxInstructions(r.bytes)}、変換の時間 ${median(times) / 1_000_000} ms",
            )
        }
        println("#338 挿入だけ: モジュール ${metered.bytes.size} バイト、最大の関数の命令 ${maxInstructions(metered.bytes)}")
        for (compile in listOf(false, true)) {
            val runtime = EndiveRuntime(compile = compile)
            val modules = variants.mapValues { (_, bytes) ->
                repeat(2) { runtime.load(bytes) }
                val t0 = System.nanoTime()
                val m = runtime.load(bytes)
                println("#338 ${if (compile) "コンパイラ" else "インタプリタ"} ${variantName(variants, bytes)}: 読み込み ${(System.nanoTime() - t0) / 1_000_000} ms、コンパイルした ${isCompiled(m)}")
                m
            }
            if (compile) modules.values.forEach { assertTrue(isCompiled(it)) }
            for ((name, script) in scripts) {
                // 温める
                modules.values.forEach { timeLua(it, script) }
                val times = modules.mapValues { (_, m) -> median((1..rounds).map { timeLua(m, script) }) }
                val base = times.getValue("挿入だけ").toDouble()
                println(
                    "#338 ${if (compile) "コンパイラ" else "インタプリタ"} $name: " +
                        times.entries.joinToString("、") { (k, v) -> "$k ${v / 1_000_000} ms（%.2f 倍）".format(v / base) },
                )
            }
        }
    }

    private fun variantName(variants: Map<String, ByteArray>, bytes: ByteArray) = variants.entries.first { it.value === bytes }.key
}
