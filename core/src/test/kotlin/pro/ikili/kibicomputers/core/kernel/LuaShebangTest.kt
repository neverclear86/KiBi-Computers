package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** シバン（`#!/bin/lua`）付きの Lua スクリプトを、exec で直接起動する（#93）。実際の Endive と `lua.wasm` で動かす */
class LuaShebangTest {
    private val limit = Duration.ofSeconds(60)

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    private fun Machine.writeFile(path: String, bytes: ByteArray) = lock.withLockValue {
        val f = vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true, truncate = true))
        vfs.write(f, bytes, 0, bytes.size)
    }

    private fun runScript(script: String, path: String, options: SpawnOptions): Pair<ProcessState, String> =
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            m.lock.withLockValue {
                m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
                m.vfs.createDirectory(VfsPath.parse("/bin"))
                m.vfs.createDirectory(VfsPath.parse("/home"))
            }
            m.writeFile("/bin/lua", resource("lua.wasm"))
            m.writeFile(path, script.toByteArray(Charsets.UTF_8))
            val output = m.lock.withLockValue { ByteStream(64 * 1024).also { m.kernel.objects.add(it) }.id }
            val stdio = StdioSpec(StdioSource.Closed, StdioSource.Object(output, HandleRights.WRITE), StdioSource.Object(output, HandleRights.WRITE))
            val pid = m.exec(path, KernelDrivers.DEFAULT, options.copy(stdio = stdio, fds = SpawnOptions.ROOT_PREOPEN))
            eventually(30_000, "終わらない") {
                h.scheduler.tick()
                Thread.sleep(1)
                m.stateOf(pid).isTerminal
            }
            val text = m.lock.withLockValue {
                val s = m.kernel.objects[output] as ByteStream
                val buf = ByteArray(s.available)
                s.read(buf, 0, buf.size, m.wakeSink)
                String(buf, Charsets.UTF_8)
            }
            m.stateOf(pid) to text
        }

    @Test
    fun `シバン付きのLuaスクリプトを直接実行でき、引数がargに入る`() = timeout(limit) {
        val script = "#!/bin/lua\nprint(arg[0], select('#', ...), ...)\n"
        val (state, out) = runScript(script, "/home/hello.lua", SpawnOptions(args = listOf("hello.lua", "a", "b")))
        assertEquals(ProcessState.EXITED, state, out)
        assertEquals("/home/hello.lua\t2\ta\tb\n", out)
    }

    @Test
    fun `拡張子がなくCRLFで保存したスクリプトも動く`() = timeout(limit) {
        val script = "#!/bin/lua\r\nprint(6 * 7)\r\n"
        val (state, out) = runScript(script, "/home/answer", SpawnOptions())
        assertEquals(ProcessState.EXITED, state, out)
        assertEquals("42\n", out)
    }
}
