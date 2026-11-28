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
import pro.ikili.kibicomputers.core.testing.bodyTimeout as timeout

/**
 * 会話的な `/bin/sh` の行の編集・補完・履歴（#102）を、カーネルの TTY の上で動かして確かめる。
 * コンソールの出力は、端末の解釈（`AnsiTerminal`）にも通して、画面の見え方も確かめる
 */
class ShellLineEditTest {
    private val limit = Duration.ofSeconds(180)

    private class ScreenHost(columns: Int, rows: Int) : MachineHost {
        private val out = ByteArrayOutputStream()
        val terminal = AnsiTerminal(TextBuffer(columns, rows), 256)

        /** 書かれたバイト数と、末尾の 2 バイト（プロンプトを見分けるため。tick ごとに出力の全体を文字列にしないように。#993） */
        private var size = 0L
        private var last1 = -1
        private var last2 = -1

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            synchronized(this) {
                out.write(bytes, 0, bytes.size)
                terminal.write(bytes)
                for (b in bytes) {
                    last2 = last1
                    last1 = b.toInt() and 0xff
                }
                size += bytes.size
            }
        }

        /** 出力の後ろがプロンプト（`$ `・`> `）なら、そこまでに書かれたバイト数。そうでなければ -1 */
        fun promptEnd(): Long = synchronized(this) {
            if (last1 == ' '.code && (last2 == '$'.code || last2 == '>'.code)) size else -1L
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

    /** [promptFuel] は、[session] の steps を 1 つ打つたびの、打つ直前（プロンプトが出た時点）のスケジューラの燃料の使用量 */
    private class Session(val host: ScreenHost, val exitCode: Int?, val files: Map<String, String>, val promptFuel: List<Long>) {
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
     * [steps] は [charset] でバイトにする（ISO-8859-1 なら、不正な UTF-8 のバイトをそのまま打てる）。
     * [splittable] なら貼り付けと同じく分けて入れてよい入力として渡す（raw の TTY の 4 KiB を超える行を打つため。#609）。
     * どちらでも、TTY が 1 バイトも捨てずに受け付けたことを確かめる。
     * カーネルの TTY の幅は、はじめは [consoleColumns]（画面の幅とは別にできる）で、[beforeStep] が i 番目の step を打つ直前に呼ばれる
     */
    private fun session(
        steps: List<String>,
        files: Map<String, String> = emptyMap(),
        env: Map<String, String> = emptyMap(),
        columns: Int = 80,
        rows: Int = 25,
        consoleColumns: Int = columns,
        beforeStep: (Int, Machine) -> Unit = { _, _ -> },
        exit: Boolean = true,
        read: List<String> = emptyList(),
        charset: Charset = Charsets.UTF_8,
        splittable: Boolean = false,
        settled: (ScreenHost) -> Boolean = { true },
    ): Session = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val host = ScreenHost(columns, rows)
            val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 16L * 1024 * 1024)
            if (consoleColumns != 80 || rows != 25) m.resizeConsole(consoleColumns, rows)
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
            var seen = 0L
            val promptFuel = ArrayList<Long>()
            eventuallyDescribe(150_000, { "終わらない: ${host.text()}\n${host.rows().joinToString("\n")}" }) {
                h.scheduler.tick()
                val end = host.promptEnd()
                // プロンプトが見えただけでは、sh はまだ CPU を持っているかもしれない（書いてから poll で待つまでの間は、渡されたスライスの
                // 未返却の分だけ燃料の使用量が多く出る）。入力を待って CPU を返した（WAITING_EVENT）のを確かめてから、燃料を読んで打つ
                if (next < steps.size && end > seen && m.stateOf(pid) == ProcessState.WAITING_EVENT) {
                    seen = end
                    promptFuel += h.scheduler.fuelConsumedTotal
                    beforeStep(next, m)
                    val bytes = steps[next++].toByteArray(charset)
                    assertEquals(bytes.size, m.offerConsoleInput(bytes, splittable = splittable), "TTY が入力を捨てた")
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
            Session(host, exitCode, read.associateWith { m.readText(it) ?: "<none>" }, promptFuel)
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

    @Test
    fun `上限を超えた行は実行せず履歴にも残さない`() {
        // 1 行の上限は 4096 バイト（ADR 0102）。超えた行は貼り付けで渡す
        val a = "A".repeat(5000)
        val ok = "echo " + "O".repeat(4076) + " > /home/ok.txt"
        val ng = "echo " + "N".repeat(4077) + " > /home/ng.txt"
        // 全角の 1 文字が境目をまたぐ（4094 バイトの後ろの 3 バイト）。後ろの C は入るが、印は残る
        val u8Head = "> /home/u8.txt echo " + "B".repeat(4074)
        val u8 = u8Head + "あC"
        assertEquals(4096, ok.toByteArray().size)
        assertEquals(4097, ng.toByteArray().size)
        assertEquals(4094, u8Head.toByteArray().size)
        val s = session(
            listOf(
                "echo $a > /home/p1.txt\n",
                "echo \"st=\$?\"\r",
                "$ok\n",
                "$ng\n",
                // 超えた後に Backspace で短くしても、捨てた中身は戻らないので実行しない
                "echo $a" + "\u007f".repeat(1000) + " > /home/bs.txt\n",
                // Ctrl+U で全部消して打ち直した行は実行する
                "echo $a\u0015echo cu > /home/cu.txt\n",
                // 続きの行で超えたら、読みかけのコマンドごと捨てる
                "if true; then\r",
                "echo pre > /home/pre.txt\r",
                "echo $a\n",
                "echo \"st2=\$?\"\r",
                "$u8\n",
                "history\r",
                "exit\r",
            ),
            read = listOf(
                "/home/p1.txt",
                "/home/ok.txt",
                "/home/ng.txt",
                "/home/bs.txt",
                "/home/cu.txt",
                "/home/pre.txt",
                "/home/u8.txt",
                "/home/.sh_history",
            ),
            splittable = true,
        )
        val t = s.text
        val tail = t.takeLast(3000)
        assertEquals(5, Regex("sh: line too long\n").findAll(t).count(), tail)
        assertTrue(t.contains("\nst=2\n"), tail)
        assertTrue(t.contains("\nst2=2\n"), tail)
        assertEquals("<none>", s.files["/home/p1.txt"])
        assertEquals("O".repeat(4076) + "\n", s.files["/home/ok.txt"])
        assertEquals("<none>", s.files["/home/ng.txt"])
        assertEquals("<none>", s.files["/home/bs.txt"])
        assertEquals("cu\n", s.files["/home/cu.txt"])
        assertEquals("<none>", s.files["/home/pre.txt"])
        assertEquals("<none>", s.files["/home/u8.txt"])
        val expected = listOf(
            "echo \"st=\$?\"",
            ok,
            "echo cu > /home/cu.txt",
            "if true; then",
            "echo pre > /home/pre.txt",
            "echo \"st2=\$?\"",
            "history",
            "exit",
        )
        assertEquals(expected.joinToString("\n", postfix = "\n"), s.files["/home/.sh_history"])
        // history の一覧にも、上限を超えた行はない
        assertTrue(!t.contains("  echo AAAA"), tail)
        assertEquals(0, s.exitCode)
    }

    @Test
    fun `切れた行は、Ctrl+Uで消してCtrl+Yで戻しても、履歴を行き来しても実行しない`() {
        val a = "A".repeat(5000)
        val s = session(
            listOf(
                "echo hist\r",
                // 切れた行を Ctrl+U で消して Ctrl+Y で戻すと、印も戻る
                "> /home/cy.txt echo $a\u0015\u0019\n",
                // kill の中身は次の行にも残る。そこで戻しても実行しない
                "\u0019\n",
                // 履歴を呼び戻して編集中の行に戻ると、印も戻る
                "> /home/ud.txt echo $a$up$down\n",
                "echo \"st=\$?\"\r",
                // 履歴の項目に置き換えて実行するなら、切れた行ではなくなる
                "> /home/ud2.txt echo $a$up\r",
                "history\r",
                "exit\r",
            ),
            read = listOf("/home/cy.txt", "/home/ud.txt", "/home/ud2.txt", "/home/.sh_history"),
            splittable = true,
        )
        val t = s.text
        val tail = t.takeLast(2000)
        assertEquals(3, Regex("sh: line too long\n").findAll(t).count(), tail)
        assertTrue(t.contains("\nst=2\n"), tail)
        assertEquals("<none>", s.files["/home/cy.txt"])
        assertEquals("<none>", s.files["/home/ud.txt"])
        // ud2 は直前の履歴（echo "st=$?"）に置き換わったので、ファイルは作られず、st=0 が出る
        assertEquals("<none>", s.files["/home/ud2.txt"])
        assertTrue(t.contains("\nst=0\n"), tail)
        // 続けて同じ行を実行した分は、履歴に重ねない
        assertEquals("echo hist\necho \"st=\$?\"\nhistory\nexit\n", s.files["/home/.sh_history"])
        assertEquals(0, s.exitCode)
    }

    @Test
    fun `補完で入れる文字が行に入りきらないときは、何も入れない`() {
        // ": " + P×4083 + " /tmp/a" で 4092 バイト。残り 4 バイトに、補う 9 バイト（bcdefghij）は入らない
        val line = ": " + "P".repeat(4083) + " /tmp/a"
        assertEquals(4092, line.toByteArray().size)
        val s = session(
            listOf("$line\t\r", "exit\r"),
            files = mapOf("/tmp/abcdefghij" to ""),
            read = listOf("/home/.sh_history"),
            splittable = true,
        )
        // 半端に入れた行（/tmp/abcde など）ではなく、打った行のまま実行する
        assertEquals("$line\nexit\n", s.files["/home/.sh_history"])
        assertTrue(s.text.contains("\u0007"), "ベルが鳴らない")
        assertEquals(0, s.exitCode)
    }

    @Test
    fun `補完の後ろに足す引用符・空白・スラッシュが入らないときは、補った名前も入れない`() {
        // どれも、名前の続きはちょうど入るが、後ろに足す 1 文字が入らない（1 行の上限は 4096 バイト）
        val space = ": " + "P".repeat(4084) + " /tmp/a" // + bcd で 4096。空白が入らない
        val quote = ": " + "P".repeat(4083) + " \"/tmp/a" // + bcd で 4096。閉じる " が入らない
        val dir = ": " + "P".repeat(4084) + " /tmp/dir" // + x で 4096。/ が入らない
        assertEquals(4093, space.toByteArray().size)
        assertEquals(4093, quote.toByteArray().size)
        assertEquals(4095, dir.toByteArray().size)
        val s = session(
            listOf(
                "$space\t\r",
                // 引用符が開いたままの行にはならない。自分で閉じれば、補う前の行のまま実行する
                "$quote\t\"\r",
                "$dir\t\r",
                "exit\r",
            ),
            files = mapOf("/tmp/abcd" to "", "/tmp/dirx/" to ""),
            read = listOf("/home/.sh_history"),
            splittable = true,
        )
        assertEquals(listOf(space, "$quote\"", dir, "exit").joinToString("\n", postfix = "\n"), s.files["/home/.sh_history"])
        assertEquals(3, s.text.count { it == '\u0007' }, s.text.takeLast(500))
        assertEquals(0, s.exitCode)
    }

    @Test
    fun `長い行の貼り付けと、末尾のBackspaceの燃料は、行の長さにほぼ比例する`() {
        // 1 文字ごとに行の頭から数え直したり、折り返し・Backspace のたびに行の全体を描き直したりすると、手間が行の長さの
        // 2 乗になる（#993。直す前は、4 倍の長さの貼り付けで約 16 倍、末尾の位置を覚えるだけで約 12 倍の燃料だった）。
        // 燃料の絶対値は sh のほかの変更で動くので、長さの違う行の比だけを見る（直した後は約 4 倍）
        val steps = listOf(
            ": " + "a".repeat(1000) + "\r",
            ": " + "a".repeat(4000) + "\r",
            ": " + "a".repeat(1000) + "\u007f".repeat(500) + "\r",
            ": " + "a".repeat(4000) + "\u007f".repeat(2000) + "\r",
            "exit\r",
        )
        val s = session(steps, splittable = true)
        assertEquals(0, s.exitCode)
        // steps を打つ直前ごとの燃料の差が、その行の燃料（プロンプトで待つ間は燃料を使わない）
        val perLine = s.promptFuel.zipWithNext { a, b -> b - a }
        assertEquals(4, perLine.size, "$perLine")
        val (paste1, paste4, back1, back4) = perLine
        assertTrue(paste4 < 6 * paste1, "貼り付けの燃料が長さに比例しない: $perLine")
        assertTrue(back4 < 6 * back1, "末尾の Backspace の燃料が長さに比例しない: $perLine")
    }

    @Test
    fun `行の末尾に足して右端で折り返す文字と、末尾のBackspaceは、描き直さなくても画面が崩れない`() {
        val s = session(
            listOf(
                // 3 行に折り返した後、Backspace で行をまたいで 1 行目まで戻り、打ち足す
                "echo 0123456789abcdefghijklmnopqrstuvwxyz" + "\u007f".repeat(24) + "XY\r",
                // 全角がちょうど右端の 2 セルに入る（保留の後の次の行の頭に、次の文字を足す）
                "echo 01234567890あい\r",
                // 全角が右端の 1 セルに入らず、次の行へ送られる
                "echo 012345678901あ\r",
                "exit\r",
            ),
            columns = 20,
            rows = 25,
        )
        val rows = s.host.rows()
        val shown = rows.joinToString("\n")
        val i = rows.indexOf("$ echo 0123456789abX")
        assertTrue(i >= 0, shown)
        assertEquals(listOf("Y", "0123456789abXY"), rows.subList(i + 1, i + 3), shown)
        assertEquals(listOf("$ echo 01234567890あ", "い", "01234567890あい"), rows.subList(i + 3, i + 6), shown)
        assertEquals(listOf("$ echo 012345678901", "あ", "012345678901あ"), rows.subList(i + 6, i + 9), shown)
        assertTrue(rows[i + 9].startsWith("$ exit"), shown)
        assertEquals(0, s.exitCode)
    }

    @Test
    fun `行をまたぐ末尾のBackspaceの後に描き直しても、画面が崩れない`() {
        // 描き直さない Backspace が、カーソルの行（描き直すときにプロンプトの頭へ戻る数）を正しく保つか（#993）。
        // Backspace の後に ← と途中への挿入で描き直す。カーソルの行がずれていると、プロンプトの頭でない行から描き直して画面が崩れる
        val s = session(
            listOf(
                // 3 行から 1 行目の右端の手前まで戻る（画面の行の頭の文字・保留の文字・同じ行の文字を消す）
                "echo 0123456789abcdefghijklmnopqrstuvwxyz" + "\u007f".repeat(24) + left + "Z\r",
                // 1 行目の右端の保留（カーソルは 2 行目の頭）で止める
                "echo 0123456789abcdefghijklmnopqrstuvwxyz" + "\u007f".repeat(23) + left + "Z\r",
                "exit\r",
            ),
            columns = 20,
            rows = 25,
        )
        val rows = s.host.rows()
        val shown = rows.joinToString("\n")
        val i = rows.indexOf("$ echo 0123456789aZb")
        assertTrue(i >= 0, shown)
        assertEquals(
            listOf("0123456789aZb", "$ echo 0123456789abZ", "c", "0123456789abZc"),
            rows.subList(i + 1, i + 5),
            shown,
        )
        assertTrue(rows[i + 5].startsWith("$ exit"), shown)
        assertEquals(0, s.exitCode)
    }

    @Test
    fun `プロンプトを出した後に画面の幅が変わっても、末尾に足す文字とBackspaceは新しい幅で書く`() {
        // プロンプトは幅 80 で描き、打つ前に幅を 20 にする。描き直さない末尾への追加と Backspace も、書く前に幅を読み直す（#993）
        val s = session(
            listOf(
                "echo 0123456789abcdefghijklmnopqrstuvwxyz" + "\u007f".repeat(24) + "XY\r",
                "exit\r",
            ),
            columns = 20,
            rows = 25,
            consoleColumns = 80,
            beforeStep = { i, m -> if (i == 0) m.resizeConsole(20, 25) },
        )
        val rows = s.host.rows()
        val shown = rows.joinToString("\n")
        val i = rows.indexOf("$ echo 0123456789abX")
        assertTrue(i >= 0, shown)
        assertEquals(listOf("Y", "0123456789abXY"), rows.subList(i + 1, i + 3), shown)
        assertTrue(rows[i + 3].startsWith("$ exit"), shown)
        assertEquals(0, s.exitCode)
    }

    @Test
    fun `続きの行で入力の領域の残りが少ないと、入らない履歴の項目は呼び戻さずベルを鳴らす`() {
        // 入力の領域は 64 KiB。引用符を開いたままの行を重ねて、残りを 4090 バイトより小さくする
        val x = "x".repeat(4090)
        val y = "y".repeat(1500)
        val steps = buildList {
            add(": '\r")
            repeat(15) { add("$x\n") }
            add("$y\n")
            // y の項目は入る。x の項目（4090 バイト）は入らず、ベルで行はそのまま
            add("$up$up\u0015'\r")
            add("echo \"st=\$?\"\r")
            add("exit\r")
        }
        val s = session(steps, splittable = true)
        val t = s.text
        assertEquals(1, t.count { it == '\u0007' }, t.takeLast(500))
        assertTrue(t.contains("\nst=0\n"), t.takeLast(500))
        assertEquals(0, s.exitCode)
    }
}
