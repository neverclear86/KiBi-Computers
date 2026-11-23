package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProgramLoadBusyException
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.sched.FairShareGroup
import pro.ikili.kibicomputers.core.runtime.FakeModule
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 公平の組（#738）の配線を、実機（[Machine]・`ProcessRunner`・[MachineScheduler]）で通す。
 * [pro.ikili.kibicomputers.core.kernel.sched.PermitPool] などの単体は FairPermitPoolTest 等が確かめるので、ここでは
 * 「マシンが並んだ・借りた組を控え、その組へ返すか」「スケジューラの設定が部品に渡るか」を見る
 */
class FairShareWiringTest {
    private val ownerA = UUID(0, 1)
    private val ownerB = UUID(0, 2)
    private val ownerC = UUID(0, 3)

    private fun env(owner: UUID?): MachineEnvironment = MachineEnvironment.builder().fairnessKey(owner).build()

    private fun timeout(block: () -> Unit) = assertTimeoutPreemptively(Duration.ofSeconds(60), block)

    /** 許可がすべて返り、表に組が残っていない */
    private fun MachineScheduler.assertPermitsSettled(total: Int) {
        eventually(10_000, "許可が戻らない: 空き ${permits.availablePermits()} / $total、待ち ${permits.waiting}、組 ${permits.groupCount}") {
            permits.availablePermits() == total && permits.waiting == 0 && permits.groupCount == 0
        }
    }

    @Test
    fun `待機点型のstepは、並んだときの組で許可を借り、持ち主が変わっても借りた組へ返す`() = timeout {
        val config = SchedulerConfig.builder().maxConcurrentMachines(1).maxBlockingProcesses(64).build()
        Harness(config).use { h ->
            val a = FairShareGroup.of(ownerA)
            val b = FairShareGroup.of(ownerB)
            val c = FairShareGroup.of(ownerC)
            val permits = h.scheduler.permits
            val m = h.machine(environment = env(ownerA))
            // 許可は 1 つ。先に借りておき、マシンの step を並ばせる
            permits.acquire(FairShareGroup.NONE)
            val seen = AtomicReference<List<Int>>()
            val pid = m.spawnTest(
                h.program(emptyModule()),
                TestDriver(ProcessKind.WAIT_POINT) {
                    seen.set(listOf(permits.heldBy(a), permits.heldBy(b), permits.heldBy(c)))
                    // 許可を借りている間に持ち主が変わる
                    m.updateFairnessKey(ownerC)
                    StepResult.Exit(0)
                },
            )
            h.tickUntilTrue("step が許可を待たない") { permits.waiting == 1 }
            // 並んでいる間に持ち主が変わる
            m.updateFairnessKey(ownerB)
            permits.release()
            h.tickUntilTrue("step が終わらない") { m.stateOf(pid).isTerminal }
            assertEquals(listOf(1, 0, 0), seen.get(), "並んだときの組（a）が借りる")
            h.scheduler.assertPermitsSettled(1)
            m.stop()
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `組ごとの同時実行の上限は、同じ持ち主の台の許可を待たせ、別の持ち主の台は通す`() = timeout {
        val config = SchedulerConfig.builder().maxConcurrentMachines(4).maxBlockingProcesses(64).maxConcurrentMachinesPerGroup(1).build()
        Harness(config).use { h ->
            val a = FairShareGroup.of(ownerA)
            val permits = h.scheduler.permits
            val m1 = h.machine(environment = env(ownerA))
            val m2 = h.machine(environment = env(ownerA))
            val m3 = h.machine(environment = env(ownerB))
            val gate = CountDownLatch(1)
            val started = CountDownLatch(1)
            val first = m1.spawnTest(
                h.program(emptyModule()),
                TestDriver(ProcessKind.WAIT_POINT) {
                    started.countDown()
                    gate.await()
                    StepResult.Exit(0)
                },
            )
            try {
                h.tickUntilTrue("1 つ目の step が始まらない") { started.count == 0L }
                val second = m2.spawnTest(h.program(emptyModule()), TestDriver(ProcessKind.WAIT_POINT) { StepResult.Exit(0) })
                val third = m3.spawnTest(h.program(emptyModule()), TestDriver(ProcessKind.WAIT_POINT) { StepResult.Exit(0) })
                h.tickUntilTrue("別の持ち主の step が通らない") { m3.stateOf(third).isTerminal }
                // 許可は空いているのに、同じ持ち主の 2 台目は組の上限で待つ
                h.tickUntilTrue("2 台目が並ばない") { permits.waiting == 1 }
                assertEquals(1, permits.heldBy(a))
                assertFalse(m2.stateOf(second).isTerminal)
                gate.countDown()
                h.tickUntilTrue("2 台目が進まない") { m2.stateOf(second).isTerminal && m1.stateOf(first).isTerminal }
                h.scheduler.assertPermitsSettled(4)
            } finally {
                gate.countDown()
            }
            for (m in listOf(m1, m2, m3)) {
                m.stop()
                assertTrue(m.awaitTermination(5_000))
            }
        }
    }

    @Test
    fun `ブロッキング型のプロセスも、許可を借りた組へ返す`() = timeout {
        val config = SchedulerConfig.builder().maxConcurrentMachines(2).maxBlockingProcesses(64).build()
        Harness(config).use { h ->
            val a = FairShareGroup.of(ownerA)
            val permits = h.scheduler.permits
            val m = h.machine(environment = env(ownerA), fuelPerTick = 1_000_000)
            val seen = AtomicReference<List<Int>>()
            val module = FakeModule.builder().fuelMetered().export("run", VOID) { ctx, _ ->
                ctx.step(1)
                seen.set(listOf(permits.heldBy(a), h.scheduler.blockingOf(a)))
                // 許可と枠を借りている間に持ち主が変わる
                m.updateFairnessKey(ownerB)
                ctx.step(1)
                LongArray(0)
            }.build()
            val pid = m.spawnTest(h.program(module), runDriver())
            h.tickUntilTrue("プロセスが終わらない") { m.stateOf(pid).isTerminal }
            assertEquals(listOf(1, 1), seen.get(), "借りている間は、組 a の許可と枠が 1 つずつ")
            h.scheduler.assertPermitsSettled(2)
            eventually(10_000, "ブロッキング型の枠が戻らない") { h.scheduler.blockingGroupCount == 0 }
            m.stop()
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `許可の組ごとの上限は、0なら上限なし、1以上ならその数でPermitPoolに渡る`() = timeout {
        for ((limit, immediate) in listOf(0 to 4, 2 to 2)) {
            val config = SchedulerConfig.builder().maxConcurrentMachines(4).maxBlockingProcesses(64).maxConcurrentMachinesPerGroup(limit).build()
            Harness(config).use { h ->
                val a = FairShareGroup.of(ownerA)
                val permits = h.scheduler.permits
                val ran = AtomicInteger()
                repeat(4) { i -> permits.submit(i, a) { ran.incrementAndGet() } }
                eventually(10_000, "上限 $limit: すぐ動く仕事が $immediate 個にならない") { ran.get() == immediate }
                Thread.sleep(50)
                assertEquals(immediate, ran.get(), "上限 $limit")
                assertEquals(immediate, permits.heldBy(a), "上限 $limit")
                assertEquals(4 - immediate, permits.waiting, "上限 $limit")
                repeat(4) {
                    eventually(10_000, "上限 $limit: 許可を借りた仕事がない") { permits.heldBy(a) > 0 }
                    permits.release(a)
                }
                eventually(10_000, "上限 $limit: 仕事が全部動かない") { ran.get() == 4 }
                h.scheduler.assertPermitsSettled(4)
            }
        }
    }

    @Test
    fun `読み込みの組ごとの上限は、0なら上限なし、1以上ならその数でProgramCacheに渡る`() = timeout {
        for ((limit, accepted) in listOf(0 to 20, 3 to 3)) {
            val config = SchedulerConfig.builder().maxConcurrentMachines(2).maxBlockingProcesses(64).compilerThreads(1).maxPendingLoadsPerGroup(limit).build()
            Harness(config).use { h ->
                val a = FairShareGroup.of(ownerA)
                val programs = h.scheduler.programs
                val gate = CountDownLatch(1)
                try {
                    // 台ごとの上限（8）に当たらないように、台を分けて頼む
                    val futures: List<CompletableFuture<*>> = (0 until 20).map { i ->
                        programs.load(i.toLong(), a, true, "load-$i".toByteArray(), true, emptySet()) {
                            gate.await()
                            throw WasmLoadException("held")
                        }
                    }
                    val busy = futures.count { f -> f.isCompletedExceptionally && runCatching { f.get() }.exceptionOrNull()?.cause is ProgramLoadBusyException }
                    assertEquals(20 - accepted, busy, "上限 $limit")
                    assertEquals(accepted, programs.pendingOfGroup(a), "上限 $limit")
                } finally {
                    gate.countDown()
                }
                eventually(10_000, "上限 $limit: 読み込みが終わらない") { programs.pendingGroupCount == 0 }
            }
        }
    }
}
