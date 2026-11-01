package pro.ikili.kibicomputers.core.display

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TextBufferTest {
    private fun TextBuffer.row(y: Int): String = buildString {
        for (x in 0 until width) appendCodePoint(getCodePoint(x, y))
    }

    @Test
    fun `作ったばかりのバッファは空白で既定の色`() {
        val b = TextBuffer(4, 2)
        assertEquals(4, b.width)
        assertEquals(2, b.height)
        for (y in 0 until 2) {
            for (x in 0 until 4) {
                assertEquals(' '.code, b.getCodePoint(x, y))
                assertEquals(TextBuffer.DEFAULT_FOREGROUND, b.getForeground(x, y))
                assertEquals(TextBuffer.DEFAULT_BACKGROUND, b.getBackground(x, y))
            }
        }
    }

    @Test
    fun `カーソルは置いた位置を覚え変わったときだけ版が上がりcopyOfにも写る`() {
        val b = TextBuffer(4, 2)
        assertEquals(false, b.cursorVisible)
        val r0 = b.revision
        b.setCursor(3, 1, true)
        assertEquals(3, b.cursorX)
        assertEquals(1, b.cursorY)
        assertEquals(true, b.cursorVisible)
        val r1 = b.revision
        assertTrue(r1 > r0)
        b.setCursor(3, 1, true)
        assertEquals(r1, b.revision)
        val c = b.copyOf()
        assertEquals(3, c.cursorX)
        assertEquals(true, c.cursorVisible)
        assertThrows<IllegalArgumentException> { b.setCursor(-1, 0, true) }
    }

    @Test
    fun `大きさが正でないと作れない`() {
        assertThrows<IllegalArgumentException> { TextBuffer(0, 1) }
        assertThrows<IllegalArgumentException> { TextBuffer(1, -1) }
        assertThrows<IllegalArgumentException> { TextBuffer(Int.MAX_VALUE, Int.MAX_VALUE) }
    }

    @Test
    fun `画面の外を読むと例外`() {
        val b = TextBuffer(3, 3)
        assertThrows<IndexOutOfBoundsException> { b.getCodePoint(3, 0) }
        assertThrows<IndexOutOfBoundsException> { b.getForeground(0, -1) }
        assertThrows<IndexOutOfBoundsException> { b.getBackground(-1, 0) }
    }

    @Test
    fun `setは画面の外を無視し色の上位ビットを捨てる`() {
        val b = TextBuffer(3, 3)
        b.set(1, 2, 'A'.code, 0x7F123456, -1)
        assertEquals('A'.code, b.getCodePoint(1, 2))
        assertEquals(0x123456, b.getForeground(1, 2))
        assertEquals(0xFFFFFF, b.getBackground(1, 2))
        val before = b.copyOf()
        b.set(3, 0, 'B'.code, 0, 0)
        b.set(-1, 0, 'B'.code, 0, 0)
        assertTrue(b.contentEquals(before))
        assertThrows<IllegalArgumentException> { b.set(0, 0, 0x110000, 0, 0) }
    }

    @Test
    fun `writeは1コードポイントを1セルに書き次のx座標を返す`() {
        val b = TextBuffer(6, 2)
        assertEquals(5, b.write(1, 0, "a😀漢b", 0xFF0000, 0x00FF00))
        assertEquals(" a" + String(Character.toChars(0x1F600)) + "漢b ", b.row(0))
        assertEquals(0xFF0000, b.getForeground(2, 0))
        assertEquals(0x00FF00, b.getBackground(4, 0))
        assertEquals(TextBuffer.DEFAULT_FOREGROUND, b.getForeground(5, 0))
    }

    @Test
    fun `writeははみ出した分を捨てて折り返さない`() {
        val b = TextBuffer(4, 2)
        assertEquals(3, b.write(-2, 0, "abcde", 0, 0))
        assertEquals("cde ", b.row(0))
        assertEquals(6, b.write(2, 1, "wxyz", 0, 0))
        assertEquals("  wx", b.row(1))
        b.write(0, 5, "zzz", 0, 0)
        assertEquals("cde ", b.row(0))
        assertEquals(Int.MAX_VALUE, b.write(Int.MAX_VALUE - 1, 0, "abc", 0, 0))
    }

    @Test
    fun `writeは対になっていないサロゲートを置き換える`() {
        val b = TextBuffer(3, 1)
        b.write(0, 0, "a\uD800b", 0, 0)
        assertEquals(0xFFFD, b.getCodePoint(1, 0))
        assertEquals('b'.code, b.getCodePoint(2, 0))
    }

    @Test
    fun `fillは画面の内側だけを塗る`() {
        val b = TextBuffer(4, 3)
        b.fill(-1, 1, 3, 10, '#'.code, 1, 2)
        assertEquals("    ", b.row(0))
        assertEquals("##  ", b.row(1))
        assertEquals("##  ", b.row(2))
        assertEquals(1, b.getForeground(1, 2))
        assertEquals(2, b.getBackground(0, 1))
        b.fill(0, 0, Int.MAX_VALUE, Int.MAX_VALUE, '.'.code, 0, 0)
        assertEquals("....", b.row(2))
        b.fill(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, 'x'.code, 0, 0)
        assertEquals("....", b.row(0))
        b.fill(0, 0, -1, 2, 'x'.code, 0, 0)
        assertEquals("....", b.row(0))
    }

    @Test
    fun `copyで上へスクロールできる`() {
        val b = TextBuffer(3, 4)
        for (y in 0 until 4) b.write(0, y, "$y$y$y", y, y)
        b.copy(0, 1, 3, 3, 0, -1)
        b.fill(0, 3, 3, 1, ' '.code, 9, 9)
        assertEquals(listOf("111", "222", "333", "   "), (0 until 4).map { b.row(it) })
        assertEquals(2, b.getForeground(0, 1))
        assertEquals(3, b.getBackground(2, 2))
    }

    @Test
    fun `copyで下へスクロールできる`() {
        val b = TextBuffer(3, 4)
        for (y in 0 until 4) b.write(0, y, "$y$y$y", 0, 0)
        b.copy(0, 0, 3, 3, 0, 1)
        assertEquals(listOf("000", "000", "111", "222"), (0 until 4).map { b.row(it) })
    }

    @Test
    fun `copyは横に重なっても写す前の内容を写す`() {
        val b = TextBuffer(6, 1)
        b.write(0, 0, "abcdef", 0, 0)
        b.copy(0, 0, 4, 1, 2, 0)
        assertEquals("ababcd", b.row(0))
        b.write(0, 0, "abcdef", 0, 0)
        b.copy(2, 0, 4, 1, -2, 0)
        assertEquals("cdefef", b.row(0))
    }

    @Test
    fun `copyは写し元と写し先を画面に切り取る`() {
        val b = TextBuffer(4, 2)
        b.write(0, 0, "abcd", 0, 0)
        b.write(0, 1, "efgh", 0, 0)
        b.copy(-2, 0, 4, 1, 3, 1)
        assertEquals("efga", b.row(1))
        b.copy(0, 0, 4, 2, 10, 0)
        b.copy(0, 0, 4, 2, Int.MAX_VALUE, Int.MIN_VALUE)
        assertEquals("abcd", b.row(0))
        assertEquals("efga", b.row(1))
    }

    @Test
    fun `clearは全体を空白にする`() {
        val b = TextBuffer(2, 2)
        b.write(0, 0, "ab", 1, 2)
        b.clear()
        assertTrue(b.contentEquals(TextBuffer(2, 2)))
        b.clear(5, 6)
        assertEquals(5, b.getForeground(1, 1))
        assertEquals(6, b.getBackground(1, 1))
    }

    @Test
    fun `resizeは左上を残して増えた部分を空白にする`() {
        val b = TextBuffer(3, 2)
        b.write(0, 0, "abc", 1, 1)
        b.write(0, 1, "def", 1, 1)
        b.resize(2, 3)
        assertEquals(2, b.width)
        assertEquals(3, b.height)
        assertEquals(listOf("ab", "de", "  "), (0 until 3).map { b.row(it) })
        assertEquals(TextBuffer.DEFAULT_FOREGROUND, b.getForeground(0, 2))
        b.resize(4, 1)
        assertEquals("ab  ", b.row(0))
        assertThrows<IllegalArgumentException> { b.resize(0, 1) }
        assertEquals(4, b.width)
    }

    @Test
    fun `copyOfは独立したバッファを作る`() {
        val b = TextBuffer(2, 1)
        b.write(0, 0, "ab", 0, 0)
        val c = b.copyOf()
        assertTrue(c.contentEquals(b))
        c.set(0, 0, 'z'.code, 0, 0)
        assertFalse(c.contentEquals(b))
        assertEquals('a'.code, b.getCodePoint(0, 0))
        assertFalse(TextBuffer(2, 1).contentEquals(TextBuffer(1, 2)))
    }
}
