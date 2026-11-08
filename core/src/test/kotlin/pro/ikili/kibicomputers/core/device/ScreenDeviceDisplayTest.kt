package pro.ikili.kibicomputers.core.device

import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.ComputerCase
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.display.DisplayDevice
import pro.ikili.kibicomputers.core.display.ScreenTier
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.kernel.BootConfig
import pro.ikili.kibicomputers.core.kernel.ConsoleSink
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineConfig
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.kernel.eventuallyDescribe
import pro.ikili.kibicomputers.core.machine.MachineManager
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.Collections
import java.util.concurrent.locks.ReentrantLock
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 表示デバイス（#355）: プロセスの出力は、プロセスのスレッドで表示デバイスに書く */
class ScreenDeviceDisplayTest {
    private val limit = Duration.ofSeconds(90)
    private val testBootDisk = BootConfig.IMAGE_DISK_PREFIX + "testboot"

    private fun lines(display: DisplayDevice): List<String> {
        display.lock.lock()
        try {
            val text = display.text
            return (0 until text.height).map { y ->
                val sb = StringBuilder()
                for (x in 0 until text.width) sb.appendCodePoint(text.getCodePoint(x, y))
                sb.toString().trimEnd()
            }
        } finally {
            display.lock.unlock()
        }
    }

    /** 接続層に届いたコンソールの出力（画面のデバイスがないときの行き先）を記録する */
    private class Recorder : MachineHost {
        val delivered = ByteArrayOutputStream()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            delivered.write(bytes)
        }
    }

    private val keyboard = KeyboardDevice()

    private fun boot(console: ScreenDevice, block: (MachineManager<String>, Machine, Recorder) -> Unit) {
        val manager = MachineManager<String>()
        try {
            val host = Recorder()
            val case = ComputerCase<String>(CaseTier.BASIC, host)
            assertEquals(ComputerStatus.RUNNING, case.turnOn(manager, "pc", { true }, true, MachineConfig.DEFAULT))
            val machine = case.machine!!
            machine.attachConsole(console, keyboard)
            assertTrue(machine.boot(BootConfig.builder().addBootDisk(testBootDisk).build()))
            block(manager, machine, host)
            case.turnOff()
        } finally {
            assertTrue(manager.shutdown(10_000))
        }
    }

    private fun MachineManager<String>.tickUntil(describe: () -> String, condition: () -> Boolean) =
        eventuallyDescribe(60_000, describe) {
            tick()
            condition()
        }

    @Test
    fun `プロセスの出力は、メインスレッドを通らずに表示デバイスに書かれる`() = timeout(limit) {
        val display = DisplayDevice.forTier(ScreenTier.COLOR)
        val console = ScreenDevice(display)
        boot(console) { manager, machine, host ->
            assertSame(display.consoleSink, machine.consoleSink, "つなぐと書く先になる")
            manager.tickUntil({ "プロンプトが出ない: ${lines(display)}" }) { lines(display).lastOrNull { it.isNotEmpty() } == ">" }
            host.delivered.reset()
            keyboard.input(KeyboardEvent.Paste("for i=1,300 do print('n'..i) end\n"))
            manager.tickUntil({ "最後まで出ない: ${lines(display)}" }) {
                val shown = lines(display).filter { it.isNotEmpty() }
                shown.lastOrNull() == ">" && shown.contains("n300")
            }
            val shown = lines(display).filter { it.isNotEmpty() }
            assertEquals((290..300).map { "n$it" } + ">", shown.takeLast(12), "$shown")
            // 画面のデバイスをつないでいる間は、接続層（onConsoleOutput）には何も来ない（#65。メインスレッドの分も画面のデバイスに書く）
            val delivered = host.delivered.toString(Charsets.UTF_8)
            assertEquals("", delivered, "接続層に届いた出力")
        }
    }

    @Test
    fun `書く先にはプロセスのスレッドから書く`() = timeout(limit) {
        // 書いたスレッドと中身
        val writes = Collections.synchronizedList(ArrayList<Pair<Thread, String>>())
        val written = ByteArrayOutputStream()
        val sink = object : ConsoleSink {
            override val lock = ReentrantLock()

            override fun write(bytes: ByteArray): Long {
                assertTrue(lock.isHeldByCurrentThread, "ロックを持って呼ばれる")
                writes += Thread.currentThread() to bytes.toString(Charsets.UTF_8)
                synchronized(written) { written.write(bytes) }
                return bytes.size.toLong()
            }
        }
        val console = ScreenDevice(TextBuffer(80, 25), 2)
        boot(console) { manager, machine, _ ->
            manager.tickUntil({ "プロンプトが出ない" }) { lines(console.display).lastOrNull { it.isNotEmpty() } == ">" }
            // マシンの書く先を、記録するものに替える（キー入力は KeyboardDevice から入れる）
            machine.consoleSink = sink
            keyboard.input(KeyboardEvent.Paste("for i=1,100 do print('m'..i) end\n"))
            manager.tickUntil({ "出力が出ない: ${synchronized(written) { written.toString(Charsets.UTF_8) }}" }) {
                synchronized(written) { written.toString(Charsets.UTF_8).contains("m100") }
            }
            // プログラムの出力は、tick を回すスレッドでは書かない（キー入力のエコーは、入力したスレッドで書く）
            val fromTick = synchronized(writes) { writes.filter { it.first === Thread.currentThread() && it.second.contains("m5") } }
            assertTrue(fromTick.isEmpty(), "tick を回すスレッドで書いた出力: $fromTick")
        }
    }
}
