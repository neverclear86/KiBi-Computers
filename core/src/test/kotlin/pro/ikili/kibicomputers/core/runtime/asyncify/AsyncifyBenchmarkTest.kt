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
 * lua.wasm を、燃料の計測コードの挿入だけ・挿入と変換（`call_indirect` を型だけで絞る前の解析 `typeOnlyIndirect`）・
 * 挿入と変換（本番の形）の 3 つで読み（すべてを退避する形 [AsyncifyDebug.SAVE_ALL] との比べは #922 で ADR 0329 に書いた）、待たない計算（Lua の数値のループ・`table.sort`・文字列の処理）の時間を、インタプリタとランタイムコンパイラで比べる。
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
        // 本番の形（Instrumentation）と同じく、課金の位置を渡す（#389）
        fun transform(debug: AsyncifyDebug, typeOnly: Boolean) = Asyncify.transform(
            metered.bytes, WasiP1.WAIT_IMPORTS, Instrumentation.YIELD_IMPORT, metered::originalFunc,
            debug = debug, fuelSites = metered.sites, typeOnlyIndirect = typeOnly,
        )
        val variants = linkedMapOf<String, ByteArray>("挿入だけ" to metered.bytes)
        val forms = linkedMapOf(
            "型だけで絞る（#338 の解析の前）" to (AsyncifyDebug.NONE to true),
            "参照で絞る（本番）" to (AsyncifyDebug.NONE to false),
        )
        for ((label, form) in forms) {
            val (debug, typeOnly) = form
            repeat(3) { transform(debug, typeOnly) }
            val times = (1..rounds).map {
                val t0 = System.nanoTime()
                transform(debug, typeOnly)
                System.nanoTime() - t0
            }
            val r = transform(debug, typeOnly)
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

    /**
     * 同梱の標準 OS（`os.zip`）のコマンドと、テスト用の p1 の WASM について、`call_indirect` を参照で絞る解析（#338）で、
     * 変換する関数とコードがどれだけ減るかを出す（時間は測らない）
     */
    @Test
    fun `標準OSのコマンドで、参照で絞る解析の効果を数える`() {
        val modules = linkedMapOf<String, ByteArray>()
        val zip = requireNotNull(javaClass.getResourceAsStream("/kibicomputers/images/os.zip")) { "os.zip がリソースにない" }
        java.util.zip.ZipInputStream(zip).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val bytes = z.readBytes()
                if (!e.isDirectory && bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 'a'.code.toByte()) modules["os:${e.name}"] = bytes
            }
        }
        java.io.File(requireNotNull(javaClass.getResource("/kibicomputers/wasm/prompt-wasi.wasm")).toURI()).parentFile
            .listFiles { it -> it.name.endsWith(".wasm") }!!.sortedBy { it.name }.forEach { modules["test:${it.name}"] = it.readBytes() }
        var before = 0
        var after = 0
        var codeBefore = 0L
        var codeAfter = 0L
        for ((name, wasm) in modules) {
            val metered = try {
                FuelInjector.inject(wasm)
            } catch (e: Exception) {
                println("#338 参照 $name: 挿入できない（${e.message}）")
                continue
            }
            fun transform(typeOnly: Boolean) = try {
                Asyncify.transform(metered.bytes, WasiP1.WAIT_IMPORTS, Instrumentation.YIELD_IMPORT, metered::originalFunc, fuelSites = metered.sites, typeOnlyIndirect = typeOnly)
            } catch (e: AsyncifyUnsupported) {
                null
            }
            val a = transform(true) ?: continue
            val b = transform(false) ?: continue
            before += a.instrumented
            after += b.instrumented
            codeBefore += a.codeAfter
            codeAfter += b.codeAfter
            if (a.instrumented != b.instrumented) {
                println("#338 参照 $name: 変換 ${a.instrumented} → ${b.instrumented} / ${a.functions} 関数、コード ${a.codeAfter} → ${b.codeAfter} バイト（変換前 ${a.codeBefore}）")
            }
        }
        println("#338 参照 合計（${modules.size} モジュール）: 変換 $before → $after 関数、コード $codeBefore → $codeAfter バイト")
    }

    private fun variantName(variants: Map<String, ByteArray>, bytes: ByteArray) = variants.entries.first { it.value === bytes }.key
}
