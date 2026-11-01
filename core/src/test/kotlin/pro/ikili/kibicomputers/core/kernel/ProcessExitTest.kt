package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.ChildSelector
import pro.ikili.kibicomputers.core.kernel.process.ExitReason
import pro.ikili.kibicomputers.core.kernel.process.ExitStatus
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.ReapResult
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 終了の記録と終了待ち（刈り取り）（#46、ADR 0046） */
class ProcessExitTest {
    private val limit = Duration.ofSeconds(30)

    @Test
    fun `子の終了コードを刈り取ると表から消える（32ビットそのまま）`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val program = h.program(emptyModule())
            val results = Collections.synchronizedList(ArrayList<ReapResult>())
            val objectIds = Collections.synchronizedList(ArrayList<Int>())
            val parent = m.spawnTest(program, doing { ctx ->
                for (code in listOf(3, -1, Int.MAX_VALUE)) {
                    val child = ctx.spawn(program, exiting(code))
                    objectIds += ctx.withKernelLock { ctx.kernel.processes[child]!!.objectId }
                    results += ctx.waitChild(ChildSelector.Pid(child))
                }
            })
            h.tickUntilTrue { m.stateOf(parent).isTerminal }
            assertEquals(ProcessState.EXITED, m.stateOf(parent))
            val statuses = results.map { (it as ReapResult.Reaped).status }
            assertEquals(listOf(3, -1, Int.MAX_VALUE), statuses.map { it.code })
            assertTrue(statuses.all { it.reason == ExitReason.EXITED && it.signal == null })
            for (s in statuses) {
                assertNull(m.processState(s.pid), "刈り取った子は表から消える")
                assertNull(m.runtime(s.pid))
            }
            m.lock.withLockValue { for (id in objectIds) assertNull(m.kernel.objects[id], "ProcessObject も外れる") }
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `ブロッキング型の親はwaitChildで子が終わるまで待ち、その間CPUを返す`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val program = h.program(emptyModule())
            val gate = Gate(m)
            val childPid = AtomicInteger()
            val result = AtomicReference<ReapResult>()
            val parent = m.spawnTest(program, doing { ctx ->
                childPid.set(ctx.spawn(program, gated(gate, 5)))
                result.set(ctx.waitChild(ChildSelector.Any))
            })
            h.tickUntilTrue { childPid.get() != 0 && m.stateOf(parent) == ProcessState.WAITING_EVENT && m.stateOf(childPid.get()) == ProcessState.WAITING_EVENT }
            // 親が待っている間も、同じマシンのほかのプロセスが動ける
            val other = m.spawnTest(program, exiting(0))
            h.tickUntilTrue { m.stateOf(other).isTerminal }
            assertNull(result.get())
            gate.open()
            h.tickUntilTrue { result.get() != null }
            assertEquals(ReapResult.Reaped(ExitStatus(childPid.get(), ExitReason.EXITED, 5, null)), result.get())
            h.tickUntilTrue { m.stateOf(parent).isTerminal }
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `待機点型の親はchildExitKeysで待ち、待っている間は待機点にいる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val program = h.program(emptyModule())
            val gate = Gate(m)
            val childPid = AtomicInteger()
            val result = AtomicReference<ReapResult>()
            val parent = m.spawnTest(program, TestDriver(
                ProcessKind.WAIT_POINT,
                onResume = { ctx, _ ->
                    when (val r = ctx.tryReap(ChildSelector.Any)) {
                        ReapResult.Running -> StepResult.Wait(ctx.childExitKeys(ChildSelector.Any))
                        else -> {
                            result.set(r)
                            StepResult.Exit(0)
                        }
                    }
                },
            ) { ctx ->
                childPid.set(ctx.spawn(program, gated(gate, 7, ProcessKind.WAIT_POINT)))
                StepResult.Wait(ctx.childExitKeys(ChildSelector.Any))
            })
            h.tickUntilTrue { m.stateOf(parent) == ProcessState.WAITING_EVENT && childPid.get() != 0 && m.stateOf(childPid.get()) == ProcessState.WAITING_EVENT }
            assertTrue(m.isAtWaitPoint)
            gate.open()
            h.tickUntilTrue { m.stateOf(parent).isTerminal }
            assertEquals(ReapResult.Reaped(ExitStatus(childPid.get(), ExitReason.EXITED, 7, null)), result.get())
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `Anyは終わった順ではなくpidの古い順に刈り取る`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val program = h.program(emptyModule())
            val gateA = Gate(m)
            val gateB = Gate(m)
            val go = Gate(m)
            val kids = Collections.synchronizedList(ArrayList<Int>())
            val results = Collections.synchronizedList(ArrayList<ReapResult>())
            val parent = m.spawnTest(program, doing { ctx ->
                kids += ctx.spawn(program, gated(gateA, 1))
                kids += ctx.spawn(program, gated(gateB, 2))
                ctx.block(listOf(go.key))
                repeat(3) { results += ctx.tryReap(ChildSelector.Any) }
            })
            h.tickUntilTrue { kids.size == 2 && m.stateOf(parent) == ProcessState.WAITING_EVENT }
            gateB.open()
            h.tickUntilTrue { m.stateOf(kids[1]).isTerminal }
            gateA.open()
            h.tickUntilTrue { m.stateOf(kids[0]).isTerminal }
            go.open()
            h.tickUntilTrue { m.stateOf(parent).isTerminal }
            assertEquals(
                listOf(
                    ReapResult.Reaped(ExitStatus(kids[0], ExitReason.EXITED, 1, null)),
                    ReapResult.Reaped(ExitStatus(kids[1], ExitReason.EXITED, 2, null)),
                    ReapResult.NoChild,
                ),
                results.toList(),
            )
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `子がいない・自分の子でないならNoChild、終わっていなければRunning`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val program = h.program(emptyModule())
            val gate = Gate(m)
            val stranger = m.spawnTest(program, gated(gate))
            val results = Collections.synchronizedList(ArrayList<Any>())
            val parent = m.spawnTest(program, doing { ctx ->
                results += ctx.tryReap(ChildSelector.Any)
                results += ctx.waitChild(ChildSelector.Any)
                results.add(ctx.childExitKeys(ChildSelector.Any))
                val child = ctx.spawn(program, gated(gate))
                results += ctx.tryReap(ChildSelector.Pid(child))
                results += ctx.tryReap(ChildSelector.Any)
                results += ctx.tryReap(ChildSelector.Pid(9999))
                results += ctx.tryReap(ChildSelector.Pid(ctx.pid))
                results += ctx.tryReap(ChildSelector.Pid(stranger))
                results.add(ctx.childExitKeys(ChildSelector.Pid(stranger)))
                results += ctx.childExitKeys(ChildSelector.Pid(child)).size
            })
            h.tickUntilTrue { results.size == 10 }
            assertEquals(
                listOf<Any>(
                    ReapResult.NoChild, ReapResult.NoChild, emptyList<Any>(),
                    ReapResult.Running, ReapResult.Running, ReapResult.NoChild, ReapResult.NoChild, ReapResult.NoChild,
                    emptyList<Any>(), 1,
                ),
                results.toList(),
            )
            gate.open()
            h.tickUntilTrue { m.stateOf(parent).isTerminal && m.stateOf(stranger).isTerminal }
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `例外で終わった子はCRASHED`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val program = h.program(emptyModule())
            val result = AtomicReference<ReapResult>()
            val childPid = AtomicInteger()
            m.spawnTest(program, doing { ctx ->
                childPid.set(ctx.spawn(program, TestDriver(ProcessKind.BLOCKING) { throw IllegalStateException("boom") }))
                result.set(ctx.waitChild(ChildSelector.Pid(childPid.get())))
            })
            h.tickUntilTrue { result.get() != null }
            assertEquals(ReapResult.Reaped(ExitStatus(childPid.get(), ExitReason.CRASHED, 0, null)), result.get())
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `子を作っては刈り取ることを再起動なしでくり返しても、終わる途中のスレッドの参照は増え続けない`() = timeout(Duration.ofSeconds(60)) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val program = h.program(emptyModule())
            val done = AtomicBoolean(false)
            val maxRetired = AtomicInteger()
            val parent = m.spawnTest(program, doing { ctx ->
                repeat(200) {
                    val child = ctx.spawn(program, exiting(0))
                    check(ctx.waitChild(ChildSelector.Pid(child)) is ReapResult.Reaped)
                    maxRetired.accumulateAndGet(m.retiredThreadCount, ::maxOf)
                }
                done.set(true)
            })
            h.tickUntilTrue { m.stateOf(parent).isTerminal }
            assertTrue(done.get())
            assertTrue(maxRetired.get() <= 8, "retiredThreads が増え続けている: ${maxRetired.get()}")
            assertTrue(m.retiredThreadCount <= 8, "retiredThreads: ${m.retiredThreadCount}")
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `stopで止まったプロセスの記録はKILLEDでシグナルなし`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val program = h.program(emptyModule())
            val pid = m.spawnTest(program, gated(Gate(m)))
            h.tickUntilTrue { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            m.stop()
            assertTrue(m.awaitTermination(5_000))
            assertEquals(ExitStatus(pid, ExitReason.KILLED, 0, null), m.exitStatusOf(pid))
            assertNull(m.process(pid)!!.termSignal)
        }
    }
}
