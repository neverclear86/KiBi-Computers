package pro.ikili.kibicomputers.core.wasi.kibi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.TtySignalHandler
import pro.ikili.kibicomputers.core.kernel.emptyModule
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.spawnTest
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.tickUntilTrue
import pro.ikili.kibicomputers.core.kernel.tty.TtyMode
import pro.ikili.kibicomputers.core.kernel.tty.TtySignal
import pro.ikili.kibicomputers.core.kernel.tty.TtySize
import pro.ikili.kibicomputers.core.kernel.tty.TtySizeInfo
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 独自拡張 `kibi:io/tty` の意味の部分（[KibiTty]。#427）。プロセスの fd 0/1/2 はコンソールの TTY */
class KibiTtyTest {
    /** ブロッキング型のプロセスの中で [body] を動かし、例外があれば投げ直す */
    private fun inProcess(setup: (Machine) -> Unit = {}, body: (ProcessContext, Machine) -> Unit) = timeout(Duration.ofSeconds(30)) {
        Harness().use { h ->
            val m = h.machine()
            setup(m)
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
    fun `fd 0・1・2はTTYで、ファイル・パイプ・閉じたfd・2の31乗以上のfdはTTYでない`() = inProcess { ctx, _ ->
        for (fd in 0L..2L) assertTrue(KibiTty.isTty(ctx, fd), "fd $fd")
        val ends = KibiIo.pipe(ctx)
        assertFalse(KibiTty.isTty(ctx, ends.read.toLong()))
        assertFalse(KibiTty.isTty(ctx, ends.write.toLong()))
        assertFalse(KibiTty.isTty(ctx, 200))
        assertFalse(KibiTty.isTty(ctx, 0x8000_0000L))
        assertFalse(KibiTty.isTty(ctx, 0xffff_ffffL))
    }

    @Test
    fun `set-modeとget-modeが往復し、同じTTYのほかのfdからも見える`() = inProcess { ctx, _ ->
        assertEquals(TtyMode.ALL, KibiTty.mode(ctx, 0))
        KibiTty.setMode(ctx, 0, TtyMode.RAW.bits)
        assertEquals(0, KibiTty.mode(ctx, 0))
        assertEquals(0, KibiTty.mode(ctx, 1))
        val noEcho = TtyMode.ALL and TtyMode.ECHO.inv()
        KibiTty.setMode(ctx, 2, noEcho)
        assertEquals(noEcho, KibiTty.mode(ctx, 0))
        KibiTty.setMode(ctx, 1, TtyMode.COOKED.bits)
        assertEquals(TtyMode.ALL, KibiTty.mode(ctx, 2))
        // 定義外のビットは読まない（TtyMode の require に届かない）
        KibiTty.setMode(ctx, 0, -1)
        assertEquals(TtyMode.ALL, KibiTty.mode(ctx, 0))
    }

    @Test
    fun `TTYでないfdはno-tty、閉じたfdと範囲外のfdはbad-descriptor`() = inProcess { ctx, _ ->
        val ends = KibiIo.pipe(ctx)
        val notTty = ends.read.toLong()
        assertErrno(Errno.NOTTY) { KibiTty.mode(ctx, notTty) }
        assertErrno(Errno.NOTTY) { KibiTty.setMode(ctx, notTty, 0) }
        assertErrno(Errno.NOTTY) { KibiTty.size(ctx, notTty) }
        assertErrno(Errno.NOTTY) { KibiTty.foreground(ctx, notTty) }
        assertErrno(Errno.NOTTY) { KibiTty.setForeground(ctx, notTty, 1) }
        for (fd in listOf(200L, 0x8000_0000L, 0xffff_ffffL)) {
            assertErrno(Errno.BADF) { KibiTty.mode(ctx, fd) }
            assertErrno(Errno.BADF) { KibiTty.setMode(ctx, fd, 0) }
            assertErrno(Errno.BADF) { KibiTty.size(ctx, fd) }
            assertErrno(Errno.BADF) { KibiTty.foreground(ctx, fd) }
            assertErrno(Errno.BADF) { KibiTty.setForeground(ctx, fd, 1) }
        }
        // 失敗してもモードは変わらない
        assertEquals(TtyMode.ALL, KibiTty.mode(ctx, 0))
    }

    @Test
    fun `get-sizeはresizeConsole後の大きさと世代を返す`() = inProcess(setup = { it.resizeConsole(100, 30) }) { ctx, m ->
        assertEquals(TtySizeInfo(TtySize(100, 30), 1), KibiTty.size(ctx, 0))
        m.resizeConsole(120, 40)
        m.resizeConsole(120, 40)
        assertEquals(TtySizeInfo(TtySize(120, 40), 2), KibiTty.size(ctx, 2))
    }

    @Test
    fun `set-foregroundは0と正の値を受け、2の31乗以上はinvalid`() = inProcess { ctx, _ ->
        assertEquals(0, KibiTty.foreground(ctx, 0))
        KibiTty.setForeground(ctx, 0, 5)
        assertEquals(5, KibiTty.foreground(ctx, 1))
        KibiTty.setForeground(ctx, 2, Int.MAX_VALUE.toLong())
        assertEquals(Int.MAX_VALUE, KibiTty.foreground(ctx, 0))
        for (pid in listOf(0x8000_0000L, 0xffff_ffffL)) assertErrno(Errno.INVAL) { KibiTty.setForeground(ctx, 0, pid) }
        // pid を先に確かめる
        assertErrno(Errno.INVAL) { KibiTty.setForeground(ctx, 200, 0x8000_0000L) }
        assertEquals(Int.MAX_VALUE, KibiTty.foreground(ctx, 0))
        KibiTty.setForeground(ctx, 0, 0)
        assertEquals(0, KibiTty.foreground(ctx, 0))
    }

    @Test
    fun `Ctrl+Cはset-foregroundで決めたpidに届く`() {
        val received = Collections.synchronizedList(ArrayList<Pair<TtySignal, Int>>())
        inProcess(setup = { m -> m.ttySignalHandler = TtySignalHandler { _, s, fg -> received += s to fg } }) { ctx, m ->
            KibiTty.setForeground(ctx, 0, 42)
            m.offerConsoleInput(byteArrayOf(0x03))
            KibiTty.setForeground(ctx, 0, 0)
            m.offerConsoleInput(byteArrayOf(0x03))
        }
        assertEquals(listOf(TtySignal.INTERRUPT to 42, TtySignal.INTERRUPT to 0), received.toList())
    }
}
