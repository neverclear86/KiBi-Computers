package pro.ikili.kibicomputers.core.runtime.component.canon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.component.ComponentLoader
import pro.ikili.kibicomputers.core.runtime.component.ComponentSection
import pro.ikili.kibicomputers.core.runtime.component.ParsedComponent
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.ListOf
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.Tuple
import pro.ikili.kibicomputers.core.wasi.kibi.WitTool

/**
 * 型の添字空間の解決のうち、`alias outer`・インスタンスの export の alias・型の export が添字を 1 つ足す道（#264。ADR 0264）。
 * 番号がずれると、後ろの型がすべて別の型を指すので、どの番号がどの型になるかを 1 つずつ確かめる
 */
class CanonTypeSpaceTest {
    private fun load(wat: String): ParsedComponent = ComponentLoader.load(WitTool.wat(wat.trimIndent()), policy = { null }).root

    private fun nested(c: ParsedComponent): List<ParsedComponent> =
        c.sections.filterIsInstance<ComponentSection.Nested>().map { it.component }

    private fun message(block: () -> Unit): String = assertThrows<WasmLoadException> { block() }.message!!

    @Test
    fun `alias outer は、外側の添字空間の同じ番号の型になる`() {
        val top = load(
            """
            (component ${'$'}top
              (type ${'$'}a (record (field "a" u32)))
              (type ${'$'}b (list u8))
              (component ${'$'}mid
                (alias outer ${'$'}top ${'$'}a (type ${'$'}oa))
                (type ${'$'}l (list ${'$'}oa))
                (alias outer ${'$'}mid ${'$'}l (type ${'$'}self))
                (alias outer ${'$'}top ${'$'}b (type ${'$'}ob))
                (type ${'$'}t (tuple ${'$'}self ${'$'}ob))
                (component ${'$'}leaf
                  (alias outer ${'$'}top ${'$'}b (type ${'$'}tb))
                  (alias outer ${'$'}mid ${'$'}t (type ${'$'}mt))
                  (type ${'$'}x (tuple ${'$'}tb ${'$'}mt))
                )
              )
            )
            """,
        )
        val mid = nested(top).single()
        val leaf = nested(mid).single()
        val topSpace = CanonTypeSpace.of(top)
        val midSpace = CanonTypeSpace.of(mid, listOf(topSpace))
        val leafSpace = CanonTypeSpace.of(leaf, listOf(midSpace, topSpace))

        assertEquals(2, topSpace.size)
        assertEquals(5, midSpace.size)
        // count 1: 親の添字空間の番号
        assertSame(topSpace.valueType(0), midSpace.valueType(0))
        assertSame(topSpace.valueType(1), midSpace.valueType(3))
        assertSame(midSpace.valueType(0), (midSpace.valueType(1) as ListOf).element)
        // count 0: 自分の添字空間の、前の番号
        assertSame(midSpace.valueType(1), midSpace.valueType(2))
        val t = midSpace.valueType(4) as Tuple
        assertSame(midSpace.valueType(1), t.fieldTypes[0])
        assertSame(topSpace.valueType(1), t.fieldTypes[1])
        // count 2: 親の親
        assertEquals(3, leafSpace.size)
        assertSame(topSpace.valueType(1), leafSpace.valueType(0))
        assertSame(midSpace.valueType(4), leafSpace.valueType(1))
        val x = leafSpace.valueType(2) as Tuple
        assertSame(leafSpace.valueType(0), x.fieldTypes[0])
        assertSame(leafSpace.valueType(1), x.fieldTypes[1])
    }

    @Test
    fun `外側の添字空間を渡さないと、alias outer は解決できないものとして持つ`() {
        val top = load(
            """
            (component ${'$'}top
              (type ${'$'}a (record (field "a" u32)))
              (component ${'$'}mid
                (type ${'$'}local (list u8))
                (alias outer ${'$'}top ${'$'}a (type ${'$'}oa))
                (alias outer ${'$'}mid ${'$'}local (type ${'$'}self))
                (type ${'$'}u (list ${'$'}oa))
                (type ${'$'}v (list ${'$'}self))
              )
            )
            """,
        )
        val mid = nested(top).single()
        // 数は合う（外側の空間がなくても、番号は 1 つ進める）
        val space = CanonTypeSpace.of(mid)
        assertEquals(5, space.size)
        assertTrue(message { space.valueType(1) }.contains("リンクで引く")) { "外側のコンポーネントの型" }
        assertTrue(message { space.valueType(3) }.contains("リンクで引く")) { "それを使う型も" }
        // count 0 と、それを使う型は解決できる
        assertSame(space.valueType(0), space.valueType(2))
        assertSame(space.valueType(0), (space.valueType(4) as ListOf).element)
        // 外側の空間を渡せば解決できる
        val withOuter = CanonTypeSpace.of(mid, listOf(CanonTypeSpace.of(top)))
        assertEquals("record{a: u32}", withOuter.valueType(1).toString())
        assertEquals("list<record{a: u32}>", withOuter.valueType(3).toString())
    }

    @Test
    fun `インスタンスの export の alias は解決できないものとして番号を 1 つ進め、型の export も番号を 1 つ進める`() {
        val c = load(
            """
            (component
              (import "i" (instance ${'$'}i (export "t" (type (sub resource)))))
              (type ${'$'}pre (list u8))
              (alias export ${'$'}i "t" (type ${'$'}it))
              (type ${'$'}a (record (field "a" u32)))
              (export ${'$'}ea "ea" (type ${'$'}a))
              (type ${'$'}after (list ${'$'}ea))
              (import "r" (type ${'$'}r (sub resource)))
              (type ${'$'}own (own ${'$'}r))
              (export ${'$'}eown "eown" (type ${'$'}own))
              (type ${'$'}viaexport (option ${'$'}eown))
              (type ${'$'}bad (own ${'$'}it))
              (type ${'$'}res (resource (rep i32)))
              (type ${'$'}ownres (own ${'$'}res))
            )
            """,
        )
        val space = CanonTypeSpace.of(c)
        // 0 instance 型（import の中に書いたもの） / 1 pre / 2 alias(解決できない) / 3 a / 4 ea / 5 after / 6 r / 7 own / 8 eown
        // / 9 viaexport / 10 bad / 11 res / 12 ownres
        assertEquals(13, space.size)
        assertTrue(message { space.valueType(0) }.contains("インスタンス型")) { "import の中に書いたインスタンス型も 1 つ数える" }
        assertEquals("list<u8>", space.valueType(1).toString())
        assertTrue(message { space.valueType(2) }.contains("インスタンスの export")) { "alias export" }
        val a = space.valueType(3)
        assertEquals("record{a: u32}", a.toString())
        // 型の export は、同じ型を指す番号を足す
        assertSame(a, space.valueType(4))
        assertSame(a, (space.valueType(5) as ListOf).element)
        // resource の識別子は、その resource を定義・import した番号
        assertEquals(6, space.resourceId(6))
        assertEquals(6, (space.valueType(7) as CanonType.Own).resource)
        assertSame(space.valueType(7), space.valueType(8))
        assertSame(space.valueType(7), (space.valueType(9) as CanonType.Option).type)
        assertTrue(message { space.valueType(10) }.contains("インスタンスの export")) { "解決できないものを使う型も" }
        assertEquals(11, space.resourceId(11))
        assertEquals(11, (space.valueType(12) as CanonType.Own).resource)
        // 値の型でないものは断る
        assertTrue(message { space.valueType(11) }.contains("値の型ではない"))
    }
}
