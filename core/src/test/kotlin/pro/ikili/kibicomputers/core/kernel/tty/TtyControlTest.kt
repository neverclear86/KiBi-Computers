package pro.ikili.kibicomputers.core.kernel.tty

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.emptyModule
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.spawnTest
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.withLockValue
import java.time.Duration
import java.util.Collections
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** fd を通した TTY の操作（#60） */
class TtyControlTest {
    private fun errno(block: () -> Any?): Any? = try {
        block()
    } catch (e: FsException) {
        e.errno
    }

    @Test
    fun `TTYのfdでモード・大きさ・世代・前のプロセスを読み書きでき、ほかはNOTTY、閉じたfdはBADF`() = timeout(Duration.ofSeconds(30)) {
        Harness().use { h ->
            val m = h.machine()
            val results = Collections.synchronizedList(ArrayList<Any?>())
            val pid = m.spawnTest(h.program(emptyModule()), TestDriver(ProcessKind.BLOCKING) { ctx: ProcessContext ->
                val streamFd = ctx.withKernelLock {
                    FdTable(ctx.kernel.processes[ctx.pid]!!, ctx.kernel.objects, ctx.wakeSink).open(ByteStream(8))
                }
                results.add(listOf(TtyControl.isTty(ctx, 0), TtyControl.isTty(ctx, 1), TtyControl.isTty(ctx, 2)))
                results.add(TtyControl.isTty(ctx, streamFd))
                results.add(TtyControl.isTty(ctx, 9))
                results.add(TtyControl.mode(ctx, 0))
                TtyControl.setMode(ctx, 1, TtyMode.COOKED.with(TtyMode.ECHO, false))
                results.add(TtyControl.mode(ctx, 2))
                TtyControl.setMode(ctx, 0, TtyControl.mode(ctx, 0).with(TtyMode.CANONICAL, false))
                results.add(TtyControl.mode(ctx, 0))
                results.add(TtyControl.size(ctx, 0))
                results.add(TtyControl.sizeGeneration(ctx, 0))
                TtyControl.setForeground(ctx, 0, ctx.pid)
                results.add(TtyControl.foreground(ctx, 1))
                results.add(errno { TtyControl.mode(ctx, streamFd) })
                results.add(errno { TtyControl.size(ctx, 9) })
                results.add(errno { TtyControl.setForeground(ctx, 0, -1) })
                StepResult.Exit(0)
            })
            eventually(10_000) {
                h.scheduler.tick()
                Thread.sleep(1)
                m.stateOf(pid).isTerminal
            }
            assertEquals(
                listOf(
                    listOf(true, true, true),
                    false,
                    false,
                    TtyMode.COOKED,
                    TtyMode.COOKED.with(TtyMode.ECHO, false),
                    TtyMode.COOKED.with(TtyMode.ECHO, false).with(TtyMode.CANONICAL, false),
                    TtySize(Tty.DEFAULT_COLUMNS, Tty.DEFAULT_ROWS),
                    0L,
                    pid,
                    Errno.NOTTY,
                    Errno.BADF,
                    Errno.INVAL,
                ),
                results.toList(),
            )
            assertEquals(pid, m.lock.withLockValue { m.consoleTtyLocked().foregroundPid })
        }
    }
}
