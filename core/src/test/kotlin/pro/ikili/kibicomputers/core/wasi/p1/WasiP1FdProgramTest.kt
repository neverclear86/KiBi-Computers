package pro.ikili.kibicomputers.core.wasi.p1

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.io.ReadResult
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.withLockValue
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** wasi-libc でビルドした fd_wasi.c（実際の Endive）を、p1 の層とカーネルの上で動かす（#70） */
class WasiP1FdProgramTest {
    private val program: Program by lazy {
        val bytes = javaClass.getResourceAsStream("/kibicomputers/wasm/fd-wasi.wasm")?.use { it.readBytes() }
        assertNotNull(bytes, "fd-wasi.wasm がリソースにない")
        Program.load(EndiveRuntime(), bytes!!)
    }

    @Test
    fun `wasi-libcのファイル操作がfdの関数を通って動く`() = timeout(Duration.ofSeconds(60)) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val (input, output, dirId, fileId) = m.lock.withLockValue {
                m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
                m.vfs.createDirectory(VfsPath.parse("/data"))
                val f = m.vfs.open(VfsPath.parse("/data/a.txt"), OpenOptions(read = true, write = true, create = true))
                m.vfs.write(f, "abcde".toByteArray(), 0, 5)
                f.position = 0
                val d = m.vfs.open(VfsPath.ROOT, OpenOptions(read = true))
                val input = ByteStream(64).also { m.kernel.objects.add(it) }
                val output = ByteStream(64 * 1024).also { m.kernel.objects.add(it) }
                listOf(input.id, output.id, m.kernel.objects.add(d), m.kernel.objects.add(f))
            }
            val options = SpawnOptions(
                allowUnmetered = true,
                args = listOf("fd-wasi"),
                stdio = StdioSpec(StdioSource.Object(input, HandleRights.READ), StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed),
                fds = mapOf(3 to StdioSource.Object(dirId, HandleRights.ALL), 4 to StdioSource.Object(fileId, HandleRights.ALL)),
            )
            val pid = m.spawn(program, WasiP1Driver(program.module), options)

            // 標準入力が空なので、読むところで待つ
            eventually(20_000, "標準入力で待たない") { m.stateOf(pid) == ProcessState.WAITING_EVENT || m.stateOf(pid).isTerminal }
            m.lock.withLockValue {
                val s = m.kernel.objects[input] as ByteStream
                s.write("hello\n".toByteArray(), 0, 6, m.wakeSink)
                s.closeWrite(m.wakeSink)
            }
            eventually(20_000, "終わらない") { m.stateOf(pid).isTerminal }

            val text = m.lock.withLockValue {
                val s = m.kernel.objects[output] as ByteStream
                val buf = ByteArray(s.available)
                (s.read(buf, 0, buf.size, m.wakeSink) as? ReadResult.Bytes)
                String(buf, Charsets.UTF_8)
            }
            assertEquals("OK\n", text, "プログラムの出力")
            assertEquals(ProcessState.EXITED, m.stateOf(pid))
            // ファイルの中身: "Zbcd" + 0 が 6 バイト + 追記の "Q"
            val content = m.lock.withLockValue {
                val f = m.vfs.open(VfsPath.parse("/data/a.txt"), OpenOptions(read = true))
                val buf = ByteArray(32)
                val n = m.vfs.read(f, buf, 0, buf.size)
                buf.copyOf(n)
            }
            assertEquals("Zbcd" + "\u0000".repeat(6) + "Q", String(content, Charsets.ISO_8859_1))
        }
    }
}
