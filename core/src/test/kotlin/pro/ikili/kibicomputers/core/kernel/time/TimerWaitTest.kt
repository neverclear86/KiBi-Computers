package pro.ikili.kibicomputers.core.kernel.time

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineEnvironment
import pro.ikili.kibicomputers.core.kernel.ResourceLimitException
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.emptyModule
import pro.ikili.kibicomputers.core.kernel.event.EventFilter
import pro.ikili.kibicomputers.core.kernel.event.EventValue
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.host
import pro.ikili.kibicomputers.core.kernel.hostCallModule
import pro.ikili.kibicomputers.core.kernel.kernelThreadNames
import pro.ikili.kibicomputers.core.kernel.process
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.spawnTest
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.tickUntilTrue
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.withLockValue
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** スケジューラの上でタイマー・sleep・時計を使う（#64）。 */
class TimerWaitTest {
    private val limit = Duration.ofSeconds(30)
    private val ms = 1_000_000L

    /** 手で進める時計とタイマーのある Harness */
    private class ManualHarness {
        val clock = ManualClock()
        val timers = ManualTimerService(clock)
        val h = Harness(timers = timers)
        val env: MachineEnvironment = MachineEnvironment.builder().clock(clock).build()

        fun machine(): Machine = h.machine(environment = env)
    }

    private fun manual(block: (ManualHarness) -> Unit) {
        val mh = ManualHarness()
        mh.h.use { block(mh) }
    }

    private fun Machine.exited(pid: Int): Int? = process(pid)?.takeIf { it.state == ProcessState.EXITED }?.exitCode

    /** 待機点型: タイマーを [delay] 後に設定して待ち、鳴ったら回数で終わる */
    private fun sleepDriver(delay: Long, interval: Long = 0, handle: AtomicInteger = AtomicInteger(-1), resumes: AtomicInteger = AtomicInteger()) =
        TestDriver(
            ProcessKind.WAIT_POINT,
            onResume = { ctx, _ ->
                resumes.incrementAndGet()
                val n = ctx.readTimer(handle.get())
                if (n == 0L) StepResult.Wait(listOf(ctx.timerWaitKey(handle.get()))) else StepResult.Exit(n.toInt())
            },
        ) { ctx ->
            handle.set(ctx.createTimer())
            ctx.setTimer(handle.get(), delay, interval)
            StepResult.Wait(listOf(ctx.timerWaitKey(handle.get())))
        }

    @Test
    fun `待機点型はタイマーを待ってスレッドを持たず、期限が来たらresumeされる`() = timeout(limit) {
        manual { mh ->
            val m = mh.machine()
            val pid = m.spawnTest(mh.h.program(emptyModule()), sleepDriver(1_000 * ms))
            mh.h.tickUntilTrue { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            assertTrue(m.isAtWaitPoint)
            eventually { kernelThreadNames("KiBi-m").isEmpty() }
            assertEquals(1, m.lock.withLockValue { m.timers.scheduledCount })
            mh.timers.advance(999 * ms)
            assertEquals(ProcessState.WAITING_EVENT, m.stateOf(pid))
            mh.timers.advance(1 * ms)
            mh.h.tickUntilTrue { m.stateOf(pid).isTerminal }
            assertEquals(1, m.exited(pid))
            // 終わったプロセスのタイマーは閉じられ、予定も消える
            assertEquals(0, m.lock.withLockValue { m.timers.scheduledCount })
            assertEquals(0, mh.timers.pending)
        }
    }

    @Test
    fun `読まれていない繰り返しのタイマーは予定を入れ直さず、読んだときに過ぎた回をまとめて数える`() = timeout(limit) {
        manual { mh ->
            val m = mh.machine()
            val handle = AtomicInteger(-1)
            val count = AtomicLong(-1)
            val ready = java.util.concurrent.CountDownLatch(1)
            val go = java.util.concurrent.CountDownLatch(1)
            val driver = TestDriver(
                ProcessKind.BLOCKING,
                onConfigure = { ctx, b ->
                    b.host(ctx) { c, _, _ ->
                        handle.set(c.createTimer())
                        c.setTimer(handle.get(), 100 * ms, 100 * ms)
                        ready.countDown()
                        go.await()
                        count.set(c.readTimer(handle.get()))
                        LongArray(0)
                    }
                },
            ) { ctx ->
                ctx.instance.call("run")
                StepResult.Exit(0)
            }
            val pid = m.spawnTest(mh.h.program(hostCallModule()), driver)
            mh.h.scheduler.tick()
            ready.await()
            val before = mh.timers.scheduled
            mh.timers.advance(100 * ms)
            assertEquals(0, m.lock.withLockValue { m.timers.scheduledCount }, "読まれるまで次の予定を入れない")
            assertEquals(before, mh.timers.scheduled)
            mh.timers.advance(450 * ms) // 550 ms: 100, 200, 300, 400, 500 の5回
            go.countDown()
            mh.h.tickUntilTrue { m.stateOf(pid).isTerminal }
            assertEquals(5, count.get())
        }
    }

    @Test
    fun `イベントを出すタイマーは、指定したキューにだけtimerのイベントを入れる`() = timeout(limit) {
        manual { mh ->
            val m = mh.machine()
            val got = AtomicReference<List<Long>>()
            val other = AtomicInteger(-1)
            val queue = AtomicInteger(-1)
            val driver = TestDriver(
                ProcessKind.WAIT_POINT,
                onResume = { ctx, _ ->
                    val batch = ctx.takeEvents(queue.get())
                    val e = batch.events.single()
                    assertEquals(TimerLimits.EVENT_TYPE, e.type)
                    got.set(e.values.map { (it as EventValue.I64).value })
                    assertEquals(0, ctx.takeEvents(other.get()).events.size, "ほかのキューには入れない")
                    StepResult.Exit(0)
                },
            ) { ctx ->
                queue.set(ctx.openEventQueue(EventFilter(setOf(TimerLimits.EVENT_TYPE))))
                other.set(ctx.openEventQueue(EventFilter.ALL))
                val t = ctx.createTimer(queue.get(), tag = 42)
                ctx.setTimer(t, 50 * ms)
                assertEquals(0, ctx.readTimer(t))
                val key = ctx.timerWaitKey(t)
                assertFalse(ctx.withKernelLock { ctx.kernel.objects[key.objectId]!!.isReady(key.condition) })
                StepResult.Wait(listOf(ctx.eventWaitKey(queue.get())))
            }
            val pid = m.spawnTest(mh.h.program(emptyModule()), driver)
            mh.h.tickUntilTrue { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            mh.timers.advance(50 * ms)
            mh.h.tickUntilTrue { m.stateOf(pid).isTerminal }
            assertEquals(0, m.exited(pid))
            assertEquals(listOf(42L, 1L), got.get())
        }
    }

    @Test
    fun `タイマーの操作の誤りはerrnoか上限の例外になる`() = timeout(limit) {
        manual { mh ->
            val m = mh.machine()
            val errors = AtomicReference<List<Any>>()
            val driver = TestDriver(ProcessKind.WAIT_POINT) { ctx ->
                val out = ArrayList<Any>()
                fun errno(f: () -> Unit): Errno = assertThrows<FsException>(f).errno
                val t = ctx.createTimer()
                val q = ctx.openEventQueue(EventFilter(setOf("key")))
                out += errno { ctx.setTimer(t, 0, intervalNanos = 1 * ms) }
                out += errno { ctx.setTimer(t, 0, intervalNanos = -1) }
                out += errno { ctx.readTimer(q) }
                out += errno { ctx.timerWaitKey(99) }
                out += errno { ctx.createTimer(eventQueue = t) }
                out += errno { ctx.createTimer(eventQueue = q) }
                // 上限まで動かすと、次は ResourceLimitException。同じタイマーの設定し直しは数えない
                val handles = (1 until TimerLimits.MAX_ARMED_TIMERS).map { ctx.createTimer().also { h -> ctx.setTimer(h, 1_000 * ms) } }
                ctx.setTimer(t, 1_000 * ms)
                ctx.setTimer(t, 2_000 * ms)
                assertThrows<ResourceLimitException> { ctx.setTimer(ctx.createTimer(), 1_000 * ms) }
                ctx.cancelTimer(handles[0])
                ctx.setTimer(ctx.createTimer(), 1_000 * ms)
                errors.set(out)
                StepResult.Exit(0)
            }
            val pid = m.spawnTest(mh.h.program(emptyModule()), driver)
            mh.h.tickUntilTrue { m.stateOf(pid).isTerminal }
            assertEquals(0, m.exited(pid), m.process(pid)?.toString())
            assertEquals(listOf(Errno.INVAL, Errno.INVAL, Errno.BADF, Errno.BADF, Errno.BADF, Errno.INVAL), errors.get())
            assertEquals(0, mh.timers.pending, "終わったら予定はすべて消える")
        }
    }

    @Test
    fun `復元するとタイマーは残りの時間から続き、止まっていた間に期限が来ていても鳴らない`() = timeout(limit) {
        manual { mh ->
            val m = mh.machine()
            val pid = m.spawnTest(mh.h.program(emptyModule()), sleepDriver(10_000 * ms))
            mh.h.tickUntilTrue { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            mh.timers.advance(4_000 * ms)
            val snap = m.captureClocks()
            assertEquals(4_000 * ms, snap.monotonicNanos)
            // チャンクが読み込まれていない 1 時間（ホストの時計だけが進む）。予定は捨てられたものとして、戻す
            mh.clock.monotonic.addAndGet(3_600_000 * ms)
            mh.clock.realtime.addAndGet(3_600_000 * ms)
            val jump = m.restoreClocks(snap)
            assertEquals(3_600_000 * ms, jump.realtimeNanos)
            assertEquals(4_000 * ms, m.monotonicNanos())
            mh.timers.runDue()
            assertEquals(ProcessState.WAITING_EVENT, m.stateOf(pid), "止まっていた間の分は数えない")
            mh.timers.advance(5_999 * ms)
            assertEquals(ProcessState.WAITING_EVENT, m.stateOf(pid))
            mh.timers.advance(1 * ms)
            mh.h.tickUntilTrue { m.stateOf(pid).isTerminal }
            assertEquals(1, m.exited(pid))
        }
    }

    @Test
    fun `起動からの経過はtickで数え、単調時計はマシンを作ったときが0`() = timeout(limit) {
        manual { mh ->
            repeat(5) { mh.h.scheduler.tick() }
            val m = mh.machine()
            assertEquals(0, m.uptimeTicks())
            assertEquals(0, m.monotonicNanos())
            repeat(3) { mh.h.scheduler.tick() }
            mh.timers.advance(7 * ms)
            assertEquals(3, m.uptimeTicks())
            assertEquals(7 * ms, m.monotonicNanos())
            val seen = AtomicReference<Pair<Long, Long>>()
            val pid = m.spawnTest(mh.h.program(emptyModule()), TestDriver(ProcessKind.WAIT_POINT) { ctx ->
                seen.set(ctx.monotonicNanos() to ctx.uptimeTicks())
                StepResult.Exit(0)
            })
            mh.h.tickUntilTrue { m.stateOf(pid).isTerminal }
            assertEquals(7 * ms, seen.get().first)
            assertTrue(seen.get().second >= 4)
        }
    }

    @Test
    fun `ブロッキング型のsleepは本物のタイマーのスレッドで指定した時間だけ止まる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val elapsed = AtomicLong(-1)
            val driver = TestDriver(
                ProcessKind.BLOCKING,
                onConfigure = { ctx, b ->
                    b.host(ctx) { c, _, _ ->
                        c.sleep(0)
                        val start = c.monotonicNanos()
                        c.sleep(80 * ms)
                        elapsed.set(c.monotonicNanos() - start)
                        LongArray(0)
                    }
                },
            ) { ctx ->
                ctx.instance.call("run")
                StepResult.Exit(0)
            }
            val pid = m.spawnTest(h.program(hostCallModule()), driver)
            h.tickUntilTrue { m.stateOf(pid).isTerminal }
            assertEquals(0, m.exited(pid))
            assertTrue(elapsed.get() >= 80 * ms, "止まった時間 ${elapsed.get()}")
            assertEquals(0, m.lock.withLockValue { m.timers.scheduledCount })
            var timers = 0
            m.lock.withLockValue { m.kernel.objects.forEachObject { if (it is Timer) timers++ } }
            assertEquals(0, timers, "一時的なタイマーは閉じる")
        }
    }

    @Test
    fun `sleep中に止めるとKILLEDになり、タイマーの予定は消える`() = timeout(limit) {
        manual { mh ->
            val m = mh.machine()
            val sleeping = java.util.concurrent.CountDownLatch(1)
            val driver = TestDriver(
                ProcessKind.BLOCKING,
                onConfigure = { ctx, b ->
                    b.host(ctx) { c, _, _ ->
                        sleeping.countDown()
                        c.sleep(60_000 * ms)
                        LongArray(0)
                    }
                },
            ) { ctx: ProcessContext ->
                ctx.instance.call("run")
                StepResult.Exit(0)
            }
            val pid = m.spawnTest(mh.h.program(hostCallModule()), driver)
            mh.h.scheduler.tick()
            sleeping.await()
            eventually { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            assertEquals(1, mh.timers.pending)
            m.stop()
            mh.h.tickUntilTrue { m.stateOf(pid).isTerminal }
            assertEquals(ProcessState.KILLED, m.stateOf(pid))
            assertEquals(0, mh.timers.pending)
        }
    }
}
