package pro.ikili.kibicomputers.core.machine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.Gate
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.MachineConfig
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.kernel.emptyModule
import pro.ikili.kibicomputers.core.kernel.gated
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.spawnTest
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.tickUntilTrue
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.SaveReadiness
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
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
            // 差分の基準が使えなかったときの取り直し（#83）は、取り出し済みの印を見ずに全体を取る
            val again = manager.recapture("a", CapturePolicy.KILL_TRANSIENT)
            assertNotNull(again)
            assertNull(again!!.deltaBase)
            assertTrue(manager.isCaptured("a"), "印は立てたまま")
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

    @Test
    fun `取り出した台は外すまで止めたままで、外す途中で続かない`() = timeout(limit) {
        // 止めている間に起こされた待機点型が、外す手順で止める印を返したときに step を始めないこと。
        // 止める印を先に返すと、マシンを閉じるまでの間に step が動きうる（競合なので何回か試す）
        repeat(REPEAT_RELEASE) {
            Harness().use { h ->
                val manager = MachineManager<String>(h.scheduler, MachineErrorListener.IGNORE)
                val m = manager.attach("a", host, config)
                val gate = Gate(m)
                val pid = m.spawnTest(h.program(emptyModule()), gated(gate, code = 3, kind = ProcessKind.WAIT_POINT))
                h.tickUntilTrue { m.stateOf(pid) == ProcessState.WAITING_EVENT }
                assertTrue(manager.beginQuiesce("a"))
                gate.open()
                repeat(5) {
                    h.scheduler.tick()
                    Thread.sleep(1)
                }
                assertFalse(m.stateOf(pid).isTerminal, "止めている間は進まない")
                h.tickUntilTrue("READY にならない") { manager.readiness("a") == SaveReadiness.READY }
                assertNotNull(manager.capture("a", CapturePolicy.KILL_TRANSIENT))
                val steps = AtomicInteger()
                h.scheduler.hooks.beforeStepStart = { machine, _ -> if (machine === m) steps.incrementAndGet() }
                assertTrue(manager.release("a", MachineReleaseReason.UNLOADED))
                assertFalse(m.isQuiesced, "外すと止める印も返す")
                repeat(10) {
                    h.scheduler.tick()
                    Thread.sleep(1)
                }
                assertEquals(0, steps.get(), "取り出した後に step が動いた")
                h.scheduler.hooks.beforeStepStart = null
                manager.close()
            }
        }
    }

    private companion object {
        const val REPEAT_RELEASE = 30
    }
}
