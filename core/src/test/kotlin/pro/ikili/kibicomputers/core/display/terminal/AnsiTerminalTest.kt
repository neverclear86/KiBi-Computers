package pro.ikili.kibicomputers.core.display.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.display.ScreenSyncReceiver
import pro.ikili.kibicomputers.core.display.ScreenSyncSender
import pro.ikili.kibicomputers.core.display.ScreenSyncTarget
import pro.ikili.kibicomputers.core.display.PixelBuffer
import pro.ikili.kibicomputers.core.display.TextBuffer

class AnsiTerminalTest {
    private class Recorder : TerminalListener {
        val replies = StringBuilder()
        var bells = 0
        override fun reply(bytes: ByteArray) {
            replies.append(bytes.decodeToString())
        }
        override fun bell() {
            bells++
        }
    }

    private fun term(w: Int = 10, h: Int = 4, colors: Int = 256, listener: TerminalListener = TerminalListener.NONE) =
        AnsiTerminal(TextBuffer(w, h), colors, listener)

    private fun AnsiTerminal.row(y: Int): String = buildString {
        for (x in 0 until buffer.width) appendCodePoint(buffer.getCodePoint(x, y))
    }.trimEnd()

    private fun AnsiTerminal.cursor(): Pair<Int, Int> = cursorX to cursorY

    @Test
    fun `publishes cursor to the buffer`() {
        val t = term()
        assertFalse(t.buffer.cursorVisible)
        t.write("ab\r\ncd")
        assertEquals(Triple(2, 1, true), Triple(t.buffer.cursorX, t.buffer.cursorY, t.buffer.cursorVisible))
        t.write("\u001b[?25l\u001b[4;5H")
        assertEquals(Triple(4, 3, false), Triple(t.buffer.cursorX, t.buffer.cursorY, t.buffer.cursorVisible))
        t.reset()
        assertEquals(Triple(0, 0, true), Triple(t.buffer.cursorX, t.buffer.cursorY, t.buffer.cursorVisible))
    }

    @Test
    fun `prints text and handles CR LF`() {
        val t = term()
        t.write("ab\r\ncd")
        assertEquals("ab", t.row(0))
        assertEquals("cd", t.row(1))
        assertEquals(2 to 1, t.cursor())
    }

    @Test
    fun `LF alone moves down without returning`() {
        val t = term()
        t.write("ab\ncd")
        assertEquals("  cd", t.row(1))
    }

    @Test
    fun `wraps at right edge only when next char arrives`() {
        val t = term(w = 4)
        t.write("abcd")
        assertEquals(3 to 0, t.cursor())
        assertEquals("", t.row(1))
        t.write("e")
        assertEquals("abcd", t.row(0))
        assertEquals("e", t.row(1))
        assertEquals(1 to 1, t.cursor())
    }

    @Test
    fun `CR after full line does not add blank line`() {
        val t = term(w = 4)
        t.write("abcd\r\nx")
        assertEquals("x", t.row(1))
    }

    @Test
    fun `autowrap off overwrites last column`() {
        val t = term(w = 4)
        t.write("\u001b[?7labcdef")
        assertEquals("abcf", t.row(0))
        assertEquals("", t.row(1))
    }

    @Test
    fun `scrolls when writing past bottom`() {
        val t = term(w = 4, h = 3)
        t.write("1\r\n2\r\n3\r\n4")
        assertEquals("2", t.row(0))
        assertEquals("3", t.row(1))
        assertEquals("4", t.row(2))
    }

    @Test
    fun `auto wrap at bottom scrolls after flushing the full row`() {
        val t = term(w = 4, h = 3)
        // 最下行に着いた状態で、折り返しを含む文字列を1回の write で送る
        t.write("1\r\n2\r\n")
        t.write("abcde")
        assertEquals("2", t.row(0))
        assertEquals("abcd", t.row(1))
        assertEquals("e", t.row(2))
        assertEquals(1 to 2, t.cursor())
    }

    @Test
    fun `wide char wrap at bottom scrolls after flushing the full row`() {
        val t = term(w = 4, h = 3)
        t.write("1\r\n2\r\n")
        t.write("abcあ")
        assertEquals("2", t.row(0))
        assertEquals("abc", t.row(1))
        assertEquals('あ'.code, t.buffer.getCodePoint(0, 2))
    }

    @Test
    fun `cursor movement sequences`() {
        val t = term()
        t.write("\u001b[3;5H")
        assertEquals(4 to 2, t.cursor())
        t.write("\u001b[A\u001b[2D")
        assertEquals(2 to 1, t.cursor())
        t.write("\u001b[B\u001b[10C")
        assertEquals(9 to 2, t.cursor())
        t.write("\u001b[100A")
        assertEquals(9 to 0, t.cursor())
        t.write("\u001b[G")
        assertEquals(0 to 0, t.cursor())
        t.write("\u001b[4d")
        assertEquals(0 to 3, t.cursor())
        t.write("\u001b[H")
        assertEquals(0 to 0, t.cursor())
        t.write("\u001b[2E")
        assertEquals(0 to 2, t.cursor())
    }

    @Test
    fun `erase in line and display`() {
        val t = term(w = 5, h = 3)
        t.write("aaaaa\r\nbbbbb\r\nccccc")
        t.write("\u001b[2;3H\u001b[K")
        assertEquals("bb", t.row(1))
        t.write("\u001b[1K")
        assertEquals("", t.row(1))
        t.write("\u001b[1;3H\u001b[J")
        assertEquals("aa", t.row(0))
        assertEquals("", t.row(2))
        t.write("\u001b[2J")
        assertEquals("", t.row(0))
    }

    @Test
    fun `erase uses current background`() {
        val t = term(w = 3, h = 1, colors = 16)
        t.write("\u001b[41m\u001b[2J")
        assertEquals(0xCD0000, t.buffer.getBackground(2, 0))
    }

    @Test
    fun `scroll region confines scrolling`() {
        val t = term(w = 3, h = 5)
        t.write("0\r\n1\r\n2\r\n3\r\n4")
        t.write("\u001b[2;4r")
        assertEquals(0 to 0, t.cursor())
        t.write("\u001b[4;1H\nX")
        assertEquals(listOf("0", "2", "3", "X", "4"), (0 until 5).map { t.row(it) })
        // 上の端で RI すると下へずれる
        t.write("\u001b[2;1H\u001bMY")
        assertEquals(listOf("0", "Y", "2", "3", "4"), (0 until 5).map { t.row(it) })
    }

    @Test
    fun `insert and delete lines inside region`() {
        val t = term(w = 3, h = 4)
        t.write("a\r\nb\r\nc\r\nd")
        t.write("\u001b[2;1H\u001b[L")
        assertEquals(listOf("a", "", "b", "c"), (0 until 4).map { t.row(it) })
        t.write("\u001b[2M")
        assertEquals(listOf("a", "c", "", ""), (0 until 4).map { t.row(it) })
    }

    @Test
    fun `insert delete and erase characters`() {
        val t = term(w = 6, h = 1)
        t.write("abcdef\u001b[1;2H\u001b[2@")
        assertEquals("a  bcd", t.row(0))
        t.write("\u001b[3P")
        assertEquals("acd", t.row(0))
        t.write("\u001b[2X")
        assertEquals("a", t.row(0))
    }

    @Test
    fun `insert mode shifts text right`() {
        val t = term(w = 6, h = 1)
        t.write("abc\r\u001b[4hXY\u001b[4lZ")
        assertEquals("XYZbc", t.row(0))
    }

    @Test
    fun `scroll up and down sequences`() {
        val t = term(w = 2, h = 3)
        t.write("a\r\nb\r\nc\u001b[S")
        assertEquals(listOf("b", "c", ""), (0 until 3).map { t.row(it) })
        t.write("\u001b[2T")
        assertEquals(listOf("", "", "b"), (0 until 3).map { t.row(it) })
    }

    @Test
    fun `tabs default every eight and can be changed`() {
        val t = term(w = 20, h = 1)
        t.write("\tx")
        assertEquals(9, t.cursorX)
        t.write("\r\u001b[3g\u001b[1;4H\u001bH\r\t")
        assertEquals(3, t.cursorX)
        t.write("\t")
        assertEquals(19, t.cursorX)
        t.write("\u001b[Z")
        assertEquals(3, t.cursorX)
    }

    @Test
    fun `sgr colors in 256 color mode`() {
        val t = term(colors = 256)
        t.write("\u001b[31;42ma\u001b[38;5;196mb\u001b[38;2;10;20;30mc\u001b[0md")
        assertEquals(0xCD0000, t.buffer.getForeground(0, 0))
        assertEquals(0x00CD00, t.buffer.getBackground(0, 0))
        assertEquals(0xFF0000, t.buffer.getForeground(1, 0))
        assertEquals(TerminalColors.palette(TerminalColors.nearest256(0x0A141E)), t.buffer.getForeground(2, 0))
        assertEquals(TextBuffer.DEFAULT_FOREGROUND, t.buffer.getForeground(3, 0))
        assertEquals(TextBuffer.DEFAULT_BACKGROUND, t.buffer.getBackground(3, 0))
    }

    @Test
    fun `colon separated extended colors`() {
        val t = term(colors = 1 shl 24)
        t.write("\u001b[38:2::1:2:3ma\u001b[48:2:4:5:6mb\u001b[38:5:21mc")
        assertEquals(0x010203, t.buffer.getForeground(0, 0))
        assertEquals(0x040506, t.buffer.getBackground(1, 0))
        assertEquals(TerminalColors.palette(21), t.buffer.getForeground(2, 0))
    }

    @Test
    fun `16 color mode rounds palette and rgb`() {
        val t = term(colors = 16)
        t.write("\u001b[38;5;196ma\u001b[38;2;250;250;250mb\u001b[94mc")
        assertEquals(0xFF0000, t.buffer.getForeground(0, 0))
        assertEquals(0xFFFFFF, t.buffer.getForeground(1, 0))
        assertEquals(0x5C5CFF, t.buffer.getForeground(2, 0))
    }

    @Test
    fun `bold brightens basic colors and inverse swaps`() {
        val t = term(colors = 16)
        t.write("\u001b[1;31ma\u001b[22;7mb")
        assertEquals(0xFF0000, t.buffer.getForeground(0, 0))
        assertEquals(TextBuffer.DEFAULT_BACKGROUND, t.buffer.getForeground(1, 0))
        assertEquals(0xCD0000, t.buffer.getBackground(1, 0))
    }

    @Test
    fun `monochrome keeps text readable`() {
        val t = term(colors = 2)
        t.write("\u001b[30ma\u001b[47mb\u001b[0;7mc\u001b[0;34;44;8md")
        // 黒の文字は黒の背景では読めないので白にする
        assertEquals(0xFFFFFF, t.buffer.getForeground(0, 0))
        // 明るい背景には黒の文字
        assertEquals(0xFFFFFF, t.buffer.getBackground(1, 0))
        assertEquals(0x000000, t.buffer.getForeground(1, 0))
        // 反転
        assertEquals(0xFFFFFF, t.buffer.getBackground(2, 0))
        assertEquals(0x000000, t.buffer.getForeground(2, 0))
        // 隠し文字は背景と同じ
        assertEquals(t.buffer.getBackground(3, 0), t.buffer.getForeground(3, 0))
    }

    @Test
    fun `changing max colors affects later text`() {
        val t = term(colors = 256)
        t.write("\u001b[38;5;196ma")
        t.maxColors = 2
        t.write("b")
        assertEquals(0xFF0000, t.buffer.getForeground(0, 0))
        assertEquals(0xFFFFFF, t.buffer.getForeground(1, 0))
    }

    @Test
    fun `alternate screen saves and restores main screen`() {
        val t = term(w = 4, h = 2, colors = 16)
        t.write("\u001b[31mab\u001b[0m\r\ncd")
        val before = t.buffer.copyOf()
        t.write("\u001b[?1049h")
        assertTrue(t.alternateScreen)
        assertEquals("", t.row(0))
        t.write("\u001b[HXYZ\u001b[2;1H\u001b[44mQ")
        assertEquals("XYZ", t.row(0))
        t.write("\u001b[?1049l")
        assertFalse(t.alternateScreen)
        assertTrue(t.buffer.contentEquals(before))
        assertEquals(2 to 1, t.cursor())
        // 色の属性も入る前に戻る
        t.write("e")
        assertEquals(TextBuffer.DEFAULT_BACKGROUND, t.buffer.getBackground(2, 1))
    }

    @Test
    fun `alternate screen survives resize`() {
        val t = term(w = 4, h = 2)
        t.write("abcd")
        t.write("\u001b[?47h")
        t.resize(6, 3)
        t.write("\u001b[?47l")
        assertEquals("abcd", t.row(0))
        assertEquals(6, t.buffer.width)
    }

    @Test
    fun `save and restore cursor`() {
        val t = term()
        t.write("\u001b[2;3H\u001b[31m\u001b7\u001b[H\u001b[0m\u001b8x")
        assertEquals(0xCD0000, t.buffer.getForeground(2, 1))
        t.write("\u001b[4;4H\u001b[s\u001b[H\u001b[u")
        assertEquals(3 to 3, t.cursor())
    }

    @Test
    fun `origin mode makes positions relative to region`() {
        val t = term(w = 5, h = 5)
        t.write("\u001b[2;4r\u001b[?6h")
        assertEquals(0 to 1, t.cursor())
        t.write("\u001b[10;1H")
        assertEquals(0 to 3, t.cursor())
    }

    @Test
    fun `wide characters take two cells`() {
        val t = term(w = 5, h = 2)
        t.write("あい")
        assertEquals('あ'.code, t.buffer.getCodePoint(0, 0))
        assertEquals(' '.code, t.buffer.getCodePoint(1, 0))
        assertEquals('い'.code, t.buffer.getCodePoint(2, 0))
        assertEquals(4 to 0, t.cursor())
        // 右端の1セルに入らない全角の文字は次の行へ
        t.write("う")
        assertEquals('う'.code, t.buffer.getCodePoint(0, 1))
        assertEquals(2 to 1, t.cursor())
    }

    @Test
    fun `overwriting right half clears wide char`() {
        val t = term(w = 5, h = 1)
        t.write("あ\u001b[1;2Hx")
        assertEquals(' '.code, t.buffer.getCodePoint(0, 0))
        assertEquals('x'.code, t.buffer.getCodePoint(1, 0))
    }

    @Test
    fun `utf8 split across writes and invalid bytes`() {
        val t = term()
        val bytes = "é".encodeToByteArray()
        t.write(bytes, 0, 1)
        t.write(bytes, 1, 1)
        assertEquals('é'.code, t.buffer.getCodePoint(0, 0))
        t.write(byteArrayOf(0xFF.toByte(), 'a'.code.toByte(), 0xC3.toByte(), 'b'.code.toByte()))
        assertEquals("é�a�b", t.row(0))
    }

    @Test
    fun `escape sequence split across writes`() {
        val t = term()
        t.write("\u001b[")
        t.write("3")
        t.write(";4H")
        assertEquals(3 to 2, t.cursor())
    }

    @Test
    fun `osc and dcs strings are ignored`() {
        val t = term()
        t.write("\u001b]0;title\u0007a\u001b]2;x\u001b\\b\u001bPqdata\u001b\\c")
        assertEquals("abc", t.row(0))
    }

    @Test
    fun `cancel aborts sequence and unknown sequences are ignored`() {
        val t = term()
        t.write("\u001b[3\u0018a\u001b[?999hb\u001b[1\$pc\u001b[>1;2zd")
        assertEquals("abcd", t.row(0))
    }

    @Test
    fun `controls inside csi are executed`() {
        val t = term()
        t.write("ab\u001b[\r1Cx")
        assertEquals("ax", t.row(0))
    }

    @Test
    fun `device status and attributes replies`() {
        val r = Recorder()
        val t = term(listener = r)
        t.write("\u001b[2;3H\u001b[6n\u001b[5n\u001b[c\u0007")
        assertEquals("\u001b[2;3R\u001b[0n\u001b[?1;2c", r.replies.toString())
        assertEquals(1, r.bells)
    }

    @Test
    fun `dec special graphics`() {
        val t = term()
        t.write("\u001b(0lqk\u001b(Bq\u000e\u001b)0x\u000fx")
        assertEquals("┌─┐q│x", t.row(0))
    }

    @Test
    fun `cursor visibility`() {
        val t = term()
        t.write("\u001b[?25l")
        assertFalse(t.cursorVisible)
        t.write("\u001b[?25h")
        assertTrue(t.cursorVisible)
    }

    @Test
    fun `full reset clears everything`() {
        val t = term()
        t.write("\u001b[31mab\u001b[?1049h\u001b[2;3r\u001bc")
        assertFalse(t.alternateScreen)
        assertEquals("", t.row(0))
        assertEquals(0 to 0, t.cursor())
        t.write("x")
        assertEquals(TextBuffer.DEFAULT_FOREGROUND, t.buffer.getForeground(0, 0))
    }

    @Test
    fun `external resize is picked up`() {
        val t = term(w = 10, h = 4)
        t.write("\u001b[4;10H")
        t.buffer.resize(5, 2)
        t.write("x")
        assertEquals(4 to 1, t.cursor())
        assertEquals('x'.code, t.buffer.getCodePoint(4, 1))
    }

    @Test
    fun `backspace moves left and cancels pending wrap`() {
        val t = term(w = 3, h = 2)
        t.write("abc\bX")
        assertEquals("aXc", t.row(0))
        t.write("\r\b")
        assertEquals(0, t.cursorX)
    }

    @Test
    fun `combining marks are dropped`() {
        val t = term()
        t.write("éx")
        assertEquals("ex", t.row(0))
    }

    @Test
    fun `screen sync replays terminal output`() {
        class Screen(override val text: TextBuffer) : ScreenSyncTarget {
            override fun pixels(): PixelBuffer? = null
            override fun pixelsIfCreated(): PixelBuffer? = null
        }
        val server = TextBuffer(20, 6)
        val client = Screen(TextBuffer(20, 6))
        val sender = ScreenSyncSender(server)
        val receiver = ScreenSyncReceiver()
        fun sync() {
            val f = sender.flush(hasWatchers = true, hasNewWatchers = false)
            for (b in f.forWatchers) receiver.receive(b, client)
        }
        for (b in sender.flush(hasWatchers = false, hasNewWatchers = true).forNewWatchers) receiver.receive(b, client)
        val t = AnsiTerminal(server, 16)
        t.write("hello \u001b[32mworld\u001b[0m\r\n")
        sync()
        t.write("\u001b[2;10r\u001b[?1049h\u001b[5;5Hあ\u001b[L\u001b[?1049l\u001b[3;1H\u001b[44m\u001b[K")
        sync()
        repeat(10) { t.write("line $it\r\n") }
        sync()
        assertTrue(server.contentEquals(client.text))
    }
}
