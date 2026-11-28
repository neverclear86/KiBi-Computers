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
import pro.ikili.kibicomputers.core.testing.bodyTimeout as timeout

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
            // デバイスは届いた修飾キーをそのまま出す。修飾キー自身の押下に自分のビットが付くかは、クライアント（GLFW の OS ごとの
            // 実装と KeyboardInputScreen）次第で、ゲストには約束しない（ADR 0326）。ここでは付いて届いた場合を流すだけ
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
        // ABI（#326）。Key の名前を変えても、この表は変えない。どのキーがどの名前かを1つずつ書く
        val expected = listOf(
            Key.UNKNOWN to "unknown",
            Key.A to "a",
            Key.B to "b",
            Key.C to "c",
            Key.D to "d",
            Key.E to "e",
            Key.F to "f",
            Key.G to "g",
            Key.H to "h",
            Key.I to "i",
            Key.J to "j",
            Key.K to "k",
            Key.L to "l",
            Key.M to "m",
            Key.N to "n",
            Key.O to "o",
            Key.P to "p",
            Key.Q to "q",
            Key.R to "r",
            Key.S to "s",
            Key.T to "t",
            Key.U to "u",
            Key.V to "v",
            Key.W to "w",
            Key.X to "x",
            Key.Y to "y",
            Key.Z to "z",
            Key.DIGIT_0 to "digit-0",
            Key.DIGIT_1 to "digit-1",
            Key.DIGIT_2 to "digit-2",
            Key.DIGIT_3 to "digit-3",
            Key.DIGIT_4 to "digit-4",
            Key.DIGIT_5 to "digit-5",
            Key.DIGIT_6 to "digit-6",
            Key.DIGIT_7 to "digit-7",
            Key.DIGIT_8 to "digit-8",
            Key.DIGIT_9 to "digit-9",
            Key.SPACE to "space",
            Key.APOSTROPHE to "apostrophe",
            Key.COMMA to "comma",
            Key.MINUS to "minus",
            Key.PERIOD to "period",
            Key.SLASH to "slash",
            Key.SEMICOLON to "semicolon",
            Key.EQUAL to "equal",
            Key.LEFT_BRACKET to "left-bracket",
            Key.BACKSLASH to "backslash",
            Key.RIGHT_BRACKET to "right-bracket",
            Key.GRAVE_ACCENT to "grave-accent",
            Key.WORLD_1 to "world-1",
            Key.WORLD_2 to "world-2",
            Key.ESCAPE to "escape",
            Key.ENTER to "enter",
            Key.TAB to "tab",
            Key.BACKSPACE to "backspace",
            Key.INSERT to "insert",
            Key.DELETE to "delete",
            Key.RIGHT to "right",
            Key.LEFT to "left",
            Key.DOWN to "down",
            Key.UP to "up",
            Key.PAGE_UP to "page-up",
            Key.PAGE_DOWN to "page-down",
            Key.HOME to "home",
            Key.END to "end",
            Key.CAPS_LOCK to "caps-lock",
            Key.SCROLL_LOCK to "scroll-lock",
            Key.NUM_LOCK to "num-lock",
            Key.PRINT_SCREEN to "print-screen",
            Key.PAUSE to "pause",
            Key.F1 to "f1",
            Key.F2 to "f2",
            Key.F3 to "f3",
            Key.F4 to "f4",
            Key.F5 to "f5",
            Key.F6 to "f6",
            Key.F7 to "f7",
            Key.F8 to "f8",
            Key.F9 to "f9",
            Key.F10 to "f10",
            Key.F11 to "f11",
            Key.F12 to "f12",
            Key.KEYPAD_0 to "keypad-0",
            Key.KEYPAD_1 to "keypad-1",
            Key.KEYPAD_2 to "keypad-2",
            Key.KEYPAD_3 to "keypad-3",
            Key.KEYPAD_4 to "keypad-4",
            Key.KEYPAD_5 to "keypad-5",
            Key.KEYPAD_6 to "keypad-6",
            Key.KEYPAD_7 to "keypad-7",
            Key.KEYPAD_8 to "keypad-8",
            Key.KEYPAD_9 to "keypad-9",
            Key.KEYPAD_DECIMAL to "keypad-decimal",
            Key.KEYPAD_DIVIDE to "keypad-divide",
            Key.KEYPAD_MULTIPLY to "keypad-multiply",
            Key.KEYPAD_SUBTRACT to "keypad-subtract",
            Key.KEYPAD_ADD to "keypad-add",
            Key.KEYPAD_ENTER to "keypad-enter",
            Key.KEYPAD_EQUAL to "keypad-equal",
            Key.LEFT_SHIFT to "left-shift",
            Key.LEFT_CONTROL to "left-control",
            Key.LEFT_ALT to "left-alt",
            Key.LEFT_SUPER to "left-super",
            Key.RIGHT_SHIFT to "right-shift",
            Key.RIGHT_CONTROL to "right-control",
            Key.RIGHT_ALT to "right-alt",
            Key.RIGHT_SUPER to "right-super",
            Key.MENU to "menu",
        )
        assertEquals(Key.entries.toList(), expected.map { it.first }, "全キーが Key の並びで1つずつある")
        for ((key, name) in expected) assertEquals(name, KeyEvents.name(key), "$key")
        val actual = Key.entries.map { KeyEvents.name(it) }
        assertEquals(actual.size, actual.toSet().size, "名前が重ならない")
        assertTrue(actual.all { it.isNotEmpty() && it.length <= 64 && it.matches(Regex("[a-z0-9-]+")) })
    }
}
