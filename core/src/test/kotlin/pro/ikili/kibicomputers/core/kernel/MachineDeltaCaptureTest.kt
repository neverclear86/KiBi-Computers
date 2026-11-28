package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SnapshotCapableDriver
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.process.BaseRef
import pro.ikili.kibicomputers.core.kernel.process.MemoryBase
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.ColdBootReason
import pro.ikili.kibicomputers.core.persist.DeltaBase
import pro.ikili.kibicomputers.core.persist.FullSaveRule
import pro.ikili.kibicomputers.core.persist.MachineSaveConfig
import pro.ikili.kibicomputers.core.persist.MachineSnapshot
import pro.ikili.kibicomputers.core.persist.ModuleStore
import pro.ikili.kibicomputers.core.persist.SnapshotInspection
import pro.ikili.kibicomputers.core.persist.StoredSnapshot
import pro.ikili.kibicomputers.core.runtime.FakeInstance
import pro.ikili.kibicomputers.core.runtime.MemoryChunkCounts
import pro.ikili.kibicomputers.core.runtime.StateCaptureMode
import pro.ikili.kibicomputers.core.runtime.WasmBinary
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1Driver
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Duration
import java.util.UUID
import java.util.zip.CRC32C
import pro.ikili.kibicomputers.core.testing.bodyTimeout as timeout

/**
 * 差分の写しと、プロセスの線形メモリの基準（`MemoryBase`）の進め方（#83、ADR 0083 の 5.）。
 * テスト用のランタイム（区画を追えるふりをする [FakeInstance]）と、取り方を覚える [TestDriver] で確かめる
 */
class MachineDeltaCaptureTest {
    private val limit = Duration.ofSeconds(30)
    private val policy = CapturePolicy.SKIP_IF_TRANSIENT
    private val id1 = UUID.randomUUID()
    private val id2 = UUID.randomUUID()

    private fun base(id: UUID, generation: Long) = DeltaBase(id, generation, 100, 0)

    private class Proc(val pid: Int, val driver: TestDriver)

    /** 待機点で止まるプロセスを作り、インスタンスを「区画を追える」（[tracks]）ものにする */
    private fun Harness.waiting(m: Machine, gate: Gate, tracks: Boolean = true, driver: TestDriver = gated(gate, kind = ProcessKind.WAIT_POINT)): Proc {
        val pid = m.spawnTest(program(emptyModule()), driver)
        tickUntilTrue { m.stateOf(pid) == ProcessState.WAITING_EVENT }
        val instance = m.rt(pid).instances.first() as FakeInstance
        instance.tracks = tracks
        instance.chunkCounts = MemoryChunkCounts(10, 1)
        return Proc(pid, driver)
    }

    private fun Machine.base(pid: Int): MemoryBase = lock.withLockValue { rt(pid).memoryBase }

    private fun Machine.isCommitted(pid: Int, id: UUID, generation: Long): Boolean =
        lock.withLockValue { MemoryBase.matches(rt(pid).memoryBase, BaseRef(id, generation), rt(pid).instances.first()) }

    @Test
    fun `全体を書けると基準が進み、次は差分でDELTA_BASEが先頭に付く。基準の後に作ったプロセスは全体の形`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val a = h.waiting(m, gate)
            val full = m.captureSnapshot(policy)!!
            assertNull(full.deltaBase)
            assertEquals(StateCaptureMode.FULL_AND_TRACK, a.driver.modes.last())
            assertTrue(m.base(a.pid) is MemoryBase.Pending, "書けるまでは Pending")
            full.build(id1, 1, "test")
            full.committed(id1, 1)
            assertTrue(m.isCommitted(a.pid, id1, 1))

            val b = h.waiting(m, gate)
            val delta = m.captureSnapshot(policy, base(id1, 1), FullSaveRule(1.0))!!
            assertEquals(base(id1, 1), delta.deltaBase)
            assertEquals(StateCaptureMode.CHANGED, a.driver.modes.last())
            assertEquals(StateCaptureMode.FULL, b.driver.modes.last(), "基準の後に作ったプロセスは全体の形")
            val snapshot = delta.build(id1, 2, "test")
            assertEquals(base(id1, 1), snapshot.deltaBase)
            // 差分を書いても基準は変わらない
            delta.committed(id1, 2)
            assertTrue(m.isCommitted(a.pid, id1, 1))
            assertTrue(m.base(b.pid) is MemoryBase.None)
            gate.open()
            m.stop()
        }
    }

    @Test
    fun `書けなかった全体の写しの後は、前の基準の差分でも全体の形`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val a = h.waiting(m, gate)
            m.captureSnapshot(policy)!!.committed(id1, 1)
            assertTrue(m.isCommitted(a.pid, id1, 1))
            // 2 回目の全体は書けなかった（committed を呼ばない）
            m.captureSnapshot(policy)!!
            assertTrue(m.base(a.pid) is MemoryBase.Pending)
            m.captureSnapshot(policy, base(id1, 1), FullSaveRule(1.0))!!
            assertEquals(StateCaptureMode.FULL, a.driver.modes.last())
            gate.open()
            m.stop()
        }
    }

    @Test
    fun `変わった量の比が境目を超えたら、差分を頼まれても全体の写しにする`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val a = h.waiting(m, gate)
            m.captureSnapshot(policy)!!.committed(id1, 1)
            val instance = m.rt(a.pid).instances.first() as FakeInstance
            instance.chunkCounts = MemoryChunkCounts(10, 6)
            val over = m.captureSnapshot(policy, base(id1, 1), FullSaveRule(0.5, 0))!!
            assertNull(over.deltaBase)
            assertEquals(StateCaptureMode.FULL_AND_TRACK, a.driver.modes.last())
            over.committed(id1, 2)
            instance.chunkCounts = MemoryChunkCounts(10, 5)
            val under = m.captureSnapshot(policy, base(id1, 2), FullSaveRule(0.5, 0))!!
            assertEquals(base(id1, 2), under.deltaBase)
            assertEquals(StateCaptureMode.CHANGED, a.driver.modes.last())
            gate.open()
            m.stop()
        }
    }

    @Test
    fun `比が境目を超えても、変わった区画が下限以下なら差分。下限を超えたら全体`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val a = h.waiting(m, gate)
            m.captureSnapshot(policy)!!.committed(id1, 1)
            val instance = m.rt(a.pid).instances.first() as FakeInstance
            // 比は 1.0（境目 0.5 を超える）だが、変わった区画 8 は下限 8 以下
            instance.chunkCounts = MemoryChunkCounts(8, 8)
            val small = m.captureSnapshot(policy, base(id1, 1), FullSaveRule(0.5, 8))!!
            assertEquals(base(id1, 1), small.deltaBase)
            assertEquals(StateCaptureMode.CHANGED, a.driver.modes.last())
            small.committed(id1, 2)
            assertTrue(m.isCommitted(a.pid, id1, 1), "差分を書いても基準は進まない")
            // 変わった区画 9 は下限 8 を超え、比も超える
            instance.chunkCounts = MemoryChunkCounts(9, 9)
            val large = m.captureSnapshot(policy, base(id1, 1), FullSaveRule(0.5, 8))!!
            assertNull(large.deltaBase)
            assertEquals(StateCaptureMode.FULL_AND_TRACK, a.driver.modes.last())
            large.committed(id1, 3)
            assertTrue(m.isCommitted(a.pid, id1, 3))
            // 下限を超えても、比が境目以下なら差分
            instance.chunkCounts = MemoryChunkCounts(20, 10)
            val half = m.captureSnapshot(policy, base(id1, 3), FullSaveRule(0.5, 8))!!
            assertEquals(base(id1, 3), half.deltaBase)
            gate.open()
            m.stop()
        }
    }

    @Test
    fun `既定の規則では、小さな台の変わった区画は比によらず差分`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val a = h.waiting(m, gate)
            m.captureSnapshot(policy)!!.committed(id1, 1)
            val instance = m.rt(a.pid).instances.first() as FakeInstance
            // 標準 OS の init と sh が 1 行で変える程度（30 区画のうち 16。比 0.53）
            instance.chunkCounts = MemoryChunkCounts(30, 16)
            assertEquals(base(id1, 1), m.captureSnapshot(policy, base(id1, 1))!!.deltaBase)
            assertEquals(StateCaptureMode.CHANGED, a.driver.modes.last())
            gate.open()
            m.stop()
        }
    }

    @Test
    fun `基準に入っていないプロセスがいれば、下限によらず比で決める`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val a = h.waiting(m, gate)
            m.captureSnapshot(policy)!!.committed(id1, 1)
            val instanceA = m.rt(a.pid).instances.first() as FakeInstance
            instanceA.chunkCounts = MemoryChunkCounts(10, 2)
            // 基準の後に起こしたプロセス: 基準なしの 12 区画が加わり、changed = 14、total = 22（比 0.64）。下限（64）以下でも全体
            val b = h.waiting(m, gate)
            val instanceB = m.rt(b.pid).instances.first() as FakeInstance
            instanceB.chunkCounts = MemoryChunkCounts(12, 12)
            val counts = m.changedChunks(base(id1, 1))
            assertEquals(14L, counts.changed)
            assertEquals(22L, counts.total)
            assertEquals(12L, counts.unbased)
            val full = m.captureSnapshot(policy, base(id1, 1))!!
            assertEquals(null, full.deltaBase, "基準なしのプロセスがいるのに、下限で差分になった")
            full.committed(id1, 2)
            // 全体の写しで基準が付いたので、次は小さな差分
            instanceB.chunkCounts = MemoryChunkCounts(12, 1)
            instanceA.chunkCounts = MemoryChunkCounts(10, 2)
            assertEquals(0L, m.changedChunks(base(id1, 2)).unbased)
            assertEquals(base(id1, 2), m.captureSnapshot(policy, base(id1, 2))!!.deltaBase)
            gate.open()
            m.stop()
        }
    }

    @Test
    fun `基準が合わない台（復元の直後）は、小さくても比で全体になる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val a = h.waiting(m, gate)
            val instance = m.rt(a.pid).instances.first() as FakeInstance
            // 復元した台: 置き場は基準を持つが、プロセスの基準は None。全部の区画が changed に入る（23 区画。下限以下）
            instance.chunkCounts = MemoryChunkCounts(23, 23)
            val counts = m.changedChunks(base(id1, 1))
            assertEquals(23L, counts.unbased)
            assertEquals(null, m.captureSnapshot(policy, base(id1, 1))!!.deltaBase)
            gate.open()
            m.stop()
        }
    }

    @Test
    fun `全体か差分かの規則の境目`() {
        val rule = FullSaveRule(0.5, 4)
        assertTrue(!rule.isFull(4, 4), "下限ちょうどは差分")
        assertTrue(rule.isFull(5, 5))
        assertTrue(!rule.isFull(5, 10), "比ちょうどは差分")
        assertTrue(rule.isFull(6, 10))
        assertTrue(!rule.isFull(0, 0), "書き換えた区画がなければ差分")
        // 下限 0 は比だけで決める（#668 より前と同じ）
        val ratioOnly = FullSaveRule(0.5, 0)
        assertTrue(ratioOnly.isFull(1, 1))
        assertTrue(!ratioOnly.isFull(0, 0))
        assertTrue(!FullSaveRule(0.0, 0).isFull(0, 10), "変わった区画が 0 なら、境目 0 でも差分")
        assertTrue(FullSaveRule(0.0, 0).isFull(1, 10))
        assertTrue(!FullSaveRule(1.0, 0).isFull(10, 10), "境目 1.0 なら全体にならない")
        // 基準なしの区画があれば下限は効かない（全体の写しでしか基準を付け直せない。#668）
        assertTrue(rule.isFull(4, 4, 4), "基準なしの区画は下限で隠さない")
        assertTrue(!rule.isFull(4, 10, 4), "基準なしの区画があっても、比が境目以下なら差分")
        assertTrue(!rule.isFull(4, 4, 0))
        assertEquals(FullSaveRule(MachineSaveConfig.DEFAULT_FULL_SAVE_CHANGED_RATIO, MachineSaveConfig.DEFAULT_FULL_SAVE_MIN_CHANGED_CHUNKS), MachineSaveConfig.DEFAULT.fullSaveRule)
    }

    @Test
    fun `冷えた起動で捨てた写しは基準を進めず、読み取り専用に戻したプロセスも基準なしに戻す`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val a = h.waiting(m, gate)
            val initDriver = gated(gate, kind = ProcessKind.WAIT_POINT)
            initDriver.abiPayload = null
            val init = h.waiting(m, gate, driver = initDriver)
            m.lock.withLockValue { m.kernel.initPid = init.pid }
            val copy = m.captureCopy(policy)!!
            assertEquals(ColdBootReason.INIT_KILLED, copy.coldBootReason)
            assertEquals(StateCaptureMode.FULL_AND_TRACK, a.driver.modes.last(), "A は init より先に取った")
            assertTrue(m.base(a.pid) is MemoryBase.None)
            copy.toSource().committed(id1, 1)
            assertTrue(m.base(a.pid) is MemoryBase.None, "冷えた起動の写しは基準を進めない")
            // 次の差分の取り出しで A は全体の形
            initDriver.abiPayload = ByteArray(0)
            m.captureSnapshot(policy, base(id1, 1), FullSaveRule(1.0))!!
            assertEquals(StateCaptureMode.FULL, a.driver.modes.last())
            gate.open()
            m.stop()
        }
    }

    @Test
    fun `写しの中で殺したプロセスは基準なしになり、振り直した後の基準ではCHANGEDで取らない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val a = h.waiting(m, gate)
            m.captureSnapshot(policy)!!.committed(id1, 1)
            assertTrue(m.isCommitted(a.pid, id1, 1))
            // 殺さずに振り直しただけ: (id1, 1) を基準にしているが、基準は (id2, 1)
            m.captureSnapshot(policy, base(id2, 1), FullSaveRule(1.0))!!
            assertEquals(StateCaptureMode.FULL, a.driver.modes.last())
            assertTrue(m.isCommitted(a.pid, id1, 1), "差分の写しでは基準を変えない")

            // 次の全体保存の写しの中で殺す
            a.driver.abiPayload = null
            val killed = m.captureCopy(policy)!!
            assertEquals(listOf(a.pid), killed.killedPids)
            assertTrue(m.base(a.pid) is MemoryBase.None)
            killed.toSource().committed(id2, 1)
            assertTrue(m.base(a.pid) is MemoryBase.None)
            a.driver.abiPayload = ByteArray(0)
            m.captureSnapshot(policy, base(id2, 1), FullSaveRule(1.0))!!
            assertEquals(StateCaptureMode.FULL, a.driver.modes.last())
            gate.open()
            m.stop()
        }
    }

    @Test
    fun `区画を追えないランタイムのプロセスは、全体を書けても基準なしで、差分でも全体の形`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val a = h.waiting(m, gate, tracks = false)
            val full = m.captureSnapshot(policy)!!
            assertEquals(StateCaptureMode.FULL, a.driver.modes.last())
            full.committed(id1, 1)
            assertTrue(m.base(a.pid) is MemoryBase.None)
            m.captureSnapshot(policy, base(id1, 1), FullSaveRule(1.0))!!
            assertEquals(StateCaptureMode.FULL, a.driver.modes.last())
            gate.open()
            m.stop()
        }
    }

    @Test
    fun `インスタンスを作り直したら、前の基準は使わない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val a = h.waiting(m, gate)
            m.captureSnapshot(policy)!!.committed(id1, 1)
            val committed = m.base(a.pid) as MemoryBase.Committed
            assertSame(m.rt(a.pid).instances.first(), committed.instance)
            // 別のインスタンスに差し替わった（exec・戻し）ものとして扱う
            val replacement = h.program(emptyModule()).module.instantiate(pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig.builder().build()) as FakeInstance
            replacement.tracks = true
            replacement.chunkCounts = MemoryChunkCounts(1, 1)
            val original = m.lock.withLockValue { m.rt(a.pid).instances.set(0, replacement) }
            m.captureSnapshot(policy, base(id1, 1), FullSaveRule(1.0))!!
            assertEquals(StateCaptureMode.FULL, a.driver.modes.last())
            m.lock.withLockValue { m.rt(a.pid).instances.set(0, original) }
            replacement.close()
            gate.open()
            m.stop()
        }
    }

    /** 待ちは [inner] に任せ、層の中身は本物の p1 の層（[WasiP1Driver]）で取る */
    private class P1LayerDriver(private val inner: TestDriver, private val p1: WasiP1Driver) : SnapshotCapableDriver {
        val modes: MutableList<StateCaptureMode> = java.util.concurrent.CopyOnWriteArrayList()

        override val kind: ProcessKind get() = inner.kind

        override val abiLayer: Int get() = p1.abiLayer

        override val abiLayerVersion: Int get() = p1.abiLayerVersion

        override fun captureAbi(rt: ProcessRuntime): ByteArray? = captureAbi(rt, StateCaptureMode.FULL)

        override fun captureAbi(rt: ProcessRuntime, mode: StateCaptureMode): ByteArray? {
            modes += mode
            return p1.captureAbi(rt, mode)
        }

        override fun configure(ctx: ProcessContext, builder: WasmInstanceConfig.Builder) = inner.configure(ctx, builder)

        override fun start(ctx: ProcessContext): StepResult = inner.start(ctx)

        override fun resume(ctx: ProcessContext, ready: List<WaitKey>): StepResult = inner.resume(ctx, ready)
    }

    private fun bytesOf(snapshot: MachineSnapshot): ByteArray =
        ByteArrayOutputStream().also { out -> DataOutputStream(out).use { snapshot.writeTo(it) } }.toByteArray()

    private fun stored(bytes: ByteArray): StoredSnapshot = StoredSnapshot(
        MachineSnapshot.readFrom(DataInputStream(bytes.inputStream())),
        bytes.size.toLong(),
        CRC32C().also { it.update(bytes) }.value.toInt(),
    )

    @Test
    fun `線形メモリのないp1のプロセスは、全体の後の差分の写しでも殺されず、基準と合わせて読み直せる（#562）`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            // 線形メモリのない、本物のランタイムのモジュール（可変のグローバルだけ）
            val bytes = WasmBinary().apply { mutableI64Global(7) }.build()
            val program = Program.load(EndiveRuntime(), bytes)
            h.moduleStore.put(ModuleStore.sha256(bytes), bytes)
            val driver = P1LayerDriver(gated(gate, kind = ProcessKind.WAIT_POINT), WasiP1Driver(program.module))
            val pid = m.spawnTest(program, driver, unmetered = true)
            h.tickUntilTrue { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            assertTrue(m.rt(pid).instances.first().tracksChanges)

            val id = UUID.randomUUID()
            val full = m.captureCopy(policy)!!
            assertEquals(emptyList<Int>(), full.killedPids)
            assertEquals(StateCaptureMode.FULL_AND_TRACK, driver.modes.last())
            val baseBytes = bytesOf(full.toSource().build(id, 1, "test"))
            full.toSource().committed(id, 1)
            assertTrue(m.isCommitted(pid, id, 1))
            val base = stored(baseBytes)

            val delta = m.captureCopy(policy, base.asDeltaBase(), FullSaveRule(1.0))!!
            assertEquals(StateCaptureMode.CHANGED, driver.modes.last())
            assertEquals(emptyList<Int>(), delta.killedPids, "差分の写しの中で殺さない")
            val snapshot = delta.toSource().build(id, 2, "test")
            assertEquals(base.asDeltaBase(), snapshot.deltaBase)
            val read = MachineSnapshot.readFrom(DataInputStream(bytesOf(snapshot).inputStream()))
            val contents = SnapshotInspection.inspect(read, 64, m.config.memoryPages, base)
            assertEquals(listOf(pid), contents.processes.map { it.pid })
            gate.open()
            m.stop()
        }
    }
}
