package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.BaseRef
import pro.ikili.kibicomputers.core.kernel.process.MemoryBase
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.ColdBootReason
import pro.ikili.kibicomputers.core.persist.DeltaBase
import pro.ikili.kibicomputers.core.runtime.FakeInstance
import pro.ikili.kibicomputers.core.runtime.MemoryChunkCounts
import pro.ikili.kibicomputers.core.runtime.StateCaptureMode
import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

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
            val delta = m.captureSnapshot(policy, base(id1, 1), 1.0)!!
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
            m.captureSnapshot(policy, base(id1, 1), 1.0)!!
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
            val over = m.captureSnapshot(policy, base(id1, 1), 0.5)!!
            assertNull(over.deltaBase)
            assertEquals(StateCaptureMode.FULL_AND_TRACK, a.driver.modes.last())
            over.committed(id1, 2)
            instance.chunkCounts = MemoryChunkCounts(10, 5)
            val under = m.captureSnapshot(policy, base(id1, 2), 0.5)!!
            assertEquals(base(id1, 2), under.deltaBase)
            assertEquals(StateCaptureMode.CHANGED, a.driver.modes.last())
            gate.open()
            m.stop()
        }
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
            m.captureSnapshot(policy, base(id1, 1), 1.0)!!
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
            m.captureSnapshot(policy, base(id2, 1), 1.0)!!
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
            m.captureSnapshot(policy, base(id2, 1), 1.0)!!
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
            m.captureSnapshot(policy, base(id1, 1), 1.0)!!
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
            m.captureSnapshot(policy, base(id1, 1), 1.0)!!
            assertEquals(StateCaptureMode.FULL, a.driver.modes.last())
            m.lock.withLockValue { m.rt(a.pid).instances.set(0, original) }
            replacement.close()
            gate.open()
            m.stop()
        }
    }
}
