package pro.ikili.kibicomputers.core.runtime.validation

import java.lang.management.ManagementFactory
import java.lang.management.ThreadMXBean
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 途中で時間を確かめられない処理（コンポーネントの wasm-tools の検証）を、時間の上限（[LoadDeadline]）で止める見張り（#666）。
 *
 * 見張る処理は、スレッドの割り込み（`Thread.interrupt`）で止まるものに限る（Endive の実行は、ループなどで割り込みの印を見て
 * `WasmInterruptedException` で止まる）。[watch] で見張りを始め、処理が終わったら（例外のときも）必ず [Watch.finish] を呼ぶ。
 *
 * - 時間は [LoadDeadline] と同じ時計で測る。CPU 時間で測る上限なら、見張るスレッドの CPU 時間を、ほかのスレッドから
 *   読む（`ThreadMXBean.getThreadCpuTime`）。読めない JVM では経過時間で代え、[WALL_FALLBACK_FACTOR] 倍まで待つ
 *   （経過時間は CPU 時間より長いので、そのままだと上限より早く切ってしまう。混んだサーバーで正しいものを断らないため）
 * - 最初の確認は、残りの時間の後（CPU 時間は経過時間より進まないので、それより前には超えない）。まだ超えていなければ、
 *   残りの分だけ後にもう一度確かめる（間隔は [MIN_DELAY_NANOS] 以上）
 * - 超えていたら、ロックの中で状態を「時間切れ」にし、同じロックの中で割り込む。[Watch.finish] も同じロックで状態を「終わり」にし、
 *   時間切れだったら割り込みの印を下ろす。だから、処理が終わった後に割り込みが遅れて届き、同じスレッドの次の仕事
 *   （Endive の読み込み、別の `ProgramCache` の仕事など）を止めることはない
 *
 * 予定は、全体で共有する1本のタイマーのスレッド（[shared]。最初に使うときに作り、仕事がなければ終わる daemon）で待つ。
 * 同時に見張るのは、コンパイル用のスレッドの本数まで（既定 1 本）なので、台数が増えてもスレッドは増えない。
 * 取り消した予定はすぐ外す（普段の検証は数十 ms で終わり、そのたびに予定を取り消す）。
 */
internal class ValidationWatchdog(
    private val timer: Timer,
    /** 別のスレッドの CPU 時間を読む（テストで差し替える）。読めなければ -1 */
    private val cpuReader: (Thread) -> Long = ::threadCpuNanos,
    /** 経過時間の時計（テストで差し替える） */
    private val wallClock: () -> Long = System::nanoTime,
) {
    /** 予定を入れるもの（テストで差し替える）。[schedule] は [delayNanos] 後に [task] を動かし、取り消せる [Future] を返す */
    fun interface Timer {
        fun schedule(delayNanos: Long, task: Runnable): Future<*>
    }

    /**
     * 今のスレッドを、[deadline] の上限で見張り始める。[deadline] は今のスレッドで作ったもの、または今のスレッドで
     * [LoadDeadline.check] を呼ぶもの
     */
    fun watch(deadline: LoadDeadline): Watch {
        val w = Watch(Thread.currentThread(), deadline)
        w.start()
        return w
    }

    /** 1回の見張り */
    inner class Watch internal constructor(private val thread: Thread, private val deadline: LoadDeadline) {
        private val lock = Any()
        private var state = State.RUNNING
        private var pending: Future<*>? = null

        /** 見張りを始めた時点で使っていた時間 */
        private val base = deadline.elapsedNanos

        /** 見張りを始めた時点の時計の値（CPU 時間が読めなければ経過時間） */
        private val cpuAtStart: Long = if (deadline.usesCpuTime) cpuReader(thread) else -1
        private val wallAtStart: Long = wallClock()

        /** 時間切れで割り込んだか */
        val timedOut: Boolean get() = synchronized(lock) { state == State.TIMED_OUT }

        internal fun start() {
            synchronized(lock) { reschedule(remainingWallNanos()) }
        }

        /**
         * 予定の時刻に、タイマーのスレッドから呼ばれる。超えていれば時間切れにして割り込み、まだなら予定を入れ直す。
         * 終わった後に呼ばれても何もしない
         */
        internal fun fire() {
            synchronized(lock) {
                if (state != State.RUNNING) return
                pending = null
                if (exceeded()) {
                    state = State.TIMED_OUT
                    // ロックの中で割り込む（finish が印を下ろすのと順番が入れ替わらない）
                    thread.interrupt()
                } else {
                    reschedule(remainingWallNanos())
                }
            }
        }

        /**
         * 見張りを終える。見張っていたスレッドで、処理が終わったら（例外のときも）必ず呼ぶ。時間切れで割り込んだときは
         * 割り込みの印を下ろして `true` を返す（呼んだ側は時間切れとして断る）。
         *
         * 時間切れと、ほかの理由の割り込み（スケジューラの停止の `shutdownNow`）が重なると、後者の印もここで下ろす。
         * 停止の後はスレッドのプールが新しい仕事を取らずに終わり、`ProgramCache` も閉じた例外で待ちを終えるので、失われて困ることはない
         */
        fun finish(): Boolean {
            check(Thread.currentThread() === thread) { "見張っていたスレッドから呼ぶ" }
            synchronized(lock) {
                pending?.cancel(false)
                pending = null
                val wasTimedOut = state == State.TIMED_OUT
                state = State.DONE
                if (wasTimedOut) Thread.interrupted()
                return wasTimedOut
            }
        }

        /** 上限を超えたか（[LoadDeadline] の時計にそろえて測る） */
        private fun exceeded(): Boolean = effectiveElapsed() > deadline.limitNanos

        /** [LoadDeadline] の時計で見た、使った時間 */
        private fun effectiveElapsed(): Long {
            if (deadline.usesCpuTime) {
                val cpu = if (cpuAtStart >= 0) cpuReader(thread) else -1
                if (cpu >= 0) return base + (cpu - cpuAtStart)
                // 別のスレッドの CPU 時間が読めない: 経過時間で代え、早く切らないように割り引く
                return base + (wallClock() - wallAtStart) / WALL_FALLBACK_FACTOR
            }
            return base + (wallClock() - wallAtStart)
        }

        /** 上限までの残りを、待つ経過時間にしたもの */
        private fun remainingWallNanos(): Long {
            val remaining = deadline.limitNanos - effectiveElapsed()
            val cpuReadable = deadline.usesCpuTime && cpuAtStart >= 0
            val wall = if (deadline.usesCpuTime && !cpuReadable) saturatedTimes(remaining, WALL_FALLBACK_FACTOR) else remaining
            return wall.coerceAtLeast(MIN_DELAY_NANOS)
        }

        private fun reschedule(delayNanos: Long) {
            pending = try {
                timer.schedule(delayNanos) { fire() }
            } catch (e: RejectedExecutionException) {
                // タイマーが使えない（JVM の終了中など）。見張らずに続ける（終わった後の LoadDeadline.check は効く）
                null
            }
        }
    }

    private enum class State { RUNNING, TIMED_OUT, DONE }

    internal companion object {
        /** 予定の間隔の下限（空回りしないため） */
        const val MIN_DELAY_NANOS: Long = 10_000_000L

        /** 別のスレッドの CPU 時間が読めないとき、経過時間を割り引く倍率（上限の何倍の経過時間まで待つか） */
        const val WALL_FALLBACK_FACTOR: Long = 4

        /**
         * タイマーのスレッドの名前。JVM に 1 本で、スケジューラのものではない（テストの補助が、カーネルのスレッドの残りから除くのに使う。#990）
         */
        const val THREAD_NAME: String = "KiBi-wasm-watchdog"

        /** 仕事がないときにタイマーのスレッドを残しておく時間（ミリ秒。検証が続くあいだは残り、止まればすぐ終わる） */
        private const val IDLE_MILLIS = 1_000L

        private val bean: ThreadMXBean? = try {
            ManagementFactory.getThreadMXBean().takeIf { it.isThreadCpuTimeSupported }
        } catch (e: Exception) {
            null
        } catch (e: LinkageError) {
            null
        }

        /** [thread] の CPU 時間（ナノ秒）。読めなければ -1 */
        fun threadCpuNanos(thread: Thread): Long = try {
            @Suppress("DEPRECATION")
            bean?.getThreadCpuTime(thread.id) ?: -1
        } catch (e: Exception) {
            -1
        }

        private fun saturatedTimes(a: Long, b: Long): Long =
            if (a > Long.MAX_VALUE / b) Long.MAX_VALUE else a * b

        /**
         * タイマーのスレッド1本の実行器を作る。スレッドは daemon で、優先度は通常に固定する（指定しないと、`schedule` を呼んだスレッド、
         * つまり本番では最低優先度のコンパイル用のスレッドから引き継ぎ、時間の上限を守らせる側が遅れうる）。
         * 仕事がなければ [idleMillis] 後に終わる（作り直しは仕事が来たとき）
         */
        fun newExecutor(idleMillis: Long): ScheduledThreadPoolExecutor =
            ScheduledThreadPoolExecutor(1) { r ->
                Thread(r, THREAD_NAME).apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY
                }
            }.apply {
                removeOnCancelPolicy = true
                setKeepAliveTime(idleMillis, TimeUnit.MILLISECONDS)
                allowCoreThreadTimeOut(true)
            }

        private val executorLazy: Lazy<ScheduledThreadPoolExecutor> = lazy { newExecutor(IDLE_MILLIS) }
        private val executor: ScheduledThreadPoolExecutor by executorLazy

        /**
         * 共有の見張りの待ち行列に残っている予定の数（テスト用。#990）。`Watch.finish` の呼び忘れを、スレッドが終わるのを待たずに見つける。
         * 共有の実行器をまだ作っていなければ、作らずに 0 を返す
         */
        fun sharedPendingCount(): Int = if (executorLazy.isInitialized()) executorLazy.value.queue.size else 0

        /** 全体で共有する見張り（タイマーのスレッド1本） */
        val shared: ValidationWatchdog by lazy {
            ValidationWatchdog(timer = { delay, task -> executor.schedule(task, delay, TimeUnit.NANOSECONDS) })
        }
    }
}
