package pro.ikili.kibicomputers.core.runtime.asyncify

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.runtime.Instrumentation
import pro.ikili.kibicomputers.core.runtime.WasmAsyncStack
import pro.ikili.kibicomputers.core.runtime.WasmAsyncState
import pro.ikili.kibicomputers.core.runtime.WasmBinary
import pro.ikili.kibicomputers.core.runtime.WasmFuelHandler
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.fuel.AsyncifyFuelWeight
import pro.ikili.kibicomputers.core.runtime.fuel.FuelCosts
import pro.ikili.kibicomputers.core.runtime.fuel.FuelInjector
import run.endive.wasm.Parser

/** 変換が足すコードの分を、止まりうる関数の課金に見込む（#389。ADR 0329 の「#389: 変換が足すコードの見込み」） */
class AsyncifyFuelEstimateTest {
    private val i32 = 0x7F

    /**
     * `w.wait() -> i32` を呼んで、その値の回数だけ回る loop（止まりうる関数 `run` の中）と、
     * 50 回まわる葉の関数 `leaf`（止まりうる関数を呼ばない）を通る `run() -> i32`。戻り値は 7（`AsyncifyTest` と同じ形）
     */
    private fun loopModule(): ByteArray {
        val b = WasmBinary()
        val wait = b.importFunc("w", "wait", emptyList(), listOf(i32))
        b.mutableI64Global(0)
        val countDown = WasmBinary.code(0x02, 0x40, 0x03, 0x40, 0x20, 0, 0x45, 0x0D, 1, 0x20, 0, 0x41, 1, 0x6B, 0x21, 0, 0x0C, 0, 0x0B, 0x0B)
        val leaf = b.func(listOf(i32), listOf(i32), countDown + WasmBinary.code(0x41, 7))
        val run = b.func(
            emptyList(),
            listOf(i32),
            WasmBinary.code(0x10, wait, 0x21, 0) + countDown + WasmBinary.code(0x41, 50, 0x10, leaf),
            locals = listOf(i32),
        )
        b.exportFunc("run", run)
        return b.build()
    }

    private class Stack : WasmAsyncStack {
        val values = ArrayList<Long>()

        override fun push(value: Long) {
            values += value
        }

        override fun pop(): Long = values.removeAt(values.size - 1)
    }

    private val metered = FuelInjector.inject(loopModule())

    /** 挿入した [metered] を、[weight] で見込んで変換する（[yield] なら燃料切れも待ち点にする段1） */
    private fun transform(weight: AsyncifyFuelWeight?, yield: Boolean = true): ByteArray = Asyncify.transform(
        metered.bytes,
        setOf("w.wait"),
        if (yield) Instrumentation.YIELD_IMPORT else null,
        metered::originalFunc,
        fuelSites = weight?.let { metered.sites.withWeight(it) },
    ).bytes

    private val noExhaustion = object : WasmFuelHandler {
        override fun onFuelExhausted(instance: WasmInstance, remaining: Long): Long = throw AssertionError("燃料は切れない")
    }

    /** `w.wait` が [n] を返すとき、`run` が使った燃料（切れないだけの燃料で） */
    private fun used(bytes: ByteArray, n: Int): Long {
        val module = EndiveRuntime().load(bytes)
        val initial = 1_000_000_000L
        val config = WasmInstanceConfig.builder()
            .hostFunction("w", "wait", WasmFunctionType.of(emptyList(), listOf(WasmValueType.I32))) { _, _ -> longArrayOf(n.toLong()) }
            .asyncStack(Stack())
            .fuel(initial, noExhaustion)
            .build()
        return module.instantiate(config).use { instance ->
            assertArrayEquals(longArrayOf(7), instance.call("run"))
            initial - instance.fuel
        }
    }

    private fun instructions(bytes: ByteArray, defined: Int) =
        Parser.parse(bytes).codeSection().getFunctionBody(defined).instructions().map { it.opcode() to it.operands().toList() }

    @Test
    fun `見込まない重みは、位置を渡さないときと同じバイト列になる`() {
        assertArrayEquals(transform(null), transform(AsyncifyFuelWeight.NONE))
        assertArrayEquals(transform(null, yield = false), transform(AsyncifyFuelWeight.NONE, yield = false))
        // 本番の重みでは変わる
        assertFalse(transform(null).contentEquals(transform(AsyncifyFuelWeight.DEFAULT)))
    }

    @Test
    fun `同じ入力からは同じバイト列が出る`() {
        assertArrayEquals(transform(AsyncifyFuelWeight.DEFAULT), transform(AsyncifyFuelWeight.DEFAULT))
        assertArrayEquals(Instrumentation.apply(loopModule(), setOf("w.wait")), Instrumentation.apply(loopModule(), setOf("w.wait")))
    }

    @Test
    fun `止まりうる関数の課金だけを倍率どおりに増やし、葉の関数は変えない`() {
        assertEquals(2, FuelCosts.ASYNCIFY_NUMERATOR / FuelCosts.ASYNCIFY_DENOMINATOR)
        for (yield in listOf(true, false)) {
            val stage = "段${if (yield) 1 else 2}"
            val none = transform(AsyncifyFuelWeight.NONE, yield)
            val def = transform(AsyncifyFuelWeight.DEFAULT, yield)
            val base = listOf(0, 100, 200).map { used(none, it) }
            val extra = listOf(0, 100, 200).map { used(def, it) - used(none, it) }
            // 止まりうる関数 run の loop の 1 回ごとの燃料は、ちょうど 2 倍になる（増えた分 = 見込まないときの分）
            val perIteration = base[1] - base[0]
            assertTrue(perIteration > 0, "$stage: $base")
            assertEquals(perIteration, base[2] - base[1], "$stage: $base")
            assertEquals(perIteration, extra[1] - extra[0], "$stage: $extra")
            assertEquals(perIteration, extra[2] - extra[1], "$stage: $extra")
            // 葉の関数の分（50 回まわる loop）は増えないので、増えた分は全体より少ない
            assertTrue(extra[0] in 1 until base[0], "$stage: $base $extra")
            // 葉の関数（定義済みの関数の 0 番）は変換しないので、命令も課金もそのまま
            assertEquals(instructions(none, 0), instructions(def, 0))
            assertEquals(instructions(metered.bytes, 0).size, instructions(def, 0).size)
        }
    }

    @Test
    fun `倍率は整数で計算して切り上げる`() {
        val w = AsyncifyFuelWeight(3, 2)
        assertEquals(2, w.apply(1)) // 1.5 を切り上げ
        assertEquals(3, w.apply(2))
        assertEquals(15, w.apply(10))
        assertEquals(10, AsyncifyFuelWeight.NONE.apply(10))
        assertEquals(20, AsyncifyFuelWeight.DEFAULT.apply(10))
        assertEquals(FuelCosts.ASYNCIFY_NUMERATOR, AsyncifyFuelWeight.DEFAULT.numerator)
        assertEquals(FuelCosts.ASYNCIFY_DENOMINATOR, AsyncifyFuelWeight.DEFAULT.denominator)
        assertThrows<IllegalArgumentException> { AsyncifyFuelWeight(1, 2) }
    }

    @Test
    fun `燃料切れで巻き戻して巻き直しても、使った燃料は燃料が切れないときと同じ`() {
        // 見込みは課金の定数を大きくするだけで、課金は if (state == NORMAL) の中にあるので、巻き直しの間は引かない
        val bytes = Instrumentation.apply(loopModule(), setOf("w.wait"))
        val n = 300
        val expected = used(bytes, n)
        val module = EndiveRuntime().load(bytes)
        val grant = 40L
        var granted = grant
        var unwinds = 0
        var blocking = 0
        // 巻き戻したときの残り（燃料のグローバルは、巻き戻しでは書き戻されないので、カーネルと同じくこれを使う）
        var unwoundRemaining = 0L
        val handler = object : WasmFuelHandler {
            // 葉の関数の中の切れ（巻き戻せない）は、その場で足す
            override fun onFuelExhausted(instance: WasmInstance, remaining: Long): Long {
                blocking++
                granted += grant
                return remaining + grant
            }

            override fun onFuelExhaustedUnwindable(instance: WasmInstance, remaining: Long): Long {
                if (instance.asyncState == WasmAsyncState.REWINDING) {
                    instance.asyncState = WasmAsyncState.NORMAL
                    return instance.fuel
                }
                unwinds++
                unwoundRemaining = remaining
                instance.asyncState = WasmAsyncState.UNWINDING
                return remaining
            }
        }
        val stack = Stack()
        val config = WasmInstanceConfig.builder()
            .hostFunction("w", "wait", WasmFunctionType.of(emptyList(), listOf(WasmValueType.I32))) { _, _ -> longArrayOf(n.toLong()) }
            .asyncStack(stack)
            .fuel(grant, handler)
            .build()
        module.instantiate(config).use { instance ->
            var result = instance.call("run")
            while (instance.asyncState == WasmAsyncState.UNWINDING) {
                instance.fuel = unwoundRemaining + grant
                granted += grant
                instance.asyncState = WasmAsyncState.REWINDING
                result = instance.call("run")
            }
            assertArrayEquals(longArrayOf(7), result)
            assertTrue(stack.values.isEmpty())
            assertTrue(unwinds > 10, "巻き戻し $unwinds 回")
            assertTrue(blocking > 0, "葉の関数の中の切れ $blocking 回")
            assertEquals(expected, granted - instance.fuel)
        }
    }

    @Test
    fun `燃料を共有するcoreモジュール（コンポーネント）にも見込む`() {
        val shared = FuelInjector.inject(loopModule(), sharedGlobal = true)
        fun transform(weight: AsyncifyFuelWeight?) = Asyncify.prepare(shared.bytes, {}, shared::originalFunc, weight?.let { shared.sites.withWeight(it) })
            .transform(setOf("w.wait"), yieldImport = Instrumentation.YIELD_IMPORT).bytes
        assertArrayEquals(transform(null), transform(AsyncifyFuelWeight.NONE))
        val def = transform(AsyncifyFuelWeight.DEFAULT)
        assertFalse(transform(null).contentEquals(def))
        // 正しい WASM として読める
        Parser.parse(def)
    }
}
