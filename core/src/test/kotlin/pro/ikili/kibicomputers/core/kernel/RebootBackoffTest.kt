package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** 起動ループの抑止の待ち（#635、ADR 0635） */
class RebootBackoffTest {
    private val b = RebootBackoff.DEFAULT

    /** [ran] の並びで [n] 回再起動したときの待ち */
    private fun delays(n: Int, ran: (Int) -> Long): List<Long> {
        var debt = 0L
        return List(n) { i ->
            val step = b.next(debt, ran(i))
            debt = step.debtTicks
            step.delayTicks
        }
    }

    @Test
    fun `すぐ再起動すると3回までは待たず、そこから倍々にして上限で頭打ち`() {
        assertEquals(listOf(0L, 0, 0, 20, 40, 80, 160, 320, 640, 1200, 1200, 1200, 1200), delays(13) { 0 })
    }

    @Test
    fun `借りは0より下にも上限より上にもならない`() {
        var debt = 0L
        repeat(100) {
            debt = b.next(debt, 0).debtTicks
            assertTrue(debt <= b.capLevels * b.decayTicks)
        }
        assertEquals(b.capLevels * b.decayTicks, debt)
        val after = b.next(debt, Long.MAX_VALUE)
        assertEquals(b.decayTicks, after.debtTicks, "長く動けば借りは返し終わり、今回の分だけ残る")
        assertEquals(0L, after.delayTicks)
        assertEquals(b.decayTicks, b.next(0, -5).debtTicks, "負の時間は 0 とみなす")
    }

    @Test
    fun `動いた時間で段が戻る`() {
        var debt = 0L
        repeat(8) { debt = b.next(debt, 0).debtTicks }
        // 段 8（320 tick）。3 分動くと 3 段戻り、今回の 1 段を足して段 6（80 tick）
        val step = b.next(debt, 3 * b.decayTicks)
        assertEquals(6 * b.decayTicks, step.debtTicks)
        assertEquals(80L, step.delayTicks)
    }

    @Test
    fun `ときどき長く動く普通の使い方では待たない`() {
        // 1 分以上動いてから再起動するなら、借りは 1 段のまま
        assertEquals(List(50) { 0L }, delays(50) { b.decayTicks })
    }

    @ParameterizedTest
    @ValueSource(longs = [0, 1, 20, 199, 200, 201, 600, 1000, 1199, 1200, 1201])
    fun `どれだけ動いてから再起動しても、長い目で見た起動の率は上限を超えない`(ran: Long) {
        val n = 1000
        val total = delays(n) { ran }.sum() + ran * n
        // N 回にかかる tick は (N - CAP) * DECAY 以上（ADR 0635）
        val bound = (n - b.capLevels).toLong() * b.decayTicks
        assertTrue(total >= bound, "ran=$ran: $total < $bound")
    }

    @Test
    fun `閾値のすぐ上で回し続ける init も、いずれ待たされる`() {
        // 以前の案の「短命」の閾値（200 tick）のすぐ上
        val d = delays(200) { 201 }
        assertTrue(d.last() >= 1000, "待ちが上限近くまで延びる: ${d.takeLast(5)}")
    }

    @Test
    fun `動く時間がばらばらでも上限を超えない`() {
        val rnd = java.util.Random(635)
        val n = 2000
        val runs = LongArray(n) { if (rnd.nextInt(4) == 0) rnd.nextInt(3000).toLong() else rnd.nextInt(50).toLong() }
        val total = delays(n) { runs[it] }.sum() + runs.sum()
        assertTrue(total >= (n - b.capLevels).toLong() * b.decayTicks, "$total")
    }

    @Test
    fun `NONEは待たない`() {
        var debt = 0L
        repeat(100) {
            val step = RebootBackoff.NONE.next(debt, 0)
            debt = step.debtTicks
            assertEquals(0L, step.delayTicks)
        }
    }
}
