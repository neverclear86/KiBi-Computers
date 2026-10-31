package pro.ikili.kibicomputers.poc.endivebench

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** どの実行方式でも同じ結果になることを、小さい入力で確かめる（測定の前提）。 */
class EnginesTest {
    private fun cpu(mode: Mode): List<Long> {
        val instance = Engine(Wasm.BENCH, mode).newInstance()
        return listOf(
            instance.call("fib", 20),
            instance.call("sieve", 1000),
            instance.call("matmul", 16),
            instance.call("crc32", 4096, 2),
        )
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `CPUベンチマークの結果がインタプリタと同じ`(mode: Mode) {
        if (!Wasm.BENCH.supports(mode)) return
        val expected = cpu(Mode.INTERPRETER)
        assertEquals(6765L, expected[0])
        assertEquals(168L, expected[1])
        assertEquals(expected, cpu(mode))
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `Luaのスクリプトの出力がインタプリタと同じ`(mode: Mode) {
        if (!Wasm.LUA.supports(mode)) return
        val script = "local function f(n) if n < 2 then return n end return f(n-1) + f(n-2) end\nprint(f(15), ('%05d'):format(42), #table.concat({1, 2, 3}))"
        assertEquals("610\t00042\t3\n", Engine(Wasm.LUA, mode).runLua(script))
    }

    @ParameterizedTest
    @EnumSource(Mode::class, names = ["INTERPRETER", "RUNTIME_COMPILER", "AOT"])
    fun `終わらないループを割り込みで止められる`(mode: Mode) {
        assertNotNull(interruptLatency(Engine(Wasm.BENCH, mode).newInstance()))
    }

    @Test
    fun `RedlineはLuaを動かせない`() {
        // lua.wasm は例外処理（exnref）を使うが、Redline は未対応
        assertFalse(Wasm.LUA.supports(Mode.REDLINE))
    }
}
