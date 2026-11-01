package pro.ikili.kibicomputers.core.kernel.event

import pro.ikili.kibicomputers.core.kernel.obj.KernelObject
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.wait.WakeSink

/**
 * 受け取るイベントの種類。空ならすべて受け取る。種類は `MAX_FILTER_TYPES` 個まで、それぞれ 1..`MAX_TYPE_BYTES` バイト
 * （超えたら [IllegalArgumentException]）。
 */
internal class EventFilter(types: Set<String> = emptySet()) {
    /** 受け取る種類（作るときに写す） */
    val types: Set<String> = if (types.isEmpty()) emptySet() else LinkedHashSet(types)

    init {
        require(this.types.size <= EventLimits.MAX_FILTER_TYPES) { "フィルターの種類は ${EventLimits.MAX_FILTER_TYPES} 個まで（${this.types.size}）" }
        for (t in this.types) {
            val n = t.toByteArray(Charsets.UTF_8).size
            require(n in 1..EventLimits.MAX_TYPE_BYTES) { "イベントの種類は 1..${EventLimits.MAX_TYPE_BYTES} バイト（$n）" }
        }
    }

    /** すべての種類を受け取る */
    val acceptsAll: Boolean get() = types.isEmpty()

    /** [event] を受け取るか */
    fun accepts(event: KernelEvent): Boolean = acceptsAll || event.type in types

    override fun equals(other: Any?): Boolean = other is EventFilter && types == other.types

    override fun hashCode(): Int = types.hashCode()

    override fun toString(): String = if (acceptsAll) "EventFilter(ALL)" else "EventFilter($types)"

    companion object {
        /** すべて受け取る */
        val ALL: EventFilter = EventFilter()
    }
}

/** [EventQueue.take] の結果。[dropped] は前回の take からあふれて捨てた数 */
internal data class EventBatch(val events: List<KernelEvent>, val dropped: Long) {
    /** イベントも、捨てた知らせもない */
    val isEmpty: Boolean get() = events.isEmpty() && dropped == 0L
}

/**
 * イベントキュー（カーネルオブジェクト。プロセスがハンドルで持つ。ADR 0063）。`READABLE` を待てる。
 *
 * FIFO で、[capacity] 件まで溜める。あふれたら新しいイベントを捨て、[dropped] に数える（次の [take] で知らせて 0 に戻す）。
 * `dropped > 0` だけでも `READABLE`。中身はどれも保存できる値。並びは最初に入れるときに作り、空になったら手放す。
 * カーネルロックの中で使う。
 */
internal class EventQueue(
    val filter: EventFilter,
    val capacity: Int = EventLimits.DEFAULT_CAPACITY,
) : KernelObject() {
    init {
        require(capacity in 1..EventLimits.MAX_CAPACITY) { "キューの件数は 1..${EventLimits.MAX_CAPACITY}（$capacity）" }
    }

    private var events: ArrayDeque<KernelEvent>? = null

    /** 溜めているイベントの数 */
    val size: Int get() = events?.size ?: 0

    /** 溜めているイベントの [KernelEvent.footprint] の合計 */
    var pendingBytes: Int = 0
        private set

    /** 前回の [take] からあふれて捨てた数 */
    var dropped: Long = 0
        private set

    override fun canWait(condition: WaitCondition): Boolean = condition == WaitCondition.READABLE

    override fun isReady(condition: WaitCondition): Boolean = condition == WaitCondition.READABLE && (size > 0 || dropped > 0)

    /** [event] を入れる。満杯なら捨てて [dropped] を数え、`false`。どちらでも `(id, READABLE)` を知らせる */
    fun offer(event: KernelEvent, sink: WakeSink): Boolean {
        val ok = size < capacity
        if (ok) {
            val q = events ?: ArrayDeque<KernelEvent>().also { events = it }
            q.addLast(event)
            pendingBytes += event.footprint
        } else {
            dropped++
        }
        sink.wake(WaitKey(id, WaitCondition.READABLE))
        return ok
    }

    /** 入れずに [dropped] を数え、`(id, READABLE)` を知らせる（マシン全体の量が上限のとき。[EventHub] から） */
    fun drop(sink: WakeSink) {
        dropped++
        sink.wake(WaitKey(id, WaitCondition.READABLE))
    }

    /** 先頭から最大 [max] 個（1 以上。そうでなければ [IllegalArgumentException]）取り出し、[dropped] を 0 に戻す */
    fun take(max: Int): EventBatch {
        require(max >= 1) { "取り出す数は 1 以上（$max）" }
        val q = events
        val out: List<KernelEvent> = if (q == null) {
            emptyList()
        } else {
            val n = minOf(max, q.size)
            val list = ArrayList<KernelEvent>(n)
            repeat(n) {
                val e = q.removeFirst()
                pendingBytes -= e.footprint
                list += e
            }
            if (q.isEmpty()) {
                events = null
                pendingBytes = 0
            }
            list
        }
        val batch = EventBatch(out, dropped)
        dropped = 0
        return batch
    }

    override fun onLastClose(sink: WakeSink) {
        events = null
        pendingBytes = 0
        dropped = 0
    }
}
