package pro.ikili.kibicomputers.core.wasi.p2

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
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * アダプターで包んだ p1 のプログラムでも、起動処理（`kibi_cwd.c`）が p2 の `get-environment` の `PWD` から
 * wasi-libc の cwd をカーネルの cwd に合わせる（#760。p1 の [pro.ikili.kibicomputers.core.wasi.p1.WasiP1CwdProgramTest] と同じ期待）
 */
class WasiP2CwdProgramTest {
    private val limit = Duration.ofSeconds(120)

    private val cwdProgram: Program by lazy { Program.component(P2Testing.load(P2Testing.wrap("cwd-wasi"))) }

    private fun driver(): WasiP2Driver = WasiP2Driver.create(cwdProgram.component!!)

    private fun Machine.setUpRoot() = run {
        vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        vfs.createDirectory(VfsPath.parse("/home"))
        vfs.createDirectory(VfsPath.parse("/home/user"))
    }

    private fun Machine.readFile(path: String): String? = run {
        val f = try {
            vfs.open(VfsPath.parse(path), OpenOptions(read = true))
        } catch (_: FsException) {
            return@run null
        }
        val buf = ByteArray(64)
        String(buf, 0, vfs.read(f, buf, 0, buf.size), Charsets.UTF_8)
    }

    @Test
    fun `包んだp1も起動時にPWDへchdirし、相対パスがカーネルのcwdから辿れる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 5_000_000)
            m.setUpRoot()
            val input = P2Testing.outputStream(m)
            val output = P2Testing.outputStream(m)
            val options = SpawnOptions(
                args = listOf("cwd-wasi", "follow", "/home/user"),
                // 呼び出し元が渡した PWD は、起動時のカーネルの cwd に置き換わる
                env = linkedMapOf("PWD" to "/wrong"),
                cwd = "/home/user",
                stdio = StdioSpec(StdioSource.Object(input, HandleRights.READ), StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed),
                fds = SpawnOptions.ROOT_PREOPEN,
            )
            val pid = m.spawn(cwdProgram, driver(), options, 0)
            val seen = StringBuilder()
            eventually(60_000, "WAIT まで進まない") {
                h.scheduler.tick()
                seen.append(P2Testing.drain(m, output))
                seen.contains("WAIT\n") || m.stateOf(pid).isTerminal
            }
            assertEquals("WAIT\n", seen.toString(), "プログラムの出力")

            // プログラムの chdir("sub") は、カーネルの cwd に反映しない
            assertEquals("/home/user", m.lock.withLockValue { m.kernel.processes[pid]!!.cwd.toString() })

            m.lock.withLockValue {
                (m.kernel.objects[input] as ByteStream).write("go\n".toByteArray(), 0, 3, m.wakeSink)
                Unit
            }
            eventually(60_000, "終わらない") {
                h.scheduler.tick()
                seen.append(P2Testing.drain(m, output))
                m.stateOf(pid).isTerminal
            }
            seen.append(P2Testing.drain(m, output))
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
    fun `包んだp1でもカーネルのcwdが消えていると、ルートのまま動く`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 5_000_000)
            m.setUpRoot()
            val output = P2Testing.outputStream(m)
            val options = SpawnOptions(
                args = listOf("cwd-wasi", "fallback", "/home/user"),
                cwd = "/home/user",
                stdio = StdioSpec(StdioSource.Closed, StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed),
                fds = SpawnOptions.ROOT_PREOPEN,
            )
            // 起動して、最初の tick の前に cwd のディレクトリを消す（PWD は /home/user のまま見え、chdir が失敗する）
            val pid = m.spawn(cwdProgram, driver(), options, 0)
            m.vfs.removeDirectory(VfsPath.parse("/home/user"))
            val out = StringBuilder()
            eventually(60_000, "終わらない") {
                h.scheduler.tick()
                out.append(P2Testing.drain(m, output))
                m.stateOf(pid).isTerminal
            }
            out.append(P2Testing.drain(m, output))
            assertEquals("OK\n", out.toString(), "プログラムの出力")
            assertEquals(ProcessState.EXITED, m.stateOf(pid))
            assertEquals("root", m.readFile("/root.txt"))
        }
    }
}
