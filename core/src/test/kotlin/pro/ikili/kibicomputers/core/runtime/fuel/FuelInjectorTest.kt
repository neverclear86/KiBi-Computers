package pro.ikili.kibicomputers.core.runtime.fuel

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.runtime.TestModules
import pro.ikili.kibicomputers.core.runtime.WasmFuelHandler
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import run.endive.wasm.Parser
import run.endive.wasm.types.OpCode

/** 燃料の計測コードの挿入（#38、ADR 0028）を、実際の Endive で確かめる */
class FuelInjectorTest {
    private val runtime = EndiveRuntime()

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    private val fuelWasm by lazy { resource("fuel.wasm") }
    private val metered: WasmModule by lazy { runtime.load(FuelInjector.inject(fuelWasm).bytes) }

    private class Stop : RuntimeException()

    private fun instance(initial: Long, handler: WasmFuelHandler? = null): WasmInstance =
        metered.instantiate(WasmInstanceConfig.builder().fuel(initial, handler).build())

    /** 十分な燃料で [name] を呼び、戻り値と使った燃料を返す */
    private fun run(name: String, vararg args: Long): Pair<Long, Long> {
        val initial = 1L shl 40
        instance(initial).use { inst ->
            val result = inst.call(name, *args).single()
            return result to initial - inst.fuel
        }
    }

    @Test
    fun `挿入しても結果は変わらず、燃料は毎回同じだけ減る`() {
        val plain = runtime.load(fuelWasm).instantiate(WasmInstanceConfig.EMPTY)
        assertFalse(plain.module.isFuelMetered)
        assertTrue(metered.isFuelMetered)
        assertEquals(plain.call("fib", 20).single(), run("fib", 20).first)
        val (_, used) = run("fib", 20)
        assertTrue(used > 20_000, "fib(20) の燃料 $used")
        assertEquals(used, run("fib", 20).second)
        // 計測の import・export は、モジュールの import・export に出ない
        assertEquals(plain.module.exports.map { it.name }, metered.exports.map { it.name })
        assertEquals(plain.module.imports, metered.imports)
    }

    @Test
    fun `燃料を小分けに配っても結果と使った燃料の合計は変わらない`() {
        val (expected, used) = run("fib", 20)
        for (slice in listOf(1L, 100L, 10_000L)) {
            var granted = slice
            var exhausted = 0
            val handler = WasmFuelHandler { _, remaining ->
                assertTrue(remaining < 0, "燃料切れの通知の残りは負: $remaining")
                exhausted++
                granted += slice
                remaining + slice
            }
            instance(slice, handler).use { inst ->
                assertEquals(expected, inst.call("fib", 20).single())
                assertEquals(used, granted - inst.fuel, "小分け $slice")
            }
            assertTrue(exhausted > 0, "燃料切れの通知がない（小分け $slice）")
        }
    }

    @Test
    fun `終わらないループは燃料切れの通知で止められ、通知の戻り値を新しい残りとして使う`() {
        val maxUnchecked = FuelInjector.inject(fuelWasm).maxUnchecked
        val seen = ArrayList<Long>()
        val handler = WasmFuelHandler { _, remaining ->
            seen += remaining
            if (seen.size == 3) throw Stop()
            1_000
        }
        instance(1_000, handler).use { inst ->
            assertThrows<Stop> { inst.call("forever") }
        }
        assertEquals(3, seen.size)
        // 戻り値の 1,000 から数え直しているので、行き過ぎは確かめる間の命令数まで
        for (r in seen) assertTrue(r < 0 && r >= -maxUnchecked, "残り $r（上限 $maxUnchecked）")
    }

    @Test
    fun `燃料切れの通知がなければFUEL_EXHAUSTEDのトラップになる`() {
        instance(1_000).use { inst ->
            val e = assertThrows<WasmTrapException> { inst.call("forever") }
            assertEquals(WasmTrapKind.FUEL_EXHAUSTED, e.kind)
        }
    }

    @Test
    fun `一括メモリ命令は長さに比例した燃料を課す`() {
        val big = 400_000L
        val (fillBig, fillBigUsed) = run("fill", big)
        val (_, fillSmallUsed) = run("fill", 8)
        assertEquals(7L, fillBig)
        assertEquals((big shr FuelCosts.MEMORY_BYTES_SHIFT) - 1, fillBigUsed - fillSmallUsed)

        val (_, copyBigUsed) = run("copy", big)
        val (_, copySmallUsed) = run("copy", 8)
        assertEquals((big shr FuelCosts.MEMORY_BYTES_SHIFT) - 1, copyBigUsed - copySmallUsed)

        val (_, grow0) = run("grow", 0)
        val (old, grow2) = run("grow", 2)
        assertTrue(old > 0, "memory.grow の戻り値 $old")
        assertEquals(2L shl FuelCosts.MEMORY_GROW_PAGE_SHIFT, grow2 - grow0)
    }

    @Test
    fun `末尾呼び出しの番号を付け替え、深い相互再帰も動く`() {
        val ops = Parser.parse(fuelWasm).codeSection().functionBodies().flatMap { it.instructions() }.map { it.opcode() }.toSet()
        assertTrue(OpCode.RETURN_CALL in ops && OpCode.MEMORY_FILL in ops && OpCode.MEMORY_COPY in ops, "テスト用の WASM に使いたい命令がない: $ops")
        val (even, used) = run("is_even", 1_000_000)
        assertEquals(1L, even)
        assertTrue(used > 1_000_000, "燃料 $used")
        assertEquals(0L, run("is_even", 999_999).first)
    }

    @Test
    fun `ホストの関数を呼ぶ前に書き戻し、戻った後に読み直す`() {
        // run(x) = twice(x) + 1。本体は local.get・call・i32.const・i32.add・end の 5 命令で、入口でまとめて引く
        val module = runtime.load(FuelInjector.inject(TestModules.HOST).bytes)
        var seen = 0L
        val config = WasmInstanceConfig.builder()
            .hostFunction("env", "twice", TestModules.TWICE_TYPE) { inst, args ->
                seen = inst.fuel
                inst.fuel = 500
                longArrayOf(args[0] * 2)
            }
            .fuel(1_000, null)
            .build()
        module.instantiate(config).use { inst ->
            assertEquals(7L, inst.call("run", 3).single())
            assertEquals(995L, seen, "ホストの関数から見た残り")
            assertEquals(500L, inst.fuel, "ホストの関数が書いた残りを読み直し、export から戻る前に書き戻す")
        }
    }

    @Test
    fun `start関数から戻る前に書き戻す`() {
        // start の本体は call・end の 2 命令
        val module = runtime.load(FuelInjector.inject(TestModules.START).bytes)
        var seen = 0L
        val config = WasmInstanceConfig.builder()
            .hostFunction("env", "started", pro.ikili.kibicomputers.core.runtime.WasmFunctionType.EMPTY) { inst, _ ->
                seen = inst.fuel
                inst.fuel -= 10
                LongArray(0)
            }
            .fuel(100, null)
            .build()
        module.instantiate(config).use { inst ->
            assertEquals(98L, seen)
            assertEquals(88L, inst.fuel)
        }
    }

    @Test
    fun `計測の名前を使っているWASMと読めないバイト列は断る`() {
        assertThrows<WasmLoadException> { FuelInjector.inject(TestModules.FUEL) }
        assertThrows<WasmLoadException> { FuelInjector.inject(TestModules.MALFORMED) }
        assertThrows<WasmLoadException> { FuelInjector.inject(fuelWasm.copyOf(fuelWasm.size / 2)) }
    }

    @Test
    fun `同じ入力からは同じバイト列になる`() {
        assertArrayEquals(FuelInjector.inject(fuelWasm).bytes, FuelInjector.inject(fuelWasm).bytes)
    }

    @Test
    fun `lua_wasmに挿入しても大きさの増え方を抑える`() {
        val lua = resource("lua.wasm")
        val result = FuelInjector.inject(lua)
        runtime.load(result.bytes)
        val before = result.sizesBefore.sum().toLong()
        val after = result.sizesAfter.sum().toLong()
        // PoC（#28）では +67%。block・try_table の入口で引く処理をまとめて、それより小さくする
        assertTrue(after < before * 165 / 100, "コードの大きさ $before → $after")
        for (i in result.sizesBefore.indices) {
            val b = result.sizesBefore[i]
            val a = result.sizesAfter[i]
            assertTrue(a <= b * 3 + 64, "関数 $i の大きさ $b → $a")
        }
        assertTrue(result.maxUnchecked < 10_000, "確かめる間の命令数 ${result.maxUnchecked}")
        println("lua.wasm: コード $before → $after バイト、全体 ${lua.size} → ${result.bytes.size} バイト、確かめる間の命令数 ${result.maxUnchecked}")
    }
}
