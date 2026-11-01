package pro.ikili.kibicomputers.core.kernel.event

import pro.ikili.kibicomputers.core.kernel.ResourceLimitException
import pro.ikili.kibicomputers.core.kernel.obj.ObjectTable
import pro.ikili.kibicomputers.core.kernel.wait.WakeSink

/**
 * 種類 → イベントキューのオブジェクトID の索引（マシンに1つ。`KernelState.events`。保存の対象で、番号と文字列だけを持つ）。
 *
 * キューが閉じても（`onLastClose` は `KernelState` に触れないので）索引はすぐには直さない。
 * [subscribe]（上限を確かめる前）・[post]・[sweep]（起動のとき）で、`objects[id]` が索引の場所に合う `EventQueue` でない ID を外す。
 * 索引の場所に合うとは、すべてを受け取る索引ならフィルターが空、種類の索引ならフィルターがその種類を含むこと
 * （`ObjectTable` の ID が一周して使い回されたとき、古い種類の下に残った ID から配らないため）。
 * 表は最初に使うときに作る。カーネルロックの中で使う。
 */
internal class EventHub(
    val maxQueues: Int = EventLimits.MAX_QUEUES,
    val maxPendingBytes: Int = EventLimits.MAX_PENDING_BYTES,
) {
    init {
        require(maxQueues > 0) { "キューの数の上限は 1 以上" }
        require(maxPendingBytes > 0) { "溜める量の上限は 1 以上" }
    }

    /** 索引に入れたキューの ID（掃除していない閉じたキューを含む） */
    private var queues: LinkedHashSet<Int>? = null

    /** すべてを受け取るキューの ID */
    private var all: LinkedHashSet<Int>? = null

    /** 種類 → その種類を受け取るキューの ID */
    private var byType: HashMap<String, LinkedHashSet<Int>>? = null

    /** 索引にある ID の数（掃除していない閉じたキューを含む。テスト用） */
    val indexedCount: Int get() = queues?.size ?: 0

    /** 閉じたキューの ID を外してから、生きているキューの数を返す */
    fun queueCount(objects: ObjectTable): Int {
        sweep(objects)
        return indexedCount
    }

    /** 閉じたキューの ID を外してから、生きているキューが上限なら [ResourceLimitException] */
    @Throws(ResourceLimitException::class)
    fun ensureCapacity(objects: ObjectTable) {
        if (queueCount(objects) >= maxQueues) throw ResourceLimitException("イベントキューの数が上限（$maxQueues）")
    }

    /** 閉じたキューの ID を外してから、[queue]（オブジェクト表に入っていること）を索引に足す。生きているキューが上限なら [ResourceLimitException] */
    @Throws(ResourceLimitException::class)
    fun subscribe(queue: EventQueue, objects: ObjectTable) {
        val id = queue.id
        require(id != 0 && objects[id] === queue) { "オブジェクト表に入っていないキュー" }
        ensureCapacity(objects)
        (queues ?: LinkedHashSet<Int>().also { queues = it }).add(id)
        if (queue.filter.acceptsAll) {
            (all ?: LinkedHashSet<Int>().also { all = it }).add(id)
        } else {
            val map = byType ?: HashMap<String, LinkedHashSet<Int>>().also { byType = it }
            for (t in queue.filter.types) map.getOrPut(t) { LinkedHashSet() }.add(id)
        }
    }

    /**
     * [event] を、種類の合う生きているキューに配り、入れられたキューの数を返す（捨てた分は数えない）。
     * 見つからない・場所に合わない ID は索引から外す。マシン全体の溜めている量が [maxPendingBytes] を超えるなら、
     * そのキューには入れずに `drop` する（READABLE は知らせる）
     */
    fun post(event: KernelEvent, objects: ObjectTable, sink: WakeSink): Int {
        val candidatesAll = all
        val candidatesType = byType?.get(event.type)
        if (candidatesAll.isNullOrEmpty() && candidatesType.isNullOrEmpty()) return 0
        var total = pendingBytes(objects)
        var delivered = 0
        fun deliver(set: LinkedHashSet<Int>?, matches: (EventQueue) -> Boolean) {
            if (set == null) return
            val iter = set.iterator()
            while (iter.hasNext()) {
                val id = iter.next()
                val q = objects[id] as? EventQueue
                if (q == null || !matches(q)) {
                    iter.remove()
                    if (q == null) queues?.remove(id)
                    continue
                }
                if (total.toLong() + event.footprint > maxPendingBytes) {
                    q.drop(sink)
                } else if (q.offer(event, sink)) {
                    total += event.footprint
                    delivered++
                }
            }
        }
        deliver(candidatesAll) { it.filter.acceptsAll }
        deliver(candidatesType) { !it.filter.acceptsAll && event.type in it.filter.types }
        if (candidatesType != null && candidatesType.isEmpty()) byType?.remove(event.type)
        return delivered
    }

    /**
     * 種類の索引を通さずに、[queue] だけへ [event] を入れる（タイマーのイベント。#64）。マシン全体の溜めている量の上限は
     * [post] と同じく守り、超えるなら入れずに `drop` する。入れられたら `true`
     */
    fun deliverTo(queue: EventQueue, event: KernelEvent, objects: ObjectTable, sink: WakeSink): Boolean {
        if (pendingBytes(objects).toLong() + event.footprint > maxPendingBytes) {
            queue.drop(sink)
            return false
        }
        return queue.offer(event, sink)
    }

    /** 索引の場所に合う `EventQueue` でない ID を外す（件数は索引の ID の数に比例する） */
    fun sweep(objects: ObjectTable) {
        val ids = queues ?: return
        ids.removeAll { objects[it] !is EventQueue }
        all?.removeAll { (objects[it] as? EventQueue)?.filter?.acceptsAll != true }
        byType?.let { map ->
            val iter = map.entries.iterator()
            while (iter.hasNext()) {
                val (type, set) = iter.next()
                set.removeAll { id -> (objects[id] as? EventQueue)?.filter?.types?.contains(type) != true }
                if (set.isEmpty()) iter.remove()
            }
        }
    }

    /** 生きているキューの [EventQueue.pendingBytes] の合計（キューは [maxQueues] 個ほどまで） */
    private fun pendingBytes(objects: ObjectTable): Int {
        var sum = 0L
        queues?.forEach { id -> (objects[id] as? EventQueue)?.let { sum += it.pendingBytes } }
        return sum.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
