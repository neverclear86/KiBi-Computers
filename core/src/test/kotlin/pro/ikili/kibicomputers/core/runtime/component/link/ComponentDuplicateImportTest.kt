package pro.ikili.kibicomputers.core.runtime.component.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.call
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.fn
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.host
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.instantiate
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.load
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.u32

/**
 * core モジュールの同じ (module, name) の import（#690）。コンポーネントの中では許されない（wasm-tools が断る）。
 * 検証を通さずに計画まで来ても、インスタンス化の [IllegalArgumentException] でなく、読み込みの失敗になる
 */
class ComponentDuplicateImportTest {
    private val doubleHost = ComponentHost.of(
        host(
            "test:host/math",
            "double" to HostFunction(fn(listOf(CanonType.U32), CanonType.U32)) { _, args -> u32((args[0] as CanonValue.I32).value * 2) },
        ),
    )

    /** ほかのインスタンスの export（`export`）、canon lower（`lower`）、トラップする canon（`trap`）を重複して import するもの */
    private fun duplicated(kind: String): String {
        val (provider, imports) = when (kind) {
            "export" -> """
                (core module ${'$'}a (func (export "f") (param i32) (result i32) local.get 0))
                (core instance ${'$'}p (instantiate ${'$'}a))
            """ to """(import "x" "f" (func (param i32) (result i32))) (import "x" "f" (func (param i32) (result i32)))"""
            "lower" -> """
                (import "test:host/math" (instance ${'$'}h (export "double" (func (param "x" u32) (result u32)))))
                (alias export ${'$'}h "double" (func ${'$'}double))
                (core func ${'$'}lowered (canon lower (func ${'$'}double)))
                (core instance ${'$'}p (export "f" (func ${'$'}lowered)))
            """ to """(import "x" "f" (func (param i32) (result i32))) (import "x" "f" (func (param i32) (result i32)))"""
            "trap" -> """
                (type ${'$'}r (resource (rep i32)))
                (core func ${'$'}new (canon resource.new ${'$'}r))
                (core instance ${'$'}p (export "f" (func ${'$'}new)))
            """ to """(import "x" "f" (func (param i32) (result i32))) (import "x" "f" (func (param i32) (result i32)))"""
            "global" -> """
                (core module ${'$'}a (global (export "f") (mut i32) (i32.const 0)))
                (core instance ${'$'}p (instantiate ${'$'}a))
            """ to """(import "x" "f" (global (mut i32))) (import "x" "f" (global (mut i32)))"""
            else -> error(kind)
        }
        return """
            (component
              $provider
              (core module ${'$'}m $imports)
              (core instance ${'$'}i (instantiate ${'$'}m (with "x" (instance ${'$'}p)))))
        """
    }

    @ParameterizedTest
    @ValueSource(strings = ["export", "lower", "trap", "global"])
    fun `重複したimportはwasm-toolsの検証で読み込みの失敗`(kind: String) {
        val e = assertThrows<WasmLoadException> { load(duplicated(kind), doubleHost) }
        assertTrue(e.message!!.contains("duplicate import"), e.message)
    }

    @ParameterizedTest
    @ValueSource(strings = ["export", "lower", "trap", "global"])
    fun `検証を通さなくても、重複したimportは計画で読み込みの失敗`(kind: String) {
        for (metered in listOf(false, true)) {
            val e = assertThrows<WasmLoadException> { load(duplicated(kind), doubleHost, metered = metered, validate = {}) }
            assertTrue(e.message!!.contains("x.f を2回書いている"), e.message)
        }
    }

    @Test
    fun `検証を通さなくても、型の違う重複は型の違いとして断る`() {
        val text = """
            (component
              (core module ${'$'}a (func (export "f") (param i32) (result i32) local.get 0))
              (core instance ${'$'}p (instantiate ${'$'}a))
              (core module ${'$'}m (import "x" "f" (func (param i32) (result i32))) (import "x" "f" (func (param i64) (result i32))))
              (core instance ${'$'}i (instantiate ${'$'}m (with "x" (instance ${'$'}p)))))
        """
        val e = assertThrows<WasmLoadException> { load(text, validate = {}) }
        assertTrue(e.message!!.contains("型が違う"), e.message)
    }

    @Test
    fun `NULを含む別々の名前は別のimportとしてつなぐ`() {
        // 以前は (module, name) を U+0000 でつないだ文字列を鍵にしていて、("a\0", "b") と ("a", "\0b") が同じ鍵になった
        val p = load(
            """
            (component
              (core module ${'$'}a
                (func (export "x") (result i32) i32.const 1)
                (func (export "y") (result i32) i32.const 2))
              (core instance ${'$'}ia (instantiate ${'$'}a))
              (core instance ${'$'}j (export "b" (func ${'$'}ia "x")))
              (core instance ${'$'}k (export "\00b" (func ${'$'}ia "y")))
              (core module ${'$'}b
                (import "a\00" "b" (func ${'$'}x (result i32)))
                (import "a" "\00b" (func ${'$'}y (result i32)))
                (func (export "run") (result i32) (i32.add (i32.mul (call ${'$'}x) (i32.const 10)) (call ${'$'}y))))
              (core instance ${'$'}ib (instantiate ${'$'}b (with "a\00" (instance ${'$'}j)) (with "a" (instance ${'$'}k))))
              (func (export "run") (result u32) (canon lift (core func ${'$'}ib "run"))))
            """,
        )
        instantiate(p).use { c -> assertEquals(u32(12), c.call("run")) }
    }
}
