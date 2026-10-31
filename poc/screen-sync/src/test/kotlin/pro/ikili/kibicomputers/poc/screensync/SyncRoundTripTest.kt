package pro.ikili.kibicomputers.poc.screensync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.random.Random

class SyncRoundTripTest {
    companion object {
        @JvmStatic
        fun cases(): List<Arguments> = Scenario.all.flatMap { s ->
            Method.entries.map { m -> Arguments.of(s, m) }
        }
    }

    /** どの方式でも、毎 tick クライアントの画面がサーバーと同じになる。 */
    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("cases")
    fun clientMatchesServerEveryTick(scenario: Scenario, method: Method) {
        val (w, h) = if (scenario.text) 40 to 12 else 64 to 40
        val server = newScreen(scenario.text, w, h)
        val client = newScreen(scenario.text, w, h)
        val sender = method.sender(server)
        val receiver = method.receiver(client)
        val rnd = Random(1)
        repeat(30) { t ->
            scenario.tick(server, t, rnd)
            sender.encode()?.let(receiver::receive)
            assertTrue(server.sameAs(client), "tick $t")
        }
    }

    @Test
    fun idleSendsNothing() {
        for (method in Method.entries) {
            val sender = method.sender(TextScreen(80, 25))
            assertEquals(null, sender.encode(), method.label)
        }
    }

    @Test
    fun varintRoundTrip() {
        val b = Buf(1)
        val values = intArrayOf(0, 1, 127, 128, 300, Int.MAX_VALUE, -1, -300, Int.MIN_VALUE)
        for (v in values) { b.varint(v); b.svarint(v) }
        val r = Reader(b.toByteArray())
        for (v in values) {
            assertEquals(v, r.varint())
            assertEquals(v, r.svarint())
        }
    }

    @Test
    fun overlappingCopyScrollsUp() {
        val s = TextScreen(3, 3)
        for (y in 0 until 3) s.set(0, y, "$y$y$y", 1, 2)
        s.copy(0, 1, 3, 2, 0, -1)
        assertEquals('1'.code, s.cp[0])
        assertEquals('2'.code, s.cp[3])
        assertEquals('2'.code, s.cp[6])
    }
}
