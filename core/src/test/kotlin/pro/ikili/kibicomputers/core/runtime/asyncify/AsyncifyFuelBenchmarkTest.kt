package pro.ikili.kibicomputers.core.runtime.asyncify

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import pro.ikili.kibicomputers.core.runtime.Instrumentation
import pro.ikili.kibicomputers.core.runtime.TestWasiP1
import pro.ikili.kibicomputers.core.runtime.WasmAsyncStack
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.fuel.AsyncifyFuelWeight
import pro.ikili.kibicomputers.core.runtime.fuel.FuelInjector
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1
import java.lang.management.ManagementFactory

/**
 * 変換したモジュールの、燃料 1 あたりの CPU 時間を測る（#389。ADR 0329 の「#389: 変換が足すコードの見込み」）。
 * ふつうの `:core:test` では動かさない（`-Pkibi.asyncifyFuelBenchmark=true` のときだけ）。CI では時間を検査しない（揺れて落ちるため）。
 *
 * lua.wasm を、燃料の計測コードの挿入だけ（基準）と、挿入と変換（段1）を見込みの倍率を変えて読み、待たない計算
 * （Lua の数値のループ・`table.sort`・文字列の処理）の、使った燃料とスレッドの CPU 時間を比べる。出すのは、燃料 1 あたりの CPU 時間の比
 * R = 変換あり / 挿入だけ（1 に近いほど、燃料で tick あたりの CPU を抑える前提がそろう）。本番と同じランタイムコンパイラで動かす
 */
@EnabledIfSystemProperty(named = "kibi.asyncifyFuelBenchmark", matches = "true")
class AsyncifyFuelBenchmarkTest {
    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    private val scripts = linkedMapOf(
        "数値のループ" to "local s = 0 for i = 1, 1000000 do s = s + i % 7 end print(s)",
        "table.sort" to "local t = {} for i = 1, 200000 do t[i] = (i * 7919) % 100003 end table.sort(t) print(t[1])",
        "文字列" to "local u = '' for i = 1, 30000 do u = string.format('%s%d', string.sub(u, -20), i) end print(#u)",
    )

    private val noStack = object : WasmAsyncStack {
        override fun push(value: Long) = throw AssertionError("巻き戻さない")

        override fun pop(): Long = throw AssertionError("巻き戻さない")
    }

    private class Run(val cpuNanos: Long, val fuel: Long)

    /** `lua -e <script>` を動かした CPU 時間と、使った燃料。巻き戻さない */
    private fun runLua(module: WasmModule, script: String): Run {
        var result: Run? = null
        var failure: Throwable? = null
        val thread = Thread(null, {
            try {
                val bean = ManagementFactory.getThreadMXBean()
                val wasi = TestWasiP1(listOf("lua", "-e", script))
                val initial = Long.MAX_VALUE / 2
                module.instantiate(wasi.config(module) { fuel(initial, null); if (module.isAsyncified) asyncStack(noStack) }).use { instance ->
                    val t0 = bean.currentThreadCpuTime
                    try {
                        instance.call("_start")
                    } catch (e: TestWasiP1.Exit) {
                        assertEquals(0, e.code, wasi.stderr.toString(Charsets.UTF_8))
                    }
                    result = Run(bean.currentThreadCpuTime - t0, initial - instance.fuel)
                }
            } catch (e: Throwable) {
                failure = e
            }
        }, "asyncify-fuel-benchmark", 16L * 1024 * 1024)
        thread.start()
        thread.join()
        failure?.let { throw it }
        return result!!
    }

    private fun median(xs: List<Long>): Long = xs.sorted()[xs.size / 2]

    @Test
    fun `変換したlua_wasmの燃料1あたりのCPU時間を挿入だけのものと比べる`() {
        val lua = resource("lua.wasm")
        val rounds = Integer.getInteger("kibi.asyncifyFuelBenchmark.rounds", 5)
        val metered = FuelInjector.inject(lua)
        val weights = linkedMapOf(
            "見込まない" to AsyncifyFuelWeight.NONE,
            "×1.5" to AsyncifyFuelWeight(3, 2),
            "×1.75" to AsyncifyFuelWeight(7, 4),
            "×2.5" to AsyncifyFuelWeight(5, 2),
            "本番（既定 ${AsyncifyFuelWeight.DEFAULT}）" to AsyncifyFuelWeight.DEFAULT,
        )
        val variants = linkedMapOf<String, ByteArray>("挿入だけ" to metered.bytes)
        for ((name, w) in weights) {
            val sites = metered.sites.withWeight(w)
            fun transform() = Asyncify.transform(metered.bytes, WasiP1.WAIT_IMPORTS, Instrumentation.YIELD_IMPORT, metered::originalFunc, fuelSites = sites)
            repeat(2) { transform() }
            val times = (1..rounds).map {
                val t0 = System.nanoTime()
                transform()
                System.nanoTime() - t0
            }
            val r = transform()
            println("#389 段1 $name: 変換の時間 ${median(times) / 1_000_000} ms、モジュール ${r.bytes.size} バイト")
            variants["段1 $name"] = r.bytes
        }
        // 段2（燃料切れを待ち点にしない）も、既定の重みで
        variants["段2 本番"] =
            Asyncify.transform(metered.bytes, WasiP1.WAIT_IMPORTS, null, metered::originalFunc, fuelSites = metered.sites).bytes
        variants["段2 見込まない"] =
            Asyncify.transform(metered.bytes, WasiP1.WAIT_IMPORTS, null, metered::originalFunc, fuelSites = metered.sites.withWeight(AsyncifyFuelWeight.NONE)).bytes
        println("#389 Java ${System.getProperty("java.version")}")

        val runtime = EndiveRuntime()
        val modules = variants.mapValues { (_, bytes) -> runtime.load(bytes) }
        for ((name, script) in scripts) {
            modules.values.forEach { runLua(it, script) } // 温める
            val base = modules.getValue("挿入だけ").let { m ->
                val runs = (1..rounds).map { runLua(m, script) }
                median(runs.map { it.cpuNanos }).toDouble() / runs.first().fuel
            }
            val baseFuel = runLua(modules.getValue("挿入だけ"), script).fuel
            for ((variant, m) in modules) {
                val runs = (1..rounds).map { runLua(m, script) }
                val cpu = median(runs.map { it.cpuNanos })
                val fuel = runs.first().fuel
                val perFuel = cpu.toDouble() / fuel
                println(
                    "#389 $name $variant: CPU ${cpu / 1_000_000} ms、燃料 $fuel（挿入だけの %.2f 倍）、燃料 1 あたり %.2f ns、R = %.2f"
                        .format(fuel.toDouble() / baseFuel, perFuel, perFuel / base),
                )
            }
        }
    }
}
