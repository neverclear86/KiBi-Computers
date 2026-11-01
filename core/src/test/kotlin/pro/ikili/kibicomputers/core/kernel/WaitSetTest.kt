package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.ObjectTable
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.wait.WaitRegistry
import pro.ikili.kibicomputers.core.kernel.wait.WaitSet
import pro.ikili.kibicomputers.core.kernel.wait.Waiter

class WaitSetTest {
    private class CountingWaiter : Waiter() {
        var wakes = 0

        override fun onWake() {
            wakes++
        }
    }

    private val objects = ObjectTable()
    private val registry = WaitRegistry()

    private fun stream(): ByteStream = ByteStream(16).also { objects.add(it) }

    private fun readable(s: ByteStream) = WaitKey(s.id, WaitCondition.READABLE)

    private fun writable(s: ByteStream) = WaitKey(s.id, WaitCondition.WRITABLE)

    @Test
    fun `どれかがそろったら起き、待ちが終わったら登録が外れる`() {
        val a = stream()
        val b = stream()
        val waiter = CountingWaiter()
        val ws = WaitSet.register(registry, objects, listOf(readable(a), readable(b)), waiter)
        assertTrue(ws.readyKeys(objects).isEmpty())
        b.write(byteArrayOf(1), 0, 1, registry)
        assertEquals(1, waiter.wakes)
        assertEquals(listOf(readable(b)), ws.readyKeys(objects))
        ws.unregister(registry)
        assertEquals(0, registry.size)
    }

    @Test
    fun `最初からそろっていればすぐ返る`() {
        val a = stream()
        val ws = WaitSet.register(registry, objects, listOf(writable(a)), CountingWaiter())
        assertEquals(listOf(writable(a)), ws.readyKeys(objects))
    }

    @Test
    fun `変わったほうのkeyに登録したWaiterだけが起きる`() {
        val a = stream()
        val onRead = CountingWaiter()
        val onWrite = CountingWaiter()
        WaitSet.register(registry, objects, listOf(readable(a)), onRead)
        WaitSet.register(registry, objects, listOf(writable(a)), onWrite)
        a.write(byteArrayOf(1), 0, 1, registry)
        assertEquals(1, onRead.wakes)
        assertEquals(0, onWrite.wakes)
    }

    @Test
    fun `WaitKeyの並びと新しいWaitRegistryから作り直しても同じ条件で待てる`() {
        val a = stream()
        val saved = listOf(readable(a))
        val fresh = WaitRegistry()
        val waiter = CountingWaiter()
        val ws = WaitSet.register(fresh, objects, saved, waiter)
        a.write(byteArrayOf(1), 0, 1, fresh)
        assertEquals(1, waiter.wakes)
        assertEquals(saved, ws.readyKeys(objects))
    }

    @Test
    fun `待っている間にオブジェクトが閉じられたら起き、見つからないkeyはそろった扱い`() {
        val a = stream()
        val waiter = CountingWaiter()
        val ws = WaitSet.register(registry, objects, listOf(readable(a)), waiter)
        objects.release(a.id, registry)
        assertEquals(1, waiter.wakes)
        assertEquals(listOf(readable(a)), ws.readyKeys(objects))
        val missing = WaitSet.register(registry, objects, listOf(WaitKey(9999, WaitCondition.READABLE)), CountingWaiter())
        assertEquals(1, missing.readyKeys(objects).size)
    }

    @Test
    fun `起こされた後に空になったらrearmで待ち直し、印が立っている間のwakeは数えない`() {
        val a = stream()
        val p = CountingWaiter()
        val q = CountingWaiter()
        val wp = WaitSet.register(registry, objects, listOf(readable(a)), p)
        val wq = WaitSet.register(registry, objects, listOf(readable(a)), q)
        a.write(byteArrayOf(1), 0, 1, registry)
        a.write(byteArrayOf(2), 0, 1, registry) // 印が立っているので数えない
        assertEquals(1, p.wakes)
        assertEquals(1, q.wakes)
        // p が読み切る
        a.read(ByteArray(2), 0, 2, registry)
        wp.unregister(registry)
        assertTrue(wq.readyKeys(objects).isEmpty())
        wq.rearm()
        assertFalse(q.woken)
        assertEquals(1, registry.size, "登録は残っている")
        a.write(byteArrayOf(3), 0, 1, registry)
        assertEquals(2, q.wakes)
        assertEquals(listOf(readable(a)), wq.readyKeys(objects))
    }

    @Test
    fun `outputPendingは待ち手を起こさない`() {
        val a = stream()
        val r = CountingWaiter()
        val w = CountingWaiter()
        WaitSet.register(registry, objects, listOf(readable(a)), r)
        WaitSet.register(registry, objects, listOf(writable(a)), w)
        registry.outputPending(a.id)
        assertEquals(0, r.wakes)
        assertEquals(0, w.wakes)
    }
}
