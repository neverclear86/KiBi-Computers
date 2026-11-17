package pro.ikili.kibicomputers.core.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.input.Key
import pro.ikili.kibicomputers.core.input.KeyEvents
import pro.ikili.kibicomputers.core.input.KeyModifiers
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.input.KeyboardEventCodec
import pro.ikili.kibicomputers.core.input.KeyboardSession
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.event.EventFilter
import pro.ikili.kibicomputers.core.kernel.event.EventQueue
import pro.ikili.kibicomputers.core.kernel.event.EventValue
import pro.ikili.kibicomputers.core.kernel.event.KernelEvent
import java.time.Duration
import java.util.UUID
import kotlin.concurrent.withLock
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** キーの押下・離しのイベント `key-pressed`・`key-released`（#326、ADR 0326） */
class KeyboardEventsTest {
    private val limit = Duration.ofSeconds(30)

    private val address = "00000000-0000-4000-8000-000000000326"

    private val keyKinds = EventFilter(setOf(KeyEvents.PRESSED, KeyEvents.RELEASED))

    private fun Machine.subscribe(filter: EventFilter = EventFilter.ALL, capacity: Int = 64): EventQueue = lock.withLock {
        kernel.events.ensureCapacity(kernel.objects)
        val q = EventQueue(filter, capacity)
        kernel.objects.add(q)
        kernel.events.subscribe(q, kernel.objects)
        q
    }

    private fun Machine.pending(q: EventQueue): List<KernelEvent> = lock.withLock { q.pendingEvents() }

    private fun pressed(name: String, mods: Long, repeat: Boolean) =
        KernelEvent(KeyEvents.PRESSED, address, listOf(EventValue.Text(name), EventValue.Integer(mods), EventValue.Boolean(repeat)))

    private fun released(name: String, mods: Long) =
        KernelEvent(KeyEvents.RELEASED, address, listOf(EventValue.Text(name), EventValue.Integer(mods)))

    @Test
    fun `端末のバイト列が空になるキーでも、返り値は0のままイベントが出る`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val keyboard = KeyboardDevice()
            m.devices.attach(address, keyboard)
            val q = m.subscribe(keyKinds)
            assertEquals(0, keyboard.input(KeyboardEvent.KeyDown(Key.Q, 0, false)))
            assertEquals(0, keyboard.input(KeyboardEvent.KeyDown(Key.LEFT_SHIFT, KeyModifiers.SHIFT, false)))
            assertEquals(0, keyboard.input(KeyboardEvent.KeyDown(Key.CAPS_LOCK, KeyModifiers.CAPS_LOCK, false)))
            val ctrlAlt = KeyModifiers.CONTROL or KeyModifiers.ALT
            assertEquals(0, keyboard.input(KeyboardEvent.KeyDown(Key.A, ctrlAlt, true)))
            assertEquals(0, keyboard.input(KeyboardEvent.KeyUp(Key.Q, 0)))
            assertEquals(
                listOf(
                    pressed("q", 0, false),
                    pressed("left-shift", 1, false),
                    pressed("caps-lock", 0x10, false),
                    pressed("a", 6, true),
                    released("q", 0),
                ),
                m.pending(q),
            )
        }
    }

    @Test
    fun `バイト列も出るキーは、TTYとイベントの両方に出る`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val keyboard = KeyboardDevice()
            m.devices.attach(address, keyboard)
            val q = m.subscribe(keyKinds)
            assertEquals(5, keyboard.input(KeyboardEvent.KeyDown(Key.F5, 0, false)))
            assertEquals(1, keyboard.input(KeyboardEvent.KeyDown(Key.ENTER, 0, false)))
            assertEquals(listOf(pressed("f5", 0, false), pressed("enter", 0, false)), m.pending(q))
        }
    }

    @Test
    fun `文字の入力と貼り付けではイベントを出さず、TTYには流す`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val keyboard = KeyboardDevice()
            m.devices.attach(address, keyboard)
            val all = m.subscribe()
            assertEquals(3, keyboard.input(KeyboardEvent.Text("abc")))
            assertEquals(2, keyboard.input(KeyboardEvent.Paste("xy")))
            assertEquals(emptyList<KernelEvent>(), m.pending(all))
        }
    }

    @Test
    fun `種類を絞ったキューにだけ入り、外した後は出ない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val keyboard = KeyboardDevice()
            m.devices.attach(address, keyboard)
            val onlyPressed = m.subscribe(EventFilter(setOf(KeyEvents.PRESSED)))
            val other = m.subscribe(EventFilter(setOf("timer")))
            keyboard.input(KeyboardEvent.KeyDown(Key.Z, 0, false))
            keyboard.input(KeyboardEvent.KeyUp(Key.Z, 0))
            m.devices.detach(address)
            val afterDetach = m.subscribe(keyKinds)
            assertEquals(0, keyboard.input(KeyboardEvent.KeyDown(Key.Z, 0, false)))
            assertEquals(listOf(pressed("z", 0, false)), m.pending(onlyPressed))
            assertEquals(emptyList<KernelEvent>(), m.pending(other))
            assertEquals(emptyList<KernelEvent>(), m.pending(afterDetach))
        }
    }

    @Test
    fun `キューがなくても例外にならない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val keyboard = KeyboardDevice()
            m.devices.attach(address, keyboard)
            assertEquals(0, keyboard.input(KeyboardEvent.KeyDown(Key.Q, 0, false)))
            assertEquals(0, keyboard.input(KeyboardEvent.KeyUp(Key.Q, 0)))
        }
    }

    @Test
    fun `知らない修飾キーのビットは落とす`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val keyboard = KeyboardDevice()
            m.devices.attach(address, keyboard)
            val q = m.subscribe(keyKinds)
            keyboard.input(KeyboardEvent.KeyDown(Key.B, 0x40 or KeyModifiers.SHIFT, false))
            keyboard.input(KeyboardEvent.KeyUp(Key.B, -1))
            assertEquals(listOf(pressed("b", 1, false), released("b", KeyModifiers.ALL.toLong())), m.pending(q))
        }
    }

    @Test
    fun `セッションを閉じて合成した離しは、修飾キー0で届く`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val keyboard = KeyboardDevice()
            m.devices.attach(address, keyboard)
            val q = m.subscribe(keyKinds)
            val session = KeyboardSession(1, UUID(0, 1))
            val received = session.receive(
                KeyboardEventCodec.encode(
                    listOf(
                        KeyboardEvent.KeyDown(Key.LEFT_CONTROL, KeyModifiers.CONTROL, false),
                        KeyboardEvent.KeyDown(Key.C, KeyModifiers.CONTROL, false),
                    ),
                ).single(),
                0,
            )!!
            for (e in received) keyboard.input(e)
            for (e in session.close()) keyboard.input(e)
            assertEquals(
                listOf(
                    pressed("left-control", 2, false),
                    pressed("c", 2, false),
                    released("left-control", 0),
                    released("c", 0),
                ),
                m.pending(q),
            )
        }
    }

    @Test
    fun `キー名の表は凍らせてある`() {
        // ABI（#326）。Key の名前を変えても、この表は変えない
        val expected = listOf(
            "unknown",
            "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m",
            "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
            "digit-0", "digit-1", "digit-2", "digit-3", "digit-4", "digit-5", "digit-6", "digit-7", "digit-8", "digit-9",
            "space", "apostrophe", "comma", "minus", "period", "slash", "semicolon", "equal",
            "left-bracket", "backslash", "right-bracket", "grave-accent",
            "world-1", "world-2",
            "escape", "enter", "tab", "backspace", "insert", "delete",
            "right", "left", "down", "up", "page-up", "page-down", "home", "end",
            "caps-lock", "scroll-lock", "num-lock", "print-screen", "pause",
            "f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8", "f9", "f10", "f11", "f12",
            "keypad-0", "keypad-1", "keypad-2", "keypad-3", "keypad-4", "keypad-5", "keypad-6", "keypad-7", "keypad-8", "keypad-9",
            "keypad-decimal", "keypad-divide", "keypad-multiply", "keypad-subtract", "keypad-add", "keypad-enter", "keypad-equal",
            "left-shift", "left-control", "left-alt", "left-super",
            "right-shift", "right-control", "right-alt", "right-super",
            "menu",
        )
        val actual = Key.entries.map { KeyEvents.name(it) }
        assertEquals(expected.toSet(), actual.toSet())
        assertEquals(actual.size, actual.toSet().size, "名前が重ならない")
        assertEquals(expected.size, actual.size)
        assertTrue(actual.all { it.isNotEmpty() && it.length <= 64 && it.matches(Regex("[a-z0-9-]+")) })
        assertEquals("a", KeyEvents.name(Key.A))
        assertEquals("digit-0", KeyEvents.name(Key.DIGIT_0))
        assertEquals("left-shift", KeyEvents.name(Key.LEFT_SHIFT))
        assertEquals("keypad-enter", KeyEvents.name(Key.KEYPAD_ENTER))
        assertEquals("world-2", KeyEvents.name(Key.WORLD_2))
        assertEquals("unknown", KeyEvents.name(Key.UNKNOWN))
    }
}
