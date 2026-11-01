package pro.ikili.kibicomputers.core.kernel.time

import pro.ikili.kibicomputers.core.kernel.obj.KernelObject
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.wait.WakeSink

/** タイマーの上限など（仮の値。確定は #251） */
internal object TimerLimits {
    /** マシンで同時に動かせる（期限を待っている）タイマーの数 */
    const val MAX_ARMED_TIMERS: Int = 128

    /** 繰り返しのタイマーの最小の間隔（ナノ秒。1 tick = 50 ms。画面への反映も tick ごとなので、これより細かくしても見えない） */
    const val MIN_INTERVAL_NANOS: Long = 50_000_000

    /** タイマーのイベントの種類（[pro.ikili.kibicomputers.core.kernel.event.KernelEvent.type]） */
    const val EVENT_TYPE: String = "timer"
}

/**
 * タイマー（カーネルオブジェクト。プロセスがハンドルで持つ。#64、ADR 0064）。`READABLE` を待てる。
 *
 * 期限（[deadlineNanos]）はマシンの単調時計（`KernelClocks.monotonicNanos`）で持つ。期限が来たら [expirations] を数え、
 * 数が 1 以上の間は `READABLE`（Linux の timerfd と同じ形。[read] で数を取り出して 0 に戻す）。
 * [intervalNanos] が 0 なら1回だけ、正なら繰り返す。遅れて鳴ったとき（サーバーが重い、tick が止まっていた）は、
 * 過ぎた回数をまとめて数え、次の期限を「今より後のいちばん近い回」に進める（溜まった分を続けて鳴らさない）。
 *
 * [eventQueueId] が 0 でなければ、鳴るたびにそのイベントキューへ `timer` のイベント（値は [eventTag] と回数）を入れる。
 *
 * 期限が来たことを知るのは実行時の側（`MachineTimers`）。この型は保存できる値だけを持つ。カーネルロックの中で使う。
 */
internal class Timer(
    /** 鳴ったときにイベントを入れるキューのオブジェクトID（0 ならイベントを出さない） */
    val eventQueueId: Int = 0,
    /** イベントに入れる値（プログラムがタイマーを見分けるため） */
    val eventTag: Long = 0,
) : KernelObject() {
    /** 期限を待っているか */
    var armed: Boolean = false
        private set

    /** 次の期限（マシンの単調時計。[armed] のときだけ意味がある） */
    var deadlineNanos: Long = 0
        private set

    /** 繰り返しの間隔（0 なら1回だけ） */
    var intervalNanos: Long = 0
        private set

    /** 前回の [read] から鳴った回数 */
    var expirations: Long = 0
        private set

    /** [arm]・[disarm] のたびに増える番号。実行時の側が、古い知らせを見分けるのに使う */
    var generation: Long = 0
        private set

    /** イベントを出すタイマーか */
    val sendsEvents: Boolean get() = eventQueueId != 0

    override fun canWait(condition: WaitCondition): Boolean = condition == WaitCondition.READABLE

    override fun isReady(condition: WaitCondition): Boolean = condition == WaitCondition.READABLE && expirations > 0

    /** [deadline] に鳴るように設定する（[interval] が正なら、その後は間隔ごと）。数えた回数は 0 に戻す */
    fun arm(deadline: Long, interval: Long) {
        require(interval >= 0) { "間隔は 0 以上（$interval）" }
        armed = true
        deadlineNanos = deadline
        intervalNanos = interval
        expirations = 0
        generation++
    }

    /** 止める。数えた回数は 0 に戻す */
    fun disarm() {
        armed = false
        expirations = 0
        generation++
    }

    /**
     * 今 [now] で期限が来ていれば鳴らし、鳴った回数を返す（来ていなければ 0）。回数を [expirations] に足し、
     * 1回だけなら止め、繰り返しなら次の期限（[now] より後）へ進める。鳴ったら `(id, READABLE)` を知らせる
     */
    fun expire(now: Long, sink: WakeSink): Long {
        if (!armed || now < deadlineNanos) return 0
        val count: Long
        if (intervalNanos == 0L) {
            count = 1
            armed = false
        } else {
            val late = saturatingSub(now, deadlineNanos)
            count = saturatingAdd(late / intervalNanos, 1)
            deadlineNanos = saturatingAdd(deadlineNanos, saturatingMul(count, intervalNanos))
            if (deadlineNanos == Long.MAX_VALUE) armed = false
        }
        expirations = saturatingAdd(expirations, count)
        sink.wake(WaitKey(id, WaitCondition.READABLE))
        return count
    }

    /** 鳴った回数を取り出して 0 に戻す */
    fun read(): Long {
        val n = expirations
        expirations = 0
        return n
    }

    override fun onLastClose(sink: WakeSink) {
        armed = false
        expirations = 0
        sink.timerClosed(id)
    }
}
