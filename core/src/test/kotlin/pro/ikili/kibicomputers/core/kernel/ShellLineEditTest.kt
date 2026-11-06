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
     * 打ち終えたら、[exit] なら終わるまで、そうでなければ [settled] が真になるまで待つ
     */
    private fun session(
        steps: List<String>,
        files: Map<String, String> = emptyMap(),
        env: Map<String, String> = emptyMap(),
        columns: Int = 80,
        rows: Int = 25,
        exit: Boolean = true,
        read: List<String> = emptyList(),
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
                    m.offerConsoleInput(steps[next++].toByteArray())
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
}
