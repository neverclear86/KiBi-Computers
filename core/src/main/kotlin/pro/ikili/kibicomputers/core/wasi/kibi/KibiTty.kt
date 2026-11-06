package pro.ikili.kibicomputers.core.wasi.kibi

import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.tty.TtyControl
import pro.ikili.kibicomputers.core.kernel.tty.TtyMode
import pro.ikili.kibicomputers.core.kernel.tty.TtySizeInfo
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.fsError

/**
 * 独自拡張 `kibi:io/tty`（#427。ADR 0427）の意味。ABI の層（p1 の import、後の p2/p3）に依らない部分。
 *
 * - `is-tty`: [isTty]
 * - `get-mode`・`set-mode`: [mode]・[setMode]
 * - `get-size`: [size]
 * - `get-foreground`・`set-foreground`: [foreground]・[setForeground]
 *
 * fd と pid は WIT の `u32` を符号なしで広げた値で受ける。fd が `Int` に入らない（2^31 以上）ものは、
 * カーネル（[TtyControl]）を呼ばずに `BADF`。開いていなければ `BADF`、TTY でなければ `NOTTY`（[TtyControl] のまま）。
 */
internal object KibiTty {
    /** [fd] が TTY を指すか。範囲外・閉じていれば `false` */
    fun isTty(ctx: ProcessContext, fd: Long): Boolean = fd in FD_RANGE && TtyControl.isTty(ctx, fd.toInt())

    /** モードのビット（[TtyMode.bits]。`flags mode` のビットと同じ） */
    @Throws(FsException::class)
    fun mode(ctx: ProcessContext, fd: Long): Int = TtyControl.mode(ctx, fdOf(fd)).bits

    /** モードを [bits] にする。[bits] は宣言したビットだけ（`P1Canonical.liftFlags` 済み）。ほかのビットは読まない */
    @Throws(FsException::class)
    fun setMode(ctx: ProcessContext, fd: Long, bits: Int) {
        val f = fdOf(fd)
        TtyControl.setMode(ctx, f, TtyMode(bits and TtyMode.ALL))
    }

    /** 大きさと世代（同じ瞬間の組） */
    @Throws(FsException::class)
    fun size(ctx: ProcessContext, fd: Long): TtySizeInfo = TtyControl.sizeInfo(ctx, fdOf(fd))

    /** Ctrl+C・Ctrl+\ を送る相手（0 = なし） */
    @Throws(FsException::class)
    fun foreground(ctx: ProcessContext, fd: Long): Int = TtyControl.foreground(ctx, fdOf(fd))

    /**
     * Ctrl+C・Ctrl+\ を送る相手を [pid] にする（0 = なし）。[pid] が 2^31 以上なら `INVAL`。生きているかは確かめない。
     * [pid] を先に確かめる（[TtyControl.setForeground] と同じ順。fd も pid も不正なら `INVAL`）
     */
    @Throws(FsException::class)
    fun setForeground(ctx: ProcessContext, fd: Long, pid: Long) {
        if (pid !in 0L..Int.MAX_VALUE.toLong()) fsError(Errno.INVAL, "pid $pid は範囲外")
        TtyControl.setForeground(ctx, fdOf(fd), pid.toInt())
    }

    private val FD_RANGE = 0L..Int.MAX_VALUE.toLong()

    /** `u32` を符号なしで広げた [fd] を fd 番号にする。`Int` に入らなければ `BADF` */
    @Throws(FsException::class)
    private fun fdOf(fd: Long): Int {
        if (fd !in FD_RANGE) fsError(Errno.BADF, "fd $fd は範囲外")
        return fd.toInt()
    }
}
