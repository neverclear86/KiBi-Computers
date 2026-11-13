package pro.ikili.kibicomputers.core.kernel.tty

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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

    private fun Tty.out(): String = drainOutput(sink)?.toString(Charsets.UTF_8) ?: ""
}
