package pro.ikili.kibicomputers.core.wasi.kibi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.MachineEnvironment
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.emptyModule
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.spawnTest
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.tickUntilTrue
import pro.ikili.kibicomputers.core.kernel.time.ManualClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 独自拡張 `kibi:computer/clock` の意味の部分（[KibiClock]。#426） */
class KibiClockTest {
    private fun inProcess(environment: MachineEnvironment, body: (ProcessContext, Harness) -> Unit) = timeout(Duration.ofSeconds(30)) {
        Harness().use { h ->
            val m = h.machine(environment = environment)
            val error = AtomicReference<Throwable>()
            val driver = TestDriver(ProcessKind.BLOCKING) { ctx ->
                try {
                    body(ctx, h)
                } catch (t: Throwable) {
                    error.set(t)
                }
                StepResult.Exit(0)
            }
            val pid = m.spawnTest(h.program(emptyModule()), driver)
            h.tickUntilTrue { m.stateOf(pid).isTerminal }
            error.get()?.let { throw it }
            assertEquals(ProcessState.EXITED, m.stateOf(pid))
        }
    }

    @Test
    fun `uptime-ticksはtickとともに進む`() {
        val first = AtomicLong(-1)
        val ticked = CountDownLatch(1)
        timeout(Duration.ofSeconds(30)) {
            Harness().use { h ->
                val m = h.machine()
                val error = AtomicReference<Throwable>()
                val driver = TestDriver(ProcessKind.BLOCKING) { ctx ->
                    try {
                        first.set(KibiClock.uptimeTicks(ctx))
                        ticked.await(10, TimeUnit.SECONDS)
                        val second = KibiClock.uptimeTicks(ctx)
                        assertTrue(first.get() >= 0)
                        assertTrue(second > first.get(), "$second > ${first.get()}")
                    } catch (t: Throwable) {
                        error.set(t)
                    }
                    StepResult.Exit(0)
                }
                val pid = m.spawnTest(h.program(emptyModule()), driver)
                h.tickUntilTrue { first.get() >= 0 || m.stateOf(pid).isTerminal }
                repeat(3) { h.scheduler.tick() }
                ticked.countDown()
                h.tickUntilTrue { m.stateOf(pid).isTerminal }
                error.get()?.let { throw it }
            }
        }
    }

    @Test
    fun `world-ticksは環境の時計のゲーム内時間を返す`() {
        val clock = ManualClock(world = 123_456)
        inProcess(MachineEnvironment.builder().clock(clock).build()) { ctx, _ ->
            assertEquals(123_456L, KibiClock.worldTicks(ctx))
            clock.world.set(200_000)
            assertEquals(200_000L, KibiClock.worldTicks(ctx))
            // 負の値は 0（u64 として出すため）
            clock.world.set(-5)
            assertEquals(0L, KibiClock.worldTicks(ctx))
        }
    }

    @Test
    fun `ワールドがなければworld-ticksは0`() = inProcess(MachineEnvironment.DEFAULT) { ctx, _ ->
        assertEquals(0L, KibiClock.worldTicks(ctx))
    }
}
