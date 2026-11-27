package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.kernel.sched.MachineCpu
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * [MachineCpu] の予約（[MachineCpu.reserve]・[MachineCpu.acquire]・[MachineCpu.cancelReservation]）を直接確かめる（#742、#737）。
 * CPU の持ち主・待ち手には、燃料待ちのまま動かないプロセスの [ProcessRuntime] を使う（tick を進めないので、マシン自身の CPU は使わない）。
 */
@Timeout(10, unit = TimeUnit.MINUTES) // 止まったときの保険（合否の基準ではない。#916）
class MachineCpuTest {
    private val limit = Duration.ofSeconds(60)

    private class Fixture(h: Harness) {
        val granted = ArrayList<ProcessRuntime>()
        val revoked = ArrayList<ProcessRuntime>()

        /** [granted] と同じ順の、strict の印（#875） */
        val strict = ArrayList<Boolean>()
        val cpu = MachineCpu({ rt, s ->
            granted += rt
            strict += s
        }, { revoked += it })
        val machine = h.machine(fuelPerTick = 1000)
        val a = machine.spawnRt(h.program(spinForever()), runDriver())
        val b = machine.spawnRt(h.program(spinForever()), runDriver())
        val c = machine.spawnRt(h.program(spinForever()), runDriver())
    }

    @Test
    fun `空いているCPUを予約すると、その場で渡り、acquireは待たずに受け取る`() = timeout(limit) {
        Harness().use { h ->
            val f = Fixture(h)
            f.cpu.reserve(f.a)
            assertSame(f.a, f.cpu.currentOwner)
            assertTrue(f.cpu.hasReservation(f.a))
            assertEquals(0, f.cpu.waiting)
            f.cpu.acquire(f.a)
            assertFalse(f.cpu.hasReservation(f.a), "受け取った後も予約が残っている")
            assertSame(f.a, f.cpu.currentOwner)
            f.cpu.release(f.a)
            assertNull(f.cpu.currentOwner)
            f.machine.stop()
        }
    }

    @Test
    fun `予約は二重に取らず、予約のない取り消しは何もしない`() = timeout(limit) {
        Harness().use { h ->
            val f = Fixture(h)
            f.cpu.reserve(f.a)
            f.cpu.reserve(f.b)
            f.cpu.reserve(f.b)
            assertEquals(1, f.cpu.waiting, "同じ待ち手が二重に並んだ")
            f.cpu.cancelReservation(f.b)
            f.cpu.cancelReservation(f.b)
            assertEquals(0, f.cpu.waiting)
            f.cpu.cancelReservation(f.c)
            assertSame(f.a, f.cpu.currentOwner)
            f.cpu.cancelReservation(f.a)
            assertNull(f.cpu.currentOwner)
            f.machine.stop()
        }
    }

    @Test
    fun `まだ渡していない予約を取り消すと、待ち行列から外れ、ほかの持ち主はそのまま`() = timeout(limit) {
        Harness().use { h ->
            val f = Fixture(h)
            f.cpu.acquire(f.a)
            f.cpu.reserve(f.b)
            f.cpu.reserve(f.c)
            assertEquals(2, f.cpu.waiting)
            f.cpu.cancelReservation(f.b)
            assertEquals(1, f.cpu.waiting)
            assertFalse(f.cpu.hasReservation(f.b))
            assertTrue(f.cpu.hasReservation(f.c))
            assertSame(f.a, f.cpu.currentOwner)
            // 外した B には渡らず、C に渡る
            f.cpu.release(f.a)
            assertSame(f.c, f.cpu.currentOwner)
            f.cpu.acquire(f.c)
            f.cpu.release(f.c)
            assertNull(f.cpu.currentOwner)
            f.machine.stop()
        }
    }

    @Test
    fun `渡した予約を取り消すと、次の待ち手へ渡す`() = timeout(limit) {
        Harness().use { h ->
            val f = Fixture(h)
            f.cpu.reserve(f.a)
            assertSame(f.a, f.cpu.currentOwner)
            f.cpu.enqueueThreadless(f.b)
            assertEquals(1, f.cpu.waiting)
            f.cpu.cancelReservation(f.a)
            assertSame(f.b, f.cpu.currentOwner, "渡した予約を外したのに、次へ渡らない")
            assertEquals(listOf(f.b), f.granted)
            assertFalse(f.cpu.hasReservation(f.a))
            f.cpu.release(f.b)
            assertNull(f.cpu.currentOwner)
            f.machine.stop()
        }
    }

    @Test
    fun `自分が持っているCPUの後ろに予約が入ると、返したときに自分へ渡り、次のacquireで受け取る`() = timeout(limit) {
        Harness().use { h ->
            // CPU を返す前に燃料待ちから起こされた形（ADR 0047）
            val f = Fixture(h)
            f.cpu.acquire(f.a)
            f.cpu.reserve(f.a)
            f.cpu.reserve(f.b)
            assertEquals(2, f.cpu.waiting)
            f.cpu.release(f.a)
            assertSame(f.a, f.cpu.currentOwner, "返したら予約の位置で自分に渡る")
            assertTrue(f.cpu.hasReservation(f.a))
            assertEquals(1, f.cpu.waiting)
            f.cpu.acquire(f.a)
            assertFalse(f.cpu.hasReservation(f.a))
            f.cpu.release(f.a)
            assertSame(f.b, f.cpu.currentOwner)
            f.cpu.acquire(f.b)
            f.cpu.release(f.b)
            assertNull(f.cpu.currentOwner)
            f.machine.stop()
        }
    }

    @Test
    fun `自分に渡った予約を受け取る前に取り消すと、次へ渡す`() = timeout(limit) {
        Harness().use { h ->
            val f = Fixture(h)
            f.cpu.acquire(f.a)
            f.cpu.reserve(f.a)
            f.cpu.reserve(f.b)
            f.cpu.release(f.a)
            assertSame(f.a, f.cpu.currentOwner)
            // A はもう CPU に着かない（受け取る前に止められた）
            f.cpu.cancelReservation(f.a)
            assertSame(f.b, f.cpu.currentOwner)
            assertFalse(f.cpu.hasReservation(f.a))
            f.cpu.acquire(f.b)
            f.cpu.release(f.b)
            assertNull(f.cpu.currentOwner)
            f.machine.stop()
        }
    }

    @Test
    fun `acquireがロックを取る前に割り込まれたら、予約は残るので呼んだ側が外す`() = timeout(limit) {
        Harness().use { h ->
            val f = Fixture(h)
            f.cpu.acquire(f.a)
            f.cpu.reserve(f.b)
            Thread.currentThread().interrupt()
            try {
                assertThrows(InterruptedException::class.java) { f.cpu.acquire(f.b) }
            } finally {
                Thread.interrupted()
            }
            assertTrue(f.cpu.hasReservation(f.b), "ロックを取る前に割り込まれたら、予約はそのまま残る")
            f.cpu.cancelReservation(f.b)
            assertEquals(0, f.cpu.waiting)
            assertSame(f.a, f.cpu.currentOwner)
            f.cpu.release(f.a)
            assertNull(f.cpu.currentOwner)
            f.machine.stop()
        }
    }

    @Test
    fun `予約を受け取って待っている間に割り込まれたら、予約は外れて待ち行列にも残らない`() = timeout(limit) {
        Harness().use { h ->
            val f = Fixture(h)
            f.cpu.acquire(f.a)
            f.cpu.reserve(f.b)
            f.cpu.reserve(f.c)
            var thrown: Throwable? = null
            val started = CountDownLatch(1)
            val t = Thread {
                try {
                    started.countDown()
                    f.cpu.acquire(f.b)
                } catch (e: Throwable) {
                    thrown = e
                }
            }
            t.start()
            started.awaitOrFail()
            eventually(10_000) { !f.cpu.hasReservation(f.b) }
            t.interrupt()
            t.joinOrFail("割り込まれた待ち手が抜けない")
            assertTrue(thrown is InterruptedException, "割り込まれていない: $thrown")
            assertEquals(1, f.cpu.waiting, "割り込まれた待ち手が待ち行列に残っている")
            f.cpu.release(f.a)
            assertSame(f.c, f.cpu.currentOwner)
            f.cpu.cancelReservation(f.c)
            assertNull(f.cpu.currentOwner)
            f.machine.stop()
        }
    }

    @Test
    fun `スレッドを持たない待ち手と予約が混ざっても、並んだ順に渡る`() = timeout(limit) {
        Harness().use { h ->
            val f = Fixture(h)
            f.cpu.acquire(f.a)
            f.cpu.enqueueThreadless(f.b)
            f.cpu.reserve(f.c)
            f.cpu.release(f.a)
            assertSame(f.b, f.cpu.currentOwner)
            assertEquals(listOf(f.b), f.granted)
            f.cpu.removeThreadless(f.b)
            assertEquals(listOf(f.b), f.revoked)
            assertSame(f.c, f.cpu.currentOwner)
            f.cpu.acquire(f.c)
            f.cpu.release(f.c)
            assertNull(f.cpu.currentOwner)
            f.machine.stop()
        }
    }

    @Test
    fun `strictはspawnのenqueueThreadlessで空いていたときだけで、並んだ後の受け渡しはいつも投げない版になる`() = timeout(limit) {
        Harness().use { h ->
            val f = Fixture(h)
            // 空いていて strict で並ぶ（spawn の直接の道）
            f.cpu.enqueueThreadless(f.a, strict = true)
            // strict で並んでも、埋まっていれば後で渡すとき（release）は strict でない
            f.cpu.enqueueThreadless(f.b, strict = true)
            f.cpu.release(f.a)
            assertSame(f.b, f.cpu.currentOwner)
            // 止めたとき（removeThreadless）の受け渡しも strict でない
            f.cpu.enqueueThreadless(f.c)
            f.cpu.removeThreadless(f.b)
            assertSame(f.c, f.cpu.currentOwner)
            f.cpu.release(f.c)
            // 既定（起こすとき・復元の取り込み）は strict でない
            f.cpu.enqueueThreadless(f.a)
            f.cpu.release(f.a)
            // 予約を取り消したときの受け渡しも strict でない
            f.cpu.reserve(f.a)
            f.cpu.enqueueThreadless(f.b, strict = true)
            f.cpu.cancelReservation(f.a)
            f.cpu.release(f.b)
            assertNull(f.cpu.currentOwner)
            assertEquals(listOf(f.a, f.b, f.c, f.a, f.b), f.granted)
            assertEquals(listOf(true, false, false, false, false), f.strict)
            f.machine.stop()
        }
    }
}
