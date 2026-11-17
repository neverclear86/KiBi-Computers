package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
 * 会話的な `sh` の行の編集で、Esc のキーだけ（続きのないエスケープシーケンス）を、続きを待たずに区切ることを確かめる（#769）。
 * キーのバイト列は 1 回の入力としてまとめて TTY に入るので、続きを別の入力で送ると、別のキーとして読まれる
 */
class ShellEscapeKeyTest {
    private val limit = Duration.ofSeconds(180)

    private class ScreenHost : MachineHost {
        private val out = ByteArrayOutputStream()
        val terminal = AnsiTerminal(TextBuffer(80, 25), 256)

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            synchronized(this) {
                out.write(bytes, 0, bytes.size)
                terminal.write(bytes)
            }
        }

        fun text(): String = synchronized(this) { out.toString(Charsets.UTF_8).replace("\r\n", "\n") }

        fun bytes(): Int = synchronized(this) { out.size() }

        /** 画面の行（右の空白は除く。この試験では半角の文字だけを使う） */
        fun rows(): List<String> = synchronized(this) {
            val b = terminal.buffer
            (0 until b.height).map { y -> buildString { for (x in 0 until b.width) appendCodePoint(b.getCodePoint(x, y)) }.trimEnd() }
        }
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

    /**
     * `sh` を起こし、[inputs] を 1 つずつ別の入力として送る。送るたびに、`sh` が入力を読み切って待機点で待ち、
     * 出力が止まるまで回す（Esc の後で `sh` がもう続きを読まずに待っていることを確かめてから、次を送る）。
     * 最後に `exit` で終わらせ、画面の行のうち、プロンプトの行でも空でもないもの（コマンドの出力）を返す
     */
    private fun run(vararg inputs: String): List<String> = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val host = ScreenHost()
            val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 16L * 1024 * 1024)
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            for (d in listOf("/bin", "/tmp", "/home")) m.vfs.createDirectory(VfsPath.parse(d))
            for (name in listOf("sh", "echo")) m.put("/bin/$name", imageFile("/bin/$name"))
            val pid = m.exec(
                "/bin/sh",
                KernelDrivers.DEFAULT,
                SpawnOptions(args = listOf("sh"), env = mapOf("PATH" to "/bin", "HOME" to "/home"), cwd = "/", fds = SpawnOptions.ROOT_PREOPEN),
            )

            fun settle() {
                var last = -1
                var still = 0
                eventuallyDescribe(60_000, { "入力を待たない: ${m.stateOf(pid)}\n${host.text()}" }) {
                    h.scheduler.tick()
                    Thread.sleep(1)
                    val waiting = m.stateOf(pid) == ProcessState.WAITING_EVENT && m.isAtWaitPoint
                    val drained = m.lock.withLockValue { m.consoleTtyLocked().inputAvailable } == 0
                    val now = host.bytes()
                    still = if (waiting && drained && now == last) still + 1 else 0
                    last = now
                    still >= 5
                }
            }

            settle()
            assertTrue(host.text().endsWith("$ "), host.text())
            for (input in inputs.toList() + "exit\r") {
                val bytes = input.toByteArray()
                assertEquals(bytes.size, m.offerConsoleInput(bytes), "TTY が入力を捨てた")
                if (input == "exit\r") break
                settle()
            }
            eventuallyDescribe(60_000, { "終わらない: ${host.text()}" }) {
                h.scheduler.tick()
                m.stateOf(pid).isTerminal
            }
            host.rows().filter { it.isNotEmpty() && !it.startsWith("$") }
        }
    }

    @Test
    fun `Escだけを押した後のEnterで行を実行する`() {
        val out = run("echo ok", "\u001b", "\r")
        assertEquals(listOf("ok"), out)
    }

    @Test
    fun `Escの後で文字を打つと、その文字が入る`() {
        val out = run("echo o", "\u001b", "k", "\r")
        assertEquals(listOf("ok"), out)
    }

    @Test
    fun `同じ回の入力でEscの後につながったEnterも効く`() {
        val out = run("echo ok\u001b\r")
        assertEquals(listOf("ok"), out)
    }

    @Test
    fun `Escを2回押した後のキーも効く`() {
        val out = run("echo ok", "\u001b\u001b", "\r", "echo again\u001b\u001b\r")
        assertEquals(listOf("ok", "again"), out)
    }

    @Test
    fun `続きのないESCと開き角括弧の後の文字は、矢印ではなく文字として入る`() {
        // 履歴があっても呼び戻さない（`A` を上の矢印の終わりとして読まない）
        val out = run("echo first\r", "echo ", "\u001b[", "A\r")
        assertEquals(listOf("first", "A"), out)
    }

    @Test
    fun `続きのないESCと大文字Oの後の文字は、矢印ではなく文字として入る`() {
        val out = run("echo ab", "\u001bO", "D\r")
        assertEquals(listOf("abD"), out)
    }

    @Test
    fun `CSIの途中に制御文字が来たら区切って、その制御文字を処理する`() {
        val out = run("echo ok\u001b[1;\r", "echo ss3\u001bO\r")
        assertEquals(listOf("ok", "ss3"), out)
    }

    @Test
    fun `1回で届いた矢印とAltのキーは今どおり効く`() {
        val out = run(
            // Alt+B で語の頭へ戻って z を入れる
            "echo xy\u001bbz\r",
            // Alt+Backspace で前の語を消す
            "echo ab cd\u001b\u007f\r",
            // 左の矢印・Ctrl+左・SS3 の右
            "echo xy\u001b[Dz\u001b[1;5Dw\u001bOCv\r",
        )
        assertEquals(listOf("zxy", "ab", "wxvzy"), out)
    }
}
