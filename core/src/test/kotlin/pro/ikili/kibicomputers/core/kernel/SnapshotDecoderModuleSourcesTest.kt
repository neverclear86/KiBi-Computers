package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ModuleSource
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.MachineSnapshot
import pro.ikili.kibicomputers.core.persist.SnapshotFormatException
import pro.ikili.kibicomputers.core.persist.SnapshotSection
import pro.ikili.kibicomputers.core.persist.snapshot.KernelLimits
import pro.ikili.kibicomputers.core.persist.snapshot.P1SectionCodec
import pro.ikili.kibicomputers.core.persist.snapshot.SnapshotDecoder
import pro.ikili.kibicomputers.core.persist.snapshot.SnapshotReader
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 復号する前にモジュールの参照だけを読む [SnapshotDecoder.moduleSources]・[P1SectionCodec.readModuleSource]（#577、#587）。
 * ここで断るものは [SnapshotDecoder.decode] も必ず断ること（先に読んでも冷えた起動の理由を変えない）を確かめる
 */
class SnapshotDecoderModuleSourcesTest {
    private val limit = Duration.ofSeconds(120)

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    private class Captured(val snapshot: MachineSnapshot, val maxProcesses: Int, val memoryPages: Int)

    /** `prompt` と、元のハッシュだけが違う `prompt` の2つが入力を待ったところの写し */
    private fun capture(): Captured = Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
        val m = h.machine(fuelPerTick = 10_000_000)
        val prompt = resource("prompt-wasi.wasm")
        m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        m.vfs.createDirectory(VfsPath.parse("/bin"))
        for ((name, bytes) in listOf("prompt" to prompt, "prompt2" to withCustomSection(prompt))) {
            val f = m.vfs.open(VfsPath.parse("/bin/$name"), OpenOptions(read = true, write = true, create = true, truncate = true))
            m.vfs.write(f, bytes, 0, bytes.size)
        }
        val pids = listOf("prompt", "prompt2").map { name ->
            val (input, output) = m.lock.withLockValue {
                ByteStream(4096).also { m.kernel.objects.add(it) }.id to ByteStream(64 * 1024).also { m.kernel.objects.add(it) }.id
            }
            val stdio = StdioSpec(StdioSource.Object(input, HandleRights.READ), StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed)
            m.exec("/bin/$name", KernelDrivers.DEFAULT, SpawnOptions(args = listOf(name), stdio = stdio))
        }
        eventually(60_000, "入力を待たない") {
            h.scheduler.tick()
            pids.all { m.stateOf(it) == ProcessState.WAITING_EVENT }
        }
        var copy: pro.ikili.kibicomputers.core.persist.KernelStateCopy? = null
        eventually(10_000, "写しが取れない") {
            copy = m.captureCopy(CapturePolicy.SKIP_IF_TRANSIENT)
            copy != null
        }
        val snapshot = copy!!.toSource().build(UUID.randomUUID(), 1, "test")
        val captured = Captured(snapshot, m.config.maxProcesses, m.config.memoryPages)
        m.stop()
        captured
    }

    private fun MachineSnapshot.withSections(sections: List<SnapshotSection>): MachineSnapshot =
        MachineSnapshot(machineId, generation + 1, modVersion, sections)

    private fun abiSections(snapshot: MachineSnapshot): List<SnapshotSection> = snapshot.sections.filter { it.kind == SnapshotSection.PROCESS_ABI }

    private fun pidOf(section: SnapshotSection): Int = SnapshotReader(section.payload).i32()

    @Test
    fun `先読みは、復号したp1の層と同じモジュールの参照を節の順に返す`(): Unit = timeout(limit) {
        val c = capture()
        val sources = SnapshotDecoder.moduleSources(c.snapshot, c.maxProcesses)
        val decoded = SnapshotDecoder.decode(c.snapshot, c.maxProcesses, c.memoryPages, null)
        val expected = abiSections(c.snapshot).map { decoded.p1.getValue(pidOf(it)).moduleSource }
        assertEquals(2, sources.size)
        assertEquals(expected, sources)
        assertNotEquals(sources[0], sources[1], "元のハッシュが違う2つのモジュール")
    }

    @Test
    fun `PROCESS_ABI節の版やp1の層の版が違えば、先読みも復号も断る`(): Unit = timeout(limit) {
        val c = capture()
        val others = c.snapshot.sections.filter { it.kind != SnapshotSection.PROCESS_ABI }
        val abi = abiSections(c.snapshot)
        // 節の版
        val badVersion = c.snapshot.withSections(
            others + abi.mapIndexed { i, s -> if (i == 0) SnapshotSection(s.kind, s.version + 1, s.payload) else s },
        )
        assertThrows<SnapshotFormatException> { SnapshotDecoder.moduleSources(badVersion, c.maxProcesses) }
        assertThrows<SnapshotFormatException> { SnapshotDecoder.decode(badVersion, c.maxProcesses, c.memoryPages, null) }
        // p1 の層の版 3（pid i32・層 u16 の後の u16）
        val badLayer = c.snapshot.withSections(
            others + abi.mapIndexed { i, s ->
                if (i == 0) {
                    val p = s.payload
                    p[6] = 0
                    p[7] = 3
                    SnapshotSection(s.kind, s.version, p)
                } else {
                    s
                }
            },
        )
        assertThrows<SnapshotFormatException> { SnapshotDecoder.moduleSources(badLayer, c.maxProcesses) }
        assertThrows<SnapshotFormatException> { SnapshotDecoder.decode(badLayer, c.maxProcesses, c.memoryPages, null) }
    }

    @Test
    fun `readModuleSourceは層の版1と2を受け、3を断る`(): Unit = timeout(limit) {
        val c = capture()
        val section = abiSections(c.snapshot).first()
        val payload = section.payload
        val layer = payload.copyOfRange(8, payload.size)
        val expected = SnapshotDecoder.moduleSources(c.snapshot, c.maxProcesses).first()
        val v1: ModuleSource = P1SectionCodec.readModuleSource(layer, 1)
        val v2: ModuleSource = P1SectionCodec.readModuleSource(layer, 2)
        assertEquals(expected, v1)
        assertEquals(expected, v2)
        assertThrows<SnapshotFormatException> { P1SectionCodec.readModuleSource(layer, 3) }
        assertThrows<SnapshotFormatException> { P1SectionCodec.readModuleSource(layer, 0) }
    }

    @Test
    fun `節の数の上限は、KERNEL節の表のプロセスの数の上限と同じ`(): Unit = timeout(limit) {
        val c = capture()
        val others = c.snapshot.sections.filter { it.kind != SnapshotSection.PROCESS_ABI }
        val one = abiSections(c.snapshot).first()
        val max = KernelLimits(1).maxTableProcesses
        assertEquals(1 * 2 + 64, max)
        // 先読みは pid を見ないので、同じ節を並べてよい
        val atLimit = c.snapshot.withSections(others + List(max) { one })
        assertEquals(max, SnapshotDecoder.moduleSources(atLimit, 1).size)
        val over = c.snapshot.withSections(others + List(max + 1) { one })
        assertThrows<SnapshotFormatException> { SnapshotDecoder.moduleSources(over, 1) }
        // decode 側: PROCESS_ABI 節は pid が KERNEL 節の表にあり重ならないものしか受けないので、節の数は表のプロセスの数で縛られる。
        // その表の数を、同じ上限で断ることを確かめる（先読みと decode の上限がずれたら落ちる）
        assertEquals("数 ${max + 1} が上限 $max を超える", decodeWithTableCount(c, max + 1).message)
        // ちょうど上限なら数の上限では断らない（中身が足りないので、別の理由で断る）
        assertNotEquals("数 $max が上限 $max を超える", decodeWithTableCount(c, max).message)
    }

    /** KERNEL 節のプロセス表の数だけを [count] に書き換えて、maxProcesses = 1 で復号したときの例外 */
    private fun decodeWithTableCount(c: Captured, count: Int): SnapshotFormatException {
        val kernel = c.snapshot.sections.single { it.kind == SnapshotSection.KERNEL }
        val p = kernel.payload
        // fuel i64・コンソール i32・init i32・時計 i64 x4・次の pid i32 の後に、孤児の数 i32 と孤児の pid i32 の列、表の数 i32
        val orphanAt = 8 + 4 + 4 + 32 + 4
        val orphans = SnapshotReader(p.copyOfRange(orphanAt, orphanAt + 4)).i32()
        val countAt = orphanAt + 4 + orphans * 4
        java.nio.ByteBuffer.wrap(p).putInt(countAt, count)
        val patched = c.snapshot.withSections(
            c.snapshot.sections.map { if (it.kind == SnapshotSection.KERNEL) SnapshotSection(it.kind, it.version, p) else it },
        )
        return assertThrows<SnapshotFormatException> { SnapshotDecoder.decode(patched, 1, c.memoryPages, null) }
    }

    @Test
    fun `maxProcessesを下げて読んでも、復号が受ける写しは先読みも受ける`(): Unit = timeout(limit) {
        // 2 プロセスの写しを maxProcesses = 1 で読む（#251 でコンフィグにして下げたとき）。前は先読みだけが断っていた（#587）
        val c = capture()
        val decoded = SnapshotDecoder.decode(c.snapshot, 1, c.memoryPages, null)
        assertEquals(2, decoded.p1.size)
        assertEquals(2, SnapshotDecoder.moduleSources(c.snapshot, 1).size)
    }

    companion object {
        /**
         * [bytes] の末尾に WASM のカスタム節（名前 `x`、中身なし）を足す。振る舞いは同じで、元のバイト列のハッシュだけが違うモジュール
         */
        fun withCustomSection(bytes: ByteArray): ByteArray = bytes + byteArrayOf(0x00, 0x02, 0x01, 'x'.code.toByte())
    }
}
