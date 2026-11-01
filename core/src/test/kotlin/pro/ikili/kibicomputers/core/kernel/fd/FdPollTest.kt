package pro.ikili.kibicomputers.core.kernel.fd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.emptyModule
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.obj.KernelObject
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.tickUntilTrue
import pro.ikili.kibicomputers.core.kernel.tty.Tty
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition.READABLE
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition.WRITABLE
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.withLockValue
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** `FdIo.pollLocked`（#72）: fd の待ちの判定 */
class FdPollTest {
    private val limit = Duration.ofSeconds(30)

    /** 待てない種類 */
    private class Opaque : KernelObject()

    /** READABLE だけ待てて、[ready] のときにそろう種類 */
    private class Flag(var ready: Boolean) : KernelObject() {
        override fun canWait(condition: WaitCondition): Boolean = condition == READABLE

        override fun isReady(condition: WaitCondition): Boolean = condition == READABLE && ready
    }

    /** プロセスを1つ動かし、その中で [body] を呼ぶ */
    private fun Harness.inProcess(m: Machine, options: SpawnOptions, body: (ProcessContext) -> Unit) {
        val error = AtomicReference<Throwable>()
        val pid = m.spawn(program(emptyModule()), TestDriver(ProcessKind.WAIT_POINT) { ctx ->
            try {
                body(ctx)
            } catch (t: Throwable) {
                error.set(t)
            }
            StepResult.Exit(0)
        }, options)
        tickUntilTrue { m.stateOf(pid).isTerminal }
        error.get()?.let { throw it }
        assertEquals(ProcessState.EXITED, m.stateOf(pid))
    }

    private fun ProcessContext.poll(fd: Int, condition: WaitCondition): FdReadiness =
        withKernelLock { FdIo.pollLocked(kernel, vfs, pid, fd, condition) }

    @Test
    fun `パイプは読めるバイトと空きと相手の閉じを返し、そろわなければ待つkeyを返す`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val (pipe, full) = m.lock.withLockValue {
                val p = ByteStream(8).also { m.kernel.objects.add(it) }
                val f = ByteStream(2).also { m.kernel.objects.add(it) }
                f.write("ab".toByteArray(), 0, 2, m.wakeSink)
                p to f
            }
            val opts = SpawnOptions(
                stdio = StdioSpec(StdioSource.Object(pipe.id, HandleRights.READ), StdioSource.Object(pipe.id, HandleRights.WRITE), StdioSource.Object(full.id, HandleRights.WRITE)),
            )
            h.inProcess(m, opts) { ctx ->
                assertEquals(FdReadiness.Pending(WaitKey(pipe.id, READABLE)), ctx.poll(0, READABLE))
                assertEquals(FdReadiness.Ready(8), ctx.poll(1, WRITABLE))
                assertEquals(FdReadiness.Pending(WaitKey(full.id, WRITABLE)), ctx.poll(2, WRITABLE))
                ctx.withKernelLock { pipe.write("xyz".toByteArray(), 0, 3, ctx.wakeSink) }
                assertEquals(FdReadiness.Ready(3), ctx.poll(0, READABLE))
                assertEquals(FdReadiness.Ready(5), ctx.poll(1, WRITABLE))
                // 権利のない向き、開いていない fd
                assertEquals(FdReadiness.Error(Errno.BADF), ctx.poll(0, WRITABLE))
                assertEquals(FdReadiness.Error(Errno.BADF), ctx.poll(1, READABLE))
                assertEquals(FdReadiness.Error(Errno.BADF), ctx.poll(9, READABLE))
                ctx.withKernelLock {
                    pipe.closeWrite(ctx.wakeSink)
                    full.closeRead(ctx.wakeSink)
                }
                assertEquals(FdReadiness.Ready(3, hangup = true), ctx.poll(0, READABLE))
                assertEquals(FdReadiness.Ready(0, hangup = true), ctx.poll(2, WRITABLE))
            }
        }
    }

    @Test
    fun `コンソールは読めるバイトと出力の空きを返す`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            h.inProcess(m, SpawnOptions(stdio = StdioSpec.CONSOLE)) { ctx ->
                val tty = ctx.withKernelLock { ctx.kernel.objects[ctx.kernel.consoleTtyId] as Tty }
                assertEquals(FdReadiness.Pending(WaitKey(tty.id, READABLE)), ctx.poll(0, READABLE))
                assertEquals(FdReadiness.Ready(tty.outputCapacity.toLong()), ctx.poll(1, WRITABLE))
                m.offerConsoleInput("hi\n".toByteArray())
                assertEquals(FdReadiness.Ready(3), ctx.poll(0, READABLE))
            }
        }
    }

    @Test
    fun `そのほかの種類は、待てればisReadyで1、待てなければINVAL`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val (opaque, flag) = m.lock.withLockValue {
                Opaque().also { m.kernel.objects.add(it) } to Flag(false).also { m.kernel.objects.add(it) }
            }
            val opts = SpawnOptions(
                stdio = StdioSpec(StdioSource.Object(opaque.id, HandleRights.ALL), StdioSource.Object(flag.id, HandleRights.ALL), StdioSource.Closed),
            )
            h.inProcess(m, opts) { ctx ->
                assertEquals(FdReadiness.Error(Errno.INVAL), ctx.poll(0, READABLE))
                assertEquals(FdReadiness.Error(Errno.INVAL), ctx.poll(1, WRITABLE))
                assertEquals(FdReadiness.Pending(WaitKey(flag.id, READABLE)), ctx.poll(1, READABLE))
                ctx.withKernelLock { flag.ready = true }
                assertEquals(FdReadiness.Ready(1), ctx.poll(1, READABLE))
            }
        }
    }
}
