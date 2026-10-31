package pro.ikili.kibicomputers.poc.fuel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class FuelMeteringTest {
    private val unlimited = Long.MAX_VALUE / 2

    private fun runBench(engine: Engine, fn: String, vararg args: Long, slice: Long = unlimited, meter: FuelMeter = FuelMeter()): Pair<Long, Long> {
        meter.start(slice)
        val instance = engine.newInstance(meter)
        val r = instance.call(fn, *args)
        return r to engine.used(instance, meter)
    }

    @Test
    fun `挿入しても結果は変わらず、燃料はランタイムによらず同じ`() {
        for (metering in listOf(Metering.INJECTED, Metering.INJECTED_GLOBAL)) checkInjected(metering)
    }

    private fun checkInjected(metering: Metering) {
        val base = Engine(Wasm.BENCH, Runtime.INTERPRETER, Metering.NONE)
        val aInterp = Engine(Wasm.BENCH, Runtime.INTERPRETER, metering)
        val aComp = Engine(Wasm.BENCH, Runtime.COMPILER, metering)
        val bInterp = Engine(Wasm.BENCH, Runtime.INTERPRETER, Metering.LISTENER)
        for ((fn, args) in listOf("fib" to longArrayOf(18), "sieve" to longArrayOf(10_000), "matmul" to longArrayOf(16), "crc32" to longArrayOf(1024, 2))) {
            val (expect, _) = runBench(base, fn, *args)
            val (r1, u1) = runBench(aInterp, fn, *args)
            val (r2, u2) = runBench(aComp, fn, *args)
            val (r3, u3) = runBench(bInterp, fn, *args)
            assertEquals(expect, r1, fn)
            assertEquals(expect, r2, fn)
            assertEquals(expect, r3, fn)
            assertTrue(u1 > 0, fn)
            assertEquals(u1, u2, "$fn: A はインタプリタとコンパイラで同じ燃料")
            assertTrue(u3 > 0, fn)
        }
    }

    @Test
    fun `B はコンパイルした実行では数えられない`() {
        val (_, used) = runBench(Engine(Wasm.BENCH, Runtime.COMPILER, Metering.LISTENER), "fib", 18)
        assertEquals(0, used)
    }

    @Test
    fun `燃料を小分けにしても結果と合計は変わらない`() {
        for (runtime in Runtime.entries) {
            val engine = Engine(Wasm.BENCH, runtime, Metering.INJECTED)
            val (expect, total) = runBench(engine, "fib", 20)
            val meter = FuelMeter { remaining -> remaining + 10_000 }
            val (r, used) = runBench(engine, "fib", 20, slice = 10_000, meter = meter)
            assertEquals(expect, r)
            assertEquals(total, used)
            assertTrue(meter.exhaustions >= total / 10_000 - 1, "${meter.exhaustions}")
        }
    }

    @Test
    fun `B でも小分けにできる`() {
        val engine = Engine(Wasm.BENCH, Runtime.INTERPRETER, Metering.LISTENER)
        val (expect, total) = runBench(engine, "fib", 18)
        val meter = FuelMeter { remaining -> remaining + 1_000 }
        val (r, used) = runBench(engine, "fib", 18, slice = 1_000, meter = meter)
        assertEquals(expect, r)
        assertEquals(total, used)
        assertEquals(1, meter.maxOvershoot, "B は1命令で気づく")
    }

    @Test
    fun `燃料切れで別スレッドを止めて、配ると再開する`() {
        for (runtime in Runtime.entries) {
            val engine = Engine(Wasm.BENCH, runtime, Metering.INJECTED)
            val (expect, _) = runBench(engine, "fib", 22)
            val ticks = Semaphore(0)
            val meter = FuelMeter { remaining ->
                // 次の tick に配られるまで止まる
                check(ticks.tryAcquire(5, TimeUnit.SECONDS)) { "配られない" }
                remaining + 20_000
            }
            meter.start(20_000)
            val instance = engine.newInstance(meter)
            var result = 0L
            val worker = Thread { result = instance.call("fib", 22) }
            worker.start()
            while (worker.isAlive) {
                ticks.release()
                worker.join(1)
            }
            assertEquals(expect, result)
            assertTrue(meter.exhaustions > 10)
        }
    }

    @Test
    fun `燃料切れで例外を投げると止まる`() {
        for (runtime in Runtime.entries) {
            val engine = Engine(Wasm.BENCH, runtime, Metering.INJECTED)
            val meter = FuelMeter { throw OutOfFuel() }
            meter.start(1_000_000)
            val instance = engine.newInstance(meter)
            // 終わらないループ
            val e = assertThrows<Throwable> { instance.call("spin") }
            assertTrue(generateSequence(e) { it.cause }.any { it is OutOfFuel }, e.toString())
        }
    }

    @Test
    fun `lua でも A の燃料はランタイムによらず同じ`() {
        // pcall と error は WASM の例外処理（try_table / throw）を通る
        val script = "local function f(n) if n < 2 then return n end return f(n-1) + f(n-2) end\n" +
            "local c = 0 for i = 1, 50 do if not pcall(error, i) then c = c + 1 end end\n" +
            "print(f(15), c)"
        for (metering in listOf(Metering.INJECTED, Metering.INJECTED_GLOBAL)) {
            val results = Runtime.entries.map { Engine(Wasm.LUA, it, metering).runLua(script, FuelMeter().also { m -> m.start(unlimited) }) }
            assertEquals("610\t50\n", results[0].first)
            assertEquals(results[0], results[1], metering.name)
            assertTrue(results[0].second > 0)
        }
    }

    @Test
    fun `lua の燃料切れでも止まり、小分けにしても結果と合計は変わらない`() {
        val script = "local t = {} for i = 1, 2000 do t[#t + 1] = tostring(i) end\n" +
            "local ok = 0 for i = 1, 20 do if not pcall(error, i) then ok = ok + 1 end end\n" +
            "print(#table.concat(t), ok)"
        for (runtime in Runtime.entries) {
            val engine = Engine(Wasm.LUA, runtime, Metering.INJECTED)
            val (out, total) = engine.runLua(script, FuelMeter().also { it.start(unlimited) })
            val meter = FuelMeter { it + 5_000 }.also { it.start(5_000) }
            val (out2, used) = engine.runLua(script, meter)
            assertEquals("6893\t20\n", out)
            assertEquals(out, out2)
            assertEquals(total, used)
            assertTrue(meter.exhaustions > 100)
        }
    }

    /** 関数 `f`（型 `() -> ()`）1つだけを export する WASM。[body] は命令の並び（最後の `end` は含めない） */
    private fun moduleOf(vararg body: Int): ByteArray {
        val code = byteArrayOf(0x00) + body.map { it.toByte() }.toByteArray() + byteArrayOf(0x0B) // ローカルなし、end
        fun section(id: Int, content: ByteArray) = byteArrayOf(id.toByte(), content.size.toByte()) + content
        return byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00) +
            section(1, byteArrayOf(1, 0x60, 0, 0)) +
            section(3, byteArrayOf(1, 0)) +
            section(7, byteArrayOf(1, 1, 'f'.code.toByte(), 0x00, 0)) +
            section(10, byteArrayOf(1, code.size.toByte()) + code)
    }

    @Test
    fun `例外の飛び先が loop でも、通常の実行で燃料を捨てず、終わらないループを止める`() {
        // loop $l (try_table (catch_all $l) end) br $l end
        val spin = moduleOf(0x03, 0x40, 0x1F, 0x40, 0x01, 0x02, 0x00, 0x0B, 0x0C, 0x00, 0x0B)
        for (runtime in Runtime.entries) for (metering in listOf(Metering.INJECTED, Metering.INJECTED_GLOBAL)) {
            val meter = FuelMeter { throw OutOfFuel() }
            meter.start(1_000_000)
            val instance = Engine(spin, runtime, metering).newInstance(meter)
            // 止まらない実装でもテストが固まらないよう、デーモンのスレッドで動かして待つ
            var error: Throwable? = null
            val worker = Thread { error = runCatching { instance.export("f").apply() }.exceptionOrNull() }
            worker.isDaemon = true
            worker.start()
            worker.join(20_000)
            assertTrue(!worker.isAlive, "$runtime $metering: 燃料切れで止まらない")
            assertTrue(generateSequence(error) { it.cause }.any { it is OutOfFuel }, "$runtime $metering: $error")
        }
    }

    @Test
    fun `例外の飛び先が block でも、通常の実行で引いた燃料を読み直しで捨てない`() {
        // block $b (try_table (catch_all $b) nop nop nop end) nop nop end
        val work = moduleOf(0x02, 0x40, 0x1F, 0x40, 0x01, 0x02, 0x00, 0x01, 0x01, 0x01, 0x0B, 0x01, 0x01, 0x0B)
        for (runtime in Runtime.entries) {
            val used = listOf(Metering.INJECTED, Metering.INJECTED_GLOBAL).map { metering ->
                val engine = Engine(work, runtime, metering)
                val meter = FuelMeter().also { it.start(unlimited) }
                val instance = engine.newInstance(meter)
                instance.export("f").apply()
                engine.used(instance, meter)
            }
            assertTrue(used[1] > 0, "$runtime")
            assertEquals(used[1], used[0], "$runtime: ローカルでもグローバルと同じ燃料")
        }
    }

    class OutOfFuel : RuntimeException("燃料切れ")
}
