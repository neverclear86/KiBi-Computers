package pro.ikili.kibicomputers.core.runtime.component.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.fn
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.host
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.instantiate
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.load
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.u32

/**
 * コンポーネントの待機点への変換（#757。ADR 0757）。カーネルを通さず、`LinkedComponent` の巻き戻し・巻き直しを確かめる。
 *
 * ホストの関数 `get` は、1 回目は巻き戻しを求め、巻き直して着いた 2 回目に値を返す。
 */
class ComponentWaitPointTest {
    /** `get` を [wait] の待ち方で出すホスト。[calls] に呼ばれた回数を数える */
    private class GetHost(wait: HostWait, private val value: Int = 2) {
        var calls = 0
        val host: ComponentHost = ComponentHost.of(
            host(
                "test:host/wait",
                "get" to HostFunction(fn(emptyList(), CanonType.U32), wait) { call, _ ->
                    calls++
                    if (calls == 1 && call.component.canUnwind()) {
                        call.component.requestUnwind()
                        null
                    } else {
                        u32(value)
                    }
                },
            ),
        )
    }

    /**
     * shim の形（#757）: main は shim の export を呼び、shim は自分の表を `call_indirect` で呼び、表は fixup が lower した import で埋める。
     * shim の中には止まりうる関数がないので、コンポーネント全体で解析しないと変換されない
     */
    private val shimShape = """
        (component
          (import "test:host/wait" (instance ${'$'}h (export "get" (func (result u32)))))
          (alias export ${'$'}h "get" (func ${'$'}get))
          (core func ${'$'}get_lower (canon lower (func ${'$'}get)))
          (core module ${'$'}shim
            (type ${'$'}t (func (result i32)))
            (table (export "${'$'}imports") 1 1 funcref)
            (global ${'$'}n (mut i32) (i32.const 0))
            ;; 呼ぶ前に数を 1 足す（変換されていなければ、巻き直しでもう 1 回足してしまう）
            (func (export "0") (result i32)
              (global.set ${'$'}n (i32.add (global.get ${'$'}n) (i32.const 1)))
              (i32.add (call_indirect (type ${'$'}t) (i32.const 0)) (global.get ${'$'}n))))
          (core module ${'$'}fixup
            (import "" "${'$'}imports" (table 1 1 funcref))
            (import "" "0" (func ${'$'}f (result i32)))
            (elem (i32.const 0) func ${'$'}f))
          (core module ${'$'}main
            (import "shim" "get" (func ${'$'}g (result i32)))
            (func (export "run") (result i32)
              (local ${'$'}x i32)
              (local.set ${'$'}x (i32.const 40))
              (i32.add (local.get ${'$'}x) (call ${'$'}g))))
          (core instance ${'$'}s (instantiate ${'$'}shim))
          (core instance ${'$'}fx (export "${'$'}imports" (table ${'$'}s "${'$'}imports")) (export "0" (func ${'$'}get_lower)))
          (core instance (instantiate ${'$'}fixup (with "" (instance ${'$'}fx))))
          (core instance ${'$'}sx (export "get" (func ${'$'}s "0")))
          (core instance ${'$'}m (instantiate ${'$'}main (with "shim" (instance ${'$'}sx))))
          (func (export "run") (result u32) (canon lift (core func ${'$'}m "run"))))
    """

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `モジュールをまたいで巻き戻し、巻き直して同じ結果になる`(metered: Boolean) {
        val g = GetHost(HostWait.UNWIND)
        val p = load(shimShape, g.host, metered = metered)
        assertTrue(p.waitPoints, "変換していない: ${p.waitPointRejection}")
        assertNull(p.waitPointRejection)
        instantiate(p).use { c ->
            val run = c.function("run")!!
            assertNull(c.call(run, emptyList()))
            assertTrue(c.unwound, "巻き戻していない")
            assertEquals(1, g.calls)
            c.beginRewind()
            assertEquals(u32(43), c.call(run, emptyList()), "shim の足し算が 2 回動いた（shim が変換されていない）")
            assertFalse(c.unwound)
            assertEquals(2, g.calls)
            // 続けて呼べる（状態は NORMAL に戻っている）
            assertEquals(u32(44), c.call(run, emptyList()))
        }
    }

    @Test
    fun `待たないホストだけなら、巻き戻さずに動く`() {
        val g = GetHost(HostWait.NONE)
        val p = load(shimShape, g.host)
        assertTrue(p.waitPoints)
        instantiate(p).use { c ->
            // NONE でも、巻き戻しを求めればできる（呼ばない前提だが、変換の形は同じ）。ここでは 1 回目から値を返させる
            g.calls = 1
            assertEquals(u32(43), c.call(c.function("run")!!, emptyList()))
            assertFalse(c.unwound)
        }
    }

    @Test
    fun `巻き戻せない待ちのホストの関数をimportしたら変換せず、理由が残る`() {
        val g = GetHost(HostWait.BLOCK)
        val p = load(shimShape, g.host)
        assertFalse(p.waitPoints)
        assertTrue(p.waitPointRejection!!.contains("get"), p.waitPointRejection)
        assertTrue(p.plan.modules.none { it.isAsyncified })
        instantiate(p).use { c ->
            assertFalse(c.asyncified)
            assertFalse(c.canUnwind())
            assertEquals(u32(43), c.call(c.function("run")!!, emptyList()))
            assertFalse(c.unwound)
        }
    }

    @Test
    fun `変換しない指定なら変換しない`() {
        val g = GetHost(HostWait.UNWIND)
        val p = ComponentProgram.load(
            LinkTesting.runtime, LinkTesting.wat(shimShape), g.host, metered = false, policy = LinkTesting.ANY_IMPORT, asyncify = false,
        )
        assertFalse(p.waitPoints)
        assertNull(p.waitPointRejection)
    }

    /** ゲストからゲストへの呼び出し（lift した関数を lower した import）の下の待ちは、巻き戻せないので変換しない */
    @Test
    fun `待ちがゲストからゲストへの呼び出しの下にあれば変換しない`() {
        val g = GetHost(HostWait.UNWIND)
        val text = """
            (component
              (import "test:host/wait" (instance ${'$'}h (export "get" (func (result u32)))))
              (alias export ${'$'}h "get" (func ${'$'}get))
              (core func ${'$'}get_lower (canon lower (func ${'$'}get)))
              (core module ${'$'}inner
                (import "host" "get" (func ${'$'}g (result i32)))
                (func (export "get") (result i32) (call ${'$'}g)))
              (core instance ${'$'}hi (export "get" (func ${'$'}get_lower)))
              (core instance ${'$'}i (instantiate ${'$'}inner (with "host" (instance ${'$'}hi))))
              (func ${'$'}lifted (result u32) (canon lift (core func ${'$'}i "get")))
              (core func ${'$'}lifted_lower (canon lower (func ${'$'}lifted)))
              (core module ${'$'}outer
                (import "inner" "get" (func ${'$'}g (result i32)))
                (func (export "run") (result i32) (call ${'$'}g)))
              (core instance ${'$'}oi (export "get" (func ${'$'}lifted_lower)))
              (core instance ${'$'}o (instantiate ${'$'}outer (with "inner" (instance ${'$'}oi))))
              (func (export "run") (result u32) (canon lift (core func ${'$'}o "run"))))
        """
        val p = load(text, g.host)
        assertFalse(p.waitPoints)
        assertTrue(p.waitPointRejection!!.contains("ゲストからゲスト"), p.waitPointRejection)
        instantiate(p).use { c ->
            g.calls = 1
            assertEquals(u32(2), c.call(c.function("run")!!, emptyList()))
        }
    }

    @Test
    fun `変換は決定的（同じ入力から同じ計画のモジュール）`() {
        val a = load(shimShape, GetHost(HostWait.UNWIND).host)
        val b = load(shimShape, GetHost(HostWait.UNWIND).host)
        assertEquals(a.waitPoints, b.waitPoints)
        assertEquals(a.cacheWeight, b.cacheWeight)
        assertTrue(a.sha256.contentEquals(b.sha256))
    }

    @Test
    fun `巻き戻したら結果をlowerせず、ハンドルを作らない`() {
        // 結果に own を返す関数で巻き戻しても、表に何も足さない（巻き直しで 1 つだけ作る）
        var calls = 0
        val res = HostResource(7)
        val host = ComponentHost.of(
            host(
                "test:host/wait",
                "make" to HostFunction(fn(emptyList(), CanonType.Own(7)), HostWait.UNWIND) { call, _ ->
                    calls++
                    if (calls == 1) {
                        call.component.requestUnwind()
                        null
                    } else {
                        CanonValue.Handle(99)
                    }
                },
                resources = mapOf("r" to res),
            ),
        )
        val text = """
            (component
              (import "test:host/wait" (instance ${'$'}h
                (export "r" (type ${'$'}r (sub resource)))
                (export "make" (func (result (own ${'$'}r))))))
              (alias export ${'$'}h "make" (func ${'$'}make))
              (core func ${'$'}make_lower (canon lower (func ${'$'}make)))
              (core module ${'$'}m
                (import "host" "make" (func ${'$'}mk (result i32)))
                (func (export "run") (result i32) (call ${'$'}mk)))
              (core instance ${'$'}hi (export "make" (func ${'$'}make_lower)))
              (core instance ${'$'}i (instantiate ${'$'}m (with "host" (instance ${'$'}hi))))
              (func (export "run") (result u32) (canon lift (core func ${'$'}i "run"))))
        """
        val p = load(text, host)
        assertTrue(p.waitPoints, p.waitPointRejection)
        instantiate(p).use { c ->
            val run = c.function("run")!!
            assertNull(c.call(run, emptyList()))
            assertTrue(c.unwound)
            c.beginRewind()
            // 最初のハンドルの番号（巻き戻しのときに作っていれば 2 になる）
            assertEquals(u32(1), c.call(run, emptyList()))
        }
    }
}
