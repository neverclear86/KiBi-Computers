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
 * `/bin/nano`（#105）を、カーネルの TTY の上で動かして確かめる。コンソールの出力は端末の解釈（`AnsiTerminal`）に通し、
 * 画面の行とカーソルを見る
 */
class NanoEditorTest {
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

    /** 動いている nano。[keys] で打ち、打つたびに入力を待つところまで進める */
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

    private fun nano(
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
            m.put("/bin/nano", imageFile("/bin/nano"))
            for ((path, bytes) in files) m.put(path, bytes)
            setup(m)
            val pid = m.exec(
                "/bin/nano",
                KernelDrivers.DEFAULT,
                SpawnOptions(args = listOf("nano") + args, env = mapOf("PWD" to "/home"), cwd = "/home", fds = SpawnOptions.ROOT_PREOPEN),
            )
            val e = Editor(h, m, host, pid)
            e.settle()
            e.body()
        }
    }

    private val up = "\u001b[A"
    private val down = "\u001b[B"
    private val right = "\u001b[C"
    private val left = "\u001b[D"
    private val ctrlX = "\u0018"
    private val ctrlS = "\u0013"
    private val ctrlO = "\u000f"

    @Test
    fun `開いたファイルを描き、下にキーの案内を出す。書いて保存して終わると、元の画面とモードに戻る`() = nano(
        listOf("hello.lua"),
        files = mapOf("/home/hello.lua" to "print(1)\nprint(2)\n".toByteArray()),
    ) {
        assertFalse(ttyMode.canonical)
        assertFalse(ttyMode.signals)
        assertTrue(host.terminal.alternateScreen, screen)
        val title = row(0)
        assertTrue(title.contains("nano") && title.contains("hello.lua"), title)
        assertEquals("print(1)", row(1))
        assertEquals("print(2)", row(2))
        assertTrue(row(22).contains("Read 2 lines"), screen)
        assertTrue(row(23).startsWith("^G Help"), screen)
        assertTrue(row(23).contains("^O Write Out") && row(23).contains("^W Where Is") && row(23).contains("^C Location"), screen)
        assertTrue(row(24).contains("^_ Go To Line"), screen)
        assertTrue(row(24).startsWith("^X Exit"), screen)
        assertEquals(0 to 1, host.cursor())

        keys("-- hi\r")
        assertEquals("-- hi", row(1))
        assertEquals("print(1)", row(2))
        assertTrue(row(0).contains("Modified"), row(0))
        assertEquals(0 to 2, host.cursor())
        keys(ctrlS)
        assertTrue(row(22).contains("Wrote 3 lines"), screen)
        assertFalse(row(0).contains("Modified"), row(0))
        keys(ctrlX)
        assertEquals(0, finish())
        assertEquals("-- hi\nprint(1)\nprint(2)\n", file("/home/hello.lua")!!.decodeToString())
        assertFalse(host.terminal.alternateScreen)
        assertTrue(host.terminal.cursorVisible)
        assertTrue(ttyMode.canonical)
        // 折り返し・スクロール領域・属性を戻している
        assertTrue(host.text().endsWith("\u001b[r\u001b[0m\u001b[?7h\u001b[?25h\u001b[?1049l"), host.text().takeLast(40))
    }

    @Test
    fun `いちばん小さな画面（50x16）でも案内が切れずに並ぶ`() = nano(listOf("a.txt"), columns = 50, rows = 16) {
        assertTrue(row(13).contains("New File"), screen)
        assertTrue(row(14).startsWith("^G Help"), screen)
        // 項目を切らずに入る数だけ並べる
        assertTrue(row(14).contains("^O Write Out") && row(14).contains("^K Cut"), screen)
        assertTrue(row(15).startsWith("^X Exit") && row(15).contains("^U Paste"), screen)
        for (r in rows) assertTrue(r.length <= 50, r)
        keys(ctrlX)
        assertEquals(0, finish())
        assertEquals(null, file("/home/a.txt"))
    }

    @Test
    fun `編集しないバイトは保存しても変えない。制御文字と不正なUTF-8は印に置き換えて見せる`() {
        val original = byteArrayOf(
            *"a\u001bc\tb\r\n".toByteArray(),
            0, 0xff.toByte(), 0xc0.toByte(), 0x9b.toByte(), 0xc2.toByte(), 0x9b.toByte(), '\n'.code.toByte(),
            *"end".toByteArray(),
        )
        nano(listOf("bin.dat"), files = mapOf("/home/bin.dat" to original)) {
            assertEquals("a^[c    b^M", row(1))
            assertEquals("^@����", row(2))
            assertEquals("end", row(3))
            // 端末は ESC c を受けていない（受ければ代替画面から出る）
            assertTrue(host.terminal.alternateScreen)
            keys(down + down + "\u0005!" + ctrlS)
            keys(ctrlX)
            assertEquals(0, finish())
            assertArrayEquals(original + "!".toByteArray(), file("/home/bin.dat"))
        }
    }

    @Test
    fun `全角の文字を入れて消せる。カーソルは全角の幅で動く`() = nano(listOf("j.txt")) {
        keys("あいう")
        assertEquals("あいう", row(1))
        assertEquals(6 to 1, host.cursor())
        keys(left)
        assertEquals(4 to 1, host.cursor())
        keys("\u007f")
        assertEquals("あう", row(1))
        assertEquals(2 to 1, host.cursor())
        keys(ctrlO)
        assertTrue(row(22).contains("File Name to Write: j.txt"), screen)
        keys("\r")
        keys(ctrlX)
        assertEquals(0, finish())
        assertEquals("あう", file("/home/j.txt")!!.decodeToString())
    }

    @Test
    fun `変更があれば終わる前に聞く。Nなら保存せず、名前のないバッファはYで名前を聞く`() {
        nano(listOf("keep.txt"), files = mapOf("/home/keep.txt" to "old\n".toByteArray())) {
            keys("x")
            keys(ctrlX)
            assertTrue(row(22).contains("Save modified buffer?"), screen)
            assertTrue(row(23).contains("Y Yes"), screen)
            keys("\u0003")
            assertTrue(row(22).contains("Cancelled"), screen)
            keys(ctrlX)
            keys("n")
            assertEquals(0, finish())
            assertEquals("old\n", file("/home/keep.txt")!!.decodeToString())
        }
        nano(emptyList()) {
            assertTrue(row(0).contains("New Buffer"), row(0))
            keys("local x = 1\r")
            keys(ctrlX)
            keys("y")
            assertTrue(row(22).contains("File Name to Write:"), screen)
            keys("new.lua\r")
            assertEquals(0, finish())
            assertEquals("local x = 1\n", file("/home/new.lua")!!.decodeToString())
        }
    }

    @Test
    fun `読み取り専用の場所に保存できなくても、編集を続けて別の名前で書き出せる`() = nano(
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
        keys("more ")
        keys(ctrlS)
        assertTrue(row(22).contains("Error writing /ro/a.txt"), screen)
        assertTrue(row(0).contains("Modified"), row(0))
        keys("+")
        assertEquals("more +data", row(1))
        keys(ctrlO + "\u0015/tmp/b.txt\r")
        assertTrue(row(22).contains("Wrote 1 line"), screen)
        keys(ctrlX)
        assertEquals(0, finish())
        assertEquals("more +data\n", file("/tmp/b.txt")!!.decodeToString())
        assertEquals("data\n", file("/ro/a.txt")!!.decodeToString())
        // 一時ファイルを残していない
        assertEquals(null, file("/tmp/.b.txt.nano0"))
    }

    @Test
    fun `切り取りと貼り付け、検索、行へ移る、位置の表示`() = nano(
        listOf("+3", "f.txt"),
        files = mapOf("/home/f.txt" to (1..40).joinToString("") { "line $it\n" }.toByteArray()),
    ) {
        // +3 で 3 行目から
        assertEquals(0 to 3, host.cursor())
        keys("\u000b\u000b")
        assertEquals("line 5", row(3))
        keys(up + "\u0015")
        assertEquals("line 3", row(2))
        assertEquals("line 4", row(3))
        assertEquals("line 2", row(4))
        keys("\u0017line 30\r")
        assertEquals("line 30", host.rows()[host.cursor().second])
        keys("\u0003")
        assertTrue(row(22).contains("line 30/41"), screen)
        keys("\u001f12,3\r")
        assertEquals("line 12", host.rows()[host.cursor().second])
        assertEquals(2, host.cursor().first)
        keys("\u001b\\")
        assertEquals(0 to 1, host.cursor())
        assertEquals("line 1", row(1))
        keys("\u001bw")
        keys(ctrlX + "n")
        assertEquals(0, finish())
    }

    @Test
    fun `検索は後ろへ探し、終わりまでなければ頭に戻る。カーソルが最後の候補より後ろでも、頭の一致から探す`() = nano(
        listOf("s.txt"),
        files = mapOf("/home/s.txt" to "foo foo xyz".toByteArray()),
    ) {
        // 前に進む（折り返さない）
        keys("\u0017foo\r")
        assertEquals(4 to 1, host.cursor())
        assertFalse(row(22).contains("Wrapped"), screen)
        // 空の入力は前の語。後ろに一致がないので、頭に戻る
        keys("\u0017\r")
        assertEquals(0 to 1, host.cursor())
        assertTrue(row(22).contains("Search Wrapped"), screen)
        // 一致が 1 つだけなら、そう知らせる
        keys("\u0017xyz\r")
        assertEquals(8 to 1, host.cursor())
        keys("\u0017\r")
        assertEquals(8 to 1, host.cursor())
        assertTrue(row(22).contains("This is the only occurrence"), screen)
        // 末尾に近い位置（11 は最後の候補 8 より後ろ）から探しても、3 番目ではなく頭の一致に行く
        keys("\u001b/")
        assertEquals(11 to 1, host.cursor())
        keys(left + "\u0017foo\r")
        assertEquals(0 to 1, host.cursor())
        assertTrue(row(22).contains("Search Wrapped"), screen)
        // 見つからなければ動かない
        keys("\u0017nope\r")
        assertEquals(0 to 1, host.cursor())
        assertTrue(row(22).contains("\"nope\" not found"), screen)
        // M-W は前の語（nope）でもう一度探す
        keys("\u0017foo\r")
        assertEquals(4 to 1, host.cursor())
        keys("\u001bw")
        assertEquals(0 to 1, host.cursor())
        keys(ctrlX + "n")
        assertEquals(0, finish())
    }

    @Test
    fun `ページ送りは本文の行数より2行少なく動く。PgDn・PgUp・^Yで行き来する`() = nano(
        listOf("pg.txt"),
        files = mapOf("/home/pg.txt" to (1..100).joinToString("") { "line $it\n" }.toByteArray()),
    ) {
        // 本文は画面の 1〜21 行目（25 行 - タイトル - 状態 - 案内 2 行）。1 ページは 19 行
        assertEquals("line 1", row(1))
        keys("\u001b[6~")
        assertEquals("line 20", row(1))
        assertEquals(0 to 1, host.cursor())
        keys("\u001b[6~")
        assertEquals("line 39", row(1))
        keys("\u0019")
        assertEquals("line 20", row(1))
        assertEquals(0 to 1, host.cursor())
        keys("\u001b[5~")
        assertEquals("line 1", row(1))
        assertEquals(0 to 1, host.cursor())
        // 先頭では動かない
        keys("\u001b[5~")
        assertEquals("line 1", row(1))
        assertEquals(0 to 1, host.cursor())
        // 画面の途中にカーソルがあっても、カーソルと画面が同じだけ動く
        keys(down.repeat(5) + "\u001b[6~")
        assertEquals("line 20", row(1))
        assertEquals(0 to 6, host.cursor())
        assertEquals("line 25", row(6))
        // 終わりでは行き過ぎない
        keys("\u001b[6~".repeat(10))
        // （最後の LF の後の空の行にカーソルがある）
        assertTrue(rows.contains("line 100"), screen)
        assertEquals("", host.rows()[host.cursor().second])
        keys(ctrlX)
        assertEquals(0, finish())
    }

    @Test
    fun `入力が終わったら、保存していない編集を名前の後ろに save を付けて残す`() {
        nano(listOf("e.txt"), files = mapOf("/home/e.txt" to "old\n".toByteArray())) {
            keys("new ")
            endInput()
            assertEquals(0, finish())
            assertEquals("new old\n", file("/home/e.txt.save")!!.decodeToString())
            assertEquals("old\n", file("/home/e.txt")!!.decodeToString())
        }
        // 名前がなければ nano.save
        nano(emptyList()) {
            keys("x\r")
            endInput()
            assertEquals(0, finish())
            assertEquals("x\n", file("/home/nano.save")!!.decodeToString())
        }
        // 同じ名前のファイルがあれば上書きせず、番号を付けて避ける
        nano(
            listOf("o.txt"),
            files = mapOf(
                "/home/o.txt" to "old\n".toByteArray(),
                "/home/o.txt.save" to "mine\n".toByteArray(),
                "/home/o.txt.save.1" to "mine1\n".toByteArray(),
            ),
        ) {
            keys("new ")
            endInput()
            assertEquals(0, finish())
            assertEquals("mine\n", file("/home/o.txt.save")!!.decodeToString())
            assertEquals("mine1\n", file("/home/o.txt.save.1")!!.decodeToString())
            assertEquals("new old\n", file("/home/o.txt.save.2")!!.decodeToString())
        }
        // 変更がなければ何も書かない
        nano(listOf("n.txt"), files = mapOf("/home/n.txt" to "same\n".toByteArray())) {
            endInput()
            assertEquals(0, finish())
            assertEquals(null, file("/home/n.txt.save"))
            assertEquals("same\n", file("/home/n.txt")!!.decodeToString())
        }
    }

    @Test
    fun `^Kを続けて押すと切り取りに足し、間に別のキーを挟むと切り直す`() = nano(
        listOf("c.txt"),
        files = mapOf("/home/c.txt" to "a\nb\nc\nd\n".toByteArray()),
    ) {
        keys("\u000b\u000b")
        assertEquals("c", row(1))
        assertEquals("d", row(2))
        keys("\u0015")
        assertEquals(listOf("a", "b", "c", "d"), rows.subList(1, 5))
        // 貼り付けの後の ^K は、前の切り取りに足さず切り直す
        keys("\u000b")
        assertEquals(listOf("a", "b", "d"), rows.subList(1, 4))
        // 間にカーソルの移動を挟んでも切り直す（足すなら "c" が残る）
        keys(up + "\u000b")
        assertEquals(listOf("a", "d"), rows.subList(1, 3))
        keys("\u0015")
        assertEquals(listOf("a", "b", "d"), rows.subList(1, 4))
        assertEquals("", row(4))
        keys(ctrlX + "n")
        assertEquals(0, finish())
    }

    @Test
    fun `長い行はカーソルのある行だけ横に送り、端に$を出す`() = nano(
        listOf("long.txt"),
        files = mapOf("/home/long.txt" to ("x".repeat(100) + "\n" + "y".repeat(100) + "\n").toByteArray()),
        columns = 50,
        rows = 16,
    ) {
        assertEquals("x".repeat(49) + "$", row(1))
        keys("\u0005")
        assertTrue(row(1).startsWith("$"), row(1))
        assertTrue(row(1).endsWith("x"), row(1))
        assertEquals("y".repeat(49) + "$", row(2))
        val (x, y) = host.cursor()
        assertEquals(1, y)
        assertTrue(x in 1..48, "$x")
        keys(down)
        assertEquals("x".repeat(49) + "$", row(1))
        keys(ctrlX)
        assertEquals(0, finish())
    }

    @Test
    fun `入力を待つ間は待機点で止まり、スレッドを持たない。画面の大きさが変わると描き直す`() = nano(
        listOf("w.txt"),
        files = mapOf("/home/w.txt" to "abc\n".toByteArray()),
    ) {
        assertTrue(m.isAtWaitPoint)
        assertTrue(h.scheduler.dedicatedThreads.isEmpty(), "専用スレッドがある")
        assertEquals(0, h.scheduler.blockingProcesses, "ブロッキング型の枠を使っている")
        // 待っている間は何も書かない
        val before = host.bytes()
        repeat(50) { h.scheduler.tick() }
        assertEquals(before, host.bytes())
        assertEquals(ProcessState.WAITING_EVENT, m.stateOf(pid))

        m.resizeConsole(60, 20)
        host.resize(60, 20)
        keys(right)
        assertTrue(row(19).startsWith("^X Exit"), screen)
        assertEquals("abc", row(1))
        assertEquals(1 to 1, host.cursor())
        keys(ctrlX)
        assertEquals(0, finish())
    }

    @Test
    fun `1文字を打っても、全画面を書き直さない`() = nano(
        listOf("d.txt"),
        files = mapOf("/home/d.txt" to (1..60).joinToString("") { "row $it\n" }.toByteArray()),
    ) {
        val before = host.bytes()
        keys("z")
        assertTrue(host.bytes() - before < 300, "${host.bytes() - before} バイト書いた")
        assertEquals("zrow 1", row(1))
        // 1 行下へ送るのは、スクロールで動かして 1 行だけ書く
        keys(down.repeat(25))
        val mid = host.bytes()
        keys(down)
        assertTrue(host.bytes() - mid < 300, "${host.bytes() - mid} バイト書いた")
        assertEquals("row 27", row(21))
        keys(ctrlX + "n")
        assertEquals(0, finish())
    }

    @Test
    fun `大きすぎるファイル・ディレクトリ・2つ以上のファイルは開かず、TTYのモードを変えない`() {
        val big = ByteArray(4 * 1024 * 1024 + 1) { 'a'.code.toByte() }
        nano(listOf("big.txt"), files = mapOf("/home/big.txt" to big)) {
            assertEquals(1, finish())
            assertTrue(host.text().contains("nano: big.txt: file is too large"), host.text())
            assertFalse(host.terminal.alternateScreen)
            assertTrue(ttyMode.canonical)
        }
        nano(listOf("/tmp")) {
            assertEquals(1, finish())
            assertTrue(host.text().contains("Is a directory"), host.text())
        }
        nano(listOf("a", "b")) {
            assertEquals(2, finish())
        }
    }

    @Test
    fun `RAMが足りなければ、落ちずに知らせて編集を続ける`() = nano(
        listOf("m.txt"),
        files = mapOf("/home/m.txt" to ("q".repeat(60 * 1024) + "\n").toByteArray()),
        memoryBytes = 768L * 1024,
    ) {
        keys("\u000b")
        repeat(16) {
            keys("\u0015")
        }
        assertTrue(screen.contains("Not enough memory"), screen)
        keys("ok")
        assertTrue(m.stateOf(pid) == ProcessState.WAITING_EVENT, screen)
        keys(ctrlX + "n")
        assertEquals(0, finish())
    }

    @Test
    fun `入力欄のカーソルは入力の後ろに出る`() = nano(listOf("q.txt")) {
        keys(ctrlO)
        // 初めから入っている名前（q.txt）の後ろ
        val label = "File Name to Write: "
        assertTrue(row(22).startsWith(label + "q.txt"), screen)
        assertEquals(label.length + 5 to 22, host.cursor())
        keys("abc")
        assertEquals(label.length + 8 to 22, host.cursor())
        keys("あ")
        assertEquals(label.length + 10 to 22, host.cursor())
        keys("")
        keys(ctrlX)
        assertEquals(0, finish())
    }

    @Test
    fun `4KiBの領域がUTF8の途中で埋まっても、読めた分はすぐ描き、続きでつなぐ`() = nano(listOf("big.txt")) {
        val head = "a".repeat(4094).toByteArray()
        val bytes = "あ".toByteArray()
        assertEquals(3, bytes.size)
        // 4096 バイト目が「あ」の 2 バイト目で終わる
        m.offerConsoleInput(head + bytes.copyOfRange(0, 2))
        settle()
        assertTrue(row(1).contains("aaaa"), row(1))
        m.offerConsoleInput(bytes.copyOfRange(2, 3))
        settle()
        keys(ctrlS + ctrlX)
        assertEquals(0, finish())
        assertEquals("a".repeat(4094) + "あ", file("/home/big.txt")!!.decodeToString())
    }

    @Test
    fun `貼り付けたLuaは字下げを足さずにそのまま入り、まとめて1回描く`() {
        val lua = (1..100).joinToString("") { "  if x == $it then\n    print(\"v$it\")\n  end\n" }.take(3_500)
        nano(listOf("p.lua")) {
            val before = host.bytes()
            // 貼り付けは LF が CR になって届く
            keys(lua.replace('\n', '\r'))
            assertTrue(host.bytes() - before < 4_000, "${host.bytes() - before} バイト書いた")
            keys(ctrlS + ctrlX)
            assertEquals(0, finish())
            assertEquals(lua, file("/home/p.lua")!!.decodeToString())
        }
    }

    @Test
    fun `4KiBを超えるLuaを一度に貼り付けても欠けずに入る`() {
        // 多バイト文字を混ぜて、TTY の入力の上限（4 KiB）の境目が文字の途中に当たりうる長さにする
        val lua = (1..400).joinToString("") { "  if x == $it then\n    print(\"値$it\")\n  end\n" }
        val bytes = lua.toByteArray()
        val end = (6_000 until bytes.size).first { bytes[it] == '\n'.code.toByte() } + 1
        val text = bytes.copyOf(end).decodeToString()
        assertTrue(text.toByteArray().size in 6_000..6_100)
        nano(listOf("big.lua")) {
            paste(text)
            keys(ctrlS + ctrlX)
            assertEquals(0, finish())
            assertEquals(text, file("/home/big.lua")!!.decodeToString())
        }
    }

    @Test
    fun `かたまりの最後の素のESCはEscと見て、次に読んだキーとつながない`() = nano(
        listOf("e.txt"),
        files = mapOf("/home/e.txt" to "abc\n".toByteArray()),
    ) {
        // ESC だけを 1 回の入力として送る（素の Esc。編集では何もしない）
        keys("\u001b")
        // 次の入力の x は M-X にならず、文字として入る
        keys("x")
        assertEquals("xabc", row(1))
        assertFalse(row(22).contains("Unbound key"), screen)
        // 同じかたまりの中の ESC の後の文字は Alt+文字（M-X は割り当てがない）
        keys("\u001bx")
        assertEquals("xabc", row(1))
        assertTrue(row(22).contains("Unbound key"), screen)
        keys(ctrlX + "n")
        assertEquals(0, finish())
    }

    @Test
    fun `修飾キーのついたCSIを読み分ける。Ctrl+Homeはテキストの頭へ、Ctrl+Upは割り当てがない`() = nano(
        listOf("c.txt"),
        files = mapOf("/home/c.txt" to (1..5).joinToString("") { "line $it\n" }.toByteArray()),
    ) {
        keys(down.repeat(3) + right.repeat(2))
        assertEquals(2 to 4, host.cursor())
        keys("\u001b[1;5H")
        assertEquals(0 to 1, host.cursor())
        assertFalse(row(22).contains("Unbound key"), screen)
        // Ctrl+End はテキストの終わり（最後の LF の後の空の行）
        keys("\u001b[1;5F")
        assertEquals(0 to 6, host.cursor())
        keys("\u001b[1;5A")
        assertTrue(row(22).contains("Unbound key"), screen)
        // カーソルは動かない
        assertEquals(0 to 6, host.cursor())
        keys(ctrlX)
        assertEquals(0, finish())
    }

    @Test
    fun `F1（ESC O P）でヘルプを開き、^Xで編集に戻る`() = nano(
        listOf("h.txt"),
        files = mapOf("/home/h.txt" to "abc\n".toByteArray()),
    ) {
        keys("\u001bOP")
        assertEquals("KiBi nano help", row(1))
        assertFalse(rows.contains("abc"), screen)
        keys(ctrlX)
        assertEquals("abc", row(1))
        assertEquals(0 to 1, host.cursor())
        // CSI の形の F1（ESC [ P）でも開く
        keys("\u001b[P")
        assertEquals("KiBi nano help", row(1))
        keys(ctrlX)
        assertEquals("abc", row(1))
        keys(ctrlX)
        assertEquals(0, finish())
    }

    @Test
    fun `同じかたまりで画面の上の行より前に出てから改行しても、上の行の位置がずれない`() = nano(
        listOf("t.txt"),
        files = mapOf("/home/t.txt" to (1..100).joinToString("") { "line $it\n" }.toByteArray()),
    ) {
        keys("\u001b[6~" + right.repeat(2))
        assertEquals("line 20", row(1))
        assertEquals(2 to 1, host.cursor())
        // ↑ で画面の上の行（line 20）より前の line 19 の 2 桁目へ出て、同じかたまりで改行する
        keys(up + "\r")
        assertEquals("ne 19", row(1))
        assertEquals("line 20", row(2))
        assertEquals(0 to 1, host.cursor())
        keys(ctrlX + "n")
        assertEquals(0, finish())
    }

    @Test
    fun `1行の画面では、知らせか入力欄があるときだけ状態の行が本文の行を覆う`() = nano(
        listOf("r.txt"),
        files = mapOf("/home/r.txt" to "abcdef\n".toByteArray()),
        columns = 40,
        rows = 1,
    ) {
        // 起動の知らせが本文を覆う（本文がつながって書かれない）
        assertTrue(row(0).trim().startsWith("[ Read 1 line"), screen)
        assertFalse(row(0).contains("abcdef"), screen)
        // 知らせが消えると、本文が見える。タイトルは出さない
        keys(right)
        assertEquals("abcdef", row(0))
        assertEquals(1 to 0, host.cursor())
        keys("\u0003")
        assertTrue(row(0).contains("line 1/2"), screen)
        assertFalse(row(0).contains("abcdef"), screen)
        // 次のキーで本文に戻る
        keys(right)
        assertEquals("abcdef", row(0))
        assertEquals(2 to 0, host.cursor())
        // 入力欄もカーソルも 0 行目に出る
        keys("\u0017")
        assertTrue(row(0).startsWith("Search"), screen)
        assertEquals(0, host.cursor().second)
        keys("\u0003")
        assertTrue(row(0).contains("Cancelled"), screen)
        keys(right)
        assertEquals("abcdef", row(0))
        keys(ctrlX)
        assertEquals(0, finish())
    }

    @Test
    fun `1行の画面でも、ヘルプを0行目に出して、動かして、^Xで本文に戻る`() = nano(
        listOf("r.txt"),
        files = mapOf("/home/r.txt" to "abcdef\n".toByteArray()),
        columns = 40,
        rows = 1,
    ) {
        // 起動の知らせが出ている最初のキーでヘルプを開く（知らせはキーで消え、ヘルプの間は出ない）
        assertTrue(row(0).trim().startsWith("[ Read 1 line"), screen)
        keys("\u0007")
        assertEquals("KiBi nano help", row(0))
        assertFalse(row(0).contains("Read 1 line"), screen)
        assertFalse(row(0).contains("abcdef"), screen)
        // 1 行ずつ動く（本文の行は 1 行だけ）
        keys(down + down)
        assertTrue(row(0).startsWith("Type to insert text"), screen)
        keys(up + up)
        assertEquals("KiBi nano help", row(0))
        keys(ctrlX)
        assertEquals("abcdef", row(0))
        assertEquals(0 to 0, host.cursor())
        keys(ctrlX)
        assertEquals(0, finish())
    }

    @Test
    fun `2行の画面では、タイトルを出さずに本文1行と状態の行にする`() = nano(
        listOf("r.txt"),
        files = mapOf("/home/r.txt" to "abcdef\nsecond\nthird\n".toByteArray()),
        columns = 40,
        rows = 2,
    ) {
        assertEquals("abcdef", row(0))
        assertTrue(row(1).trim().startsWith("[ Read 3 lines"), screen)
        assertEquals(0 to 0, host.cursor())
        keys(right)
        assertEquals("abcdef", row(0))
        assertEquals("", row(1))
        assertEquals(1 to 0, host.cursor())
        keys("\u0003")
        assertEquals("abcdef", row(0))
        assertTrue(row(1).contains("line 1/4"), screen)
        // 縦に動くと、本文の 1 行が入れ替わる（1 行のスクロール領域は使わない）
        keys(down)
        assertEquals("second", row(0))
        assertEquals(1 to 0, host.cursor())
        assertFalse(host.text().contains("\u001b[1;1r"), "1 行のスクロール領域を使った")
        assertFalse(host.text().contains("\u001b[2;2r"), "状態の行をスクロール領域にした")
        // 入力欄は 1 行目に出て、本文は見えたまま
        keys("\u0017")
        assertTrue(row(1).startsWith("Search"), screen)
        assertEquals("second", row(0))
        assertEquals(1, host.cursor().second)
        keys("\u0003")
        assertTrue(row(1).contains("Cancelled"), screen)
        assertEquals("second", row(0))
        // ヘルプも 0 行目から
        keys("\u0007")
        assertEquals("KiBi nano help", row(0))
        keys(ctrlX)
        assertEquals("second", row(0))
        keys(ctrlX)
        assertEquals(0, finish())
    }

    @Test
    fun `3行の画面では、タイトル・本文・状態の行を1行ずつ出す`() = nano(
        listOf("r.txt"),
        files = mapOf("/home/r.txt" to "abcdef\n".toByteArray()),
        columns = 40,
        rows = 3,
    ) {
        assertTrue(row(0).contains("nano") && row(0).contains("r.txt"), screen)
        assertEquals("abcdef", row(1))
        assertTrue(row(2).contains("Read 1 line"), screen)
        assertEquals(0 to 1, host.cursor())
        keys(ctrlX)
        assertEquals(0, finish())
    }

    @Test
    fun `25行から2行にしてまた25行に戻すと、タイトルの行が消えてから戻る`() = nano(
        listOf("r.txt"),
        files = mapOf("/home/r.txt" to "abcdef\n".toByteArray()),
    ) {
        assertTrue(row(0).contains("r.txt"), screen)
        assertEquals("abcdef", row(1))
        m.resizeConsole(80, 2)
        host.resize(80, 2)
        keys(right)
        assertEquals("abcdef", row(0))
        assertEquals("", row(1))
        assertEquals(1 to 0, host.cursor())
        m.resizeConsole(80, 25)
        host.resize(80, 25)
        keys(right)
        assertTrue(row(0).contains("r.txt"), screen)
        assertEquals("abcdef", row(1))
        assertTrue(row(24).startsWith("^X Exit"), screen)
        assertEquals(2 to 1, host.cursor())
        keys(ctrlX)
        assertEquals(0, finish())
    }
}
