package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 同じ実行ファイルの読み込み結果を、マシンをまたいで共有する（#37）。実際の Endive と `lua.wasm` で動かす */
class ProgramSharingTest {
    private val lua: ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/lua.wasm")).use { it.readBytes() }

    private fun Harness.luaMachine(): Machine = machine(fuelPerTick = 10_000_000).also { m ->
        m.lock.withLockValue {
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            m.vfs.createDirectory(VfsPath.parse("/bin"))
            val f = m.vfs.open(VfsPath.parse("/bin/lua"), OpenOptions(read = true, write = true, create = true))
            m.vfs.write(f, lua, 0, lua.size)
        }
    }

    @Test
    fun `別々のマシンで同じWASMを動かしても、読み込みとコンパイルは1回だけ`() = timeout(Duration.ofSeconds(60)) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val machines = (0 until 4).map { h.luaMachine() }
            val options = SpawnOptions(args = listOf("lua", "-e", "print(6 * 7)"), stdio = StdioSpec(StdioSource.Closed, StdioSource.Closed, StdioSource.Closed))
            val pids = machines.map { it.exec("/bin/lua", KernelDrivers.DEFAULT, options) }
            val programs = machines.zip(pids).map { (m, pid) -> m.rt(pid).program }
            assertTrue(programs.all { it === programs[0] })
            eventually(30_000, "終わらない") {
                h.scheduler.tick()
                Thread.sleep(1)
                machines.zip(pids).all { (m, pid) -> m.stateOf(pid).isTerminal }
            }
            machines.zip(pids).forEach { (m, pid) -> assertEquals(ProcessState.EXITED, m.stateOf(pid)) }
            assertEquals(1, h.scheduler.programs.loads.get())
            // 後から起動したものも同じものを使う
            val m = h.luaMachine()
            val pid = m.exec("/bin/lua", KernelDrivers.DEFAULT, options)
            assertSame(programs[0], m.rt(pid).program)
            assertEquals(1, h.scheduler.programs.loads.get())
        }
    }
}
