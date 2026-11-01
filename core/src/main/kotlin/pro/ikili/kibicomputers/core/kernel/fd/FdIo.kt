package pro.ikili.kibicomputers.core.kernel.fd

import pro.ikili.kibicomputers.core.kernel.KernelState
import pro.ikili.kibicomputers.core.kernel.ProcessKilledException
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.io.ReadResult
import pro.ikili.kibicomputers.core.kernel.io.WriteResult
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.tty.Tty
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.OpenFile
import pro.ikili.kibicomputers.core.kernel.vfs.Vfs
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.wait.WakeSink

/** fd の読み書きの結果 */
internal sealed interface IoResult {
    /** [count] バイト読めた・書けた */
    data class Bytes(val count: Int) : IoResult

    /** 今はできない。[key] がそろったらやり直す（`BLOCKING` は `ctx.block`、`WAIT_POINT` は `StepResult.Wait`） */
    data class WouldBlock(val key: WaitKey) : IoResult

    /** 読み: 終わり（書く側が閉じた、ファイルの終わり） */
    data object Eof : IoResult

    /** 失敗（`BADF`・`PIPE`・`FAULT`・ファイルシステムのエラーなど） */
    data class Error(val errno: Errno) : IoResult
}

/** fd の待ちの判定（[FdIo.pollLocked] の結果） */
internal sealed interface FdReadiness {
    /** そろっている。[nbytes] は読める・書けるバイト数の目安（バイトでないものは 1）、[hangup] はパイプの相手側が閉じた */
    data class Ready(val nbytes: Long, val hangup: Boolean = false) : FdReadiness

    /** まだ。[key] がそろったら判定し直す */
    data class Pending(val key: WaitKey) : FdReadiness

    /** 待てない（開いていない・権利がない fd は `BADF`、その条件を待てない種類は `INVAL`） */
    data class Error(val errno: Errno) : FdReadiness
}

/**
 * fd を通した読み書き。fd が指すオブジェクトの種類（`ByteStream`、`Tty`、`OpenFile`）で振り分ける。ABI 層（p1 の `fd_read`・`fd_write`、
 * p2 の stream）はこれを使い、[IoResult] を自分の ABI の値に直す。
 *
 * fd の権利（[HandleRights]）がなければ `BADF`。読み書きを持たない種類のオブジェクトは `INVAL`。
 * 燃料の課金はしない（ABI 層が `ctx.chargeFuel` で行う）。
 */
internal object FdIo {
    /** 待たずに読む（カーネルロックは自分で取る） */
    fun read(ctx: ProcessContext, fd: Int, dst: ByteArray, off: Int, len: Int): IoResult =
        ctx.withKernelLock { readLocked(ctx.kernel, ctx.vfs, ctx.wakeSink, ctx.pid, fd, dst, off, len) }

    /** 待たずに書く（カーネルロックは自分で取る） */
    fun write(ctx: ProcessContext, fd: Int, src: ByteArray, off: Int, len: Int): IoResult =
        ctx.withKernelLock { writeLocked(ctx.kernel, ctx.vfs, ctx.wakeSink, ctx.pid, fd, src, off, len) }

    /** 読めるまで待って読む（`BLOCKING` のプロセスのスレッドから）。[IoResult.WouldBlock] は返さない */
    @Throws(ProcessKilledException::class)
    fun readBlocking(ctx: ProcessContext, fd: Int, dst: ByteArray, off: Int, len: Int): IoResult {
        while (true) {
            val r = read(ctx, fd, dst, off, len)
            if (r !is IoResult.WouldBlock) return r
            ctx.block(listOf(r.key))
        }
    }

    /** 少しでも書けるまで待って書く（`BLOCKING` のプロセスのスレッドから）。一部だけ書けることがある。[IoResult.WouldBlock] は返さない */
    @Throws(ProcessKilledException::class)
    fun writeBlocking(ctx: ProcessContext, fd: Int, src: ByteArray, off: Int, len: Int): IoResult {
        while (true) {
            val r = write(ctx, fd, src, off, len)
            if (r !is IoResult.WouldBlock) return r
            ctx.block(listOf(r.key))
        }
    }

    /**
     * カーネルロックの中で、プロセス [pid] の fd [fd] が [condition]（`READABLE` か `WRITABLE`）でそろっているか判定する（#72、ADR 0072）。
     * 何も取り出さない（キューのイベントもタイマーの回数も残る）。p1 の `poll_oneoff`、p2 の `pollable`、p3 の waitable-set が使う。
     *
     * - fd が開いていない、fd にその権利（[HandleRights]）がない: `BADF`
     * - パイプ（[ByteStream]）: 読みは読めるバイト（`nbytes`）、書きは空き。相手側が閉じていれば `hangup`
     * - コンソール（[Tty]）: 読みは今読めるバイト、書きは出力の空き
     * - ファイル・ディレクトリ（[OpenFile]）: 常にそろう（POSIX の `poll` と同じ）。ファイルの読みは残りのバイト、ほかは 0
     * - そのほかの待てる種類（イベントキュー、タイマー）: [pro.ikili.kibicomputers.core.kernel.obj.KernelObject.isReady] で、`nbytes` は 1
     * - その条件を待てない種類: `INVAL`
     */
    fun pollLocked(kernel: KernelState, vfs: Vfs, pid: Int, fd: Int, condition: WaitCondition): FdReadiness {
        require(condition == WaitCondition.READABLE || condition == WaitCondition.WRITABLE) { "fd で待てる条件ではない: $condition" }
        val reading = condition == WaitCondition.READABLE
        val process = checkNotNull(kernel.processes[pid]) { "プロセス $pid がない" }
        // 判定は状態を変えないので、知らせ口は使わない
        val table = FdTable(process, kernel.objects, WakeSink.NONE)
        return try {
            val entry = table.entry(fd)
            if (entry.rights and (if (reading) HandleRights.READ else HandleRights.WRITE) == 0) return FdReadiness.Error(Errno.BADF)
            when (val obj = table.objectOf(fd)) {
                is ByteStream -> when {
                    !obj.isReady(condition) -> FdReadiness.Pending(WaitKey(obj.id, condition))
                    reading -> FdReadiness.Ready(obj.available.toLong(), hangup = obj.writeClosed)
                    obj.readClosed -> FdReadiness.Ready(0, hangup = true)
                    else -> FdReadiness.Ready((obj.capacity - obj.available).toLong())
                }
                is Tty -> when {
                    !obj.isReady(condition) -> FdReadiness.Pending(WaitKey(obj.id, condition))
                    reading -> FdReadiness.Ready(obj.inputAvailable.toLong())
                    else -> FdReadiness.Ready(obj.outputFree.toLong())
                }
                is OpenFile -> when {
                    obj.isDirectory || !reading -> FdReadiness.Ready(0)
                    else -> FdReadiness.Ready(maxOf(0L, vfs.stat(obj).size - obj.position))
                }
                else -> when {
                    !obj.canWait(condition) -> FdReadiness.Error(Errno.INVAL)
                    obj.isReady(condition) -> FdReadiness.Ready(1)
                    else -> FdReadiness.Pending(WaitKey(obj.id, condition))
                }
            }
        } catch (e: FsException) {
            FdReadiness.Error(e.errno)
        }
    }

    /** カーネルロックの中で、プロセス [pid] の fd [fd] から読む */
    fun readLocked(kernel: KernelState, vfs: Vfs, sink: WakeSink, pid: Int, fd: Int, dst: ByteArray, off: Int, len: Int): IoResult {
        if (off < 0 || len < 0 || off.toLong() + len > dst.size) return IoResult.Error(Errno.FAULT)
        val process = checkNotNull(kernel.processes[pid]) { "プロセス $pid がない" }
        val table = FdTable(process, kernel.objects, sink)
        return try {
            val entry = table.entry(fd)
            if (entry.rights and HandleRights.READ == 0) return IoResult.Error(Errno.BADF)
            when (val obj = table.objectOf(fd)) {
                is ByteStream -> when (val r = obj.read(dst, off, len, sink)) {
                    is ReadResult.Bytes -> IoResult.Bytes(r.count)
                    ReadResult.WouldBlock -> IoResult.WouldBlock(WaitKey(obj.id, WaitCondition.READABLE))
                    ReadResult.Eof -> IoResult.Eof
                }
                is Tty -> when (val r = obj.read(dst, off, len, sink)) {
                    is ReadResult.Bytes -> IoResult.Bytes(r.count)
                    ReadResult.WouldBlock -> IoResult.WouldBlock(WaitKey(obj.id, WaitCondition.READABLE))
                    ReadResult.Eof -> IoResult.Eof
                }
                is OpenFile -> {
                    val n = vfs.read(obj, dst, off, len)
                    if (n == 0 && len > 0) IoResult.Eof else IoResult.Bytes(n)
                }
                else -> IoResult.Error(Errno.INVAL)
            }
        } catch (e: FsException) {
            IoResult.Error(e.errno)
        }
    }

    /** カーネルロックの中で、プロセス [pid] の fd [fd] へ書く */
    fun writeLocked(kernel: KernelState, vfs: Vfs, sink: WakeSink, pid: Int, fd: Int, src: ByteArray, off: Int, len: Int): IoResult {
        if (off < 0 || len < 0 || off.toLong() + len > src.size) return IoResult.Error(Errno.FAULT)
        val process = checkNotNull(kernel.processes[pid]) { "プロセス $pid がない" }
        val table = FdTable(process, kernel.objects, sink)
        return try {
            val entry = table.entry(fd)
            if (entry.rights and HandleRights.WRITE == 0) return IoResult.Error(Errno.BADF)
            when (val obj = table.objectOf(fd)) {
                is ByteStream -> when (val r = obj.write(src, off, len, sink)) {
                    is WriteResult.Bytes -> IoResult.Bytes(r.count)
                    WriteResult.WouldBlock -> IoResult.WouldBlock(WaitKey(obj.id, WaitCondition.WRITABLE))
                    WriteResult.Closed -> IoResult.Error(Errno.PIPE)
                }
                is Tty -> when (val r = obj.write(src, off, len, sink)) {
                    is WriteResult.Bytes -> IoResult.Bytes(r.count)
                    WriteResult.WouldBlock -> IoResult.WouldBlock(WaitKey(obj.id, WaitCondition.WRITABLE))
                    WriteResult.Closed -> IoResult.Error(Errno.PIPE)
                }
                is OpenFile -> IoResult.Bytes(vfs.write(obj, src, off, len))
                else -> IoResult.Error(Errno.INVAL)
            }
        } catch (e: FsException) {
            IoResult.Error(e.errno)
        }
    }
}
