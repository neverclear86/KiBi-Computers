package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
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
import pro.ikili.kibicomputers.core.persist.DeltaBase
import pro.ikili.kibicomputers.core.persist.MachineSnapshot
import pro.ikili.kibicomputers.core.persist.SnapshotFormatException
import pro.ikili.kibicomputers.core.persist.SnapshotInspection
import pro.ikili.kibicomputers.core.persist.SnapshotSection
import pro.ikili.kibicomputers.core.persist.StoredSnapshot
import pro.ikili.kibicomputers.core.persist.snapshot.P1SectionCodec
import pro.ikili.kibicomputers.core.persist.snapshot.SnapshotReader
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Duration
import java.util.UUID
import java.util.zip.CRC32C
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 本物の p1 のコマンド（`prompt-wasi.wasm`）で、全体 → 差分の世代を取り、基準と合わせて確かめる（#83、ADR 0083）。
 * 全体保存で区画を読み取り専用に戻した後も、コマンドは同じように動き続ける
 */
class P1DeltaSnapshotTest {
    private val limit = Duration.ofSeconds(120)

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    private fun bytesOf(snapshot: MachineSnapshot): ByteArray =
        ByteArrayOutputStream().also { out -> DataOutputStream(out).use { snapshot.writeTo(it) } }.toByteArray()

    private fun stored(bytes: ByteArray): StoredSnapshot = StoredSnapshot(
        MachineSnapshot.readFrom(DataInputStream(bytes.inputStream())),
        bytes.size.toLong(),
        CRC32C().also { it.update(bytes) }.value.toInt(),
    )

    @Test
    fun `全体を書けた後の差分は変わった区画だけで、基準と合わせて確かめられ、コマンドは動き続ける`() = timeout(limit) {
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
            val out = StringBuilder()
            fun drain(): String = m.lock.withLockValue {
                val s = m.kernel.objects[output] as ByteStream
                val buf = ByteArray(s.available)
                s.read(buf, 0, buf.size, m.wakeSink)
                out.append(String(buf, Charsets.UTF_8))
                out.toString()
            }
            fun send(text: String) = m.lock.withLockValue {
                val b = text.toByteArray()
                (m.kernel.objects[input] as ByteStream).write(b, 0, b.size, m.wakeSink)
                Unit
            }
            val stdio = StdioSpec(StdioSource.Object(input, HandleRights.READ), StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed)
            val pid = m.exec("/bin/prompt", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("prompt"), stdio = stdio))
            var prompts = 0
            fun awaitPrompt() {
                prompts++
                eventually(60_000, "プロンプトで待たない: ${drain()}") {
                    h.scheduler.tick()
                    drain().split("> ").size - 1 >= prompts && m.stateOf(pid) == ProcessState.WAITING_EVENT
                }
            }
            awaitPrompt()

            val id = UUID.randomUUID()
            // 全体（区画を読み取り専用に戻す）
            val fullSource = m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT)!!
            val fullBytes = bytesOf(fullSource.build(id, 1, "test"))
            fullSource.committed(id, 1)
            val base = stored(fullBytes)
            assertEquals(null, base.snapshot.deltaBase)

            // 1 行入力して、また待つところまで進める（読み取り専用に戻した後も WASM が壊れない）
            send("hello\n")
            awaitPrompt()
            assertTrue(drain().contains("hello"), drain())

            val deltaBase: DeltaBase = base.asDeltaBase()
            val deltaSource = m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT, deltaBase, 1.0)!!
            assertEquals(deltaBase, deltaSource.deltaBase)
            val deltaSnapshot = deltaSource.build(id, 2, "test")
            val deltaBytes = bytesOf(deltaSnapshot)
            val read = MachineSnapshot.readFrom(DataInputStream(deltaBytes.inputStream()))
            assertEquals(deltaBase, read.deltaBase)
            val abi = read.sections.single { it.kind == SnapshotSection.PROCESS_ABI }
            val r = SnapshotReader(abi.payload)
            assertEquals(pid, r.i32())
            assertEquals(SnapshotSection.ABI_LAYER_P1, r.u16())
            assertEquals(2, r.u16())
            val layer = P1SectionCodec.read(r.raw(r.remaining))
            assertTrue(layer.instance!!.changedOnly)
            val fullLayer = P1SectionCodec.read(
                SnapshotReader(base.snapshot.sections.single { it.kind == SnapshotSection.PROCESS_ABI }.payload).also { it.raw(8) }.let { it.raw(it.remaining) },
            )
            assertFalse(fullLayer.instance!!.changedOnly)
            assertTrue(layer.instance!!.writtenChunkCount in 1 until fullLayer.instance!!.writtenChunkCount)

            // 基準と合わせて確かめる
            val contents = SnapshotInspection.inspect(read, 64, m.config.memoryPages, base)
            assertEquals(listOf(pid), contents.processes.map { it.pid })
            // 基準がない・長さ・CRC32C・世代・UUID が違えば拒む
            assertThrows<SnapshotFormatException> { SnapshotInspection.inspect(read, 64) }
            assertThrows<SnapshotFormatException> {
                SnapshotInspection.inspect(read, 64, m.config.memoryPages, StoredSnapshot(base.snapshot, base.fileLength + 1, base.crc32c))
            }
            assertThrows<SnapshotFormatException> {
                SnapshotInspection.inspect(read, 64, m.config.memoryPages, StoredSnapshot(base.snapshot, base.fileLength, base.crc32c xor 1))
            }
            val otherGeneration = MachineSnapshot(id, 5, "test", base.snapshot.sections, coldBootCode = base.snapshot.coldBootCode)
            assertThrows<SnapshotFormatException> {
                SnapshotInspection.inspect(read, 64, m.config.memoryPages, StoredSnapshot(otherGeneration, base.fileLength, base.crc32c))
            }
            // 基準にそのプロセスがいない
            val noProcess = MachineSnapshot(id, 1, "test", base.snapshot.sections.filter { it.kind != SnapshotSection.PROCESS_ABI })
            assertThrows<SnapshotFormatException> {
                SnapshotInspection.inspect(read, 64, m.config.memoryPages, StoredSnapshot(noProcess, base.fileLength, base.crc32c))
            }
            // RAM の上限が小さすぎれば拒む
            assertThrows<SnapshotFormatException> { SnapshotInspection.inspect(read, 64, 0, base) }

            // もう 1 行: 差分の後もコマンドは動き、さらに全体保存できる
            send("world\n")
            awaitPrompt()
            assertTrue(drain().contains("world"))
            val again = m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT)!!
            assertNotNull(SnapshotInspection.inspect(again.build(id, 3, "test"), 64))

            m.lock.withLockValue { (m.kernel.objects[input] as ByteStream).closeWrite(m.wakeSink) }
            m.stop()
        }
    }
}
