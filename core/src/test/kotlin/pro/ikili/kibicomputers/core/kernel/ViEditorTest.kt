package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.display.CharWidth
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.display.terminal.AnsiTerminal
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.tty.TtyMode
import pro.ikili.kibicomputers.core.kernel.vfs.ImageFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.io.ByteArrayOutputStream
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * `/bin/vi`（#104）を、カーネルの TTY の上で動かして確かめる。コンソールの出力は端末の解釈（`AnsiTerminal`）に通し、
 * 画面の行とカーソルを見る
 */
class ViEditorTest {
    private val limit = Duration.ofSeconds(180)

    private class ScreenHost(columns: Int, rows: Int) : MachineHost {
        private val out = ByteArrayOutputStream()
        val terminal = AnsiTerminal(TextBuffer(columns, rows), 256)

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            synchronized(this) {
                out.write(bytes, 0, bytes.size)
                terminal.write(bytes)
            }
        }

        fun resize(columns: Int, rows: Int) = synchronized(this) { terminal.resize(columns, rows) }

        fun text(): String = synchronized(this) { out.toString(Charsets.UTF_8) }

        fun bytes(): Int = synchronized(this) { out.size() }

        /** 画面の [y] 行目（全角の文字の右のセルは飛ばす） */
        fun row(y: Int): String = synchronized(this) {
            buildString {
                var x = 0
                val b = terminal.buffer
                while (x < b.width) {
                    val cp = b.getCodePoint(x, y)
                    appendCodePoint(cp)
                    x += if (CharWidth.isWide(cp)) 2 else 1
                }
            }.trimEnd()
        }

        fun rows(): List<String> = synchronized(this) { (0 until terminal.buffer.height).map { row(it) } }

        fun screen(): String = rows().joinToString("\n")

        fun cursor(): Pair<Int, Int> = synchronized(this) { terminal.cursorX to terminal.cursorY }
    }

    private val image: ImageFileSystem by lazy { requireNotNull(ImageFileSystem.bundled("os")) { "os.zip がリソースにない" } }

    private fun imageFile(path: String): ByteArray {
        val stat = image.stat(VfsPath.parse(path))
        val bytes = ByteArray(stat.size.toInt())
        image.read(stat.node, 0, bytes, 0, bytes.size)
        return bytes
    }

    private fun Machine.put(path: String, bytes: ByteArray) {
        val f = vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true, truncate = true))
        vfs.write(f, bytes, 0, bytes.size)
    }

    private fun Machine.readBytes(path: String): ByteArray? = try {
        val f = vfs.open(VfsPath.parse(path), OpenOptions(read = true))
        val out = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (true) {
            val n = vfs.read(f, buf, 0, buf.size)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        out.toByteArray()
    } catch (e: Exception) {
        null
    }

    /** 動いている vi。[keys] で打ち、打つたびに入力を待つところまで進める */
    private inner class Editor(val h: Harness, val m: Machine, val host: ScreenHost, val pid: Int) {
        /** 入力を待つところまで進め、出力が届き終わるまで待つ */
        fun settle() {
            var last = -1
            var still = 0
            eventuallyDescribe(60_000, { "入力を待たない: ${m.stateOf(pid)}\n${host.screen()}" }) {
                h.scheduler.tick()
                Thread.sleep(1)
                val state = m.stateOf(pid)
                val idle = state.isTerminal || (state == ProcessState.WAITING_EVENT && m.isAtWaitPoint)
                val now = host.bytes()
                still = if (idle && now == last) still + 1 else 0
                last = now
                still >= 5
            }
        }

        /** [s] を 1 回の入力として打つ */
        fun keys(s: String) {
            m.offerConsoleInput(s.toByteArray())
            settle()
        }

        /** [s] を 1 回の貼り付けとして入れる（LF は CR にして、文字の境目で分けてよい入力として渡す。#609） */
        fun paste(s: String) {
            m.offerConsoleInput(s.replace('\n', '\r').toByteArray(), splittable = true)
            settle()
        }

        fun row(y: Int) = host.row(y)
        val screen: String get() = host.screen()
        val rows: List<String> get() = host.rows()

        fun file(path: String): ByteArray? = m.readBytes(path)

        fun finish(): Int {
            eventuallyDescribe(60_000, { "終わらない: ${host.screen()}" }) {
                h.scheduler.tick()
                Thread.sleep(1)
                m.stateOf(pid).isTerminal
            }
            assertEquals(ProcessState.EXITED, m.stateOf(pid), host.screen())
            repeat(5) { h.scheduler.tick() }
            return m.lock.withLockValue { m.kernel.processes[pid]!!.exitCode }!!
        }

        val ttyMode: TtyMode get() = m.lock.withLockValue { m.consoleTtyLocked().mode }

        /** 入力を終わらせる（TTY を行編集のモードにして、行頭の ^D を送る。読む側には EOF になる） */
        fun endInput() {
            m.lock.withLockValue { m.consoleTtyLocked().setMode(TtyMode.COOKED, m.wakeSink) }
            m.offerConsoleInput(byteArrayOf(4))
        }
    }

    private fun vi(
        args: List<String>,
        files: Map<String, ByteArray> = emptyMap(),
        columns: Int = 80,
        rows: Int = 25,
        memoryBytes: Long = 16L * 1024 * 1024,
        setup: (Machine) -> Unit = {},
        body: Editor.() -> Unit,
    ) = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val host = ScreenHost(columns, rows)
            val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = memoryBytes)
            if (columns != 80 || rows != 25) m.resizeConsole(columns, rows)
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            for (d in listOf("/bin", "/tmp", "/home")) m.vfs.createDirectory(VfsPath.parse(d))
            m.put("/bin/vi", imageFile("/bin/vi"))
            for ((path, bytes) in files) m.put(path, bytes)
            setup(m)
            val pid = m.exec(
                "/bin/vi",
                KernelDrivers.DEFAULT,
                SpawnOptions(args = listOf("vi") + args, env = mapOf("PWD" to "/home"), cwd = "/home", fds = SpawnOptions.ROOT_PREOPEN),
            )
            val e = Editor(h, m, host, pid)
            e.settle()
            e.body()
        }
    }


    private val esc = "\u001b"

    /** いちばん下の行（25 行の画面） */
    private val Editor.status: String get() = row(24)

    @Test
    fun `開いたファイルを描き、編集してwqで保存して終わると、元の画面とモードに戻る`() = vi(
        listOf("hello.lua"),
        files = mapOf("/home/hello.lua" to "print(1)\nprint(2)\n".toByteArray()),
    ) {
        assertFalse(ttyMode.canonical)
        assertFalse(ttyMode.signals)
        assertTrue(host.terminal.alternateScreen, screen)
        assertEquals("print(1)", row(0))
        assertEquals("print(2)", row(1))
        assertEquals("~", row(2))
        assertEquals("~", row(23))
        assertEquals("\"hello.lua\" 2L, 18B", status)
        assertEquals(0 to 0, host.cursor())

        keys("O-- hi$esc")
        assertEquals("-- hi", row(0))
        assertEquals("print(1)", row(1))
        assertEquals(4 to 0, host.cursor())
        keys(":wq")
        assertEquals(":wq", status)
        keys("\r")
        assertEquals(0, finish())
        assertEquals("-- hi\nprint(1)\nprint(2)\n", file("/home/hello.lua")!!.decodeToString())
        assertFalse(host.terminal.alternateScreen)
        assertTrue(host.terminal.cursorVisible)
        assertTrue(ttyMode.canonical)
        assertTrue(host.text().endsWith("\u001b[r\u001b[0m\u001b[?7h\u001b[?25h\u001b[?1049l"), host.text().takeLast(40))
    }

    @Test
    fun `挿入の間はいちばん下にモードを出し、Escで1文字左に戻る`() = vi(listOf("a.txt")) {
        assertEquals("\"a.txt\" [New]", status)
        keys("ihello")
        assertEquals("-- INSERT --", status)
        assertEquals("hello", row(0))
        assertEquals(5 to 0, host.cursor())
        keys(esc)
        assertEquals("", status)
        assertEquals(4 to 0, host.cursor())
        keys("ZZ")
        assertEquals(0, finish())
        assertEquals("hello", file("/home/a.txt")!!.decodeToString())
    }

    @Test
    fun `Escの後に速く打ったキーが1回の入力で届いても、Escと次のキーに分ける`() = vi(
        listOf("e.txt"),
        files = mapOf("/home/e.txt" to "abc\ndef\nghi\n".toByteArray()),
    ) {
        // Esc → : （1 回の入力）
        keys("iX$esc:w\r")
        assertEquals("Xabc\ndef\nghi\n", file("/home/e.txt")!!.decodeToString())
        assertTrue(status.contains("written"), screen)
        // Esc → j
        keys("iY${esc}j")
        assertEquals("YXabc", row(0))
        assertEquals(0 to 1, host.cursor())
        // Esc → O（表にない ESC O q は、Esc・O・q）
        keys("iZ${esc}Oq$esc")
        assertEquals("q", row(1))
        assertEquals("Zdef", row(2))
        // Esc → 日本語（UTF-8 の先頭バイト）: Esc と、normal では何もしない文字。残りのバイトをキーにしない
        keys("jiあ${esc}い")
        assertEquals("あZdef", row(2))
        assertFalse(screen.contains("�"), screen)
        assertEquals("", status)
        // 別の入力で送った素の Esc
        keys("ix")
        keys(esc)
        keys(":")
        assertEquals(":", status)
        keys(esc)
        // ESC O A（↑ と同じ並び）は、1 回の入力で届くと ↑ と読む（ADR 0104 に書いたあいまいさ）
        keys("iW${esc}OA")
        assertEquals("-- INSERT --", status)
        keys(esc)
        keys(":q!\r")
        assertEquals(0, finish())
    }

    @Test
    fun `動き・数・演算子`() = vi(
        listOf("m.txt"),
        files = mapOf("/home/m.txt" to "one two three\nfour (five) six\n\nseven\n".toByteArray()),
    ) {
        keys("w")
        assertEquals(4 to 0, host.cursor())
        keys("e")
        assertEquals(6 to 0, host.cursor())
        keys("$")
        assertEquals(12 to 0, host.cursor())
        keys("0fe")
        assertEquals(2 to 0, host.cursor())
        keys(";")
        assertEquals(11 to 0, host.cursor())
        keys("b")
        assertEquals(8 to 0, host.cursor())
        keys("j0f(%")
        assertEquals(10 to 1, host.cursor())
        keys("%")
        assertEquals(5 to 1, host.cursor())
        keys("G")
        assertEquals(0 to 3, host.cursor())
        // 空の行も語に数える
        keys("b")
        assertEquals(0 to 2, host.cursor())
        keys("b")
        assertEquals(12 to 1, host.cursor())
        keys("w")
        assertEquals(0 to 2, host.cursor())
        keys("gg")
        assertEquals(0 to 0, host.cursor())
        keys("2w")
        assertEquals(8 to 0, host.cursor())
        // dw は行の終わりの語で次の行へ出ない
        keys("dw")
        assertEquals("one two", row(0))
        keys("0d2w")
        assertEquals("", row(0))
        keys("u")
        assertEquals("one two", row(0))
        keys("jcwFOUR$esc")
        assertEquals("FOUR (five) six", row(1))
        keys("3x")
        assertEquals("FOUfive) six", row(1))
        keys("dt)")
        assertEquals("FOU) six", row(1))
        keys("jjyyP")
        assertEquals(listOf("one two", "FOU) six", "", "seven", "seven", "~"), rows.subList(0, 6))
        keys("J")
        assertEquals("seven seven", row(3))
        keys("2dd")
        assertEquals(listOf("one two", "FOU) six", "", "~"), rows.subList(0, 4))
        keys(":wq\r")
        assertEquals(0, finish())
        assertEquals("one two \nFOU) six\n\n", file("/home/m.txt")!!.decodeToString())
    }

    @Test
    fun `元に戻すは挿入の1回を1つにまとめ、やり直せる。ドットで前の変更を繰り返す`() = vi(
        listOf("u.txt"),
        files = mapOf("/home/u.txt" to "a b c d e\n".toByteArray()),
    ) {
        keys("ihello $esc")
        assertEquals("hello a b c d e", row(0))
        keys("u")
        assertEquals("a b c d e", row(0))
        keys("\u0012")
        assertEquals("hello a b c d e", row(0))
        keys("u")
        keys("dw")
        assertEquals("b c d e", row(0))
        keys(".")
        assertEquals("c d e", row(0))
        keys("2.")
        assertEquals("e", row(0))
        keys("uuu")
        assertEquals("a b c d e", row(0))
        keys("u")
        assertTrue(status.contains("Already at oldest change"), screen)
        // 挿入の回数と、その繰り返し
        keys("3ix$esc")
        assertEquals("xxxa b c d e", row(0))
        keys("$.")
        assertEquals("xxxa b c d xxxe", row(0))
        keys(":q!\r")
        assertEquals(0, finish())
        assertEquals("a b c d e\n", file("/home/u.txt")!!.decodeToString())
    }

    @Test
    fun `改行で終わらない最後の行でも、消す・貼る・開くでテキストの終わりに改行を足さない`() {
        vi(listOf("n.txt"), files = mapOf("/home/n.txt" to "a\nb".toByteArray())) {
            assertEquals("\"n.txt\" 2L, 3B", status)
            keys("Gdd")
            assertEquals(listOf("a", "~"), rows.subList(0, 2))
            keys("p")
            assertEquals(listOf("a", "b"), rows.subList(0, 2))
            keys(":w\r")
            assertEquals("a\nb", file("/home/n.txt")!!.decodeToString())
            keys("ggyyGp")
            keys(":w\r")
            assertEquals("a\nb\na", file("/home/n.txt")!!.decodeToString())
            keys("Goc$esc:w\r")
            assertEquals("a\nb\na\nc", file("/home/n.txt")!!.decodeToString())
            keys("ggyGP:w\r")
            assertEquals("a\nb\na\nc\na\nb\na\nc", file("/home/n.txt")!!.decodeToString())
            keys(":q\r")
            assertEquals(0, finish())
        }
        // 空のファイル・改行だけのファイル
        vi(listOf("e.txt"), files = mapOf("/home/e.txt" to ByteArray(0))) {
            keys("ddxp")
            assertTrue(status.contains("Nothing in register"), screen)
            keys("ihi$esc:wq\r")
            assertEquals(0, finish())
            assertEquals("hi", file("/home/e.txt")!!.decodeToString())
        }
        vi(listOf("l.txt"), files = mapOf("/home/l.txt" to "\n".toByteArray())) {
            assertEquals("\"l.txt\" 1L, 1B", status)
            assertEquals(listOf("", "~"), rows.subList(0, 2))
            keys("dd:wq\r")
            assertEquals(0, finish())
            assertEquals("", file("/home/l.txt")!!.decodeToString())
        }
    }

    @Test
    fun `編集しないバイトは保存しても変えない。制御文字と不正なUTF-8は印に置き換えて見せる`() {
        val original = byteArrayOf(
            *"a\u001bc\tb\r\n".toByteArray(),
            0, 0xff.toByte(), 0xc0.toByte(), 0x9b.toByte(), 0xc2.toByte(), 0x9b.toByte(), '\n'.code.toByte(),
            *"end".toByteArray(),
        )
        vi(listOf("bin.dat"), files = mapOf("/home/bin.dat" to original)) {
            assertEquals("a^[c    b^M", row(0))
            assertEquals("^@����", row(1))
            assertEquals("end", row(2))
            assertTrue(host.terminal.alternateScreen)
            keys("GA!$esc:wq\r")
            assertEquals(0, finish())
            assertArrayEquals(original + "!".toByteArray(), file("/home/bin.dat"))
        }
    }

    @Test
    fun `全角の文字の上にカーソルを置き、消せる`() = vi(listOf("j.txt")) {
        keys("iあいう$esc")
        assertEquals("あいう", row(0))
        assertEquals(4 to 0, host.cursor())
        keys("h")
        assertEquals(2 to 0, host.cursor())
        keys("x")
        assertEquals("あう", row(0))
        assertEquals(2 to 0, host.cursor())
        keys(":wq\r")
        assertEquals(0, finish())
        assertEquals("あう", file("/home/j.txt")!!.decodeToString())
    }

    @Test
    fun `変更があればqを断り、q!で保存せずに終わる。Ctrl+Cで終わり方を出す`() = vi(
        listOf("keep.txt"),
        files = mapOf("/home/keep.txt" to "old\n".toByteArray()),
    ) {
        keys("x")
        keys(":q\r")
        assertTrue(status.contains("No write since last change"), screen)
        keys("\u0003")
        assertEquals("Type :q! and press Enter to quit without saving", status)
        keys(":w other.txt\r")
        assertTrue(status.contains("\"other.txt\" 1L, 3B written"), screen)
        // 別の名前に書いても、今のファイルは変えたまま
        keys(":q\r")
        assertTrue(status.contains("No write since last change"), screen)
        keys(":w other.txt\r")
        assertTrue(status.contains("File exists"), screen)
        keys(":q!\r")
        assertEquals(0, finish())
        assertEquals("old\n", file("/home/keep.txt")!!.decodeToString())
        assertEquals("ld\n", file("/home/other.txt")!!.decodeToString())
    }

    @Test
    fun `読み取り専用の場所に保存できなくても、編集を続けて別の名前で書き出せる`() = vi(
        listOf("/ro/a.txt"),
        setup = { m ->
            val ro = TestMemoryFileSystem()
            m.vfs.createDirectory(VfsPath.parse("/ro"))
            val rw = m.vfs.mount(VfsPath.parse("/ro"), ro, readOnly = false, source = "test:ro")
            m.put("/ro/a.txt", "data\n".toByteArray())
            m.vfs.unmount(rw.id)
            m.vfs.mount(VfsPath.parse("/ro"), ro, readOnly = true, source = "test:ro")
        },
    ) {
        keys("imore $esc:w\r")
        assertTrue(status.contains("Error writing /ro/a.txt"), screen)
        keys("a+$esc")
        assertEquals("more +data", row(0))
        keys(":w /tmp/b.txt\r")
        assertTrue(status.contains("written"), screen)
        keys(":q!\r")
        assertEquals(0, finish())
        assertEquals("more +data\n", file("/tmp/b.txt")!!.decodeToString())
        assertEquals("data\n", file("/ro/a.txt")!!.decodeToString())
        assertEquals(null, file("/tmp/.b.txt.vi0"))
    }

    @Test
    fun `探すと置き換え`() = vi(
        listOf("s.txt"),
        files = mapOf("/home/s.txt" to "foo bar\nbaz foo\nfoo foo\n".toByteArray()),
    ) {
        keys("/foo\r")
        assertEquals(4 to 1, host.cursor())
        keys("n")
        assertEquals(0 to 2, host.cursor())
        keys("n")
        assertEquals(4 to 2, host.cursor())
        keys("n")
        assertEquals(0 to 0, host.cursor())
        assertTrue(status.contains("search hit BOTTOM"), screen)
        keys("N")
        assertEquals(4 to 2, host.cursor())
        keys("?ba.\r")
        assertEquals(0 to 1, host.cursor())
        keys("/nope\r")
        assertTrue(status.contains("Pattern not found: nope"), screen)
        keys(":%s/foo/[&]/g\r")
        assertEquals(listOf("[foo] bar", "baz [foo]", "[foo] [foo]"), rows.subList(0, 3))
        assertTrue(status.contains("4 substitutions on 3 lines"), screen)
        keys("u")
        assertEquals(listOf("foo bar", "baz foo", "foo foo"), rows.subList(0, 3))
        keys(":2,3s/\\(x\\)/y/\r")
        assertTrue(status.contains("Invalid pattern"), screen)
        keys(":3s/o*\$/!/\r")
        assertEquals("foo f!", row(2))
        keys(":1s/ /\\r/\r")
        assertEquals(listOf("foo", "bar", "baz foo"), rows.subList(0, 3))
        keys(":q!\r")
        assertEquals(0, finish())
    }

    @Test
    fun `探すと置き換えは UTF-8 の文字を単位にし、文字を割らない`() = vi(
        listOf("m.txt"),
        files = mapOf("/home/m.txt" to "あいう\nxあy\n".toByteArray()),
    ) {
        keys(":1s/^./X/\r")
        assertEquals("Xいう", row(0))
        keys(":2s/[^x]/_/g\r")
        assertEquals("x__", row(1))
        keys("u")
        assertEquals("xあy", row(1))
        keys(":2s/[あい]/Z/g\r")
        assertEquals("xZy", row(1))
        keys(":1s/[あい]/Z/g\r")
        assertEquals("XZう", row(0))
        keys(":wq\r")
        assertEquals(0, finish())
        assertArrayEquals("XZう\nxZy\n".toByteArray(), file("/home/m.txt"))
    }

    @Test
    fun `多バイトの文字の上でも、探すと次の文字へ進む`() = vi(
        listOf("n.txt"),
        files = mapOf("/home/n.txt" to "あいう\n".toByteArray()),
    ) {
        keys("/.\r")
        assertEquals(2 to 0, host.cursor())
        keys("n")
        assertEquals(4 to 0, host.cursor())
        keys("n")
        assertEquals(0 to 0, host.cursor())
        keys("/[^a]\r")
        assertEquals(2 to 0, host.cursor())
        keys("N")
        assertEquals(0 to 0, host.cursor())
        keys(":q!\r")
        assertEquals(0, finish())
    }

    @Test
    fun `gの置き換えは、直前の一致につながる空の一致を飛ばし、行の終わりの空の一致は置き換える`() = vi(
        listOf("g.txt"),
        files = mapOf("/home/g.txt" to "baaac\naaa\n".toByteArray()),
    ) {
        keys(":%s/a*/-/g\r")
        assertEquals(listOf("-b-c-", "-"), rows.subList(0, 2))
        keys(":q!\r")
        assertEquals(0, finish())
    }

    @Test
    fun `とても長い1行でも、文字ごとに進む探す・置き換え・回数付きの削除が行の長さの2乗にならない`() {
        // 前は文字を 1 つ進めるたびに行の頭から数えていたので、ここで止まった（settle が時間切れになる）
        val text = "あ".repeat(100_000) + "\n" + "a".repeat(200_000) + "\n"
        vi(listOf("long.txt"), files = mapOf("/home/long.txt" to text.toByteArray())) {
            keys("\$?.\r")
            assertFalse(status.contains("Pattern not found"), screen)
            keys("99998X")
            assertEquals("ああ", row(0))
            keys("j:2s/z*/-/g\r")
            assertTrue(status.contains("200001 substitutions on 1 line"), screen)
            assertTrue(row(1).startsWith("-a-a-a"), screen)
            keys("0999999x")
            assertEquals("", row(1))
            keys(":wq\r")
            assertEquals(0, finish())
            assertEquals("ああ\n\n", file("/home/long.txt")!!.decodeToString())
        }
    }

    @Test
    fun `長すぎる探す語は切り詰めずに断り、前の語を変えない`() = vi(
        listOf("p.txt"),
        files = mapOf("/home/p.txt" to "abc\n".toByteArray()),
    ) {
        keys("/" + "あ".repeat(100) + "\r")
        assertTrue(status.contains("Pattern is too long"), screen)
        keys("n")
        assertTrue(status.contains("No previous regular expression"), screen)
        keys("/c\r")
        assertEquals(2 to 0, host.cursor())
        keys("/" + "x".repeat(300) + "\r")
        assertTrue(status.contains("Pattern is too long"), screen)
        keys("0n")
        assertEquals(2 to 0, host.cursor())
        keys(":q!\r")
        assertEquals(0, finish())
    }

    @Test
    fun `置き換えが作業の領域に入りきらなければ、置き換えずに知らせる`() {
        val original = ("a".repeat(3000) + "\n").toByteArray()
        vi(listOf("l.txt"), files = mapOf("/home/l.txt" to original)) {
            keys(":1s/a*/&&/\r")
            assertTrue(status.contains("Replacement is too long"), screen)
            keys(":wq\r")
            assertEquals(0, finish())
            assertArrayEquals(original, file("/home/l.txt"))
        }
    }

    @Test
    fun `テキストが上限の近くでも、大きさの変わらない組は戻せて、やり直せる`() {
        val a = "a".repeat(4000)
        val b = "b".repeat(4000)
        val filler = "c".repeat(999) + "\n"
        val text = "$a\n".repeat(100) + filler.repeat(3750)
        val replaced = "$b\n".repeat(100) + filler.repeat(3750)
        vi(listOf("big.txt"), files = mapOf("/home/big.txt" to text.toByteArray())) {
            keys(":1,100s/a*/$b/\r")
            assertTrue(row(0).startsWith("bbbb"), screen)
            keys("u")
            assertFalse(status.contains("buffer is full"), screen)
            assertTrue(row(0).startsWith("aaaa"), screen)
            keys("\u0012")
            assertFalse(status.contains("buffer is full"), screen)
            assertTrue(row(0).startsWith("bbbb"), screen)
            keys(":wq\r")
            assertEquals(0, finish())
            assertEquals(replaced, file("/home/big.txt")!!.decodeToString())
        }
    }

    @Test
    fun `行の番号を出し、行の番号のコマンドで行へ移る。画面を送る`() = vi(
        listOf("pg.txt"),
        files = mapOf("/home/pg.txt" to (1..100).joinToString("") { "line $it\n" }.toByteArray()),
    ) {
        keys(":set nu\r")
        assertEquals("  1 line 1", row(0))
        assertEquals(4 to 0, host.cursor())
        keys(":set nonu\r")
        keys(":50\r")
        assertEquals("line 50", host.rows()[host.cursor().second])
        keys("gg\u0006")
        assertEquals("line 23", row(0))
        assertEquals(0 to 0, host.cursor())
        keys("\u0002")
        assertEquals("line 1", row(0))
        keys("\u0004")
        assertEquals("line 13", row(0))
        assertEquals("line 35", host.rows()[host.cursor().second])
        keys("G")
        assertEquals("line 100", host.rows()[host.cursor().second])
        keys("\u0007")
        assertTrue(status.contains("line 100 of 100"), screen)
        keys(":q\r")
        assertEquals(0, finish())
    }

    @Test
    fun `入力が終わったら、保存していない編集を名前の後ろに save を付けて残す`() {
        vi(listOf("e.txt"), files = mapOf("/home/e.txt" to "old\n".toByteArray())) {
            keys("inew $esc")
            endInput()
            assertEquals(0, finish())
            assertEquals("new old\n", file("/home/e.txt.save")!!.decodeToString())
            assertEquals("old\n", file("/home/e.txt")!!.decodeToString())
        }
        vi(emptyList()) {
            keys("ix")
            endInput()
            assertEquals(0, finish())
            assertEquals("x", file("/home/vi.save")!!.decodeToString())
        }
    }

    @Test
    fun `入力を待つ間は待機点で止まり、スレッドを持たない。画面の大きさが変わると描き直す`() = vi(
        listOf("w.txt"),
        files = mapOf("/home/w.txt" to "abc\n".toByteArray()),
    ) {
        assertTrue(m.isAtWaitPoint)
        assertTrue(h.scheduler.dedicatedThreads.isEmpty(), "専用スレッドがある")
        assertEquals(0, h.scheduler.blockingProcesses, "ブロッキング型の枠を使っている")
        val before = host.bytes()
        repeat(50) { h.scheduler.tick() }
        assertEquals(before, host.bytes())
        assertEquals(ProcessState.WAITING_EVENT, m.stateOf(pid))

        m.resizeConsole(60, 20)
        host.resize(60, 20)
        keys("l")
        assertEquals("abc", row(0))
        assertEquals("~", row(18))
        assertEquals(1 to 0, host.cursor())
        keys(":q\r")
        assertEquals(0, finish())
    }

    @Test
    fun `1文字を打っても、全画面を書き直さない`() = vi(
        listOf("d.txt"),
        files = mapOf("/home/d.txt" to (1..60).joinToString("") { "row $it\n" }.toByteArray()),
    ) {
        keys("i")
        val before = host.bytes()
        keys("z")
        assertTrue(host.bytes() - before < 300, "${host.bytes() - before} バイト書いた")
        assertEquals("zrow 1", row(0))
        keys(esc)
        keys("23j")
        val mid = host.bytes()
        keys("j")
        assertTrue(host.bytes() - mid < 300, "${host.bytes() - mid} バイト書いた")
        assertEquals("row 25", row(23))
        keys(":q!\r")
        assertEquals(0, finish())
    }

    @Test
    fun `大きすぎるファイル・ディレクトリ・2つ以上のファイルは開かず、TTYのモードを変えない`() {
        val big = ByteArray(4 * 1024 * 1024 + 1) { 'a'.code.toByte() }
        vi(listOf("big.txt"), files = mapOf("/home/big.txt" to big)) {
            assertEquals(1, finish())
            assertTrue(host.text().contains("vi: big.txt: file is too large"), host.text())
            assertFalse(host.terminal.alternateScreen)
            assertTrue(ttyMode.canonical)
        }
        vi(listOf("/tmp")) {
            assertEquals(1, finish())
            assertTrue(host.text().contains("Is a directory"), host.text())
        }
        vi(listOf("a", "b")) {
            assertEquals(2, finish())
        }
    }

    @Test
    fun `RAMが足りなくても大きな数でも、落ちずに知らせて編集を続ける`() = vi(
        listOf("m.txt"),
        files = mapOf("/home/m.txt" to ("q".repeat(60 * 1024) + "\n").toByteArray()),
        memoryBytes = 768L * 1024,
    ) {
        keys("yy")
        repeat(16) { keys("p") }
        assertTrue(screen.contains("Not enough"), screen)
        keys("ok")
        assertTrue(m.stateOf(pid) == ProcessState.WAITING_EVENT, screen)
        keys("$esc:q!\r")
        assertEquals(0, finish())
    }

    @Test
    fun `とても大きな数を付けても、上限で断って落ちない`() = vi(
        listOf("c.txt"),
        files = mapOf("/home/c.txt" to "a\nb\nc\n".toByteArray()),
    ) {
        keys("999999999ix$esc")
        assertTrue(status.contains("The buffer is full"), screen)
        assertEquals("xa", row(0))
        keys("yy999999999p")
        assertTrue(status.contains("The buffer is full"), screen)
        keys("999999999dd")
        assertEquals(listOf("", "~"), rows.subList(0, 2))
        keys("999999999u")
        assertEquals(listOf("a", "b", "c"), rows.subList(0, 3))
        assertTrue(m.stateOf(pid) == ProcessState.WAITING_EVENT, screen)
        keys(":q!\r")
        assertEquals(0, finish())
        assertEquals("a\nb\nc\n", file("/home/c.txt")!!.decodeToString())
    }

    @Test
    fun `4KiBを超える貼り付けも、挿入で欠けずに入る`() {
        val lua = (1..400).joinToString("") { "  if x == $it then\n    print(\"値$it\")\n  end\n" }
        val bytes = lua.toByteArray()
        val end = (6_000 until bytes.size).first { bytes[it] == '\n'.code.toByte() } + 1
        val text = bytes.copyOf(end).decodeToString()
        vi(listOf("big.lua")) {
            keys("i")
            paste(text)
            keys("$esc:wq\r")
            assertEquals(0, finish())
            assertEquals(text, file("/home/big.lua")!!.decodeToString())
        }
    }

    @Test
    fun `1行の画面では、知らせかコマンドの入力があるときだけ、いちばん下の行が本文を覆う`() = vi(
        listOf("r.txt"),
        files = mapOf("/home/r.txt" to "abcdef\n".toByteArray()),
        columns = 40,
        rows = 1,
    ) {
        assertEquals("\"r.txt\" 1L, 7B", row(0))
        assertFalse(host.terminal.cursorVisible, screen)
        keys("l")
        assertEquals("abcdef", row(0))
        assertEquals(1 to 0, host.cursor())
        assertTrue(host.terminal.cursorVisible)
        keys(":")
        assertEquals(":", row(0))
        assertEquals(1 to 0, host.cursor())
        keys(esc)
        assertEquals("abcdef", row(0))
        // 挿入の表示は本文を覆わない
        keys("ix")
        assertEquals("axbcdef", row(0))
        keys("$esc:q!\r")
        assertEquals(0, finish())
    }

    @Test
    fun `2行と3行の画面と、25行から2行への大きさの変更`() {
        vi(listOf("r.txt"), files = mapOf("/home/r.txt" to "abcdef\nsecond\nthird\n".toByteArray()), columns = 40, rows = 2) {
            assertEquals("abcdef", row(0))
            assertTrue(row(1).startsWith("\"r.txt\""), screen)
            keys("j")
            assertEquals("second", row(0))
            assertEquals("", row(1))
            assertFalse(host.text().contains("\u001b[1;1r"), "1 行のスクロール領域を使った")
            keys(":q\r")
            assertEquals(0, finish())
        }
        vi(listOf("r.txt"), files = mapOf("/home/r.txt" to "abcdef\n".toByteArray()), columns = 40, rows = 3) {
            assertEquals(listOf("abcdef", "~"), rows.subList(0, 2))
            assertTrue(row(2).startsWith("\"r.txt\""), screen)
            keys(":q\r")
            assertEquals(0, finish())
        }
        vi(listOf("r.txt"), files = mapOf("/home/r.txt" to "abcdef\nxyz\n".toByteArray())) {
            m.resizeConsole(80, 2)
            host.resize(80, 2)
            keys("j")
            assertEquals("xyz", row(0))
            m.resizeConsole(80, 25)
            host.resize(80, 25)
            keys("k")
            assertEquals(listOf("abcdef", "xyz", "~"), rows.subList(0, 3))
            assertEquals("~", row(23))
            keys(":q\r")
            assertEquals(0, finish())
        }
    }
}
