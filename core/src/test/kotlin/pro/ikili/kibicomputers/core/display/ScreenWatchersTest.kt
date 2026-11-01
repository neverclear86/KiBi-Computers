package pro.ikili.kibicomputers.core.display

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ScreenWatchersTest {
    /** テスト用のプレイヤー。距離は直接決める。 */
    private class Player(val name: String, var distance: Double)

    private class Host : ScreenSyncHost<Player> {
        val players = ArrayList<Player>()
        val received = HashMap<Player, MutableList<ByteArray>>()
        var candidateCalls = 0

        override fun candidates(): Iterable<Player> {
            candidateCalls++
            return players
        }

        override fun distanceSquared(player: Player): Double = player.distance * player.distance

        override fun send(player: Player, fragment: ByteArray) {
            received.getOrPut(player, ::ArrayList) += fragment
        }

        fun keyframes(p: Player): Int = received[p].orEmpty().count { it.size > 1 && it[1].toInt() == 1 }

        fun messages(p: Player): Int = received[p]?.size ?: 0
    }

    private val text = TextBuffer(10, 3)
    private val sender = ScreenSyncSender(text)
    private val host = Host()
    private val traffic = ScreenSyncTraffic<Player>()

    private fun watchers(resync: Int = 40, scan: Int = 10) =
        ScreenWatchers<Player>(startDistance = 10.0, stopDistance = 15.0, resyncIntervalTicks = resync, scanIntervalTicks = scan)

    @Test
    fun `nearby player starts watching and gets one keyframe`() {
        val w = watchers()
        val near = Player("near", 5.0)
        val far = Player("far", 12.0)
        host.players += listOf(near, far)
        w.tick(0, sender, host, traffic)
        assertTrue(w.isWatching(near))
        assertFalse(w.isWatching(far))
        assertEquals(1, host.keyframes(near))
        assertEquals(0, host.messages(far))
        assertEquals(1L, traffic.keyframesSent(near))
        assertEquals(traffic.totalBytes, traffic.bytesSent(near))
        assertTrue(traffic.bytesSent(near) > 0)

        // 描いた分は描画命令で届き、キーフレームは増えない
        text.write(0, 0, "hi", 0xFFFFFF, 0)
        w.tick(1, sender, host, traffic)
        assertEquals(2, host.messages(near))
        assertEquals(1, host.keyframes(near))
    }

    @Test
    fun `hysteresis keeps watching between start and stop distance`() {
        val w = watchers()
        val p = Player("p", 12.0)
        host.players += p
        w.tick(0, sender, host, traffic)
        assertFalse(w.isWatching(p), "between the distances, a non-watcher does not start")
        p.distance = 9.0
        w.tick(10, sender, host, traffic)
        assertTrue(w.isWatching(p))
        p.distance = 14.0
        w.tick(11, sender, host, traffic)
        assertTrue(w.isWatching(p), "between the distances, a watcher keeps watching")
        p.distance = 16.0
        w.tick(12, sender, host, traffic)
        assertFalse(w.isWatching(p), "beyond the stop distance, the watcher stops at once")
        assertEquals(0, w.count)
    }

    @Test
    fun `candidates are scanned only every scan interval`() {
        val w = watchers(scan = 10)
        val p = Player("p", 20.0)
        host.players += p
        w.tick(0, sender, host, traffic)
        assertEquals(1, host.candidateCalls)
        p.distance = 5.0
        for (t in 1L..9L) w.tick(t, sender, host, traffic)
        assertEquals(1, host.candidateCalls, "no scan between scan ticks")
        assertFalse(w.isWatching(p))
        w.tick(10, sender, host, traffic)
        assertEquals(2, host.candidateCalls)
        assertTrue(w.isWatching(p))
    }

    @Test
    fun `resync requests are limited per interval and deferred`() {
        val w = watchers(resync = 40)
        val p = Player("p", 5.0)
        host.players += p
        w.tick(0, sender, host, traffic)
        assertEquals(1, host.keyframes(p))
        for (t in 1L..39L) {
            repeat(3) { assertTrue(w.requestResync(p, t)) }
            w.tick(t, sender, host, traffic)
        }
        assertEquals(1, host.keyframes(p), "spammed requests should not resend before the interval")
        w.tick(40, sender, host, traffic)
        assertEquals(2, host.keyframes(p), "the deferred request is granted once the interval passes")
        for (t in 41L..60L) w.tick(t, sender, host, traffic)
        assertEquals(2, host.keyframes(p))
        assertEquals(2L, traffic.keyframesSent(p))
        // 間隔が過ぎていれば、その tick の終わりに送る
        assertTrue(w.requestResync(p, 80))
        w.tick(80, sender, host, traffic)
        assertEquals(3, host.keyframes(p))
    }

    @Test
    fun `resync request from a non-watcher is ignored`() {
        val w = watchers()
        val p = Player("p", 50.0)
        host.players += p
        w.tick(0, sender, host, traffic)
        assertFalse(w.requestResync(p, 1))
        w.tick(1, sender, host, traffic)
        assertEquals(0, host.messages(p))
        assertEquals(0L, traffic.bytesSent(p))
    }

    @Test
    fun `leaving and coming back within the interval does not resend at once`() {
        val w = watchers(resync = 40, scan = 10)
        val p = Player("p", 5.0)
        host.players += p
        w.tick(0, sender, host, traffic)
        p.distance = 20.0
        w.tick(1, sender, host, traffic)
        assertFalse(w.isWatching(p))
        p.distance = 5.0
        for (t in 2L..39L) w.tick(t, sender, host, traffic)
        assertFalse(w.isWatching(p), "coming back within the interval should wait")
        assertEquals(1, host.keyframes(p))
        w.tick(40, sender, host, traffic)
        assertTrue(w.isWatching(p))
        assertEquals(2, host.keyframes(p))
    }

    @Test
    fun `remove and clear drop watchers and recording stops`() {
        val w = watchers()
        val a = Player("a", 1.0)
        val b = Player("b", 1.0)
        host.players += listOf(a, b)
        w.tick(0, sender, host, traffic)
        assertEquals(2, w.count)
        w.remove(a)
        assertFalse(w.isWatching(a))
        assertEquals(1, w.count)
        w.clear()
        assertEquals(0, w.count)
        host.players.clear()
        w.tick(1, sender, host, traffic)
        assertFalse(sender.isRecording, "no watchers, so the sender stops recording")
    }

    @Test
    fun `traffic forget and clear`() {
        val t = ScreenSyncTraffic<String>()
        t.recordBytes("a", 10)
        t.recordBytes("b", 5)
        t.recordKeyframe("a")
        assertEquals(10L, t.bytesSent("a"))
        assertEquals(15L, t.totalBytes)
        assertEquals(1L, t.keyframesSent("a"))
        t.forget("a")
        assertEquals(0L, t.bytesSent("a"))
        assertEquals(0L, t.keyframesSent("a"))
        t.clear()
        assertEquals(0L, t.totalBytes)
    }

    @Test
    fun `invalid arguments are rejected`() {
        assertThrows<IllegalArgumentException> { ScreenWatchers<Player>(startDistance = -1.0) }
        assertThrows<IllegalArgumentException> { ScreenWatchers<Player>(startDistance = 10.0, stopDistance = 5.0) }
        assertThrows<IllegalArgumentException> { ScreenWatchers<Player>(resyncIntervalTicks = -1) }
        assertThrows<IllegalArgumentException> { ScreenWatchers<Player>(scanIntervalTicks = 0) }
    }
}
