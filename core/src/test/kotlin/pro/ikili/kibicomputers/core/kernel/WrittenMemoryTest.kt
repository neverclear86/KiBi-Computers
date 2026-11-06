package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.endive.romChunks
import pro.ikili.kibicomputers.core.runtime.endive.writtenChunks
import java.io.ByteArrayOutputStream
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** RAM を書き換えた 4 KiB の区画で数える（#407）ことを、Endive とカーネルを通して確かめる */
class WrittenMemoryTest {
    private val limit = Duration.ofSeconds(90)
    private val kib = 1024L

    private class ConsoleHost : MachineHost {
        private val out = ByteArrayOutputStream()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            synchronized(out) { out.write(bytes, 0, bytes.size) }
        }

        fun text(): String = synchronized(out) { out.toString(Charsets.UTF_8).replace("\r\n", "\n") }
    }

    private class Result(val console: String, val state: ProcessState, val exitCode: Int?, val pid: Int)

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    private fun Harness.tickUntil(message: String, condition: () -> Boolean) = eventually(60_000, message) {
        scheduler.tick()
        Thread.sleep(1)
        condition()
    }

    private fun Machine.install(path: String, wasm: String) = run {
        val bytes = resource(wasm)
        val f = vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true, truncate = true))
        vfs.write(f, bytes, 0, bytes.size)
    }

    /** [wasm] を `/bin/<name>` に置き、RAM [ramBytes] のマシンで [args] で動かして、終わるまで待つ */
    private fun run(wasm: String, args: List<String>, ramBytes: Long): Result = Harness(execRuntime = EndiveRuntime()).use { h ->
        val host = ConsoleHost()
        val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = ramBytes)
        run {
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            m.vfs.createDirectory(VfsPath.parse("/bin"))
        }
        val path = "/bin/${args[0]}"
        m.install(path, wasm)
        val pid = m.exec(path, KernelDrivers.DEFAULT, SpawnOptions(args = args))
        h.tickUntil("終わらない: ${host.text()}") { m.stateOf(pid).isTerminal }
        val exitCode = m.lock.withLockValue { m.kernel.processes[pid]!!.exitCode }
        val state = m.stateOf(pid)
        h.tickUntil("RAM が戻らない: ${m.memoryAccount.usedBytes}") { m.memoryAccount.usedBytes == 0L }
        // コンソールの行は、プロセスが終わるときに書く。書き出しを待つ
        h.scheduler.tick()
        Result(host.text(), state, exitCode, pid)
    }

    @Test
    fun `RAMの上限を超えて書くとトラップになり、メモリ不足と表示する`() = timeout(limit) {
        val r = run("oom-wasi.wasm", listOf("oom", "write", "1024"), ramBytes = 512 * kib)
        assertEquals(ProcessState.CRASHED, r.state, r.console)
        // grow は数えないので malloc は NULL を返さず、書いたところで止まる
        assertFalse("malloc returned NULL" in r.console, r.console)
        assertTrue("kibi: oom (pid ${r.pid}) crashed: out of memory (RAM limit 512 KiB)\n" in r.console, r.console)
    }

    @Test
    fun `RAMに収まるだけ書けば、宣言したメモリがRAMより大きくても動く`() = timeout(limit) {
        val r = run("oom-wasi.wasm", listOf("oom", "write", "128"), ramBytes = 512 * kib)
        assertEquals(ProcessState.EXITED, r.state, r.console)
        assertEquals(0, r.exitCode, r.console)
        assertTrue("wrote 1" in r.console, r.console)
    }

    @Test
    fun `growをRAMの上限で断られた後に0以外で終わると、メモリ不足が原因だったと表示する`() = timeout(limit) {
        val r = run("oom-wasi.wasm", listOf("oom", "grow", "1"), ramBytes = 256 * kib)
        assertEquals(ProcessState.EXITED, r.state, r.console)
        assertEquals(1, r.exitCode, r.console)
        assertTrue("growing\n" in r.console, r.console)
        assertTrue("kibi: oom (pid ${r.pid}) exited with code 1 (memory.grow was refused: out of memory, RAM limit 256 KiB)\n" in r.console, r.console)
    }

    @Test
    fun `growをRAMの上限で断られた後にトラップで落ちると、理由にメモリ不足を添える`() = timeout(limit) {
        val r = run("oom-wasi.wasm", listOf("oom", "grow-trap"), ramBytes = 256 * kib)
        assertEquals(ProcessState.CRASHED, r.state, r.console)
        assertTrue("kibi: oom (pid ${r.pid}) crashed: unreachable executed (memory.grow was refused: out of memory, RAM limit 256 KiB)\n" in r.console, r.console)
    }

    @Test
    fun `growを断られても正常に終われば、何も表示しない`() = timeout(limit) {
        val r = run("oom-wasi.wasm", listOf("oom", "grow", "0"), ramBytes = 256 * kib)
        assertEquals(ProcessState.EXITED, r.state, r.console)
        assertEquals(0, r.exitCode, r.console)
        assertFalse("kibi:" in r.console, r.console)
    }

    @Test
    fun `Luaは128KiBのRAMで動く`() = timeout(limit) {
        val r = run("lua.wasm", listOf("lua", "-e", "print(1 + 1)"), ramBytes = 128 * kib)
        assertEquals(ProcessState.EXITED, r.state, r.console)
        assertEquals(0, r.exitCode, r.console)
        assertTrue("2\n" in r.console, r.console)
    }

    @Test
    fun `Luaはアドレス空間に入らない確保をnot enough memoryで扱って続けられ、何も表示しない`() = timeout(limit) {
        // 128 MiB はアドレス空間の上限（64 MiB）に入らないので、grow が -1 になり、realloc が NULL を返す
        val script = "local ok, err = pcall(string.rep, 'x', 128 * 1024 * 1024) print(ok, err) print(#string.rep('y', 1024 * 1024))"
        val r = run("lua.wasm", listOf("lua", "-e", script), ramBytes = 8 * 1024 * kib)
        assertEquals(ProcessState.EXITED, r.state, r.console)
        assertTrue("false\tnot enough memory\n" in r.console, r.console)
        assertTrue("1048576\n" in r.console, r.console)
        assertFalse("kibi:" in r.console, r.console)
    }

    @Test
    fun `LuaがRAMを超えて確保すると、not enough memoryで終わり、メモリ不足が原因だったと表示する`() = timeout(limit) {
        // アロケーターが書く前に kibi:computer/memory の commit で数えさせ、断られたら NULL を返す（#409）。トラップにならない
        val r = run("lua.wasm", listOf("lua", "-e", "print(#string.rep('x', 16 * 1024 * 1024))"), ramBytes = 8 * 1024 * kib)
        assertEquals(ProcessState.EXITED, r.state, r.console)
        assertEquals(1, r.exitCode, r.console)
        assertTrue("not enough memory" in r.console, r.console)
        assertTrue("kibi: lua (pid ${r.pid}) exited with code 1 (kibi:memory commit was refused: out of memory, RAM limit 8 MiB)\n" in r.console, r.console)
    }

    @Test
    fun `入力を待っているLuaのREPLは、書き換えた区画だけを数え、データセグメントはROMとして数えない`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val host = ConsoleHost()
            val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 256 * kib)
            run { m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root") }
            m.install("/lua", "lua.wasm")
            val pid = m.exec("/lua", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("lua")))
            h.tickUntil("入力を待たない: ${host.text()}") { host.text().endsWith("> ") && m.stateOf(pid) == ProcessState.WAITING_EVENT }
            val memory = m.rt(pid).instances.mapNotNull { it.memory }.single()
            val written = writtenChunks(memory)
            val rom = romChunks(memory)
            // #370 の PoC では 22 区画（88 KiB）。ROM（データ約 28 KiB）は 7〜8 区画
            assertTrue(written in 1..48, "書き換え $written 区画")
            assertTrue(rom in 1..16, "ROM $rom 区画")
            assertTrue(memory.pages * 16 > written + rom, "宣言したページ数より少ない")
            // マシンの RAM には、書き換えた区画とテーブル（#361）の分が入る
            assertTrue(m.memoryAccount.usedBytes >= written * 4096L, "RAM ${m.memoryAccount.usedBytes}")
            m.stop()
            h.tickUntil("止まらない") { m.stateOf(pid).isTerminal }
        }
    }
}
