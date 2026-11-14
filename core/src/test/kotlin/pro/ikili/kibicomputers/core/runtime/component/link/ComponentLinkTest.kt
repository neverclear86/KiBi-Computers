package pro.ikili.kibicomputers.core.runtime.component.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.component.ComponentLoader
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.ANY_IMPORT
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.call
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.fn
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.host
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.instantiate
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.load
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.u32
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.wat
import java.util.concurrent.atomic.AtomicInteger

/** コンポーネントのインスタンス化とリンク（#263）。インスタンスの間の受け渡しは、計測なしとあり（燃料を共有する形）の両方で確かめる（#692） */
class ComponentLinkTest {
    /** 計測ありなら、燃料が減っていて、どの core インスタンスから読んでも同じこと（共有の形）を確かめる */
    private fun assertFuelShared(p: ComponentProgram, c: LinkedComponent) {
        if (!p.metered) return
        assertTrue(p.plan.modules.all { it.sharesFuel })
        val left = c.coreInstances.map(WasmInstance::fuel)
        assertEquals(1, left.toSet().size, "共有した残りはどのインスタンスから読んでも同じ: $left")
        assertTrue(left[0] < LinkTesting.DEFAULT_FUEL, "燃料が減っていない: ${left[0]}")
    }

    private val doubleHost = ComponentHost.of(
        host(
            "test:host/math",
            "double" to HostFunction(fn(listOf(CanonType.U32), CanonType.U32)) { _, args -> u32((args[0] as CanonValue.I32).value * 2) },
        ),
    )

    private val callsHost = """
        (component
          (import "test:host/math" (instance ${'$'}h
            (export "double" (func (param "x" u32) (result u32)))))
          (alias export ${'$'}h "double" (func ${'$'}double))
          (core func ${'$'}double_lower (canon lower (func ${'$'}double)))
          (core module ${'$'}m
            (import "host" "double" (func ${'$'}d (param i32) (result i32)))
            (func (export "run") (param i32) (result i32)
              local.get 0
              call ${'$'}d
              i32.const 1
              i32.add))
          (core instance ${'$'}hi (export "double" (func ${'$'}double_lower)))
          (core instance ${'$'}i (instantiate ${'$'}m (with "host" (instance ${'$'}hi))))
          (func (export "run") (param "x" u32) (result u32) (canon lift (core func ${'$'}i "run"))))
    """

    @Test
    fun `ホストの関数を呼ぶ`() {
        instantiate(load(callsHost, doubleHost)).use { c ->
            assertEquals(u32(11), c.call("run", args = listOf(u32(5))))
        }
    }

    @Test
    fun `ホストにないインターフェース・関数・違う型は読み込みの失敗`() {
        assertTrue(assertThrows<WasmLoadException> { load(callsHost) }.message!!.contains("test:host/math"))
        val noFunc = ComponentHost.of(host("test:host/math"))
        assertTrue(assertThrows<WasmLoadException> { load(callsHost, noFunc) }.message!!.contains("double"))
        val wrongType = ComponentHost.of(
            host("test:host/math", "double" to HostFunction(fn(listOf(CanonType.U64), CanonType.U32)) { _, _ -> u32(0) }),
        )
        assertTrue(assertThrows<WasmLoadException> { load(callsHost, wrongType) }.message!!.contains("型が違う"))
    }

    @Test
    fun `ホストにだけある関数は見ない`() {
        val more = ComponentHost.of(
            host(
                "test:host/math",
                "double" to HostFunction(fn(listOf(CanonType.U32), CanonType.U32)) { _, args -> u32((args[0] as CanonValue.I32).value * 2) },
                "other" to HostFunction(fn(emptyList(), null)) { _, _ -> null },
            ),
        )
        instantiate(load(callsHost, more)).use { c -> assertEquals(u32(7), c.call("run", args = listOf(u32(3)))) }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `coreインスタンスどうしでメモリ・グローバル・テーブル・関数を渡す`(metered: Boolean) {
        val p = load(
            """
            (component
              (core module ${'$'}a
                (memory (export "mem") 1)
                (global (export "g") (mut i32) (i32.const 7))
                (table (export "t") 1 funcref)
                (func ${'$'}f (result i32) i32.const 40)
                (elem (i32.const 0) ${'$'}f)
                (func (export "add") (param i32 i32) (result i32) local.get 0 local.get 1 i32.add)
                (func (export "peek") (result i32) (i32.add (i32.load (i32.const 16)) (global.get 0))))
              (core module ${'$'}b
                (import "a" "mem" (memory 1))
                (import "a" "g" (global ${'$'}g (mut i32)))
                (import "a" "t" (table 1 funcref))
                (import "a" "add" (func ${'$'}add (param i32 i32) (result i32)))
                (type ${'$'}t (func (result i32)))
                (func (export "run") (result i32)
                  (i32.store (i32.const 16) (i32.const 2))
                  (call ${'$'}add (i32.load (i32.const 16)) (global.get ${'$'}g))
                  (call_indirect (type ${'$'}t) (i32.const 0))
                  i32.add
                  (global.set ${'$'}g (i32.const 100))))
              (core instance ${'$'}ia (instantiate ${'$'}a))
              (core instance ${'$'}ib (instantiate ${'$'}b (with "a" (instance ${'$'}ia))))
              (func (export "run") (result u32) (canon lift (core func ${'$'}ib "run")))
              (func (export "peek") (result u32) (canon lift (core func ${'$'}ia "peek"))))
            """,
            metered = metered,
        )
        instantiate(p).use { c ->
            assertEquals(2, c.coreInstances.size)
            assertEquals(u32(49), c.call("run"))
            assertEquals(u32(102), c.call("peek"))
            assertFuelShared(p, c)
        }
    }

    private val stringParts = """
          (core module ${'$'}mem
            (memory (export "mem") 1)
            (global ${'$'}bump (mut i32) (i32.const 1024))
            (func (export "realloc") (param i32 i32 i32 i32) (result i32)
              (local ${'$'}p i32)
              (local.set ${'$'}p (i32.and (i32.add (global.get ${'$'}bump) (i32.sub (local.get 2) (i32.const 1)))
                (i32.sub (i32.const 0) (local.get 2))))
              (global.set ${'$'}bump (i32.add (local.get ${'$'}p) (local.get 3)))
              (local.get ${'$'}p)))
          (core instance ${'$'}m (instantiate ${'$'}mem))
          (alias core export ${'$'}m "mem" (core memory ${'$'}memory))
          (alias core export ${'$'}m "realloc" (core func ${'$'}realloc))
    """

    private val greetHost = ComponentHost.of(
        host(
            "test:host/greet",
            "greet" to HostFunction(fn(listOf(CanonType.Str), CanonType.Str)) { _, args ->
                CanonValue.Str.of("hello, " + (args[0] as CanonValue.Str).value)
            },
        ),
    )

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `文字列をreallocで渡し、post-returnは結果を写した後に呼ぶ`(metered: Boolean) {
        val p = load(
            """
            (component
              (import "test:host/greet" (instance ${'$'}h (export "greet" (func (param "name" string) (result string)))))
              $stringParts
              (alias export ${'$'}h "greet" (func ${'$'}greet))
              (core func ${'$'}greet_lower (canon lower (func ${'$'}greet) (memory ${'$'}memory) (realloc ${'$'}realloc)))
              (core module ${'$'}main
                (import "env" "mem" (memory 1))
                (import "host" "greet" (func ${'$'}greet (param i32 i32 i32)))
                (global ${'$'}pr (mut i32) (i32.const 0))
                (data (i32.const 0) "world")
                (func (export "run") (result i32)
                  (call ${'$'}greet (i32.const 0) (i32.const 5) (i32.const 64))
                  (i32.const 64))
                (func (export "post") (param i32)
                  (memory.fill (i32.load (local.get 0)) (i32.const 0) (i32.load offset=4 (local.get 0)))
                  (global.set ${'$'}pr (i32.add (global.get ${'$'}pr) (i32.const 1))))
                (func (export "post-count") (result i32) (global.get ${'$'}pr)))
              (core instance ${'$'}env (export "mem" (memory ${'$'}memory)))
              (core instance ${'$'}hi (export "greet" (func ${'$'}greet_lower)))
              (core instance ${'$'}mi (instantiate ${'$'}main (with "env" (instance ${'$'}env)) (with "host" (instance ${'$'}hi))))
              (alias core export ${'$'}mi "run" (core func ${'$'}run))
              (alias core export ${'$'}mi "post" (core func ${'$'}post))
              (func (export "run") (result string) (canon lift (core func ${'$'}run) (memory ${'$'}memory) (post-return ${'$'}post)))
              (func (export "post-count") (result u32) (canon lift (core func ${'$'}mi "post-count"))))
            """,
            greetHost,
            metered = metered,
        )
        instantiate(p).use { c ->
            assertEquals(CanonValue.Str.of("hello, world"), c.call("run"))
            assertEquals(u32(1), c.call("post-count"))
            assertFuelShared(p, c)
        }
    }

    @Test
    fun `reallocの中からimportを呼ぶとトラップし、以後は呼べない`() {
        val ping = ComponentHost.of(
            greetHost.interfaceOf("test:host/greet")!!,
            host("test:host/ping", "ping" to HostFunction(fn(emptyList(), null)) { _, _ -> null }),
        )
        val p = load(
            """
            (component
              (import "test:host/greet" (instance ${'$'}h (export "greet" (func (param "name" string) (result string)))))
              (import "test:host/ping" (instance ${'$'}ph (export "ping" (func))))
              (alias export ${'$'}ph "ping" (func ${'$'}ping))
              (core func ${'$'}ping_lower (canon lower (func ${'$'}ping)))
              (core module ${'$'}mem
                (import "host" "ping" (func ${'$'}ping))
                (memory (export "mem") 1)
                (func (export "realloc") (param i32 i32 i32 i32) (result i32)
                  call ${'$'}ping
                  i32.const 1024))
              (core instance ${'$'}pi (export "ping" (func ${'$'}ping_lower)))
              (core instance ${'$'}m (instantiate ${'$'}mem (with "host" (instance ${'$'}pi))))
              (alias core export ${'$'}m "mem" (core memory ${'$'}memory))
              (alias core export ${'$'}m "realloc" (core func ${'$'}realloc))
              (alias export ${'$'}h "greet" (func ${'$'}greet))
              (core func ${'$'}greet_lower (canon lower (func ${'$'}greet) (memory ${'$'}memory) (realloc ${'$'}realloc)))
              (core module ${'$'}main
                (import "host" "greet" (func ${'$'}greet (param i32 i32 i32)))
                (func (export "run") (call ${'$'}greet (i32.const 0) (i32.const 0) (i32.const 64))))
              (core instance ${'$'}hi (export "greet" (func ${'$'}greet_lower)))
              (core instance ${'$'}mi (instantiate ${'$'}main (with "host" (instance ${'$'}hi))))
              (func (export "run") (canon lift (core func ${'$'}mi "run"))))
            """,
            ping,
        )
        instantiate(p).use { c ->
            val e = assertThrows<WasmTrapException> { c.call("run") }
            assertTrue(e.message!!.contains("may_leave"), e.message)
            val again = assertThrows<WasmTrapException> { c.call("run") }
            assertTrue(again.message!!.contains("前にトラップした"), again.message)
        }
    }

    @Test
    fun `post-returnの中からimportを呼ぶとトラップし、ホストの関数は動かない`() {
        val pings = AtomicInteger()
        val ping = ComponentHost.of(
            host("test:host/ping", "ping" to HostFunction(fn(emptyList(), null)) { _, _ -> pings.incrementAndGet().let { null } }),
        )
        val p = load(
            """
            (component
              (import "test:host/ping" (instance ${'$'}ph (export "ping" (func))))
              (alias export ${'$'}ph "ping" (func ${'$'}ping))
              (core func ${'$'}ping_lower (canon lower (func ${'$'}ping)))
              (core module ${'$'}m
                (import "host" "ping" (func ${'$'}ping))
                (func (export "run") (result i32) i32.const 42)
                (func (export "post") (param i32) call ${'$'}ping)
                (func (export "plain") (result i32) i32.const 1))
              (core instance ${'$'}hi (export "ping" (func ${'$'}ping_lower)))
              (core instance ${'$'}i (instantiate ${'$'}m (with "host" (instance ${'$'}hi))))
              (alias core export ${'$'}i "run" (core func ${'$'}run))
              (alias core export ${'$'}i "post" (core func ${'$'}post))
              (func (export "run") (result u32) (canon lift (core func ${'$'}run) (post-return ${'$'}post)))
              (func (export "plain") (result u32) (canon lift (core func ${'$'}i "plain"))))
            """,
            ping,
        )
        instantiate(p).use { c ->
            val e = assertThrows<WasmTrapException> { c.call("run") }
            assertTrue(e.message!!.contains("may_leave"), e.message)
            // may_leave は lower の入口（引数の lift の前）で確かめるので、ホストの関数の本体は動かない
            assertEquals(0, pings.get())
            val again = assertThrows<WasmTrapException> { c.call("plain") }
            assertTrue(again.message!!.contains("前にトラップした"), again.message)
        }
    }

    @Test
    fun `post-returnからcoreどうしで直接呼ぶのはトラップしない`() {
        val p = load(
            """
            (component
              (core module ${'$'}a
                (global ${'$'}n (mut i32) (i32.const 0))
                (func (export "bump") (global.set ${'$'}n (i32.add (global.get ${'$'}n) (i32.const 1))))
                (func (export "count") (result i32) (global.get ${'$'}n)))
              (core instance ${'$'}ia (instantiate ${'$'}a))
              (core module ${'$'}b
                (import "a" "bump" (func ${'$'}bump))
                (func (export "run") (result i32) i32.const 42)
                (func (export "post") (param i32) call ${'$'}bump))
              (core instance ${'$'}ib (instantiate ${'$'}b (with "a" (instance ${'$'}ia))))
              (alias core export ${'$'}ib "run" (core func ${'$'}run))
              (alias core export ${'$'}ib "post" (core func ${'$'}post))
              (func (export "run") (result u32) (canon lift (core func ${'$'}run) (post-return ${'$'}post)))
              (func (export "count") (result u32) (canon lift (core func ${'$'}ia "count"))))
            """,
        )
        instantiate(p).use { c ->
            assertEquals(u32(42), c.call("run"))
            assertEquals(u32(1), c.call("count"))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `入れ子のコンポーネントの関数を呼び、post-returnは呼ぶ側が写した後`(metered: Boolean) {
        val p = load(
            """
            (component
              (component ${'$'}inner
                (core module ${'$'}m
                  (memory (export "mem") 1)
                  (global ${'$'}pr (mut i32) (i32.const 0))
                  (data (i32.const 100) "inner!")
                  (func (export "get") (result i32)
                    (i32.store (i32.const 200) (i32.const 100))
                    (i32.store (i32.const 204) (i32.const 6))
                    (i32.const 200))
                  (func (export "post") (param i32)
                    (memory.fill (i32.const 100) (i32.const 0) (i32.const 6))
                    (global.set ${'$'}pr (i32.add (global.get ${'$'}pr) (i32.const 1))))
                  (func (export "count") (result i32) (global.get ${'$'}pr)))
                (core instance ${'$'}i (instantiate ${'$'}m))
                (alias core export ${'$'}i "mem" (core memory ${'$'}mm))
                (alias core export ${'$'}i "get" (core func ${'$'}get))
                (alias core export ${'$'}i "post" (core func ${'$'}post))
                (func (export "get") (result string) (canon lift (core func ${'$'}get) (memory ${'$'}mm) (post-return ${'$'}post)))
                (func (export "count") (result u32) (canon lift (core func ${'$'}i "count"))))
              (instance ${'$'}in (instantiate ${'$'}inner))
              $stringParts
              (alias export ${'$'}in "get" (func ${'$'}get))
              (core func ${'$'}get_lower (canon lower (func ${'$'}get) (memory ${'$'}memory) (realloc ${'$'}realloc)))
              (core module ${'$'}main
                (import "env" "mem" (memory 1))
                (import "inner" "get" (func ${'$'}get (param i32)))
                (func (export "run") (result i32) (call ${'$'}get (i32.const 64)) (i32.const 64)))
              (core instance ${'$'}env (export "mem" (memory ${'$'}memory)))
              (core instance ${'$'}gi (export "get" (func ${'$'}get_lower)))
              (core instance ${'$'}mi (instantiate ${'$'}main (with "env" (instance ${'$'}env)) (with "inner" (instance ${'$'}gi))))
              (func (export "run") (result string) (canon lift (core func ${'$'}mi "run") (memory ${'$'}memory)))
              (alias export ${'$'}in "count" (func ${'$'}count))
              (export "count" (func ${'$'}count)))
            """,
            metered = metered,
        )
        instantiate(p).use { c ->
            assertEquals(3, c.coreInstances.size)
            assertEquals(CanonValue.Str.of("inner!"), c.call("run"))
            assertEquals(u32(1), c.call("count"))
            assertFuelShared(p, c)
        }
    }

    @Test
    fun `インスタンスのexportの関数を呼べる`() {
        val p = load(
            """
            (component
              (core module ${'$'}m (func (export "f") (result i32) i32.const 9))
              (core instance ${'$'}i (instantiate ${'$'}m))
              (func ${'$'}f (result u32) (canon lift (core func ${'$'}i "f")))
              (instance ${'$'}e (export "f" (func ${'$'}f)))
              (export "test:guest/api" (instance ${'$'}e)))
            """,
        )
        instantiate(p).use { c -> assertEquals(u32(9), c.call("test:guest/api", "f")) }
    }

    @Test
    fun `同じ中身のcoreモジュールは1つにまとめ、インスタンスは別に作る`() {
        val module = """(core module (global ${'$'}g (mut i32) (i32.const 0))
            (func (export "inc") (result i32) (global.set ${'$'}g (i32.add (global.get ${'$'}g) (i32.const 1))) (global.get ${'$'}g)))"""
        val p = load(
            """
            (component
              $module
              $module
              (core instance ${'$'}a (instantiate 0))
              (core instance ${'$'}b (instantiate 1))
              (func (export "a") (result u32) (canon lift (core func ${'$'}a "inc")))
              (func (export "b") (result u32) (canon lift (core func ${'$'}b "inc"))))
            """,
        )
        assertEquals(1, p.moduleCount)
        assertEquals(2, p.plan.steps.size)
        instantiate(p).use { c ->
            assertEquals(u32(1), c.call("a"))
            assertEquals(u32(2), c.call("a"))
            assertEquals(u32(1), c.call("b"))
        }
    }

    private val loop = """
        (component
          (core module ${'$'}a
            (func (export "spin") (param i32) (result i32)
              (local ${'$'}i i32)
              (loop ${'$'}l
                (local.set ${'$'}i (i32.add (local.get ${'$'}i) (i32.const 1)))
                (br_if ${'$'}l (i32.lt_u (local.get ${'$'}i) (local.get 0))))
              (local.get ${'$'}i)))
          (core instance ${'$'}ia (instantiate ${'$'}a))
          (core module ${'$'}b
            (import "a" "spin" (func ${'$'}spin (param i32) (result i32)))
            (func (export "run") (param i32) (result i32) (call ${'$'}spin (local.get 0))))
          (core instance ${'$'}ib (instantiate ${'$'}b (with "a" (instance ${'$'}ia))))
          (func (export "run") (param "n" u32) (result u32) (canon lift (core func ${'$'}ib "run"))))
    """

    @Test
    fun `燃料は全coreインスタンスで共有する`() {
        val p = load(loop, metered = true)
        assertTrue(p.plan.modules.all { it.sharesFuel })
        instantiate(p, fuel = 1_000_000).use { c ->
            assertEquals(u32(1000), c.call("run", args = listOf(u32(1000))))
            val left = c.coreInstances.map(WasmInstance::fuel)
            assertEquals(1, left.toSet().size, "共有した残りはどのインスタンスから読んでも同じ: $left")
            assertTrue(left[0] < 1_000_000 - 1000, "ループの分を引いた: ${left[0]}")
            c.coreInstances[1].fuel = 5
            assertEquals(5, c.coreInstances[0].fuel)
        }
        instantiate(p, fuel = 100).use { c ->
            val e = assertThrows<WasmTrapException> { c.call("run", args = listOf(u32(100_000))) }
            assertEquals(WasmTrapKind.FUEL_EXHAUSTED, e.kind)
        }
    }

    @Test
    fun `coreモジュールの関数の本体はEndiveが検証する`() {
        val bytes = wat("""(component (core module (func (result i32))))""")
        assertNotNull(ComponentLoader.load(bytes, policy = ANY_IMPORT))
        assertThrows<WasmLoadException> { ComponentProgram.load(LinkTesting.runtime, bytes, ComponentHost.NONE, metered = false, policy = ANY_IMPORT) }
        assertThrows<WasmLoadException> { ComponentProgram.load(LinkTesting.runtime, bytes, ComponentHost.NONE, metered = true, policy = ANY_IMPORT) }
    }

    /** core モジュール 1 つだけのコンポーネント（[sections] は型・関数のセクションの後に足すセクション） */
    private fun componentWith(vararg sections: Int): ByteArray {
        val module = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00) +
            bytes(0x01, 0x04, 0x01, 0x60, 0x00, 0x00) + bytes(0x03, 0x02, 0x01, 0x00) + bytes(*sections)
        require(module.size < 0x80)
        return byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x0d, 0x00, 0x01, 0x00) + bytes(0x01, module.size) + module
    }

    private fun bytes(vararg b: Int): ByteArray = ByteArray(b.size) { b[it].toByte() }

    private fun assertEndiveRejects(bytes: ByteArray) {
        for (metered in listOf(false, true)) {
            assertThrows<WasmLoadException>("metered=$metered") {
                ComponentProgram.load(LinkTesting.runtime, bytes, ComponentHost.NONE, metered = metered, policy = ANY_IMPORT)
            }
        }
    }

    @Test
    fun `coreモジュールの元のバイト列の壊れはEndiveが断る`() {
        // 正しい形は通る
        val ok = componentWith(0x0a, 0x04, 0x01, 0x02, 0x00, 0x0b)
        assertEquals(1, ComponentProgram.load(LinkTesting.runtime, ok, ComponentHost.NONE, metered = true, policy = ANY_IMPORT).moduleCount)
        // 関数の本体の end の後ろに余り
        assertEndiveRejects(componentWith(0x0a, 0x05, 0x01, 0x03, 0x00, 0x0b, 0x01))
        // コードのセクションの、最後の本体の後ろに余り
        assertEndiveRejects(componentWith(0x0a, 0x05, 0x01, 0x02, 0x00, 0x0b, 0x00))
        // カスタム セクションの名前が UTF-8 でない
        assertEndiveRejects(componentWith(0x0a, 0x04, 0x01, 0x02, 0x00, 0x0b, 0x00, 0x03, 0x02, 0xff, 0xfe))
        // カスタム セクションの名前の長さがセクションを超える
        assertEndiveRejects(componentWith(0x0a, 0x04, 0x01, 0x02, 0x00, 0x0b, 0x00, 0x02, 0x05, 0x61))
    }

    @Test
    fun `インスタンス化の途中で失敗したら作ったものを閉じる`() {
        val p = load(
            """
            (component
              (core module ${'$'}a (func (export "f")))
              (core instance ${'$'}ia (instantiate ${'$'}a))
              (core module ${'$'}b (func ${'$'}s unreachable) (start ${'$'}s))
              (core instance ${'$'}ib (instantiate ${'$'}b)))
            """,
        )
        val created = ArrayList<WasmInstance>()
        val env = object : LinkEnv {
            override fun onInstanceCreated(instance: WasmInstance) {
                created += instance
            }
        }
        assertThrows<WasmTrapException> { instantiate(p, env) }
        assertEquals(1, created.size)
        assertThrows<IllegalStateException> { created[0].call("f") }
    }

    /** 呼ばれると、呼んだコンポーネントの "run" を入れ子で呼ぶホスト（#691）。[swallow] なら断られた例外を握りつぶして 0 を返す */
    private fun reenteringHost(swallow: Boolean, rejected: MutableList<IllegalStateException>) = ComponentHost.of(
        host(
            "test:host/math",
            "double" to HostFunction(fn(listOf(CanonType.U32), CanonType.U32)) { call, _ ->
                try {
                    call.component.call(call.component.function("run")!!, listOf(u32(1)))
                } catch (e: IllegalStateException) {
                    rejected += e
                    if (!swallow) throw e
                    u32(0)
                }
            },
        ),
    )

    @Test
    fun `ホストの関数の中からcallを呼ぶと断られ、外の呼び出しも失敗して以後は呼べない`() {
        val rejected = ArrayList<IllegalStateException>()
        instantiate(load(callsHost, reenteringHost(swallow = false, rejected))).use { c ->
            val e = assertThrows<IllegalStateException> { c.call("run", args = listOf(u32(5))) }
            assertTrue(e.message!!.contains("ホストの関数の中"), e.message)
            assertEquals(1, rejected.size)
            val again = assertThrows<WasmTrapException> { c.call("run", args = listOf(u32(5))) }
            assertTrue(again.message!!.contains("前にトラップした"), again.message)
        }
    }

    @Test
    fun `断られた入れ子のcallはコンポーネントを壊さず、その後の呼び出しは普通に動く`() {
        val rejected = ArrayList<IllegalStateException>()
        instantiate(load(callsHost, reenteringHost(swallow = true, rejected))).use { c ->
            assertEquals(u32(1), c.call("run", args = listOf(u32(5))))
            assertEquals(u32(1), c.call("run", args = listOf(u32(7))))
            assertEquals(2, rejected.size)
        }
    }

    @Test
    fun `start関数から呼ばれたホストの関数の中からcallを呼ぶと、インスタンス化が失敗して作ったものを閉じる`() {
        val p = load(
            """
            (component
              (import "test:host/math" (instance ${'$'}h
                (export "double" (func (param "x" u32) (result u32)))))
              (alias export ${'$'}h "double" (func ${'$'}double))
              (core func ${'$'}double_lower (canon lower (func ${'$'}double)))
              (core module ${'$'}a (func (export "f") (param i32) (result i32) local.get 0))
              (core instance ${'$'}ia (instantiate ${'$'}a))
              (core module ${'$'}m
                (import "host" "double" (func ${'$'}d (param i32) (result i32)))
                (func ${'$'}s i32.const 1 call ${'$'}d drop)
                (start ${'$'}s))
              (core instance ${'$'}hi (export "double" (func ${'$'}double_lower)))
              (core instance ${'$'}i (instantiate ${'$'}m (with "host" (instance ${'$'}hi))))
              (func (export "run") (param "x" u32) (result u32) (canon lift (core func ${'$'}ia "f"))))
            """,
            reenteringHost(swallow = false, ArrayList()),
        )
        val created = ArrayList<WasmInstance>()
        val env = object : LinkEnv {
            override fun onInstanceCreated(instance: WasmInstance) {
                created += instance
            }
        }
        val e = assertThrows<IllegalStateException> { instantiate(p, env) }
        assertTrue(e.message!!.contains("インスタンス化の途中"), e.message)
        assertEquals(1, created.size)
        assertThrows<IllegalStateException> { created[0].call("f", 1L) }
    }
}
