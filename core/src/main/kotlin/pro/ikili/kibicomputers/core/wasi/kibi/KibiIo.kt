package pro.ikili.kibicomputers.core.wasi.kibi

import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.fd.PipeFds
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.fsError

/**
 * 独自拡張 `kibi:io/fd`・`kibi:io/pipe`（#75。ADR 0075）の意味。ABI の層（p1 の import）に依らない部分。
 *
 * - `kibi:io/pipe.create`: [pipe]
 * - `kibi:io/fd.dup`: [dup]
 * - `kibi:io/fd.dup-to`: [dupTo]
 *
 * fd は WIT の `u32` を符号なしで広げた値で受け、`Int` に入らない（2^31 以上）ものは `BADF` にする。
 * 失敗は [FsException]（`BADF`・`MFILE`）。どれもカーネルの fd 表（[FdTable]）の操作そのままで、表を変えずに失敗する。
 * パイプの数は fd の上限で抑えられる（ADR 0059）ので、`NFILE` は返さない。
 */
internal object KibiIo {
    /** パイプを作り、両端の fd を返す（読む端は `READ`、書く端は `WRITE`、フラグは 0）。空き番号が2つなければ、何も作らずに `MFILE` */
    @Throws(FsException::class)
    fun pipe(ctx: ProcessContext): PipeFds = ctx.withKernelLock { table(ctx).pipe() }

    /** [fd] をいちばん小さい空き番号に複製して返す。権利とフラグも写す。開いていない・範囲外なら `BADF`、満杯なら `MFILE` */
    @Throws(FsException::class)
    fun dup(ctx: ProcessContext, fd: Long): Int {
        val from = fdOf(fd)
        return ctx.withKernelLock { table(ctx).dup(from) }
    }

    /**
     * [fd] を [to] に複製する。[to] が開いていれば、種類によらず `fd_close` と同じに閉じてから。同じ番号なら、開いているか確かめるだけ。
     * [fd] が開いていない、[fd]・[to] が範囲外なら `BADF`（このとき [to] は閉じない）
     */
    @Throws(FsException::class)
    fun dupTo(ctx: ProcessContext, fd: Long, to: Long) {
        val from = fdOf(fd)
        val dest = fdOf(to)
        ctx.withKernelLock { table(ctx).dup2(from, dest) }
    }

    private fun table(ctx: ProcessContext) = FdTable(checkNotNull(ctx.kernel.processes[ctx.pid]), ctx.kernel.objects, ctx.wakeSink)

    /** `u32` を符号なしで広げた [fd] を fd 番号にする。`Int` に入らなければ `BADF` */
    @Throws(FsException::class)
    private fun fdOf(fd: Long): Int {
        if (fd !in 0L..Int.MAX_VALUE.toLong()) fsError(Errno.BADF, "fd $fd は範囲外")
        return fd.toInt()
    }
}
