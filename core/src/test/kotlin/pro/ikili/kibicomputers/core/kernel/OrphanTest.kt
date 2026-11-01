package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.ChildSelector
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.ReapResult
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 親が終わったときの子と、親のないゾンビ・プロセスの上限（#46、ADR 0046） */
class OrphanTest {
    private val limit = Duration.ofSeconds(30)

    private fun Machine.tableSize(): Int = lock.withLockValue { kernel.processes.size }

    @Test
    fun `親が先に終わると子は親なしになり、動き続ける`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val program = h.program(emptyModule())
            val gate = Gate(m)
            val child = AtomicInteger()
            val parent = m.spawnTest(program, doing { ctx -> child.set(ctx.spawn(program, gated(gate, 4))) })
            h.tickUntilTrue { m.stateOf(parent).isTerminal && child.get() != 0 && m.stateOf(child.get()) == ProcessState.WAITING_EVENT }
            assertEquals(0, m.process(child.get())!!.parentPid)
            assertEquals(ProcessState.EXITED, m.stateOf(parent), "親のないゾンビは表に残る")
            gate.open()
            h.tickUntilTrue { m.stateOf(child.get()).isTerminal }
            assertEquals(ProcessState.EXITED, m.stateOf(child.get()))
            assertEquals(4, m.exitStatusOf(child.get())!!.code)
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `親が終わると、刈り取られていない子のゾンビは消える`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val program = h.program(emptyModule())
            val go = Gate(m)
            val child = AtomicInteger()
            val childObject = AtomicInteger()
            val parent = m.spawnTest(program, doing { ctx ->
                val c = ctx.spawn(program, exiting(0))
                childObject.set(ctx.withKernelLock { ctx.kernel.processes[c]!!.objectId })
                child.set(c)
                ctx.block(listOf(go.key))
            })
            h.tickUntilTrue { child.get() != 0 && m.stateOf(child.get()).isTerminal }
            go.open()
            h.tickUntilTrue { m.stateOf(parent).isTerminal }
            assertNull(m.processState(child.get()))
            m.lock.withLockValue { assertNull(m.kernel.objects[childObject.get()]) }
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `親のないゾンビは表がmaxProcessesの2倍に達すると古い順に刈り取られる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000, maxProcesses = 4)
            val program = h.program(emptyModule())
            val pids = (1..8).map {
                m.spawnTest(program, exiting(it)).also { pid -> h.tickUntilTrue { m.stateOf(pid).isTerminal } }
            }
            assertEquals(8, m.tableSize(), "上限に数えないので、上限を超えても作れる")
            for (pid in pids) assertEquals(ProcessState.EXITED, m.stateOf(pid), "表に残って状態を読める")
            val next = m.spawnTest(program, exiting(0))
            assertNull(m.processState(pids[0]), "いちばん古いゾンビが刈り取られる")
            assertEquals(ProcessState.EXITED, m.stateOf(pids[1]))
            assertEquals(8, m.tableSize())
            h.tickUntilTrue { m.stateOf(next).isTerminal }
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `子を作っては待たない親は、終わっていない子とゾンビの合計で上限になり、刈り取るとまた作れる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000, maxProcesses = 4)
            val program = h.program(emptyModule())
            val results = Collections.synchronizedList(ArrayList<Any>())
            val parent = m.spawnTest(program, doing { ctx ->
                val kids = (1..3).map { ctx.spawn(program, exiting(0)) }
                for (k in kids) ctx.block(ctx.childExitKeys(ChildSelector.Pid(k)))
                results += runCatching { ctx.spawn(program, exiting(0)) }.exceptionOrNull()?.javaClass ?: "spawned"
                results += ctx.tryReap(ChildSelector.Any).javaClass
                results += runCatching { ctx.spawn(program, exiting(0)) }.exceptionOrNull()?.javaClass ?: "spawned"
            })
            h.tickUntilTrue { m.stateOf(parent).isTerminal }
            assertEquals(listOf<Any>(ResourceLimitException::class.java, ReapResult.Reaped::class.java, "spawned"), results.toList())
            assertTrue(m.awaitTermination(5_000))
            // 親が終わったので、刈り取らなかった子のゾンビは残らない
            assertTrue(m.children(parent).isEmpty())
        }
    }
}
