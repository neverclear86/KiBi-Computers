package pro.ikili.kibicomputers.core.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.input.Key
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.input.KeyboardText
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.MachineLimits
import pro.ikili.kibicomputers.core.kernel.tty.TtyMode
import java.time.Duration
import kotlin.concurrent.withLock
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

    @Test
    fun `貼り付けはTTYに入りきらなければ待ちの列に積み、全部を受け付けたものとして数える`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val keyboard = KeyboardDevice()
            m.devices.attach(KEYBOARD_ADDRESS, keyboard)
            m.lock.withLock { m.consoleTtyLocked().setMode(TtyMode.RAW, m.wakeSink) }
            assertEquals(6000, keyboard.input(KeyboardEvent.Paste("p".repeat(6000))))
            m.lock.withLock {
                val tty = m.consoleTtyLocked()
                assertEquals(MachineLimits.CONSOLE_INPUT_BYTES, tty.inputAvailable)
                assertEquals(6000 - MachineLimits.CONSOLE_INPUT_BYTES, tty.pendingInputBytes)
                assertEquals(0L, tty.droppedInputBytes)
            }
        }
    }

    @Test
    fun `文字の入力は分けてよい入力として、キーは分けない入力として渡す`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val keyboard = KeyboardDevice()
            m.devices.attach(KEYBOARD_ADDRESS, keyboard)
            m.lock.withLock { m.consoleTtyLocked().setMode(TtyMode.RAW, m.wakeSink) }
            // 入力をいっぱいにする
            assertEquals(MachineLimits.CONSOLE_INPUT_BYTES, keyboard.input(KeyboardEvent.Paste("p".repeat(MachineLimits.CONSOLE_INPUT_BYTES))))
            // キー（エスケープシーケンス）は分けない入力なので、入らなければ捨てる
            assertEquals(0, keyboard.input(KeyboardEvent.KeyDown(Key.UP, 0, false)))
            val dropped = m.lock.withLock {
                val tty = m.consoleTtyLocked()
                assertEquals(0, tty.pendingInputBytes)
                tty.droppedInputBytes
            }
            assertTrue(dropped > 0)
            // 文字の入力は分けてよい入力なので、入らなければ待ちの列に積んで、捨てない
            assertEquals(1, keyboard.input(KeyboardEvent.Text("x")))
            m.lock.withLock {
                val tty = m.consoleTtyLocked()
                assertEquals(1, tty.pendingInputBytes)
                assertEquals(dropped, tty.droppedInputBytes)
            }
        }
    }

    @Test
    fun `入力がいっぱいでも、貼り付けの上限ちょうどの貼り付けは全部受け付けて捨てない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val keyboard = KeyboardDevice()
            m.devices.attach(KEYBOARD_ADDRESS, keyboard)
            m.lock.withLock { m.consoleTtyLocked().setMode(TtyMode.RAW, m.wakeSink) }
            assertEquals(MachineLimits.CONSOLE_INPUT_BYTES, keyboard.input(KeyboardEvent.Paste("f".repeat(MachineLimits.CONSOLE_INPUT_BYTES))))
            assertEquals(KeyboardText.MAX_PASTE_BYTES, keyboard.input(KeyboardEvent.Paste("p".repeat(KeyboardText.MAX_PASTE_BYTES))))
            m.lock.withLock {
                val tty = m.consoleTtyLocked()
                assertEquals(MachineLimits.CONSOLE_INPUT_BYTES, tty.inputAvailable)
                assertEquals(KeyboardText.MAX_PASTE_BYTES, tty.pendingInputBytes)
                assertEquals(0L, tty.droppedInputBytes)
            }
        }
    }
}
