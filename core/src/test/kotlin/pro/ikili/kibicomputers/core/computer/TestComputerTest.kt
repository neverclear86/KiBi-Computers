package pro.ikili.kibicomputers.core.computer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.input.Key
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.kernel.BootConfig
import pro.ikili.kibicomputers.core.kernel.BootFailure
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineConfig
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.kernel.eventuallyDescribe
import pro.ikili.kibicomputers.core.machine.MachineManager
import java.time.Duration
import java.util.Collections
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** テスト用コンピューターの仮の結線（#307）: 同梱イメージ testboot からの起動と、[MachineConsole] */
class TestComputerTest {
    private val limit = Duration.ofSeconds(60)

    private fun lines(text: TextBuffer): List<String> = (0 until text.height).map { y ->
        val sb = StringBuilder()
        for (x in 0 until text.width) sb.appendCodePoint(text.getCodePoint(x, y))
        sb.toString().trimEnd()
    }

    /** ケースから、コンソールの出力を画面へ流す相手 */
    private class ToScreen(val console: MachineConsole) : MachineHost {
        val states: MutableList<MachineState> = Collections.synchronizedList(ArrayList())
        val failures: MutableList<BootFailure> = Collections.synchronizedList(ArrayList())

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) {
            states += state
        }

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) = console.write(bytes)

        override fun onBootFailed(machine: Machine, failure: BootFailure, detail: String) {
            failures += failure
        }
    }

    private fun MachineManager<String>.tickUntil(describe: () -> String, condition: () -> Boolean) =
        eventuallyDescribe(30_000, describe) {
            tick()
            condition()
        }

    @Test
    fun `testbootのinitが画面に出し、打った行を返し、exitで止まる`() = timeout(limit) {
        val manager = MachineManager<String>()
        try {
            val screen = TextBuffer(40, 10)
            val console = MachineConsole(screen, 2)
            val host = ToScreen(console)
            val case = ComputerCase<String>(CaseTier.BASIC, host)
            assertEquals(ComputerStatus.RUNNING, case.turnOn(manager, "pc", { true }, true, MachineConfig.DEFAULT))
            val machine = case.machine!!
            console.machine = machine
            assertTrue(machine.boot(BootConfig.builder().addBootDisk("missing").addBootDisk(BootConfig.TEST_BOOT_DISK).build()))

            manager.tickUntil({ "プロンプトが出ない: ${lines(screen)} ${host.failures}" }) { lines(screen).contains(">") }
            assertEquals("kibi: booting from disk ${BootConfig.TEST_BOOT_DISK}", lines(screen)[0])
            assertTrue(lines(screen).contains("KiBi test init"), "${lines(screen)}")

            // キーボードの入力: 文字と Enter。KeyUp は何も送らない
            assertEquals(5, console.keyboardInput(KeyboardEvent.Text("hello")))
            assertEquals(0, console.keyboardInput(KeyboardEvent.KeyUp(Key.ENTER, 0)))
            assertEquals(1, console.keyboardInput(KeyboardEvent.KeyDown(Key.ENTER, 0, false)))
            manager.tickUntil({ "返ってこない: ${lines(screen)}" }) { lines(screen).contains("echo: hello") }
            assertTrue(lines(screen).contains("> hello"), "TTY のエコー: ${lines(screen)}")

            // 次のプロンプトが出てから打つ（先に打つと、エコーがプロンプトより前に出て、行の並びが変わる）
            manager.tickUntil({ "次のプロンプトが出ない: ${lines(screen)}" }) { lines(screen).contains(">") }
            console.keyboardInput(KeyboardEvent.Paste("exit\n"))
            manager.tickUntil({ "止まらない: ${lines(screen)} ${host.states}" }) {
                machine.state == MachineState.STOPPED && lines(screen).any { it.endsWith("bye") }
            }
            assertTrue(host.failures.isEmpty(), "${host.failures}")

            // 画面を消して、つなぎを外す
            console.reset()
            assertTrue(lines(screen).all { it.isEmpty() })
            console.machine = null
            assertEquals(0, console.keyboardInput(KeyboardEvent.Text("x")))
            case.turnOff()
        } finally {
            assertTrue(manager.shutdown(10_000))
        }
    }

    @Test
    fun `知らないイメージや形の違う識別子では起動ディスクがない`() = timeout(limit) {
        val manager = MachineManager<String>()
        try {
            for (disk in listOf("image:no-such-image", "image:../x", "image:", "testboot")) {
                val failures = Collections.synchronizedList(ArrayList<BootFailure>())
                val machine = manager.attach(disk, object : MachineHost {
                    override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

                    override fun onBootFailed(machine: Machine, failure: BootFailure, detail: String) {
                        failures += failure
                    }
                }, MachineConfig.DEFAULT)
                assertTrue(machine.boot(BootConfig.builder().addBootDisk(disk).build()))
                manager.tickUntil({ "失敗が届かない: $disk" }) { failures.isNotEmpty() }
                assertEquals(listOf(BootFailure.NO_BOOT_DISK), failures.toList(), disk)
            }
        } finally {
            assertTrue(manager.shutdown(10_000))
        }
    }
}
