package pro.ikili.kibicomputers.core.kernel.tty

import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.fsError

/**
 * fd を通した TTY の操作（ABI 層が使う。#60、ADR 0060）。カーネルロックは自分で取る。
 *
 * fd が開いていなければ `BADF`、TTY でなければ `NOTTY`。fd の権利（読み・書き）は問わない（termios と同じ）。
 * WASM にどう見せるか（`kibi:*` の WIT と p1 向け import）は #73。
 */
internal object TtyControl {
    /** fd [fd] が TTY を指すか（開いていなければ `false`） */
    fun isTty(ctx: ProcessContext, fd: Int): Boolean = ctx.withKernelLock {
        try {
            table(ctx).objectOf(fd) is Tty
        } catch (e: FsException) {
            false
        }
    }

    /** モード */
    @Throws(FsException::class)
    fun mode(ctx: ProcessContext, fd: Int): TtyMode = withTty(ctx, fd) { it.mode }

    /** モードを変える */
    @Throws(FsException::class)
    fun setMode(ctx: ProcessContext, fd: Int, mode: TtyMode): Unit = withTty(ctx, fd) { it.setMode(mode, ctx.wakeSink) }

    /** 画面の大きさ */
    @Throws(FsException::class)
    fun size(ctx: ProcessContext, fd: Int): TtySize = withTty(ctx, fd) { it.size }

    /** 画面の大きさが変わった回数（前に覚えた値と比べて、変わったことを知る） */
    @Throws(FsException::class)
    fun sizeGeneration(ctx: ProcessContext, fd: Int): Long = withTty(ctx, fd) { it.sizeGeneration }

    /** Ctrl+C を送る相手を [pid] にする（0 = なし。負は `INVAL`） */
    @Throws(FsException::class)
    fun setForeground(ctx: ProcessContext, fd: Int, pid: Int) {
        if (pid < 0) fsError(Errno.INVAL, "pid は 0 以上")
        withTty(ctx, fd) { it.foregroundPid = pid }
    }

    /** Ctrl+C を送る相手（0 = なし） */
    @Throws(FsException::class)
    fun foreground(ctx: ProcessContext, fd: Int): Int = withTty(ctx, fd) { it.foregroundPid }

    private fun table(ctx: ProcessContext): FdTable {
        val process = checkNotNull(ctx.kernel.processes[ctx.pid]) { "プロセス ${ctx.pid} がない" }
        return FdTable(process, ctx.kernel.objects, ctx.wakeSink)
    }

    private inline fun <T> withTty(ctx: ProcessContext, fd: Int, crossinline block: (Tty) -> T): T = ctx.withKernelLock {
        val tty = table(ctx).objectOf(fd) as? Tty ?: fsError(Errno.NOTTY, "fd $fd は TTY ではない")
        block(tty)
    }
}
