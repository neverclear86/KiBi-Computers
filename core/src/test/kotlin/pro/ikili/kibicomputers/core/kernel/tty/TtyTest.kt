package pro.ikili.kibicomputers.core.kernel.tty

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.io.ReadResult
import pro.ikili.kibicomputers.core.kernel.io.WriteResult
import pro.ikili.kibicomputers.core.kernel.obj.ObjectTable
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.wait.WakeSink

/** [Tty] 単体（#60） */
class TtyTest {
    private class RecordingSink : WakeSink {
        val wakes = ArrayList<WaitKey>()
        val objects = ArrayList<Int>()
        val pending = ArrayList<Int>()

        override fun wake(key: WaitKey) {
            wakes += key
        }

        override fun wakeObject(objectId: Int) {
            objects += objectId
        }

        override fun outputPending(objectId: Int) {
            pending += objectId
        }

        fun clear() {
            wakes.clear()
            objects.clear()
            pending.clear()
        }
    }

    private val sink = RecordingSink()

    private fun tty(input: Int = 4096, output: Int = 16 * 1024): Tty = Tty(input, output).also { ObjectTable().add(it) }

    private fun Tty.type(text: String): TtyInputResult = typeBytes(text.toByteArray())

    private fun Tty.typeBytes(bytes: ByteArray): TtyInputResult = input(bytes, 0, bytes.size, sink)

    private fun Tty.out(): String = drainOutput(sink)?.toString(Charsets.UTF_8) ?: ""

    private fun Tty.readText(max: Int = 4096): Any {
        val buf = ByteArray(max)
        return when (val r = read(buf, 0, max, sink)) {
            is ReadResult.Bytes -> String(buf, 0, r.count, Charsets.UTF_8)
            else -> r
        }
    }

    private fun Tty.writeText(text: String): WriteResult {
        val b = text.toByteArray()
        return write(b, 0, b.size, sink)
    }

    private fun readable(t: Tty) = WaitKey(t.id, WaitCondition.READABLE)

    private fun writable(t: Tty) = WaitKey(t.id, WaitCondition.WRITABLE)

    private val cbreak = TtyMode.COOKED.with(TtyMode.CANONICAL, false)

    // ---- canonical ----

    @Test
    fun `canonicalでは行が確定するまで読めず、1回の読み取りは行を越えない`() {
        val t = tty()
        t.type("ab")
        assertEquals(ReadResult.WouldBlock, t.readText())
        assertFalse(t.isReady(WaitCondition.READABLE))
        t.type("\nxyz\n")
        assertTrue(t.isReady(WaitCondition.READABLE))
        assertEquals("ab\n", t.readText())
        assertEquals("xy", t.readText(2))
        assertEquals("z\n", t.readText())
        assertEquals(ReadResult.WouldBlock, t.readText())
    }

    @Test
    fun `エコーは普通の文字をそのまま、CRはLFにして出力はCR LF、制御文字は^X`() {
        val t = tty()
        t.typeBytes(byteArrayOf('a'.code.toByte(), 0x1B, 0x01, 0x09, 0x0D))
        assertEquals("a^[^A\t\r\n", t.out())
        assertEquals("a\u001b\u0001\t\n", t.readText())
        t.setMode(TtyMode.COOKED.with(TtyMode.ECHO, false), sink)
        t.type("secret\n")
        assertEquals("", t.out())
        assertEquals("secret\n", t.readText())
    }

    @Test
    fun `消去はUTF-8の1文字単位で、表示の幅に合わせて消す`() {
        val t = tty()
        t.type("aあ\u0001")
        t.out()
        t.type("\u007f")
        assertEquals("\b\b  \b\b", t.out(), "^A は2セル")
        t.type("\u0008")
        assertEquals("\b\b  \b\b", t.out(), "全角は2セル")
        t.type("\u007f")
        assertEquals("\b \b", t.out())
        t.type("\u007f")
        assertEquals("", t.out(), "空の行では何もしない")
        t.type("x\n")
        assertEquals("x\n", t.readText())
    }

    @Test
    fun `タブは行の始まりの桁から数え直して消す`() {
        val t = tty()
        t.writeText("> ")
        t.out()
        t.type("ab\t")
        assertEquals("ab\t", t.out())
        // 桁は 2（プロンプト）+ 2 = 4 からタブで 8 へ。消すと 4 に戻るので BS 4つ
        t.type("\u007f")
        assertEquals("\b\b\b\b", t.out())
        t.type("\n")
        assertEquals("ab\n", t.readText())
    }

    @Test
    fun `Ctrl+Uは行を全部消し、Ctrl+Wは直前の単語を消す`() {
        val t = tty()
        t.type("ab あ")
        t.out()
        t.type("\u0015")
        assertEquals("\b\b  \b\b" + "\b \b" + "\b \b" + "\b \b", t.out())
        assertEquals(0, t.editingLength)
        t.type("foo bar  \u0017")
        t.out()
        t.type("\n")
        assertEquals("foo \n", t.readText())
    }

    @Test
    fun `UTF-8の途中でinputの呼び出しをまたいでも1文字単位で消える`() {
        val t = tty()
        val a = "aあ".toByteArray()
        t.typeBytes(a.copyOfRange(0, 2))
        t.typeBytes(a.copyOfRange(2, a.size))
        t.out()
        t.type("\u007f")
        assertEquals("\b\b  \b\b", t.out())
        t.type("\n")
        assertEquals("a\n", t.readText())
    }

    @Test
    fun `Ctrl+Dは空の行でEofを1回、文字があれば改行なしで確定する`() {
        val t = tty()
        t.type("\u0004\u0004")
        assertEquals("", t.out(), "エコーしない")
        assertEquals(ReadResult.Eof, t.readText())
        assertEquals(ReadResult.WouldBlock, t.readText(), "続けて押しても印は1つ")
        t.type("ab\u0004")
        assertEquals("ab", t.readText())
        assertEquals(ReadResult.WouldBlock, t.readText())
    }

    @Test
    fun `Ctrl+Cは行と読める入力を捨て、^Cと改行をエコーし、INTERRUPTを返す`() {
        val t = tty()
        t.type("line\npart")
        t.out()
        val r = t.type("\u0003")
        assertEquals(listOf(TtySignal.INTERRUPT), r.signals)
        assertEquals("^C\r\n", t.out())
        assertEquals(ReadResult.WouldBlock, t.readText())
        assertEquals(listOf(TtySignal.QUIT), t.type("\u001c").signals)
        assertEquals("^\\\r\n", t.out())
        // signals オフなら 0x03 は行に入る
        t.setMode(TtyMode.COOKED.with(TtyMode.SIGNALS, false), sink)
        assertTrue(t.type("\u0003\n").signals.isEmpty())
        assertEquals("^C\r\n", t.out())
        assertEquals("\u0003\n", t.readText())
    }

    // ---- 非 canonical ----

    @Test
    fun `RAWは素通しで、エコーせず、出力のLFも変えない`() {
        val t = tty()
        t.setMode(TtyMode.RAW, sink)
        val r = t.type("a\u0003\u0004\u007f\r")
        assertTrue(r.signals.isEmpty())
        assertEquals("", t.out())
        assertEquals("a\u0003\u0004\u007f\r", t.readText())
        assertEquals(ReadResult.WouldBlock, t.readText())
        t.writeText("x\ny")
        assertEquals("x\ny", t.out())
    }

    @Test
    fun `cbreak相当では1バイトで読め、Ctrl+Cはシグナル、DELは^?でエコーし消去しない`() {
        val t = tty()
        t.setMode(cbreak, sink)
        t.type("a")
        assertEquals("a", t.readText())
        assertEquals("a", t.out())
        t.type("b\u007f\r")
        assertEquals("b^?\r\n", t.out())
        assertEquals("b\u007f\n", t.readText())
        t.type("zz")
        val r = t.type("\u0003")
        assertEquals(listOf(TtySignal.INTERRUPT), r.signals)
        assertEquals(ReadResult.WouldBlock, t.readText(), "読める入力を捨て、0x03 も入れない")
        t.type("\u0004")
        assertEquals("\u0004", t.readText(), "Ctrl+D はただのバイト")
    }

    @Test
    fun `非canonicalでsignalsだけ、echoだけのとき`() {
        val t = tty()
        t.setMode(TtyMode(TtyMode.SIGNALS), sink)
        assertEquals(listOf(TtySignal.INTERRUPT), t.type("\u0003").signals)
        assertEquals("", t.out())
        t.setMode(TtyMode(TtyMode.ECHO), sink)
        assertTrue(t.type("\u0003").signals.isEmpty())
        assertEquals("^C", t.out())
        assertEquals("\u0003", t.readText())
    }

    // ---- 入力の上限 ----

    @Test
    fun `canonicalでは上限の1つ手前まで入り、あふれた文字はBELで捨て、LFで確定できる`() {
        val cap = 16
        val t = tty(input = cap)
        val r = t.type("x".repeat(cap + 3))
        assertEquals(4, r.dropped)
        assertEquals(4, t.droppedInputBytes)
        assertEquals(cap - 1, t.editingLength)
        assertTrue(t.out().endsWith("\u0007\u0007\u0007\u0007"))
        t.type("\n")
        assertEquals("x".repeat(cap - 1) + "\n", t.readText())
    }

    @Test
    fun `いっぱいでもCtrl+Cで入力が空になり、その後の文字がまた入る`() {
        val cap = 16
        val t = tty(input = cap)
        t.type("abcdefg\n")
        t.type("x".repeat(cap))
        assertEquals(listOf(TtySignal.INTERRUPT), t.type("\u0003").signals)
        assertEquals(0, t.inputAvailable)
        assertEquals(0, t.editingLength)
        t.type("ok\n")
        assertEquals("ok\n", t.readText())
    }

    @Test
    fun `いっぱいでも消去でき、空いた分にまた入る`() {
        val cap = 8
        val t = tty(input = cap)
        t.type("1234567")
        assertEquals(1, t.type("8").dropped)
        t.type("\u007f9")
        assertEquals("1234569", run { t.type("\n"); t.readText() }.toString().trimEnd('\n'))
        t.type("abcdefg\u0015xy\n")
        assertEquals("xy\n", t.readText())
    }

    @Test
    fun `1回のinputにあふれる文字とCtrl+Cと普通の文字を並べても全部処理する`() {
        val cap = 8
        val t = tty(input = cap)
        val r = t.type("x".repeat(10) + "\u0003" + "ab\n")
        assertEquals(3, r.dropped)
        assertEquals(listOf(TtySignal.INTERRUPT), r.signals)
        assertEquals("ab\n", t.readText())
    }

    @Test
    fun `非canonicalでいっぱいなら捨てて数え、echoならBEL`() {
        val cap = 4
        val t = tty(input = cap)
        t.setMode(cbreak, sink)
        t.out()
        assertEquals(2, t.type("abcdef").dropped)
        assertEquals("abcd\u0007\u0007", t.out())
        t.setMode(TtyMode.RAW, sink)
        assertEquals(1, t.type("g").dropped)
        assertEquals("", t.out())
        t.setMode(cbreak, sink)
        assertEquals(listOf(TtySignal.INTERRUPT), t.type("\u0003").signals)
        assertEquals(0, t.inputAvailable)
    }

    @Test
    fun `1行が4095バイトを超えたらBEL`() {
        val t = tty(input = 64 * 1024)
        assertEquals(1, t.type("y".repeat(Tty.MAX_LINE_BYTES + 1)).dropped)
        assertEquals(Tty.MAX_LINE_BYTES, t.editingLength)
        assertTrue(t.out().endsWith("y\u0007"))
    }

    // ---- 切り替え ----

    @Test
    fun `canonicalから非canonicalにすると編集中の行が読めるようになる`() {
        val cap = 8
        val t = tty(input = cap)
        t.type("abc\n")
        t.type("defg")
        assertEquals(3, t.editingLength, "確定の1バイトを残す")
        sink.clear()
        t.setMode(TtyMode.RAW, sink)
        assertEquals(cap - 1, t.inputAvailable, "合計は上限の内")
        assertEquals("abc\ndef", t.readText())
        // 空の状態から切り替えて読めるようになったら READABLE
        t.setMode(TtyMode.COOKED, sink)
        t.type("hi")
        sink.clear()
        t.setMode(TtyMode.RAW, sink)
        assertEquals(listOf(readable(t)), sink.wakes)
    }

    @Test
    fun `非canonicalからcanonicalにすると、たまった分が1つの塊で読める`() {
        val t = tty()
        t.setMode(TtyMode.RAW, sink)
        t.type("ab\ncd")
        t.setMode(TtyMode.COOKED, sink)
        assertEquals("ab\ncd", t.readText())
        assertEquals(ReadResult.WouldBlock, t.readText())
    }

    // ---- 知らせ ----

    @Test
    fun `書き込みとエコーではREADABLEを知らせず、積むたびにoutputPendingを呼ぶ`() {
        val t = tty()
        t.writeText("hello")
        t.writeText("more")
        t.type("ab")
        assertTrue(sink.wakes.isEmpty(), sink.wakes.toString())
        assertEquals(listOf(t.id, t.id, t.id), sink.pending)
        sink.clear()
        t.setMode(TtyMode.COOKED.with(TtyMode.ECHO, false), sink)
        t.type("c")
        assertTrue(sink.pending.isEmpty(), "エコーしなければ呼ばない")
    }

    @Test
    fun `行を確定したらREADABLEを1回、2行確定しても1回`() {
        val t = tty()
        t.type("a\nb\n")
        assertEquals(listOf(readable(t)), sink.wakes)
        sink.clear()
        t.type("c\n")
        assertTrue(sink.wakes.isEmpty(), "もう読める")
    }

    @Test
    fun `出力がいっぱいならWouldBlockで、drainOutputの後にWRITABLEを知らせる`() {
        val t = tty(output = 8)
        assertEquals(WriteResult.Bytes(8), t.writeText("0123456789"))
        assertFalse(t.isReady(WaitCondition.WRITABLE))
        assertEquals(WriteResult.WouldBlock, t.writeText("x"))
        sink.clear()
        assertEquals("01234567", t.out())
        assertEquals(listOf(writable(t)), sink.wakes)
        assertTrue(t.isReady(WaitCondition.WRITABLE))
        assertNull(t.drainOutput(sink))
    }

    @Test
    fun `空き1バイトでLFは書けず、WRITABLEも偽`() {
        val t = tty(output = 8)
        assertEquals(WriteResult.Bytes(7), t.writeText("0123456"))
        assertFalse(t.isReady(WaitCondition.WRITABLE))
        assertEquals(WriteResult.WouldBlock, t.writeText("\n"))
        // LF でなければ書ける
        assertEquals(WriteResult.Bytes(1), t.writeText("7"))
        assertEquals("01234567", t.out())
        assertEquals(WriteResult.Bytes(2), t.writeText("\n\n"))
        assertEquals("\r\n\r\n", t.out())
    }

    @Test
    fun `エコーは上限を超えて積め、それも超えたら捨てて数える`() {
        val t = tty(output = 8)
        t.writeText("01234567")
        t.type("x".repeat(Tty.ECHO_SLACK_BYTES + 5))
        assertEquals(5, t.droppedEchoBytes)
        assertEquals(8 + Tty.ECHO_SLACK_BYTES, t.outputAvailable)
    }

    // ---- 大きさ ----

    @Test
    fun `大きさが変わったときだけ世代が進み、待ち手は起こさない`() {
        val t = tty()
        assertEquals(TtySize(80, 25), t.size)
        assertFalse(t.resize(TtySize(80, 25)))
        assertEquals(0, t.sizeGeneration)
        assertTrue(t.resize(TtySize(100, 30)))
        assertEquals(1, t.sizeGeneration)
        assertEquals(TtySize(100, 30), t.size)
        assertTrue(sink.wakes.isEmpty() && sink.objects.isEmpty())
        assertThrows<IllegalArgumentException> { TtySize(0, 1) }
        assertThrows<IllegalArgumentException> { TtySize(1, -1) }
    }

    @Test
    fun `モードのビットは個別に変えられ、知らないビットは受け付けない`() {
        val m = TtyMode.COOKED.with(TtyMode.ECHO, false)
        assertTrue(m.canonical && !m.echo && m.signals && m.crToNl && m.nlToCrNl)
        assertEquals(TtyMode.COOKED, m.with(TtyMode.ECHO, true))
        assertFalse(TtyMode.RAW.canonical || TtyMode.RAW.echo || TtyMode.RAW.signals || TtyMode.RAW.crToNl || TtyMode.RAW.nlToCrNl)
        assertThrows<IllegalArgumentException> { TtyMode(64) }
    }

    @Test
    fun `空になったらバッファを手放す`() {
        val t = tty()
        t.type("abc\n")
        t.readText()
        t.out()
        assertEquals(0, t.allocatedBytes)
    }

    // ---- 履歴とエスケープシーケンス（#96） ----

    private val up = "\u001b[A"
    private val down = "\u001b[B"

    @Test
    fun `上下の矢印で確定した行を呼び戻し、いちばん新しいものの次は呼び戻す前の行に戻る`() {
        val t = tty()
        t.type("one\ntwo\n")
        t.readText()
        t.readText()
        t.out()
        assertEquals(2, t.historyCount)
        t.type("dr")
        t.out()
        t.type(up)
        assertEquals("\b \b\b \btwo", t.out())
        t.type(up)
        assertEquals("\b \b\b \b\b \bone", t.out())
        // いちばん古いものより前はない: BEL を鳴らして行はそのまま
        t.type(up)
        assertEquals("\u0007", t.out())
        t.type(down + down)
        assertEquals("\b \b\b \b\b \btwo\b \b\b \b\b \bdr", t.out())
        // 呼び戻していなければ、下の矢印は何もしない
        t.type(down)
        assertEquals("", t.out())
        t.type("\u0015" + up + "!\n")
        assertEquals("two!\n", t.readText())
        assertEquals(3, t.historyCount)
    }

    @Test
    fun `空の行・直前と同じ行・エコーしていない行は履歴に残さない`() {
        val t = tty()
        t.type("\na\na\n")
        assertEquals(1, t.historyCount)
        t.setMode(TtyMode.COOKED.with(TtyMode.ECHO, false), sink)
        t.type("secret\n")
        assertEquals(1, t.historyCount)
        // エコーしていなければ呼び戻さない（シーケンスは読み捨てる）
        t.type(up + "\n")
        while (t.readText() is String) Unit
        t.setMode(TtyMode.COOKED, sink)
        t.out()
        t.type(up + "\n")
        assertEquals("a\r\n", t.out())
        assertEquals("a\n", t.readText())
    }

    @Test
    fun `履歴は行の数とバイト数の上限を超えたら古いものから捨てる`() {
        val t = tty()
        repeat(Tty.HISTORY_MAX_ENTRIES + 5) {
            t.type("line$it\n")
            t.readText()
        }
        assertEquals(Tty.HISTORY_MAX_ENTRIES, t.historyCount)
        val h = TtyHistory(10, 8)
        h.add("abcd".toByteArray())
        h.add("efgh".toByteArray())
        h.add("ij".toByteArray())
        assertEquals(2, h.count)
        assertEquals("efgh", String(h[0]))
        h.add(ByteArray(9))
        assertEquals(2, h.count)
    }

    @Test
    fun `呼び戻した行が入力の上限に入らなければBELを鳴らして行はそのまま`() {
        val t = tty(input = 8)
        t.type("abcdef\n")
        t.type(up)
        assertEquals("abcdef\r\n\u0007", t.out())
        assertEquals("abcdef\n", t.readText())
        t.type(up)
        assertEquals("abcdef", t.out())
    }

    @Test
    fun `canonicalでは矢印などのシーケンスを行に入れず、ESCの後が別の文字ならESCを入れる`() {
        val t = tty()
        t.type("a\u001b[1;5Cb\u001bOPc\u001b[3~d\u001bxe\n")
        assertEquals("abcd\u001bxe\n", t.readText())
        // 1回の入力の境目をまたいでも同じ
        t.type("\u001b")
        t.type("[")
        t.type("D")
        t.type("z\n")
        assertEquals("z\n", t.readText())
        // 途中の制御文字はシーケンスをやめて普通に扱う
        t.type("\u001b[\n")
        assertEquals("\n", t.readText())
    }

    @Test
    fun `非canonicalではシーケンスもそのまま読める`() {
        val t = tty()
        t.setMode(cbreak, sink)
        t.type(up)
        assertEquals(up, t.readText())
    }

    @Test
    fun `Ctrl+Cは呼び戻しとシーケンスの途中をやめ、最後に閉じたら履歴を捨てる`() {
        val t = tty()
        t.type("x\n")
        t.readText()
        t.type("\u001b[")
        t.typeBytes(byteArrayOf(0x03))
        t.type("A\n")
        assertEquals("A\n", t.readText())
        t.onLastClose(sink)
        assertEquals(0, t.historyCount)
    }
}
