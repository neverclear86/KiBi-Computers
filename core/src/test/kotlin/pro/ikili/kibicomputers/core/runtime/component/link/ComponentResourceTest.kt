package pro.ikili.kibicomputers.core.runtime.component.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonLimits
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.call
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.fn
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.host
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.instantiate
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.load
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.u32

/**
 * resource の own・borrow、ハンドル表、drop と dtor（#265。ADR 0265）。仕様どおりの番号の振り方・型の照合・lend・
 * インスタンスごとの表・pass_rep_not_handle は、仕様のテスト（`ComponentSpecTest` の `resources-*.wast`）でも確かめる
 */
class ComponentResourceTest {
    // ---- ホストの resource ----

    private class Log {
        val drops = ArrayList<Int>()
        val peeks = ArrayList<Int>()
        val takes = ArrayList<Int>()
    }

    private fun repOf(v: CanonValue): Int = (v as CanonValue.Handle).value

    /** resource `thing`（ホストの番号 7）を出すホスト。`test:host/res2` も同じホストの resource を `thing` として出す */
    private fun thingHost(log: Log): ComponentHost {
        val thing = HostResource(7) { call, rep ->
            assertEquals("[resource-drop]thing", call.functionName)
            log.drops += rep
        }
        val make = HostFunction(fn(listOf(CanonType.U32), CanonType.Own(7))) { _, a -> CanonValue.Handle((a[0] as CanonValue.I32).value) }
        val peek = HostFunction(fn(listOf(CanonType.Borrow(7)), CanonType.U32)) { _, a -> log.peeks += repOf(a[0]); u32(repOf(a[0])) }
        val take = HostFunction(fn(listOf(CanonType.Own(7)), CanonType.U32)) { _, a -> log.takes += repOf(a[0]); u32(repOf(a[0])) }
        return ComponentHost.of(
            host("test:host/res", "make" to make, "peek" to peek, "take" to take, resources = mapOf("thing" to thing)),
            host("test:host/res2", "peek" to peek, resources = mapOf("thing" to thing)),
        )
    }

    private val usesThing = """
        (component
          (import "test:host/res" (instance ${'$'}h
            (export "thing" (type ${'$'}t (sub resource)))
            (export "make" (func (param "n" u32) (result (own ${'$'}t))))
            (export "peek" (func (param "t" (borrow ${'$'}t)) (result u32)))
            (export "take" (func (param "t" (own ${'$'}t)) (result u32)))))
          (import "test:host/res2" (instance ${'$'}h2
            (export "thing" (type ${'$'}t (sub resource)))
            (export "peek" (func (param "t" (borrow ${'$'}t)) (result u32)))))
          (alias export ${'$'}h "thing" (type ${'$'}thing))
          (alias export ${'$'}h "make" (func ${'$'}make))
          (alias export ${'$'}h "peek" (func ${'$'}peek))
          (alias export ${'$'}h "take" (func ${'$'}take))
          (alias export ${'$'}h2 "peek" (func ${'$'}peek2))
          (core func ${'$'}make_l (canon lower (func ${'$'}make)))
          (core func ${'$'}peek_l (canon lower (func ${'$'}peek)))
          (core func ${'$'}take_l (canon lower (func ${'$'}take)))
          (core func ${'$'}peek2_l (canon lower (func ${'$'}peek2)))
          (core func ${'$'}drop (canon resource.drop ${'$'}thing))
          (core module ${'$'}m
            (import "h" "make" (func ${'$'}make (param i32) (result i32)))
            (import "h" "peek" (func ${'$'}peek (param i32) (result i32)))
            (import "h" "take" (func ${'$'}take (param i32) (result i32)))
            (import "h" "peek2" (func ${'$'}peek2 (param i32) (result i32)))
            (import "h" "drop" (func ${'$'}drop (param i32)))
            (func (export "run") (param ${'$'}n i32) (result i32)
              (local ${'$'}h1 i32) (local ${'$'}h2 i32) (local ${'$'}r i32) (local ${'$'}h3 i32)
              (local.set ${'$'}h1 (call ${'$'}make (local.get ${'$'}n)))
              (local.set ${'$'}h2 (call ${'$'}make (i32.add (local.get ${'$'}n) (i32.const 1))))
              (if (i32.ne (local.get ${'$'}h1) (i32.const 1)) (then unreachable))
              (if (i32.ne (local.get ${'$'}h2) (i32.const 2)) (then unreachable))
              (local.set ${'$'}r (call ${'$'}peek (local.get ${'$'}h1)))
              (local.set ${'$'}r (i32.add (local.get ${'$'}r) (call ${'$'}peek2 (local.get ${'$'}h1))))
              (call ${'$'}drop (local.get ${'$'}h2))
              (drop (call ${'$'}take (local.get ${'$'}h1)))
              (local.set ${'$'}h3 (call ${'$'}make (i32.const 5)))
              (call ${'$'}drop (local.get ${'$'}h3))
              (i32.add (i32.mul (local.get ${'$'}h3) (i32.const 1000)) (local.get ${'$'}r)))
            (func (export "keep") (param i32) (result i32) (local.get 0))
            (func (export "use") (param ${'$'}b i32) (result i32) (local ${'$'}v i32)
              (local.set ${'$'}v (call ${'$'}peek (local.get ${'$'}b)))
              (call ${'$'}drop (local.get ${'$'}b))
              (local.get ${'$'}v))
            (func (export "give") (param ${'$'}o i32) (result i32) (local ${'$'}v i32)
              (local.set ${'$'}v (call ${'$'}peek (local.get ${'$'}o)))
              (call ${'$'}drop (local.get ${'$'}o))
              (local.get ${'$'}v))
            (func (export "misuse") (param ${'$'}b i32) (result i32) (call ${'$'}take (local.get ${'$'}b)))
            (func (export "hoard") (param ${'$'}n i32) (result i32)
              (block ${'$'}done
                (loop ${'$'}l
                  (br_if ${'$'}done (i32.eqz (local.get ${'$'}n)))
                  (drop (call ${'$'}make (i32.const 1)))
                  (local.set ${'$'}n (i32.sub (local.get ${'$'}n) (i32.const 1)))
                  (br ${'$'}l)))
              (i32.const 0)))
          (core instance ${'$'}hi
            (export "make" (func ${'$'}make_l)) (export "peek" (func ${'$'}peek_l)) (export "take" (func ${'$'}take_l))
            (export "peek2" (func ${'$'}peek2_l)) (export "drop" (func ${'$'}drop)))
          (core instance ${'$'}i (instantiate ${'$'}m (with "h" (instance ${'$'}hi))))
          (func (export "run") (param "n" u32) (result u32) (canon lift (core func ${'$'}i "run")))
          (func (export "keep") (param "t" (borrow ${'$'}thing)) (result u32) (canon lift (core func ${'$'}i "keep")))
          (func (export "use") (param "t" (borrow ${'$'}thing)) (result u32) (canon lift (core func ${'$'}i "use")))
          (func (export "give") (param "t" (own ${'$'}thing)) (result u32) (canon lift (core func ${'$'}i "give")))
          (func (export "misuse") (param "t" (borrow ${'$'}thing)) (result u32) (canon lift (core func ${'$'}i "misuse")))
          (func (export "hoard") (param "n" u32) (result u32) (canon lift (core func ${'$'}i "hoard"))))
    """

    @Test
    fun `ホストのresourceのownとborrowを受け渡し、ownのdropでホストのdtorを1回だけ呼ぶ`() {
        val log = Log()
        instantiate(load(usesThing, thingHost(log))).use { c ->
            // 番号は 1 から。空きは最後に空いたものから使う（2 を drop、1 をホストに渡した後の番号は 1）
            assertEquals(u32(1080), c.call("run", args = listOf(u32(40))))
        }
        assertEquals(listOf(41, 5), log.drops)
        assertEquals(listOf(40, 40), log.peeks, "別のインターフェースの thing も同じホストの resource として通る")
        assertEquals(listOf(40), log.takes)
    }

    @Test
    fun `ホストから渡したborrowはdropしてもdtorを呼ばず、ownはdropでdtorを呼ぶ`() {
        val log = Log()
        instantiate(load(usesThing, thingHost(log))).use { c ->
            assertEquals(u32(9), c.call("use", args = listOf(CanonValue.Handle(9))))
            assertEquals(emptyList<Int>(), log.drops)
            assertEquals(u32(3), c.call("give", args = listOf(CanonValue.Handle(3))))
            assertEquals(listOf(3), log.drops)
        }
    }

    @Test
    fun `borrowをdropせずに返るとトラップ`() {
        instantiate(load(usesThing, thingHost(Log()))).use { c ->
            assertThrows<WasmTrapException> { c.call("keep", args = listOf(CanonValue.Handle(9))) }
        }
    }

    @Test
    fun `borrowのハンドルをownとして渡すとトラップ`() {
        val log = Log()
        instantiate(load(usesThing, thingHost(log))).use { c ->
            assertThrows<WasmTrapException> { c.call("misuse", args = listOf(CanonValue.Handle(9))) }
        }
        assertEquals(emptyList<Int>(), log.takes)
        assertEquals(emptyList<Int>(), log.drops)
    }

    @Test
    fun `ハンドルの数は上限でトラップ`() {
        val p = load(usesThing, thingHost(Log()))
        instantiate(p).use { c -> assertEquals(u32(0), c.call("hoard", args = listOf(u32(CanonLimits.MAX_HANDLES)))) }
        instantiate(p).use { c ->
            val e = assertThrows<WasmTrapException> { c.call("hoard", args = listOf(u32(CanonLimits.MAX_HANDLES + 1))) }
            assertTrue(e.message!!.contains("上限"), e.message)
        }
    }

    // ---- ゲストの resource ----

    private val guestResource = """
        (component
          (core module ${'$'}D
            (global ${'$'}last (mut i32) (i32.const 0))
            (func (export "dtor") (param i32) (global.set ${'$'}last (local.get 0)))
            (func (export "last") (result i32) (global.get ${'$'}last)))
          (core instance ${'$'}d (instantiate ${'$'}D))
          (alias core export ${'$'}d "dtor" (core func ${'$'}dtor))
          (type ${'$'}r (resource (rep i32) (dtor (func ${'$'}dtor))))
          (export ${'$'}re "r" (type ${'$'}r))
          (core func ${'$'}new (canon resource.new ${'$'}r))
          (core func ${'$'}rep (canon resource.rep ${'$'}r))
          (core func ${'$'}drop (canon resource.drop ${'$'}r))
          (core module ${'$'}M
            (import "r" "new" (func ${'$'}new (param i32) (result i32)))
            (import "r" "rep" (func ${'$'}rep (param i32) (result i32)))
            (import "r" "drop" (func ${'$'}drop (param i32)))
            (import "d" "last" (func ${'$'}last (result i32)))
            (memory (export "mem") 1)
            (func (export "cycle") (param i32) (result i32)
              (call ${'$'}drop (call ${'$'}new (local.get 0)))
              (call ${'$'}last))
            (func (export "mk") (param i32) (result i32) (call ${'$'}new (local.get 0)))
            (func (export "eat") (param i32) (call ${'$'}drop (local.get 0)))
            (func (export "realloc_new") (param i32 i32 i32 i32) (result i32) (drop (call ${'$'}new (i32.const 1))) (i32.const 8))
            (func (export "realloc_rep") (param i32 i32 i32 i32) (result i32) (drop (call ${'$'}rep (i32.const 1))) (i32.const 8))
            (func (export "len") (param i32 i32) (result i32) (local.get 1)))
          (core instance ${'$'}di (export "last" (func ${'$'}d "last")))
          (core instance ${'$'}ri (export "new" (func ${'$'}new)) (export "rep" (func ${'$'}rep)) (export "drop" (func ${'$'}drop)))
          (core instance ${'$'}i (instantiate ${'$'}M (with "r" (instance ${'$'}ri)) (with "d" (instance ${'$'}di))))
          (func (export "cycle") (param "rep" u32) (result u32) (canon lift (core func ${'$'}i "cycle")))
          (func (export "make") (param "rep" u32) (result (own ${'$'}re)) (canon lift (core func ${'$'}i "mk")))
          (func (export "hold") (param "rep" u32) (result u32) (canon lift (core func ${'$'}i "mk")))
          (func (export "eat") (param "r" (own ${'$'}re)) (canon lift (core func ${'$'}i "eat")))
          (func (export "len-new") (param "s" string) (result u32)
            (canon lift (core func ${'$'}i "len") (memory ${'$'}i "mem") (realloc (func ${'$'}i "realloc_new"))))
          (func (export "len-rep") (param "s" string) (result u32)
            (canon lift (core func ${'$'}i "len") (memory ${'$'}i "mem") (realloc (func ${'$'}i "realloc_rep")))))
    """

    @Test
    fun `ゲストのresourceは同じインスタンスでもdropでdtorを呼ぶ`() {
        instantiate(load(guestResource)).use { c ->
            assertEquals(u32(77), c.call("cycle", args = listOf(u32(77))))
            assertEquals(u32(78), c.call("cycle", args = listOf(u32(78))))
        }
    }

    @Test
    fun `reallocの中のresource_newはトラップし、resource_repは通る`() {
        instantiate(load(guestResource)).use { c ->
            assertEquals(u32(1), c.call("hold", args = listOf(u32(5))))
            assertEquals(u32(2), c.call("len-rep", args = listOf(CanonValue.Str.of("hi"))))
        }
        instantiate(load(guestResource)).use { c ->
            assertThrows<WasmTrapException> { c.call("len-new", args = listOf(CanonValue.Str.of("hi"))) }
        }
    }

    @Test
    fun `ゲストのresourceのownの結果はrepでホストに移る`() {
        instantiate(load(guestResource)).use { c ->
            assertEquals(CanonValue.Handle(5), c.call("make", args = listOf(u32(5))))
            // 表から外れたので、次の new も 1 番
            assertEquals(u32(1), c.call("hold", args = listOf(u32(6))))
        }
    }

    @Test
    fun `ホストからゲストのresourceのハンドルは渡せない`() {
        instantiate(load(guestResource)).use { c ->
            assertThrows<IllegalArgumentException> { c.call("eat", args = listOf(CanonValue.Handle(1))) }
            // ホストの誤りなので、コンポーネントは使えなくならない
            assertEquals(u32(4), c.call("cycle", args = listOf(u32(4))))
        }
    }

    @Test
    fun `dtorの型が違えば読み込みの失敗`() {
        assertThrows<WasmLoadException> {
            load(
                """
                (component
                  (core module ${'$'}D (func (export "dtor") (param i32) (result i32) (local.get 0)))
                  (core instance ${'$'}d (instantiate ${'$'}D))
                  (alias core export ${'$'}d "dtor" (core func ${'$'}dtor))
                  (type ${'$'}r (resource (rep i32) (dtor (func ${'$'}dtor)))))
                """,
            )
        }
    }
}
