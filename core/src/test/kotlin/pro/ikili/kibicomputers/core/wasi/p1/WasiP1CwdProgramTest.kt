package pro.ikili.kibicomputers.core.wasi.p1

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.withLockValue
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * カーネルの cwd を p1 の `PWD` で渡し、プログラムの起動処理（`kibi_cwd.c`）が wasi-libc の cwd を合わせる（#332、ADR 0332）。
 * wasi-libc でビルドした cwd_wasi.c と lua.wasm を、実際の Endive で動かす
 */
class WasiP1CwdProgramTest {
    private val limit = Duration.ofSeconds(60)

    private fun load(name: String): Program {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }
        return Program.load(EndiveRuntime(), bytes)
    }

    private val cwdProgram: Program by lazy { load("cwd-wasi.wasm") }

    private fun Machine.setUpRoot() = lock.withLockValue {
        vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        vfs.createDirectory(VfsPath.parse("/home"))
        vfs.createDirectory(VfsPath.parse("/home/user"))
    }

    private fun Machine.newStream(capacity: Int): Int = lock.withLockValue { ByteStream(capacity).also { kernel.objects.add(it) }.id }

    private fun Machine.drain(id: Int): String = lock.withLockValue {
        val s = kernel.objects[id] as ByteStream
        val buf = ByteArray(s.available)
        s.read(buf, 0, buf.size, wakeSink)
        String(buf, Charsets.UTF_8)
    }

    private fun Machine.readFile(path: String): String? = lock.withLockValue {
        val f = try {
            vfs.open(VfsPath.parse(path), OpenOptions(read = true))
        } catch (_: FsException) {
            return@withLockValue null
        }
        val buf = ByteArray(64)
        String(buf, 0, vfs.read(f, buf, 0, buf.size), Charsets.UTF_8)
    }

    @Test
    fun `起動時にPWDへchdirし、相対パスがカーネルのcwdから辿れる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            m.setUpRoot()
            val input = m.newStream(64)
            val output = m.newStream(64 * 1024)
            val options = SpawnOptions(
                allowUnmetered = true,
                args = listOf("cwd-wasi", "follow", "/home/user"),
                // 呼び出し元が渡した PWD は、カーネルの cwd に置き換わる
                env = linkedMapOf("PWD" to "/wrong"),
                cwd = "/home/user",
                stdio = StdioSpec(StdioSource.Object(input, HandleRights.READ), StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed),
                fds = SpawnOptions.ROOT_PREOPEN,
            )
            val pid = m.spawn(cwdProgram, WasiP1Driver(cwdProgram.module), options)
            val seen = StringBuilder()
            eventually(20_000, "WAIT まで進まない") {
                h.scheduler.tick()
                seen.append(m.drain(output))
                seen.contains("WAIT") || m.stateOf(pid).isTerminal
            }
            assertEquals("WAIT\n", seen.toString(), "プログラムの出力")

            // プログラムの chdir("sub") は、カーネルの cwd に反映しない
            val kernelCwd = m.lock.withLockValue { m.kernel.processes[pid]!!.cwd.toString() }
            assertEquals("/home/user", kernelCwd)

            m.lock.withLockValue {
                (m.kernel.objects[input] as ByteStream).write("go\n".toByteArray(), 0, 3, m.wakeSink)
                Unit
            }
            eventually(20_000, "終わらない") {
                h.scheduler.tick()
                seen.append(m.drain(output))
                m.stateOf(pid).isTerminal
            }
            seen.append(m.drain(output))
            assertEquals("WAIT\nOK\n", seen.toString(), "プログラムの出力")
            assertEquals(ProcessState.EXITED, m.stateOf(pid))
            assertEquals("hi", m.readFile("/home/user/rel.txt"))
            assertEquals("deep", m.readFile("/home/user/sub/in_sub.txt"))
            assertEquals("up", m.readFile("/home/user/up.txt"))
            // 環境変数そのもの（保存される Process.env）は変えない
            assertEquals(mapOf("PWD" to "/wrong"), m.lock.withLockValue { m.kernel.processes[pid]!!.env.toMap() })
        }
    }

    @Test
    fun `カーネルのcwdが消えていると、ルートのまま動く`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            m.setUpRoot()
            val output = m.newStream(64 * 1024)
            val options = SpawnOptions(
                allowUnmetered = true,
                args = listOf("cwd-wasi", "fallback", "/home/user"),
                cwd = "/home/user",
                stdio = StdioSpec(StdioSource.Closed, StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed),
                fds = SpawnOptions.ROOT_PREOPEN,
            )
            // 起動して、プログラムが環境変数を読む前に cwd のディレクトリを消す（ロックを持っている間、プログラムは読めない）
            val pid = m.lock.withLockValue {
                m.spawn(cwdProgram, WasiP1Driver(cwdProgram.module), options).also {
                    m.vfs.removeDirectory(VfsPath.parse("/home/user"))
                }
            }
            eventually(20_000, "終わらない") {
                h.scheduler.tick()
                m.stateOf(pid).isTerminal
            }
            assertEquals("OK\n", m.drain(output), "プログラムの出力")
            assertEquals(ProcessState.EXITED, m.stateOf(pid))
            assertEquals("root", m.readFile("/root.txt"))
        }
    }

    @Test
    fun `Luaのio_openもカーネルのcwdから辿る`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            m.setUpRoot()
            val output = m.newStream(64 * 1024)
            val lua = load("lua.wasm")
            val options = SpawnOptions(
                allowUnmetered = true,
                args = listOf("lua", "-e", "local f = assert(io.open('x.txt', 'w')) f:write('lua') f:close()"),
                cwd = "/home/user",
                stdio = StdioSpec(StdioSource.Closed, StdioSource.Object(output, HandleRights.WRITE), StdioSource.Object(output, HandleRights.WRITE)),
                fds = SpawnOptions.ROOT_PREOPEN,
            )
            val pid = m.spawn(lua, WasiP1Driver(lua.module), options)
            eventually(30_000, "終わらない") {
                h.scheduler.tick()
                m.stateOf(pid).isTerminal
            }
            val out = m.drain(output)
            assertEquals(ProcessState.EXITED, m.stateOf(pid), out)
            assertEquals("lua", m.readFile("/home/user/x.txt"))
        }
    }
}
