package pro.ikili.kibicomputers.core.kernel.time

/**
 * マシンのタイマーの、実行時だけの部分（`Machine.timers`。保存しない）。タイマーのオブジェクトID → [TimerService] の予定。
 *
 * 予定は「期限を待っている」タイマーにだけ入れる。数えた回数を読まれていない繰り返しのタイマー（イベントを出さないもの）は、
 * 次の期限の予定を入れずに止めておき、読まれたときにまとめて数える（読まれない繰り返しのタイマーが、スレッドを起こし続けないため）。
 * どれもカーネルロックの中で使う。表は最初に使うときに作る。
 *
 * @param onDue 期限が来た（タイマーのオブジェクトID, 予定を入れたときの `Timer.generation`）。[TimerService] のスレッドから、ロックを持たずに呼ばれる
 */
internal class MachineTimers(private val service: TimerService, private val onDue: (Int, Long) -> Unit) {
    private var pending: HashMap<Int, TimerService.Handle>? = null

    /** 予定を入れているタイマーの数 */
    val scheduledCount: Int get() = pending?.size ?: 0

    /** [timerId] に予定を入れているか */
    fun isScheduled(timerId: Int): Boolean = pending?.containsKey(timerId) == true

    /** [timerId] の予定を入れ直す（前の予定は取り消す） */
    fun schedule(timerId: Int, generation: Long, delayNanos: Long) {
        cancel(timerId)
        val map = pending ?: HashMap<Int, TimerService.Handle>().also { pending = it }
        map[timerId] = service.schedule(delayNanos) { onDue(timerId, generation) }
    }

    /** [timerId] の予定を取り消す */
    fun cancel(timerId: Int) {
        val map = pending ?: return
        map.remove(timerId)?.cancel()
        if (map.isEmpty()) pending = null
    }

    /** 予定を表から外す（鳴った後に、入れ直さないとき。取り消しはしない） */
    fun forget(timerId: Int) {
        val map = pending ?: return
        map.remove(timerId)
        if (map.isEmpty()) pending = null
    }

    /** すべて取り消す */
    fun cancelAll() {
        val map = pending ?: return
        pending = null
        map.values.forEach { it.cancel() }
    }
}
