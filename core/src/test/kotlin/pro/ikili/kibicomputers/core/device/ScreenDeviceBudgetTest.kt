package pro.ikili.kibicomputers.core.device

import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.ComputerCase
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 1台・1 tick の、メインスレッドでの端末の処理量（#357・#355）。同梱イメージ testboot（Lua の REPL）で、実際の出力を流す */
class ScreenDeviceBudgetTest {
    private val limit = Duration.ofSeconds(90)
    private val testBootDisk = BootConfig.IMAGE_DISK_PREFIX + "testboot"

    private fun lines(text: TextBuffer): List<String> = (0 until text.height).map { y ->
        val sb = StringBuilder()
        for (x in 0 until text.width) sb.appendCodePoint(text.getCodePoint(x, y))
        sb.toString().trimEnd()
    }

    private class Booted(
        val manager: MachineManager<String>,
        val case: ComputerCase<String>,
        val machine: Machine,
        val console: ScreenDevice,
        val keyboard: KeyboardDevice,
    )

    private fun boot(screen: TextBuffer, cellsPerTick: Long, block: (Booted) -> Unit) {
        val manager = MachineManager<String>()
        try {
            val console = ScreenDevice(screen, 2)
            console.cellsPerTick = cellsPerTick
            val keyboard = KeyboardDevice()
            val case = ComputerCase<String>(CaseTier.BASIC, MachineHost { _, _, _ -> })
            assertEquals(ComputerStatus.RUNNING, case.turnOn(manager, "pc", { true }, true, MachineConfig.DEFAULT))
            val machine = case.machine!!
            machine.attachConsole(console, keyboard)
            assertTrue(machine.boot(BootConfig.builder().addBootDisk(testBootDisk).build()))
            val booted = Booted(manager, case, machine, console, keyboard)
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
    fun `プロセスの出力はプロセスのスレッドで書くので、メインスレッドの上限で止めず、なくさずに書いた順に出す`() = timeout(limit) {
        val screen = TextBuffer(40, 10)
        // 1行ごとに画面全体（400 セル）を写す。メインスレッドで書くなら、1 tick に数行しか進まない上限
        boot(screen, 2_000) { b ->
            b.keyboard.input(KeyboardEvent.Paste("for i=1,3000 do print(i) end\n"))
            var held = false
            b.tickUntil({ "最後まで出ない: ${lines(screen)}" }) {
                held = held || b.machine.consoleOutputHeld
                b.console.display.lock.lock()
                try {
                    lines(screen).lastOrNull { it.isNotEmpty() } == ">"
                } finally {
                    b.console.display.lock.unlock()
                }
            }
            assertFalse(held, "メインスレッドで出力を止めない（#355）")
            val shown = lines(screen).filter { it.isNotEmpty() }
            // 最後の数行が、抜けずに順に並んでいる
            assertEquals((2992..3000).map { it.toString() } + ">", shown.takeLast(10), "$shown")
            assertEquals(0, b.console.backlogBytes)
        }
    }

    @Test
    fun `1行送りの連打はプロセスのスレッドで書き、メインスレッドの tick は重くならない`() = timeout(limit) {
        flood("\\27[S")
    }

    @Test
    fun `まとめられない1行送りの連打でも、メインスレッドの tick は重くならない`() = timeout(limit) {
        // 1行送りの間に文字を挟むと、まとめられず、1回ごとに画面全体を写す。その重さはプロセスが燃料で払う（#355）
        flood("\\27[Sx")
    }

    @Test
    fun `TTY に書いた後で書き手のスレッドが遅れても、メインスレッドはその出力を取り出さない`() = timeout(limit) {
        // まとめられない1行送りで、TTY の出力をメインスレッドが取り出せば、1 tick の上限を超えて止める（#419）
        val screen = TextBuffer(160, 50)
        boot(screen, ScreenDevice.DEFAULT_CELLS_PER_TICK) { b ->
            val armed = AtomicBoolean(false)
            val reached = CountDownLatch(1)
            val go = CountDownLatch(1)
            // TTY に書いた後・表示デバイスに書く前で、書き手を1回だけ止める（書き手のスレッドが OS に後回しにされたのと同じ）
            b.machine.scheduler.hooks.beforeConsoleFlush = { _, _ ->
                if (armed.compareAndSet(true, false)) {
                    reached.countDown()
                    go.await(30, TimeUnit.SECONDS)
                }
            }
            b.keyboard.input(KeyboardEvent.Paste("while true do io.write((\"\\27[Sx\"):rep(5000)) end\n"))
            b.tickUntil({ "出力が出ない: ${lines(screen)}" }) { lines(screen).none { it.startsWith("> while") } }
            armed.set(true)
            b.tickUntil({ "書き手が止まらない" }) { reached.count == 0L }
            try {
                // 書き手が止まっている間に何 tick 進めても、TTY に残った出力を取り出さない（取り出せば止める）
                repeat(10) {
                    b.tick()
                    assertFalse(b.machine.consoleOutputHeld, "書き手のいる出力をメインスレッドが取り出した（#419）")
                    assertEquals(0, b.console.backlogBytes)
                }
            } finally {
                go.countDown()
            }
            repeat(50) {
                b.tick()
                LockSupport.parkNanos(5_000_000L)
                assertFalse(b.machine.consoleOutputHeld, "書き手を再開した後にメインスレッドで出力を止めた（#419）")
            }
            assertEquals(MachineState.RUNNING, b.machine.state)
        }
    }

    @Test
    fun `書き手が表示デバイスに書いている最中でも、メインスレッドの tick はそのロックを待たない`() = timeout(limit) {
        // 出力し続ける台ごとに、メインスレッドが表示デバイスのロックを待つと、その台数に比例して tick が重くなる（#351）
        val screen = TextBuffer(40, 10)
        boot(screen, ScreenDevice.DEFAULT_CELLS_PER_TICK) { b ->
            b.keyboard.input(KeyboardEvent.Paste("local i = 0 while true do i = i + 1 print(i) end\n"))
            b.tickUntil({ "出力が出ない: ${lines(screen)}" }) { lines(screen).none { it.startsWith("> local") } }
            // 書き手が表示デバイスに書いている最中（ロックを持ったまま）を、別のスレッドでロックを持って作る
            val holding = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = Thread {
                b.console.display.lock.lock()
                try {
                    holding.countDown()
                    release.await(30, TimeUnit.SECONDS)
                } finally {
                    b.console.display.lock.unlock()
                }
            }
            holder.start()
            try {
                assertTrue(holding.await(10, TimeUnit.SECONDS))
                // その間も、Lua は TTY に書き、TTY の印を積む（表示デバイスに書けずに待つ）
                repeat(20) {
                    val start = System.nanoTime()
                    b.manager.tick()
                    val millis = (System.nanoTime() - start) / 1_000_000
                    assertTrue(millis < 2_000, "tick waited $millis ms for the display device lock held by a writer")
                    LockSupport.parkNanos(5_000_000L)
                }
            } finally {
                release.countDown()
                holder.join()
            }
            // ロックが空けば、書き手が続きを書き、出力は止まらない
            val before = lines(screen)
            b.tickUntil({ "出力が続かない: ${lines(screen)}" }) { lines(screen) != before }
            assertFalse(b.machine.consoleOutputHeld)
            assertEquals(MachineState.RUNNING, b.machine.state)
        }
    }

    private fun flood(unit: String) {
        val screen = TextBuffer(160, 50)
        boot(screen, ScreenDevice.DEFAULT_CELLS_PER_TICK) { b ->
            b.keyboard.input(KeyboardEvent.Paste("while true do io.write((\"$unit\"):rep(5000)) end\n"))
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
            // 書き手のスレッドが遅れても（混んだ環境）、書くと予約した出力はメインスレッドが取り出さないので、一度も止めない（#419）
            assertEquals(0, held, "メインスレッドで出力を止めた tick（#355）")
            assertEquals(MachineState.RUNNING, b.machine.state)
            // 時間は機械に左右されるので、ゆるく見る（上限がなければ1 tick に数千回、画面全体を写す）
            val avgMillis = totalNanos / 250 / 1_000_000.0
            assertTrue(avgMillis < 20.0, "avg tick $avgMillis ms")
        }
    }
}
