package pro.ikili.kibicomputers.core.kernel.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.ResourceLimitException
import pro.ikili.kibicomputers.core.kernel.obj.ObjectTable
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.wait.WakeSink

class EventQueueTest {
    private class RecordingSink : WakeSink {
        val woken = ArrayList<WaitKey>()

        override fun wake(key: WaitKey) {
            woken += key
        }

        override fun wakeObject(objectId: Int) {}
    }

    private val objects = ObjectTable()
    private val sink = RecordingSink()

    private fun ev(type: String = "t", vararg values: EventValue) = KernelEvent(type, "", values.toList())

    private fun queue(hub: EventHub, filter: EventFilter = EventFilter.ALL, capacity: Int = EventLimits.DEFAULT_CAPACITY): EventQueue =
        EventQueue(filter, capacity).also {
            objects.add(it)
            hub.subscribe(it, objects)
        }

    private fun readable(q: EventQueue) = WaitKey(q.id, WaitCondition.READABLE)

    @Test
    fun `FIFOで取り出せ、maxで一部だけ取れる`() {
        val q = EventQueue(EventFilter.ALL).also { objects.add(it) }
        assertFalse(q.isReady(WaitCondition.READABLE))
        for (i in 1..3) q.offer(ev("t", EventValue.I64(i.toLong())), sink)
        assertTrue(q.isReady(WaitCondition.READABLE))
        assertFalse(q.isReady(WaitCondition.WRITABLE))
        assertTrue(q.canWait(WaitCondition.READABLE))
        assertFalse(q.canWait(WaitCondition.WRITABLE))
        val first = q.take(2)
        assertEquals(listOf(1L, 2L), first.events.map { (it.values[0] as EventValue.I64).value })
        assertEquals(1, q.size)
        assertEquals(listOf(3L), q.take(10).events.map { (it.values[0] as EventValue.I64).value })
        assertEquals(0, q.pendingBytes)
        assertTrue(q.take(1).isEmpty)
        assertThrows<IllegalArgumentException> { q.take(0) }
    }

    @Test
    fun `件数の上限を超えると新しいものを捨て、droppedが次のtakeで返って0に戻る`() {
        val q = EventQueue(EventFilter.ALL, capacity = 2).also { objects.add(it) }
        assertTrue(q.offer(ev("a"), sink))
        assertTrue(q.offer(ev("b"), sink))
        assertFalse(q.offer(ev("c"), sink))
        val batch = q.take(10)
        assertEquals(listOf("a", "b"), batch.events.map { it.type })
        assertEquals(1, batch.dropped)
        assertEquals(0, q.dropped)
        // あふれただけでも READABLE
        q.drop(sink)
        assertTrue(q.isReady(WaitCondition.READABLE))
        val onlyDropped = q.take(1)
        assertTrue(onlyDropped.events.isEmpty())
        assertEquals(1, onlyDropped.dropped)
        assertFalse(q.isReady(WaitCondition.READABLE))
    }

    @Test
    fun `offerとdropでREADABLEが知らされる`() {
        val q = EventQueue(EventFilter.ALL, capacity = 1).also { objects.add(it) }
        q.offer(ev(), sink)
        q.offer(ev(), sink)
        q.drop(sink)
        assertEquals(List(3) { readable(q) }, sink.woken)
    }

    @Test
    fun `capacityの範囲`() {
        for (bad in listOf(0, -1, EventLimits.MAX_CAPACITY + 1)) assertThrows<IllegalArgumentException> { EventQueue(EventFilter.ALL, bad) }
        EventQueue(EventFilter.ALL, 1)
        EventQueue(EventFilter.ALL, EventLimits.MAX_CAPACITY)
    }

    @Test
    fun `フィルターに合わないイベントは入らず、空のフィルターはすべて受け取る`() {
        val hub = EventHub()
        val all = queue(hub)
        val onlyA = queue(hub, EventFilter(setOf("a")))
        val ab = queue(hub, EventFilter(setOf("a", "b")))
        assertEquals(3, hub.post(ev("a"), objects, sink))
        assertEquals(2, hub.post(ev("b"), objects, sink))
        assertEquals(1, hub.post(ev("c"), objects, sink))
        assertEquals(listOf("a", "b", "c"), all.take(10).events.map { it.type })
        assertEquals(listOf("a"), onlyA.take(10).events.map { it.type })
        assertEquals(listOf("a", "b"), ab.take(10).events.map { it.type })
    }

    @Test
    fun `同報では同じイベントを共有する`() {
        val hub = EventHub()
        val q1 = queue(hub)
        val q2 = queue(hub)
        val e = ev("x", EventValue.Text("hello"))
        assertEquals(2, hub.post(e, objects, sink))
        assertTrue(q1.take(1).events[0] === q2.take(1).events[0])
    }

    @Test
    fun `キューがなければ捨てる`() {
        val hub = EventHub()
        assertEquals(0, hub.post(ev(), objects, sink))
        val q = queue(hub, EventFilter(setOf("a")))
        assertEquals(0, hub.post(ev("b"), objects, sink))
        assertEquals(0, q.size)
    }

    @Test
    fun `閉じたキューは次のpostで索引から外れる`() {
        val hub = EventHub()
        val q = queue(hub, EventFilter(setOf("a")))
        val other = queue(hub)
        objects.release(q.id, sink)
        assertEquals(2, hub.indexedCount)
        assertEquals(1, hub.post(ev("a"), objects, sink))
        assertEquals(1, hub.indexedCount)
        assertEquals(1, other.size)
    }

    @Test
    fun `生きているキューの数の上限でResourceLimitException`() {
        val hub = EventHub(maxQueues = 2)
        queue(hub)
        val second = queue(hub)
        assertThrows<ResourceLimitException> { queue(hub) }
        objects.release(second.id, sink)
        queue(hub) // 閉じた分は数えない
    }

    @Test
    fun `一度もpostされないキューを上限より多く開いて閉じても、次のsubscribeが通る`() {
        val hub = EventHub()
        repeat(EventLimits.MAX_QUEUES + 1) {
            val q = queue(hub, EventFilter(setOf("never")))
            objects.release(q.id, sink)
        }
        assertEquals(0, hub.queueCount(objects))
        queue(hub)
        assertEquals(1, hub.queueCount(objects))
    }

    @Test
    fun `古いIDに別のフィルターのキューが置かれても、古い種類のイベントは配られない`() {
        // 索引だけを持つ hub に、オブジェクト表の ID を使い回したのと同じ状態を作る
        val hub = EventHub()
        val oldObjects = ObjectTable()
        val old = EventQueue(EventFilter(setOf("old"))).also { oldObjects.add(it) }
        hub.subscribe(old, oldObjects)
        val reused = EventQueue(EventFilter(setOf("new"))).also { oldObjects.release(old.id, sink); objects.add(it) }
        assertEquals(old.id, reused.id)
        hub.subscribe(reused, objects)
        assertEquals(0, hub.post(ev("old"), objects, sink))
        assertEquals(0, reused.size)
        assertEquals(1, hub.post(ev("new"), objects, sink))
        // すべてを受け取る索引に残った古い ID からも二重に配らない
        val hub2 = EventHub()
        val objects2 = ObjectTable()
        val oldAll = EventQueue(EventFilter.ALL).also { objects2.add(it) }
        hub2.subscribe(oldAll, objects2)
        objects2.release(oldAll.id, sink)
        val objects3 = ObjectTable()
        val reusedTyped = EventQueue(EventFilter(setOf("t"))).also { objects3.add(it) }
        assertEquals(oldAll.id, reusedTyped.id)
        hub2.subscribe(reusedTyped, objects3)
        assertEquals(1, hub2.post(ev("t"), objects3, sink))
        assertEquals(1, reusedTyped.size)
    }

    @Test
    fun `マシン全体の量の上限で、ほかのキューへのpostもdroppedに数えられる`() {
        val one = ev("a").footprint
        val hub = EventHub(maxPendingBytes = one * 3)
        val full = queue(hub, EventFilter(setOf("a")))
        val other = queue(hub, EventFilter(setOf("b")))
        repeat(3) { assertEquals(1, hub.post(ev("a"), objects, sink)) }
        sink.woken.clear()
        assertEquals(0, hub.post(ev("b"), objects, sink))
        assertEquals(0, other.size)
        assertEquals(1, other.dropped)
        assertEquals(listOf(readable(other)), sink.woken)
        // 取り出すと再び入る
        full.take(1)
        assertEquals(1, hub.post(ev("b"), objects, sink))
        // 溜めたまま閉じたキューの分は数えない
        objects.release(full.id, sink)
        assertEquals(1, hub.post(ev("b"), objects, sink))
        assertEquals(1, hub.post(ev("b"), objects, sink))
        assertEquals(3, other.size)
        assertEquals(one * 3, other.pendingBytes)
    }

    @Test
    fun `KernelEventの大きさの上限`() {
        assertThrows<IllegalArgumentException> { KernelEvent("") }
        assertThrows<IllegalArgumentException> { KernelEvent("x".repeat(EventLimits.MAX_TYPE_BYTES + 1)) }
        KernelEvent("x".repeat(EventLimits.MAX_TYPE_BYTES))
        // 種類は UTF-8 のバイト数で数える（3 バイト × 22 = 66）
        assertThrows<IllegalArgumentException> { KernelEvent("あ".repeat(22)) }
        assertThrows<IllegalArgumentException> { KernelEvent("t", "s".repeat(EventLimits.MAX_SOURCE_BYTES + 1)) }
        KernelEvent("t", "s".repeat(EventLimits.MAX_SOURCE_BYTES))
        assertThrows<IllegalArgumentException> { KernelEvent("t", "", List(EventLimits.MAX_VALUES + 1) { EventValue.Bool(true) }) }
        KernelEvent("t", "", List(EventLimits.MAX_VALUES) { EventValue.Bool(true) })
        val half = EventLimits.MAX_PAYLOAD_BYTES / 2
        KernelEvent("t", "", listOf(EventValue.Text("a".repeat(half)), EventValue.Bytes(ByteArray(half))))
        assertThrows<IllegalArgumentException> {
            KernelEvent("t", "", listOf(EventValue.Text("a".repeat(half)), EventValue.Bytes(ByteArray(half + 1))))
        }
        assertThrows<IllegalArgumentException> { EventFilter(List(EventLimits.MAX_FILTER_TYPES + 1) { "t$it" }.toSet()) }
        assertThrows<IllegalArgumentException> { EventFilter(setOf("")) }
    }

    @Test
    fun `footprintが中身に合い、値の数に比例して増える`() {
        val base = EventLimits.EVENT_OVERHEAD_BYTES + EventLimits.OBJECT_OVERHEAD_BYTES + 1
        assertEquals(base, KernelEvent("t").footprint)
        assertEquals(base + EventLimits.OBJECT_OVERHEAD_BYTES + 3, KernelEvent("t", "src").footprint)
        for (n in listOf(1, 2, EventLimits.MAX_VALUES)) {
            val e = KernelEvent("t", "", List(n) { EventValue.I64(it.toLong()) })
            assertEquals(base + n * EventLimits.EVENT_VALUE_OVERHEAD_BYTES, e.footprint)
        }
        val text = KernelEvent("t", "", listOf(EventValue.Text("日本")))
        assertEquals(base + EventLimits.EVENT_VALUE_OVERHEAD_BYTES + EventLimits.OBJECT_OVERHEAD_BYTES + 6, text.footprint)
        val bytes = KernelEvent("t", "", listOf(EventValue.Bytes(ByteArray(10))))
        assertEquals(base + EventLimits.EVENT_VALUE_OVERHEAD_BYTES + EventLimits.OBJECT_OVERHEAD_BYTES + 10, bytes.footprint)
        // MAX_VALUES 個の数値を持つイベントは、空のイベントより値の数 × 固定のコストだけ大きい
        val numbers = KernelEvent("t", "", List(EventLimits.MAX_VALUES) { EventValue.F64(it.toDouble()) })
        assertTrue(numbers.footprint >= EventLimits.MAX_VALUES * EventLimits.EVENT_VALUE_OVERHEAD_BYTES)
    }

    @Test
    fun `Bytesは写しを持ち、中身で比べる`() {
        val src = byteArrayOf(1, 2, 3)
        val b = EventValue.Bytes(src)
        src[0] = 9
        assertEquals(1, b.toByteArray()[0])
        b.toByteArray()[1] = 9
        assertEquals(2, b.toByteArray()[1])
        assertEquals(EventValue.Bytes(byteArrayOf(1, 2, 3)), b)
        assertNotEquals(EventValue.Bytes(byteArrayOf(1, 2)), b)
        val values = arrayListOf<EventValue>(EventValue.I64(1))
        val e = KernelEvent("t", "", values)
        values += EventValue.I64(2)
        assertEquals(1, e.values.size)
        assertEquals(KernelEvent("t", "", listOf(EventValue.I64(1))), e)
    }

    @Test
    fun `閉じたキューは中身を手放す`() {
        val hub = EventHub()
        val q = queue(hub)
        hub.post(ev(), objects, sink)
        objects.release(q.id, sink)
        assertEquals(0, q.size)
        assertEquals(0, q.pendingBytes)
    }
}
