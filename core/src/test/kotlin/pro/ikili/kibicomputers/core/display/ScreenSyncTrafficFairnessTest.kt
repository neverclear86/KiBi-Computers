package pro.ikili.kibicomputers.core.display

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.sched.FairShareGroup
import java.util.UUID

/** 画面の送信の予算を、映している側の公平の組（持ち主）の間で回し、組ごとの予算をかける（#738） */
class ScreenSyncTrafficFairnessTest {
    private class Player(val name: String)

    private class Host(private val group: FairShareGroup, private val distance: Double) : ScreenSyncHost<Player> {
        var sent = 0

        override fun candidates(): Iterable<Player> = emptyList()

        override fun distanceSquared(player: Player): Double = distance * distance

        override fun send(player: Player, fragment: ByteArray) {
            sent++
        }

        override fun fairShareGroup(): FairShareGroup = group
    }

    private val a = FairShareGroup.of(UUID(0, 1))
    private val b = FairShareGroup.of(UUID(0, 2))
    private val watchers = ScreenWatchers<Player>()
    private val player = Player("p")

    private fun ScreenSyncTraffic<Player>.queue(host: Host, bytes: Int) =
        enqueue(watchers, host, player, listOf(ByteArray(bytes)), keyframe = false, distanceSquared = host.distanceSquared(player))

    @Test
    fun `近い画面の多い持ち主がいても、ほかの持ち主の画面に順番が回ってくる`() {
        // サーバー全体の予算は 200 バイト。60 バイトのメッセージは 3 つまで
        val tr = ScreenSyncTraffic<Player>(playerBytesPerSecond = 1 shl 30, serverBytesPerSecond = 200)
        val near = (1..3).map { Host(a, 0.0) }
        val far = Host(b, 10.0)
        near.forEach { tr.queue(it, 60) }
        tr.queue(far, 60)
        tr.endTick()
        // 優先度だけなら a の 3 つで使い切るが、組の間で回すので b も届く
        assertEquals(1, far.sent)
        assertEquals(2, near.sumOf { it.sent })
        assertEquals(1L, tr.totalThrottled)
    }

    @Test
    fun `組が1つだけなら、優先度の順に送る`() {
        val tr = ScreenSyncTraffic<Player>(playerBytesPerSecond = 1 shl 30, serverBytesPerSecond = 200)
        val near = (1..3).map { Host(FairShareGroup.NONE, 0.0) }
        val far = Host(FairShareGroup.NONE, 10.0)
        tr.queue(far, 60)
        near.forEach { tr.queue(it, 60) }
        tr.endTick()
        assertEquals(0, far.sent)
        assertEquals(3, near.sumOf { it.sent })
    }

    @Test
    fun `組ごとの予算を超えた組だけ止め、満タンに戻った組は勘定から消す`() {
        val tr = ScreenSyncTraffic<Player>(playerBytesPerSecond = 1 shl 30, serverBytesPerSecond = 1 shl 30, groupBytesPerSecond = 100)
        val hostA = Host(a, 1.0)
        val hostB = Host(b, 1.0)
        tr.queue(hostA, 60)
        tr.queue(hostA, 60)
        tr.queue(hostB, 60)
        tr.endTick()
        assertEquals(1, hostA.sent)
        assertEquals(1, hostB.sent)
        assertEquals(1L, tr.totalThrottled)
        // キーフレームを試す見込みも、組の予算で見る
        assertFalse(tr.hasBudget(player, 60, a))
        assertTrue(tr.hasBudget(player, 30, a))
        assertEquals(2, tr.trackedGroups)
        // 1 tick に 5 バイトずつ戻る。満タンになった組は消す
        repeat(20) { tr.endTick() }
        assertEquals(0, tr.trackedGroups)
        assertEquals(100L, tr.remainingGroupBudget(a))
        assertTrue(tr.hasBudget(player, 60, a))
    }

    @Test
    fun `組ごとの予算が0なら上限を置かない`() {
        val tr = ScreenSyncTraffic<Player>(playerBytesPerSecond = 1 shl 30, serverBytesPerSecond = 1 shl 30, groupBytesPerSecond = 0)
        val host = Host(a, 1.0)
        repeat(10) { tr.queue(host, 1000) }
        tr.endTick()
        assertEquals(10, host.sent)
        assertEquals(Long.MAX_VALUE, tr.remainingGroupBudget(a))
        assertEquals(0, tr.trackedGroups)
    }
}
