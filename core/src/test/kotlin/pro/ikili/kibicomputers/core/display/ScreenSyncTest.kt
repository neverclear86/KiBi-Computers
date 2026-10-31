package pro.ikili.kibicomputers.core.display

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class ScreenSyncTest {
    /** テスト用の画面（サーバーかクライアントの片方）。 */
    private class Screen(w: Int, h: Int, private val pw: Int = 0, private val ph: Int = 0) : ScreenSyncTarget {
        override val text = TextBuffer(w, h)
        private var px: PixelBuffer? = null
        override fun pixels(): PixelBuffer? = if (pw == 0) null else px ?: PixelBuffer(pw, ph).also { px = it }
        override fun pixelsIfCreated(): PixelBuffer? = px
    }

    private class Client(w: Int, h: Int, pw: Int = 0, ph: Int = 0) {
        val screen = Screen(w, h, pw, ph)
        val receiver = ScreenSyncReceiver()
        var messages = 0
        fun receive(fragments: List<ByteArray>) {
            for (f in fragments) if (receiver.receive(f, screen)) messages++
        }
    }

    private fun assertSame(server: Screen, client: Client) {
        assertTrue(server.text.contentEquals(client.screen.text), "text should match")
        val sp = server.pixelsIfCreated()
        val cp = client.screen.pixelsIfCreated()
        if (sp != null) {
            // クライアントがまだピクセル画面を作っていなければ、中身は既定の色と同じ
            val expected = cp ?: PixelBuffer(sp.width, sp.height)
            assertTrue(sp.contentEquals(expected), "pixels should match")
        } else if (cp != null) {
            assertTrue(cp.contentEquals(PixelBuffer(cp.width, cp.height)), "client pixels should be blank")
        }
    }

    private fun isKeyframe(fragments: List<ByteArray>): Boolean = fragments.isNotEmpty() && fragments[0][1].toInt() == 1

    @Test
    fun `no watchers sends nothing and records nothing`() {
        val server = Screen(10, 4)
        val sender = ScreenSyncSender(server.text)
        server.text.write(0, 0, "hello", 0xFFFFFF, 0)
        val f = sender.flush(hasWatchers = false, hasNewWatchers = false)
        assertTrue(f.isEmpty)
        assertFalse(sender.isRecording)
    }

    @Test
    fun `new watcher gets keyframe and then commands`() {
        val server = Screen(20, 5)
        val sender = ScreenSyncSender(server.text)
        server.text.write(1, 1, "before", 0x00FF00, 0)
        val client = Client(20, 5)
        val first = sender.flush(hasWatchers = false, hasNewWatchers = true)
        assertTrue(first.forWatchers.isEmpty())
        assertTrue(isKeyframe(first.forNewWatchers))
        client.receive(first.forNewWatchers)
        assertSame(server, client)
        assertTrue(sender.isRecording)

        server.text.write(0, 0, "日本語 ok", 0xFF0000, 0x000080)
        server.text.copy(0, 1, 20, 4, 0, -1)
        server.text.fill(0, 4, 20, 1, TextBuffer.BLANK, 0xFFFFFF, 0)
        server.text.set(19, 4, '$'.code, 0x123456, 0x654321)
        val next = sender.flush(hasWatchers = true, hasNewWatchers = false)
        assertFalse(isKeyframe(next.forWatchers))
        assertTrue(next.forNewWatchers.isEmpty())
        client.receive(next.forWatchers)
        assertSame(server, client)

        // 何も描かなければ送らない
        assertTrue(sender.flush(hasWatchers = true, hasNewWatchers = false).isEmpty)
    }

    @Test
    fun `random drawing stays in sync for text and pixels`() {
        val rnd = Random(140)
        val server = Screen(40, 12, 80, 48)
        val sender = ScreenSyncSender(server.text)
        val client = Client(40, 12, 80, 48)
        client.receive(sender.flush(false, true).forNewWatchers)
        for (tick in 0 until 200) {
            repeat(rnd.nextInt(6)) {
                val t = server.text
                when (rnd.nextInt(10)) {
                    0 -> t.set(rnd.nextInt(-2, 42), rnd.nextInt(-2, 14), 'a'.code + rnd.nextInt(26), rnd.nextInt(), rnd.nextInt())
                    1 -> t.write(rnd.nextInt(-5, 40), rnd.nextInt(-1, 13), "x\uD800yあ😀", rnd.nextInt(), rnd.nextInt())
                    2 -> t.fill(rnd.nextInt(-5, 40), rnd.nextInt(-5, 12), rnd.nextInt(-1, 20), rnd.nextInt(-1, 8), '#'.code, rnd.nextInt(), rnd.nextInt())
                    3 -> t.copy(rnd.nextInt(-5, 40), rnd.nextInt(-5, 12), rnd.nextInt(30), rnd.nextInt(10), rnd.nextInt(-5, 5), rnd.nextInt(-3, 3))
                    else -> {
                        if (sender.pixels == null) sender.pixels = server.pixels()
                        val p = server.pixels()!!
                        when (rnd.nextInt(5)) {
                            0 -> p.set(rnd.nextInt(-2, 82), rnd.nextInt(-2, 50), rnd.nextInt())
                            1 -> p.fill(rnd.nextInt(-10, 80), rnd.nextInt(-10, 48), rnd.nextInt(30), rnd.nextInt(30), rnd.nextInt())
                            2 -> p.copy(rnd.nextInt(80), rnd.nextInt(48), rnd.nextInt(40), rnd.nextInt(20), rnd.nextInt(-9, 9), rnd.nextInt(-9, 9))
                            3 -> {
                                val img = IntArray(12 * 7) { rnd.nextInt() }
                                p.setPixels(rnd.nextInt(-8, 80), rnd.nextInt(-5, 48), 10, 7, img, 2, 12)
                            }
                            else -> if (rnd.nextInt(20) == 0) p.clear(rnd.nextInt())
                        }
                    }
                }
            }
            val f = sender.flush(hasWatchers = true, hasNewWatchers = false)
            client.receive(f.forWatchers)
            assertSame(server, client)
        }
    }

    @Test
    fun `large drawing in one tick switches to keyframe`() {
        val server = Screen(20, 5)
        val sender = ScreenSyncSender(server.text)
        val client = Client(20, 5)
        client.receive(sender.flush(false, true).forNewWatchers)
        for (y in 0 until 5) for (x in 0 until 20) server.text.set(x, y, 'a'.code + x, x * 1000, y * 1000)
        val f = sender.flush(hasWatchers = true, hasNewWatchers = false)
        assertTrue(isKeyframe(f.forWatchers))
        client.receive(f.forWatchers)
        assertSame(server, client)
    }

    @Test
    fun `existing watchers get commands while new watchers get keyframe in the same tick`() {
        val server = Screen(20, 5)
        val sender = ScreenSyncSender(server.text)
        val old = Client(20, 5)
        old.receive(sender.flush(false, true).forNewWatchers)
        server.text.write(0, 0, "abc", 0xFFFFFF, 0)
        server.text.copy(0, 0, 20, 5, 0, 1)
        val f = sender.flush(hasWatchers = true, hasNewWatchers = true)
        assertFalse(isKeyframe(f.forWatchers))
        assertTrue(isKeyframe(f.forNewWatchers))
        val fresh = Client(20, 5)
        old.receive(f.forWatchers)
        fresh.receive(f.forNewWatchers)
        assertSame(server, old)
        assertSame(server, fresh)
    }

    @Test
    fun `stops recording when watchers leave and resumes with keyframe`() {
        val server = Screen(10, 3)
        val sender = ScreenSyncSender(server.text)
        val client = Client(10, 3)
        client.receive(sender.flush(false, true).forNewWatchers)
        assertTrue(sender.flush(false, false).isEmpty)
        assertFalse(sender.isRecording)
        server.text.write(0, 0, "unseen", 0xFFFFFF, 0)
        val back = sender.flush(false, true)
        assertTrue(isKeyframe(back.forNewWatchers))
        client.receive(back.forNewWatchers)
        assertSame(server, client)
    }

    @Test
    fun `throttles when over the byte budget and catches up with a keyframe`() {
        val server = Screen(20, 5)
        // 1 tick に 20 バイトしか増えない
        val sender = ScreenSyncSender(server.text, bytesPerSecond = 400)
        val client = Client(20, 5)
        client.receive(sender.flush(false, true).forNewWatchers) // キーフレームで残りが負になる
        server.text.write(0, 0, "hello", 0xFFFFFF, 0)
        val throttled = sender.flush(hasWatchers = true, hasNewWatchers = false)
        assertTrue(throttled.forWatchers.isEmpty(), "should not send while over budget")
        var caughtUp: List<ByteArray> = emptyList()
        for (i in 0 until 100) {
            val f = sender.flush(hasWatchers = true, hasNewWatchers = false)
            if (f.forWatchers.isNotEmpty()) {
                caughtUp = f.forWatchers
                break
            }
        }
        assertTrue(isKeyframe(caughtUp), "should send a keyframe once the budget recovers")
        client.receive(caughtUp)
        assertSame(server, client)
    }

    @Test
    fun `large keyframes are split into fragments`() {
        val server = Screen(30, 10, 64, 64)
        val sender = ScreenSyncSender(server.text, maxFragmentSize = 1000)
        sender.pixels = server.pixels()
        server.pixels()!!.fill(3, 3, 40, 40, 0xABCDEF)
        server.text.write(2, 2, "fragmented", 0xFFFFFF, 0)
        val f = sender.flush(false, true).forNewWatchers
        assertTrue(f.size > 1)
        assertTrue(f.all { it.size <= 1000 })
        val client = Client(30, 10, 64, 64)
        client.receive(f)
        assertEquals(1, client.messages)
        assertSame(server, client)
    }

    @Test
    fun `keyframe without pixels clears client pixels`() {
        val server = Screen(10, 3, 8, 8)
        val sender = ScreenSyncSender(server.text)
        val client = Client(10, 3, 8, 8)
        client.screen.pixels()!!.fill(0, 0, 8, 8, 0xFFFFFF)
        client.receive(sender.flush(false, true).forNewWatchers)
        assertSame(server, client)
    }

    @Test
    fun `keyframe resizes the client buffer`() {
        val server = Screen(12, 4)
        server.text.resize(6, 2)
        val sender = ScreenSyncSender(server.text)
        val client = Client(12, 4)
        client.receive(sender.flush(false, true).forNewWatchers)
        assertEquals(6, client.screen.text.width)
        assertSame(server, client)
    }

    @Test
    fun `malformed data throws and drops pending fragments`() {
        val client = Client(10, 3)
        assertThrows<IllegalArgumentException> { client.receiver.receive(byteArrayOf(), client.screen) }
        assertThrows<IllegalArgumentException> { client.receiver.receive(byteArrayOf(1, 9), client.screen) }
        assertThrows<IllegalArgumentException> {
            client.receiver.receive(byteArrayOf(1, 0, SyncOps.TEXT_SET.toByte(), 1), client.screen)
        }
        // ピクセルのない画面にピクセルの命令
        assertThrows<IllegalArgumentException> {
            client.receiver.receive(byteArrayOf(1, 0, SyncOps.PIXEL_CLEAR.toByte(), 0, 0, 0), client.screen)
        }
        assertThrows<IllegalArgumentException> {
            client.receiver.receive(byteArrayOf(1, 1, 100, 100), client.screen)
        }
    }

    @Test
    fun `rejects invalid settings`() {
        assertThrows<IllegalArgumentException> { ScreenSyncSender(TextBuffer(1, 1), bytesPerSecond = 0) }
        assertThrows<IllegalArgumentException> { ScreenSyncSender(TextBuffer(1, 1), maxFragmentSize = 1) }
    }
}
