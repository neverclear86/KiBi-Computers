package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.sched.PermitPool
import pro.ikili.kibicomputers.core.kernel.sched.WorkerPool
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.wait.WakeSink
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 許可を待つ間はスレッドを持たない（#350）。同時に動ける台が許可の数より多くても、ワーカーのスレッドは許可の数＋定数に収まる。
 * ワーカーは仕事がなくても [Harness] の既定（30 秒）まで残るので、プールの大きさはそれまでの最大の本数になる。
 */
class WorkerThreadsTest {
    private val permits = 2

    /** 許可の数を超えて増えてよい本数（step の終わりからプールに戻るまでの間に、別のスレッドが許可を返したときの分） */
    private val slack = 3

    private fun harness(): Harness =
        Harness(SchedulerConfig.builder().maxConcurrentMachines(permits).maxBlockingProcesses(8).build())

    @Test
    fun `200台が一斉に動き出しても、ワーカーは許可の数程度`() = timeout(Duration.ofSeconds(60)) {
        harness().use { h ->
            val program = h.program(emptyModule())
            val running = AtomicInteger()
            val maxRunning = AtomicInteger()
            val machines = (1..200).map { h.machine(fuelPerTick = 1000) }
            val pids = machines.map { m ->
                m.spawnTest(
                    program,
                    TestDriver(ProcessKind.WAIT_POINT) {
                        val n = running.incrementAndGet()
                        maxRunning.accumulateAndGet(n) { a, b -> maxOf(a, b) }
                        Thread.sleep(2)
                        running.decrementAndGet()
                        StepResult.Exit(0)
                    },
                )
            }
            h.scheduler.tick()
            eventually(30_000, "全部が終わらない") { machines.indices.all { machines[it].stateOf(pids[it]) == ProcessState.EXITED } }
            assertTrue(maxRunning.get() <= permits, "同時に動いた数: ${maxRunning.get()}")
            val pool = h.scheduler.workers.poolSize
            assertTrue(pool <= permits + slack, "ワーカーの数: $pool")
            assertTrue(kernelThreadNames("KiBi-worker-").size <= permits + slack)
            assertEquals(permits, h.scheduler.permits.availablePermits())
        }
    }

    @Test
    fun `動き続ける台が許可の数より多くても、ワーカーは許可の数程度`() = timeout(Duration.ofSeconds(60)) {
        harness().use { h ->
            val program = h.program(emptyModule())
            val count = 40
            val machines = (1..count).map { h.machine(fuelPerTick = 1000) }
            val streams = machines.map { m -> m.lock.withLockValue { ByteStream(16).also { m.kernel.objects.add(it) } } }
            val steps = AtomicInteger()
            val target = 2_000
            // 環に並べ、読んだら次の台に書いて起こす。10 個の印が回り続ける
            fun pass(i: Int) {
                val next = (i + 1) % count
                machines[next].lock.withLockValue { streams[next].write(byteArrayOf(1), 0, 1, machines[next].registry) }
            }
            val pids = machines.mapIndexed { i, m ->
                val key = WaitKey(streams[i].id, WaitCondition.READABLE)
                m.spawnTest(
                    program,
                    TestDriver(ProcessKind.WAIT_POINT, onResume = { ctx, ready ->
                        val buf = ByteArray(16)
                        val n = ctx.withKernelLock { (streams[i].read(buf, 0, buf.size, WakeSink.NONE) as? pro.ikili.kibicomputers.core.kernel.io.ReadResult.Bytes)?.count ?: 0 }
                        if (steps.incrementAndGet() >= target) {
                            StepResult.Exit(0)
                        } else {
                            Thread.sleep(1)
                            repeat(n) { pass(i) }
                            StepResult.Wait(ready)
                        }
                    }) { StepResult.Wait(listOf(key)) },
                )
            }
            h.scheduler.tick()
            eventually(30_000, "全部が待ちに入らない") { machines.indices.all { machines[it].stateOf(pids[it]) == ProcessState.WAITING_EVENT } }
            repeat(10) { pass(it * 4 + 3) }
            eventually(30_000, "回らない: ${steps.get()}") { steps.get() >= target }
            val pool = h.scheduler.workers.poolSize
            assertTrue(pool <= permits + slack, "ワーカーの数: $pool")
            machines.forEach { it.stop() }
        }
    }

    @Test
    fun `200台を一斉に起動しても、起動のスレッドは許可の数まで、ワーカーは作らない`() = timeout(Duration.ofSeconds(60)) {
        harness().use { h ->
            val maxBootThreads = AtomicInteger()
            val names = Collections.synchronizedSet(HashSet<String>())
            h.scheduler.hooks.bootVolumes = BootVolumeOpener { _ ->
                names += Thread.currentThread().name
                maxBootThreads.accumulateAndGet(kernelThreadNames("KiBi-boot-").size) { a, b -> maxOf(a, b) }
                Thread.sleep(2)
                null
            }
            val machines = (1..200).map { h.scheduler.createMachine(MachineHost { _, _, _ -> }, MachineConfig.builder().build()) }
            val config = BootConfig.builder().addBootDisk("d").build()
            machines.forEach { assertTrue(it.boot(config)) }
            eventually(30_000, "起動の手順が終わらない") { machines.none { it.isBooting } }
            assertTrue(names.all { it.startsWith("KiBi-boot-") }, names.toString())
            assertTrue(maxBootThreads.get() <= permits, "起動のスレッド: ${maxBootThreads.get()}")
            assertEquals(0, h.scheduler.workers.submitted.get(), "起動の手順はワーカーを使わない")
        }
    }

    @Test
    fun `許可はスレッドとスレッドを持たない仕事に、並んだ順に渡す`() = timeout(Duration.ofSeconds(10)) {
        val workers = WorkerPool(1L shl 20, 1_000, null)
        try {
            val pool = PermitPool(1, workers)
            pool.acquire()
            val order = Collections.synchronizedList(ArrayList<String>())
            val done = CountDownLatch(2)
            pool.submit("a") {
                order += "a"
                pool.release(inline = true)
                done.countDown()
            }
            val waiter = Thread {
                pool.acquire()
                order += "thread"
                pool.release()
                done.countDown()
            }.apply { start() }
            eventually { pool.waiting == 2 }
            pool.submit("removed") { order += "removed" }
            assertTrue(pool.remove("removed"))
            pool.release()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            waiter.join()
            assertEquals(listOf("a", "thread"), order)
            assertEquals(1, pool.availablePermits())
        } finally {
            workers.shutdown()
            workers.awaitTermination(5_000)
        }
    }
}
