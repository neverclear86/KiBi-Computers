package pro.ikili.kibicomputers.core.display

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.sched.FairShareGroup
import java.util.UUID

/** 画面の送信の予算を、映している側の公平の組（持ち主）の間で回し（tick をまたいで続ける。#896）、組ごとの予算をかける（#738） */
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

    // サーバー全体の予算を使い切り、1 tick に perTick バイトずつだけ戻る状態にする
    private fun starvedTraffic(perTick: Int): ScreenSyncTraffic<Player> {
        val tr = ScreenSyncTraffic<Player>(playerBytesPerSecond = 1 shl 30, serverBytesPerSecond = perTick * ScreenSyncSender.TICKS_PER_SECOND)
        tr.queue(Host(FairShareGroup.NONE, 0.0), perTick * ScreenSyncSender.TICKS_PER_SECOND)
        tr.endTick()
        assertEquals(perTick.toLong(), tr.remainingServerBudget)
        return tr
    }

    @Test
    fun `予算の不足が続いても、組の巡回は tick をまたいで続き、どの持ち主にも順番が来る`() {
        // 1 tick に 60 バイトのメッセージ 3 つ分。持ち主は 5 人で、毎 tick 1 つずつ積む（後ろの持ち主ほど遠い）
        val tr = starvedTraffic(perTick = 180)
        val hosts = (1..5).map { Host(FairShareGroup.of(UUID(1, it.toLong())), it.toDouble()) }
        repeat(2) {
            hosts.forEach { tr.queue(it, 60) }
            tr.endTick()
        }
        // 2 tick で 6 件。前の tick で送れなかった 2 人が、次の tick の先頭の枠を取る
        assertTrue(hosts.all { it.sent >= 1 }, hosts.map { it.sent }.toString())
        repeat(8) {
            hosts.forEach { tr.queue(it, 60) }
            tr.endTick()
        }
        // 10 tick で 30 件を、5 人で等しく分ける
        assertEquals(List(5) { 6 }, hosts.map { it.sent })
    }

    @Test
    fun `1 tick に1件分しかないときも、持ち主の間で順番に回す`() {
        val tr = starvedTraffic(perTick = 60)
        val hosts = (1..3).map { Host(FairShareGroup.of(UUID(2, it.toLong())), it.toDouble()) }
        repeat(6) {
            hosts.forEach { tr.queue(it, 60) }
            tr.endTick()
        }
        assertEquals(listOf(2, 2, 2), hosts.map { it.sent })
    }

    @Test
    fun `送れなかった組は、しばらく積まずに戻ってきても先に回す`() {
        // 送れなかった画面は、キーフレームを試すまで間を置くので、毎 tick は積まない
        val tr = starvedTraffic(perTick = 60)
        val near = Host(a, 0.0)
        val far = Host(b, 10.0)
        tr.queue(near, 60)
        tr.queue(far, 60)
        tr.endTick()
        assertEquals(listOf(1, 0), listOf(near.sent, far.sent))
        repeat(5) {
            tr.queue(near, 60)
            tr.endTick()
        }
        tr.queue(near, 60)
        tr.queue(far, 60)
        tr.endTick()
        assertEquals(1, far.sent)
    }

    @Test
    fun `間を置いて積む近い持ち主が交互にいても、試し直しで戻る遠い持ち主に順番が来る`() {
        // 近い持ち主 nearA は偶数の tick、nearC は奇数の tick にだけ積む（出力があった tick にだけ積む画面）。
        // 遠い持ち主 far は、送れなかったら試し直しの間隔（10 tick）だけ積まずに戻る（ScreenWatchers の止める動き）。
        // 積まなかった組の巡回の位置をすぐ消すと、far は戻るたびに nearA・nearC と同じ扱いになり、優先度で負け続ける
        val tr = starvedTraffic(perTick = 60)
        val nearA = Host(a, 0.0)
        val nearC = Host(FairShareGroup.of(UUID(0, 3)), 0.0)
        val far = Host(b, 10.0)
        val retry = ScreenWatchers.DEFAULT_THROTTLE_RETRY_TICKS
        for (t in 0 until retry * 3 + 1) {
            tr.queue(if (t % 2 == 0) nearA else nearC, 60)
            if (t % retry == 0) tr.queue(far, 60)
            tr.endTick()
        }
        assertTrue(far.sent >= 1, "far=${far.sent} nearA=${nearA.sent} nearC=${nearC.sent}")
        // far が戻るのは 4 回。最初の1回は優先度で負けても、2回目からは先頭の枠を取る
        assertEquals(3, far.sent)
    }

    @Test
    fun `初めて積んだ組は、直前に送れた組と同点にならず、その後ろに並ぶ`() {
        // 1 tick に 1 つしか通らない。先に a だけが送れ、次の tick に、遠い a と近い新しい組 c が並ぶ
        val tr = starvedTraffic(perTick = 60)
        val hostA = Host(a, 10.0)
        val hostC = Host(FairShareGroup.of(UUID(0, 3)), 0.0)
        tr.queue(hostA, 60)
        tr.endTick()
        assertEquals(1, hostA.sent)
        tr.queue(hostA, 60)
        tr.queue(hostC, 60)
        tr.endTick()
        // c の方が近くても、c は a の後ろ（直前に送れたのは a だが、c は初めて積んだ組なので a と同点にしない）
        assertEquals(2, hostA.sent)
        assertEquals(0, hostC.sent)
    }

    @Test
    fun `しばらく積まなかった組の巡回の位置は消し、組が1つの tick は優先度の順のまま`() {
        val tr = starvedTraffic(perTick = 60)
        val hosts = (1..4).map { Host(FairShareGroup.of(UUID(3, it.toLong())), it.toDouble()) }
        hosts.forEach { tr.queue(it, 60) }
        tr.endTick()
        // 組が1つ（NONE）の分と、4 つの組
        assertEquals(5, tr.rotationGroups)

        // 組が1つの tick: 巡回の位置が古い組がいても、その tick に積んだのが1つの組だけなら優先度の順
        val solo = (1..2).map { Host(FairShareGroup.NONE, 0.0) }
        val soloFar = Host(FairShareGroup.NONE, 5.0)
        tr.queue(soloFar, 60)
        solo.forEach { tr.queue(it, 60) }
        tr.endTick()
        assertEquals(0, soloFar.sent)
        assertEquals(1, solo.sumOf { it.sent })

        // 複数の組に戻ると、送れていない組から回す（1つ目の tick で送れた hosts[0] は後ろ）
        hosts.forEach { tr.queue(it, 60) }
        tr.endTick()
        assertEquals(listOf(1, 1, 0, 0), hosts.map { it.sent })

        // 積まなくなって ROTATION_RETAIN_TICKS を過ぎた組は消す
        repeat(ScreenSyncTraffic.ROTATION_RETAIN_TICKS) {
            tr.queue(solo[0], 1)
            tr.endTick()
        }
        assertEquals(1, tr.rotationGroups)
        tr.clear()
        assertEquals(0, tr.rotationGroups)
    }

    /** [ScreenWatchers] と [ScreenSyncSender] を通した、1人の持ち主の画面（#896） */
    private class Owner(group: FairShareGroup, distance: Double, viewer: Player) {
        val text = TextBuffer(20, 4)
        val sender = ScreenSyncSender(text)
        val watchers = ScreenWatchers<Player>(startDistance = 32.0)
        val host = object : ScreenSyncHost<Player> {
            override fun candidates(): Iterable<Player> = listOf(viewer)

            override fun distanceSquared(player: Player): Double = distance * distance

            override fun send(player: Player, fragment: ByteArray) {
                if (fragment.size > 1 && fragment[1].toInt() == 1) keyframes++
                messages++
            }

            override fun fairShareGroup(): FairShareGroup = group
        }
        var keyframes = 0
        var messages = 0

        fun step(now: Long, tr: ScreenSyncTraffic<Player>) = watchers.tick(now, sender, host, tr)
    }

    @Test
    fun `ScreenWatchers を通しても、間を置いて積む近い持ち主の間で、遠い持ち主の画面が送り直される`() {
        // 遠い持ち主の画面は ScreenWatchers を通す（送れなかったら止められ、試し直しの間隔の後で、予算が収まるときだけキーフレームを積む）。
        // 近い持ち主 nearA・nearC は、交互の tick に、1 tick の補充分のメッセージを1つずつ積む
        val far = Owner(b, 20.0, player)
        val keyframeBytes = far.sender.keyframeBytes
        val perTick = keyframeBytes + 40
        val tr = starvedTraffic(perTick)
        val nearA = Host(a, 1.0)
        val nearC = Host(FairShareGroup.of(UUID(0, 3)), 1.0)
        for (t in 1L..60L) {
            far.text.write(0, 0, "line %04d".format(t), 0xFFFFFF, 0)
            far.step(t, tr)
            tr.queue(if (t % 2 == 0L) nearA else nearC, perTick)
            tr.endTick()
        }
        // 最初のキーフレームは優先度で負けるが、試し直しで戻るたびに、送れていない組として先頭の枠を取る
        // （巡回の位置をすぐ消す方式では、戻るたびに近い持ち主と同じ扱いになり、一度も届かない）
        assertTrue(far.keyframes >= 3, "far keyframes=${far.keyframes} messages=${far.messages}")
    }
}
