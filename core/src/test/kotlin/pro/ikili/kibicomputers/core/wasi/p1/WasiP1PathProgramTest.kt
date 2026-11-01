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

/** wasi-libc でビルドした path_wasi.c（実際の Endive）を、preopen の `/` を渡して動かす（#71） */
class WasiP1PathProgramTest {
    private val program: Program by lazy {
        val bytes = javaClass.getResourceAsStream("/kibicomputers/wasm/path-wasi.wasm")?.use { it.readBytes() }
        assertNotNull(bytes, "path-wasi.wasm がリソースにない")
        Program.load(EndiveRuntime(), bytes!!)
    }

    @Test
    fun `wasi-libcのパスの操作がpathの関数を通って動く`() = timeout(Duration.ofSeconds(60)) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val output = m.lock.withLockValue {
                m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
                m.vfs.createDirectory(VfsPath.parse("/data"))
                val f = m.vfs.open(VfsPath.parse("/data/a.txt"), OpenOptions(read = true, write = true, create = true))
                m.vfs.write(f, "abcde".toByteArray(), 0, 5)
                m.vfs.mount(VfsPath.parse("/ro"), TestMemoryFileSystem(), readOnly = true, source = "test:ro")
                ByteStream(64 * 1024).also { m.kernel.objects.add(it) }.id
            }
            val options = SpawnOptions(
                allowUnmetered = true,
                args = listOf("path-wasi"),
                stdio = StdioSpec(StdioSource.Closed, StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed),
                fds = SpawnOptions.ROOT_PREOPEN,
            )
            val pid = m.spawn(program, WasiP1Driver(program.module), options)
            eventually(20_000, "終わらない") { m.stateOf(pid).isTerminal }

            val text = m.lock.withLockValue {
                val s = m.kernel.objects[output] as ByteStream
                val buf = ByteArray(s.available)
                (s.read(buf, 0, buf.size, m.wakeSink) as? ReadResult.Bytes)
                String(buf, Charsets.UTF_8)
            }
            assertEquals("OK\n", text, "プログラムの出力")
            assertEquals(ProcessState.EXITED, m.stateOf(pid))
            val content = m.lock.withLockValue {
                val f = m.vfs.open(VfsPath.parse("/data/b.txt"), OpenOptions(read = true))
                val buf = ByteArray(32)
                String(buf, 0, m.vfs.read(f, buf, 0, buf.size), Charsets.UTF_8)
            }
            assertEquals("xyz", content)
        }
    }
}
