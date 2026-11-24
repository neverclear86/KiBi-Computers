package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.time.KernelClocks

class PublishedWorldClockTest {
    @Test
    fun `作ったときの値を返し、publishした値に変わる`() {
        val clock = PublishedWorldClock(1234)
        assertEquals(1234, clock.worldTicks())
        clock.publish(1300)
        assertEquals(1300, clock.worldTicks())
        // 負の値もそのまま返す（0 に丸めるのはカーネル）
        clock.publish(-5)
        assertEquals(-5, clock.worldTicks())
    }

    @Test
    fun `実時間と単調時計はSYSTEMと同じ流れ`() {
        val clock = PublishedWorldClock(0)
        val sysMono1 = MachineClock.SYSTEM.monotonicNanos()
        val mono = clock.monotonicNanos()
        val sysMono2 = MachineClock.SYSTEM.monotonicNanos()
        assertTrue(mono in sysMono1..sysMono2, "monotonic should follow System.nanoTime: $sysMono1 <= $mono <= $sysMono2")
        val sysReal1 = MachineClock.SYSTEM.realtimeNanos()
        val real = clock.realtimeNanos()
        val sysReal2 = MachineClock.SYSTEM.realtimeNanos()
        assertTrue(real in sysReal1..sysReal2, "realtime should follow the system clock: $sysReal1 <= $real <= $sysReal2")
    }

    @Test
    fun `別のスレッドからpublishした値が見える`() {
        val clock = PublishedWorldClock(0)
        val writer = Thread { clock.publish(42) }
        writer.start()
        writer.join()
        assertEquals(42, clock.worldTicks())
    }

    @Test
    fun `保存と復元でゲーム内時間の飛びが出る`() {
        val clock = PublishedWorldClock(1000)
        val saved = KernelClocks().apply { start(clock, 0) }.snapshot(clock, 0)
        assertEquals(1000, saved.worldTicks)
        clock.publish(1600)
        val jump = KernelClocks().restore(saved, clock, 0)
        assertEquals(600, jump.worldTicks)
    }
}
