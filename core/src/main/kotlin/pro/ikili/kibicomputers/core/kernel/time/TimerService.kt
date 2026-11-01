package pro.ikili.kibicomputers.core.kernel.time

import pro.ikili.kibicomputers.core.kernel.sched.KernelThread
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 期限が来たら知らせる口（スケジューラに1つ。実行時だけ。#64、ADR 0064）。
 *
 * 既定は [ExecutorTimerService]（スレッド `KiBi-timer` 1本）。テストでは時計を手で進めるものに差し替える。
 * [schedule] はカーネルロックの中から呼んでよい（中のロックは末端で、待たない）。
 */
internal interface TimerService {
    /** 取り消しの口 */
    fun interface Handle {
        /** まだ実行していなければ取り消す。2回呼んでもよい */
        fun cancel()
    }

    /** [delayNanos] 後（0 以下ならすぐ）に [task] を実行する。閉じた後なら何もしない */
    fun schedule(delayNanos: Long, task: Runnable): Handle

    /** 予定をすべて捨て、スレッドを終える。待たない */
    fun close()

    /** スレッドが終わるまで待つ。終わったら `true` */
    @Throws(InterruptedException::class)
    fun awaitTermination(timeoutMillis: Long): Boolean
}

/**
 * [ScheduledThreadPoolExecutor]（スレッド1本。最初に予定を入れるときに作る）で期限を待つ。
 *
 * 予定は二分ヒープで持つので、台数が増えても入れる・取り消すのは O(log n)、待っている間はスレッドが眠るだけ。
 * 取り消した予定はすぐヒープから外す（長いタイマーを作っては閉じても溜まらない）。
 * 実行する仕事（マシンのカーネルロックを短く取って、タイマーを鳴らす）はメインスレッドに乗らない。
 */
internal class ExecutorTimerService(stackBytes: Long) : TimerService {
    private val executor = ScheduledThreadPoolExecutor(1) { r -> KernelThread(THREAD_NAME, stackBytes, r) }.apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
        continueExistingPeriodicTasksAfterShutdownPolicy = false
    }

    override fun schedule(delayNanos: Long, task: Runnable): TimerService.Handle {
        val future: ScheduledFuture<*> = try {
            executor.schedule(task, delayNanos.coerceAtLeast(0), TimeUnit.NANOSECONDS)
        } catch (e: RejectedExecutionException) {
            return TimerService.Handle {}
        }
        return TimerService.Handle { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }

    override fun awaitTermination(timeoutMillis: Long): Boolean = executor.awaitTermination(timeoutMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)

    companion object {
        const val THREAD_NAME: String = "KiBi-timer"
    }
}
