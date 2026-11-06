package pro.ikili.kibicomputers.core.kernel.tty

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.emptyModule
import pro.ikili.kibicomputers.core.kernel.event.EventValue
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.spawnTest
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.tickUntilTrue
import pro.ikili.kibicomputers.core.wasi.kibi.KibiEvents
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 画面の大きさが変わったときの `tty-resize` のイベント（#427。ADR 0427） */
class TtyResizeEventTest {
    private fun inProcess(body: (ProcessContext, Machine) -> Unit) = timeout(Duration.ofSeconds(30)) {
        Harness().use { h ->
            val m = h.machine()
            val error = AtomicReference<Throwable>()
            val pid = m.spawnTest(h.program(emptyModule()), TestDriver(ProcessKind.BLOCKING) { ctx ->
                try {
                    body(ctx, m)
                } catch (t: Throwable) {
                    error.set(t)
                }
                StepResult.Exit(0)
            })
            h.tickUntilTrue { m.stateOf(pid).isTerminal }
            error.get()?.let { throw it }
            assertEquals(ProcessState.EXITED, m.stateOf(pid))
        }
    }

    private fun values(vararg v: Long): List<EventValue> = v.map { EventValue.I64(it) }

    @Test
    fun `resizeConsoleで、tty-resizeのキューにconsoleから列・行・世代が入る`() = inProcess { ctx, m ->
        val q = KibiEvents.open(ctx, listOf(TtyLimits.RESIZE_EVENT_TYPE), null)
        val other = KibiEvents.open(ctx, listOf("timer"), null)
        m.resizeConsole(100, 30)
        m.resizeConsole(120, 40)
        val batch = KibiEvents.take(ctx, q, 16)
        assertEquals(0L, batch.dropped)
        assertEquals(listOf("tty-resize", "tty-resize"), batch.events.map { it.type })
        assertEquals(listOf("console", "console"), batch.events.map { it.source })
        assertEquals(listOf(values(100, 30, 1), values(120, 40, 2)), batch.events.map { it.values })
        // 種類の違うキューには入らない
        assertTrue(KibiEvents.take(ctx, other, 16).isEmpty)
    }

    @Test
    fun `同じ大きさならイベントは入らず、世代も進まない`() = inProcess { ctx, m ->
        val q = KibiEvents.open(ctx, listOf(TtyLimits.RESIZE_EVENT_TYPE), null)
        val size = m.consoleSize
        m.resizeConsole(size.columns, size.rows)
        assertTrue(KibiEvents.take(ctx, q, 16).isEmpty)
        assertEquals(0L, m.consoleSizeGeneration)
        m.resizeConsole(size.columns + 1, size.rows)
        m.resizeConsole(size.columns + 1, size.rows)
        assertEquals(1, KibiEvents.take(ctx, q, 16).events.size)
    }

    @Test
    fun `キューがなくても大きさは変わる`() = timeout(Duration.ofSeconds(30)) {
        Harness().use { h ->
            val m = h.machine()
            m.resizeConsole(90, 20)
            assertEquals(TtySize(90, 20), m.consoleSize)
            assertEquals(1L, m.consoleSizeGeneration)
        }
    }
}
