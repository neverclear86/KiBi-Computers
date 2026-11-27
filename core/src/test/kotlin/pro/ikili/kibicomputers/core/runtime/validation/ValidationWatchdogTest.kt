package pro.ikili.kibicomputers.core.runtime.validation

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import pro.ikili.kibicomputers.core.kernel.joinOrFail
import org.junit.jupiter.api.Timeout
import pro.ikili.kibicomputers.core.kernel.awaitOrFail

/** [ValidationWatchdog] の時計の分岐とタイマーのスレッド（#666）。時計を差し替えて、実時間を待たずに確かめる */
@Timeout(10, unit = TimeUnit.MINUTES) // 止まったときの保険（合否の基準ではない。#916）
class ValidationWatchdogTest {
    /** 手で動かすタイマー（予定を貯めるだけ） */
    private class ManualTimer : ValidationWatchdog.Timer {
        val tasks = ArrayList<Pair<Long, Runnable>>()

        override fun schedule(delayNanos: Long, task: Runnable): Future<*> {
            tasks += delayNanos to task
            return CompletableFuture<Unit>()
        }
    }

    @AfterEach
    fun clearInterrupt() {
        Thread.interrupted()
    }

    private val second = 1_000_000_000L

    @Test
    fun `別スレッドのCPU時間が読めないときは経過時間を割り引いて待つ`() {
        val limit = second
        var wall = 1_000L
        val start = wall
        val timer = ManualTimer()
        // CPU 時間で測る上限（usesCpuTime）だが、別のスレッドからは読めない
        val watchdog = ValidationWatchdog(timer, cpuReader = { -1 }, wallClock = { wall })
        val w = watchdog.watch(LoadDeadline(limit, cpuClock = { 0L }))
        // 経過時間は CPU 時間より長いので、上限の WALL_FALLBACK_FACTOR 倍まで待つ
        assertEquals(limit * ValidationWatchdog.WALL_FALLBACK_FACTOR, timer.tasks.last().first)

        // 経過時間が上限の 2 倍: 割り引いた時間は上限の半分。まだ超えていないので入れ直す（残りの半分の 4 倍）
        wall = start + 2 * limit
        timer.tasks.last().second.run()
        assertFalse(Thread.currentThread().isInterrupted)
        assertEquals(2, timer.tasks.size)
        assertEquals(2 * limit, timer.tasks.last().first)

        // 上限の 3.9 倍: まだ超えていない（割り引いた時間は上限の 0.975 倍。残りの 0.025 倍の 4 倍を待つ）
        wall = start + limit * 39 / 10
        timer.tasks.last().second.run()
        assertFalse(Thread.currentThread().isInterrupted)
        assertEquals(3, timer.tasks.size)
        assertEquals(limit / 10, timer.tasks.last().first)
        assertFalse(w.timedOut)

        // 上限の 4 倍を超えたら時間切れ
        wall = start + limit * ValidationWatchdog.WALL_FALLBACK_FACTOR + ValidationWatchdog.WALL_FALLBACK_FACTOR
        timer.tasks.last().second.run()
        assertTrue(Thread.currentThread().isInterrupted)
        assertTrue(w.timedOut)
        assertTrue(w.finish())
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test
    fun `別スレッドのCPU時間が読めないときの待ち時間は桁あふれしない`() {
        val timer = ManualTimer()
        val watchdog = ValidationWatchdog(timer, cpuReader = { -1 }, wallClock = { 0L })
        watchdog.watch(LoadDeadline(Long.MAX_VALUE / 2, cpuClock = { 0L })).finish()
        assertEquals(Long.MAX_VALUE, timer.tasks.last().first)
    }

    @Test
    fun `経過時間で測る上限は割り引かずに待って超えたら止める`() {
        val limit = 10 * second
        var wall = 5_000L
        val start = wall
        val timer = ManualTimer()
        val watchdog = ValidationWatchdog(timer, cpuReader = { error("経過時間で測る上限では CPU 時間を読まない") }, wallClock = { wall })
        val deadline = LoadDeadline(limit, cpuClock = null)
        assertFalse(deadline.usesCpuTime)
        val w = watchdog.watch(deadline)
        // 割り引かない（作った直後なので、上限より少し短いだけ）
        assertTrue(timer.tasks.last().first in 9 * second..limit) { "${timer.tasks.last().first}" }

        wall = start + limit / 2
        timer.tasks.last().second.run()
        assertFalse(Thread.currentThread().isInterrupted)
        assertEquals(2, timer.tasks.size)
        assertTrue(timer.tasks.last().first in 4 * second..limit / 2) { "${timer.tasks.last().first}" }

        wall = start + limit + second
        timer.tasks.last().second.run()
        assertTrue(Thread.currentThread().isInterrupted)
        assertTrue(w.finish())
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test
    fun `CPU時間が読めるときはCPU時間で測り経過時間は進んでも超えない`() {
        val limit = second
        var cpu = 100L
        var wall = 0L
        val timer = ManualTimer()
        val watchdog = ValidationWatchdog(timer, cpuReader = { cpu }, wallClock = { wall })
        val w = watchdog.watch(LoadDeadline(limit, cpuClock = { 0L }))
        assertEquals(limit, timer.tasks.last().first)
        // 経過時間がどれだけ進んでも、CPU 時間が進まなければ超えない
        wall += 100 * limit
        cpu += limit * 6 / 10
        timer.tasks.last().second.run()
        assertFalse(Thread.currentThread().isInterrupted)
        assertEquals(limit * 4 / 10, timer.tasks.last().first)
        cpu += limit
        timer.tasks.last().second.run()
        assertTrue(Thread.currentThread().isInterrupted)
        assertTrue(w.finish())
    }

    @Test
    fun `タイマーが使えないときは見張らずに続ける`() {
        val watchdog = ValidationWatchdog(timer = { _, _ -> throw RejectedExecutionException("終了中") })
        val w = watchdog.watch(LoadDeadline(second))
        assertFalse(w.timedOut)
        assertFalse(w.finish())
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test
    fun `タイマーのスレッドは呼んだスレッドの優先度を引き継がず仕事の後に終わる`() {
        val executor = ValidationWatchdog.newExecutor(idleMillis = 50)
        try {
            // 本番と同じく、最低優先度のスレッドから予定を入れる
            val ran = CountDownLatch(1)
            val worker = AtomicReference<Thread>()
            val caller = Thread {
                executor.schedule(
                    {
                        worker.set(Thread.currentThread())
                        ran.countDown()
                    },
                    0,
                    TimeUnit.MILLISECONDS,
                )
            }
            caller.priority = Thread.MIN_PRIORITY
            caller.start()
            caller.join()
            ran.awaitOrFail()
            val t = worker.get()
            assertEquals("KiBi-wasm-watchdog", t.name)
            assertEquals(Thread.NORM_PRIORITY, t.priority)
            assertTrue(t.isDaemon)
            // 仕事がなければスレッドが終わる（ワールドを出入りしても溜まらない）
            t.joinOrFail("タイマーのスレッドが仕事の後に終わらない")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `取り消した予定はタイマーの待ち行列からすぐ外す`() {
        val executor = ValidationWatchdog.newExecutor(idleMillis = 50)
        try {
            val f = executor.schedule({}, 1, TimeUnit.HOURS)
            assertEquals(1, executor.queue.size)
            f.cancel(false)
            assertEquals(0, executor.queue.size)
        } finally {
            executor.shutdownNow()
        }
    }
}
