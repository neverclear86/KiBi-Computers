package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.SaveReadiness
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import pro.ikili.kibicomputers.core.testing.bodyTimeout as timeout

/** マシン1台だけを止める口（`Machine.quiesce`）と、保存できるかの判定（`Machine.saveReadiness`）のテスト（#81） */
@Timeout(10, unit = TimeUnit.MINUTES) // 止まったときの保険（合否の基準ではない。#916）
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

    /**
     * 止めている間は、起こされた待機点型は先へ進まない（CPU の許可が取れず、終わらない）。ここで確かめるのは、
     * 進まないことと、最後に READY（待機点）へ戻ることだけ。途中で step を一度だけ始めて燃料待ちへ戻る一瞬が
     * あるかどうかは問わない（その窓は次のテストが固定する）
     */
    @Test
    fun `止めている間は起こされた待機点型は先へ進まず、最後はREADYに戻る`() = timeout(limit) {
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

    /**
     * 止めた後に起こされると、ワーカーが CPU を受け取って step を1回だけ始め（`rt.thread` を登録する）、許可が取れずに
     * 燃料待ちへ戻る（#1020）。その間はスレッドを持つので待機点ではなく TRANSIENT（ADR 0043、`isAtWaitPointLocked`）。
     * 起こした直後（`thread == null` の `RUNNABLE`）に待機点を見ても、写しを取るまでにこの窓に入りうるので、保存するテストは
     * 写しが取れるまで待つ。ここでは step の始めで止めて、窓を決まった順で作る。
     *
     * このテストは今の振る舞い（止めた後でも、起こされた待機点型は step を1回始める）を前提にして、その窓を再現する。
     * 製品を「止めている間は step を割り当てない」に変えたら（窓がなくなる）、`beforeStepStart` が呼ばれず
     * 「起こされた step が始まらない」で落ちるので、そのときは窓の再現ごとこのテストを消すか、前提を直す
     */
    @Test
    fun `止めた後に起こされた待機点型は、stepを始めて燃料待ちに戻るまでの一瞬だけTRANSIENTで、待てばREADYに戻る`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val pid = m.spawnTest(h.program(emptyModule()), gated(gate, code = 3, kind = ProcessKind.WAIT_POINT))
            h.tickUntilTrue { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            val handle = m.quiesce()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            h.releaseOnClose { release.countDown() }
            h.scheduler.hooks.beforeStepStart = { machine, p ->
                if (machine === m && p == pid) {
                    entered.countDown()
                    release.await()
                }
            }
            try {
                gate.open()
                h.tickUntilTrue("起こされた step が始まらない") { entered.count == 0L }
                assertEquals(SaveReadiness.TRANSIENT, handle.readiness, "step を始めた一瞬は待機点ではない")
                assertNull(m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT))
            } finally {
                h.scheduler.hooks.beforeStepStart = null
                release.countDown()
            }
            // 止めているので許可は取れず、スレッドを持たない RUNNABLE（待機点）に戻る
            var snapshot: Any? = null
            h.tickUntilTrue("写しが取れない") {
                snapshot = m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT)
                snapshot != null
            }
            assertEquals(ProcessState.RUNNABLE, m.stateOf(pid))
            assertEquals(SaveReadiness.READY, handle.readiness)
            assertTrue(m.isAtWaitPoint)
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
                    release.await()
                    StepResult.Wait(listOf(gate.key))
                },
            )
            h.releaseOnClose { release.countDown() }
            h.scheduler.tick()
            entered.awaitOrFail()
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
