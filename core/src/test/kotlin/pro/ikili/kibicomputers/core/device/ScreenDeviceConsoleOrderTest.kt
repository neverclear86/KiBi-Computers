package pro.ikili.kibicomputers.core.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.ComputerCase
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineConfig
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.kernel.eventuallyDescribe
import pro.ikili.kibicomputers.core.machine.MachineManager
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * TTY の出力の後に書いたカーネルのメッセージが、TTY の出力より後に出ること（#435、#381）。
 * TTY の印を、書く先のロックが取れずに飛ばしたときや、印とメッセージの間にほかの通知が挟まったときも同じ。
 * マシンは起動しない（TTY の出力はキー入力のエコー、カーネルのメッセージは writeConsoleLine で出す）
 */
class ScreenDeviceConsoleOrderTest {
    private val limit = Duration.ofSeconds(60)

    private fun lines(text: TextBuffer): List<String> = (0 until text.height).map { y ->
        val sb = StringBuilder()
        for (x in 0 until text.width) sb.appendCodePoint(text.getCodePoint(x, y))
        sb.toString().trimEnd()
    }

    private fun shown(text: TextBuffer): List<String> = lines(text).filter { it.isNotEmpty() }

    private class Env(val manager: MachineManager<String>, val machine: Machine, val screen: TextBuffer, val console: ScreenDevice)

    private fun env(block: (Env) -> Unit) {
        val manager = MachineManager<String>()
        try {
            val case = ComputerCase<String>(CaseTier.BASIC, MachineHost { _, _, _ -> })
            assertEquals(ComputerStatus.RUNNING, case.turnOn(manager, "pc", { true }, true, MachineConfig.DEFAULT))
            val machine = case.machine!!
            val screen = TextBuffer(40, 10)
            val console = ScreenDevice(screen, 2)
            machine.devices.attach(SCREEN_ADDRESS, console)
            manager.tick()
            block(Env(manager, machine, screen, console))
            case.turnOff()
        } finally {
            assertTrue(manager.shutdown(10_000))
        }
    }

    // 残った TTY の出力を見直す分（次の tick の印、取り出さずに見送る1回）まで回す
    private fun Env.settle() = repeat(5) { manager.tick() }

    /**
     * 別のスレッドで表示デバイスのロックを持つ（書き手が書いている最中を作る）。[releaseWhen] が true になったら放す。
     * 失敗したときも必ず放す
     */
    private fun holdDisplay(console: ScreenDevice, releaseWhen: () -> Boolean): Thread {
        val holding = CountDownLatch(1)
        val lock = console.display.lock
        val holder = Thread {
            lock.lock()
            try {
                holding.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                while (!releaseWhen() && System.nanoTime() < deadline) Thread.onSpinWait()
            } finally {
                lock.unlock()
            }
        }
        holder.isDaemon = true
        holder.start()
        assertTrue(holding.await(10, TimeUnit.SECONDS), "ロックを持てない")
        return holder
    }

    @Test
    fun `TTY の印を飛ばした直後のカーネルのメッセージは、TTY の出力の後に出る`() = timeout(limit) {
        env { e ->
            val lock = e.console.display.lock
            // メインスレッドが、カーネルのメッセージを配るために表示デバイスのロックを待ち始めたら放す
            val holder = holdDisplay(e.console) { lock.hasQueuedThreads() }
            try {
                e.machine.offerConsoleInput("E1\n".toByteArray())
                e.machine.writeConsoleLine("K1")
                e.manager.tick()
            } finally {
                holder.join(10_000)
            }
            e.settle()
            assertEquals(listOf("E1", "K1"), shown(e.screen))
        }
    }

    @Test
    fun `TTY の印を飛ばした後にほかの通知が挟まっても、カーネルのメッセージは TTY の出力の後に出る`() = timeout(limit) {
        env { e ->
            val release = CountDownLatch(1)
            val holder = holdDisplay(e.console) { release.count == 0L }
            try {
                e.machine.offerConsoleInput("E1\n".toByteArray())
                // 通知の中（メインスレッド）でロックを空けるので、「印を飛ばす → ほかの通知 → カーネルのメッセージ」の順が決まる
                e.machine.postHostCallback {
                    release.countDown()
                    holder.join(10_000)
                }
                e.machine.writeConsoleLine("K1")
                e.manager.tick()
            } finally {
                release.countDown()
                holder.join(10_000)
            }
            e.settle()
            assertEquals(listOf("E1", "K1"), shown(e.screen))
        }
    }

    @Test
    fun `TTY の印とカーネルのメッセージの間にほかの通知が挟まっても、書いた順に出る`() = timeout(limit) {
        env { e ->
            e.machine.offerConsoleInput("E1\n".toByteArray())
            e.machine.postHostCallback {}
            e.machine.writeConsoleLine("K1")
            e.manager.tick()
            e.settle()
            assertEquals(listOf("E1", "K1"), shown(e.screen))
        }
    }

    @Test
    fun `止めている間にほかの通知が挟まっても、再開すると書いた順に出る`() = timeout(limit) {
        env { e ->
            e.console.cellsPerTick = 40
            e.console.write(ByteArray(600) { 'x'.code.toByte() })
            assertTrue(e.machine.consoleOutputHeld)
            e.machine.offerConsoleInput("E1\n".toByteArray())
            e.machine.postHostCallback {}
            e.machine.writeConsoleLine("K1")
            e.machine.offerConsoleInput("E2\n".toByteArray())
            eventuallyDescribe(30_000, { "書ききらない: ${lines(e.screen)}" }) {
                e.manager.tick()
                e.console.backlogBytes == 0 && !e.machine.consoleOutputHeld && lines(e.screen).any { it == "E2" }
            }
            e.settle()
            val rest = shown(e.screen).filter { !it.all { c -> c == 'x' } }
            assertEquals(listOf("E1", "K1", "E2"), rest, "${lines(e.screen)}")
        }
    }
}
