package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.io.ByteArrayOutputStream
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 同梱の Lua が、RAM の上限でトラップでなく `not enough memory` のエラーになる（#409）ことを、カーネルを通して確かめる */
class LuaMemoryLimitTest {
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

    /** `lua.wasm` を `/bin/lua` に置き、RAM [ramBytes] のマシンで `lua -e <script>` を動かして、終わるまで待つ */
    private fun runLua(script: String, ramBytes: Long): Result = Harness(execRuntime = EndiveRuntime()).use { h ->
        val host = ConsoleHost()
        val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = ramBytes)
        m.lock.withLockValue {
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            m.vfs.createDirectory(VfsPath.parse("/bin"))
            val bytes = requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/lua.wasm")) { "lua.wasm がない" }.use { it.readBytes() }
            val f = m.vfs.open(VfsPath.parse("/bin/lua"), OpenOptions(read = true, write = true, create = true, truncate = true))
            m.vfs.write(f, bytes, 0, bytes.size)
        }
        val pid = m.exec("/bin/lua", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("lua", "-e", script)))
        eventually(60_000, "終わらない: ${host.text()}") {
            h.scheduler.tick()
            Thread.sleep(1)
            m.stateOf(pid).isTerminal
        }
        val exitCode = m.lock.withLockValue { m.kernel.processes[pid]!!.exitCode }
        val state = m.stateOf(pid)
        eventually(60_000, "RAM が戻らない: ${m.memoryAccount.usedBytes}") {
            h.scheduler.tick()
            m.memoryAccount.usedBytes == 0L
        }
        // コンソールの行は、プロセスが終わるときに書く。書き出しを待つ
        h.scheduler.tick()
        Result(host.text(), state, exitCode, pid)
    }

    @ParameterizedTest
    @ValueSource(longs = [128, 512])
    fun `大きなテーブルを作るとnot enough memoryのエラーになり、pcallで受けられる`(ramKib: Long) = timeout(limit) {
        // エラーを受けた後は、新しい確保をせずに（作ったテーブルを捨てて GC してから）結果を書いて終わる
        val script = """
            local t = {}
            local ok, err = pcall(function() for i = 1, 1e7 do t[i] = ('x'):rep(64) .. i end end)
            t = nil
            collectgarbage()
            io.write('caught ', tostring(not ok), ' ', tostring(err), '\n')
            os.exit(ok and 3 or 0)
        """.trimIndent()
        val r = runLua(script, ramKib * kib)
        assertEquals(ProcessState.EXITED, r.state, r.console)
        assertEquals(0, r.exitCode, r.console)
        assertTrue("caught true not enough memory\n" in r.console, r.console)
    }

    @ParameterizedTest
    @ValueSource(longs = [128, 512])
    fun `受けなければLuaのエラーで終わり、メモリ不足が原因だったと表示する`(ramKib: Long) = timeout(limit) {
        val r = runLua("local t = {} for i = 1, 1e7 do t[i] = ('x'):rep(64) .. i end", ramKib * kib)
        assertEquals(ProcessState.EXITED, r.state, r.console)
        assertEquals(1, r.exitCode, r.console)
        assertTrue("not enough memory" in r.console, r.console)
        assertTrue("kibi: lua (pid ${r.pid}) exited with code 1 (kibi:memory commit was refused: out of memory, RAM limit $ramKib KiB)\n" in r.console, r.console)
    }
}
