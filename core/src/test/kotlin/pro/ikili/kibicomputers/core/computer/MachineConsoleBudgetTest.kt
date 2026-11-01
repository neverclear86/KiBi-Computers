package pro.ikili.kibicomputers.core.computer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.kernel.BootConfig
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineConfig
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.kernel.eventuallyDescribe
import pro.ikili.kibicomputers.core.machine.MachineManager
import java.time.Duration
import java.util.concurrent.locks.LockSupport
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 1台・1 tick の端末の処理量の上限（#357）。同梱イメージ testboot（Lua の REPL）で、実際の出力を流す */
class MachineConsoleBudgetTest {
    private val limit = Duration.ofSeconds(90)
    private val testBootDisk = BootConfig.IMAGE_DISK_PREFIX + "testboot"

    private fun lines(text: TextBuffer): List<String> = (0 until text.height).map { y ->
        val sb = StringBuilder()
        for (x in 0 until text.width) sb.appendCodePoint(text.getCodePoint(x, y))
        sb.toString().trimEnd()
    }

    private class Booted(val manager: MachineManager<String>, val case: ComputerCase<String>, val machine: Machine, val console: MachineConsole)

    private fun boot(screen: TextBuffer, cellsPerTick: Long, block: (Booted) -> Unit) {
        val manager = MachineManager<String>()
        try {
            val console = MachineConsole(screen, 2)
            console.cellsPerTick = cellsPerTick
            val host = object : MachineHost {
                override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit
                override fun onConsoleOutput(machine: Machine, bytes: ByteArray) = console.write(bytes)
            }
            val case = ComputerCase<String>(CaseTier.BASIC, host)
            assertEquals(ComputerStatus.RUNNING, case.turnOn(manager, "pc", { true }, true, MachineConfig.DEFAULT))
            val machine = case.machine!!
            console.machine = machine
            assertTrue(machine.boot(BootConfig.builder().addBootDisk(testBootDisk).build()))
            val booted = Booted(manager, case, machine, console)
            booted.tickUntil({ "プロンプトが出ない: ${lines(screen)}" }) { lines(screen).lastOrNull { it.isNotEmpty() } == ">" }
            block(booted)
            case.turnOff()
        } finally {
            assertTrue(manager.shutdown(10_000))
        }
    }

    private fun Booted.tickUntil(describe: () -> String, condition: () -> Boolean) =
        eventuallyDescribe(60_000, describe) {
            tick()
            condition()
        }

    // 1 tick 進め、使ったセルの数が上限（と1回の操作の分）に収まることを確かめる
    private fun Booted.tick() {
        manager.tick()
        val slack = console.screen.width.toLong() * console.screen.height + console.screen.width
        assertTrue(console.cellsUsedThisTick <= console.cellsPerTick + slack, "cells ${console.cellsUsedThisTick}")
    }

    @Test
    fun `上限を超えた出力は次の tick に回し、なくさずに書いた順に出す`() = timeout(limit) {
        val screen = TextBuffer(40, 10)
        // 1行ごとに画面全体（400 セル）を写すので、1 tick に数行しか進まない
        boot(screen, 2_000) { b ->
            b.console.keyboardInput(KeyboardEvent.Paste("for i=1,3000 do print(i) end\n"))
            var held = false
            b.tickUntil({ "最後まで出ない: ${lines(screen)}" }) {
                held = held || b.machine.consoleOutputHeld
                // 取っておく量は、TTY の出力の上限とカーネルのメッセージの上限で抑えられる
                assertTrue(b.console.backlogBytes <= 128 * 1024, "backlog ${b.console.backlogBytes}")
                lines(screen).lastOrNull { it.isNotEmpty() } == ">"
            }
            assertTrue(held, "出力を止めたことがある")
            val shown = lines(screen).filter { it.isNotEmpty() }
            // 最後の数行が、抜けずに順に並んでいる
            assertEquals((2992..3000).map { it.toString() } + ">", shown.takeLast(10), "$shown")
            assertEquals(0, b.console.backlogBytes)
            assertTrue(!b.machine.consoleOutputHeld)
        }
    }

    @Test
    fun `1行送りの連打はまとめて写すので、上限に届かない`() = timeout(limit) {
        flood("\\27[S", expectHeld = false)
    }

    @Test
    fun `まとめられない1行送りの連打でも1 tick の処理は上限で止まり、書き手は待たされる`() = timeout(limit) {
        // 1行送りの間に文字を挟むと、まとめられず、1回ごとに画面全体を写す
        flood("\\27[Sx", expectHeld = true)
    }

    private fun flood(unit: String, expectHeld: Boolean) {
        val screen = TextBuffer(160, 50)
        boot(screen, MachineConsole.DEFAULT_CELLS_PER_TICK) { b ->
            b.console.keyboardInput(KeyboardEvent.Paste("while true do io.write((\"$unit\"):rep(5000)) end\n"))
            // 打った行が流れて消えるまで待つ（Lua が動きだした）
            b.tickUntil({ "出力が出ない: ${lines(screen)}" }) { lines(screen).none { it.startsWith("> while") } }
            var held = 0
            var totalNanos = 0L
            repeat(300) { i ->
                val start = System.nanoTime()
                b.tick()
                if (i >= 50) totalNanos += System.nanoTime() - start
                // 実際の tick の間隔のように、書き手が TTY を埋める時間をおく
                LockSupport.parkNanos(5_000_000L)
                if (b.machine.consoleOutputHeld) held++
                assertTrue(b.console.backlogBytes <= 128 * 1024, "backlog ${b.console.backlogBytes}")
            }
            assertEquals(expectHeld, held > 0, "出力が上限を超えて止めたか: $held")
            assertEquals(MachineState.RUNNING, b.machine.state)
            // 時間は機械に左右されるので、ゆるく見る（上限がなければ1 tick に数千回、画面全体を写す）
            val avgMillis = totalNanos / 250 / 1_000_000.0
            assertTrue(avgMillis < 20.0, "avg tick $avgMillis ms")
        }
    }
}
