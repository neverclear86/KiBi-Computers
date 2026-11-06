package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
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
 * 標準 OS のビルド（#98）: 同梱イメージ `os`（`os/bin` の Zig のプログラム）と、共通ライブラリ `kibi`（`os/lib/kibi`）を、
 * カーネルの上で確かめる。ライブラリは `os/test/libtest.zig`（テストのリソース `os-libtest.wasm`）を通して確かめる
 */
class StandardOsTest {
    private val limit = Duration.ofSeconds(120)

    private class ConsoleHost : MachineHost {
        private val out = ByteArrayOutputStream()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            synchronized(out) { out.write(bytes, 0, bytes.size) }
        }

        fun text(): String = synchronized(out) { out.toString(Charsets.UTF_8).replace("\r\n", "\n") }
    }

    private class Result(val console: String, val state: ProcessState, val exitCode: Int?, val kind: ProcessKind)

    private val libtest: ByteArray by lazy {
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/os-libtest.wasm")) { "os-libtest.wasm がリソースにない" }
            .use { it.readBytes() }
    }

    private fun Machine.put(path: String, bytes: ByteArray) {
        val f = vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true, truncate = true))
        vfs.write(f, bytes, 0, bytes.size)
    }

    /** [setup] で `/` を用意したマシンで、[path] を [args] で動かして終わるまで待つ */
    private fun run(path: String, args: List<String>, setup: (Machine) -> Unit): Result = Harness(execRuntime = EndiveRuntime()).use { h ->
        val host = ConsoleHost()
        val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 1024L * 1024)
        setup(m)
        val pid = m.exec(path, KernelDrivers.DEFAULT, SpawnOptions(args = args, fds = SpawnOptions.ROOT_PREOPEN))
        val kind = m.rt(pid).driver.kind
        eventually(90_000, "終わらない: ${host.text()}") {
            h.scheduler.tick()
            Thread.sleep(1)
            m.stateOf(pid).isTerminal
        }
        val exitCode = m.lock.withLockValue { m.kernel.processes[pid]!!.exitCode }
        // コンソールの出力は tick で配られるので、終わった後にも回して残りを受け取る
        eventually(30_000, "RAM が戻らない") {
            h.scheduler.tick()
            m.memoryAccount.usedBytes == 0L
        }
        repeat(3) { h.scheduler.tick() }
        Result(host.text(), m.stateOf(pid), exitCode, kind)
    }

    /** 標準 OS のイメージを読み取り専用で `/` にマウントする */
    private fun mountImage(m: Machine) {
        m.vfs.mount(VfsPath.ROOT, requireNotNull(ImageFileSystem.bundled("os")), readOnly = true, source = "disk:image:os")
    }

    /** 書けるメモリの FS を `/` に置き、`/bin/libtest`・`/home`・`/tmp` を作る */
    private fun libraryRoot(m: Machine) {
        m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        for (d in listOf("/bin", "/home", "/tmp")) m.vfs.createDirectory(VfsPath.parse(d))
        m.put("/bin/libtest", libtest)
    }

    private fun lib(vararg args: String): Result = run("/bin/libtest", listOf("libtest") + args, ::libraryRoot)

    private fun Result.assertOk(): Result {
        assertEquals(ProcessState.EXITED, state, console)
        assertEquals(0, exitCode, console)
        assertFalse("FAIL" in console, console)
        return this
    }

    @Test
    fun `同梱イメージosにはbinのプログラムが入り、どれも燃料を計測するWASMになる`() {
        val fs = requireNotNull(ImageFileSystem.bundled("os")) { "os.zip がリソースにない" }
        for (name in listOf("echo", "true", "false")) {
            val stat = fs.stat(VfsPath.parse("/bin/$name"))
            assertEquals(FileType.REGULAR_FILE, stat.type, name)
            val bytes = ByteArray(stat.size.toInt())
            fs.read(stat.node, 0, bytes, 0, bytes.size)
            // WASM の頭（\0asm、版 1）
            assertEquals(listOf(0, 'a'.code, 's'.code, 'm'.code, 1, 0, 0, 0), bytes.take(8).map { it.toInt() }, name)
        }
    }

    @Test
    fun `イメージのechoは引数をつないで書き、待機点型で動く`() = timeout(limit) {
        val r = run("/bin/echo", listOf("echo", "hello,", "standard", "OS"), ::mountImage).assertOk()
        assertEquals("hello, standard OS\n", r.console)
        assertEquals(ProcessKind.WAIT_POINT, r.kind)
        assertEquals("ab", run("/bin/echo", listOf("echo", "-n", "ab"), ::mountImage).assertOk().console)
    }

    @Test
    fun `イメージのtrueは0、falseは1で終わる`() = timeout(limit) {
        assertEquals(0, run("/bin/true", listOf("true"), ::mountImage).exitCode)
        val f = run("/bin/false", listOf("false"), ::mountImage)
        assertEquals(ProcessState.EXITED, f.state)
        assertEquals(1, f.exitCode)
    }

    @Test
    fun `ライブラリの引数と環境変数`() = timeout(limit) {
        assertEquals("a|b c|\n", lib("args", "a", "b c", "").assertOk().console)
        assertEquals("PWD=/\nNONE=(none)\n", lib("env", "PWD", "NONE").assertOk().console)
        val exit = lib("exit", "42")
        assertEquals(ProcessState.EXITED, exit.state)
        assertEquals(42, exit.exitCode)
    }

    @Test
    fun `ライブラリのパスの解決とcwd`() = timeout(limit) {
        val r = lib("path").assertOk()
        assertEquals(listOf("cwd /", "cwd /home", "chdir missing NOENT", "path ok"), r.console.lines().filter { it.isNotEmpty() }, r.console)
    }

    @Test
    fun `ライブラリのファイルとディレクトリ`() = timeout(limit) {
        assertEquals("file ok\n", lib("file", "/tmp").assertOk().console)
    }

    @Test
    fun `ライブラリのエラーはerrnoの名前と説明になる`() = timeout(limit) {
        assertEquals("NOENT No such file or directory\n", lib("errno").assertOk().console)
    }

    @Test
    fun `ライブラリのパイプとdup`() = timeout(limit) {
        assertEquals("pipe ok\n", lib("pipe").assertOk().console)
    }

    @Test
    fun `ライブラリの子プロセス`() = timeout(limit) {
        val r = lib("spawn").assertOk()
        val lines = r.console.lines().filter { it.isNotEmpty() }
        assertEquals(
            listOf(
                "exited 7",
                "/home directory",
                "child said GREETING=hi",
                "PWD=/home",
                "killed terminate",
                "spawn missing NOENT",
                "spawn ok",
            ),
            lines,
            r.console,
        )
    }

    @Test
    fun `ライブラリのpidとTTY・RAM・時計`() = timeout(limit) {
        assertEquals("self 1 parent 0\n", lib("pid").assertOk().console)
        val tty = lib("tty").assertOk().console
        assertTrue(tty.endsWith("tty ok\n"), tty)
        assertTrue(Regex("size \\d+x\\d+").containsMatchIn(tty), tty)
        assertEquals("memory ok\n", lib("memory").assertOk().console)
        assertEquals("time ok\n", lib("time").assertOk().console)
    }
}
