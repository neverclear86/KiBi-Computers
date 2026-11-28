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
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 実際の Endive で、燃料を計測しない読み込み（[SharedPrograms.load]。挿入なし）の WASM を動かす。
 * 計算中の pauseAll が時間切れになることと、割り込みによる停止を確かめる（ADR 0043）。燃料の計測は FuelMeteringTest で確かめる。
 */
class RealWasmSchedulerTest {
    private val fib: Program by lazy { loadResource("fib.wasm") }

    /** fuel.wasm の `forever`（燃料を入れない読み込みでは、割り込み以外では終わらない無限ループ）を呼ぶためのもの（#994）。 */
    private val forever: Program by lazy { loadResource("fuel.wasm") }

    private fun loadResource(name: String): Program {
        val bytes = javaClass.getResourceAsStream("/kibicomputers/wasm/$name")?.use { it.readBytes() }
        assertNotNull(bytes, "$name がリソースにない")
        return SharedPrograms.load(bytes!!)
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

    /**
     * 計算は本当に終わらない（#994。前は 2^31 回のループで、負荷の下では stop より先に終わることがあった）。
     * 割り込みが効かないと stop でも Harness の片付けでも止まらないので、Endive を上げたときの検査を兼ねる。
     */
    @Test
    fun `終わらない計算はpauseAllでTimeoutExceptionになり、stopでEndiveの中断によってKILLEDになる`() = timeout(Duration.ofSeconds(30)) {
        Harness().use { h ->
            val m = h.machine()
            val driver = TestDriver(ProcessKind.BLOCKING) { ctx ->
                ctx.instance.call("forever")
                // ここには届かない。届けば EXITED になり、下の assertState で落ちる
                StepResult.Exit(0)
            }
            val pid = m.spawnTest(forever, driver, unmetered = true)
            eventually { h.scheduler.activeProcesses == 1 }
            assertThrows<TimeoutException> { h.scheduler.pauseAll(100) }
            m.stop()
            assertTrue(m.awaitTermination(10_000))
            assertState(ProcessState.KILLED, m, pid)
        }
    }
}
