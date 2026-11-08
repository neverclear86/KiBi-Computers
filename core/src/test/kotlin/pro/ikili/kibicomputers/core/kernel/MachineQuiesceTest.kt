package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.SaveReadiness
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** マシン1台だけを止める口（`Machine.quiesce`）と、保存できるかの判定（`Machine.saveReadiness`）のテスト（#81） */
class MachineQuiesceTest {
    private val limit = Duration.ofSeconds(30)

    @Test
    fun `1台だけ止めても他は動き、closeで続く`() = timeout(limit) {
        Harness().use { h ->
            val p1 = AtomicInteger()
            val p2 = AtomicInteger()
            val m1 = h.machine(fuelPerTick = 1000)
            val m2 = h.machine(fuelPerTick = 1000)
            m1.spawnTest(h.program(spinForever(p1)), runDriver())
            m2.spawnTest(h.program(spinForever(p2)), runDriver())
            h.tickUntilTrue { p1.get() > 0 && p2.get() > 0 }
            val handle = m1.quiesce()
            assertTrue(m1.isQuiesced)
            // 手持ちを使い切るまでは進むので、止まるのを待つ
            var last = -1
            h.tickUntilTrue("止まらない") {
                val now = p1.get()
                (now == last).also { last = now }
            }
            val atStop = p1.get()
            val other = p2.get()
            repeat(10) {
                h.scheduler.tick()
                Thread.sleep(5)
            }
            assertTrue(p1.get() - atStop <= 1000, "止めたマシンは手持ちより先へ進まない")
            h.tickUntilTrue("止めていないマシンが進まない") { p2.get() > other + 1000 }
            handle.close()
            handle.close() // 2回呼んでもよい
            assertFalse(m1.isQuiesced)
            val resumed = p1.get()
            h.tickUntilTrue("続かない") { p1.get() > resumed + 1000 }
            m1.stop()
            m2.stop()
        }
    }

    @Test
    fun `止めている間は起こされた待機点型にstepが割り当てられず、READYのまま`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val pid = m.spawnTest(h.program(emptyModule()), gated(gate, code = 3, kind = ProcessKind.WAIT_POINT))
            h.tickUntilTrue { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            assertEquals(SaveReadiness.READY, m.saveReadiness())
            val handle = m.quiesce()
            gate.open()
            repeat(20) {
                h.scheduler.tick()
                Thread.sleep(2)
            }
            assertFalse(m.stateOf(pid).isTerminal, "止めている間は進まない")
            h.tickUntilTrue("READY にならない") { handle.readiness == SaveReadiness.READY }
            handle.close()
            h.tickUntilTrue("続かない") { m.stateOf(pid) == ProcessState.EXITED }
        }
    }

    @Test
    fun `stepの途中の待機点型はTRANSIENTで、SKIP_IF_TRANSIENTは写さない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val gate = Gate(m)
            val pid = m.spawnTest(
                h.program(emptyModule()),
                TestDriver(ProcessKind.WAIT_POINT, onResume = { _, _ -> StepResult.Exit(0) }) {
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    StepResult.Wait(listOf(gate.key))
                },
            )
            h.scheduler.tick()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(SaveReadiness.TRANSIENT, m.saveReadiness())
            assertNull(m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT))
            val copy = m.captureCopy(CapturePolicy.KILL_TRANSIENT)!!
            assertEquals(listOf(pid), copy.killedPids, "KILL_TRANSIENT は写しの中で殺す")
            assertFalse(m.stateOf(pid).isTerminal, "元のマシンは止めない")
            release.countDown()
            h.tickUntilTrue { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            assertEquals(SaveReadiness.READY, m.saveReadiness())
            m.stop()
        }
    }

    @Test
    fun `待機点にいないブロッキング型はUNSAVEABLEで、自動保存の写しでも殺す`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val blocking = m.spawnTest(h.program(emptyModule()), gated(gate))
            val waiting = m.spawnTest(h.program(emptyModule()), gated(gate, kind = ProcessKind.WAIT_POINT))
            h.tickUntilTrue { m.stateOf(blocking) == ProcessState.WAITING_EVENT && m.stateOf(waiting) == ProcessState.WAITING_EVENT }
            assertEquals(SaveReadiness.UNSAVEABLE, m.saveReadiness())
            val copy = m.captureCopy(CapturePolicy.SKIP_IF_TRANSIENT)!!
            assertEquals(listOf(blocking), copy.killedPids)
            assertEquals(ProcessState.WAITING_EVENT, copy.processes[waiting]!!.state)
            assertEquals(pro.ikili.kibicomputers.core.persist.ColdBootReason.NONE, copy.coldBootReason)
            m.stop()
        }
    }

    @Test
    fun `プロセスのないマシンはREADY`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            assertEquals(SaveReadiness.READY, m.saveReadiness())
            val copy = m.captureCopy(CapturePolicy.SKIP_IF_TRANSIENT)!!
            assertEquals(emptyList<Int>(), copy.killedPids)
        }
    }
}
