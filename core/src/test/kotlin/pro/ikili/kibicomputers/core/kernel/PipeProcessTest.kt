package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.fd.FdIo
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.fd.IoResult
import pro.ikili.kibicomputers.core.kernel.fd.PipeFds
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.Collections
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** パイプでつないだプロセスどうしの読み書き（#59）: いっぱいで書き手が待ち、書き手が終わると読み手が EOF、読み手が終わると書き手が PIPE */
class PipeProcessTest {
    private val limit = Duration.ofSeconds(30)

    private fun Harness.tickUntil(message: String = "条件がそろわない", condition: () -> Boolean) = eventually(10_000, message) {
        scheduler.tick()
        Thread.sleep(1)
        condition()
    }

    /** 止められるまで待ち続けるプロセス（パイプを作って子に渡す親） */
    private fun Harness.waitingParent(m: Machine): Int {
        val never = m.lock.withLockValue { m.kernel.objects.add(ByteStream(1)) }
        val pid = m.spawnTest(program(emptyModule()), TestDriver(ProcessKind.BLOCKING) { ctx ->
            ctx.block(listOf(WaitKey(never, WaitCondition.READABLE)))
            StepResult.Exit(0)
        })
        tickUntil { m.stateOf(pid) == ProcessState.WAITING_EVENT }
        return pid
    }

    private fun Machine.fds(pid: Int): FdTable = FdTable(kernel.processes[pid]!!, kernel.objects, wakeSink)

    private fun Machine.pipe(parent: Int, capacity: Int): PipeFds = lock.withLockValue { fds(parent).pipe(capacity) }

    private fun Machine.close(pid: Int, vararg fd: Int) = lock.withLockValue { fd.forEach { fds(pid).close(it) } }

    @Test
    fun `いっぱいなら書き手が待ち、書き手が終わったら読み手は残りを読んでEof`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val parent = h.waitingParent(m)
            val p = m.pipe(parent, capacity = 8)
            val data = "0123456789abcdefghij".toByteArray()
            val writes = Collections.synchronizedList(ArrayList<IoResult>())
            val writer = m.spawn(
                h.program(emptyModule()),
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    // 読み手がまだいないので、容量まで書けて、その次は待つしかない
                    writes += FdIo.write(ctx, 1, data, 0, data.size)
                    writes += FdIo.write(ctx, 1, data, 8, 1)
                    var off = 8
                    while (off < data.size) {
                        val r = FdIo.writeBlocking(ctx, 1, data, off, data.size - off)
                        writes += r
                        off += (r as IoResult.Bytes).count
                    }
                    StepResult.Exit(0)
                },
                SpawnOptions(stdio = StdioSpec(StdioSource.Closed, StdioSource.ParentFd(p.write), StdioSource.Closed)),
                parent,
            )
            h.tickUntil("書き手が待たない") { m.stateOf(writer) == ProcessState.WAITING_EVENT }
            assertEquals(IoResult.Bytes(8), writes[0])
            assertEquals(IoResult.WouldBlock::class, writes[1]::class)

            val got = ByteArrayOutputStream()
            val last = Collections.synchronizedList(ArrayList<IoResult>())
            val reader = m.spawn(
                h.program(emptyModule()),
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    val buf = ByteArray(3)
                    while (true) {
                        val r = FdIo.readBlocking(ctx, 0, buf, 0, buf.size)
                        if (r !is IoResult.Bytes) {
                            last += r
                            break
                        }
                        synchronized(got) { got.write(buf, 0, r.count) }
                    }
                    StepResult.Exit(0)
                },
                SpawnOptions(stdio = StdioSpec(StdioSource.ParentFd(p.read), StdioSource.Closed, StdioSource.Closed)),
                parent,
            )
            // 親が持つ両端を閉じる（書き手が終われば、書く端を持つものがいなくなる）
            m.close(parent, p.read, p.write)
            h.tickUntil("読み手が終わらない") { m.stateOf(writer).isTerminal && m.stateOf(reader).isTerminal }
            assertEquals(String(data), synchronized(got) { got.toString(Charsets.UTF_8) })
            assertEquals(listOf<IoResult>(IoResult.Eof), last)
        }
    }

    @Test
    fun `読み手が終わったら、待っていた書き手は起きてPIPE`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val parent = h.waitingParent(m)
            val p = m.pipe(parent, capacity = 4)
            val results = Collections.synchronizedList(ArrayList<IoResult>())
            val writer = m.spawn(
                h.program(emptyModule()),
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    val b = "abcdef".toByteArray()
                    results += FdIo.writeBlocking(ctx, 1, b, 0, b.size)
                    results += FdIo.writeBlocking(ctx, 1, b, 4, 2) // いっぱいなので待つ
                    StepResult.Exit(0)
                },
                SpawnOptions(stdio = StdioSpec(StdioSource.Closed, StdioSource.ParentFd(p.write), StdioSource.Closed)),
                parent,
            )
            h.tickUntil("書き手が待たない") { m.stateOf(writer) == ProcessState.WAITING_EVENT }
            // 読む端を持つ子を作って、何も読まずに終わらせる
            val reader = m.spawn(
                h.program(emptyModule()),
                TestDriver(ProcessKind.BLOCKING) { StepResult.Exit(0) },
                SpawnOptions(stdio = StdioSpec(StdioSource.ParentFd(p.read), StdioSource.Closed, StdioSource.Closed)),
                parent,
            )
            m.close(parent, p.read)
            h.tickUntil("読み手が終わらない") { m.stateOf(reader).isTerminal }
            h.tickUntil("書き手が起きない") { m.stateOf(writer).isTerminal }
            assertEquals(listOf(IoResult.Bytes(4), IoResult.Error(pro.ikili.kibicomputers.core.kernel.vfs.Errno.PIPE)), results.toList())
        }
    }
}
