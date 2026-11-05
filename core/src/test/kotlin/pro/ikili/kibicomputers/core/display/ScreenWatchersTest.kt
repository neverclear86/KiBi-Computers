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

    // 1 tick 分: 画面の tick の後に、積んだものを予算の中で送る
    private fun ScreenWatchers<Player>.step(now: Long, tr: ScreenSyncTraffic<Player> = traffic, s: ScreenSyncSender = sender) {
        tick(now, s, host, tr)
        tr.endTick()
    }

    private fun watchers(resync: Int = 40, scan: Int = 10) =
        ScreenWatchers<Player>(startDistance = 10.0, stopDistance = 15.0, resyncIntervalTicks = resync, scanIntervalTicks = scan)

    @Test
    fun `nearby player starts watching and gets one keyframe`() {
        val w = watchers()
        val near = Player("near", 5.0)
        val far = Player("far", 12.0)
        host.players += listOf(near, far)
        w.step(0)
        assertTrue(w.isWatching(near))
        assertFalse(w.isWatching(far))
        assertEquals(1, host.keyframes(near))
        assertEquals(0, host.messages(far))
        assertEquals(1L, traffic.keyframesSent(near))
        assertEquals(traffic.totalBytes, traffic.bytesSent(near))
        assertTrue(traffic.bytesSent(near) > 0)

        // 描いた分は描画命令で届き、キーフレームは増えない
        text.write(0, 0, "hi", 0xFFFFFF, 0)
        w.step(1)
        assertEquals(2, host.messages(near))
        assertEquals(1, host.keyframes(near))
    }

    @Test
    fun `hysteresis keeps watching between start and stop distance`() {
        val w = watchers()
        val p = Player("p", 12.0)
        host.players += p
        w.step(0)
        assertFalse(w.isWatching(p), "between the distances, a non-watcher does not start")
        p.distance = 9.0
        w.step(10)
        assertTrue(w.isWatching(p))
        p.distance = 14.0
        w.step(11)
        assertTrue(w.isWatching(p), "between the distances, a watcher keeps watching")
        p.distance = 16.0
        w.step(12)
        assertFalse(w.isWatching(p), "beyond the stop distance, the watcher stops at once")
        assertEquals(0, w.count)
    }

    @Test
    fun `candidates are scanned only every scan interval`() {
        val w = watchers(scan = 10)
        val p = Player("p", 20.0)
        host.players += p
        w.step(0)
        assertEquals(1, host.candidateCalls)
        p.distance = 5.0
        for (t in 1L..9L) w.step(t)
        assertEquals(1, host.candidateCalls, "no scan between scan ticks")
        assertFalse(w.isWatching(p))
        w.step(10)
        assertEquals(2, host.candidateCalls)
        assertTrue(w.isWatching(p))
    }

    @Test
    fun `needsTick is false only between scans while nobody watches`() {
        val w = watchers(scan = 10)
        val p = Player("p", 20.0)
        host.players += p
        assertTrue(w.needsTick(3, sender), "the first tick always scans")
        w.step(3)
        for (t in 4L..9L) assertFalse(w.needsTick(t, sender), "nobody watches and no scan at $t")
        assertTrue(w.needsTick(10, sender), "scan tick")

        // 見はじめたら毎 tick 要る（見ている人が遠ざかったか調べ、描画命令を送る）
        p.distance = 5.0
        w.step(10)
        assertTrue(w.isWatching(p))
        assertTrue(sender.isRecording)
        assertTrue(w.needsTick(11, sender))

        // 見るのをやめた tick の後は、記録も止まり、また要らなくなる
        p.distance = 16.0
        w.step(11)
        assertFalse(w.isWatching(p))
        assertFalse(sender.isRecording)
        assertFalse(w.needsTick(12, sender))
    }

    @Test
    fun `resync requests are limited per interval and deferred`() {
        val w = watchers(resync = 40)
        val p = Player("p", 5.0)
        host.players += p
        w.step(0)
        assertEquals(1, host.keyframes(p))
        for (t in 1L..39L) {
            repeat(3) { assertTrue(w.requestResync(p, t)) }
            w.step(t)
        }
        assertEquals(1, host.keyframes(p), "spammed requests should not resend before the interval")
        w.step(40)
        assertEquals(2, host.keyframes(p), "the deferred request is granted once the interval passes")
        for (t in 41L..60L) w.step(t)
        assertEquals(2, host.keyframes(p))
        assertEquals(2L, traffic.keyframesSent(p))
        // 間隔が過ぎていれば、その tick の終わりに送る
        assertTrue(w.requestResync(p, 80))
        w.step(80)
        assertEquals(3, host.keyframes(p))
    }

    @Test
    fun `resync request from a non-watcher is ignored`() {
        val w = watchers()
        val p = Player("p", 50.0)
        host.players += p
        w.step(0)
        assertFalse(w.requestResync(p, 1))
        w.step(1)
        assertEquals(0, host.messages(p))
        assertEquals(0L, traffic.bytesSent(p))
    }

    @Test
    fun `leaving and coming back within the interval does not resend at once`() {
        val w = watchers(resync = 40, scan = 10)
        val p = Player("p", 5.0)
        host.players += p
        w.step(0)
        p.distance = 20.0
        w.step(1)
        assertFalse(w.isWatching(p))
        p.distance = 5.0
        for (t in 2L..39L) w.step(t)
        assertFalse(w.isWatching(p), "coming back within the interval should wait")
        assertEquals(1, host.keyframes(p))
        w.step(40)
        assertTrue(w.isWatching(p))
        assertEquals(2, host.keyframes(p))
    }

    @Test
    fun `remove and clear drop watchers and recording stops`() {
        val w = watchers()
        val a = Player("a", 1.0)
        val b = Player("b", 1.0)
        host.players += listOf(a, b)
        w.step(0)
        assertEquals(2, w.count)
        w.remove(a)
        assertFalse(w.isWatching(a))
        assertEquals(1, w.count)
        w.clear()
        assertEquals(0, w.count)
        host.players.clear()
        w.step(1)
        assertFalse(sender.isRecording, "no watchers, so the sender stops recording")
    }

    @Test
    fun `removed player can start again without waiting for the interval`() {
        val w = watchers(resync = 40, scan = 10)
        val p = Player("p", 5.0)
        host.players += p
        w.step(0)
        assertEquals(1, host.keyframes(p))
        // ディメンションを移って戻ってきたときなど。クライアントの画面は作り直されているので、すぐに画面全体を送る
        w.remove(p)
        w.step(10)
        assertTrue(w.isWatching(p))
        assertEquals(2, host.keyframes(p))
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
    fun `messages are queued until endTick`() {
        val w = watchers()
        val p = Player("p", 1.0)
        host.players += p
        w.tick(0, sender, host, traffic)
        assertEquals(0, host.messages(p), "nothing is sent before endTick")
        traffic.endTick()
        assertEquals(1, host.keyframes(p))
        assertEquals(1L, traffic.keyframesSent(p))
    }

    @Test
    fun `without traffic messages are sent at once`() {
        val w = watchers()
        val p = Player("p", 1.0)
        host.players += p
        w.tick(0, sender, host, null)
        assertEquals(1, host.keyframes(p))
    }

    @Test
    fun `per-player budget limits the total and near or quiet screens go first`() {
        // 予算は 1 tick あたり 25 バイト（1秒分は 500 バイト）。キーフレーム（10x3 の文字画面）は約 260 バイト、
        // 1 tick の描画命令は十数バイト
        val tr = ScreenSyncTraffic<Player>(playerBytesPerSecond = 25 * 20, serverBytesPerSecond = 1 shl 30)
        val p = Player("p", 0.0)
        host.players += p
        val nearText = TextBuffer(10, 3)
        val farText = TextBuffer(10, 3)
        val near = ScreenSyncSender(nearText)
        val far = ScreenSyncSender(farText)
        val nearHost = Host().also { it.players += p }
        val farHost = Host().also { it.players += p }
        val wn = watchers()
        val wf = watchers()
        fun tick(t: Long, farDistance: Double = 8.0) {
            p.distance = 1.0
            wn.tick(t, near, nearHost, tr)
            p.distance = farDistance
            wf.tick(t, far, farHost, tr)
            tr.endTick()
        }
        tick(0)
        assertEquals(1, nearHost.keyframes(p))
        // 初めの予算（1秒分）があるので、遠い方もキーフレームが届く
        assertEquals(1, farHost.keyframes(p))
        // 予算を使い切らせる
        repeat(30) { t ->
            nearText.write(0, t % 3, "n$t", 0xFFFFFF, 0)
            farText.write(0, t % 3, "f$t", 0xFFFFFF, 0)
            tick(1L + t)
        }
        val before = tr.bytesSent(p)
        val farBefore = farHost.messages(p)
        val nearBefore = nearHost.messages(p)
        for (t in 31L..230L) {
            nearText.write(0, (t % 3).toInt(), "n$t", 0xFFFFFF, 0)
            farText.write(0, (t % 3).toInt(), "f$t", 0xFFFFFF, 0)
            tick(t)
        }
        val sent = tr.bytesSent(p) - before
        // 200 tick で 25 B/tick + 初めの残り（1秒分）+ メッセージ1つ分の超過まで
        assertTrue(sent <= 200 * 25 + 500 + 300, "sent $sent bytes")
        assertTrue(tr.throttled(p) > 0, "some messages should be throttled")
        assertTrue(
            nearHost.messages(p) - nearBefore > farHost.messages(p) - farBefore,
            "the near screen should go first: near ${nearHost.messages(p) - nearBefore} (${nearHost.keyframes(p)} keyframes), " +
                "far ${farHost.messages(p) - farBefore} (${farHost.keyframes(p)} keyframes), sent $sent",
        )
        assertTrue(wf.isWatching(p), "a throttled player still counts as watching")

        // 描くのをやめると、止められた画面にも画面全体が届き、追いつく
        for (t in 231L..400L) tick(t)
        assertFalse(wf.isThrottled(p))
        assertFalse(wn.isThrottled(p))
        val client = TextBuffer(10, 3)
        val receiver = ScreenSyncReceiver()
        val target = object : ScreenSyncTarget {
            override val text: TextBuffer = client
            override fun pixels(): PixelBuffer? = null
            override fun pixelsIfCreated(): PixelBuffer? = null
        }
        for (f in farHost.received[p].orEmpty()) receiver.receive(f, target)
        assertTrue(client.contentEquals(farText), "the far client should catch up with a keyframe")
    }

    @Test
    fun `throttled player waits until the keyframe fits the budget`() {
        val tr = ScreenSyncTraffic<Player>(playerBytesPerSecond = 20, serverBytesPerSecond = 1 shl 30)
        val w = watchers(resync = 40)
        val p = Player("p", 1.0)
        host.players += p
        // 初めの予算は 20 バイトしかないが、残りが正なのでキーフレームは送る（残りは負になる）
        w.step(0, tr)
        assertEquals(1, host.keyframes(p))
        text.write(0, 0, "x", 0xFFFFFF, 0)
        w.step(1, tr)
        assertTrue(w.isThrottled(p), "commands over the budget should throttle the player")
        assertEquals(1L, tr.throttled(p))
        // 予算がずっと負なので、止められたまま。画面全体を作り直してもみない
        for (t in 2L..100L) {
            text.write(0, 1, "y$t", 0xFFFFFF, 0)
            w.step(t, tr)
        }
        assertEquals(1, host.keyframes(p))
        assertTrue(w.isThrottled(p))
        assertFalse(sender.isRecording, "nobody receives commands, so the sender stops recording")
        // 止められた人の送り直しのお願いは受け付ける（どのみち画面全体を送る）
        assertTrue(w.requestResync(p, 101))
        // 予算が満タンに戻ると（キーフレームは1秒分より大きいので、満タンのときだけ送る）、画面全体が届いて戻る
        for (t in 101L..400L) w.step(t, tr)
        assertFalse(w.isThrottled(p))
        assertEquals(2, host.keyframes(p))
        // 遠ざかったら外れる
        p.distance = 100.0
        w.step(401, tr)
        assertFalse(w.isWatching(p))
    }

    @Test
    fun `server budget limits all players`() {
        val tr = ScreenSyncTraffic<Player>(playerBytesPerSecond = 1 shl 30, serverBytesPerSecond = 10 * 20)
        val w = watchers()
        val a = Player("a", 1.0)
        val b = Player("b", 2.0)
        host.players += listOf(a, b)
        repeat(200) { t ->
            text.write(0, t % 3, "t$t", 0xFFFFFF, 0)
            w.step(t.toLong(), tr)
        }
        assertTrue(tr.totalBytes <= 200 * 10 + 10 * 20 + 300, "sent ${tr.totalBytes} bytes")
        assertTrue(tr.totalThrottled > 0)
        assertTrue(tr.remainingServerBudget <= 10L * 20)
    }

    @Test
    fun `forget drops queued messages`() {
        val w = watchers()
        val p = Player("p", 1.0)
        host.players += p
        w.tick(0, sender, host, traffic)
        traffic.forget(p)
        traffic.endTick()
        assertEquals(0, host.messages(p))
        assertEquals(0L, traffic.bytesSent(p))
    }

    @Test
    fun `invalid arguments are rejected`() {
        assertThrows<IllegalArgumentException> { ScreenSyncTraffic<Player>(playerBytesPerSecond = 0) }
        assertThrows<IllegalArgumentException> { ScreenSyncTraffic<Player>(serverBytesPerSecond = 0) }
        assertThrows<IllegalArgumentException> { ScreenWatchers<Player>(startDistance = -1.0) }
        assertThrows<IllegalArgumentException> { ScreenWatchers<Player>(startDistance = 10.0, stopDistance = 5.0) }
        assertThrows<IllegalArgumentException> { ScreenWatchers<Player>(resyncIntervalTicks = -1) }
        assertThrows<IllegalArgumentException> { ScreenWatchers<Player>(scanIntervalTicks = 0) }
        assertThrows<IllegalArgumentException> { ScreenWatchers<Player>(throttleRetryTicks = -1) }
    }
}
