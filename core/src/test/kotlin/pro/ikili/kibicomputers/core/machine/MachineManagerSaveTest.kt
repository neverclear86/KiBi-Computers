package pro.ikili.kibicomputers.core.machine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.MachineConfig
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.SaveReadiness
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** マシン管理の、待機点保存の手順（止める・取り出す・外す。#81） */
class MachineManagerSaveTest {
    private val limit = Duration.ofSeconds(30)
    private val config = MachineConfig.builder().fuelPerTick(100).build()
    private val host = MachineHost { _, _, _ -> }

    @Test
    fun `UNLOADEDの前に止めて取り出し、取り出し済みの台は取り出し直さず、外すと止める`() = timeout(limit) {
        Harness().use { h ->
            val manager = MachineManager<String>(h.scheduler, MachineErrorListener.IGNORE)
            val m = manager.attach("a", host, config)
            assertTrue(manager.beginQuiesce("a"))
            assertTrue(m.isQuiesced)
            assertEquals(SaveReadiness.READY, manager.readiness("a"))
            assertFalse(manager.isCaptured("a"))
            assertNotNull(manager.capture("a", CapturePolicy.KILL_TRANSIENT))
            assertTrue(manager.isCaptured("a"))
            assertNull(manager.capture("a", CapturePolicy.KILL_TRANSIENT), "取り出し済みは取り出し直さない")
            assertSame(m, manager["a"], "取り出しただけでは捨てない")
            assertTrue(manager.release("a", MachineReleaseReason.UNLOADED))
            assertFalse(m.isQuiesced, "外すと止める印も返す")
            assertNull(manager.capture("a", CapturePolicy.KILL_TRANSIENT))
            assertFalse(manager.beginQuiesce("a"))
            manager.close()
        }
    }

    @Test
    fun `quiesceAllで全台を止め、endQuiesceで続けて取り出し済みの印も消す`() = timeout(limit) {
        Harness().use { h ->
            val manager = MachineManager<String>(h.scheduler, MachineErrorListener.IGNORE)
            val a = manager.attach("a", host, config)
            val b = manager.attach("b", host, config)
            manager.quiesceAll()
            assertTrue(a.isQuiesced && b.isQuiesced)
            assertNotNull(manager.capture("a", CapturePolicy.SKIP_IF_TRANSIENT))
            assertTrue(manager.isCaptured("a"))
            manager.endQuiesce("a")
            assertFalse(a.isQuiesced)
            assertFalse(manager.isCaptured("a"))
            assertTrue(b.isQuiesced)
            manager.close()
            assertFalse(b.isQuiesced)
        }
    }
}
