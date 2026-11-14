package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.runtime.FakeInstance
import pro.ikili.kibicomputers.core.runtime.MemoryChunkCounts
import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * CRASHED の台は写さない（#698）。知らせ（tick で配る）がケースに届く前に保存の経路が走っても、
 * どちらの方針でも `null` を返し、取り出しの番号・差分の基準に触らない
 */
class CaptureCrashedTest {
    private val limit = Duration.ofSeconds(30)

    private fun Machine.captureTokenValue(): Long {
        val field = Machine::class.java.getDeclaredField("captureToken")
        field.isAccessible = true
        return lock.withLockValue { field.getLong(this) }
    }

    @Test
    fun `CRASHEDの台は、知らせを配る前でもどちらの方針でも写さない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val pid = m.spawnTest(h.program(emptyModule()), gated(gate, kind = ProcessKind.WAIT_POINT))
            h.tickUntilTrue { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            // 待機点にいる台は写せる
            assertNotNull(m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT))
            m.lock.withLockValue { m.inconsistencyLocked("テスト") }
            // tick を回さない（知らせはまだ配らない）
            assertEquals(MachineState.CRASHED, m.state)
            assertTrue(h.events.none { it.second == MachineState.CRASHED }, "知らせを配った")
            assertNull(m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT))
            assertNull(m.captureSnapshot(CapturePolicy.KILL_TRANSIENT))
            assertFalse(m.isSettlingForSave)
            m.close()
        }
    }

    @Test
    fun `断っても取り出しの番号と差分の基準は変わらない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val pid = m.spawnTest(h.program(emptyModule()), gated(gate, kind = ProcessKind.WAIT_POINT))
            h.tickUntilTrue { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            val instance = m.rt(pid).instances.first() as FakeInstance
            instance.tracks = true
            instance.chunkCounts = MemoryChunkCounts(10, 1)
            val id = UUID.randomUUID()
            m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT)!!.committed(id, 1)
            val rt = m.rt(pid)
            val base = m.lock.withLockValue { rt.memoryBase }
            val token = m.captureTokenValue()
            m.lock.withLockValue { m.inconsistencyLocked("テスト") }
            assertNull(m.captureSnapshot(CapturePolicy.KILL_TRANSIENT))
            assertNull(m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT))
            assertEquals(token, m.captureTokenValue(), "断ったのに取り出しの番号を進めた")
            assertEquals(base, m.lock.withLockValue { rt.memoryBase }, "断ったのに差分の基準を変えた")
            m.close()
        }
    }
}
