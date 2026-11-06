package pro.ikili.kibicomputers.core.wasi.kibi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.emptyModule
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.io.PipeEnd
import pro.ikili.kibicomputers.core.kernel.io.ReadResult
import pro.ikili.kibicomputers.core.kernel.io.WriteResult
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
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

/** 独自拡張 `kibi:io/fd`・`kibi:io/pipe` の意味の部分（[KibiIo]。#75） */
class KibiIoTest {
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

    private fun <T> ProcessContext.table(block: (FdTable) -> T): T =
        withKernelLock { block(FdTable(checkNotNull(kernel.processes[pid]), kernel.objects, wakeSink)) }

    private fun ProcessContext.limit(): Int = withKernelLock { checkNotNull(kernel.processes[pid]).handles.limit }

    /** [fd]（パイプの端）の中身のストリーム。カーネルロックの中で呼ぶ */
    private fun ProcessContext.streamOf(fd: Int): ByteStream {
        val end = FdTable(checkNotNull(kernel.processes[pid]), kernel.objects, wakeSink).objectOf(fd) as PipeEnd
        return kernel.objects[end.streamId] as ByteStream
    }

    private fun ProcessContext.write(fd: Int, s: String): WriteResult = withKernelLock {
        val b = s.toByteArray()
        streamOf(fd).write(b, 0, b.size, wakeSink)
    }

    private fun ProcessContext.read(fd: Int): Any = withKernelLock {
        val buf = ByteArray(64)
        when (val r = streamOf(fd).read(buf, 0, buf.size, wakeSink)) {
            is ReadResult.Bytes -> String(buf, 0, r.count)
            else -> r
        }
    }

    /** 空き番号が [free] 個になるまで fd を埋める（表に fd が1つはあること） */
    private fun ProcessContext.fillUntil(free: Int) {
        val limit = limit()
        table { t ->
            val any = t.entries().keys.first()
            while (t.size < limit - free) t.dup(any)
        }
    }

    @Test
    fun `createは読む端と書く端を小さい番号から順に入れ、権利は片方ずつ`() = inProcess { ctx, _ ->
        val used = ctx.table { it.entries().keys }
        val ends = KibiIo.pipe(ctx)
        val expected = generateSequence(0) { it + 1 }.filter { it !in used }.take(2).toList()
        assertEquals(expected, listOf(ends.read, ends.write))
        ctx.table { t ->
            assertEquals(HandleRights.READ, t.entry(ends.read).rights)
            assertEquals(HandleRights.WRITE, t.entry(ends.write).rights)
            assertEquals(0L, t.entry(ends.read).flags)
            assertEquals(0L, t.entry(ends.write).flags)
        }
        assertEquals(WriteResult.Bytes(2), ctx.write(ends.write, "hi"))
        assertEquals("hi", ctx.read(ends.read))
    }

    @Test
    fun `createは空き番号が1つか0ならmfileで、オブジェクト表は変わらない`() = inProcess { ctx, _ ->
        KibiIo.pipe(ctx) // 表に fd を置く
        for (free in listOf(1, 0)) {
            ctx.fillUntil(free)
            val before = ctx.withKernelLock { ctx.kernel.objects.size }
            assertErrno(Errno.MFILE) { KibiIo.pipe(ctx) }
            assertEquals(before, ctx.withKernelLock { ctx.kernel.objects.size })
        }
    }

    @Test
    fun `dupは同じオブジェクトを指し、権利とフラグを写す`() = inProcess { ctx, _ ->
        val ends = KibiIo.pipe(ctx)
        ctx.table { it.setFlags(ends.write, 4) }
        val d = KibiIo.dup(ctx, ends.write.toLong())
        ctx.table { t ->
            assertEquals(t.entry(ends.write), t.entry(d))
            assertEquals(HandleRights.WRITE, t.entry(d).rights)
            assertEquals(4L, t.entry(d).flags)
            // フラグは fd ごと（共有しない。ADR 0075）
            t.setFlags(d, 0)
            assertEquals(4L, t.entry(ends.write).flags)
        }
        // 複製した方で書いたものが、元の読む端から読める。複製を閉じても元は使える
        ctx.write(d, "a")
        ctx.table { it.close(d) }
        ctx.write(ends.write, "b")
        assertEquals("ab", ctx.read(ends.read))
    }

    @Test
    fun `dupは閉じたfdや2の31乗以上ならbad-descriptor、満杯ならmfile`() = inProcess { ctx, _ ->
        val ends = KibiIo.pipe(ctx)
        ctx.table { it.close(ends.write) }
        assertErrno(Errno.BADF) { KibiIo.dup(ctx, ends.write.toLong()) }
        assertErrno(Errno.BADF) { KibiIo.dup(ctx, 0x8000_0000L) }
        assertErrno(Errno.BADF) { KibiIo.dup(ctx, 0xffff_ffffL) }
        ctx.fillUntil(0)
        assertErrno(Errno.MFILE) { KibiIo.dup(ctx, ends.read.toLong()) }
    }

    @Test
    fun `dup-toは開いているtoを閉じ、最後の書く端を上書きすると読む側はEOF`() = inProcess { ctx, _ ->
        val a = KibiIo.pipe(ctx)
        val b = KibiIo.pipe(ctx)
        ctx.write(a.write, "x")
        // a の書く端（最後の1つ）を b の書く端で上書きする
        KibiIo.dupTo(ctx, b.write.toLong(), a.write.toLong())
        assertEquals("x", ctx.read(a.read))
        assertEquals(ReadResult.Eof, ctx.read(a.read))
        ctx.table { t -> assertEquals(t.entry(b.write), t.entry(a.write)) }
        // 上書きした番号に書いたものは b に入る
        ctx.write(a.write, "y")
        assertEquals("y", ctx.read(b.read))
    }

    @Test
    fun `dup-toは同じ番号なら確かめるだけで、toが範囲外やfdが閉じていればbad-descriptorでtoを閉じない`() = inProcess { ctx, _ ->
        val ends = KibiIo.pipe(ctx)
        fun refs() = ctx.table { t -> ctx.kernel.objects.refCount(t.entry(ends.read).objectId) }
        val before = refs()
        KibiIo.dupTo(ctx, ends.read.toLong(), ends.read.toLong())
        assertEquals(before, refs())
        val limit = ctx.limit()
        assertErrno(Errno.BADF) { KibiIo.dupTo(ctx, ends.read.toLong(), limit.toLong()) }
        assertErrno(Errno.BADF) { KibiIo.dupTo(ctx, ends.read.toLong(), 0x8000_0000L) }
        assertErrno(Errno.BADF) { KibiIo.dupTo(ctx, 0x8000_0000L, ends.write.toLong()) }
        val closed = limit - 1
        ctx.table { assertNull(it.entries()[closed]) }
        assertErrno(Errno.BADF) { KibiIo.dupTo(ctx, closed.toLong(), ends.write.toLong()) }
        assertErrno(Errno.BADF) { KibiIo.dupTo(ctx, closed.toLong(), closed.toLong()) }
        // 書く端は閉じていない
        assertEquals(WriteResult.Bytes(1), ctx.write(ends.write, "z"))
        assertEquals(before, refs())
    }
}
