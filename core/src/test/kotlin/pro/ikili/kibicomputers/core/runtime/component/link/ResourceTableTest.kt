package pro.ikili.kibicomputers.core.runtime.component.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.runtime.WasmTrapException

/** コンポーネントのインスタンス 1 つのハンドル表と、task の番号（#265） */
class ResourceTableTest {
    @Test
    fun `番号は1から振り、空きは最後に空いたものから使う`() {
        val t = ResourceTable()
        assertEquals(listOf(1, 2, 3), (1..3).map { t.addOwn(1, it * 100) })
        t.remove(1)
        t.remove(3)
        assertEquals(3, t.addOwn(1, 7))
        assertEquals(1, t.addOwn(1, 8))
        assertEquals(4, t.addOwn(1, 9))
        assertEquals(8, t.rep(1))
        assertEquals(200, t.rep(2))
        assertEquals(4, t.size)
    }

    @Test
    fun `表にない番号はトラップ`() {
        val t = ResourceTable()
        val h = t.addOwn(1, 5)
        for (i in listOf(0, -1, h + 1, 1000)) assertThrows<WasmTrapException>("$i") { t.rep(i) }
        t.remove(h)
        assertThrows<WasmTrapException> { t.rep(h) }
        assertThrows<WasmTrapException> { t.remove(h) }
    }

    @Test
    fun `ownとborrowは範囲の値に関係なく種類のタグで区別する`() {
        val t = ResourceTable()
        for (scope in listOf(0L, -1L, 1L, Long.MAX_VALUE, Long.MIN_VALUE)) {
            val b = t.addBorrow(3, 9, scope)
            assertFalse(t.isOwn(b), "範囲 $scope")
            assertEquals(scope, t.scope(b))
            val o = t.addOwn(3, 9)
            assertTrue(t.isOwn(o))
            assertThrows<IllegalStateException> { t.scope(o) }
        }
    }

    @Test
    fun `貸している数を数え、広げても中身を保つ`() {
        val t = ResourceTable()
        val hs = (1..100).map { t.addOwn(it, it) }
        t.lend(hs[50], 1)
        t.lend(hs[50], 1)
        assertEquals(2, t.lends(hs[50]))
        t.lend(hs[50], -2)
        assertEquals(0, t.lends(hs[50]))
        assertThrows<IllegalStateException> { t.lend(hs[50], -1) }
        assertEquals(hs.map { it }, hs.map { t.resource(it) })
    }

    @Test
    fun `taskの番号は折り返さず、使い切ったらトラップ`() {
        val ids = TaskIds()
        assertEquals(1L, ids.take())
        assertEquals(2L, ids.take())
        val end = TaskIds(Long.MAX_VALUE - 1)
        assertEquals(Long.MAX_VALUE - 1, end.take())
        assertThrows<WasmTrapException> { end.take() }
    }
}
