package pro.ikili.kibicomputers.core.input

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.input.KeyboardEvent.KeyDown
import pro.ikili.kibicomputers.core.input.KeyboardEvent.KeyUp
import pro.ikili.kibicomputers.core.input.KeyboardEvent.Paste
import pro.ikili.kibicomputers.core.input.KeyboardEvent.Text

class KeyboardEventCodecTest {
    @Test
    fun roundTripsAllKindsOfEvents() {
        val events = listOf(
            KeyDown(Key.A, KeyModifiers.SHIFT, false),
            Text("A"),
            KeyDown(Key.A, KeyModifiers.SHIFT, true),
            KeyUp(Key.A, KeyModifiers.NONE),
            Text("日本語😀"),
            Paste("line1\nline2\tend"),
            KeyDown(Key.MENU, KeyModifiers.ALL, false),
        )
        val packets = KeyboardEventCodec.encode(events)
        assertEquals(1, packets.size)
        assertEquals(events, KeyboardEventCodec.decode(packets[0]))
    }

    @Test
    fun encodesNothingForNoEvents() {
        assertTrue(KeyboardEventCodec.encode(emptyList()).isEmpty())
        assertTrue(KeyboardEventCodec.decode(ByteArray(0)).isEmpty())
    }

    @Test
    fun splitsLongTextAtCodePointsAndPackets() {
        // 4 バイトの文字を上限より多く。分けても文字は壊れない
        val text = "😀".repeat(KeyboardEventCodec.MAX_TEXT_BYTES / 4 * 3 + 1)
        val packets = KeyboardEventCodec.encode(listOf(Paste(text), Text("x")))
        assertTrue(packets.size >= 2)
        packets.forEach { assertTrue(it.size <= KeyboardEventCodec.MAX_PACKET_BYTES) }
        val decoded = packets.flatMap { KeyboardEventCodec.decode(it) }
        assertEquals(text, decoded.dropLast(1).joinToString("") { (it as Paste).text })
        assertEquals(Text("x"), decoded.last())
    }

    @Test
    fun splitsByEventCount() {
        val events = List(KeyboardEventCodec.MAX_EVENTS + 5) { KeyUp(Key.B, 0) }
        val packets = KeyboardEventCodec.encode(events)
        assertEquals(2, packets.size)
        assertEquals(events, packets.flatMap { KeyboardEventCodec.decode(it) })
    }

    @Test
    fun rejectsMalformedPackets() {
        val good = KeyboardEventCodec.encode(listOf(KeyDown(Key.Z, 0, false)))[0]
        // 途中で切れている
        assertThrows<IllegalArgumentException> { KeyboardEventCodec.decode(good.copyOf(good.size - 1)) }
        // 知らない種類
        assertThrows<IllegalArgumentException> { KeyboardEventCodec.decode(byteArrayOf(9)) }
        // 知らないキー
        assertThrows<IllegalArgumentException> { KeyboardEventCodec.decode(byteArrayOf(1, 0x7F, 0)) }
        // 知らない修飾キーのビット
        assertThrows<IllegalArgumentException> { KeyboardEventCodec.decode(byteArrayOf(1, 1, 0x40)) }
        // 真偽が 0/1 でない
        assertThrows<IllegalArgumentException> { KeyboardEventCodec.decode(byteArrayOf(0, 1, 0, 2)) }
        // 壊れた UTF-8
        assertThrows<IllegalArgumentException> { KeyboardEventCodec.decode(byteArrayOf(2, 1, 0xC3.toByte())) }
        // 文字に制御文字
        assertThrows<IllegalArgumentException> { KeyboardEventCodec.decode(byteArrayOf(2, 1, 0x03)) }
        // 貼り付けでも ESC は許さない
        assertThrows<IllegalArgumentException> { KeyboardEventCodec.decode(byteArrayOf(3, 1, 0x1B)) }
        // 空の文字列
        assertThrows<IllegalArgumentException> { KeyboardEventCodec.decode(byteArrayOf(2, 0)) }
        // 大きすぎる
        assertThrows<IllegalArgumentException> { KeyboardEventCodec.decode(ByteArray(KeyboardEventCodec.MAX_PACKET_BYTES + 1)) }
        // 長さが残りより長い
        assertThrows<IllegalArgumentException> { KeyboardEventCodec.decode(byteArrayOf(2, 0x7F, 0x41)) }
    }
}

class KeyboardTextTest {
    @Test
    fun sanitizesPaste() {
        assertEquals("a\nb\nc\td", KeyboardText.sanitizePaste("a\r\nb\rc\td\u0000\u001b\u0085"))
        assertEquals("ab", KeyboardText.sanitizePaste("a\uD800b"))
        assertEquals("x😀", KeyboardText.sanitizePaste("x😀"))
    }

    @Test
    fun truncatesPasteAtCharacterBoundary() {
        assertEquals("あ", KeyboardText.sanitizePaste("あい", maxBytes = 5))
        assertEquals("", KeyboardText.sanitizePaste("😀", maxBytes = 3))
    }

    @Test
    fun validatesText() {
        assertTrue(KeyboardText.isValidText("あ😀"))
        assertFalse(KeyboardText.isValidText(""))
        assertFalse(KeyboardText.isValidText("a\n"))
        assertFalse(KeyboardText.isValidText("\uDC00"))
        assertTrue(KeyboardText.isValidPaste("a\n\tb"))
        assertFalse(KeyboardText.isValidPaste("a\r"))
    }

    @Test
    fun joinsSurrogatePairs() {
        val joiner = Utf16Joiner()
        assertEquals("a", joiner.feed('a'))
        val pair = "😀"
        assertNull(joiner.feed(pair[0]))
        assertEquals(pair, joiner.feed(pair[1]))
        // 対にならない後半は捨てる
        assertNull(joiner.feed(pair[1]))
        // 前半のあとに普通の文字が来たら、前半は捨てる
        assertNull(joiner.feed(pair[0]))
        assertEquals("b", joiner.feed('b'))
    }
}

class TerminalKeyEncoderTest {
    private fun key(key: Key, mods: Int = 0) = String(TerminalKeyEncoder.encode(KeyDown(key, mods, false)), Charsets.ISO_8859_1)

    @Test
    fun encodesTextAndPaste() {
        assertArrayEquals("あa".toByteArray(), TerminalKeyEncoder.encode(Text("あa")))
        assertEquals("a\rb", String(TerminalKeyEncoder.encode(Paste("a\nb"))))
    }

    @Test
    fun printableKeysAloneProduceNothing() {
        // 文字は Text で届くので、キーからは作らない
        assertEquals("", key(Key.A))
        assertEquals("", key(Key.A, KeyModifiers.SHIFT))
        assertEquals("", key(Key.SPACE))
        assertEquals("", key(Key.LEFT_SHIFT))
        assertEquals("", String(TerminalKeyEncoder.encode(KeyUp(Key.ENTER, 0))))
    }

    @Test
    fun encodesEditingKeys() {
        assertEquals("\r", key(Key.ENTER))
        assertEquals("\r", key(Key.KEYPAD_ENTER))
        assertEquals("\u007f", key(Key.BACKSPACE))
        assertEquals("\b", key(Key.BACKSPACE, KeyModifiers.CONTROL))
        assertEquals("\t", key(Key.TAB))
        assertEquals("\u001b[Z", key(Key.TAB, KeyModifiers.SHIFT))
        assertEquals("\u001b", key(Key.ESCAPE))
    }

    @Test
    fun encodesCursorAndFunctionKeys() {
        assertEquals("\u001b[A", key(Key.UP))
        assertEquals("\u001b[D", key(Key.LEFT))
        assertEquals("\u001b[1;5C", key(Key.RIGHT, KeyModifiers.CONTROL))
        assertEquals("\u001b[1;2B", key(Key.DOWN, KeyModifiers.SHIFT))
        assertEquals("\u001b[H", key(Key.HOME))
        assertEquals("\u001b[F", key(Key.END))
        assertEquals("\u001b[3~", key(Key.DELETE))
        assertEquals("\u001b[5;3~", key(Key.PAGE_UP, KeyModifiers.ALT))
        assertEquals("\u001bOP", key(Key.F1))
        assertEquals("\u001b[1;2S", key(Key.F4, KeyModifiers.SHIFT))
        assertEquals("\u001b[15~", key(Key.F5))
        assertEquals("\u001b[24~", key(Key.F12))
    }

    @Test
    fun encodesControlAndAltCombinations() {
        assertEquals("\u0003", key(Key.C, KeyModifiers.CONTROL))
        assertEquals("\u0001", key(Key.A, KeyModifiers.CONTROL or KeyModifiers.SHIFT))
        assertEquals("\u001a", key(Key.Z, KeyModifiers.CONTROL))
        assertEquals("\u0000", key(Key.SPACE, KeyModifiers.CONTROL))
        assertEquals("\u001c", key(Key.BACKSLASH, KeyModifiers.CONTROL))
        assertEquals("\u001bx", key(Key.X, KeyModifiers.ALT))
        assertEquals("\u001bX", key(Key.X, KeyModifiers.ALT or KeyModifiers.SHIFT))
        assertEquals("\u001b.", key(Key.PERIOD, KeyModifiers.ALT))
        assertEquals("\u001b\r", key(Key.ENTER, KeyModifiers.ALT))
        // Ctrl+Alt は AltGr とみなす
        assertEquals("", key(Key.Q, KeyModifiers.CONTROL or KeyModifiers.ALT))
    }

    @Test
    fun encodesSequenceOfEvents() {
        val bytes = TerminalKeyEncoder.encodeAll(
            listOf(KeyDown(Key.H, 0, false), Text("h"), KeyUp(Key.H, 0), Text("i"), KeyDown(Key.ENTER, 0, false)),
        )
        assertEquals("hi\r", String(bytes))
    }
}

class KeyboardInputBudgetTest {
    @Test
    fun refillsOverTimeUpToCapacity() {
        val budget = KeyboardInputBudget(capacity = 100, refillPerTick = 10)
        assertTrue(budget.tryConsume(100, 0))
        assertFalse(budget.tryConsume(1, 0))
        assertEquals(30, budget.available(3))
        assertFalse(budget.tryConsume(31, 3))
        assertTrue(budget.tryConsume(30, 3))
        assertEquals(100, budget.available(1_000_000_000L))
    }

    @Test
    fun zeroRefillNeverRefills() {
        val budget = KeyboardInputBudget(capacity = 10, refillPerTick = 0)
        assertTrue(budget.tryConsume(10, 0))
        assertEquals(0, budget.available(100))
    }

    @Test
    fun keyHelpers() {
        assertEquals('A', Key.A.letter)
        assertEquals('Z', Key.Z.letter)
        assertNull(Key.DIGIT_0.letter)
        assertTrue(Key.RIGHT_SUPER.isModifier)
        assertFalse(Key.MENU.isModifier)
        assertEquals(Key.ENTER, Key.fromOrdinal(Key.ENTER.ordinal))
        assertNull(Key.fromOrdinal(-1))
        assertNull(Key.fromOrdinal(Key.entries.size))
    }
}
