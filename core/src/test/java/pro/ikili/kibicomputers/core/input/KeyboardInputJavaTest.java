package pro.ikili.kibicomputers.core.input;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * キーボードの入力（#142）の公開宣言が Java から自然に使えることを、コンパイルが通るかで確かめるテスト。
 */
class KeyboardInputJavaTest {
    /** Java 17 のパターンつき instanceof で分岐し、フィールドを読む */
    private static String describe(KeyboardEvent ev) {
        if (ev instanceof KeyboardEvent.KeyDown kd) {
            return "down:" + kd.key + ":" + kd.modifiers + ":" + kd.repeat;
        } else if (ev instanceof KeyboardEvent.KeyUp ku) {
            return "up:" + ku.key + ":" + ku.modifiers;
        } else if (ev instanceof KeyboardEvent.Text t) {
            return "text:" + t.text;
        } else if (ev instanceof KeyboardEvent.Paste p) {
            return "paste:" + p.text;
        }
        throw new AssertionError(ev);
    }

    @Test
    void eventsFromJava() {
        int mods = KeyModifiers.SHIFT | KeyModifiers.CONTROL;
        assertEquals(0, KeyModifiers.NONE);
        assertEquals(0, mods & ~KeyModifiers.ALL);
        assertTrue((KeyModifiers.ALL & KeyModifiers.ALT) != 0);
        assertTrue((KeyModifiers.ALL & KeyModifiers.SUPER) != 0);
        assertTrue((KeyModifiers.ALL & KeyModifiers.CAPS_LOCK) != 0);
        assertTrue((KeyModifiers.ALL & KeyModifiers.NUM_LOCK) != 0);

        assertEquals("down:A:3:true", describe(new KeyboardEvent.KeyDown(Key.A, mods, true)));
        assertEquals("up:ENTER:0", describe(new KeyboardEvent.KeyUp(Key.ENTER, KeyModifiers.NONE)));
        assertEquals("text:あ", describe(new KeyboardEvent.Text("あ")));
        assertEquals("paste:a\nb", describe(new KeyboardEvent.Paste("a\nb")));
        assertEquals(new KeyboardEvent.Text("x"), new KeyboardEvent.Text("x"));

        assertEquals(Key.A, Key.fromOrdinal(Key.A.ordinal()));
        assertNull(Key.fromOrdinal(-1));
        assertNull(Key.fromOrdinal(Key.values().length));
        assertEquals(Character.valueOf('B'), Key.B.getLetter());
        assertNull(Key.ENTER.getLetter());
        assertTrue(Key.LEFT_SHIFT.isModifier());
    }

    @Test
    void listenerAsLambda() {
        List<KeyboardEvent> got = new ArrayList<>();
        KeyboardListener lambda = (typist, ev) -> got.add(ev);
        UUID typist = new UUID(0L, 1L);
        lambda.onKeyboardEvent(typist, new KeyboardEvent.Text("a"));
        lambda.onKeyboardEvent(typist, new KeyboardEvent.KeyUp(Key.B, KeyModifiers.NONE));
        assertEquals(2, got.size());
    }

    @Test
    void sessionFromJava() {
        UUID typist = new UUID(0L, 2L);
        KeyboardSession defaults = new KeyboardSession(1, typist);
        assertEquals(KeyboardInputBudget.DEFAULT_CAPACITY, defaults.getBudget().getCapacity());
        KeyboardSession session = new KeyboardSession(2, typist, new KeyboardInputBudget(1000, 0));
        assertEquals(2, session.getId());
        assertEquals(typist, session.getTypist());
        byte[] data = KeyboardEventCodec.encode(List.of(new KeyboardEvent.KeyDown(Key.A, KeyModifiers.NONE, false))).get(0);
        List<KeyboardEvent> got = session.receive(data, 0L);
        assertEquals(1, got.size());
        assertTrue(session.getHeldKeys().contains(Key.A));
        assertNull(session.receive(new byte[] {(byte) 99}, 0L));
        assertEquals(List.of(new KeyboardEvent.KeyUp(Key.A, KeyModifiers.NONE)), session.close());
        assertFalse(session.isOpen());
        assertNull(session.receive(data, 0L));
    }

    @Test
    void codecFromJava() {
        List<KeyboardEvent> events = List.of(
                new KeyboardEvent.KeyDown(Key.LEFT, KeyModifiers.SHIFT, false),
                new KeyboardEvent.KeyUp(Key.LEFT, KeyModifiers.NONE),
                new KeyboardEvent.Text("é"),
                new KeyboardEvent.Paste("line1\nline2"));
        List<byte[]> packets = KeyboardEventCodec.encode(events);
        assertEquals(1, packets.size());
        assertTrue(packets.get(0).length <= KeyboardEventCodec.MAX_PACKET_BYTES);
        assertEquals(events, KeyboardEventCodec.decode(packets.get(0)));
        assertTrue(KeyboardEventCodec.MAX_EVENTS > 0);
        assertTrue(KeyboardEventCodec.MAX_TEXT_BYTES > 0);
        assertThrows(IllegalArgumentException.class, () -> KeyboardEventCodec.decode(new byte[] {(byte) 99}));
    }

    @Test
    void budgetFromJava() {
        KeyboardInputBudget defaults = new KeyboardInputBudget();
        assertEquals(KeyboardInputBudget.DEFAULT_CAPACITY, defaults.getCapacity());
        assertEquals(KeyboardInputBudget.DEFAULT_REFILL_PER_TICK, defaults.getRefillPerTick());
        KeyboardInputBudget capOnly = new KeyboardInputBudget(100);
        assertEquals(KeyboardInputBudget.DEFAULT_REFILL_PER_TICK, capOnly.getRefillPerTick());

        KeyboardInputBudget budget = new KeyboardInputBudget(10, 5);
        assertTrue(budget.tryConsume(8, 0L));
        assertFalse(budget.tryConsume(8, 0L));
        assertEquals(2, budget.available(0L));
        assertTrue(budget.tryConsume(7, 1L));
        assertThrows(IllegalArgumentException.class, () -> budget.tryConsume(-1, 1L));
        assertThrows(IllegalArgumentException.class, () -> new KeyboardInputBudget(0));
        assertThrows(IllegalArgumentException.class, () -> new KeyboardInputBudget(10, -1));
    }

    @Test
    void textFromJava() {
        assertTrue(KeyboardText.isValidText("abc"));
        assertFalse(KeyboardText.isValidText("a\nb"));
        assertTrue(KeyboardText.isValidPaste("a\tb\nc"));
        assertFalse(KeyboardText.isValidPaste(""));
        assertTrue(KeyboardText.MAX_PASTE_BYTES > 0);

        assertEquals("a\nb\nc", KeyboardText.sanitizePaste("a\r\nb\rc\u0007"));
        assertEquals("ab", KeyboardText.sanitizePaste("abc", 2));
        assertEquals("", KeyboardText.sanitizePaste("あ", 2));
        assertThrows(IllegalArgumentException.class, () -> KeyboardText.sanitizePaste("a", -1));

        Utf16Joiner joiner = new Utf16Joiner();
        String emoji = "😀";
        assertNull(joiner.feed(emoji.charAt(0)));
        assertEquals(emoji, joiner.feed(emoji.charAt(1)));
        assertEquals("x", joiner.feed('x'));
        assertNull(joiner.feed(emoji.charAt(0)));
        joiner.reset();
        assertNull(joiner.feed(emoji.charAt(1)));
    }

    @Test
    void terminalEncoderFromJava() {
        assertArrayEquals(new byte[] {0x0D}, TerminalKeyEncoder.encode(new KeyboardEvent.KeyDown(Key.ENTER, KeyModifiers.NONE, false)));
        assertArrayEquals(new byte[] {0x03}, TerminalKeyEncoder.encode(new KeyboardEvent.KeyDown(Key.C, KeyModifiers.CONTROL, false)));
        assertEquals(0, TerminalKeyEncoder.encode(new KeyboardEvent.KeyUp(Key.C, KeyModifiers.NONE)).length);
        byte[] all = TerminalKeyEncoder.encodeAll(List.of(
                new KeyboardEvent.Text("hi"),
                new KeyboardEvent.Paste("a\nb"),
                new KeyboardEvent.KeyDown(Key.UP, KeyModifiers.NONE, false)));
        assertArrayEquals("hia\rb\u001b[A".getBytes(StandardCharsets.UTF_8), all);
    }
}
