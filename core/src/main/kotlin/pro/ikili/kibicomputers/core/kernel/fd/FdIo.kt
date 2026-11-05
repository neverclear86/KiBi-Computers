package pro.ikili.kibicomputers.core.kernel.fd

import pro.ikili.kibicomputers.core.kernel.KernelState
import pro.ikili.kibicomputers.core.kernel.ProcessKilledException
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.io.PipeEnd
import pro.ikili.kibicomputers.core.kernel.io.ReadResult
import pro.ikili.kibicomputers.core.kernel.io.WriteResult
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.obj.KernelObject
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
 * fd を通した読み書き。fd が指すオブジェクトの種類（`ByteStream`、パイプの端 `PipeEnd`、`Tty`、`OpenFile`）で振り分ける。ABI 層（p1 の `fd_read`・`fd_write`、
 * p2 の stream）はこれを使い、[IoResult] を自分の ABI の値に直す。
 *
 * fd の権利（[HandleRights]）がなければ `BADF`。読み書きを持たない種類のオブジェクトは `INVAL`。
 * パイプの端（[PipeEnd]）は中身の [ByteStream] として読み書きする。向きの違う端（読む端への書き込み、書く端からの読み）は、権利があっても `BADF`。
 * 燃料の課金はしない（ABI 層が `ctx.chargeFuel` で行う）。
 */
internal object FdIo {
    /**
     * 待たずに読む（カーネルロックは自分で取る）。ファイル（[OpenFile]）は、ロックの中で位置を読み、ロックの外で読んで、
     * ロックの中で位置を進める（ファイルシステムの I/O をロックの外で行うため。#52。ADR 0052）
     */
    fun read(ctx: ProcessContext, fd: Int, dst: ByteArray, off: Int, len: Int): IoResult {
        if (off < 0 || len < 0 || off.toLong() + len > dst.size) return IoResult.Error(Errno.FAULT)
        var file: OpenFile? = null
        var position = 0L
        val r = ctx.withKernelLock {
            val f = openFileLocked(ctx.kernel, ctx.pid, fd, HandleRights.READ)
            if (f == null) return@withKernelLock readLocked(ctx.kernel, ctx.vfs, ctx.wakeSink, ctx.pid, fd, dst, off, len)
            file = f
            position = f.position
            null
        }
        if (r != null) return r
        val f = file!!
        val n = try {
            ctx.vfs.pread(f, position, dst, off, len)
        } catch (e: FsException) {
            return IoResult.Error(e.errno)
        }
        ctx.withKernelLock { f.position = position + n }
        return if (n == 0 && len > 0) IoResult.Eof else IoResult.Bytes(n)
    }

    /** [fd] が読み・書きの権利 [right] を持つファイル（[OpenFile]）を指していれば、それ（カーネルロックの中）。ほかは `null` */
    private fun openFileLocked(kernel: KernelState, pid: Int, fd: Int, right: Int): OpenFile? {
        val process = kernel.processes[pid] ?: return null
        val entry = process.handles[fd] ?: return null
        if (entry.rights and right == 0) return null
        return kernel.objects[entry.objectId] as? OpenFile
    }

    // ファイルへの書き込み（カーネルロックの外。位置はロックの中で読み書きする）
    private fun writeFile(ctx: ProcessContext, file: OpenFile, position: Long, src: ByteArray, off: Int, len: Int): IoResult = try {
        if (file.append) {
            val w = ctx.vfs.append(file, src, off, len)
            ctx.withKernelLock { file.position = w.position + w.count }
            IoResult.Bytes(w.count)
        } else {
            val n = ctx.vfs.pwrite(file, position, src, off, len)
            ctx.withKernelLock { file.position = position + n }
            IoResult.Bytes(n)
        }
    } catch (e: FsException) {
        IoResult.Error(e.errno)
    }

    /**
     * 待たずに書く（カーネルロックは自分で取る）。コンソールの TTY に書けたら、たまった出力をこのスレッドで表示デバイスに書く
     * （[ProcessContext.flushConsole]。#355）。書くことは、書いたのと同じカーネルロックの中で予約する
     * （[ProcessContext.reserveConsoleFlushLocked]。その間に、メインスレッドが書き手のいない出力として取り出さないように。#419）
     */
    fun write(ctx: ProcessContext, fd: Int, src: ByteArray, off: Int, len: Int): IoResult {
        if (off < 0 || len < 0 || off.toLong() + len > src.size) return IoResult.Error(Errno.FAULT)
        var flush = false
        var file: OpenFile? = null
        var position = 0L
        val r = ctx.withKernelLock {
            val f = openFileLocked(ctx.kernel, ctx.pid, fd, HandleRights.WRITE)
            if (f != null) {
                file = f
                position = f.position
                return@withKernelLock null
            }
            val console = isConsoleLocked(ctx.kernel, ctx.pid, fd)
            val written = writeLocked(ctx.kernel, ctx.vfs, ctx.wakeSink, ctx.pid, fd, src, off, len)
            if (console && written is IoResult.Bytes && written.count > 0) {
                ctx.reserveConsoleFlushLocked()
                flush = true
            }
            written
        }
        if (r == null) return writeFile(ctx, file!!, position, src, off, len)
        if (flush) ctx.flushConsole()
        return r
    }

    // fd [fd] がコンソールの TTY を指すか
    private fun isConsoleLocked(kernel: KernelState, pid: Int, fd: Int): Boolean {
        val id = kernel.consoleTtyId
        if (id == 0) return false
        val process = kernel.processes[pid] ?: return false
        val entry = process.handles[fd] ?: return false
        return entry.objectId == id
    }

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
     * - パイプ（[ByteStream]、[PipeEnd]）: 読みは読めるバイト（`nbytes`）、書きは空き。相手側が閉じていれば `hangup`。
     *   向きの違うパイプの端は `BADF`
     * - コンソール（[Tty]）: 読みは今読めるバイト、書きは出力の空き
     * - ファイル・ディレクトリ（[OpenFile]）: 常にそろう（POSIX の `poll` と同じ）。ファイルの読みは残りのバイトの目安（I/O をしない [Vfs.sizeHintLocked]）、ほかは 0
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
            val obj = resolvePipe(kernel, table.objectOf(fd), reading) ?: return FdReadiness.Error(Errno.BADF)
            when (obj) {
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
                    else -> FdReadiness.Ready(maxOf(0L, vfs.sizeHintLocked(obj) - obj.position))
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

    /**
     * カーネルロックの中で、プロセス [pid] の fd [fd] から読む。ファイル（[OpenFile]）は [vfs] でその場で読むので、
     * カーネルロックを持たない VFS（マシンを使わないテスト）でだけ使う。プロセスからは [read] を使う
     */
    fun readLocked(kernel: KernelState, vfs: Vfs, sink: WakeSink, pid: Int, fd: Int, dst: ByteArray, off: Int, len: Int): IoResult {
        if (off < 0 || len < 0 || off.toLong() + len > dst.size) return IoResult.Error(Errno.FAULT)
        val process = checkNotNull(kernel.processes[pid]) { "プロセス $pid がない" }
        val table = FdTable(process, kernel.objects, sink)
        return try {
            val entry = table.entry(fd)
            if (entry.rights and HandleRights.READ == 0) return IoResult.Error(Errno.BADF)
            val obj = resolvePipe(kernel, table.objectOf(fd), reading = true) ?: return IoResult.Error(Errno.BADF)
            when (obj) {
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

    /** カーネルロックの中で、プロセス [pid] の fd [fd] へ書く。ファイルの扱いは [readLocked] と同じ。プロセスからは [write] を使う */
    fun writeLocked(kernel: KernelState, vfs: Vfs, sink: WakeSink, pid: Int, fd: Int, src: ByteArray, off: Int, len: Int): IoResult {
        if (off < 0 || len < 0 || off.toLong() + len > src.size) return IoResult.Error(Errno.FAULT)
        val process = checkNotNull(kernel.processes[pid]) { "プロセス $pid がない" }
        val table = FdTable(process, kernel.objects, sink)
        return try {
            val entry = table.entry(fd)
            if (entry.rights and HandleRights.WRITE == 0) return IoResult.Error(Errno.BADF)
            val obj = resolvePipe(kernel, table.objectOf(fd), reading = false) ?: return IoResult.Error(Errno.BADF)
            when (obj) {
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

    // パイプの端なら中身の ByteStream に置き換える。向きが違えば null（`BADF`）。ほかの種類はそのまま
    private fun resolvePipe(kernel: KernelState, obj: KernelObject, reading: Boolean): KernelObject? {
        if (obj !is PipeEnd) return obj
        if (obj.reading != reading) return null
        return checkNotNull(kernel.objects[obj.streamId] as? ByteStream) { "パイプの端 ${obj.id} の中身 ${obj.streamId} がない" }
    }
}
