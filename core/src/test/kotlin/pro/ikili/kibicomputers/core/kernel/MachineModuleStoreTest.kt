package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
import pro.ikili.kibicomputers.core.persist.FileModuleStore
import pro.ikili.kibicomputers.core.persist.ModuleStore
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** exec とモジュールの置き場（#82、#551）: 作るときの種のファイルは、exec の put で中身を確かめ、壊れていれば書き直す */
class MachineModuleStoreTest {
    private val limit = Duration.ofSeconds(120)

    @TempDir
    lateinit var dir: Path

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    @Test
    fun `置き場の壊れた種のファイルは、同じモジュールのexecで書き直す`() = timeout(limit) {
        val bytes = resource("prompt-wasi.wasm")
        val hash = ModuleStore.sha256(bytes)
        val file = dir.resolve(ModuleStore.hex(hash) + ".wasm")
        // 途中で切れた書き込みのような、壊れたファイルを置いてから作る（ファイル名だけが種になる）
        Files.write(file, bytes.copyOf(bytes.size / 2))
        val store = FileModuleStore(dir)
        assertTrue(store.contains(hash), "ファイル名が種になる")

        Harness(execRuntime = EndiveRuntime(), moduleStore = store).use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            m.vfs.createDirectory(VfsPath.parse("/bin"))
            val f = m.vfs.open(VfsPath.parse("/bin/prompt"), OpenOptions(read = true, write = true, create = true, truncate = true))
            m.vfs.write(f, bytes, 0, bytes.size)
            val (input, output) = m.lock.withLockValue {
                ByteStream(4096).also { m.kernel.objects.add(it) }.id to ByteStream(64 * 1024).also { m.kernel.objects.add(it) }.id
            }
            val stdio = StdioSpec(StdioSource.Object(input, HandleRights.READ), StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed)
            val pid = m.exec("/bin/prompt", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("prompt"), stdio = stdio))
            eventually(60_000, "入力を待たない") {
                h.scheduler.tick()
                m.stateOf(pid) == ProcessState.WAITING_EVENT
            }

            // exec の put が種の中身を確かめて書き直している
            assertArrayEquals(bytes, Files.readAllBytes(file))
            assertArrayEquals(bytes, store.get(hash))
            assertTrue(store.contains(hash))

            m.lock.withLockValue { (m.kernel.objects[input] as ByteStream).closeWrite(m.wakeSink) }
            m.stop()
        }
    }
}
