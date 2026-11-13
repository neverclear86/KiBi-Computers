package pro.ikili.kibicomputers.core.kernel

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
import pro.ikili.kibicomputers.core.kernel.vfs.FileType
import pro.ikili.kibicomputers.core.kernel.vfs.ImageFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.io.ByteArrayOutputStream
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * `/bin/man` と同梱の man ページ（#106）。ページを探す順・言語のフォールバック・`-k`・折り返しは `/bin/sh -c` から、
 * ページ送りは TTY の上で `/bin/man` を直に動かして、端末の解釈（`AnsiTerminal`）の画面で確かめる
 */
class ManCommandTest {
    private val limit = Duration.ofSeconds(240)

    private val image: ImageFileSystem by lazy { requireNotNull(ImageFileSystem.bundled("os")) { "os.zip がリソースにない" } }

    private fun imageFile(path: String): ByteArray {
        val stat = image.stat(VfsPath.parse(path))
        val bytes = ByteArray(stat.size.toInt())
        image.read(stat.node, 0, bytes, 0, bytes.size)
        return bytes
    }

    /** イメージの [dir] の下の普通のファイル（パス → 中身） */
    private fun imageTree(dir: String): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        fun walk(path: String) {
            for (e in image.readDirectory(image.stat(VfsPath.parse(path)).node)) {
                val child = "$path/${e.name}"
                when (e.type) {
                    FileType.DIRECTORY -> walk(child)
                    FileType.REGULAR_FILE -> out[child] = imageFile(child)
                    else -> {}
                }
            }
        }
        walk(dir)
        return out
    }

    private val manPages: Map<String, ByteArray> by lazy { imageTree("/usr/share/man") }

    private fun Machine.put(path: String, bytes: ByteArray) {
        val p = VfsPath.parse(path)
        var parent = VfsPath.ROOT
        for (name in p.toString().trim('/').split('/').dropLast(1)) {
            parent = VfsPath.parse("$parent/$name".replace("//", "/"))
            if (runCatching { vfs.stat(parent) }.isFailure) vfs.createDirectory(parent)
        }
        val f = vfs.open(p, OpenOptions(read = true, write = true, create = true, truncate = true))
        vfs.write(f, bytes, 0, bytes.size)
    }

    private fun Machine.setUp(files: Map<String, String>) {
        vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        for (d in listOf("/bin", "/tmp", "/home")) vfs.createDirectory(VfsPath.parse(d))
        for (name in listOf("sh", "man", "cat", "echo", "head", "grep", "true", "false")) put("/bin/$name", imageFile("/bin/$name"))
        for ((path, bytes) in manPages) put(path, bytes)
        for ((path, text) in files) put(path, text.toByteArray())
    }

    private class ConsoleHost : MachineHost {
        private val out = ByteArrayOutputStream()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            synchronized(out) { out.write(bytes, 0, bytes.size) }
        }

        fun text(): String = synchronized(out) { out.toString(Charsets.UTF_8).replace("\r\n", "\n") }
    }

    /** `/bin/sh -c [script]` を動かし、終わるまで待ってコンソールの出力を返す */
    private fun sh(script: String, files: Map<String, String> = emptyMap(), env: Map<String, String> = emptyMap()): String = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val host = ConsoleHost()
            val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 32L * 1024 * 1024)
            m.setUp(files)
            val pid = m.exec(
                "/bin/sh",
                KernelDrivers.DEFAULT,
                SpawnOptions(
                    args = listOf("sh", "-c", script.trimIndent()),
                    env = mapOf("PATH" to "/bin", "HOME" to "/home") + env,
                    cwd = "/",
                    fds = SpawnOptions.ROOT_PREOPEN,
                ),
            )
            eventuallyDescribe(200_000, { "終わらない: ${host.text()}" }) {
                h.scheduler.tick()
                Thread.sleep(1)
                m.stateOf(pid).isTerminal
            }
            repeat(5) { h.scheduler.tick() }
            assertEquals(ProcessState.EXITED, m.stateOf(pid), host.text())
            host.text()
        }
    }

    /** 画面に表示する幅（全角は 2） */
    private fun displayWidth(s: String): Int = s.codePoints().toArray().sumOf { if (CharWidth.isWide(it)) 2 else 1 }

    @Test
    fun `binとsbinのすべてのプログラムに、英語と日本語のページがある`() {
        var count = 0
        for ((dir, sec) in listOf("/bin" to "1", "/sbin" to "8")) {
            for (e in image.readDirectory(image.stat(VfsPath.parse(dir)).node)) {
                for (lang in listOf("", "ja/")) {
                    val page = "/usr/share/man/${lang}man$sec/${e.name}.$sec"
                    assertEquals(FileType.REGULAR_FILE, runCatching { image.stat(VfsPath.parse(page)).type }.getOrNull(), "$dir/${e.name} のページ $page がない")
                }
                count++
            }
        }
        assertTrue(count > 40, "プログラムが $count 個しかない")
        // 言語ごとのページの組がそろっている（訳だけ・英語だけのページがない）
        val en = manPages.keys.filter { !it.startsWith("/usr/share/man/ja/") }.map { it.removePrefix("/usr/share/man/") }.toSet()
        val ja = manPages.keys.filter { it.startsWith("/usr/share/man/ja/") }.map { it.removePrefix("/usr/share/man/ja/") }.toSet()
        assertEquals(en, ja)
        for (p in listOf("man5/inittab.5", "man7/intro.7", "man7/kibi.7")) assertTrue(p in en, p)
    }

    @Test
    fun `パイプにはSGRなしで80桁に折って書き、言語はLANGの形からフォールバックする`() {
        val out = sh(
            """
            man ls | head -3
            echo ---
            LANG=ja_JP.UTF-8 man ls | head -3
            echo ---
            LANG=ja man -L C ls | head -3
            echo ---
            LC_ALL=fr_FR.UTF-8 man ls | head -3
            echo ---
            LANG=C LC_MESSAGES=ja man ls | head -3
            echo ---
            man -L ja_JP ls | head -3
            echo ---
            LANG=ja man ls | grep -c .
            """,
        )
        val blocks = out.split("---\n").map { it.trim('\n') }
        val en = "ls(1)\n\nNAME"
        val ja = "ls(1)\n\n名前"
        assertEquals(listOf(en, ja, en, en, ja, ja), blocks.take(6), out)
        assertFalse(out.contains('\u001b'), out)
    }

    @Test
    fun `節を書くとその節だけを探し、書かなければ1・8・5・7の順`() {
        val out = sh(
            """
            man inittab | head -1
            man 5 inittab | head -1
            man 1 inittab; echo "status ${'$'}?"
            man init | head -1
            man intro | head -1
            """,
        )
        assertEquals("inittab(5)\ninittab(5)\nman: no manual entry for inittab in section 1\nstatus 1\ninit(8)\nintro(7)\n", out)
    }

    @Test
    fun `見つからない・使い方の誤り・ページの外を指す名前`() {
        val out = sh(
            """
            man nosuch; echo "status ${'$'}?"
            man; echo "status ${'$'}?"
            man -x ls; echo "status ${'$'}?"
            man ../../../etc/secret; echo "status ${'$'}?"
            man -L ../../../etc ls | head -1
            man .secret; echo "status ${'$'}?"
            """,
            files = mapOf("/etc/secret" to "# secret(1)\n", "/usr/share/man/man1/.secret.1" to "# hidden(1)\n"),
        )
        assertEquals(
            """
            man: no manual entry for nosuch
            status 1
            man: which manual page do you want?
            usage: man [-L lang] [section] name... | man -k keyword...
            status 2
            man: unknown option -x
            usage: man [-L lang] [section] name... | man -k keyword...
            status 2
            man: no manual entry for ../../../etc/secret
            status 1
            ls(1)
            man: no manual entry for .secret
            status 1
            """.trimIndent() + "\n",
            out,
        )
    }

    @Test
    fun `MANPATHのページも探し、訳のない言語は英語になる`() {
        val out = sh(
            """
            MANPATH=/home/man:/usr/share/man man hello | cat
            LANG=ja MANPATH=/home/man man hello | cat
            MANPATH=/home/man man ls; echo "status ${'$'}?"
            """,
            files = mapOf("/home/man/man1/hello.1" to "# hello(1)\n\n## NAME\n\nhello - say hello\n"),
        )
        val page = "hello(1)\n\nNAME\n    hello - say hello\n"
        assertEquals(page + page + "man: no manual entry for ls\nstatus 1\n", out.replace(Regex("\n+(?=man:)"), "\n"))
    }

    @Test
    fun `kはNAMEの行から探し、言語の訳があれば訳を出す`() {
        val out = sh(
            """
            man -k directory
            echo ---
            LANG=ja man -k ディレクトリ | grep -c .
            LANG=ja man -k 'list directory' | head -1
            LANG=ja man -k mount
            echo ---
            man -k zzzz; echo "status ${'$'}?"
            """,
        )
        val (en, ja, none) = out.split("---\n")
        assertTrue(en.lines().contains("ls(1) - list directory contents"), en)
        assertTrue(en.lines().contains("pwd(1) - print the working directory"), en)
        assertFalse(en.lines().any { it.startsWith("cat(1)") }, en)
        val jaLines = ja.trim('\n').lines()
        assertTrue(jaLines[0].toInt() >= 3, ja)
        // 日本語に「list directory」は合わないので、英語のページで探す
        assertEquals("ls(1) - list directory contents", jaLines[1], ja)
        assertTrue(jaLines.contains("mount(1) - ディスクをマウントする・マウントを一覧する"), ja)
        assertTrue(jaLines.contains("umount(1) - マウントを外す"), ja)
        assertEquals("man: zzzz: nothing appropriate\nstatus 1\n", none)
    }

    @Test
    fun `空のページ・大きすぎるページ・合計が入りきらないページは、理由を知らせて1で終わる`() {
        val filler = "word ".repeat(12 * 1024)
        val big = "# big(1)\n\n## NAME\n\n" + "x ".repeat(33 * 1024)
        val out = sh(
            """
            MANPATH=/home/man man empty; echo "status ${'$'}?"
            MANPATH=/home/man man big; echo "status ${'$'}?"
            MANPATH=/home/man man p1 p2 p3 | grep -c .
            """,
            files = mapOf(
                "/home/man/man1/empty.1" to "",
                "/home/man/man1/big.1" to big,
                "/home/man/man1/p1.1" to "# p1(1)\n\n$filler\n",
                "/home/man/man1/p2.1" to "# p2(1)\n\n$filler\n",
                "/home/man/man1/p3.1" to "# p3(1)\n\n$filler\n",
            ),
        )
        val lines = out.lines()
        assertEquals("man: empty: manual page is empty", lines[0], out)
        assertEquals("status 1", lines[1], out)
        assertEquals("man: big: manual page too large", lines[2], out)
        assertEquals("status 1", lines[3], out)
        // 3 つめだけが合計に入らない（1 ページの大きさの超過とは別の知らせ）。入った 2 つは書く
        assertEquals("man: p3: too many manual pages at once (total size limit)", lines[4], out)
        assertTrue(lines[5].toInt() > 100, out)
    }

    @Test
    fun `16Ki文字を超える段落も、文字を捨てずに続きを書く`() {
        val words = (1..3500).joinToString(" ") { "w%04d".format(it) }
        val out = sh(
            """
            MANPATH=/home/man man big | cat
            """,
            files = mapOf("/home/man/man1/big.1" to "# big(1)\n\n$words\n"),
        )
        assertFalse(out.contains("truncated"), out)
        val found = Regex("w\\d{4}").findAll(out).map { it.value }.toList()
        assertEquals((1..3500).map { "w%04d".format(it) }, found)
        for (line in out.lines()) assertTrue(displayWidth(line) <= 80, line)
    }

    @Test
    fun `幅を超える見出しは空白の位置で折る`() {
        val out = sh(
            """
            MANWIDTH=20 MANPATH=/home/man man h | cat
            """,
            files = mapOf("/home/man/man1/h.1" to "# h(1)\n\n## alpha beta gamma delta epsilon\n\ntext\n"),
        )
        assertTrue(out.contains("alpha beta gamma\ndelta epsilon\n"), out)
    }

    @Test
    fun `どのページも幅50と80で、英語も日本語も行が幅を超えない`() {
        val names = manPages.keys.filter { !it.startsWith("/usr/share/man/ja/") }.map { it.substringAfterLast('/') }
        val script = buildString {
            for (width in listOf(50, 80)) {
                for (lang in listOf("C", "ja")) {
                    for (n in names) {
                        val name = n.substringBeforeLast('.')
                        val sec = n.substringAfterLast('.')
                        appendLine("echo '=== $width $lang $n'")
                        appendLine("MANWIDTH=$width man -L $lang $sec $name | cat")
                    }
                }
            }
        }
        val out = sh(script)
        var current = 80
        var checked = 0
        for (line in out.lines()) {
            if (line.startsWith("=== ")) {
                current = line.split(' ')[1].toInt()
                checked++
                continue
            }
            assertTrue(displayWidth(line) <= current, "幅 $current を超える: [$line]")
            assertFalse(line.contains('�'), "不正な UTF-8: [$line]")
        }
        assertEquals(names.size * 4, checked)
        assertFalse(out.contains("no manual entry"), out)
    }

    // ---- ページ送り ----

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

        fun bytes(): Int = synchronized(this) { out.size() }

        fun resize(columns: Int, rows: Int) = synchronized(this) { terminal.resize(columns, rows) }

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
    }

    private inner class Pager(val h: Harness, val m: Machine, val host: ScreenHost, val pid: Int) {
        fun settle() {
            var last = -1
            var still = 0
            eventuallyDescribe(60_000, { "入力を待たない: ${m.stateOf(pid)}\n${host.rows().joinToString("\n")}" }) {
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

        fun keys(s: String) {
            m.offerConsoleInput(s.toByteArray())
            settle()
        }

        /** 画面とコンソールの TTY の大きさを変える（man は次のキーで読み直す） */
        fun resize(columns: Int, rows: Int) {
            m.resizeConsole(columns, rows)
            host.resize(columns, rows)
        }

        val rows: List<String> get() = host.rows()

        fun screen(): String = rows.joinToString("\n")
        val ttyMode: TtyMode get() = m.lock.withLockValue { m.consoleTtyLocked().mode }
        val finished: Boolean get() = m.stateOf(pid).isTerminal

        fun exitCode(): Int {
            assertEquals(ProcessState.EXITED, m.stateOf(pid))
            return m.lock.withLockValue { m.kernel.processes[pid]!!.exitCode }!!
        }
    }

    private fun pager(args: List<String>, env: Map<String, String> = emptyMap(), columns: Int = 50, rows: Int = 16, body: Pager.() -> Unit) = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val host = ScreenHost(columns, rows)
            val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 16L * 1024 * 1024)
            if (columns != 80 || rows != 25) m.resizeConsole(columns, rows)
            m.setUp(emptyMap())
            val pid = m.exec(
                "/bin/man",
                KernelDrivers.DEFAULT,
                SpawnOptions(args = listOf("man") + args, env = mapOf("PWD" to "/") + env, cwd = "/", fds = SpawnOptions.ROOT_PREOPEN),
            )
            val p = Pager(h, m, host, pid)
            p.settle()
            p.body()
        }
    }

    @Test
    fun `長いページは画面ごとに送り、qで閉じると元の画面とモードに戻る`() = pager(listOf("man")) {
        assertFalse(ttyMode.canonical)
        assertFalse(ttyMode.signals)
        assertTrue(host.terminal.alternateScreen)
        assertEquals("man(1)", rows[0])
        assertTrue(rows[15].startsWith("--More--("), rows.joinToString("\n"))
        for (r in rows) assertTrue(displayWidth(r) <= 50, r)
        val first = rows.take(15)
        keys(" ")
        assertTrue(rows.take(15) != first, rows.joinToString("\n"))
        keys("b")
        assertEquals(first, rows.take(15))
        keys("j")
        assertEquals(first.drop(1), rows.take(14))
        keys("G")
        assertEquals("(END)", rows[15])
        keys("q")
        settle()
        assertTrue(finished)
        assertEquals(0, exitCode())
        assertFalse(host.terminal.alternateScreen)
        assertEquals(TtyMode.COOKED, ttyMode)
    }

    @Test
    fun `日本語のページは幅50の画面で折り、Ctrl+Cでも閉じてモードを戻す`() = pager(listOf("ls"), env = mapOf("LANG" to "ja_JP.UTF-8")) {
        assertEquals("ls(1)", rows[0])
        assertTrue(rows.any { it.contains("ディレクトリ") }, rows.joinToString("\n"))
        for (r in rows) assertTrue(displayWidth(r) <= 50, r)
        keys("\u0003")
        assertTrue(finished)
        assertEquals(0, exitCode())
        assertEquals(TtyMode.COOKED, ttyMode)
    }

    @Test
    fun `行数だけ変わると送る量が変わり、いちばん上の行はそのまま`() = pager(listOf("man")) {
        val tall = rows.take(15)
        resize(50, 10)
        keys("x") // 何もしないキー。大きさを読み直して描き直す
        assertEquals(tall.take(9), rows.take(9))
        assertTrue(rows[9].startsWith("--More--("), screen())
        keys(" ")
        // 新しい行数 − 1（9 行）だけ送る
        assertEquals(tall.drop(9).take(6), rows.take(6))
        assertTrue(rows[9].startsWith("--More--("), screen())
        keys("q")
        assertTrue(finished)
    }

    @Test
    fun `幅が変わると組み直し、行は新しい幅に収まり、いちばん上は最後のページを超えない`() = pager(listOf("man")) {
        keys(" ")
        val before = rows.take(15)
        // 狭くする: 新しい幅で折り直し、ページの途中のまま
        resize(30, 16)
        keys("x")
        for (r in rows) assertTrue(displayWidth(r) <= 30, "幅 30 を超える: [$r]")
        assertTrue(rows[15].startsWith("--More--("), screen())
        assertTrue(rows.take(15) != before, screen())
        // いちばん上は割合で移るので、先頭（top = 0）には戻らない
        assertTrue(rows[0] != "man(1)", screen())
        // 最後のページで広げる: 行が減っても、いちばん上は新しい最後のページ（G と同じ位置）に収まる
        keys("G")
        assertEquals("(END)", rows[15])
        resize(80, 16)
        keys("x")
        for (r in rows) assertTrue(displayWidth(r) <= 80, "幅 80 を超える: [$r]")
        assertEquals("(END)", rows[15])
        val afterResize = rows
        keys("G")
        assertEquals(afterResize, rows)
        // 最後のページが画面の途中から始まっていない（1 行戻ると画面が変わる）
        keys("k")
        assertTrue(rows != afterResize, screen())
        keys("q")
        assertTrue(finished)
        assertEquals(0, exitCode())
        assertEquals(TtyMode.COOKED, ttyMode)
    }

    @Test
    fun `空白で最後まで送ると閉じる。画面に収まるページは送らずに書く`() {
        pager(listOf("true"), rows = 40) {
            assertTrue(finished)
            assertEquals(0, exitCode())
            assertFalse(host.terminal.alternateScreen)
            assertTrue(rows.any { it == "true(1)" }, rows.joinToString("\n"))
        }
        pager(listOf("cat")) {
            assertFalse(finished)
            repeat(10) { if (!finished) keys(" ") }
            assertTrue(finished)
            assertEquals(TtyMode.COOKED, ttyMode)
        }
    }
}
