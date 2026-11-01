package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.event.EventFilter
import pro.ikili.kibicomputers.core.kernel.event.KernelEvent
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 規模の目標（目安 1,000 台）。時間では確かめず、内部の数え口で確かめる。 */
class ScaleTest {
    @Test
    fun `1000台の待っているだけのマシンはスレッドを持たず、tickは通知のないマシンを見ない`() = timeout(Duration.ofSeconds(60)) {
        Harness(workerIdleMillis = 200).use { h ->
            val program = h.program(emptyModule())
            val resumes = AtomicInteger()
            val machines = (1..1000).map { h.machine(fuelPerTick = 1000) }
            val streams = machines.map { m -> m.lock.withLockValue { ByteStream(16).also { m.kernel.objects.add(it) } } }
            val pids = machines.mapIndexed { i, m ->
                val key = WaitKey(streams[i].id, WaitCondition.READABLE)
                m.spawnTest(
                    program,
                    TestDriver(ProcessKind.WAIT_POINT, onResume = { ctx, ready ->
                        ctx.withKernelLock { (ctx.kernel.objects[key.objectId] as ByteStream).read(ByteArray(16), 0, 16, pro.ikili.kibicomputers.core.kernel.wait.WakeSink.NONE) }
                        resumes.incrementAndGet()
                        StepResult.Wait(ready)
                    }) { StepResult.Wait(listOf(key)) },
                )
            }
            h.scheduler.tick()
            eventually(30_000, "全部が待ちに入らない") { machines.indices.all { machines[it].stateOf(pids[it]) == ProcessState.WAITING_EVENT } }
            assertTrue(machines.all { it.isAtWaitPoint })
            assertTrue(kernelThreadNames("KiBi-m").isEmpty())
            eventually { h.scheduler.workers.activeSteps.get() == 0 }
            eventually(10_000, "ワーカーが減らない: ${h.scheduler.workers.poolSize}") { h.scheduler.workers.poolSize == 0 }
            h.scheduler.tick() // 溜まっている通知（RUNNING）を配り切る
            val visited = h.scheduler.tickVisitedMachines.get()
            val woken = h.scheduler.dispatcher.woken.get()
            repeat(100) { h.scheduler.tick() }
            assertEquals(visited, h.scheduler.tickVisitedMachines.get(), "通知のないマシンは見ない")
            assertEquals(woken, h.scheduler.dispatcher.woken.get(), "燃料待ちを起こさない")
            // 1台だけを readable にすると、その台だけが動いて Wait に戻る
            val target = 500
            val submitted = h.scheduler.workers.submitted.get()
            machines[target].lock.withLockValue { streams[target].write(byteArrayOf(1), 0, 1, machines[target].registry) }
            eventually { resumes.get() == 1 && h.scheduler.workers.activeSteps.get() == 0 && machines[target].stateOf(pids[target]) == ProcessState.WAITING_EVENT }
            assertEquals(submitted + 1, h.scheduler.workers.submitted.get())
        }
    }

    @Test
    fun `1000台がタイマーを待つ間はスレッドを持たず、期限を待つスレッドはKiBi-timerの1本だけ`() = timeout(Duration.ofSeconds(60)) {
        Harness(workerIdleMillis = 200).use { h ->
            val program = h.program(emptyModule())
            val machines = (1..1000).map { h.machine(fuelPerTick = 1000) }
            val pids = machines.map { m ->
                val handle = AtomicInteger(-1)
                m.spawnTest(
                    program,
                    TestDriver(ProcessKind.WAIT_POINT, onResume = { ctx, ready ->
                        if (ctx.readTimer(handle.get()) == 0L) StepResult.Wait(ready) else StepResult.Exit(0)
                    }) { ctx ->
                        handle.set(ctx.createTimer())
                        ctx.setTimer(handle.get(), 1_500_000_000L)
                        StepResult.Wait(listOf(ctx.timerWaitKey(handle.get())))
                    },
                )
            }
            h.scheduler.tick()
            eventually(30_000, "全部が待ちに入らない") { machines.indices.all { machines[it].stateOf(pids[it]).let { s -> s == ProcessState.WAITING_EVENT || s.isTerminal } } }
            assertTrue(kernelThreadNames("KiBi-m").isEmpty())
            assertEquals(1, kernelThreadNames("KiBi-timer").size)
            eventually(30_000, "全部が鳴って終わらない") { machines.indices.all { machines[it].stateOf(pids[it]) == ProcessState.EXITED } }
            assertTrue(machines.all { m -> m.lock.withLockValue { m.timers.scheduledCount } == 0 })
        }
    }

    @Test
    fun `1000台がイベントキューを待っている間はスレッドを持たず、tickは見ず、全台に出すと全台がresumeされる`() = timeout(Duration.ofSeconds(60)) {
        Harness(workerIdleMillis = 200).use { h ->
            val program = h.program(emptyModule())
            val resumes = AtomicInteger()
            val machines = (1..1000).map { h.machine(fuelPerTick = 1000) }
            val pids = machines.map { m ->
                var handle = -1
                m.spawnTest(
                    program,
                    TestDriver(ProcessKind.WAIT_POINT, onResume = { ctx, _ ->
                        ctx.takeEvents(handle)
                        resumes.incrementAndGet()
                        StepResult.Wait(listOf(ctx.eventWaitKey(handle)))
                    }) { ctx ->
                        handle = ctx.openEventQueue(EventFilter(setOf("ping")))
                        StepResult.Wait(listOf(ctx.eventWaitKey(handle)))
                    },
                )
            }
            h.scheduler.tick()
            eventually(30_000, "全部が待ちに入らない") { machines.indices.all { machines[it].stateOf(pids[it]) == ProcessState.WAITING_EVENT } }
            assertTrue(machines.all { it.isAtWaitPoint })
            assertTrue(kernelThreadNames("KiBi-m").isEmpty())
            eventually { h.scheduler.workers.activeSteps.get() == 0 }
            h.scheduler.tick()
            val visited = h.scheduler.tickVisitedMachines.get()
            repeat(100) { h.scheduler.tick() }
            assertEquals(visited, h.scheduler.tickVisitedMachines.get(), "待っているだけのマシンは見ない")
            // 種類の合わないイベントでは誰も動かない
            val submitted = h.scheduler.workers.submitted.get()
            machines.forEach { assertEquals(0, it.postEvent(KernelEvent("other"))) }
            assertEquals(submitted, h.scheduler.workers.submitted.get())
            machines.forEach { assertEquals(1, it.postEvent(KernelEvent("ping"))) }
            eventually(30_000, "全台が resume されない: ${resumes.get()}") {
                resumes.get() == 1000 && machines.indices.all { machines[it].stateOf(pids[it]) == ProcessState.WAITING_EVENT }
            }
            assertTrue(kernelThreadNames("KiBi-m").isEmpty())
        }
    }

    @Test
    fun `ブロッキング型のプロセスはmaxBlockingProcessesまで`() = timeout(Duration.ofSeconds(30)) {
        Harness(SchedulerConfig.builder().maxBlockingProcesses(8).build()).use { h ->
            val m = h.machine()
            val s = m.lock.withLockValue { ByteStream(16).also { m.kernel.objects.add(it) } }
            val program = h.program(hostCallModule())
            val driver = runDriver(configure = { ctx, b -> b.host(ctx) { c, _, _ -> c.block(listOf(WaitKey(s.id, WaitCondition.READABLE))); LongArray(0) } })
            val pids = (1..8).map { m.spawnTest(program, driver) }
            assertThrows<ResourceLimitException> { m.spawnTest(program, driver) }
            h.scheduler.tick()
            eventually { pids.all { m.stateOf(it) == ProcessState.WAITING_EVENT } }
            m.lock.withLockValue { s.closeWrite(m.registry) }
            eventually { pids.all { m.stateOf(it).isTerminal } }
            m.spawnTest(h.program(emptyModule()), TestDriver(ProcessKind.BLOCKING) { StepResult.Exit(0) })
            m.stop()
        }
    }
}
