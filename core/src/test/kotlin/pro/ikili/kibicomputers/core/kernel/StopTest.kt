package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.runtime.FakeModule
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

class StopTest {
    private val limit = Duration.ofSeconds(30)

    private fun stream(machine: Machine): ByteStream = machine.lock.withLockValue { ByteStream(16).also { machine.kernel.objects.add(it) } }

    private fun unmeteredSpin(): FakeModule = FakeModule.builder().export("run", VOID) { ctx, _ ->
        while (true) ctx.step(1)
        @Suppress("UNREACHABLE_CODE")
        LongArray(0)
    }.build()

    @Test
    fun `stopでどの状態のプロセスもKILLEDになり、スレッドが残らない`() = timeout(limit) {
        Harness().use { h ->
            val m1 = h.machine(fuelPerTick = 1_000_000_000_000)
            val m2 = h.machine()
            val m3 = h.machine(fuelPerTick = 10)
            val s = stream(m3)
            val key = WaitKey(s.id, WaitCondition.READABLE)
            val progress = AtomicInteger()
            val computing = m1.spawnTest(h.program(spinForever(progress)), runDriver())
            h.scheduler.tick()
            eventually { progress.get() > 0 }
            val cpuWaiting = m1.spawnTest(h.program(spinForever()), runDriver())
            val unmetered = m2.spawnTest(h.program(unmeteredSpin()), runDriver(), unmetered = true)
            val fuelWaiting = m3.spawnTest(h.program(spinForever()), runDriver())
            h.scheduler.tick()
            eventually { m3.stateOf(fuelWaiting) == ProcessState.WAITING_FUEL }
            h.scheduler.tick()
            val blocking = m3.spawnTest(h.program(hostCallModule()), runDriver(configure = { ctx, b -> b.host(ctx) { c, _, _ -> c.block(listOf(key)); LongArray(0) } }))
            val waitPoint = m3.spawnTest(h.program(emptyModule()), TestDriver(ProcessKind.WAIT_POINT) { StepResult.Wait(listOf(key)) })
            val hostWaiting = m3.spawnTest(h.program(hostCallModule()), runDriver(configure = { ctx, b -> b.host(ctx) { c, _, _ -> c.awaitHost(CompletableFuture<Unit>()); LongArray(0) } }))
            eventually(10_000, "待ちに入らない") {
                h.scheduler.tick()
                Thread.sleep(2)
                m3.stateOf(blocking) == ProcessState.WAITING_EVENT &&
                    m3.stateOf(waitPoint) == ProcessState.WAITING_EVENT &&
                    m3.stateOf(hostWaiting) == ProcessState.WAITING_HOST
            }
            eventually { h.scheduler.activeProcesses >= 2 }
            val machines = listOf(m1, m2, m3)
            machines.forEach { it.stop() }
            machines.forEach { assertTrue(it.awaitTermination(5_000), "$it") }
            for ((m, pid) in listOf(m1 to computing, m1 to cpuWaiting, m2 to unmetered, m3 to fuelWaiting, m3 to blocking, m3 to waitPoint, m3 to hostWaiting)) {
                assertState(ProcessState.KILLED, m, pid)
            }
            machines.forEach { assertEquals(MachineState.STOPPED, it.state) }
            assertTrue(kernelThreadNames("KiBi-m").isEmpty(), kernelThreadNames("KiBi-m").toString())
            h.events.clear()
            h.scheduler.tick()
            assertTrue(h.events.contains(m1.id to MachineState.STOPPED), h.events.toString())
            assertEquals(h.scheduler.config.maxConcurrentMachines, h.scheduler.permits.availablePermits())
        }
    }

    @Test
    fun `stopはホスト関数の中・onStateChangedの中から呼んでもデッドロックしない`() = timeout(limit) {
        Harness().use { h ->
            val fromHost = h.machine()
            val pid = fromHost.spawnTest(
                h.program(FakeModule.builder().fuelMetered().import("env", "h", VOID).export("run", VOID) { ctx, _ ->
                    ctx.callImport("env", "h")
                    while (true) ctx.step(1)
                    @Suppress("UNREACHABLE_CODE")
                    LongArray(0)
                }.build()),
                runDriver(configure = { ctx, b ->
                    b.host(ctx) { c, _, _ ->
                        val err = runCatching { fromHost.awaitTermination(10) }.exceptionOrNull()
                        assertTrue(err is IllegalStateException, "$err")
                        fromHost.stop()
                        LongArray(0)
                    }
                }),
            )
            h.scheduler.tick()
            assertTrue(fromHost.awaitTermination(5_000))
            assertState(ProcessState.KILLED, fromHost, pid)

            val tickThread = Thread.currentThread()
            val checked = AtomicReference<String>()
            lateinit var fromCallback: Machine
            fromCallback = h.machine(host = { m, state, _ ->
                if (state == MachineState.RUNNING) {
                    checked.set(
                        listOf(Thread.currentThread() === tickThread, !m.lock.isHeldByCurrentThread, !KernelThreadsProbe.isKernel()).toString(),
                    )
                    m.stop()
                }
            })
            val p2 = fromCallback.spawnTest(h.program(spinForever()), runDriver())
            h.scheduler.tick()
            assertEquals("[true, true, true]", checked.get())
            assertTrue(fromCallback.awaitTermination(5_000))
            assertState(ProcessState.KILLED, fromCallback, p2)
        }
    }

    @Test
    fun `停止の印がないトラップではそのプロセスだけがCRASHEDになる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1000)
            val other = h.machine(fuelPerTick = 1000)
            val p1 = AtomicInteger()
            val p2 = AtomicInteger()
            m.spawnTest(h.program(spinForever(p1)), runDriver())
            other.spawnTest(h.program(spinForever(p2)), runDriver())
            val trap = m.spawnTest(h.program(FakeModule.builder().fuelMetered().export("run", VOID) { ctx, _ -> ctx.trap(WasmTrapKind.UNREACHABLE) }.build()), runDriver())
            eventually {
                h.scheduler.tick()
                Thread.sleep(2)
                m.stateOf(trap).isTerminal
            }
            assertState(ProcessState.CRASHED, m, trap)
            val a = p1.get()
            val b = p2.get()
            eventually {
                h.scheduler.tick()
                Thread.sleep(2)
                p1.get() > a && p2.get() > b
            }
            assertEquals(MachineState.RUNNING, m.state)
            m.stop()
            other.stop()
        }
    }

    @Test
    fun `CPUと許可を返して待っているところを止めても、許可を2回返さない`() = timeout(limit) {
        Harness(SchedulerConfig.builder().maxConcurrentMachines(2).build()).use { h ->
            val m = h.machine(fuelPerTick = 10)
            val s = stream(m)
            val fuelWait = m.spawnTest(h.program(spinForever()), runDriver())
            h.scheduler.tick()
            eventually { m.stateOf(fuelWait) == ProcessState.WAITING_FUEL }
            h.scheduler.tick()
            val blocked = m.spawnTest(h.program(hostCallModule()), runDriver(configure = { ctx, b -> b.host(ctx) { c, _, _ -> c.block(listOf(WaitKey(s.id, WaitCondition.READABLE))); LongArray(0) } }))
            val host = m.spawnTest(h.program(hostCallModule()), runDriver(configure = { ctx, b -> b.host(ctx) { c, _, _ -> c.awaitHost(CompletableFuture<Unit>()); LongArray(0) } }))
            eventually {
                h.scheduler.tick()
                Thread.sleep(2)
                m.stateOf(blocked) == ProcessState.WAITING_EVENT && m.stateOf(host) == ProcessState.WAITING_HOST && m.stateOf(fuelWait) == ProcessState.WAITING_FUEL
            }
            m.stop()
            assertTrue(m.awaitTermination(5_000))
            listOf(fuelWait, blocked, host).forEach { assertState(ProcessState.KILLED, m, it) }
            assertEquals(2, h.scheduler.permits.availablePermits())
            assertEquals(null, m.cpu.currentOwner)
        }
    }

    @Test
    fun `スレッドを持たない待機点型のプロセスはstopでその場でKILLEDになる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 100)
            val s = stream(m)
            val key = WaitKey(s.id, WaitCondition.READABLE)
            val waiting = m.spawnTest(h.program(emptyModule()), TestDriver(ProcessKind.WAIT_POINT) { StepResult.Wait(listOf(key)) })
            h.scheduler.tick()
            eventually { m.stateOf(waiting) == ProcessState.WAITING_EVENT }
            // starving は燃料がないまま境目で待たせる
            m.lock.withLockValue { m.kernel.fuel = -10_000 }
            val starving = m.spawnTest(h.program(emptyModule()), TestDriver(ProcessKind.WAIT_POINT) { StepResult.Exit(0) })
            eventually { m.fuelWaiterCount == 1 && h.scheduler.workers.activeSteps.get() == 0 }
            assertTrue(m.isAtWaitPoint)
            m.stop()
            assertState(ProcessState.KILLED, m, waiting)
            assertState(ProcessState.KILLED, m, starving)
            assertEquals(MachineState.STOPPED, m.state)
            assertEquals(0, m.registry.size)
        }
    }

    @Test
    fun `closeはすぐ返り、awaitTerminationで全部終わり、その後のcreateMachineは断る`() = timeout(limit) {
        val h = Harness()
        val m = h.machine(fuelPerTick = 1000)
        m.spawnTest(h.program(spinForever()), runDriver())
        h.scheduler.tick()
        val t0 = System.nanoTime()
        h.scheduler.close()
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_000)
        assertTrue(h.scheduler.awaitTermination(10_000))
        assertThrows<IllegalStateException> { h.machine() }
        assertNoKernelThreads()
    }

    @Test
    fun `スレッドの名前・daemon・スタックの大きさの指定が効いている`() = timeout(limit) {
        fun depthWith(stack: Long, kind: ProcessKind): Pair<Int, Thread> {
            Harness(SchedulerConfig.builder().threadStackBytes(stack).build()).use { h ->
                val m = h.machine()
                val depth = AtomicInteger()
                val thread = AtomicReference<Thread>()
                fun recurse(n: Int): Int {
                    depth.set(n)
                    return recurse(n + 1) + 1
                }
                val driver = TestDriver(kind, onConfigure = { _, _ ->
                    thread.set(Thread.currentThread())
                    try {
                        recurse(0)
                    } catch (_: StackOverflowError) {
                    }
                }) { StepResult.Exit(0) }
                val pid = m.spawnTest(h.program(emptyModule()), driver)
                h.scheduler.tick()
                eventually { m.stateOf(pid).isTerminal }
                return depth.get() to thread.get()
            }
        }
        val (smallB, tb) = depthWith(256L * 1024, ProcessKind.BLOCKING)
        val (largeB, _) = depthWith(64L * 1024 * 1024, ProcessKind.BLOCKING)
        val (smallW, tw) = depthWith(256L * 1024, ProcessKind.WAIT_POINT)
        val (largeW, _) = depthWith(64L * 1024 * 1024, ProcessKind.WAIT_POINT)
        assertTrue(tb.name.matches(Regex("KiBi-m\\d+-p\\d+")), tb.name)
        assertTrue(tw.name.startsWith("KiBi-worker-"), tw.name)
        assertTrue(tb.isDaemon && tw.isDaemon)
        assertTrue(largeB > smallB * 4, "専用スレッド: $smallB → $largeB")
        assertTrue(largeW > smallW * 4, "ワーカー: $smallW → $largeW")
    }

    // ---- 割り込みの競合（1本だけのワーカーで順番を作る） ----

    private class Race(h: Harness) {
        val a = h.machine(fuelPerTick = 1000)
        val b = h.machine(fuelPerTick = 1000)
        val s = stream(a)
        val waitDriver = TestDriver(ProcessKind.WAIT_POINT) { StepResult.Wait(listOf(WaitKey(s.id, WaitCondition.READABLE))) }
        val bProgress = AtomicInteger()
        val bModule = FakeModule.builder().fuelMetered().export("run", VOID) { ctx, _ ->
            repeat(200) {
                ctx.step(1)
                bProgress.incrementAndGet()
            }
            LongArray(0)
        }.build()

        private fun stream(m: Machine) = m.lock.withLockValue { ByteStream(16).also { m.kernel.objects.add(it) } }
    }

    private fun raceHarness(): Harness = Harness(executor = Executors.newSingleThreadExecutor { r -> KernelThreadsProbe.thread(r) })

    private fun finishWithB(h: Harness, race: Race) {
        val pid = race.b.spawnTest(h.program(race.bModule), runDriver(ProcessKind.WAIT_POINT))
        eventually { race.b.stateOf(pid).isTerminal }
        assertState(ProcessState.EXITED, race.b, pid)
        assertEquals(200, race.bProgress.get())
        assertEquals(MachineState.STOPPED, race.b.state)
        assertFalse(race.b.state == MachineState.CRASHED)
    }

    @Test
    fun `割り込みの競合(a) 登録を外す前に止めると割り込みが送られ、次のstepに持ち越さない`() = timeout(limit) {
        raceHarness().use { h ->
            val race = Race(h)
            h.scheduler.tick()
            val atGate = CountDownLatch(1)
            val gate = CountDownLatch(1)
            h.scheduler.hooks.beforeUnregister = { m, _ ->
                if (m === race.a) {
                    atGate.countDown()
                    gate.awaitKeepingInterrupt()
                }
            }
            val p = race.a.spawnTest(h.program(emptyModule()), race.waitDriver)
            assertTrue(atGate.await(5, TimeUnit.SECONDS))
            val sent = h.scheduler.interruptsSent.get()
            race.a.stop()
            assertEquals(sent + 1, h.scheduler.interruptsSent.get(), "登録されているので送る")
            h.scheduler.hooks.beforeUnregister = null
            gate.countDown()
            eventually { race.a.stateOf(p).isTerminal }
            assertState(ProcessState.KILLED, race.a, p)
            finishWithB(h, race)
        }
    }

    @Test
    fun `割り込みの競合(b) 登録を外した後に止めると割り込みは送られない`() = timeout(limit) {
        raceHarness().use { h ->
            val race = Race(h)
            h.scheduler.tick()
            val atGate = CountDownLatch(1)
            val gate = CountDownLatch(1)
            h.scheduler.hooks.afterUnregister = { m, _ ->
                if (m === race.a) {
                    atGate.countDown()
                    gate.awaitKeepingInterrupt()
                }
            }
            val p = race.a.spawnTest(h.program(emptyModule()), race.waitDriver)
            assertTrue(atGate.await(5, TimeUnit.SECONDS))
            val sent = h.scheduler.interruptsSent.get()
            race.a.stop()
            assertEquals(sent, h.scheduler.interruptsSent.get(), "登録がないので送らない")
            assertState(ProcessState.KILLED, race.a, p)
            h.scheduler.hooks.afterUnregister = null
            gate.countDown()
            finishWithB(h, race)
        }
    }

    @Test
    fun `割り込みの競合(c) 割り込みを送る前に止まったstopを、登録を外す側が待つ`() = timeout(limit) {
        raceHarness().use { h ->
            val race = Race(h)
            h.scheduler.tick()
            val stepAtGate = CountDownLatch(1)
            val stepGate = CountDownLatch(1)
            val stopAtGate = CountDownLatch(1)
            val stopGate = CountDownLatch(1)
            h.scheduler.hooks.beforeUnregister = { m, _ ->
                if (m === race.a) {
                    stepAtGate.countDown()
                    stepGate.awaitKeepingInterrupt()
                }
            }
            h.scheduler.hooks.stopBeforeInterrupt = { m, _ ->
                if (m === race.a) {
                    stopAtGate.countDown()
                    stopGate.await()
                }
            }
            val p = race.a.spawnTest(h.program(emptyModule()), race.waitDriver)
            assertTrue(stepAtGate.await(5, TimeUnit.SECONDS))
            val rtP = race.a.rt(p)
            val stopper = Thread { race.a.stop() }.apply { start() }
            assertTrue(stopAtGate.await(5, TimeUnit.SECONDS))
            h.scheduler.hooks.beforeUnregister = null
            stepGate.countDown()
            Thread.sleep(50)
            assertFalse(rtP.process.state.isTerminal, "登録を外す側はカーネルロックで待たされる")
            assertTrue(rtP.thread != null)
            h.scheduler.hooks.stopBeforeInterrupt = null
            stopGate.countDown()
            stopper.join(5_000)
            eventually { race.a.stateOf(p).isTerminal }
            assertState(ProcessState.KILLED, race.a, p)
            finishWithB(h, race)
        }
    }
}

/** 割り込まれても待ち続け、割り込みの印は残す（フックの中で使う。印はカーネルが step の終わりに消す） */
internal fun CountDownLatch.awaitKeepingInterrupt() {
    var interrupted = false
    while (true) {
        try {
            await()
            break
        } catch (e: InterruptedException) {
            interrupted = true
        }
    }
    if (interrupted) Thread.currentThread().interrupt()
}

/** テストからカーネルのスレッドかを見る口 */
internal object KernelThreadsProbe {
    fun isKernel(): Boolean = pro.ikili.kibicomputers.core.kernel.sched.KernelThread.isCurrent()

    fun thread(r: Runnable): Thread = Thread(r, "KiBi-test-worker").apply { isDaemon = true }
}
