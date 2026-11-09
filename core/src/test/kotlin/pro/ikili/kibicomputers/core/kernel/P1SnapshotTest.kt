package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.ColdBootReason
import pro.ikili.kibicomputers.core.persist.ModuleStore
import pro.ikili.kibicomputers.core.persist.SnapshotSection
import pro.ikili.kibicomputers.core.persist.snapshot.P1SectionCodec
import pro.ikili.kibicomputers.core.persist.snapshot.SnapshotReader
import pro.ikili.kibicomputers.core.runtime.Instrumentation
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.validation.ModuleLimits
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1
import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * p1 のコマンドの待機点保存の中身（#82）: `prompt-wasi.wasm` が入力を待っているところで写しを取り、`PROCESS_ABI` 節の p1 の層を読む。
 * 置き場には元のバイト列が入り、そこから同じ読み込み方でやり直すと後のハッシュが合う（挿入・変換が決定的）
 */
class P1SnapshotTest {
    private val limit = Duration.ofSeconds(120)

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    @Test
    fun `入力を待つp1のプロセスの層を写し、置き場のバイト列から同じハッシュで読み直せる`() = timeout(limit) {
        val store = MemoryModuleStore()
        val runtime = EndiveRuntime()
        Harness(execRuntime = runtime, moduleStore = store).use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            val bytes = resource("prompt-wasi.wasm")
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
            // 読み込みのスレッドで置き場に書いてある
            val original = ModuleStore.sha256(bytes)
            assertTrue(store.contains(original))
            assertArrayEquals(bytes, store.get(original))

            val copy = m.captureCopy(CapturePolicy.SKIP_IF_TRANSIENT)!!
            assertEquals(ColdBootReason.NONE, copy.coldBootReason)
            assertTrue(copy.killedPids.isEmpty())
            val snapshot = copy.toSource().build(UUID.randomUUID(), 1, "test")
            val abi = snapshot.sections.single { it.kind == SnapshotSection.PROCESS_ABI }
            val r = SnapshotReader(abi.payload)
            assertEquals(pid, r.i32())
            assertEquals(SnapshotSection.ABI_LAYER_P1, r.u16())
            assertEquals(SnapshotSection.P1_LAYER_VERSION, r.u16())
            val decoded = P1SectionCodec.read(r.raw(abi.payload.size - 8))

            val rt = m.rt(pid)
            assertArrayEquals(original, decoded.moduleSource.originalHash)
            assertEquals(rt.program.hash, decoded.loadedHash)
            assertEquals(rt.program.moduleSource, decoded.moduleSource)
            assertEquals(Instrumentation.VERSION, decoded.moduleSource.instrumentationVersion)
            assertEquals(P1SectionCodec.ENTRY_REWIND, decoded.entry)
            assertTrue(decoded.asyncStack.isNotEmpty())
            val instance = decoded.instance!!
            assertTrue(instance.memoryPages > 0)
            assertTrue(instance.writtenChunkCount > 0)

            // 置き場のバイト列から同じ読み込み方でやり直すと、後のハッシュが合う（#85 が照合に使う）
            val again = Program.loadMetered(runtime, store.get(original)!!, ModuleLimits.DEFAULT, decoded.moduleSource.waitImports.toSet())
            assertEquals(decoded.loadedHash, again.hash)
            assertEquals(decoded.moduleSource, again.moduleSource)
            assertEquals(WasiP1.WAIT_IMPORTS.sorted(), decoded.moduleSource.waitImports)

            m.lock.withLockValue { (m.kernel.objects[input] as ByteStream).closeWrite(m.wakeSink) }
            m.stop()
        }
    }
}
