package pro.ikili.kibicomputers.core.wasi.kibi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.emptyModule
import pro.ikili.kibicomputers.core.kernel.event.EventLimits
import pro.ikili.kibicomputers.core.kernel.event.EventValue
import pro.ikili.kibicomputers.core.kernel.event.KernelEvent
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.spawnTest
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.tickUntilTrue
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 独自拡張 `kibi:kernel/events` の意味の部分（[KibiEvents]。#73） */
class KibiEventsTest {
    /** ブロッキング型のプロセスの中で [body] を動かし、例外があれば投げ直す */
    private fun inProcess(body: (ProcessContext, Machine) -> Unit) = timeout(Duration.ofSeconds(30)) {
        Harness().use { h ->
            val m = h.machine()
            val error = AtomicReference<Throwable>()
            val driver = TestDriver(ProcessKind.BLOCKING) { ctx ->
                try {
                    body(ctx, m)
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

    private fun assertErrno(expected: Errno, block: () -> Unit) {
        val e = assertThrows<FsException> { block() }
        assertEquals(expected, e.errno, e.message)
    }

    @Test
    fun `開いて取り出すと、受け取る種類だけが届き、あふれた数をdroppedで知らせる`() = inProcess { ctx, m ->
        val q = KibiEvents.open(ctx, listOf("a", "c"), 2)
        val values = listOf(
            EventValue.Integer(-1),
            EventValue.Float(2.5),
            EventValue.Boolean(true),
            EventValue.Text(""),
            EventValue.Text("\uD800x"),
            EventValue.Bytes(ByteArray(0)),
        )
        m.postEvent(KernelEvent("a", "src", values))
        m.postEvent(KernelEvent("b"))
        m.postEvent(KernelEvent("c"))
        m.postEvent(KernelEvent("a"))
        val batch = KibiEvents.take(ctx, q, 10)
        assertEquals(listOf("a", "c"), batch.events.map { it.type })
        assertEquals(values, batch.events[0].values)
        assertEquals("src", batch.events[0].source)
        assertEquals(1L, batch.dropped)
        val empty = KibiEvents.take(ctx, q, 1)
        assertEquals(0, empty.events.size)
        assertEquals(0L, empty.dropped)
    }

    @Test
    fun `kindsが空ならすべての種類を受け取る`() = inProcess { ctx, m ->
        val q = KibiEvents.open(ctx, emptyList(), null)
        m.postEvent(KernelEvent("x"))
        m.postEvent(KernelEvent("y"))
        assertEquals(listOf("x", "y"), KibiEvents.take(ctx, q, 10).events.map { it.type })
    }

    @Test
    fun `maxは0だけがinvalidで、2の31乗以上は全部`() = inProcess { ctx, m ->
        val q = KibiEvents.open(ctx, emptyList(), null)
        assertErrno(Errno.INVAL) { KibiEvents.take(ctx, q, 0) }
        repeat(4) { m.postEvent(KernelEvent("e")) }
        assertEquals(1, KibiEvents.take(ctx, q, 1).events.size)
        assertEquals(3, KibiEvents.take(ctx, q, 1L shl 31).events.size)
        repeat(2) { m.postEvent(KernelEvent("e")) }
        assertEquals(2, KibiEvents.take(ctx, q, 0xffff_ffffL).events.size)
    }

    @Test
    fun `capacityは1から256で、外れればinvalid`() = inProcess { ctx, _ ->
        for (bad in listOf(0L, 257L, 1L shl 31, 0xffff_ffffL)) assertErrno(Errno.INVAL) { KibiEvents.open(ctx, emptyList(), bad) }
        KibiEvents.open(ctx, emptyList(), 1)
        KibiEvents.open(ctx, emptyList(), EventLimits.MAX_CAPACITY.toLong())
    }

    @Test
    fun `kindsは16個まで、それぞれ1から64バイトで、外れればinvalid（IllegalArgumentExceptionにならない）`() = inProcess { ctx, _ ->
        KibiEvents.open(ctx, (1..16).map { "k$it" }, null)
        assertErrno(Errno.INVAL) { KibiEvents.open(ctx, (1..17).map { "k$it" }, null) }
        assertErrno(Errno.INVAL) { KibiEvents.open(ctx, listOf(""), null) }
        assertErrno(Errno.INVAL) { KibiEvents.open(ctx, listOf("a".repeat(65)), null) }
        // 多バイト文字は UTF-8 のバイトで数える（"あ" は 3 バイト。22 文字で 66 バイト）
        assertErrno(Errno.INVAL) { KibiEvents.open(ctx, listOf("あ".repeat(22)), null) }
        KibiEvents.open(ctx, listOf("a".repeat(64)), null)
    }

    @Test
    fun `マシンのキューの数の上限はnfile`() = inProcess { ctx, _ ->
        repeat(EventLimits.MAX_QUEUES) { KibiEvents.open(ctx, emptyList(), 1) }
        assertErrno(Errno.NFILE) { KibiEvents.open(ctx, emptyList(), 1) }
    }

    @Test
    fun `プロセスのハンドル表が満杯ならmfileで、オブジェクト表にも索引にも残らず、1つ閉じれば開ける`() = inProcess { ctx, _ ->
        // キューだけで埋めると MAX_QUEUES で nfile が先に出るので、キューでない fd を dup して埋める
        val spare = ctx.withKernelLock {
            val table = FdTable(ctx.kernel.processes[ctx.pid]!!, ctx.kernel.objects, ctx.wakeSink)
            val fd = table.open(ByteStream(1))
            var last = fd
            while (!table.isFull) last = table.dup(fd)
            last
        }
        val before = ctx.withKernelLock { ctx.kernel.objects.size to ctx.kernel.events.indexedCount }
        assertErrno(Errno.MFILE) { KibiEvents.open(ctx, emptyList(), null) }
        assertErrno(Errno.MFILE) { KibiEvents.open(ctx, listOf("a", "b"), 8) }
        assertEquals(before, ctx.withKernelLock { ctx.kernel.objects.size to ctx.kernel.events.indexedCount })
        ctx.withKernelLock { FdTable(ctx.kernel.processes[ctx.pid]!!, ctx.kernel.objects, ctx.wakeSink).close(spare) }
        val q = KibiEvents.open(ctx, listOf("a"), null)
        KibiEvents.drop(ctx, q)
    }

    @Test
    fun `キューの数とハンドル表の両方が上限なら、nfileが先`() = inProcess { ctx, _ ->
        repeat(EventLimits.MAX_QUEUES) { KibiEvents.open(ctx, emptyList(), 1) }
        ctx.withKernelLock {
            val table = FdTable(ctx.kernel.processes[ctx.pid]!!, ctx.kernel.objects, ctx.wakeSink)
            val fd = table.open(ByteStream(1))
            while (!table.isFull) table.dup(fd)
        }
        assertErrno(Errno.NFILE) { KibiEvents.open(ctx, emptyList(), 1) }
    }

    @Test
    fun `キューでないfdはbad-descriptorで、dropで閉じた後も同じ`() = inProcess { ctx, _ ->
        assertErrno(Errno.BADF) { KibiEvents.take(ctx, 999, 1) }
        assertErrno(Errno.BADF) { KibiEvents.drop(ctx, 999) }
        val q = KibiEvents.open(ctx, emptyList(), null)
        KibiEvents.drop(ctx, q)
        assertErrno(Errno.BADF) { KibiEvents.take(ctx, q, 1) }
        assertErrno(Errno.BADF) { KibiEvents.drop(ctx, q) }
    }
}
