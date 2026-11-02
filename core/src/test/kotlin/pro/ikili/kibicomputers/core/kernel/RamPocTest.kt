package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriverFactory
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.endive.writtenChunks
import pro.ikili.kibicomputers.core.runtime.validation.ModuleLimits
import java.io.ByteArrayOutputStream
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 書き換えた 4 KiB で RAM を数える PoC（#370）を、カーネルを通して確かめる */
class RamPocTest {
    private val limit = Duration.ofSeconds(60)
    private val kib = 1024L

    private class ConsoleHost : MachineHost {
        private val out = ByteArrayOutputStream()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            synchronized(out) { out.write(bytes, 0, bytes.size) }
        }

        fun text(): String = synchronized(out) { out.toString(Charsets.UTF_8) }
    }

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    private fun Harness.tickUntil(message: String, condition: () -> Boolean) = eventually(30_000, message) {
        scheduler.tick()
        Thread.sleep(1)
        condition()
    }

    private fun Machine.install(path: String, wasm: String) = lock.withLockValue {
        val bytes = resource(wasm)
        val f = vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true, truncate = true))
        vfs.write(f, bytes, 0, bytes.size)
    }

    private fun Machine.mountRoot() = lock.withLockValue { vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root") }

    /** [wasm] を RAM [ramBytes] のマシンで [args] で動かし、終わるまで待つ。出力・状態・終了コードを返す */
    private fun run(
        wasm: String,
        args: List<String>,
        ramBytes: Long,
        accounting: RamAccounting = RamAccounting.WRITTEN_4K,
        drivers: ProcessDriverFactory = KernelDrivers.RAM_POC,
    ): Triple<String, ProcessState, Int?> = Harness(execRuntime = EndiveRuntime(), ramAccounting = accounting).use { h ->
        val host = ConsoleHost()
        val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = ramBytes)
        m.mountRoot()
        m.install("/prog", wasm)
        val pid = m.exec("/prog", drivers, SpawnOptions(args = args))
        h.tickUntil("終わらない: ${host.text()}") { m.stateOf(pid).isTerminal }
        val process = m.lock.withLockValue { m.kernel.processes[pid]!! }
        val state = m.stateOf(pid)
        h.tickUntil("RAM が戻らない: ${m.memoryAccount.usedBytes}") { m.memoryAccount.usedBytes == 0L }
        Triple(host.text(), state, process.exitCode)
    }

    @Test
    fun `ram_remainingは書いた分だけ減る`() = timeout(limit) {
        val (out, state, code) = run("ram-poc.wasm", listOf("ram-poc"), ramBytes = 1024 * kib)
        assertEquals(ProcessState.EXITED, state, out)
        assertEquals(0, code, out)
        val match = Regex("before=(\\d+) after=(\\d+)").find(out) ?: error("出力がない: $out")
        val before = match.groupValues[1].toLong()
        val after = match.groupValues[2].toLong()
        assertTrue(before in 1..1024 * kib, out)
        // 256 KiB を書いたので、少なくともその分は減る（printf のバッファなどで少し多く減ることがある）
        assertTrue(before - after in 256 * kib..(256 + 16) * kib, out)
    }

    @Test
    fun `上限を超えて書くとプロセスがトラップで止まり、RAMが戻る`() = timeout(limit) {
        val (out, state, _) = run("ram-poc.wasm", listOf("ram-poc", "exhaust"), ramBytes = 1024 * kib)
        assertTrue("before=" in out, out)
        // grow は数えないので malloc は NULL を返さず、書き込みでトラップになる
        assertTrue("malloc returned NULL" !in out, out)
        assertEquals(ProcessState.CRASHED, state, out)
    }

    @Test
    fun `既定のドライバーはPoCのimportを断る`() {
        val program = Program.loadMetered(EndiveRuntime(), resource("ram-poc.wasm"), ModuleLimits.DEFAULT)
        val e = assertThrows<FsException> { KernelDrivers.DEFAULT.create(program) }
        assertEquals(Errno.NOEXEC, e.errno)
        assertTrue("kibi_poc.ram_remaining" in e.message!!, e.message)
    }

    @Test
    fun `包みを入れたLuaは、RAMが足りないとトラップでなくnot enough memoryのエラーで止まる`() = timeout(limit) {
        val script = "local ok, err = pcall(function() local t = {} for i = 1, 1e7 do t[i] = ('x'):rep(64) .. i end end) io.stderr:write(tostring(err)) os.exit(ok and 3 or 1)"
        for (ram in listOf(256 * kib, 1024 * kib)) {
            val (out, state, code) = run("lua-rampoc.wasm", listOf("lua", "-e", script), ramBytes = ram)
            assertEquals(ProcessState.EXITED, state, out)
            assertEquals(1, code, out)
            assertTrue("not enough memory" in out, out)
        }
    }

    @Test
    fun `今の方式ではLuaは128KiBで起動できず、書き換えた量の方式なら128KiBで動く`() = timeout(limit) {
        val declared = run("lua.wasm", listOf("lua", "-e", "print(1 + 1)"), ramBytes = 128 * kib, accounting = RamAccounting.DECLARED_PAGES, drivers = KernelDrivers.DEFAULT)
        assertTrue(declared.second != ProcessState.EXITED || declared.third != 0, declared.first)
        val written = run("lua.wasm", listOf("lua", "-e", "print(1 + 1)"), ramBytes = 128 * kib)
        assertEquals(ProcessState.EXITED, written.second, written.first)
        assertTrue(written.first.contains("2"), written.first)
    }

    @Test
    fun `入力を待っているLuaのREPLの書き換えた量を読める`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime(), ramAccounting = RamAccounting.WRITTEN_4K).use { h ->
            val host = ConsoleHost()
            val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 256 * kib)
            m.mountRoot()
            m.install("/lua", "lua.wasm")
            val pid = m.exec("/lua", KernelDrivers.RAM_POC, SpawnOptions(args = listOf("lua")))
            h.tickUntil("入力を待たない: ${host.text()}") { host.text().endsWith("> ") && m.stateOf(pid) == ProcessState.WAITING_EVENT }
            val memory = m.rt(pid).instances.mapNotNull { it.memory }.single()
            val written = writtenChunks(memory)
            assertTrue(written in 1..64, "書き換え $written")
            // マシンの RAM には、書き換えた分とテーブル（#361）の分が入る
            assertTrue(m.memoryAccount.usedBytes >= written * 4096L, "RAM ${m.memoryAccount.usedBytes}")
            m.stop()
            h.tickUntil("止まらない") { m.stateOf(pid).isTerminal }
        }
    }
}
