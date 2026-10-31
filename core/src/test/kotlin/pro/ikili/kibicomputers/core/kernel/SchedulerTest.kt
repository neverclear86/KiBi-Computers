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
import pro.ikili.kibicomputers.core.runtime.FakeContext
import pro.ikili.kibicomputers.core.runtime.FakeModule
import pro.ikili.kibicomputers.core.runtime.WasmLinkException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 同時に実行している数を数える */
internal class ConcurrencyMeter {
    val running = AtomicInteger()
    val max = AtomicInteger()

    fun <T> measure(block: () -> T): T {
        val n = running.incrementAndGet()
        max.accumulateAndGet(n) { a, b -> maxOf(a, b) }
        try {
            return block()
        } finally {
            running.decrementAndGet()
        }
    }
}

/** 1 ずつ燃料を使いながら、実行中の数を [meter] で数え続けるモジュール */
internal fun meteredSpin(meter: ConcurrencyMeter, progress: AtomicInteger, asStart: Boolean = false): FakeModule {
    val body = { ctx: FakeContext ->
        while (true) {
            meter.measure { Thread.onSpinWait() }
            progress.incrementAndGet()
            ctx.step(1)
        }
        @Suppress("UNREACHABLE_CODE")
        LongArray(0)
    }
    val b = FakeModule.builder().fuelMetered()
    return if (asStart) b.start { ctx, _ -> body(ctx) }.build() else b.export("run", VOID) { ctx, _ -> body(ctx) }.build()
}

class SchedulerTest {
    private val limit = Duration.ofSeconds(30)

    private fun stream(machine: Machine): ByteStream = machine.lock.withLockValue { ByteStream(16).also { machine.kernel.objects.add(it) } }

    @Test
    fun `燃料を使い切ったプロセスは次のtickまで進まない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 100)
            val progress = AtomicInteger()
            m.spawnTest(h.program(spinForever(progress)), runDriver())
            for (k in 1..3) {
                h.scheduler.tick()
                eventually { progress.get() >= 100 * k - 1 }
                Thread.sleep(30)
                assertTrue(progress.get() <= 100 * k + 1, "tick $k: ${progress.get()}")
            }
            m.stop()
        }
    }

    @Test
    fun `同時に実行するマシンはmaxConcurrentMachinesまでで、どちらも進む`() = timeout(limit) {
        Harness(SchedulerConfig.builder().maxConcurrentMachines(1).build()).use { h ->
            val meter = ConcurrencyMeter()
            val p1 = AtomicInteger()
            val p2 = AtomicInteger()
            val m1 = h.machine(fuelPerTick = 1000)
            val m2 = h.machine(fuelPerTick = 1000)
            m1.spawnTest(h.program(meteredSpin(meter, p1)), runDriver())
            m2.spawnTest(h.program(meteredSpin(meter, p2)), runDriver())
            repeat(20) {
                h.scheduler.tick()
                Thread.sleep(5)
            }
            eventually { p1.get() > 1000 && p2.get() > 1000 }
            assertEquals(1, meter.max.get())
        }
    }

    @Test
    fun `マシンの中ではblockで待つ間に別のプロセスが動き、同時に実行するのは1プロセス`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1000)
            val s = stream(m)
            val meter = ConcurrencyMeter()
            val progress = AtomicInteger()
            val blocker = m.spawnTest(
                h.program(hostCallModule()),
                runDriver(configure = { ctx, b ->
                    b.host(ctx) { c, _, _ ->
                        meter.measure { }
                        c.block(listOf(WaitKey(s.id, WaitCondition.READABLE)))
                        meter.measure { }
                        LongArray(0)
                    }
                }),
            )
            h.scheduler.tick()
            eventually { m.stateOf(blocker) == ProcessState.WAITING_EVENT }
            m.spawnTest(h.program(meteredSpin(meter, progress)), runDriver())
            // start 関数で計算するプロセスも同じマシンに足す
            val progress2 = AtomicInteger()
            val loader = m.spawnTest(h.program(meteredSpin(meter, progress2, asStart = true)), TestDriver(ProcessKind.BLOCKING) { StepResult.Exit(0) })
            repeat(10) {
                h.scheduler.tick()
                Thread.sleep(5)
            }
            eventually { progress.get() > 100 && progress2.get() > 100 }
            assertTrue(m.stateOf(loader) in setOf(ProcessState.LOADING, ProcessState.WAITING_FUEL), "${m.stateOf(loader)}")
            m.lock.withLockValue { s.write(byteArrayOf(1), 0, 1, m.registry) }
            eventually {
                h.scheduler.tick()
                Thread.sleep(2)
                m.stateOf(blocker).isTerminal
            }
            assertEquals(1, meter.max.get())
            m.stop()
        }
    }

    @Test
    fun `start関数がトラップする・importが足りないプロセスはCRASHEDになり、許可が1回だけ返る`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 100)
            val before = h.scheduler.permits.availablePermits()
            val trap = h.program(FakeModule.builder().fuelMetered().start { ctx, _ -> ctx.trap(WasmTrapKind.UNREACHABLE) }.build())
            val missing = h.program(hostCallModule())
            h.scheduler.tick()
            val a = m.spawnTest(trap, TestDriver(ProcessKind.BLOCKING) { StepResult.Exit(0) })
            eventually { m.stateOf(a).isTerminal }
            val b = m.spawnTest(missing, TestDriver(ProcessKind.WAIT_POINT) { StepResult.Exit(0) })
            eventually { h.scheduler.tick(); Thread.sleep(2); m.stateOf(b).isTerminal }
            assertState(ProcessState.CRASHED, m, a)
            assertState(ProcessState.CRASHED, m, b)
            assertTrue(m.rt(b).lastError is WasmLinkException)
            assertEquals(before, h.scheduler.permits.availablePermits())
            // 例外で抜けるときは、tick が進んでいれば遅延補充が1回入る（その分の 100 は正当）。ただし fuelPerTick を超えて増えない
            val fuel = m.lock.withLockValue { m.kernel.fuel }
            assertTrue(fuel == 0L || fuel == 100L, "マシンの燃料は増えない（補充の1tick分まで）: $fuel")
        }
    }

    @Test
    fun `configureでホストimportを登録し、acquireCpuの後・instantiateの前に1回だけ呼ばれる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 100)
            val module = FakeModule.builder().fuelMetered().import("env", "h", VOID)
                .start { ctx, _ -> ctx.callImport("env", "h") }.build()
            val atConfigure = AtomicReference<List<Any>>()
            val initial = AtomicReference<Long>()
            lateinit var driver: TestDriver
            driver = TestDriver(ProcessKind.BLOCKING, onConfigure = { ctx, b ->
                val rt = m.rt(ctx.pid)
                atConfigure.set(listOf(rt.holdsCpu, rt.holdsPermit, m.stateOf(ctx.pid), rt.instances.size, runCatching { ctx.instance }.exceptionOrNull() is IllegalStateException))
                b.host(ctx) { c, caller, _ ->
                    initial.set(caller.fuel)
                    c.chargeFuel(10)
                    LongArray(0)
                }
            }) { ctx ->
                assertEquals(90, ctx.instance.fuel)
                StepResult.Exit(0)
            }
            val pid = m.spawnTest(h.program(module), driver)
            h.scheduler.tick()
            eventually { m.stateOf(pid).isTerminal }
            assertState(ProcessState.EXITED, m, pid)
            assertEquals(1, driver.configureCalls.get())
            assertEquals(listOf<Any>(true, true, ProcessState.LOADING, 0, true), atConfigure.get())
            assertEquals(100L, initial.get(), "initialFuel は grant")
        }
    }

    @Test
    fun `configureでbuilder_fuelを呼ぶ・例外を投げるとCRASHEDでWASMは動かない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 100)
            val ran = AtomicInteger()
            val module = FakeModule.builder().fuelMetered().start { _, _ -> ran.incrementAndGet(); LongArray(0) }.build()
            val before = h.scheduler.permits.availablePermits()
            h.scheduler.tick()
            val a = m.spawnTest(h.program(module), TestDriver(ProcessKind.BLOCKING, onConfigure = { _, b -> b.fuel(5, null) }) { StepResult.Exit(0) })
            val b = m.spawnTest(h.program(module), TestDriver(ProcessKind.WAIT_POINT, onConfigure = { _, _ -> error("ABI 層の失敗") }) { StepResult.Exit(0) })
            eventually { h.scheduler.tick(); Thread.sleep(2); m.stateOf(a).isTerminal && m.stateOf(b).isTerminal }
            assertState(ProcessState.CRASHED, m, a)
            assertState(ProcessState.CRASHED, m, b)
            assertTrue(m.rt(a).lastError is IllegalStateException)
            assertEquals(0, ran.get())
            assertEquals(before, h.scheduler.permits.availablePermits())
        }
    }

    @Test
    fun `ctx_instantiateのconfigurerが登録したホスト関数を2つ目のstart関数から呼べ、builder_fuelは拒む`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 100)
            val second = h.program(FakeModule.builder().fuelMetered().import("env", "g", VOID).start { ctx, _ -> ctx.callImport("env", "g") }.build())
            val called = AtomicInteger()
            val result = AtomicReference<List<Any?>>()
            val driver = runDriver(configure = { ctx, b ->
                b.host(ctx) { c, caller, _ ->
                    val err = runCatching { c.instantiate(second.module) { it.fuel(1, null) } }.exceptionOrNull()
                    val fuelAfterErr = caller.fuel
                    c.instantiate(second.module) { it.host(c, "g") { _, _, _ -> called.incrementAndGet(); LongArray(0) } }
                    result.set(listOf(err is IllegalStateException, fuelAfterErr))
                    LongArray(0)
                }
            })
            val pid = m.spawnTest(h.program(hostCallModule()), driver)
            h.scheduler.tick()
            eventually { m.stateOf(pid).isTerminal }
            assertState(ProcessState.EXITED, m, pid)
            assertEquals(listOf<Any?>(true, 100L), result.get(), "燃料を動かす前に拒むので、手持ちはそのまま")
            assertEquals(1, called.get())
        }
    }

    @Test
    fun `spawnの引数`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val program = h.program(emptyModule())
            assertEquals(MachineState.STOPPED, m.state)
            assertThrows<IllegalArgumentException> { m.spawnTest(program, TestDriver(ProcessKind.WAIT_POINT) { StepResult.Exit(0) }, parentPid = 99) }
            val s = stream(m)
            val waitForever = TestDriver(ProcessKind.WAIT_POINT) { StepResult.Wait(listOf(WaitKey(s.id, WaitCondition.READABLE))) }
            val parent = m.spawnTest(program, waitForever)
            assertEquals(MachineState.RUNNING, m.state)
            val child = m.spawnTest(program, waitForever, parentPid = parent)
            assertEquals(parent, m.lock.withLockValue { m.kernel.processes[child]!!.parentPid })
            m.stop()
            if (m.state == MachineState.STOPPING) {
                assertThrows<IllegalStateException> { m.spawnTest(program, waitForever) }
            }
            assertTrue(m.awaitTermination(5_000))
            assertEquals(MachineState.STOPPED, m.state)
        }
    }

    @Test
    fun `stop中のspawnはIllegalStateException`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val inHost = CountDownLatch(1)
            val release = CountDownLatch(1)
            m.spawnTest(
                h.program(hostCallModule()),
                runDriver(configure = { ctx, b ->
                    b.host(ctx) { _, _, _ ->
                        inHost.countDown()
                        while (release.count > 0) Thread.onSpinWait() // 割り込みを無視して計算中のまね
                        LongArray(0)
                    }
                }),
            )
            h.scheduler.tick()
            assertTrue(inHost.await(5, TimeUnit.SECONDS))
            m.stop()
            assertEquals(MachineState.STOPPING, m.state)
            assertThrows<IllegalStateException> { m.spawnTest(h.program(emptyModule()), runDriver()) }
            release.countDown()
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `待機点型が境目で燃料待ちになるとスレッドを返し、次のtickで動く`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 100)
            val s = stream(m)
            val key = WaitKey(s.id, WaitCondition.READABLE)
            val resumed = AtomicInteger()
            val module = FakeModule.builder().fuelMetered().export("spin", VOID) { ctx, _ -> ctx.step(100); LongArray(0) }.build()
            val driver = TestDriver(ProcessKind.WAIT_POINT, onResume = { _, _ ->
                resumed.incrementAndGet()
                StepResult.Exit(0)
            }) { ctx ->
                ctx.instance.call("spin")
                StepResult.Wait(listOf(key))
            }
            val pid = m.spawnTest(h.program(module), driver)
            h.scheduler.tick()
            eventually { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            assertTrue(m.isAtWaitPoint)
            assertEquals(0, m.lock.withLockValue { m.kernel.fuel })
            m.lock.withLockValue { assertEquals(listOf(key), m.kernel.processes[pid]!!.wait) }
            m.lock.withLockValue { s.write(byteArrayOf(1), 0, 1, m.registry) }
            eventually { m.lock.withLockValue { m.rt(pid).fuelWaitThreadless } }
            eventually { h.scheduler.workers.activeSteps.get() == 0 }
            assertState(ProcessState.RUNNABLE, m, pid)
            assertTrue(m.isAtWaitPoint, "境目で燃料を待つ間は待機点")
            assertEquals(0, resumed.get())
            h.scheduler.tick()
            eventually { m.stateOf(pid).isTerminal }
            assertEquals(1, resumed.get())
        }
    }

    @Test
    fun `ブロッキング型がblockで待っているときは待機点ではない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val s = stream(m)
            val pid = m.spawnTest(
                h.program(hostCallModule()),
                runDriver(configure = { ctx, b ->
                    b.host(ctx) { c, _, _ ->
                        c.block(listOf(WaitKey(s.id, WaitCondition.READABLE)))
                        LongArray(0)
                    }
                }),
            )
            h.scheduler.tick()
            eventually { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            assertFalse(m.isAtWaitPoint)
            m.stop()
        }
    }

    @Test
    fun `起こされたのに空なら待ち直す（許可も燃料も使わない）`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1000)
            val s = stream(m)
            val key = WaitKey(s.id, WaitCondition.READABLE)
            val resumes = mutableListOf<List<WaitKey>>()
            val waitDriver = {
                TestDriver(ProcessKind.WAIT_POINT, onResume = { _, ready ->
                    synchronized(resumes) { resumes += ready }
                    StepResult.Exit(0)
                }) { StepResult.Wait(listOf(key)) }
            }
            val p = m.spawnTest(h.program(emptyModule()), waitDriver())
            val q = m.spawnTest(h.program(emptyModule()), waitDriver())
            val blockReturned = AtomicReference<List<WaitKey>>()
            val r = m.spawnTest(
                h.program(hostCallModule()),
                runDriver(configure = { ctx, b ->
                    b.host(ctx) { c, _, _ ->
                        blockReturned.set(c.block(listOf(key)))
                        LongArray(0)
                    }
                }),
            )
            h.scheduler.tick()
            eventuallyDescribe(describe = { listOf(p, q, r).map { m.stateOf(it) to m.rt(it).lastError }.toString() }) { listOf(p, q, r).all { m.stateOf(it) == ProcessState.WAITING_EVENT } }
            eventually { h.scheduler.workers.activeSteps.get() == 0 }
            val permits = h.scheduler.permitAcquisitions.get()
            val fuel = m.lock.withLockValue { m.kernel.fuel }
            val submitted = h.scheduler.workers.submitted.get()
            // 書いてすぐ読み切る（起こされるが、動き出したときには空）
            m.lock.withLockValue {
                s.write(byteArrayOf(1), 0, 1, m.registry)
                s.read(ByteArray(1), 0, 1, m.registry)
            }
            eventually { h.scheduler.workers.submitted.get() >= submitted + 2 }
            eventually { h.scheduler.workers.activeSteps.get() == 0 && listOf(p, q).all { m.stateOf(it) == ProcessState.WAITING_EVENT } }
            Thread.sleep(50)
            assertState(ProcessState.WAITING_EVENT, m, r)
            assertEquals(null, blockReturned.get())
            assertTrue(m.isAtWaitPoint.not(), "R はブロッキング型なので待機点ではない")
            m.lock.withLockValue {
                assertEquals(listOf(key), m.kernel.processes[p]!!.wait)
                assertEquals(fuel, m.kernel.fuel)
            }
            assertEquals(permits, h.scheduler.permitAcquisitions.get(), "許可を取らない")
            assertTrue(synchronized(resumes) { resumes.isEmpty() })
            // 次に書いたら、空でない並びで戻る
            m.lock.withLockValue { s.write(byteArrayOf(1, 2, 3), 0, 3, m.registry) }
            eventually { listOf(p, q, r).all { m.stateOf(it).isTerminal } }
            assertEquals(listOf(key), blockReturned.get())
            assertEquals(2, synchronized(resumes) { resumes.size })
        }
    }

    @Test
    fun `許可を取った後に空になったら許可を1回だけ返して待ち直す`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1000)
            val s = stream(m)
            val key = WaitKey(s.id, WaitCondition.READABLE)
            val resumed = AtomicInteger()
            val p = m.spawnTest(
                h.program(emptyModule()),
                TestDriver(ProcessKind.WAIT_POINT, onResume = { _, _ -> resumed.incrementAndGet(); StepResult.Exit(0) }) { StepResult.Wait(listOf(key)) },
            )
            h.scheduler.tick()
            eventually { m.stateOf(p) == ProcessState.WAITING_EVENT }
            eventually { h.scheduler.workers.activeSteps.get() == 0 }
            val gate = CountDownLatch(1)
            val atGate = CountDownLatch(1)
            h.scheduler.hooks.afterStepPermit = { _, pid ->
                if (pid == p) {
                    atGate.countDown()
                    gate.await()
                }
            }
            val before = h.scheduler.permits.availablePermits()
            val fuel = m.lock.withLockValue { m.kernel.fuel }
            m.lock.withLockValue { s.write(byteArrayOf(1), 0, 1, m.registry) }
            assertTrue(atGate.await(5, TimeUnit.SECONDS))
            m.lock.withLockValue { s.read(ByteArray(1), 0, 1, m.registry) }
            h.scheduler.hooks.afterStepPermit = null
            gate.countDown()
            eventually { h.scheduler.workers.activeSteps.get() == 0 && m.stateOf(p) == ProcessState.WAITING_EVENT }
            assertEquals(before, h.scheduler.permits.availablePermits())
            assertEquals(fuel, m.lock.withLockValue { m.kernel.fuel })
            assertEquals(0, resumed.get())
            m.lock.withLockValue { s.write(byteArrayOf(1), 0, 1, m.registry) }
            eventually { m.stateOf(p).isTerminal }
            assertEquals(1, resumed.get())
        }
    }

    @Test
    fun `燃料を計測しないモジュールは既定では断り、allowUnmeteredなら燃料を使わずに動く`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10)
            val unmetered = h.program(FakeModule.builder().export("run", VOID) { ctx, _ -> ctx.step(1000); LongArray(0) }.build())
            assertThrows<IllegalArgumentException> { m.spawnTest(unmetered, runDriver()) }
            val pid = m.spawnTest(unmetered, runDriver(), unmetered = true)
            eventually { m.stateOf(pid).isTerminal }
            assertState(ProcessState.EXITED, m, pid)
            assertEquals(0, m.lock.withLockValue { m.kernel.fuel })
        }
    }

    @Test
    fun `トラップを繰り返しても燃料の上限を超えない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 100)
            val total = AtomicInteger()
            val started = AtomicInteger()
            val module = FakeModule.builder().fuelMetered().export("run", VOID) { ctx, _ ->
                started.incrementAndGet()
                total.addAndGet(60)
                ctx.spinLocal(60, trapAtEnd = true)
                LongArray(0)
            }.build()
            val program = h.program(module)
            val pids = (1..5).map { m.spawnTest(program, runDriver()) }
            h.scheduler.tick()
            eventually { started.get() >= 1 && pids.count { m.stateOf(it).isTerminal } >= 1 }
            Thread.sleep(50)
            assertEquals(1, started.get(), "2つ目以降は次の tick まで進まない")
            assertTrue(total.get() <= 100 + 1)
            h.scheduler.tick()
            eventually { started.get() >= 2 }
            Thread.sleep(50)
            assertEquals(2, started.get())
            m.stop()
        }
    }
}
