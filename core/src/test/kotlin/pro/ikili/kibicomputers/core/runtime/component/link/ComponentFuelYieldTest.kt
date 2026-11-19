package pro.ikili.kibicomputers.core.runtime.component.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import pro.ikili.kibicomputers.core.runtime.WasmFuelHandler
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmSharedFuel
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.fn
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.host
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.load
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.u32

/**
 * コンポーネントの燃料切れの待ち点（#803。ADR 0757）。カーネルを通さず、変換の 2 段と、`LinkedComponent` の燃料切れの巻き戻し・巻き直しを確かめる。
 *
 * main は loop で計算してから aux の `spin` を呼び、aux は loop で計算してからホストの関数 `get` を呼ぶ（main・aux とも止まりうる関数になる）。
 */
class ComponentFuelYieldTest {
    private val getHost: ComponentHost = ComponentHost.of(
        host("test:host/wait", "get" to HostFunction(fn(emptyList(), CanonType.U32), HostWait.UNWIND) { _, _ -> u32(7) }),
    )

    /** [simd] なら、main の loop に SIMD の命令を入れる（段1で loop が待ち点になり、型を追えずに断られる。p1 の `AsyncFuelTest` と同じ） */
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

    private fun expected(): Int {
        var x = 0
        for (i in N downTo 1) x = x * 31 + i
        var y = 0
        for (n in N downTo 1) y = y * 17 + n
        return x + y + 7
    }

    /**
     * カーネルの燃料切れの処理をまねる（`ProcessRunner`）: 止まりうる関数の中の燃料切れ（`kibi.async_fuel`）では、巻き戻せれば巻き戻し、
     * 巻き直しが着いたら [LinkedComponent.endFuelRewind] で戻す。ほかは [GRANT] を足して続ける
     */
    private class FuelEnv : LinkEnv {
        lateinit var component: LinkedComponent
        var unwinds = 0
        var arrivals = 0
        var plain = 0
        var rewound = 0

        val handler = object : WasmFuelHandler {
            override fun onFuelExhausted(instance: WasmInstance, remaining: Long): Long {
                plain++
                return remaining + GRANT
            }

            override fun onFuelExhaustedUnwindable(instance: WasmInstance, remaining: Long): Long {
                if (component.isRewinding) {
                    component.endFuelRewind()
                    arrivals++
                    return remaining + GRANT
                }
                if (component.canUnwind()) {
                    unwinds++
                    component.requestUnwind()
                    return remaining
                }
                plain++
                return remaining + GRANT
            }
        }

        override fun configure(builder: WasmInstanceConfig.Builder) {
            builder.fuel(0, handler)
        }

        override fun onRewound() {
            rewound++
        }
    }

    /** [p] を動かし、燃料切れで巻き戻すたびに巻き直して呼び直す。結果と [FuelEnv] を返す */
    private fun runToEnd(p: ComponentProgram): Pair<Int, FuelEnv> {
        val env = FuelEnv()
        LinkedComponent.instantiate(p.plan, env, WasmSharedFuel(GRANT)).use { c ->
            env.component = c
            val run = c.function("run")!!
            var rounds = 0
            while (true) {
                val r = c.call(run, emptyList())
                if (!c.unwound) return (r as CanonValue.I32).value to env
                assertTrue(++rounds < 100_000, "終わらない")
                c.beginRewind()
            }
        }
    }

    @Test
    fun `全モジュールを段1で変換し、燃料切れで全インスタンスを巻き戻して、巻き直すと同じ結果になる`() {
        val p = load(shape(simd = false), getHost, metered = true)
        assertTrue(p.waitPoints, "${p.waitPointRejection}")
        assertTrue(p.fuelYield, "燃料切れを待ち点にしていない: ${p.fuelYieldRejection}")
        assertNull(p.fuelYieldRejection)
        val (value, env) = runToEnd(p)
        assertEquals(expected(), value)
        assertTrue(env.unwinds > 2, "燃料切れで巻き戻していない: ${env.unwinds}")
        assertEquals(env.unwinds, env.arrivals, "巻き直しが燃料切れの呼び出しに着いていない")
        // 燃料の巻き直しは、ホストの関数の巻き直し（onRewound）として知らせない
        assertEquals(0, env.rewound)
    }

    @Test
    fun `段1を断られたモジュールだけ段2で変換し、待機点型のまま動き、理由は1回だけログに出す`() {
        val p = load(shape(simd = true), getHost, metered = true)
        assertTrue(p.waitPoints, "待機点への変換までやめた: ${p.waitPointRejection}")
        // aux は段1で変換できる
        assertTrue(p.fuelYield)
        assertNotNull(p.fuelYieldRejection)
        val reason = p.fuelYieldRejection!!
        assertTrue(reason.contains("core モジュール"), reason)
        assertTrue(reason.contains("SIMD"), reason)
        assertFalse(FuelYieldRejectionLog.report(p.sha256, reason), "読み込みで理由を出していない")
        // 待機点に変換できなかった理由のログ（BLOCKING に落ちた理由）とは別に覚える
        assertTrue(WaitPointRejectionLog.report(p.sha256, "テストから"), "待機点の理由のログと分けていない")
        val (value, env) = runToEnd(p)
        assertEquals(expected(), value)
        // main（段2）の燃料切れはその場で足し、aux（段1）の燃料切れは巻き戻す
        assertTrue(env.plain > 0, "段2のモジュールで燃料が切れていない")
        assertTrue(env.unwinds > 0, "段1のモジュールで巻き戻していない")
        assertEquals(env.unwinds, env.arrivals)
        assertEquals(0, env.rewound)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `燃料を計測しないか段1を試さないなら、燃料切れの待ち点を作らない`(metered: Boolean) {
        val p = ComponentProgram.load(LinkTesting.runtime, LinkTesting.wat(shape(simd = false)), getHost, metered, policy = LinkTesting.ANY_IMPORT, fuelYield = false)
        assertTrue(p.waitPoints)
        assertFalse(p.fuelYield)
        assertNull(p.fuelYieldRejection)
        if (!metered) {
            val q = load(shape(simd = false), getHost, metered = false)
            assertFalse(q.fuelYield)
        }
    }

    private companion object {
        const val N = 3000
        const val GRANT = 2_000L
    }
}
