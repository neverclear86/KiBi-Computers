package pro.ikili.kibicomputers.core.display.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.display.TextBuffer

/** 端末のスクロールのまとめと、処理の重さの上限（#357） */
class TerminalScrollBudgetTest {
    private fun rows(b: TextBuffer): List<String> = (0 until b.height).map { y ->
        buildString { for (x in 0 until b.width) appendCodePoint(b.getCodePoint(x, y)) }.trimEnd()
    }

    /** 1バイトずつ書く（[AnsiTerminal.write] の終わりで必ずバッファに書くので、スクロールはまとまらない） */
    private fun byteByByte(w: Int, h: Int, input: String): TextBuffer {
        val t = AnsiTerminal(TextBuffer(w, h), 256)
        for (b in input.encodeToByteArray()) t.write(byteArrayOf(b))
        return t.buffer
    }

    private fun atOnce(w: Int, h: Int, input: String): TextBuffer {
        val t = AnsiTerminal(TextBuffer(w, h), 256)
        t.write(input)
        return t.buffer
    }

    @Test
    fun `まとめたスクロールは1つずつ写したのと同じ内容になる`() {
        val inputs = listOf(
            // 行を書いてから送る、送ってから書く
            (1..30).joinToString("") { "line$it\r\n" },
            "a\r\nb\r\nc\u001b[S\u001b[2Sd\u001b[3S",
            // 画面の高さ以上
            "x\r\ny\u001b[100S\u001b[Sz",
            // 色を変えながら送る（空いた行は、その時の背景色で消す）
            "\u001b[41ma\u001b[S\u001b[42m\u001b[S\u001b[0m\u001b[Sb\r\n\u001b[44m\r\n\r\n",
            // スクロール領域と、行の削除・挿入、逆方向
            "1\r\n2\r\n3\r\n4\r\n5\u001b[2;4r\u001b[4;1H\n\n\u001b[S\u001b[2;1H\u001b[M\u001b[M\u001b[L\u001bM\u001b[T\u001b[r\u001b[S",
            // 代替画面、消去、全消去
            "q\r\nw\r\ne\u001b[?1049h\u001b[S\r\nalt\u001b[S\u001b[?1049l\u001b[S\u001b[2J\u001b[S\u001bcr\u001b[S",
            // 挿入モードと文字の削除・挿入
            "abcdef\u001b[4h\u001b[1GXY\u001b[4l\u001b[S\u001b[2P\u001b[3@\u001b[S\u001b#8\u001b[S",
        )
        for (input in inputs) {
            val expected = byteByByte(8, 5, input)
            val actual = atOnce(8, 5, input)
            assertTrue(expected.contentEquals(actual), "$input: ${rows(expected)} != ${rows(actual)}")
        }
    }

    @Test
    fun `続けて来たスクロールは1回の写しと消去になる`() {
        val t = AnsiTerminal(TextBuffer(160, 50), 256)
        t.write("top\r\n")
        val before = t.buffer.revision
        t.write("\u001b[S".repeat(5000))
        // 写し・消去・カーソルの3つだけ（1回ごとに写すと 10,000 回になる）
        assertTrue(t.buffer.revision - before <= 3, "revisions: ${t.buffer.revision - before}")
        // 画面の高さ以上送ったので、全部消えている
        assertTrue(rows(t.buffer).all { it.isEmpty() })
        // 重さは、読んだバイトと画面1枚分
        assertEquals(15_000L + 160 * 50, t.cost)
    }

    @Test
    fun `上限に届いたら読むのを止め、続きを渡すと同じ内容になる`() {
        val input = (1..200).joinToString("") { "\u001b[3${it % 8}mline $it\r\n" }.encodeToByteArray()
        val whole = AnsiTerminal(TextBuffer(20, 6), 256).also { it.write(input) }.buffer

        val t = AnsiTerminal(TextBuffer(20, 6), 256)
        val budget = 500L
        var offset = 0
        var calls = 0
        while (offset < input.size) {
            val read = t.write(input, offset, input.size - offset, budget)
            assertTrue(read > 0, "少なくとも1バイトは読む")
            // 1回の操作の途中では止めないので、超えるのは画面1枚分（と1行分）まで
            assertTrue(t.cost <= budget + 20 * 6 + 20, "cost ${t.cost}")
            offset += read
            calls++
        }
        assertTrue(calls > 1, "上限で分かれる: $calls")
        assertTrue(whole.contentEquals(t.buffer), "${rows(whole)} != ${rows(t.buffer)}")
    }

    @Test
    fun `1行送りの連打は上限で止まる`() {
        val t = AnsiTerminal(TextBuffer(160, 50), 256)
        // カーソルを最後の行に置き、LF のたびにスクロールさせる
        t.write("\u001b[50;1H")
        val attack = "x\n".repeat(10_000).encodeToByteArray()
        val read = t.write(attack, 0, attack.size, 64_000)
        // 1行ごとに画面1枚分写すので、64,000 セルなら 8 行ほどで止まる
        assertTrue(read in 2..40, "read $read")
        assertTrue(t.cost < 64_000 + 160 * 50 + 160, "cost ${t.cost}")
    }
}
