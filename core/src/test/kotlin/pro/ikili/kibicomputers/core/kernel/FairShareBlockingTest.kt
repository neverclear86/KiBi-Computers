package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively
import pro.ikili.kibicomputers.core.config.ConfigEntry
import pro.ikili.kibicomputers.core.config.ConfigSource
import pro.ikili.kibicomputers.core.config.ConfigValues
import pro.ikili.kibicomputers.core.config.KiBiConfig
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.sched.FairShareGroup
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.persist.ModuleStore
import java.time.Duration
import java.util.UUID

/** ブロッキング型の枠の、公平の組（持ち主）ごとの上限と、組の鍵の差し替え（#738） */
class FairShareBlockingTest {
    private val ownerA = UUID(0, 1)
    private val ownerB = UUID(0, 2)
    private val ownerC = UUID(0, 3)

    private fun env(owner: UUID?): MachineEnvironment = MachineEnvironment.builder().fairnessKey(owner).build()

    private fun Machine.waiter(): TestDriver {
        val never = lock.withLockValue { kernel.objects.add(ByteStream(1)) }
        return TestDriver(ProcessKind.BLOCKING) { ctx ->
            ctx.block(listOf(WaitKey(never, WaitCondition.READABLE)))
            StepResult.Exit(0)
        }
    }

    private fun timeout(block: () -> Unit) = assertTimeoutPreemptively(Duration.ofSeconds(30), block)

    @Test
    fun `マシンの組は環境の鍵から決まり、差し替えられる。持ち主なしは1つの組`() = timeout {
        Harness().use { h ->
            val owned = h.machine(environment = env(ownerA))
            val none1 = h.machine()
            val none2 = h.machine(environment = env(null))
            assertEquals(FairShareGroup.of(ownerA), owned.fairShareGroup)
            assertSame(FairShareGroup.NONE, none1.fairShareGroup)
            assertEquals(none1.fairShareGroup, none2.fairShareGroup)
            owned.updateFairnessKey(ownerB)
            assertEquals(FairShareGroup.of(ownerB), owned.fairShareGroup)
            owned.updateFairnessKey(null)
            assertSame(FairShareGroup.NONE, owned.fairShareGroup)
        }
    }

    @Test
    fun `同じ持ち主のブロッキング型は組の上限で断り、予約した組へ返す`() = timeout {
        val config = SchedulerConfig.builder().maxConcurrentMachines(4).maxBlockingProcesses(64).maxBlockingProcessesPerGroup(1).build()
        Harness(config).use { h ->
            val a = FairShareGroup.of(ownerA)
            val b = FairShareGroup.of(ownerB)
            val m1 = h.machine(environment = env(ownerA))
            val m2 = h.machine(environment = env(ownerA))
            val m3 = h.machine(environment = env(ownerB))
            val program = h.program(emptyModule())
            m1.spawn(program, m1.waiter())
            assertEquals(1, h.scheduler.blockingOf(a))
            // 別の台でも同じ持ち主なら断る（1 台の上限と同じ例外）
            assertThrows<ResourceLimitException> { m2.spawn(program, m2.waiter()) }
            assertEquals(1, h.scheduler.blockingOf(a), "断ったら数を戻す")
            // 別の持ち主は取れる
            m3.spawn(program, m3.waiter())
            assertEquals(1, h.scheduler.blockingOf(b))
            // 持ち主が変わった台は、新しい組で取る
            m2.updateFairnessKey(ownerC)
            m2.spawn(program, m2.waiter())
            assertEquals(1, h.scheduler.blockingOf(FairShareGroup.of(ownerC)))
            // 動いている間に持ち主が変わっても、終わったら予約した組（a）に返す
            m1.updateFairnessKey(ownerB)
            m1.stop()
            assertTrue(m1.awaitTermination(5_000))
            assertEquals(0, h.scheduler.blockingOf(a))
            assertEquals(1, h.scheduler.blockingOf(b))
            for (m in listOf(m2, m3)) {
                m.stop()
                assertTrue(m.awaitTermination(5_000))
            }
            assertEquals(0, h.scheduler.blockingGroupCount, "0 になった組は消える")
        }
    }

    @Test
    fun `持ち主の組を使わない組み立てでは、持ち主なしの台はブロッキング型の全体の上限まで取れる`() = timeout {
        // #897: 1.20.1（持ち主の仕組みがない）は全台が NONE の1組。組ごとの項目が 1 でも、組の上限は掛けない
        Harness(KiBiConfig.schedulerConfig(limits(), ModuleStore.NONE, ownerGroups = false)).use { h ->
            val machines = List(5) { h.machine() }
            val program = h.program(emptyModule())
            for (m in machines.take(4)) m.spawn(program, m.waiter())
            assertEquals(0, h.scheduler.blockingOf(FairShareGroup.NONE), "組の数は数えない")
            // 全体の上限（4）は効く
            val last = machines[4]
            assertThrows<ResourceLimitException> { last.spawn(program, last.waiter()) }
            for (m in machines) {
                m.stop()
                assertTrue(m.awaitTermination(5_000))
            }
        }
    }

    @Test
    fun `持ち主の組を使う組み立てでは、持ち主なしの台も組の上限で断る`() = timeout {
        // ADR 0738（1.21.1）: 自動設置・FakePlayer の台（持ち主なし）も1組として上限に当たる。#897 の切り替えが既定を変えていないこと
        Harness(KiBiConfig.schedulerConfig(limits(), ModuleStore.NONE)).use { h ->
            val m1 = h.machine()
            val m2 = h.machine()
            val program = h.program(emptyModule())
            m1.spawn(program, m1.waiter())
            assertEquals(1, h.scheduler.blockingOf(FairShareGroup.NONE))
            assertThrows<ResourceLimitException> { m2.spawn(program, m2.waiter()) }
            for (m in listOf(m1, m2)) {
                m.stop()
                assertTrue(m.awaitTermination(5_000))
            }
            assertEquals(0, h.scheduler.blockingGroupCount)
        }
    }

    /** 全体のブロッキング型の上限 4、持ち主ごとの上限 1 のコンフィグ */
    private fun limits(): ConfigValues {
        val map = mapOf<ConfigEntry<*>, Any>(
            KiBiConfig.MAX_CONCURRENT_MACHINES to 4,
            KiBiConfig.MAX_BLOCKING_PROCESSES to 4,
            KiBiConfig.MAX_BLOCKING_PROCESSES_PER_OWNER to 1,
        )
        return ConfigValues.resolve(ConfigSource { map[it] })
    }

    @Test
    fun `サーバー全体の上限で断ったら、組の数も戻す`() = timeout {
        val config = SchedulerConfig.builder().maxConcurrentMachines(4).maxBlockingProcesses(1).maxBlockingProcessesPerGroup(8).build()
        Harness(config).use { h ->
            val m1 = h.machine(environment = env(ownerA))
            val m2 = h.machine(environment = env(ownerB))
            val program = h.program(emptyModule())
            m1.spawn(program, m1.waiter())
            assertThrows<ResourceLimitException> { m2.spawn(program, m2.waiter()) }
            assertEquals(0, h.scheduler.blockingOf(FairShareGroup.of(ownerB)))
            m1.stop()
            assertTrue(m1.awaitTermination(5_000))
            assertEquals(0, h.scheduler.blockingGroupCount)
        }
    }
}
