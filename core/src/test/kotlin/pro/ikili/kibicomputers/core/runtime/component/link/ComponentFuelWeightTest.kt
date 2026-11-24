package pro.ikili.kibicomputers.core.runtime.component.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.runtime.WasmFuelHandler
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmSharedFuel
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.fn
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.host
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.u32

/**
 * コンポーネントの core モジュールにも、止まりうる関数の課金の見込み（#389。ADR 0329）がかかることを、`ComponentProgram.load` を通して確かめる。
 *
 * 同じコードを、止まりうる import なし（変換しても止まりうる関数がなく、課金は見込まない）と、あり（止まりうる関数になり、課金が 2 倍になる）で
 * 動かし、使った燃料を比べる（`AsyncifyFuelEstimateTest` は変換だけを直接呼ぶので、`ComponentProgram` が位置を渡し忘れても落ちない）。
 * 段1・段2のどちらも通す。
 */
class ComponentFuelWeightTest {
    private val waitHost: ComponentHost = ComponentHost.of(
        host("test:host/wait", "get" to HostFunction(fn(emptyList(), CanonType.U32), HostWait.UNWIND) { _, _ -> u32(7) }),
    )

    private val plainHost: ComponentHost = ComponentHost.of(
        host("test:host/wait", "get" to HostFunction(fn(emptyList(), CanonType.U32), HostWait.NONE) { _, _ -> u32(7) }),
    )

    /** main は loop で計算してから aux の `spin` を呼び、aux は loop で計算してからホストの関数 `get` を呼ぶ（`ComponentFuelYieldTest` と同じ形） */
    private fun shape(simd: Boolean): String {
        val simdOp = if (simd) "(drop (i32x4.extract_lane 0 (v128.const i32x4 0 0 0 0)))" else ""
        return """
            (component
              (import "test:host/wait" (instance ${'$'}h (export "get" (func (result u32)))))
              (alias export ${'$'}h "get" (func ${'$'}get))
              (core func ${'$'}get_lower (canon lower (func ${'$'}get)))
              (core module ${'$'}aux
                (import "host" "get" (func ${'$'}get (result i32)))
                (func (export "spin") (param ${'$'}n i32) (result i32)
                  (local ${'$'}x i32)
                  (loop ${'$'}l
                    (local.set ${'$'}x (i32.add (i32.mul (local.get ${'$'}x) (i32.const 17)) (local.get ${'$'}n)))
                    (local.set ${'$'}n (i32.sub (local.get ${'$'}n) (i32.const 1)))
                    (br_if ${'$'}l (local.get ${'$'}n)))
                  (i32.add (local.get ${'$'}x) (call ${'$'}get))))
              (core module ${'$'}main
                (import "aux" "spin" (func ${'$'}spin (param i32) (result i32)))
                (func (export "run") (result i32)
                  (local ${'$'}i i32) (local ${'$'}x i32)
                  (local.set ${'$'}i (i32.const $N))
                  (loop ${'$'}l
                    $simdOp
                    (local.set ${'$'}x (i32.add (i32.mul (local.get ${'$'}x) (i32.const 31)) (local.get ${'$'}i)))
                    (local.set ${'$'}i (i32.sub (local.get ${'$'}i) (i32.const 1)))
                    (br_if ${'$'}l (local.get ${'$'}i)))
                  (i32.add (local.get ${'$'}x) (call ${'$'}spin (i32.const $N)))))
              (core instance ${'$'}hx (export "get" (func ${'$'}get_lower)))
              (core instance ${'$'}a (instantiate ${'$'}aux (with "host" (instance ${'$'}hx))))
              (core instance ${'$'}m (instantiate ${'$'}main (with "aux" (instance ${'$'}a))))
              (func (export "run") (result u32) (canon lift (core func ${'$'}m "run"))))
        """
    }

    private fun load(simd: Boolean, wait: Boolean, fuelYield: Boolean): ComponentProgram =
        ComponentProgram.load(
            LinkTesting.runtime,
            LinkTesting.wat(shape(simd)),
            if (wait) waitHost else plainHost,
            metered = true,
            policy = LinkTesting.ANY_IMPORT,
            fuelYield = fuelYield,
        )

    /**
     * 燃料切れの処理をカーネルのようにまねて動かし、使った燃料（配った回数 × [GRANT]。誤差は [GRANT] ほど）を返す。
     * 巻き戻せれば巻き戻し、巻き直しが着いたら戻して、結果が出るまで呼び直す
     */
    private fun used(p: ComponentProgram): Long {
        var grants = 0L
        lateinit var component: LinkedComponent
        val handler = object : WasmFuelHandler {
            override fun onFuelExhausted(instance: WasmInstance, remaining: Long): Long {
                grants++
                return remaining + GRANT
            }

            override fun onFuelExhaustedUnwindable(instance: WasmInstance, remaining: Long): Long {
                if (component.isRewinding) {
                    component.endFuelRewind()
                    grants++
                    return remaining + GRANT
                }
                if (component.canUnwind()) {
                    component.requestUnwind()
                    return remaining
                }
                grants++
                return remaining + GRANT
            }
        }
        val env = object : LinkEnv {
            override fun configure(builder: WasmInstanceConfig.Builder) {
                builder.fuel(0, handler)
            }
        }
        LinkedComponent.instantiate(p.plan, env, WasmSharedFuel(GRANT)).use { c ->
            component = c
            val run = c.function("run")!!
            var rounds = 0
            while (true) {
                val r = c.call(run, emptyList())
                if (!c.unwound) {
                    assertEquals(expected(), (r as CanonValue.I32).value)
                    return grants * GRANT
                }
                assertTrue(++rounds < 100_000, "終わらない")
                c.beginRewind()
            }
        }
    }

    private fun expected(): Int {
        var x = 0
        for (i in N downTo 1) x = x * 31 + i
        var y = 0
        for (n in N downTo 1) y = y * 17 + n
        return x + y + 7
    }

    /** 見込まないときの使った燃料に対する、見込んだときの比（2 倍のはずなので、誤差を見て ±0.15） */
    private fun assertDoubled(base: Long, weighted: Long, what: String) {
        assertTrue(base > 20 * GRANT, "$what: 燃料が少なすぎて比べられない: $base")
        val ratio = weighted.toDouble() / base
        assertTrue(ratio in 1.85..2.15, "$what: 見込まないとき $base、見込んだとき $weighted（比 $ratio）")
    }

    @Test
    fun `段1で変換した core モジュールの課金を、止まりうる関数だけ 2 倍にする`() {
        val base = load(simd = false, wait = false, fuelYield = false)
        // 止まりうる import も、燃料切れの待ち点もないので、変換しても止まりうる関数がない
        assertTrue(base.waitPoints)
        assertFalse(base.fuelYield)
        val p = load(simd = false, wait = true, fuelYield = true)
        assertTrue(p.fuelYield, "燃料切れを待ち点にしていない: ${p.fuelYieldRejection}")
        assertNull(p.fuelYieldRejection)
        assertDoubled(used(base), used(p), "段1")
    }

    @Test
    fun `段2で変換した core モジュールの課金も 2 倍にする`() {
        val base = load(simd = false, wait = false, fuelYield = false)
        val p = load(simd = false, wait = true, fuelYield = false)
        assertTrue(p.waitPoints)
        assertFalse(p.fuelYield)
        assertDoubled(used(base), used(p), "段2")
    }

    @Test
    fun `段1を断られて段2に落ちたモジュールがあっても、全モジュールの課金を 2 倍にする`() {
        val base = load(simd = true, wait = false, fuelYield = false)
        val p = load(simd = true, wait = true, fuelYield = true)
        // main は SIMD の命令の型を追えずに段1を断られ、段2で変換する。aux は段1で変換する
        assertTrue(p.waitPoints, "${p.waitPointRejection}")
        assertTrue(p.fuelYield)
        assertNotNull(p.fuelYieldRejection)
        assertTrue(p.fuelYieldRejection!!.contains("SIMD"), p.fuelYieldRejection)
        assertDoubled(used(base), used(p), "段1と段2")
    }

    private companion object {
        const val N = 3000
        const val GRANT = 500L
    }
}
