package pro.ikili.kibicomputers.core.runtime.asyncify

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.runtime.Instrumentation
import pro.ikili.kibicomputers.core.runtime.WasmAsyncStack
import pro.ikili.kibicomputers.core.runtime.WasmAsyncState
import pro.ikili.kibicomputers.core.runtime.WasmFuelHandler
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.fuel.FuelInjector
import pro.ikili.kibicomputers.core.wasi.kibi.WitTool

/**
 * 待機点への変換の、multi-value（引数を持つブロック・複数の結果を持つブロック・複数の戻り値の関数）への対応（#425）。
 *
 * WAT で書いたモジュールを変換し、入力待ち（`w.wait`）と燃料切れで巻き戻して巻き直しても、止めずに動かしたときと結果が同じことを確かめる。
 */
class AsyncifyMultiValueTest {
    /**
     * 止まりうる呼び出し（`w.wait`）を、次の中に持つ `run() -> (i32, i32)`:
     * - 引数を持ち、複数の結果を持つ `block`
     * - 引数を持つ `loop`（先頭への分岐で引数を渡す）
     * - 引数を持ち、複数の結果を持つ `if`（`then` だけが止まりうる）
     * - 引数を持ち、複数の結果を持つ `try_table`
     * - 複数の戻り値を持つ止まりうる関数 `pair`
     * - 待ち点の前に、ブロックの外の値がスタックに残っている（`i32.const 1000`）
     */
    private val wat = """
        (module
          (import "w" "wait" (func ${'$'}wait (result i32)))
          (func ${'$'}pair (param i32) (result i32 i32)
            (call ${'$'}wait)
            (i32.mul (local.get 0) (i32.const 3)))
          (func (export "run") (result i32 i32)
            (local ${'$'}c i32)
            i32.const 1000
            ;; 引数 2 つ、結果 3 つの block
            i32.const 10
            i32.const 20
            (block (param i32 i32) (result i32 i32 i32)
              call ${'$'}wait)
            i32.add
            i32.mul
            ;; 引数 2 つ（足した値と残りの回数）の loop
            i32.const 5
            (loop ${'$'}l (param i32 i32) (result i32)
              local.set ${'$'}c
              call ${'$'}wait
              i32.const 7
              i32.mul
              i32.add
              local.get ${'$'}c
              i32.const 1
              i32.sub
              local.tee ${'$'}c
              local.get ${'$'}c
              br_if ${'$'}l
              drop)
            ;; 引数 1 つ、結果 2 つの if
            i32.const 4
            i32.const 1
            (if (param i32) (result i32 i32)
              (then call ${'$'}wait)
              (else i32.const 0))
            i32.mul
            i32.add
            ;; 引数 1 つ、結果 2 つの try_table
            i32.const 9
            (try_table (param i32) (result i32 i32)
              call ${'$'}wait)
            i32.sub
            i32.add
            ;; 複数の戻り値の関数
            i32.const 11
            call ${'$'}pair
            i32.add
            i32.add
            i32.const 1000
            i32.sub
            ;; 2 つ目の戻り値
            i32.const 99
            i32.add)
          ;; 結果を 2 つ返す
          (func (export "run2") (result i32 i32)
            (call ${'$'}pair (i32.const 5))))
    """.trimIndent()

    private val bytes by lazy { WitTool.wat(wat) }

    private class Stack : WasmAsyncStack {
        val values = ArrayList<Long>()

        override fun push(value: Long) {
            values += value
        }

        override fun pop(): Long = values.removeAt(values.size - 1)
    }

    /**
     * `w.wait` は呼ばれるたびに 1, 2, 3, … を返す。[suspendWait] なら、毎回いったん巻き戻してから、巻き直しで着いたときに返す。
     * 燃料切れ（[fuel] が null でないとき）は、巻き戻せるところでは毎回巻き戻す。[export] を最後まで動かした結果を返す
     */
    private fun drive(module: WasmModule, export: String, suspendWait: Boolean, fuel: Long? = null): Pair<LongArray, Counts> {
        val counts = Counts()
        var next = 0L
        val stack = Stack()
        val builder = WasmInstanceConfig.builder()
            .hostFunction("w", "wait", WasmFunctionType.of(emptyList(), listOf(WasmValueType.I32))) { instance, _ ->
                if (instance.asyncState == WasmAsyncState.REWINDING) {
                    assertTrue(stack.values.isEmpty(), "巻き直しで着いたのに、退避した値が残っている")
                    instance.asyncState = WasmAsyncState.NORMAL
                    longArrayOf(++next)
                } else if (suspendWait) {
                    counts.waitUnwinds++
                    instance.asyncState = WasmAsyncState.UNWINDING
                    longArrayOf(0)
                } else {
                    longArrayOf(++next)
                }
            }
            .asyncStack(stack)
        if (fuel != null) {
            builder.fuel(
                fuel,
                object : WasmFuelHandler {
                    override fun onFuelExhausted(instance: WasmInstance, remaining: Long): Long {
                        counts.fuelBlocking++
                        return fuel
                    }

                    override fun onFuelExhaustedUnwindable(instance: WasmInstance, remaining: Long): Long {
                        if (instance.asyncState == WasmAsyncState.REWINDING) {
                            instance.asyncState = WasmAsyncState.NORMAL
                            return instance.fuel
                        }
                        counts.fuelUnwinds++
                        instance.asyncState = WasmAsyncState.UNWINDING
                        return remaining
                    }
                },
            )
        }
        return module.instantiate(builder.build()).use { instance ->
            var result = instance.call(export)
            var rounds = 0
            while (instance.asyncState == WasmAsyncState.UNWINDING) {
                assertTrue(++rounds < 100_000, "終わらない")
                instance.asyncState = WasmAsyncState.REWINDING
                if (fuel != null) instance.fuel = fuel
                result = instance.call(export)
            }
            assertEquals(WasmAsyncState.NORMAL, instance.asyncState)
            assertTrue(stack.values.isEmpty())
            result to counts
        }
    }

    private class Counts {
        var waitUnwinds = 0
        var fuelUnwinds = 0
        var fuelBlocking = 0
    }

    /** 止めずに動かしたときの結果（変換していないモジュール） */
    private fun expected(export: String): LongArray {
        var next = 0L
        val config = WasmInstanceConfig.builder()
            .hostFunction("w", "wait", WasmFunctionType.of(emptyList(), listOf(WasmValueType.I32))) { _, _ -> longArrayOf(++next) }
            .build()
        return EndiveRuntime().load(bytes).instantiate(config).use { it.call(export) }
    }

    @Test
    fun `multi-valueのブロックと関数を変換でき、変換したものは正しいWASM`() {
        val r = Asyncify.transform(bytes, setOf("w.wait"))
        // pair・run・run2（pair を呼ぶ）
        assertEquals(3, r.instrumented)
        assertEquals(7, r.sites)
        assertNull(WitTool.validate(r.bytes))
    }

    @Test
    fun `入力待ちで毎回巻き戻して巻き直しても、止めずに動かしたときと結果が同じ`() {
        val module = EndiveRuntime().load(Asyncify.transform(bytes, setOf("w.wait")).bytes)
        for (export in listOf("run", "run2")) {
            val want = expected(export)
            val (got, counts) = drive(module, export, suspendWait = true)
            assertArrayEquals(want, got, export)
            assertTrue(counts.waitUnwinds > 0, export)
        }
    }

    @Test
    fun `燃料切れでも毎回巻き戻して巻き直せ、スレッドを持って待たない`() {
        val applied = Instrumentation.applyDetailed(bytes, setOf("w.wait"))
        assertTrue(applied.fuelYield, "燃料切れを待ち点にできなかった: ${applied.fuelYieldRejection}")
        assertNull(applied.fuelYieldRejection)
        val module = EndiveRuntime().load(applied.bytes)
        assertTrue(module.isAsyncified && module.isFuelMetered)
        for (export in listOf("run", "run2")) {
            val want = expected(export)
            // 燃料切れだけで止める
            val (got, counts) = drive(module, export, suspendWait = false, fuel = 3)
            assertArrayEquals(want, got, export)
            assertTrue(counts.fuelUnwinds > 0, "$export: 燃料切れで巻き戻していない")
            assertEquals(0, counts.fuelBlocking, "$export: 燃料切れでスレッドを持って待った")
            // 入力待ちと燃料切れの両方で止める
            val (both, c2) = drive(module, export, suspendWait = true, fuel = 3)
            assertArrayEquals(want, both, export)
            assertTrue(c2.waitUnwinds > 0 && c2.fuelUnwinds > 0, export)
        }
    }

    @Test
    fun `燃料の計測コードを入れたものも、変換の結果は正しいWASM`() {
        val metered = FuelInjector.inject(bytes).bytes
        val r = Asyncify.transform(metered, setOf("w.wait"), "kibi.fuel")
        assertTrue(r.yieldSites > 0)
        assertNull(WitTool.validate(r.bytes))
    }
}
