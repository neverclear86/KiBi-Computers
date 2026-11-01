package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriver
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.process.crashReason
import pro.ikili.kibicomputers.core.kernel.process.trapReason
import pro.ikili.kibicomputers.core.runtime.FakeModule
import pro.ikili.kibicomputers.core.runtime.WasmLinkException
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** トラップと例外のハンドリング（#41）: そのプロセスだけを CRASHED で終え、理由をコンソールに出す */
class CrashTest {
    private val limit = Duration.ofSeconds(30)

    private class ConsoleRecorder : MachineHost {
        val console = StringBuffer()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) {}

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            console.append(String(bytes, Charsets.UTF_8).replace("\r\n", "\n"))
        }
    }

    private class Env(val h: Harness, val m: Machine, val host: ConsoleRecorder) {
        fun tickUntil(message: String, condition: () -> Boolean) = eventually(10_000, message) {
            h.scheduler.tick()
            Thread.sleep(1)
            condition()
        }

        fun spawn(program: Program, driver: ProcessDriver, vararg args: String): Int =
            m.spawn(program, driver, SpawnOptions(args = args.toList()))

        /** 動き続けるプロセスを作り、進み具合を返す */
        fun spawnSpinner(): Pair<Int, AtomicInteger> {
            val progress = AtomicInteger()
            val pid = spawn(h.program(spinForever(progress)), runDriver(), "/bin/spin")
            tickUntil("spin が動かない") { progress.get() > 0 }
            return pid to progress
        }

        /** [pid] が CRASHED になり、[line] がコンソールに出るまで待つ */
        fun awaitCrash(pid: Int, line: String) {
            tickUntil("$line が出ない: ${host.console}") { host.console.contains("$line\n") }
            assertState(ProcessState.CRASHED, m, pid)
        }

        /** ほかのプロセスが進み続け、マシンが動いたまま */
        fun assertStillRunning(progress: AtomicInteger) {
            val before = progress.get()
            tickUntil("ほかのプロセスが止まった") { progress.get() > before + 2 }
            assertEquals(MachineState.RUNNING, m.state)
        }
    }

    private fun env(block: Env.() -> Unit) = timeout(limit) {
        Harness().use { h ->
            val host = ConsoleRecorder()
            val m = h.machine(fuelPerTick = 100, host = host)
            Env(h, m, host).block()
            m.stop()
            assertTrue(m.awaitTermination(5_000))
        }
    }

    private fun trapping(kind: WasmTrapKind): FakeModule = FakeModule.builder()
        .fuelMetered()
        .export("run", VOID) { ctx, _ -> ctx.trap(kind) }
        .build()

    @Test
    fun `トラップしたプロセスだけがCRASHEDになり理由がコンソールに出る`() = env {
        val (spinner, progress) = spawnSpinner()
        val bad = spawn(h.program(trapping(WasmTrapKind.UNREACHABLE)), runDriver(), "/bin/bad")
        awaitCrash(bad, "kibi: /bin/bad (pid $bad) crashed: unreachable executed")
        assertTrue(m.rt(bad).lastError is WasmTrapException)
        assertStillRunning(progress)
        assertTrue(!m.stateOf(spinner).isTerminal)
    }

    @Test
    fun `待機点型のプロセスのトラップもそのプロセスだけを終える`() = env {
        val (_, progress) = spawnSpinner()
        val bad = spawn(h.program(trapping(WasmTrapKind.MEMORY_OUT_OF_BOUNDS)), runDriver(ProcessKind.WAIT_POINT), "oob")
        awaitCrash(bad, "kibi: oob (pid $bad) crashed: out of bounds memory access")
        assertStillRunning(progress)
        // ワーカーは壊れず、次の待機点型のプロセスも動く
        val next = spawn(h.program(emptyModule()), TestDriver(ProcessKind.WAIT_POINT) { StepResult.Exit(0) }, "next")
        tickUntil("次のプロセスが終わらない") { m.stateOf(next) == ProcessState.EXITED }
    }

    @Test
    fun `ホストの関数のJVMの例外もそのプロセスだけを終える`() = env {
        val (_, progress) = spawnSpinner()
        val cases = listOf(
            { throw IllegalStateException("内部の失敗（プレイヤーには見せない）") } to "internal error (IllegalStateException)",
            { throw StackOverflowError() } to "call stack exhausted",
            { throw WasmLinkException("x") } to "cannot instantiate module",
        )
        for ((thrower, reason) in cases) {
            val pid = spawn(h.program(hostCallModule()), runDriver { ctx, b -> b.host(ctx) { _, _, _ -> thrower() } }, "/bin/host")
            awaitCrash(pid, "kibi: /bin/host (pid $pid) crashed: $reason")
            assertTrue(!host.console.contains("プレイヤーには見せない"), "例外のメッセージは出さない")
        }
        assertStillRunning(progress)
    }

    @Test
    fun `名前の制御文字は出さず引数がなければpidだけ`() = env {
        spawnSpinner()
        val escape = spawn(h.program(trapping(WasmTrapKind.UNREACHABLE)), runDriver(), "a\u001b[2Jb")
        awaitCrash(escape, "kibi: a?[2Jb (pid $escape) crashed: unreachable executed")
        val long = spawn(h.program(trapping(WasmTrapKind.UNREACHABLE)), runDriver(), "x".repeat(100))
        awaitCrash(long, "kibi: ${"x".repeat(64)}... (pid $long) crashed: unreachable executed")
        val noArgs = spawn(h.program(trapping(WasmTrapKind.INTEGER_DIVIDE_BY_ZERO)), runDriver())
        awaitCrash(noArgs, "kibi: pid $noArgs crashed: integer divide by zero")
    }

    @Test
    fun `自分から終わったプロセスと止められたプロセスは何も出さない`() = env {
        val (spinner, _) = spawnSpinner()
        val ok = spawn(h.program(emptyModule()), TestDriver(ProcessKind.BLOCKING) { StepResult.Exit(3) }, "ok")
        tickUntil("ok が終わらない") { m.stateOf(ok) == ProcessState.EXITED }
        repeat(5) {
            h.scheduler.tick()
            Thread.sleep(1)
        }
        m.stop()
        assertTrue(m.awaitTermination(5_000))
        h.scheduler.tick()
        assertState(ProcessState.KILLED, m, spinner)
        assertEquals("", host.console.toString())
    }

    @Test
    fun `トラップの種類ごとに別の理由になる`() {
        val reasons = WasmTrapKind.entries.map(::trapReason)
        assertEquals(reasons.size, reasons.toSet().size, "$reasons")
        assertTrue(reasons.all { it.isNotBlank() && it.all { c -> c.code in 0x20..0x7e } }, "ASCII の短い英語: $reasons")
        for (kind in WasmTrapKind.entries) assertEquals(trapReason(kind), crashReason(WasmTrapException(kind, "message")))
    }
}
