package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.display.CharWidth
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.display.terminal.AnsiTerminal
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.ImageFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 会話的な `/bin/sh` の行の編集・補完・履歴（#102）を、カーネルの TTY の上で動かして確かめる。
 * コンソールの出力は、端末の解釈（`AnsiTerminal`）にも通して、画面の見え方も確かめる
 */
class ShellLineEditTest {
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

        fun text(): String = synchronized(this) { out.toString(Charsets.UTF_8).replace("\r\n", "\n") }

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

        fun rows(): List<String> = (0 until terminal.buffer.height).map { row(it) }

        fun cursor(): Pair<Int, Int> = synchronized(this) { terminal.cursorX to terminal.cursorY }
    }

    private class Session(val host: ScreenHost, val exitCode: Int?, val files: Map<String, String>) {
        val text: String get() = host.text()
    }

    private val image: ImageFileSystem by lazy { requireNotNull(ImageFileSystem.bundled("os")) { "os.zip がリソースにない" } }

    private val probe: ByteArray by lazy {
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/os-shprobe.wasm")) { "os-shprobe.wasm がリソースにない" }
            .use { it.readBytes() }
    }

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

    private fun Machine.readText(path: String): String? = try {
        val f = vfs.open(VfsPath.parse(path), OpenOptions(read = true))
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = vfs.read(f, buf, 0, buf.size)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        out.toString(Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    /**
     * 会話的な `sh` を起こし、プロンプト（`$ `・`> `）が出るたびに [steps] を 1 つずつ打つ。
     * 打ち終えたら、[exit] なら終わるまで、そうでなければ [settled] が真になるまで待つ。
     * [steps] は [charset] でバイトにする（ISO-8859-1 なら、不正な UTF-8 のバイトをそのまま打てる）
     */
    private fun session(
        steps: List<String>,
        files: Map<String, String> = emptyMap(),
        env: Map<String, String> = emptyMap(),
        columns: Int = 80,
        rows: Int = 25,
        exit: Boolean = true,
        read: List<String> = emptyList(),
        charset: Charset = Charsets.UTF_8,
        settled: (ScreenHost) -> Boolean = { true },
    ): Session = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val host = ScreenHost(columns, rows)
            val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 16L * 1024 * 1024)
            if (columns != 80 || rows != 25) m.resizeConsole(columns, rows)
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            for (d in listOf("/bin", "/tmp", "/home")) m.vfs.createDirectory(VfsPath.parse(d))
            for (name in listOf("sh", "echo", "true", "false")) m.put("/bin/$name", imageFile("/bin/$name"))
            for (name in listOf("cat", "args")) m.put("/bin/$name", probe)
            for ((path, text) in files) {
                if (path.endsWith("/")) m.vfs.createDirectory(VfsPath.parse(path.trimEnd('/'))) else m.put(path, text.toByteArray())
            }
            val fullEnv = mapOf("PATH" to "/bin", "HOME" to "/home") + env
            val pid = m.exec("/bin/sh", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("sh"), env = fullEnv, cwd = "/", fds = SpawnOptions.ROOT_PREOPEN))
            var next = 0
            var seen = 0
            eventuallyDescribe(150_000, { "終わらない: ${host.text()}\n${host.rows().joinToString("\n")}" }) {
                h.scheduler.tick()
                val text = host.text()
                if (next < steps.size && text.length > seen && (text.endsWith("$ ") || text.endsWith("> "))) {
                    seen = text.length
                    m.offerConsoleInput(steps[next++].toByteArray(charset))
                }
                Thread.sleep(1)
                if (exit) m.stateOf(pid).isTerminal else next == steps.size && settled(host)
            }
            val exitCode = if (exit) {
                assertEquals(ProcessState.EXITED, m.stateOf(pid), host.text())
                m.lock.withLockValue { m.kernel.processes[pid]!!.exitCode }
            } else {
                null
            }
            repeat(5) { h.scheduler.tick() }
            Session(host, exitCode, read.associateWith { m.readText(it) ?: "<none>" })
        }
    }

    /** バイト [b] をそのまま打つための文字列（ISO-8859-1 で打つ [session] に渡す） */
    private fun raw(vararg b: Int): String = String(CharArray(b.size) { b[it].toChar() })

    private val left = "\u001b[D"
    private val right = "\u001b[C"
    private val up = "\u001b[A"
    private val down = "\u001b[B"

    @Test
    fun `カーソルを動かして行の途中を直せる`() {
        val s = session(
            listOf(
                "echo world\u0001" + right.repeat(5) + "hello \r",
                "echo abcx\u007f\r",
                "garbage\u0015echo cleared\r",
                "echo one two\u0017three\r",
                "echo あいう$left\u007fえ\r",
                "echo tail\u0001\u000bprintf 'k\\n'\r",
                "ehco swap" + left.repeat(7) + "\u0014\r",
                "echo b\u0002a\u0005c\r",
                "echo x y\u001b[1;5D\u001b[1;5Dz\u001b[3~\r",
                "exit 3\r",
            ),
        )
        assertEquals(3, s.exitCode)
        val t = s.text
        for (line in listOf("hello world", "abc", "cleared", "one three", "あえう", "k", "swap", "abc", "z y")) {
            assertTrue(t.contains("\n$line\n"), "「$line」がない: $t")
        }
    }

    @Test
    fun `長い行は折り返して描き、途中に入れても画面が崩れない`() {
        val s = session(
            listOf(
                "echo 0123456789abcdefghijklmnopqrst\u0001" + right.repeat(5) + "X\r",
                "echo ABCDEFGHIJKLMNOPQRSTUVWXYZ\u0001" + right.repeat(5),
            ),
            columns = 20,
            rows = 12,
            exit = false,
            settled = { it.cursor() == 7 to it.rows().indexOfLast { r -> r.startsWith("$ echo ABC") } },
        )
        val rows = s.host.rows()
        val i = rows.indexOf("$ echo X0123456789ab")
        assertTrue(i >= 0, rows.joinToString("\n"))
        assertEquals("cdefghijklmnopqrst", rows[i + 1])
        assertEquals("X0123456789abcdefghi", rows[i + 2])
        assertEquals("jklmnopqrst", rows[i + 3])
        assertEquals("$ echo ABCDEFGHIJKLM", rows[i + 4])
        assertEquals("NOPQRSTUVWXYZ", rows[i + 5])
    }

    @Test
    fun `ちょうど画面の幅で終わる行を確定しても、空行は入らない`() {
        val s = session(listOf("echo 0123456789abc\r", "echo 0123456789abcdefghijklmnopqrstuvw\r", "exit\r"), columns = 20, rows = 12)
        val rows = s.host.rows()
        val shown = rows.joinToString("\n")
        val i = rows.indexOf("$ echo 0123456789abc")
        assertTrue(i >= 0, shown)
        assertEquals("0123456789abc", rows[i + 1], shown)
        // 2 行分ちょうど
        assertEquals("$ echo 0123456789abc", rows[i + 2], shown)
        assertEquals("defghijklmnopqrstuvw", rows[i + 3], shown)
        assertEquals("0123456789abcdefghij", rows[i + 4], shown)
        assertEquals("klmnopqrstuvw", rows[i + 5], shown)
        assertTrue(rows[i + 6].startsWith("$"), shown)
    }

    @Test
    fun `前の出力が改行で終わっていなくても、プロンプトは次の行に出る`() {
        val s = session(listOf("printf abc\r", "exit\r"))
        assertTrue(s.host.rows().contains("abc"), s.host.rows().joinToString("\n"))
        assertTrue(s.host.rows().contains("$ exit"), s.host.rows().joinToString("\n"))
    }

    @Test
    fun `Ctrl+Cは行を捨て、続きの行も捨てる。Ctrl+Dで終わる`() {
        val s = session(
            listOf(
                "echo never\u0003",
                "echo status \$?\r",
                "if true\r",
                "then echo never2\u0003",
                "echo after\r",
                "\u0004",
            ),
        )
        val t = s.text
        assertTrue(t.contains("$ echo never^C\n"), t)
        assertTrue(t.contains("\nstatus 130\n"), t)
        assertTrue(!t.contains("\nnever"), t)
        assertTrue(t.contains("\nafter\n"), t)
        assertEquals(0, s.exitCode)
    }

    @Test
    fun `上下の矢印で履歴を呼び戻し、ファイルに残す`() {
        val s = session(
            listOf(
                "echo one\r",
                "echo two\r",
                "echo draft$up$up$down$down\u0015echo three\r",
                "$up$up$up\r",
                "\u0010\u0010\u000e\r",
                "history\r",
                "exit\r",
            ),
            read = listOf("/home/.sh_history"),
        )
        val t = s.text
        // up×3 は echo one、Ctrl+P×2・Ctrl+N は（one two three one の）three の次 = echo one
        assertEquals(3, Regex("\none\n").findAll(t).count(), t)
        assertTrue(t.contains("    1  echo one\n    2  echo two\n    3  echo three\n    4  echo one\n    5  history\n"), t)
        assertEquals("echo one\necho two\necho three\necho one\nhistory\nexit\n", s.files["/home/.sh_history"])
    }

    @Test
    fun `起動したときに履歴のファイルを読み、HISTSIZEより多ければ書き直す`() {
        val s = session(
            listOf("$up$up\r", "exit\r"),
            files = mapOf("/tmp/h" to "echo a\necho b\necho c\necho d\n"),
            env = mapOf("HISTFILE" to "/tmp/h", "HISTSIZE" to "3"),
            read = listOf("/tmp/h"),
        )
        assertTrue(s.text.contains("\nc\n"), s.text)
        // 読んだときに 3 行に書き直し、その後に 2 行を足した
        assertEquals("echo b\necho c\necho d\necho c\nexit\n", s.files["/tmp/h"])
    }

    @Test
    fun `Tabでコマンドとパスと変数を補う`() {
        val files = mapOf(
            "/tmp/alpha.txt" to "",
            "/tmp/alpine/" to "",
            "/tmp/beta" to "",
            "/tmp/my file" to "spaced\n",
            "/tmp/.hidden" to "",
        )
        val s = session(
            listOf(
                "ech\thi\r",
                "echo /tmp/b\t\r",
                "echo /tmp/alp\t\t\r",
                "cd /tmp/alpi\t\r",
                "pwd\r",
                "cat ../my\t\r",
                "echo \"/tmp/my f\t\r",
                "echo \$HOM\t\r",
                "echo /tmp/\t\t\r",
                "cd ~/\t\r",
                "cd /tmp/n\t\r",
                "exit\r",
            ),
            files = files,
        )
        val t = s.text
        for (line in listOf("hi", "/tmp/beta", "/tmp/alp", "/tmp/alpine", "spaced", "/tmp/my file", "/home")) {
            assertTrue(t.contains("\n$line\n"), "「$line」がない: $t")
        }
        // 2 回目の Tab で候補を並べる（ディレクトリは / 付き、. で始まるものは出さない）
        assertTrue(t.contains("\nalpha.txt  alpine/\n"), t)
        assertTrue(t.contains("\nalpha.txt  alpine/    beta       my file\n"), t)
        assertTrue(s.text.contains("$ cat ../my\\ file \n"), t)
    }

    @Test
    fun `代入を含む語は等号の後ろを補い、代入の後はコマンドの位置になる`() {
        val s = session(
            listOf(
                "FOO=/tmp/bet\t\r",
                "echo \$FOO\r",
                "echo if=/tmp/bet\t\r",
                "FOO=bar ech\tdone\r",
                "exit\r",
            ),
            files = mapOf("/tmp/beta" to "", "/tmp/alpha" to ""),
        )
        val t = s.text
        for (line in listOf("/tmp/beta", "if=/tmp/beta", "done")) {
            assertTrue(t.contains("\n$line\n"), "「$line」がない: $t")
        }
        assertTrue(t.contains("$ FOO=bar echo done\n"), t)
    }

    @Test
    fun `候補の一覧では制御文字をキャレットで見せる`() {
        val s = session(
            listOf("echo /tmp/c\t\t\u0015exit\r"),
            files = mapOf("/tmp/c\u001bx" to "", "/tmp/cy" to ""),
        )
        assertTrue(s.text.contains("\nc^[x  cy\n"), s.text)
        assertTrue(!s.text.contains("c\u001bx"), s.text)
    }

    @Test
    fun `プロンプトのOSCは幅に数えない`() {
        val s = session(
            listOf("echo abc$left${left}X"),
            env = mapOf("PS1" to "\u001b]0;title\u0007$ "),
            exit = false,
            settled = { h -> h.rows().any { it == "$ echo aXbc" } },
        )
        val rows = s.host.rows()
        val y = rows.indexOf("$ echo aXbc")
        assertTrue(y >= 0, rows.joinToString("\n"))
        assertEquals(9 to y, s.host.cursor(), rows.joinToString("\n"))
    }

    @Test
    fun `プロンプトのSTで終わるOSCとDCSなどの文字列と中間のある列は幅に数えない`() {
        val esc = "\u001b"
        val st = "$esc\\"
        // ST で終わる OSC、DCS・SOS・PM・APC、ESC ( B
        val ps1 = "$esc]0;title$st${esc}P1;2q$st${esc}Xsos$st$esc^pm$st${esc}_apc$st$esc(B$ "
        val s = session(
            listOf("echo abc$left${left}X"),
            env = mapOf("PS1" to ps1),
            exit = false,
            settled = { h -> h.rows().any { it.endsWith("echo aXbc") } },
        )
        val rows = s.host.rows()
        val y = rows.indexOfFirst { it.endsWith("echo aXbc") }
        assertTrue(y >= 0, rows.joinToString("\n"))
        // 描き直した後のカーソルは、見える幅（"$ echo aX" の 9）の位置
        assertEquals(9 to y, s.host.cursor(), rows.joinToString("\n"))
    }

    @Test
    fun `幅のない文字は幅に数えない`() {
        // U+302A（CJK の声調記号。Mn）は端末が読み捨てる。全角に数えるとカーソルが 2 セルずれる
        val s = session(
            listOf("echo a\u302Ab${left}X"),
            exit = false,
            settled = { h -> h.rows().any { it == "$ echo aXb" } },
        )
        val rows = s.host.rows()
        val y = rows.indexOf("$ echo aXb")
        assertTrue(y >= 0, rows.joinToString("\n"))
        assertEquals(9 to y, s.host.cursor(), rows.joinToString("\n"))
    }

    @Test
    fun `不正なUTF-8とC1の制御文字はU+FFFDで見せる`() {
        // overlong（E0 80 80）は 1 バイトごとに、C1（C2 85）は 1 文字を U+FFFD に。ISO-8859-1 でバイトをそのまま打つ
        val shown = "$ echo \uFFFD\uFFFD\uFFFD\uFFFDYx"
        val s = session(
            listOf("echo ${raw(0xE0, 0x80, 0x80, 0xC2, 0x85)}x${left}Y"),
            exit = false,
            charset = Charsets.ISO_8859_1,
            settled = { h -> h.rows().any { it == shown } },
        )
        val rows = s.host.rows()
        val y = rows.indexOf(shown)
        assertTrue(y >= 0, rows.joinToString("\n"))
        assertEquals(12 to y, s.host.cursor(), rows.joinToString("\n"))
    }

    @Test
    fun `不正なUTF-8の上のカーソル移動とBackspaceとDeleteは見えている文字ごとに動く`() {
        // overlong（E0 80 80）は U+FFFD が 3 つ。左・右は 1 つずつ、Backspace・Delete も 1 つずつ消す
        val bad = raw(0xE0, 0x80, 0x80)
        val del = "\u001b[3~"
        // 左へ 1 つ → X は 2 つめの後ろ
        val moved = session(
            listOf("echo $bad${left}X"),
            exit = false,
            charset = Charsets.ISO_8859_1,
            settled = { h -> h.rows().any { it == "$ echo ��X�" } },
        )
        val movedRows = moved.host.rows()
        val my = movedRows.indexOf("$ echo ��X�")
        assertTrue(my >= 0, movedRows.joinToString("\n"))
        assertEquals(10 to my, moved.host.cursor(), movedRows.joinToString("\n"))
        // 左へ 3 つ・右へ 1 つ → X は 1 つめの後ろ
        val back = session(
            listOf("echo $bad$left$left$left${right}X"),
            exit = false,
            charset = Charsets.ISO_8859_1,
            settled = { h -> h.rows().any { it == "$ echo �X��" } },
        )
        val backRows = back.host.rows()
        assertTrue(backRows.contains("$ echo �X��"), backRows.joinToString("\n"))
        // Backspace は 1 つだけ消す
        val bs = session(
            listOf("echo $bad\u007f"),
            exit = false,
            charset = Charsets.ISO_8859_1,
            settled = { h -> h.rows().any { it == "$ echo ��" } },
        )
        val bsRows = bs.host.rows()
        assertTrue(bsRows.contains("$ echo ��"), bsRows.joinToString("\n"))
        // Delete も 1 つだけ消す（先頭の 1 つめの前へ戻ってから）
        val dl = session(
            listOf("echo $bad$left$left$left$del"),
            exit = false,
            charset = Charsets.ISO_8859_1,
            settled = { h -> h.rows().any { it == "$ echo ��" } },
        )
        val dlRows = dl.host.rows()
        assertTrue(dlRows.contains("$ echo ��"), dlRows.joinToString("\n"))
    }

    @Test
    fun `プロンプトの不正なUTF-8はバイトごとにU+FFFDにして数える`() {
        // サロゲート（ED A0 80）を端末は 1 つの U+FFFD に読む。sh は 3 つの U+FFFD にして送り、3 セルと数える
        val s = session(
            listOf("PS1='${raw(0xED, 0xA0, 0x80)}> '\r", "echo abc$left${left}X"),
            exit = false,
            charset = Charsets.ISO_8859_1,
            settled = { h -> h.rows().any { it.endsWith("> echo aXbc") } },
        )
        val rows = s.host.rows()
        val y = rows.indexOfFirst { it.endsWith("> echo aXbc") }
        assertTrue(y >= 0, rows.joinToString("\n"))
        assertEquals("\uFFFD\uFFFD\uFFFD> echo aXbc", rows[y])
        assertEquals(12 to y, s.host.cursor(), rows.joinToString("\n"))
    }

    @Test
    fun `代入の値の変数を補う`() {
        val s = session(listOf("FOO=\$HO\t\r", "echo \$FOO\r", "exit\r"))
        val t = s.text
        assertTrue(t.contains("$ FOO=\$HOME"), t)
        assertTrue(t.contains("\n/home\n"), t)
    }
}
