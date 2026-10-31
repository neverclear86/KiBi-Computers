package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 実際の Endive で fib.wasm（燃料を計測しない。#38 の挿入がまだないため）を動かす。燃料のテストは FakeWasmRuntime で行う。 */
class RealWasmSchedulerTest {
    private val fib: Program by lazy {
        val bytes = javaClass.getResourceAsStream("/kibicomputers/wasm/fib.wasm")?.use { it.readBytes() }
        assertNotNull(bytes, "fib.wasm がリソースにない")
        Program.load(EndiveRuntime(), bytes!!)
    }

    private fun fibDriver(n: Int, result: AtomicLong) = TestDriver(ProcessKind.BLOCKING) { ctx ->
        result.set(ctx.instance.call("fib", n.toLong())[0])
        StepResult.Exit((result.get() and 0x7f).toInt())
    }

    @Test
    fun `fib(30)がプロセスのスレッドで最後まで進む`() = timeout(Duration.ofSeconds(30)) {
        Harness().use { h ->
            val m = h.machine()
            val result = AtomicLong()
            val pid = m.spawnTest(fib, fibDriver(30, result), unmetered = true)
            eventually(10_000) { m.stateOf(pid).isTerminal }
            assertState(ProcessState.EXITED, m, pid)
            assertEquals(832040, result.get())
            assertEquals((832040 and 0x7f), m.lock.withLockValue { m.kernel.processes[pid]!!.exitCode })
        }
    }

    @Test
    fun `終わらない計算はpauseAllでTimeoutExceptionになり、stopでEndiveの中断によってKILLEDになる`() = timeout(Duration.ofSeconds(30)) {
        Harness().use { h ->
            val m = h.machine()
            val pid = m.spawnTest(fib, fibDriver(0x7fffffff, AtomicLong()), unmetered = true)
            eventually { h.scheduler.activeProcesses == 1 }
            assertThrows<TimeoutException> { h.scheduler.pauseAll(100) }
            m.stop()
            assertTrue(m.awaitTermination(10_000))
            assertState(ProcessState.KILLED, m, pid)
        }
    }
}
