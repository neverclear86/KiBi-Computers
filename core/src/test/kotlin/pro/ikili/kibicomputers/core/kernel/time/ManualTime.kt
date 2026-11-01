package pro.ikili.kibicomputers.core.kernel.time

import pro.ikili.kibicomputers.core.kernel.MachineClock
import java.util.concurrent.atomic.AtomicLong

/** 手で進める時計（テスト用）。[advance] で単調時計と実時間を同じだけ進める */
internal class ManualClock(monotonic: Long = 1_000_000_000L, realtime: Long = 1_700_000_000_000_000_000L, world: Long = 0) : MachineClock {
    val monotonic = AtomicLong(monotonic)
    val realtime = AtomicLong(realtime)
    val world = AtomicLong(world)

    override fun realtimeNanos(): Long = realtime.get()

    override fun monotonicNanos(): Long = monotonic.get()

    override fun worldTicks(): Long = world.get()
}

/**
 * [ManualClock] の単調時計で期限を数える [TimerService]（テスト用）。[advance] で時計を進め、期限の来た仕事をこのスレッドで実行する。
 */
internal class ManualTimerService(val clock: ManualClock) : TimerService {
    private class Entry(val due: Long, val seq: Long, val task: Runnable) {
        @Volatile
        var cancelled = false
    }

    private val entries = ArrayList<Entry>()
    private var seq = 0L

    /** 取り消されていない予定の数 */
    val pending: Int get() = synchronized(entries) { entries.count { !it.cancelled } }

    /** これまでに入れた予定の数 */
    @Volatile
    var scheduled: Int = 0
        private set

    override fun schedule(delayNanos: Long, task: Runnable): TimerService.Handle {
        val e = synchronized(entries) {
            scheduled++
            Entry(saturatingAdd(clock.monotonic.get(), delayNanos.coerceAtLeast(0)), seq++, task).also { entries += it }
        }
        return TimerService.Handle {
            e.cancelled = true
            synchronized(entries) { entries.remove(e) }
        }
    }

    /** 時計を [nanos] 進め、期限の来た仕事を期限の順に実行する（実行中に入った、期限の来た仕事も） */
    fun advance(nanos: Long) {
        clock.monotonic.addAndGet(nanos)
        clock.realtime.addAndGet(nanos)
        runDue()
    }

    /** 期限の来た仕事を実行する */
    fun runDue() {
        while (true) {
            val next = synchronized(entries) {
                val now = clock.monotonic.get()
                entries.filter { !it.cancelled && it.due <= now }.minWithOrNull(compareBy<Entry> { it.due }.thenBy { it.seq })?.also { entries.remove(it) }
            } ?: return
            next.task.run()
        }
    }

    override fun close() {
        synchronized(entries) { entries.clear() }
    }

    override fun awaitTermination(timeoutMillis: Long): Boolean = true
}
