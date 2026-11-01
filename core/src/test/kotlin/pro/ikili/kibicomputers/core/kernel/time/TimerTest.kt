package pro.ikili.kibicomputers.core.kernel.time

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.obj.ObjectTable
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.wait.WakeSink

/** [Timer] と [KernelClocks] の単体テスト（#64）。 */
class TimerTest {
    private class RecordingSink : WakeSink {
        val woken = ArrayList<WaitKey>()
        val closed = ArrayList<Int>()

        override fun wake(key: WaitKey) {
            woken += key
        }

        override fun wakeObject(objectId: Int) {}

        override fun timerClosed(objectId: Int) {
            closed += objectId
        }
    }

    private val ms = 1_000_000L

    @Test
    fun `1回だけのタイマーは期限で1回鳴って止まり、readで数を取り出す`() {
        val objects = ObjectTable()
        val t = Timer()
        objects.add(t)
        val sink = RecordingSink()
        assertTrue(t.canWait(WaitCondition.READABLE))
        assertFalse(t.canWait(WaitCondition.WRITABLE))
        t.arm(100 * ms, 0)
        assertEquals(0, t.expire(99 * ms, sink))
        assertFalse(t.isReady(WaitCondition.READABLE))
        assertEquals(1, t.expire(150 * ms, sink))
        assertFalse(t.armed)
        assertTrue(t.isReady(WaitCondition.READABLE))
        assertEquals(listOf(WaitKey(t.id, WaitCondition.READABLE)), sink.woken)
        assertEquals(0, t.expire(1_000 * ms, sink), "止まったら鳴らない")
        assertEquals(1, t.read())
        assertEquals(0, t.read())
        assertFalse(t.isReady(WaitCondition.READABLE))
    }

    @Test
    fun `繰り返しのタイマーは遅れた分をまとめて数え、次の期限を今より後へ進める`() {
        val t = Timer()
        val sink = RecordingSink()
        t.arm(100 * ms, 50 * ms)
        assertEquals(1, t.expire(100 * ms, sink))
        assertEquals(150 * ms, t.deadlineNanos)
        // 260 ms: 150, 200, 250 の3回
        assertEquals(3, t.expire(260 * ms, sink))
        assertEquals(300 * ms, t.deadlineNanos)
        assertEquals(4, t.read())
        assertTrue(t.armed)
    }

    @Test
    fun `armとdisarmは数えた回数を0に戻し、世代を進める`() {
        val t = Timer()
        t.arm(0, 0)
        val g = t.generation
        t.expire(0, WakeSink.NONE)
        assertEquals(1, t.expirations)
        t.arm(10, 0)
        assertEquals(0, t.expirations)
        assertTrue(t.generation > g)
        t.expire(10, WakeSink.NONE)
        t.disarm()
        assertEquals(0, t.expirations)
        assertFalse(t.armed)
        assertThrows<IllegalArgumentException> { t.arm(0, -1) }
    }

    @Test
    fun `期限と回数はあふれない`() {
        val t = Timer()
        t.arm(Long.MAX_VALUE - 10, Long.MAX_VALUE / 2)
        assertEquals(1, t.expire(Long.MAX_VALUE - 10, WakeSink.NONE))
        assertEquals(Long.MAX_VALUE, t.deadlineNanos)
        assertFalse(t.armed, "期限が無限の先になったら止める")
        assertEquals(Long.MAX_VALUE, saturatingAdd(Long.MAX_VALUE, 1))
        assertEquals(Long.MIN_VALUE, saturatingAdd(Long.MIN_VALUE, -1))
        assertEquals(Long.MAX_VALUE, saturatingSub(Long.MAX_VALUE, -1))
        assertEquals(Long.MIN_VALUE, saturatingSub(Long.MIN_VALUE, 1))
        assertEquals(Long.MAX_VALUE, saturatingMul(Long.MAX_VALUE / 2, 3))
        assertEquals(Long.MIN_VALUE, saturatingMul(Long.MAX_VALUE / 2, -3))
        assertEquals(6, saturatingMul(2, 3))
    }

    @Test
    fun `閉じたタイマーは止まり、timerClosedを知らせる`() {
        val objects = ObjectTable()
        val t = Timer()
        val id = objects.add(t)
        t.arm(0, 50 * ms)
        val sink = RecordingSink()
        objects.release(id, sink)
        assertFalse(t.armed)
        assertEquals(listOf(id), sink.closed)
    }

    @Test
    fun `単調時計と経過時間は0から始まり、起動で経過時間だけ0に戻る`() {
        val clock = ManualClock()
        val c = KernelClocks()
        c.start(clock, nowTick = 1_000)
        assertEquals(0, c.monotonicNanos(clock))
        assertEquals(0, c.uptimeTicks(1_000))
        clock.monotonic.addAndGet(5 * ms)
        assertEquals(5 * ms, c.monotonicNanos(clock))
        assertEquals(20, c.uptimeTicks(1_020))
        c.resetUptime(1_020)
        assertEquals(0, c.uptimeTicks(1_020))
        assertEquals(5 * ms, c.monotonicNanos(clock), "起動では単調時計を戻さない")
    }

    @Test
    fun `復元では単調時計と経過時間が保存した時点から続き、実時間とゲーム内時間の飛びを返す`() {
        val clock = ManualClock(world = 100)
        val c = KernelClocks()
        c.start(clock, nowTick = 0)
        clock.monotonic.addAndGet(3_000 * ms)
        val snap = c.snapshot(clock, nowTick = 60)
        assertEquals(ClockSnapshot(3_000 * ms, 60, clock.realtime.get(), 100), snap)
        // サーバーを起動し直した: JVM の時計も tick の番号も起点が変わり、実時間は 1 時間、ゲーム内時間は 72,000 tick 進んだ
        val later = ManualClock(monotonic = 5L, realtime = clock.realtime.get() + 3_600_000 * ms, world = 72_100)
        val restored = KernelClocks()
        val jump = restored.restore(snap, later, nowTick = 7)
        assertEquals(TimeJump(3_600_000 * ms, 72_000), jump)
        assertEquals(3_000 * ms, restored.monotonicNanos(later))
        assertEquals(60, restored.uptimeTicks(7))
        later.monotonic.addAndGet(10 * ms)
        assertEquals(3_010 * ms, restored.monotonicNanos(later))
        assertEquals(61, restored.uptimeTicks(8))
        assertThrows<IllegalArgumentException> { ClockSnapshot(-1, 0, 0, 0) }
    }
}
