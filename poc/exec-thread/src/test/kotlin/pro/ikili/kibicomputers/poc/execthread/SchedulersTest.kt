package pro.ikili.kibicomputers.poc.execthread

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 測定の前提（燃料の配り方、ワールド操作がメインスレッドで行われること、一時停止）を確かめる。 */
class SchedulersTest {
    private fun run(scheduler: Scheduler, ticks: Int = 10) = simulate(scheduler, 0, 2, ticks, 10_000_000_000)

    @Test
    fun `方式Aでは配った燃料をちょうど使い切る`() {
        val r = run(LockstepScheduler(3, 50, null, Program.CPU))
        assertEquals(r.fuelOffered, r.fuelConsumed)
        assertEquals(3L * 50 * r.ticks, r.fuelConsumed)
    }

    @Test
    fun `方式Aの上限で1台あたりの燃料が減る`() {
        assertEquals(25, LockstepScheduler(4, 50, 100, Program.CPU).fuelPerComputer)
        assertEquals(50, LockstepScheduler(1, 50, 100, Program.CPU).fuelPerComputer)
    }

    @Test
    fun `方式Aのワールド操作はメインスレッドで行われる`() {
        // メインスレッド以外から触ると World が例外を投げ、simulate が失敗する
        val r = run(LockstepScheduler(2, 20, null, Program.WORLD))
        // 燃料 1 単位ごとに 1 回操作するので、燃料と操作の数は同じ（端数の1回を除く）
        assertTrue(r.worldOps in (r.fuelConsumed - 2)..(r.fuelConsumed + 2), "ops=${r.worldOps} fuel=${r.fuelConsumed}")
        assertEquals(r.worldOps, r.opLatencies.size.toLong())
    }

    @Test
    fun `方式Bでも燃料はtickごとの量を超えない`() {
        val r = run(ThreadedScheduler(3, 50, 3, OpTiming.IMMEDIATE, Program.CPU))
        assertTrue(r.fuelConsumed > 0)
        // 測定の始まりと終わりの tick の境目で、最大 1 tick 分ずれる
        assertTrue(r.fuelConsumed <= r.fuelOffered + 3 * 50, "consumed=${r.fuelConsumed} offered=${r.fuelOffered}")
    }

    @Test
    fun `方式Bのワールド操作はメインスレッドで行われる`() {
        for (timing in OpTiming.entries) {
            val r = run(ThreadedScheduler(2, 20, 2, timing, Program.WORLD))
            assertTrue(r.worldOps > 0, "$timing")
        }
    }

    @Test
    fun `方式Bで全台を止めて再開できる`() {
        val scheduler = ThreadedScheduler(4, Long.MAX_VALUE / 4, 2, OpTiming.TICK_HEAD, Program.CPU)
        scheduler.start(World(Thread.currentThread()))
        try {
            scheduler.tick()
            sleepUntil(System.nanoTime() + 50_000_000)
            scheduler.pauseAll()
            assertFalse(scheduler.computers.any { it.executing })
            val consumed = scheduler.computers.sumOf { it.consumed }
            sleepUntil(System.nanoTime() + 20_000_000)
            assertEquals(consumed, scheduler.computers.sumOf { it.consumed })
            scheduler.resumeAll()
            sleepUntil(System.nanoTime() + 50_000_000)
            assertTrue(scheduler.computers.sumOf { it.consumed } > consumed)
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun `スタックが大きいほど深く再帰できる`() {
        assertTrue(maxDepth(16L shl 20) > maxDepth(1L shl 20))
    }
}
