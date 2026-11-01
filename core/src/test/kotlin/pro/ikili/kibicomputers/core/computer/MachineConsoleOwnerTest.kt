package pro.ikili.kibicomputers.core.computer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineConfig
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.kernel.eventuallyDescribe
import pro.ikili.kibicomputers.core.machine.MachineManager
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 出力を止めている間に、コンソールの持ち主（マシン）が替わるときと、止めている間の順序（#381）。
 * マシンは起動しない（コンソールの出力は、テストから直接書くか、カーネルのメッセージと TTY のエコーで出す）
 */
class MachineConsoleOwnerTest {
    private val limit = Duration.ofSeconds(60)

    private fun lines(text: TextBuffer): List<String> = (0 until text.height).map { y ->
        val sb = StringBuilder()
        for (x in 0 until text.width) sb.appendCodePoint(text.getCodePoint(x, y))
        sb.toString().trimEnd()
    }

    private fun count(text: TextBuffer, ch: Char): Int = lines(text).sumOf { line -> line.count { it == ch } }

    /** コンソールの出力を [console] に流す相手。[console] は後から入れる */
    private class ToConsole : MachineHost {
        var console: MachineConsole? = null

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            if (console?.machine === machine) console?.write(bytes)
        }
    }

    private class Env(val manager: MachineManager<String>, val host: ToConsole) {
        fun turnOn(id: String): ComputerCase<String> {
            val case = ComputerCase<String>(CaseTier.BASIC, host)
            assertEquals(ComputerStatus.RUNNING, case.turnOn(manager, id, { true }, true, MachineConfig.DEFAULT))
            return case
        }
    }

    private fun env(block: (Env) -> Unit) {
        val manager = MachineManager<String>()
        try {
            block(Env(manager, ToConsole()))
        } finally {
            assertTrue(manager.shutdown(10_000))
        }
    }

    private fun MachineManager<String>.tickUntil(describe: () -> String, condition: () -> Boolean) =
        eventuallyDescribe(30_000, describe) {
            tick()
            condition()
        }

    // 上限を超える量を直接書いて、取っておく分を作る（1文字 = 1セル）
    private fun fill(console: MachineConsole, n: Int) {
        console.write(ByteArray(n) { 'x'.code.toByte() })
        assertTrue(console.backlogBytes > 0, "取っておく分がある")
    }

    @Test
    fun `マシンを外すと、その tick の上限まで書いて、残りは捨てる`() = timeout(limit) {
        env { e ->
            val case = e.turnOn("pc")
            val machine = case.machine!!
            val screen = TextBuffer(80, 25)
            val console = MachineConsole(screen, 2)
            console.cellsPerTick = 100
            console.machine = machine
            fill(console, 1500)
            assertTrue(machine.consoleOutputHeld)
            val shown = count(screen, 'x')
            assertTrue(shown <= 100, "最初の tick は上限まで: $shown")

            // 次の tick に入ってから外すと、その tick の上限の分だけ書き、残りは捨てる
            e.manager.tick()
            val afterTick = count(screen, 'x')
            assertTrue(afterTick in shown + 1..shown + 100, "次の tick も上限まで: $afterTick")
            console.machine = null
            assertEquals(0, console.backlogBytes)
            assertFalse(machine.consoleOutputHeld, "止めるのをやめる")
            assertEquals(afterTick, count(screen, 'x'), "使いきった tick には、もう書かない")
            assertEquals(1500L - afterTick, console.droppedBytes)
            case.turnOff()
        }
    }

    @Test
    fun `画面が外れたときは、取っておいた分を書かずに捨てる`() = timeout(limit) {
        env { e ->
            val case = e.turnOn("pc")
            val machine = case.machine!!
            val screen = TextBuffer(80, 25)
            val console = MachineConsole(screen, 2)
            console.cellsPerTick = 100
            console.machine = machine
            fill(console, 1500)
            val shown = count(screen, 'x')
            console.discardOutput()
            assertEquals(0, console.backlogBytes)
            assertFalse(machine.consoleOutputHeld)
            assertEquals(1500L - shown, console.droppedBytes)
            e.manager.tick()
            console.machine = null
            assertEquals(shown, count(screen, 'x'), "捨てた分は書かない")
            case.turnOff()
        }
    }

    @Test
    fun `別のマシンに替えると、止めるのを替えた先に移し、続きを順に書く`() = timeout(limit) {
        env { e ->
            val caseA = e.turnOn("a")
            val caseB = e.turnOn("b")
            val a = caseA.machine!!
            val b = caseB.machine!!
            val screen = TextBuffer(20, 5)
            val console = MachineConsole(screen, 2)
            // 1行送りは画面全体（100 セル）を写すので、1 tick に数行しか進まない
            console.cellsPerTick = 300
            console.machine = a
            console.write((1..60).joinToString("") { "$it\r\n" }.toByteArray())
            assertTrue(a.consoleOutputHeld)

            console.machine = b
            assertFalse(a.consoleOutputHeld, "前のマシンは止めない")
            assertTrue(b.consoleOutputHeld, "替えた先のマシンを止める")
            assertTrue(console.backlogBytes > 0, "取っておいた分は捨てない")
            e.manager.tickUntil({ "書ききらない: ${console.backlogBytes}" }) { console.backlogBytes == 0 }
            assertFalse(b.consoleOutputHeld)
            assertEquals((57..60).map { it.toString() } + "", lines(screen))
            caseA.turnOff()
            caseB.turnOff()
        }
    }

    @Test
    fun `reset すると、取っておいた分を捨てて止めるのをやめる`() = timeout(limit) {
        env { e ->
            val case = e.turnOn("pc")
            val machine = case.machine!!
            val screen = TextBuffer(80, 25)
            val console = MachineConsole(screen, 2)
            console.cellsPerTick = 100
            console.machine = machine
            fill(console, 1500)
            console.reset()
            assertEquals(0, console.backlogBytes)
            assertFalse(machine.consoleOutputHeld)
            assertTrue(lines(screen).all { it.isEmpty() })
            // そのあとの出力は、初めから書く（この tick の上限は使いきっているので、次の tick から）
            console.write("ok".toByteArray())
            e.manager.tickUntil({ "${lines(screen)}" }) { lines(screen)[0] == "ok" }
            case.turnOff()
        }
    }

    @Test
    fun `電源を入れ直すと、前のマシンの取っておいた分は新しいマシンに持ち越さない`() = timeout(limit) {
        env { e ->
            val case = e.turnOn("pc")
            val old = case.machine!!
            val screen = TextBuffer(80, 25)
            val console = MachineConsole(screen, 2)
            e.host.console = console
            console.cellsPerTick = 100
            console.machine = old
            fill(console, 1500)

            // 結線と同じ順: 外す → 電源を切る → 入れる → 画面を消してつなぐ
            console.machine = null
            case.turnOff()
            assertEquals(ComputerStatus.RUNNING, case.turnOn(e.manager, "pc", { true }, true, MachineConfig.DEFAULT))
            val fresh = case.machine!!
            console.reset()
            console.machine = fresh
            assertFalse(old.consoleOutputHeld)
            assertFalse(fresh.consoleOutputHeld)
            assertEquals(0, console.backlogBytes)
            e.manager.tick()
            assertEquals(0, count(screen, 'x'), "前のマシンの出力は書かない")
            fresh.writeConsoleLine("again")
            e.manager.tickUntil({ "${lines(screen)}" }) { lines(screen)[0] == "again" }
            case.turnOff()
        }
    }

    @Test
    fun `止めている間にカーネルのメッセージと TTY の出力が混ざっても、書いた順に出る`() = timeout(limit) {
        env { e ->
            val case = e.turnOn("pc")
            val machine = case.machine!!
            val screen = TextBuffer(40, 10)
            val console = MachineConsole(screen, 2)
            e.host.console = console
            console.cellsPerTick = 40
            console.machine = machine
            fill(console, 600)
            assertTrue(machine.consoleOutputHeld)

            // カーネルのメッセージ（writeConsole）と、TTY の出力（キー入力のエコー）を交互に書く
            machine.writeConsoleLine("K1")
            e.manager.tick()
            machine.offerConsoleInput("E1\n".toByteArray())
            e.manager.tick()
            machine.writeConsoleLine("K2")
            e.manager.tick()
            machine.offerConsoleInput("E2\n".toByteArray())
            machine.writeConsoleLine("K3")
            machine.offerConsoleInput("E3\n".toByteArray())
            assertTrue(machine.consoleOutputHeld, "まだ止めている")

            e.manager.tickUntil({ "書ききらない: ${console.backlogBytes} ${lines(screen)}" }) {
                console.backlogBytes == 0 && !machine.consoleOutputHeld && lines(screen).any { it == "E3" }
            }
            val shown = lines(screen).filter { it.isNotEmpty() && !it.all { c -> c == 'x' } }
            assertEquals(listOf("K1", "E1", "K2", "E2", "K3", "E3"), shown, "${lines(screen)}")
            case.turnOff()
        }
    }
}
