package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.io.ByteArrayOutputStream
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 独自拡張 `kibi:computer/memory`（#408）の p1 の import を、WASM のプログラムから Endive とカーネルを通して確かめる */
class KibiMemoryProgramTest {
    private val limit = Duration.ofSeconds(90)
    private val kib = 1024L
    private val chunk = 4096L

    private class ConsoleHost : MachineHost {
        private val out = ByteArrayOutputStream()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            synchronized(out) { out.write(bytes, 0, bytes.size) }
        }

        fun text(): String = synchronized(out) { out.toString(Charsets.UTF_8).replace("\r\n", "\n") }
    }

    private class Result(val console: String, val state: ProcessState, val exitCode: Int?, val pid: Int, val kind: ProcessKind)

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    private fun Harness.tickUntil(message: String, condition: () -> Boolean) = eventually(60_000, message) {
        scheduler.tick()
        Thread.sleep(1)
        condition()
    }

    /** `kibi-memory-wasi.wasm` を RAM [ramBytes] のマシンで [args] で動かして、終わるまで待つ */
    private fun run(vararg args: String, ramBytes: Long = 512 * kib): Result = Harness(execRuntime = EndiveRuntime()).use { h ->
        val host = ConsoleHost()
        val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = ramBytes)
        m.lock.withLockValue {
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            m.vfs.createDirectory(VfsPath.parse("/bin"))
            val bytes = resource("kibi-memory-wasi.wasm")
            val f = m.vfs.open(VfsPath.parse("/bin/km"), OpenOptions(read = true, write = true, create = true, truncate = true))
            m.vfs.write(f, bytes, 0, bytes.size)
        }
        val pid = m.exec("/bin/km", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("km") + args))
        val kind = m.rt(pid).driver.kind
        h.tickUntil("終わらない: ${host.text()}") { m.stateOf(pid).isTerminal }
        val exitCode = m.lock.withLockValue { m.kernel.processes[pid]!!.exitCode }
        val state = m.stateOf(pid)
        h.tickUntil("RAM が戻らない: ${m.memoryAccount.usedBytes}") { m.memoryAccount.usedBytes == 0L }
        h.scheduler.tick()
        Result(host.text(), state, exitCode, pid, kind)
    }

    private fun Result.assertExitedNormally() {
        assertEquals(ProcessState.EXITED, state, console)
        assertEquals(0, exitCode, console)
        assertFalse("kibi:" in console, console)
    }

    /** [console] の [prefix] で始まる行の、空白で区切った数 */
    private fun Result.numbers(prefix: String): List<Long> {
        val line = console.lines().firstOrNull { it.startsWith(prefix) } ?: throw AssertionError("$prefix の行がない: $console")
        return line.split(' ').mapNotNull { it.toLongOrNull() }
    }

    @Test
    fun `remainingは書いた区画の分だけ減る`() = timeout(limit) {
        val r = run("remaining", "64")
        r.assertExitedNormally()
        assertEquals(ProcessKind.WAIT_POINT, r.kind, "kibi の import は待機点への変換を妨げない")
        val decreased = r.numbers("remaining decreased").single()
        assertEquals(0L, decreased % chunk, r.console)
        // 64 KiB にかかる区画（17 区画まで）から、malloc が先に書いた（grow で数えた）端の区画を除いた分
        assertTrue(decreased in (64 * kib - 2 * chunk)..(64 * kib + chunk), r.console)
    }

    @Test
    fun `commitは範囲を今のうちに数え、その後の書き込みではremainingが減らない`() = timeout(limit) {
        val r = run("commit", "96")
        r.assertExitedNormally()
        val (ok, decreased) = r.numbers("commit")
        assertEquals(1L, ok, r.console)
        assertEquals(0L, decreased % chunk, r.console)
        assertTrue(decreased in (96 * kib - 2 * chunk)..(96 * kib + chunk), r.console)
        assertEquals(listOf(0L), r.numbers("write decreased"), r.console)
    }

    @Test
    fun `commitは数えた後にkeepが残らなければ何も数えない`() = timeout(limit) {
        val r = run("commit-keep")
        r.assertExitedNormally()
        assertEquals(listOf(0L, 0L, 0L), r.numbers("keep"), r.console)
        val (fits, decreased) = r.numbers("fits")
        assertEquals(1L, fits, r.console)
        assertTrue(decreased in listOf(0L, chunk, 2 * chunk), r.console)
    }

    @Test
    fun `commitは線形メモリの外なら0、長さ0は大きさまで1、数え済みの範囲はkeepが大きくても1`() = timeout(limit) {
        val r = run("commit-oob")
        r.assertExitedNormally()
        assertEquals(listOf(0L, 0L, 1L, 0L, 1L, 0L), r.numbers("oob"), r.console)
    }

    @Test
    fun `commitをRAMの上限で断られた後に0以外で終わると、メモリ不足が原因だったと表示する`() = timeout(limit) {
        val r = run("refuse", "1", ramBytes = 256 * kib)
        assertEquals(ProcessState.EXITED, r.state, r.console)
        assertEquals(1, r.exitCode, r.console)
        assertTrue("refused 0\n" in r.console, r.console)
        assertTrue("kibi: km (pid ${r.pid}) exited with code 1 (kibi:memory commit was refused: out of memory, RAM limit 256 KiB)\n" in r.console, r.console)
    }

    @Test
    fun `commitをRAMの上限で断られた後にトラップで落ちると、理由にメモリ不足を添える`() = timeout(limit) {
        val r = run("refuse-trap", ramBytes = 256 * kib)
        assertEquals(ProcessState.CRASHED, r.state, r.console)
        assertTrue("kibi: km (pid ${r.pid}) crashed: unreachable executed (kibi:memory commit was refused: out of memory, RAM limit 256 KiB)\n" in r.console, r.console)
    }

    @Test
    fun `commitを断られても正常に終われば、何も表示しない`() = timeout(limit) {
        val r = run("refuse", "0", ramBytes = 256 * kib)
        r.assertExitedNormally()
        assertTrue("refused 0\n" in r.console, r.console)
    }

    @Test
    fun `growとcommitの両方を断られた後に0以外で終わると、両方を表示する`() = timeout(limit) {
        val r = run("refuse-grow", "3", ramBytes = 256 * kib)
        assertEquals(ProcessState.EXITED, r.state, r.console)
        assertEquals(3, r.exitCode, r.console)
        assertTrue("kibi: km (pid ${r.pid}) exited with code 3 (memory.grow and kibi:memory commit were refused: out of memory, RAM limit 256 KiB)\n" in r.console, r.console)
    }
}
