package pro.ikili.kibicomputers.core.wasi.kibi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineEnvironment
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.emptyModule
import pro.ikili.kibicomputers.core.kernel.event.EventValue
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.spawnTest
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.tickUntilTrue
import pro.ikili.kibicomputers.core.kernel.time.ManualClock
import pro.ikili.kibicomputers.core.kernel.time.ManualTimerService
import pro.ikili.kibicomputers.core.kernel.time.TimerLimits
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 独自拡張 `kibi:kernel/timers` の意味の部分（[KibiTimers]。#426）。時計とタイマーは手で進める */
class KibiTimersTest {
    private val ms = 1_000_000L

    /** ブロッキング型のプロセスの中で [body] を動かし、例外があれば投げ直す。[ManualTimerService] で時計を進める */
    private fun inProcess(body: (ProcessContext, Machine, ManualTimerService) -> Unit) = timeout(Duration.ofSeconds(30)) {
        val clock = ManualClock()
        val timers = ManualTimerService(clock)
        Harness(timers = timers).use { h ->
            val m = h.machine(environment = MachineEnvironment.builder().clock(clock).build())
            val error = AtomicReference<Throwable>()
            val driver = TestDriver(ProcessKind.BLOCKING) { ctx ->
                try {
                    body(ctx, m, timers)
                } catch (t: Throwable) {
                    error.set(t)
                }
                StepResult.Exit(0)
            }
            val pid = m.spawnTest(h.program(emptyModule()), driver)
            h.tickUntilTrue { m.stateOf(pid).isTerminal }
            error.get()?.let { throw it }
            assertEquals(ProcessState.EXITED, m.stateOf(pid))
        }
    }

    private fun assertErrno(expected: Errno, block: () -> Unit) {
        val e = assertThrows<FsException> { block() }
        assertEquals(expected, e.errno, e.message)
    }

    @Test
    fun `キューなしで作ったタイマーは止まった状態で、set-afterの期限で鳴り、readで回数を取り出す`() = inProcess { ctx, _, timers ->
        val t = KibiTimers.create(ctx, null)
        assertEquals(0L, KibiTimers.read(ctx, t))
        KibiTimers.set(ctx, t, 100 * ms, 0, absolute = false)
        timers.advance(99 * ms)
        assertEquals(0L, KibiTimers.read(ctx, t))
        timers.advance(1 * ms)
        assertEquals(1L, KibiTimers.read(ctx, t))
        assertEquals(0L, KibiTimers.read(ctx, t), "read で 0 に戻る")
        timers.advance(1_000 * ms)
        assertEquals(0L, KibiTimers.read(ctx, t), "interval が 0 なら1回だけ")
    }

    @Test
    fun `set-atは単調時計の時刻で鳴り、0や過ぎた時刻ならすぐ鳴る`() = inProcess { ctx, _, timers ->
        val t = KibiTimers.create(ctx, null)
        KibiTimers.set(ctx, t, ctx.monotonicNanos() + 50 * ms, 0, absolute = true)
        timers.advance(49 * ms)
        assertEquals(0L, KibiTimers.read(ctx, t))
        timers.advance(1 * ms)
        assertEquals(1L, KibiTimers.read(ctx, t))
        KibiTimers.set(ctx, t, 0, 0, absolute = true)
        timers.runDue()
        assertEquals(1L, KibiTimers.read(ctx, t))
        KibiTimers.set(ctx, t, 0, 0, absolute = false)
        timers.runDue()
        assertEquals(1L, KibiTimers.read(ctx, t))
    }

    @Test
    fun `繰り返しは間隔ごとに数え、cancelで止まって回数も0に戻る`() = inProcess { ctx, _, timers ->
        val t = KibiTimers.create(ctx, null)
        KibiTimers.set(ctx, t, 0, TimerLimits.MIN_INTERVAL_NANOS, absolute = false)
        timers.runDue()
        repeat(2) { timers.advance(TimerLimits.MIN_INTERVAL_NANOS) }
        assertEquals(3L, KibiTimers.read(ctx, t))
        timers.advance(TimerLimits.MIN_INTERVAL_NANOS)
        KibiTimers.cancel(ctx, t)
        assertEquals(0L, KibiTimers.read(ctx, t))
        timers.advance(10 * TimerLimits.MIN_INTERVAL_NANOS)
        assertEquals(0L, KibiTimers.read(ctx, t))
    }

    @Test
    fun `intervalは0か最小の間隔以上だけを受け付ける`() = inProcess { ctx, _, _ ->
        val t = KibiTimers.create(ctx, null)
        assertErrno(Errno.INVAL) { KibiTimers.set(ctx, t, 0, 1, absolute = false) }
        assertErrno(Errno.INVAL) { KibiTimers.set(ctx, t, 0, TimerLimits.MIN_INTERVAL_NANOS - 1, absolute = true) }
        KibiTimers.set(ctx, t, 1_000 * ms, TimerLimits.MIN_INTERVAL_NANOS, absolute = false)
        KibiTimers.set(ctx, t, 1_000 * ms, 0, absolute = false)
    }

    @Test
    fun `頭打ちにした最大の値でも溢れずに設定できる`() = inProcess { ctx, _, timers ->
        val t = KibiTimers.create(ctx, null)
        KibiTimers.set(ctx, t, Long.MAX_VALUE, Long.MAX_VALUE, absolute = false)
        KibiTimers.set(ctx, t, Long.MAX_VALUE, Long.MAX_VALUE, absolute = true)
        timers.advance(1_000_000 * ms)
        assertEquals(0L, KibiTimers.read(ctx, t))
    }

    @Test
    fun `動いているタイマーが上限ならnfile`() = inProcess { ctx, _, _ ->
        repeat(TimerLimits.MAX_ARMED_TIMERS) { KibiTimers.set(ctx, KibiTimers.create(ctx, null), 1_000 * ms, 0, absolute = false) }
        assertErrno(Errno.NFILE) { KibiTimers.set(ctx, KibiTimers.create(ctx, null), 1_000 * ms, 0, absolute = false) }
    }

    @Test
    fun `キューを渡すと鳴るたびにtagと回数のtimerイベントが届き、readは0のまま`() = inProcess { ctx, _, timers ->
        val q = KibiEvents.open(ctx, listOf("timer"), null)
        val t = KibiTimers.create(ctx, q to -7L)
        KibiTimers.set(ctx, t, 10 * ms, 0, absolute = false)
        timers.advance(10 * ms)
        val batch = KibiEvents.take(ctx, q, 10)
        assertEquals(1, batch.events.size)
        val e = batch.events[0]
        assertEquals("timer", e.type)
        assertEquals("", e.source)
        assertEquals(listOf(EventValue.Integer(-7), EventValue.Integer(1)), e.values)
        assertEquals(0L, KibiTimers.read(ctx, t))
    }

    @Test
    fun `キューでないfdはbad-descriptor、timerを受け取らないキューはinvalid`() = inProcess { ctx, _, _ ->
        val other = KibiTimers.create(ctx, null)
        assertErrno(Errno.BADF) { KibiTimers.create(ctx, other to 0L) }
        assertErrno(Errno.BADF) { KibiTimers.create(ctx, 9_999 to 0L) }
        assertErrno(Errno.BADF) { KibiTimers.create(ctx, -1 to 0L) }
        val q = KibiEvents.open(ctx, listOf("key"), null)
        assertErrno(Errno.INVAL) { KibiTimers.create(ctx, q to 0L) }
        // すべてを受け取るキューなら作れる
        KibiTimers.create(ctx, KibiEvents.open(ctx, emptyList(), null) to 0L)
    }

    @Test
    fun `タイマーでないハンドルはbad-descriptor`() = inProcess { ctx, _, _ ->
        val q = KibiEvents.open(ctx, emptyList(), null)
        assertErrno(Errno.BADF) { KibiTimers.set(ctx, q, 0, 0, absolute = false) }
        assertErrno(Errno.BADF) { KibiTimers.cancel(ctx, q) }
        assertErrno(Errno.BADF) { KibiTimers.read(ctx, q) }
        assertErrno(Errno.BADF) { KibiTimers.read(ctx, 9_999) }
        assertErrno(Errno.BADF) { KibiTimers.drop(ctx, q) }
        assertErrno(Errno.BADF) { KibiTimers.drop(ctx, 9_999) }
        // キューは閉じていない
        KibiEvents.take(ctx, q, 1)
    }

    @Test
    fun `dropで閉じたタイマーは止まり、その後はbad-descriptor`() = inProcess { ctx, m, timers ->
        val t = KibiTimers.create(ctx, null)
        KibiTimers.set(ctx, t, 10 * ms, TimerLimits.MIN_INTERVAL_NANOS, absolute = false)
        KibiTimers.drop(ctx, t)
        assertEquals(0, timers.pending, "予定も取り消す")
        assertEquals(0, ctx.withKernelLock { m.timers.scheduledCount })
        assertErrno(Errno.BADF) { KibiTimers.read(ctx, t) }
        assertErrno(Errno.BADF) { KibiTimers.drop(ctx, t) }
    }
}
