package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.ColdBootReason
import pro.ikili.kibicomputers.core.persist.MachineSnapshot
import pro.ikili.kibicomputers.core.persist.ModuleStore
import pro.ikili.kibicomputers.core.persist.SnapshotFormatException
import pro.ikili.kibicomputers.core.persist.SnapshotInspection
import pro.ikili.kibicomputers.core.persist.SnapshotSection
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 保存データの節を読んで確かめる [SnapshotInspection]（#552）のテスト */
class SnapshotInspectionTest {
    private val limit = Duration.ofSeconds(120)

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    @Test
    fun `入力を待つp1のプロセスの写しを読み、元のハッシュと引数を返す。節が合わなければ例外`() = timeout(limit) {
        val store = MemoryModuleStore()
        Harness(execRuntime = EndiveRuntime(), moduleStore = store).use { h ->
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
            val pid = m.exec("/bin/prompt", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("prompt", "x"), stdio = stdio))
            eventually(60_000, "入力を待たない") {
                h.scheduler.tick()
                m.stateOf(pid) == ProcessState.WAITING_EVENT
            }

            val copy = m.captureCopy(CapturePolicy.SKIP_IF_TRANSIENT)!!
            assertEquals(ColdBootReason.NONE, copy.coldBootReason)
            val snapshot = copy.toSource().build(UUID.randomUUID(), 1, "test")
            val contents = SnapshotInspection.inspect(snapshot, m.config.maxProcesses)
            val p = contents.processes.single { it.pid == pid }
            assertEquals(listOf("prompt", "x"), p.args)
            assertEquals(SnapshotSection.ABI_LAYER_P1, p.abiLayer)
            assertArrayEquals(ModuleStore.sha256(bytes), p.originalHash)
            assertArrayEquals(m.rt(pid).program.hash.bytes(), p.loadedHash)
            assertArrayEquals(p.loadedHash, p.abiLoadedHash)

            // 冷えた起動の写しは読まない
            val cold = MachineSnapshot(snapshot.machineId, 2, "test", emptyList(), listOf(pid), ColdBootReason.INIT_KILLED.code)
            assertThrows<SnapshotFormatException> { SnapshotInspection.inspect(cold, m.config.maxProcesses) }
            // KERNEL 節がない
            val noKernel = MachineSnapshot(
                snapshot.machineId, 3, "test", snapshot.sections.filter { it.kind != SnapshotSection.KERNEL },
            )
            assertThrows<SnapshotFormatException> { SnapshotInspection.inspect(noKernel, m.config.maxProcesses) }
            // PROCESS_ABI 節が KERNEL 節にない pid を指す
            val abi = snapshot.sections.single { it.kind == SnapshotSection.PROCESS_ABI }.payload
            val missing = pid + 1000
            abi[0] = (missing ushr 24).toByte()
            abi[1] = (missing ushr 16).toByte()
            abi[2] = (missing ushr 8).toByte()
            abi[3] = missing.toByte()
            val strayAbi = MachineSnapshot(
                snapshot.machineId, 4, "test",
                snapshot.sections.filter { it.kind != SnapshotSection.PROCESS_ABI } +
                    SnapshotSection(SnapshotSection.PROCESS_ABI, SnapshotSection.PROCESS_ABI_VERSION, abi),
            )
            assertThrows<SnapshotFormatException> { SnapshotInspection.inspect(strayAbi, m.config.maxProcesses) }

            m.lock.withLockValue { (m.kernel.objects[input] as ByteStream).closeWrite(m.wakeSink) }
            m.stop()
        }
    }
}
