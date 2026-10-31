package pro.ikili.kibicomputers.poc.playerexec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.poc.fuel.Wasm
import java.util.concurrent.TimeUnit

class PlayerRuntimeTest {
    private val bench = Program.of(Wasm.BENCH)
    private val lua = Program.of(Wasm.LUA)

    @Test
    fun `どの方式でも結果と燃料が同じ`() {
        val results = Policy.entries.map { policy ->
            PlayerRuntime(policy, hotFuel = Long.MAX_VALUE).use { rt ->
                val b = rt.runBench(bench, "fib", 20)
                val l = rt.runLua(lua, Scripts.SMALL)
                listOf(b.output, b.fuel, l.output, l.fuel)
            }
        }
        assertEquals("6765", results[0][0])
        assertEquals("2584\n", results[0][2])
        results.forEach { assertEquals(results[0], it) }
    }

    @Test
    fun `別のハッシュの変種も同じ結果になる`() {
        val v = bench.variant(3)
        assertNotEquals(bench.hash, v.hash)
        PlayerRuntime(Policy.COMPILER).use { rt ->
            assertEquals(rt.runBench(bench, "fib", 15).output, rt.runBench(v, "fib", 15).output)
            assertEquals(2, rt.stats.compiles.get())
        }
    }

    @Test
    fun `同じ中身はコンパイル結果を共有する`() {
        PlayerRuntime(Policy.COMPILER).use { rt ->
            repeat(3) { rt.runBench(bench, "fib", 10) }
            assertEquals(1, rt.stats.compiles.get())
            assertSame(rt.load(bench), rt.load(Program("copy", bench.bytes.copyOf())))
            // コンパイルしたら、パースした WASM は捨てる
            assertNull(rt.load(bench).parsed)
        }
    }

    @Test
    fun `C は閾値を超えるとコンパイルし、次の export の呼び出しで切り替える`() {
        PlayerRuntime(Policy.TIERED, hotFuel = 1_000_000).use { rt ->
            val launch = rt.launch(rt.load(bench))
            val first = launch.instance.export("fib").apply(25)[0]
            launch.finish()
            assertEquals("インタプリタ", launch.tier)
            // 閾値を超えたので、コンパイルが始まっている
            rt.compileAsync(launch.module).get(60, TimeUnit.SECONDS)
            val second = launch.instance.export("fib").apply(25)[0]
            assertEquals(first, second)
            assertEquals("コンパイル（切り替え）", launch.tier)
            assertEquals(1, rt.stats.upgrades.get())
            // 次の起動は最初からコンパイルしたコード
            assertEquals("コンパイル", rt.runBench(bench, "fib", 10).tier)
        }
    }

    @Test
    fun `C は切り替えの前後でメモリとグローバルを引き継ぐ`() {
        PlayerRuntime(Policy.TIERED, hotFuel = 1).use { rt ->
            val launch = rt.launch(rt.load(bench))
            val a = launch.instance.export("sieve").apply(100_000)[0]
            launch.finish()
            rt.compileAsync(launch.module).get(60, TimeUnit.SECONDS)
            val fuelBefore = launch.used()
            val b = launch.instance.export("sieve").apply(100_000)[0]
            assertEquals(a, b)
            assertTrue(launch.used() > fuelBefore)
            assertEquals("コンパイル（切り替え）", launch.tier)
        }
    }

    @Test
    fun `C は閾値に届かなければコンパイルしない`() {
        PlayerRuntime(Policy.TIERED).use { rt ->
            repeat(3) { rt.runLua(lua, Scripts.TINY) }
            assertEquals(0, rt.stats.compiles.get())
        }
    }
}
