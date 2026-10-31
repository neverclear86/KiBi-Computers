package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.runtime.FakeModule
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

class PauseTest {
    private val limit = Duration.ofSeconds(30)

    @Test
    fun `pauseAllの間はどのプロセスも進まず、closeで再開する`() = timeout(limit) {
        Harness().use { h ->
            val progress = AtomicInteger()
            val m = h.machine(fuelPerTick = 1000)
            m.spawnTest(h.program(spinForever(progress)), runDriver())
            h.scheduler.tick()
            eventually { progress.get() > 0 }
            val handle = h.scheduler.pauseAll(5_000)
            assertEquals(0, h.scheduler.activeProcesses)
            val atPause = progress.get()
            repeat(5) {
                h.scheduler.tick()
                Thread.sleep(10)
            }
            assertEquals(atPause, progress.get(), "一時停止の間は進まない")
            handle.close()
            handle.close() // 2回呼んでもよい
            h.scheduler.tick()
            eventually { progress.get() > atPause }
            m.stop()
        }
    }

    @Test
    fun `止まるまでの時間は手持ちを使い切るまで`() = timeout(limit) {
        Harness().use { h ->
            val progress = AtomicInteger()
            val m = h.machine(fuelPerTick = 50)
            m.spawnTest(h.program(spinForever(progress, sleepMillis = 1)), runDriver())
            h.scheduler.tick()
            eventually { progress.get() > 0 }
            val start = progress.get()
            val t0 = System.nanoTime()
            h.scheduler.pauseAll(5_000).use {
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000
                assertTrue(elapsedMs < 1_000, "止まるまで $elapsedMs ms")
                assertTrue(progress.get() - start <= 50 + 1, "渡した燃料は最大で fuelPerTick")
            }
            m.stop()
        }
    }

    @Test
    fun `燃料を計測しないプロセスが計算中だとTimeoutExceptionになり、一時停止が解ける`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val other = h.machine(fuelPerTick = 1000)
            val unmetered = h.program(
                FakeModule.builder().export("run", VOID) { ctx, _ ->
                    while (true) ctx.step(1)
                    @Suppress("UNREACHABLE_CODE")
                    LongArray(0)
                }.build(),
            )
            m.spawnTest(unmetered, runDriver(), unmetered = true)
            eventually { h.scheduler.activeProcesses == 1 }
            assertThrows<TimeoutException> { h.scheduler.pauseAll(100) }
            assertTrue(!h.scheduler.isPaused, "一時停止は解けている")
            val progress = AtomicInteger()
            other.spawnTest(h.program(spinForever(progress)), runDriver())
            h.scheduler.tick()
            eventually { progress.get() > 0 }
            m.stop()
            other.stop()
        }
    }
}
