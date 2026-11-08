package pro.ikili.kibicomputers.core.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.kernel.Harness
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** キーボードのデバイス（#65、ADR 0065） */
class KeyboardDeviceTest {
    private val limit = Duration.ofSeconds(30)

    @Test
    fun `つないでいる間だけ入力をコンソールのTTYに流す`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val keyboard = KeyboardDevice()
            assertEquals(DeviceKind.KEYBOARD, keyboard.kind)
            assertTrue(keyboard.methods.names.isEmpty())
            assertEquals(0, keyboard.input(KeyboardEvent.Text("a")), "つないでいなければ捨てる")
            m.devices.attach(KEYBOARD_ADDRESS, keyboard)
            assertEquals(m, keyboard.machine)
            assertEquals(3, keyboard.input(KeyboardEvent.Text("abc")))
            assertEquals(0, keyboard.input(KeyboardEvent.Text("")), "送るものがない")
            m.devices.detach(KEYBOARD_ADDRESS)
            assertEquals(null, keyboard.machine)
            assertEquals(0, keyboard.input(KeyboardEvent.Text("x")))
        }
    }
}
