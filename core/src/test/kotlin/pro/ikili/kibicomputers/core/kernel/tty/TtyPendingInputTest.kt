package pro.ikili.kibicomputers.core.kernel.tty

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.input.KeyboardText
import pro.ikili.kibicomputers.core.kernel.io.ReadResult
import pro.ikili.kibicomputers.core.kernel.obj.ObjectTable
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.wait.WakeSink
import java.io.ByteArrayOutputStream

/** 入りきらない貼り付けの待ちの列（#609） */
class TtyPendingInputTest {
    private class RecordingSink : WakeSink {
        val wakes = ArrayList<WaitKey>()
        val pending = ArrayList<Int>()

        override fun wake(key: WaitKey) {
            wakes += key
        }

        override fun wakeObject(objectId: Int) = Unit

        override fun outputPending(objectId: Int) {
            pending += objectId
        }

        fun clear() {
            wakes.clear()
            pending.clear()
        }
    }

    private val sink = RecordingSink()

    private fun tty(input: Int = 4096): Tty = Tty(input, 16 * 1024).also { ObjectTable().add(it) }

    private fun Tty.paste(bytes: ByteArray): TtyInputResult = input(bytes, 0, bytes.size, sink, splittable = true)

    private fun Tty.paste(text: String): TtyInputResult = paste(text.toByteArray())

    private fun Tty.key(text: String): TtyInputResult = text.toByteArray().let { input(it, 0, it.size, sink) }

    private fun Tty.readBytes(max: Int = 4096): ByteArray? {
        val buf = ByteArray(max)
        return when (val r = read(buf, 0, max, sink)) {
            is ReadResult.Bytes -> buf.copyOf(r.count)
            else -> null
        }
    }

    /** 読めなくなるまで読んで、つなげて返す */
    private fun Tty.readAll(max: Int = 4096): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = readBytes(max) ?: break
            out.write(b)
        }
        return out.toByteArray()
    }

    private fun readable(t: Tty) = WaitKey(t.id, WaitCondition.READABLE)

    private val cbreak = TtyMode.COOKED.with(TtyMode.CANONICAL, false)

    private fun rawTty(input: Int = 4096): Tty = tty(input).also { it.setMode(TtyMode.RAW, sink) }

    @Test
    fun `rawに入力の上限を超えて貼ると、入りきらない分は待ち、読むたびに続きが入って全部が順番どおり読める`() {
        val t = rawTty()
        val text = ByteArray(6000) { ('a' + it % 26).code.toByte() }
        val r = t.paste(text)
        assertEquals(0, r.dropped)
        assertEquals(4096, t.inputAvailable)
        assertEquals(6000 - 4096, t.pendingInputBytes)
        val first = t.readBytes()!!
        assertEquals(4096, first.size)
        assertEquals(6000 - 4096, t.inputAvailable)
        assertEquals(0, t.pendingInputBytes)
        assertTrue((first + t.readAll()).contentEquals(text))
        assertEquals(0, t.droppedInputBytes)
        assertEquals(0, t.allocatedBytes, "空になったらバッファを手放す")
    }

    @Test
    fun `境目が多バイト文字の途中に当たると、TTYの中身は文字の境目で止まる`() {
        val t = rawTty()
        // 4095 バイト目から「あ」（3バイト）が始まる
        val text = ("a".repeat(4094) + "あい").toByteArray()
        t.paste(text)
        assertEquals(4094, t.inputAvailable, "「あ」は分けずに待たせる")
        assertEquals(6, t.pendingInputBytes)
        assertEquals("a".repeat(4094) + "あい", (t.readAll()).decodeToString())
    }

    @Test
    fun `待ちの列があるときのキーは分けずに貼り付けの後に入る`() {
        val t = rawTty(input = 16)
        t.paste("0123456789abcdefXYZ")
        assertEquals(3, t.pendingInputBytes)
        t.key("\u001b[A")
        assertEquals(6, t.pendingInputBytes, "キーも列の後ろに積む")
        assertEquals("0123456789abcd", t.readBytes(14)!!.decodeToString())
        // 空きは 14。XYZ と ESC [ A が入る
        assertEquals("efXYZ\u001b[A", t.readAll().decodeToString())
    }

    @Test
    fun `分けてはいけないキーは、全部入る空きができるまで待つ`() {
        val t = rawTty(input = 8)
        t.paste("01234567Z")
        t.key("\u001b[1;5A")
        assertEquals("0", t.readBytes(1)!!.decodeToString())
        // 空き 1 に Z だけが入り、キー（6バイト）は待つ
        assertEquals(8, t.inputAvailable)
        assertEquals(6, t.pendingInputBytes)
        assertEquals("1234567Z", t.readBytes()!!.decodeToString())
        assertEquals("\u001b[1;5A", t.readBytes()!!.decodeToString())
    }

    @Test
    fun `signalsありのCtrl+Cは、今の入力と一緒に待ちの列も捨てる`() {
        val t = tty(input = 16).also { it.setMode(cbreak, sink) }
        t.paste("x".repeat(40))
        assertTrue(t.pendingInputBytes > 0)
        val r = t.key("\u0003")
        assertEquals(listOf(TtySignal.INTERRUPT), r.signals)
        assertEquals(0, t.inputAvailable)
        assertEquals(0, t.pendingInputBytes)
        t.key("ok")
        assertEquals("ok", t.readAll().decodeToString())
    }

    @Test
    fun `signalsのないrawでは、Ctrl+Cもただのバイトとして列に積む`() {
        val t = rawTty(input = 4)
        t.paste("abcdef")
        t.key("\u0003")
        assertEquals("abcdef\u0003", t.readAll().decodeToString())
    }

    @Test
    fun `列の上限を超えた分は捨てて数える`() {
        val t = rawTty()
        val text = ByteArray(4096 + Tty.PENDING_INPUT_BYTES + 100) { 'q'.code.toByte() }
        val r = t.paste(text)
        assertEquals(100, r.dropped)
        assertEquals(100, t.droppedInputBytes)
        assertEquals(Tty.PENDING_INPUT_BYTES, t.pendingInputBytes)
        // 分けてはいけないキーは、列に入りきらなければ全部捨てる
        assertEquals(4, t.key("\u001b[1~").dropped)
        assertEquals(104, t.droppedInputBytes)
        assertEquals(4096 + Tty.PENDING_INPUT_BYTES, t.readAll().size)
    }

    @Test
    fun `canonicalで4KiBを超える複数行を貼ると、1行ずつ全部読める`() {
        val t = tty()
        val lines = (1..300).map { "line $it " + "z".repeat(20) + "\n" }
        val text = lines.joinToString("")
        assertTrue(text.length > 8000)
        // 貼り付けは LF が CR になって届く
        assertEquals(0, t.paste(text.replace('\n', '\r')).dropped)
        val got = ArrayList<String>()
        while (true) got += (t.readBytes() ?: break).decodeToString()
        assertEquals(lines, got)
        assertEquals(0, t.droppedInputBytes)
    }

    @Test
    fun `canonicalでLFのない長い行を貼っても、後のEnterで確定できる`() {
        val t = tty()
        t.out()
        val r = t.paste("y".repeat(5000))
        // 読める入力がない（待っても空かない）ので、今までどおり行の上限を超えた分は捨てる
        assertEquals(5000 - Tty.MAX_LINE_BYTES, r.dropped)
        assertEquals(0, t.pendingInputBytes)
        t.key("\r")
        assertEquals("y".repeat(Tty.MAX_LINE_BYTES) + "\n", t.readBytes()!!.decodeToString())
    }

    @Test
    fun `canonicalで1行目の後に長い行が続くと、1行目を読んだ後に入るだけ入れて、残りは捨てる`() {
        val t = tty()
        val head = "first\n"
        t.paste(head + "y".repeat(5000))
        assertTrue(t.pendingInputBytes > 0)
        t.key("\n")
        assertEquals(head, t.readBytes()!!.decodeToString())
        // 行がいっぱいで読める入力がないので、残りの文字は捨て、Enter は確定する
        assertEquals(0, t.pendingInputBytes)
        assertEquals("y".repeat(Tty.MAX_LINE_BYTES) + "\n", t.readBytes()!!.decodeToString())
    }

    @Test
    fun `待ちの列から入れて読めるようになったら、READABLEを知らせ、エコーはoutputPendingで知らせる`() {
        val t = tty(input = 16)
        t.paste("abc\n" + "d".repeat(20) + "\n")
        sink.clear()
        assertEquals("abc\n", t.readBytes()!!.decodeToString())
        assertTrue(sink.pending.contains(t.id), "続きのエコーを知らせる")
        // 読めなくなった後に列から入れた行が確定したので READABLE を知らせる（別の待ち手のため）
        assertTrue(sink.wakes.contains(readable(t)))
        assertEquals(0, t.pendingInputBytes)
        assertEquals("d".repeat(15) + "\n", t.readBytes()!!.decodeToString())
    }

    @Test
    fun `読めない状態から列で読めるようになったらREADABLEを知らせる`() {
        val t = rawTty(input = 4)
        t.paste("abcdefgh")
        sink.clear()
        t.readBytes()
        assertTrue(sink.wakes.contains(readable(t)))
        assertEquals("efgh", t.readBytes()!!.decodeToString())
    }

    @Test
    fun `モードを変えたら入る量に合わせて続きを入れる`() {
        val t = tty(input = 8)
        t.paste("1\n" + "abcdefghij")
        assertTrue(t.pendingInputBytes > 0)
        t.setMode(TtyMode.RAW, sink)
        // canonical の確定の 1 バイトの分も入る
        assertEquals(8, t.inputAvailable)
        assertEquals("1\nabcdefghij", t.readAll().decodeToString())
    }

    @Test
    fun `最後に閉じたら列も捨てる`() {
        val t = rawTty(input = 4)
        t.paste("abcdefgh")
        t.onLastClose(sink)
        assertEquals(0, t.pendingInputBytes)
        assertEquals(0, t.allocatedBytes)
    }

    @Test
    fun `写しには待ちの列を入れない`() {
        val t = rawTty(input = 4)
        t.paste("abcdefgh")
        val copy = t.copyForSnapshot() as Tty
        assertEquals(4, copy.inputAvailable)
        assertEquals(0, copy.pendingInputBytes)
        assertEquals(4, t.pendingInputBytes)
    }

    @Test
    fun `分けない入力は今までどおりあふれた分を捨てる`() {
        val t = rawTty(input = 4)
        assertEquals(2, t.key("abcdef").dropped)
        assertEquals(0, t.pendingInputBytes)
    }

    @Test
    fun `列から入れるときも、次の多バイト文字が空きに入らなければ文字の境目で止まる`() {
        val t = rawTty(input = 8)
        t.paste("abcdefg" + "あい")
        assertEquals(7, t.inputAvailable, "「あ」は空き 1 に入らないので待つ")
        assertEquals(6, t.pendingInputBytes)
        // 1バイト読むと空きは 2。まだ「あ」（3バイト）は入らない
        assertEquals("a", t.readBytes(1)!!.decodeToString())
        assertEquals(6, t.inputAvailable)
        assertEquals(6, t.pendingInputBytes, "文字の途中までは入れない")
        // もう 1バイト読むと空きは 3。「あ」だけが入る
        assertEquals("b", t.readBytes(1)!!.decodeToString())
        assertEquals(8, t.inputAvailable)
        assertEquals(3, t.pendingInputBytes)
        assertEquals("cdefgあい", t.readAll(1).decodeToString())
    }

    @Test
    fun `canonicalでエスケープシーケンスの途中のESCは、行に入る場所が空くまで待ち、捨てずに行に入る`() {
        for (prefix in listOf("\u001b[", "\u001bO")) {
            val t = tty(input = 8)
            t.key("ab\n")
            t.key("cdef")
            // 行はいっぱい（入力 3 + 行 4 + 確定の 1 バイト）
            t.key(prefix)
            t.paste("\u001b")
            assertEquals(1, t.pendingInputBytes, "ESC は行に入るので、空きを待つ")
            assertEquals("ab\n", t.readAll().decodeToString())
            assertEquals(0, t.pendingInputBytes)
            t.key("\r")
            assertEquals("cdef\u001b\n", t.readAll().decodeToString(), "シーケンスをやめた ESC は行に残る")
            assertEquals(0, t.droppedInputBytes)
        }
    }

    @Test
    fun `canonicalでESCの次を待っているとき、ESCが行に入る分も見て入れるかを決める`() {
        val t = tty(input = 8)
        t.key("a\n")
        t.key("cd")
        t.key("\u001b")
        // 行の空きは 3。「あ」（3バイト）だけなら入るが、ESC が行に入る分の 1 が足りない
        t.paste("あ")
        assertEquals(3, t.pendingInputBytes)
        assertEquals("a\n", t.readAll().decodeToString())
        t.key("\r")
        assertEquals("cd\u001bあ\n", t.readAll().decodeToString())
        assertEquals(0, t.droppedInputBytes)
    }

    @Test
    fun `canonicalでESCの次を待っているとき、ESCがまた来ると場所を使う`() {
        val t = tty(input = 8)
        t.key("ab\n")
        t.key("cdef")
        t.key("\u001b")
        t.paste("\u001b")
        assertEquals(1, t.pendingInputBytes)
        assertEquals("ab\n", t.readAll().decodeToString())
        t.key("\r")
        assertEquals("cdef\u001b\u001b\n", t.readAll().decodeToString())
        assertEquals(0, t.droppedInputBytes)
    }

    @Test
    fun `1バイトの消去は場所を使わないので、列の先頭に来たら行がいっぱいでも待たずに効く`() {
        val t = tty(input = 8)
        t.key("ab\n")
        t.key("cdef")
        // 行がいっぱいで読める入力もあるので、文字は待ち、後の Backspace も打った順に効かせるため列の後ろに積む
        t.paste("x")
        t.key("\u007f")
        assertEquals(2, t.pendingInputBytes)
        assertEquals("a", t.readBytes(1)!!.decodeToString())
        // 空き 1 に x が入って行がいっぱいになるが、Backspace は場所を使わないのでそのまま効く
        assertEquals(0, t.pendingInputBytes)
        t.key("\r")
        assertEquals("b\n", t.readBytes()!!.decodeToString())
        assertEquals("cdef\n", t.readBytes()!!.decodeToString())
        assertEquals(0, t.droppedInputBytes)
    }

    @Test
    fun `rawで列に積んだCtrl+Cは、signalsありのモードに切り替えた後もデータとして入る`() {
        val t = rawTty(input = 4)
        t.paste("abcdefgh")
        val r = t.key("\u0003")
        assertTrue(r.signals.isEmpty())
        assertEquals(9, t.pendingInputBytes + t.inputAvailable)
        t.setMode(cbreak, sink)
        val got = StringBuilder()
        while (true) got.append(t.readBytes(1)?.decodeToString() ?: break)
        assertEquals("abcdefgh\u0003", got.toString())
        assertEquals(0, t.pendingInputBytes)
        assertEquals(0, t.droppedInputBytes)
    }

    @Test
    fun `待ちの列の上限は1回の貼り付けの上限以上で、入力がいっぱいでも上限ちょうどの貼り付けは欠けない`() {
        assertTrue(Tty.PENDING_INPUT_BYTES >= KeyboardText.MAX_PASTE_BYTES, "2つの上限がずれると貼り付けが黙って欠ける（#609）")
        // raw: 読める入力がいっぱいの状態で、上限ちょうどを貼る
        val t = rawTty()
        assertEquals(0, t.paste(ByteArray(4096) { 'f'.code.toByte() }).dropped)
        assertEquals(4096, t.inputAvailable)
        val text = ByteArray(KeyboardText.MAX_PASTE_BYTES) { ('a' + it % 26).code.toByte() }
        assertEquals(0, t.paste(text).dropped)
        assertEquals(KeyboardText.MAX_PASTE_BYTES, t.pendingInputBytes)
        val got = t.readAll()
        assertEquals(4096 + KeyboardText.MAX_PASTE_BYTES, got.size)
        assertTrue(got.copyOfRange(4096, got.size).contentEquals(text))
        assertEquals(0, t.droppedInputBytes)
    }

    @Test
    fun `canonicalで確定した行がいっぱいのときも、上限ちょうどの複数行の貼り付けは欠けない`() {
        val t = tty()
        // 確定した行で入力をいっぱいにする（canonical は確定の 1 バイトを残す）
        val fill = "w".repeat(4094) + "\r"
        assertEquals(0, t.paste(fill).dropped)
        // 多バイト文字を含む行を、上限ちょうどになるまで並べる
        val line = "あいう" + "x".repeat(54) + "\r" // 64 バイト
        val text = line.repeat(KeyboardText.MAX_PASTE_BYTES / 64)
        assertEquals(KeyboardText.MAX_PASTE_BYTES, text.toByteArray().size)
        assertEquals(0, t.paste(text).dropped)
        assertEquals("w".repeat(4094) + "\n", t.readBytes()!!.decodeToString())
        val got = ArrayList<String>()
        while (true) got += (t.readBytes() ?: break).decodeToString()
        assertEquals(List(KeyboardText.MAX_PASTE_BYTES / 64) { line.replace('\r', '\n') }, got)
        assertEquals(0, t.droppedInputBytes)
    }

    @Test
    fun `Ctrl+Dの印を読むと、読める入力がなくなるので、待っていた続きを入れて入らない分は捨てる`() {
        val t = tty(input = 8)
        t.key("\u0004")
        // 印だけで読める。行に 7 バイト入り、残りは読めば空くかもしれないので待つ
        t.paste("abcdefghij")
        assertEquals(3, t.pendingInputBytes)
        assertEquals(ReadResult.Eof, t.read(ByteArray(16), 0, 16, sink))
        // 印を読んでも行の場所は空かないが、読める入力がなくなったので、続きを処理して入らない分は捨てる
        assertEquals(0, t.pendingInputBytes)
        assertEquals(3, t.droppedInputBytes)
        t.key("\r")
        assertEquals("abcdefg\n", t.readBytes()!!.decodeToString())
    }

    @Test
    fun `Ctrl+Dの印の後に確定した行があれば、印を読んだ後も続きを待たせる`() {
        val t = tty(input = 8)
        t.key("\u0004")
        t.key("ab\r")
        t.paste("cdefgh")
        // 入力 3 + 行 4 + 確定の 1 バイトでいっぱい
        assertEquals(2, t.pendingInputBytes)
        assertEquals(ReadResult.Eof, t.read(ByteArray(16), 0, 16, sink))
        assertEquals(2, t.pendingInputBytes, "まだ読める行があるので待つ")
        assertEquals("ab\n", t.readBytes()!!.decodeToString())
        assertEquals(0, t.pendingInputBytes)
        t.key("\r")
        assertEquals("cdefgh\n", t.readBytes()!!.decodeToString())
        assertEquals(0, t.droppedInputBytes)
    }

    @Test
    fun `rawで列を残したままcanonicalに戻すと、入力がいっぱいの間は待ち、読んだ後に行編集とエコー付きで入る`() {
        val t = rawTty(input = 8)
        t.paste("abcdefgh" + "ijk\u007f\r")
        assertEquals(8, t.inputAvailable)
        assertEquals(5, t.pendingInputBytes)
        t.out()
        t.setMode(TtyMode.COOKED, sink)
        // 入力がいっぱい（文字の残りは負、LF も入らない）で読める入力があるので、続きは待つ
        assertEquals(5, t.pendingInputBytes)
        assertEquals("", t.out())
        // raw でたまった分は確定した1つの塊として読める
        assertEquals("abcdefgh", t.readBytes()!!.decodeToString())
        // 続きは canonical で入る: Backspace が効き、CR は LF になって行を確定する
        assertEquals(0, t.pendingInputBytes)
        assertTrue(t.out().startsWith("ijk"), "続きはエコーする")
        assertEquals("ij\n", t.readBytes()!!.decodeToString())
        assertEquals(0, t.droppedInputBytes)
    }

    private fun Tty.out(): String = drainOutput(sink)?.toString(Charsets.UTF_8) ?: ""
}
