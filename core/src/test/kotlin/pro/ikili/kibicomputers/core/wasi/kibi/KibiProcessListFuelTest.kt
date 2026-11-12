package pro.ikili.kibicomputers.core.wasi.kibi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.Gate
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.gated
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.tickUntilTrue
import pro.ikili.kibicomputers.core.runtime.FakeModule
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** `list-processes` の件数ごとの燃料（[KibiProcess.list]。#452、ADR 0452）。表にあるプロセスの数に比例して引く */
class KibiProcessListFuelTest {
    @Test
    fun `list-processesは表のプロセス1つにつきPROCESS_LIST_FUEL_PER_ENTRYの燃料を引く`() = timeout(Duration.ofSeconds(30)) {
        val module = FakeModule.builder().fuelMetered().memory(1).build()
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val gate = Gate(m)
            val error = AtomicReference<Throwable>()
            val others = 2
            // 表に残る（止まって待つ）だけのプロセス
            val idle = List(others) {
                m.spawn(h.program(module), gated(gate))
            }
            val driver = TestDriver(ProcessKind.BLOCKING) { ctx ->
                try {
                    fun cost(): Pair<Long, Int> {
                        val before = ctx.fuelHolder.fuel
                        val n = KibiProcess.list(ctx).size
                        return (before - ctx.fuelHolder.fuel) to n
                    }
                    val (fuel, n) = cost()
                    assertEquals(1 + others, n)
                    assertEquals(n * KibiProcess.PROCESS_LIST_FUEL_PER_ENTRY, fuel, "件数 $n に比例して引く")
                    gate.open()
                } catch (t: Throwable) {
                    error.set(t)
                    gate.open()
                }
                StepResult.Exit(0)
            }
            val pid = m.spawn(h.program(module), driver)
            h.tickUntilTrue("終わらない: ${error.get()}") { m.stateOf(pid).isTerminal && idle.all { m.stateOf(it).isTerminal } }
            error.get()?.let { throw it }
            assertEquals(ProcessState.EXITED, m.stateOf(pid))
        }
    }
}
