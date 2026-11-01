package pro.ikili.kibicomputers.core.wasi.p1

import pro.ikili.kibicomputers.core.kernel.fd.FdIo
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.fd.IoResult
import pro.ikili.kibicomputers.core.kernel.obj.HandleEntry
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.obj.KernelObject
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.tty.Tty
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FileType
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.OpenFile
import pro.ikili.kibicomputers.core.kernel.vfs.Whence
import pro.ikili.kibicomputers.core.kernel.vfs.fsError
import pro.ikili.kibicomputers.core.runtime.WasmMemory

/**
 * WASI p1 の `fd_*`（#70）。カーネルの fd 表（`FdTable`）・読み書き（`FdIo`）・VFS の上の薄い層。
 *
 * 各関数は p1 の `errno` の値を返す（成功は 0）。ゲストのメモリの範囲外は、読み書きの前に確かめて `FAULT` にする
 * （一部を読み書きしてから失敗して、データを失うことがないように）。
 *
 * - [blocking] が `true`（`BLOCKING` のプロセス）なら、ストリームの読み書きはそろうまで `ctx.block` で待つ。
 *   fd に `NONBLOCK`（[HandleEntry.flags] に p1 の `fdflags` のビットで持つ）があるか、[blocking] が `false` なら `AGAIN`
 * - 燃料: 呼び出しごとに [CALL_FUEL]、読み書きしたバイト [BYTES_PER_FUEL] ごとに 1（仮の値。確定は #251）
 */
internal class WasiP1Fd(private val ctx: ProcessContext, private val blocking: Boolean) {
    // ---- 読み書き ----

    /** `fd_read(fd, iovs, iovs_len, nread*)` */
    fun read(memory: WasmMemory, fd: Int, iovs: Int, iovsLen: Int, nreadPtr: Int): Int {
        val vec = IoVecs.load(memory, iovs, iovsLen) ?: return Errno.FAULT.code
        if (!P1.inBounds(memory, nreadPtr, 4)) return Errno.FAULT.code
        val buf = ByteArray(vec.total)
        val n = when (val r = readStream(fd, buf)) {
            is IoResult.Bytes -> r.count
            IoResult.Eof -> 0
            is IoResult.WouldBlock -> return Errno.AGAIN.code
            is IoResult.Error -> return r.errno.code
        }
        vec.scatter(memory, buf, n)
        memory.writeI32(nreadPtr, n)
        chargeBytes(n)
        return 0
    }

    /** `fd_write(fd, iovs, iovs_len, nwritten*)`。待つ fd なら、全部書くまで繰り返す */
    fun write(memory: WasmMemory, fd: Int, iovs: Int, iovsLen: Int, nwrittenPtr: Int): Int {
        val vec = IoVecs.load(memory, iovs, iovsLen) ?: return Errno.FAULT.code
        if (!P1.inBounds(memory, nwrittenPtr, 4)) return Errno.FAULT.code
        val buf = vec.gather(memory)
        if (buf.isEmpty()) {
            val err = checkRight(fd, HandleRights.WRITE)
            if (err != 0) return err
            memory.writeI32(nwrittenPtr, 0)
            return 0
        }
        val wait = shouldWait(fd)
        var done = 0
        while (done < buf.size) {
            val r = if (wait) FdIo.writeBlocking(ctx, fd, buf, done, buf.size - done) else FdIo.write(ctx, fd, buf, done, buf.size - done)
            when (r) {
                is IoResult.Bytes -> done += r.count
                is IoResult.WouldBlock -> if (done == 0) return Errno.AGAIN.code else break
                is IoResult.Error -> if (done == 0) return r.errno.code else break
                IoResult.Eof -> break
            }
            if (!wait) break
        }
        memory.writeI32(nwrittenPtr, done)
        chargeBytes(done)
        return 0
    }

    /** `fd_pread(fd, iovs, iovs_len, offset, nread*)`。位置は変えない */
    fun pread(memory: WasmMemory, fd: Int, iovs: Int, iovsLen: Int, offset: Long, nreadPtr: Int): Int {
        val vec = IoVecs.load(memory, iovs, iovsLen) ?: return Errno.FAULT.code
        if (!P1.inBounds(memory, nreadPtr, 4)) return Errno.FAULT.code
        if (offset < 0) return Errno.INVAL.code
        val buf = ByteArray(vec.total)
        val n = locked(fd) { entry, obj ->
            if (entry.rights and HandleRights.READ == 0) fsError(Errno.BADF)
            ctx.vfs.pread(file(obj), offset, buf, 0, buf.size)
        }
        vec.scatter(memory, buf, n)
        memory.writeI32(nreadPtr, n)
        chargeBytes(n)
        return 0
    }

    /** `fd_pwrite(fd, iovs, iovs_len, offset, nwritten*)`。位置は変えない（追記の fd でも [offset] に書く） */
    fun pwrite(memory: WasmMemory, fd: Int, iovs: Int, iovsLen: Int, offset: Long, nwrittenPtr: Int): Int {
        val vec = IoVecs.load(memory, iovs, iovsLen) ?: return Errno.FAULT.code
        if (!P1.inBounds(memory, nwrittenPtr, 4)) return Errno.FAULT.code
        if (offset < 0) return Errno.INVAL.code
        val buf = vec.gather(memory)
        val n = locked(fd) { entry, obj ->
            if (entry.rights and HandleRights.WRITE == 0) fsError(Errno.BADF)
            ctx.vfs.pwrite(file(obj), offset, buf, 0, buf.size)
        }
        memory.writeI32(nwrittenPtr, n)
        chargeBytes(n)
        return 0
    }

    // ---- 位置 ----

    /** `fd_seek(fd, offset, whence, newoffset*)` */
    fun seek(memory: WasmMemory, fd: Int, offset: Long, whence: Int, newOffsetPtr: Int): Int {
        if (!P1.inBounds(memory, newOffsetPtr, 8)) return Errno.FAULT.code
        val w = when (whence) {
            P1.WHENCE_SET -> Whence.SET
            P1.WHENCE_CUR -> Whence.CUR
            P1.WHENCE_END -> Whence.END
            else -> return Errno.INVAL.code
        }
        val pos = locked(fd) { _, obj -> ctx.vfs.seek(file(obj), offset, w) }
        memory.writeI64(newOffsetPtr, pos)
        return 0
    }

    /** `fd_tell(fd, offset*)` */
    fun tell(memory: WasmMemory, fd: Int, offsetPtr: Int): Int {
        if (!P1.inBounds(memory, offsetPtr, 8)) return Errno.FAULT.code
        val pos = locked(fd) { _, obj ->
            val f = file(obj)
            if (f.isDirectory) fsError(Errno.ISDIR)
            f.position
        }
        memory.writeI64(offsetPtr, pos)
        return 0
    }

    // ---- 閉じる・番号 ----

    /** `fd_close(fd)` */
    fun close(fd: Int): Int = ctx.withKernelLock { errno { table().close(fd) } }

    /** `fd_renumber(from, to)`。[to] が開いていなければ、そこへ移す（カーネルの `FdTable.renumber` と同じ） */
    fun renumber(from: Int, to: Int): Int = ctx.withKernelLock { errno { table().renumber(from, to) } }

    // ---- 属性 ----

    /** `fd_fdstat_get(fd, fdstat*)` */
    fun fdstatGet(memory: WasmMemory, fd: Int, bufPtr: Int): Int {
        if (!P1.inBounds(memory, bufPtr, P1.FDSTAT_SIZE.toLong())) return Errno.FAULT.code
        val stat = locked(fd) { entry, obj -> fdstat(entry, obj) }
        val out = ByteArray(P1.FDSTAT_SIZE)
        memory.write(bufPtr, out)
        memory.writeU8(bufPtr, stat.fileType)
        memory.writeU8(bufPtr + 2, stat.flags and 0xff)
        memory.writeU8(bufPtr + 3, (stat.flags shr 8) and 0xff)
        memory.writeI64(bufPtr + 8, stat.rightsBase)
        memory.writeI64(bufPtr + 16, stat.rightsInheriting)
        return 0
    }

    /** `fd_fdstat_set_flags(fd, fdflags)`。`APPEND` と `NONBLOCK` を変えられる。`DSYNC`・`RSYNC`・`SYNC` は `NOTSUP` */
    fun fdstatSetFlags(fd: Int, flags: Int): Int {
        if (flags and P1.FDFLAGS_ALL.inv() != 0) return Errno.INVAL.code
        if (flags and (P1.FDFLAGS_DSYNC or P1.FDFLAGS_RSYNC or P1.FDFLAGS_SYNC) != 0) return Errno.NOTSUP.code
        return ctx.withKernelLock {
            errno {
                val table = table()
                val entry = table.entry(fd)
                val obj = table.objectOf(fd)
                if (obj is OpenFile && !obj.isDirectory) obj.append = flags and P1.FDFLAGS_APPEND != 0
                val nonblock = (flags and P1.FDFLAGS_NONBLOCK).toLong()
                table.setFlags(fd, (entry.flags and P1.FDFLAGS_NONBLOCK.toLong().inv()) or nonblock)
            }
        }
    }

    /** `fd_fdstat_set_rights(fd, base, inheriting)`。p1 の権利は持たない（p2 で無くなった）ので、fd を確かめて `NOTSUP` */
    fun fdstatSetRights(fd: Int): Int = ctx.withKernelLock {
        errno {
            table().entry(fd)
            fsError(Errno.NOTSUP)
        }
    }

    /** `fd_filestat_get(fd, filestat*)` */
    fun filestatGet(memory: WasmMemory, fd: Int, bufPtr: Int): Int {
        if (!P1.inBounds(memory, bufPtr, P1.FILESTAT_SIZE.toLong())) return Errno.FAULT.code
        val out = ByteArray(P1.FILESTAT_SIZE)
        locked(fd) { _, obj ->
            when (obj) {
                is OpenFile -> {
                    val st = ctx.vfs.stat(obj)
                    putI64(out, 0, st.device)
                    putI64(out, 8, st.node)
                    out[16] = P1.fileType(st.type).toByte()
                    putI64(out, 24, st.linkCount)
                    putI64(out, 32, st.size)
                    putI64(out, 40, st.accessedNanos)
                    putI64(out, 48, st.modifiedNanos)
                    putI64(out, 56, st.changedNanos)
                }
                else -> {
                    out[16] = streamFileType(obj).toByte()
                    putI64(out, 24, 1)
                }
            }
        }
        memory.write(bufPtr, out)
        return 0
    }

    /** `fd_filestat_set_size(fd, size)` */
    fun filestatSetSize(fd: Int, size: Long): Int {
        if (size < 0) return Errno.INVAL.code
        locked(fd) { entry, obj ->
            if (obj !is OpenFile) fsError(Errno.INVAL)
            if (entry.rights and HandleRights.WRITE == 0) fsError(Errno.BADF)
            ctx.vfs.setSize(obj, size)
        }
        return 0
    }

    /** `fd_filestat_set_times(fd, atim, mtim, fst_flags)` */
    fun filestatSetTimes(fd: Int, atim: Long, mtim: Long, fstFlags: Int): Int {
        val known = P1.FSTFLAGS_ATIM or P1.FSTFLAGS_ATIM_NOW or P1.FSTFLAGS_MTIM or P1.FSTFLAGS_MTIM_NOW
        if (fstFlags and known.inv() != 0) return Errno.INVAL.code
        if (fstFlags and P1.FSTFLAGS_ATIM != 0 && fstFlags and P1.FSTFLAGS_ATIM_NOW != 0) return Errno.INVAL.code
        if (fstFlags and P1.FSTFLAGS_MTIM != 0 && fstFlags and P1.FSTFLAGS_MTIM_NOW != 0) return Errno.INVAL.code
        val now = if (fstFlags and (P1.FSTFLAGS_ATIM_NOW or P1.FSTFLAGS_MTIM_NOW) != 0) ctx.environment.clock.realtimeNanos() else 0L
        val a = when {
            fstFlags and P1.FSTFLAGS_ATIM != 0 -> atim
            fstFlags and P1.FSTFLAGS_ATIM_NOW != 0 -> now
            else -> null
        }
        val m = when {
            fstFlags and P1.FSTFLAGS_MTIM != 0 -> mtim
            fstFlags and P1.FSTFLAGS_MTIM_NOW != 0 -> now
            else -> null
        }
        locked(fd) { _, obj ->
            if (obj !is OpenFile) fsError(Errno.NOTSUP)
            if (a != null || m != null) ctx.vfs.setTimes(obj, a, m)
        }
        return 0
    }

    // ---- ファイルの付随する操作 ----

    /** `fd_advise(fd, offset, len, advice)`。中身には効かない（確かめるだけ） */
    fun advise(fd: Int, offset: Long, len: Long, advice: Int): Int {
        if (advice !in 0..P1.ADVICE_MAX || offset < 0 || len < 0) return Errno.INVAL.code
        locked(fd) { _, obj -> if (obj !is OpenFile) fsError(Errno.SPIPE) }
        return 0
    }

    /** `fd_allocate(fd, offset, len)`。`offset + len` まで大きさを広げる（縮めない） */
    fun allocate(fd: Int, offset: Long, len: Long): Int {
        if (offset < 0 || len <= 0) return Errno.INVAL.code
        if (offset > Long.MAX_VALUE - len) return Errno.FBIG.code
        locked(fd) { entry, obj ->
            val f = file(obj)
            if (entry.rights and HandleRights.WRITE == 0) fsError(Errno.BADF)
            if (f.isDirectory) fsError(Errno.ISDIR)
            if (ctx.vfs.stat(f).size < offset + len) ctx.vfs.setSize(f, offset + len)
        }
        return 0
    }

    /** `fd_sync(fd)`・`fd_datasync(fd)` */
    fun sync(fd: Int): Int {
        locked(fd) { _, obj ->
            if (obj !is OpenFile) fsError(Errno.INVAL)
            ctx.vfs.sync(obj)
        }
        return 0
    }

    // ---- ディレクトリ ----

    /**
     * `fd_readdir(fd, buf, buf_len, cookie, bufused*)`。`.`・`..` の後に、VFS の中身を名前の順に並べる。
     * cookie は並びの番号（次の dirent の `d_next` は番号 + 1）。入りきらない最後の dirent は途中で切る（`bufused == buf_len`）
     */
    fun readdir(memory: WasmMemory, fd: Int, bufPtr: Int, bufLen: Int, cookie: Long, bufusedPtr: Int): Int {
        val cap = P1.u32(bufLen.toLong())
        if (!P1.inBounds(memory, bufPtr, cap) || !P1.inBounds(memory, bufusedPtr, 4)) return Errno.FAULT.code
        val entries = locked(fd) { _, obj ->
            val f = file(obj)
            if (!f.isDirectory) fsError(Errno.NOTDIR)
            val parent = f.path.parent
            val parentNode = if (parent == null) {
                f.node
            } else {
                try {
                    ctx.vfs.stat(parent).node
                } catch (_: FsException) {
                    0L
                }
            }
            val list = ArrayList<Dirent>()
            list += Dirent(".", P1.FILETYPE_DIRECTORY, f.node)
            list += Dirent("..", P1.FILETYPE_DIRECTORY, parentNode)
            for (e in ctx.vfs.readDirectory(f)) list += Dirent(e.name, P1.fileType(e.type), e.node)
            list
        }
        val out = java.io.ByteArrayOutputStream()
        if (cookie >= 0 && cookie < entries.size) {
            for (i in cookie.toInt() until entries.size) {
                if (out.size() >= cap) break
                val e = entries[i]
                val name = e.name.toByteArray(Charsets.UTF_8)
                val header = ByteArray(P1.DIRENT_SIZE)
                putI64(header, 0, i + 1L)
                putI64(header, 8, e.node)
                putI32(header, 16, name.size)
                header[20] = e.type.toByte()
                out.write(header)
                out.write(name)
            }
        }
        val bytes = out.toByteArray()
        val used = minOf(bytes.size.toLong(), cap).toInt()
        memory.write(bufPtr, bytes, 0, used)
        memory.writeI32(bufusedPtr, used)
        chargeBytes(used)
        return 0
    }

    /** `fd_prestat_get(fd, prestat*)`。ディレクトリの fd は preopen として答える（名前は開いたときの VFS の絶対パス） */
    fun prestatGet(memory: WasmMemory, fd: Int, bufPtr: Int): Int {
        if (!P1.inBounds(memory, bufPtr, P1.PRESTAT_SIZE.toLong())) return Errno.FAULT.code
        val name = prestatName(fd) ?: return Errno.BADF.code
        memory.write(bufPtr, ByteArray(P1.PRESTAT_SIZE))
        memory.writeU8(bufPtr, P1.PREOPENTYPE_DIR)
        memory.writeI32(bufPtr + 4, name.size)
        return 0
    }

    /** `fd_prestat_dir_name(fd, path, path_len)`。NUL は付けない。[pathLen] が名前より短ければ `NAMETOOLONG` */
    fun prestatDirName(memory: WasmMemory, fd: Int, pathPtr: Int, pathLen: Int): Int {
        val len = P1.u32(pathLen.toLong())
        if (!P1.inBounds(memory, pathPtr, len)) return Errno.FAULT.code
        val name = prestatName(fd) ?: return Errno.BADF.code
        if (len < name.size) return Errno.NAMETOOLONG.code
        memory.write(pathPtr, name)
        return 0
    }

    // ---- 中身 ----

    private class Dirent(val name: String, val type: Int, val node: Long)

    private class FdStat(val fileType: Int, val flags: Int, val rightsBase: Long, val rightsInheriting: Long)

    private fun prestatName(fd: Int): ByteArray? = ctx.withKernelLock {
        try {
            val obj = table().objectOf(fd)
            if (obj is OpenFile && obj.isDirectory) obj.path.toString().toByteArray(Charsets.UTF_8) else null
        } catch (_: FsException) {
            null
        }
    }

    private fun fdstat(entry: HandleEntry, obj: KernelObject): FdStat {
        val nonblock = (entry.flags and P1.FDFLAGS_NONBLOCK.toLong()).toInt()
        val canRead = entry.rights and HandleRights.READ != 0
        val canWrite = entry.rights and HandleRights.WRITE != 0
        return when (obj) {
            is OpenFile -> if (obj.isDirectory) {
                FdStat(P1.FILETYPE_DIRECTORY, nonblock, P1.RIGHTS_DIRECTORY, P1.RIGHTS_ALL)
            } else {
                var rights = P1.RIGHTS_FILE_BASE
                if (canRead && obj.readable) rights = rights or P1.RIGHT_FD_READ
                if (canWrite && obj.writable) rights = rights or P1.RIGHTS_FILE_WRITE
                val append = if (obj.append) P1.FDFLAGS_APPEND else 0
                FdStat(P1.fileType(obj.type), append or nonblock, rights, 0)
            }
            else -> {
                var rights = P1.RIGHTS_STREAM_BASE
                if (canRead) rights = rights or P1.RIGHT_FD_READ
                if (canWrite) rights = rights or P1.RIGHT_FD_WRITE
                FdStat(streamFileType(obj), nonblock, rights, 0)
            }
        }
    }

    /** ファイル以外の種類。TTY（コンソール）は端末なので `CHARACTER_DEVICE`（wasi-libc の `isatty` が真になる）。ほかは `UNKNOWN` */
    private fun streamFileType(obj: KernelObject): Int = if (obj is Tty) P1.FILETYPE_CHARACTER_DEVICE else P1.FILETYPE_UNKNOWN

    /** 読む。待つ fd なら、少しでも読めるまで待つ。長さ 0 なら fd と権利を確かめるだけ */
    private fun readStream(fd: Int, buf: ByteArray): IoResult {
        if (buf.isEmpty()) {
            val err = checkRight(fd, HandleRights.READ)
            return if (err != 0) IoResult.Error(Errno.ofCode(err)!!) else IoResult.Bytes(0)
        }
        return if (shouldWait(fd)) FdIo.readBlocking(ctx, fd, buf, 0, buf.size) else FdIo.read(ctx, fd, buf, 0, buf.size)
    }

    /** [fd] が開いていて、権利 [right] を持つか（errno。持てば 0） */
    private fun checkRight(fd: Int, right: Int): Int = ctx.withKernelLock {
        errno { if (table().entry(fd).rights and right == 0) fsError(Errno.BADF) }
    }

    /** ストリームの読み書きで待つか（`BLOCKING` のプロセスで、fd に `NONBLOCK` がない） */
    private fun shouldWait(fd: Int): Boolean {
        if (!blocking) return false
        val flags = ctx.withKernelLock { ctx.kernel.processes[ctx.pid]?.handles?.get(fd)?.flags } ?: return true
        return flags and P1.FDFLAGS_NONBLOCK.toLong() == 0L
    }

    private fun table(): FdTable = FdTable(checkNotNull(ctx.kernel.processes[ctx.pid]), ctx.kernel.objects, ctx.wakeSink)

    /** カーネルロックの中で、[fd] のエントリとオブジェクトで [block] を実行する。失敗は [FsException] のまま投げる */
    private fun <T> locked(fd: Int, block: (HandleEntry, KernelObject) -> T): T = ctx.withKernelLock {
        val t = table()
        block(t.entry(fd), t.objectOf(fd))
    }

    /** 位置を持つファイルでなければ `SPIPE`（ストリーム） */
    private fun file(obj: KernelObject): OpenFile = obj as? OpenFile ?: fsError(Errno.SPIPE)

    private inline fun errno(block: () -> Unit): Int = try {
        block()
        0
    } catch (e: FsException) {
        e.errno.code
    }

    private fun chargeBytes(n: Int) {
        if (n > 0) ctx.chargeFuel(n.toLong() / BYTES_PER_FUEL)
    }

    /** iovec の並び。範囲を確かめた後のもの */
    private class IoVecs(private val addrs: IntArray, private val lens: IntArray, val total: Int) {
        /** [buf] の先頭 [n] バイトを、並びの順に書き込む */
        fun scatter(memory: WasmMemory, buf: ByteArray, n: Int) {
            var off = 0
            for (i in addrs.indices) {
                if (off >= n) break
                val c = minOf(lens[i], n - off)
                memory.write(addrs[i], buf, off, c)
                off += c
            }
        }

        /** 並びの中身をつなげて読む */
        fun gather(memory: WasmMemory): ByteArray {
            val buf = ByteArray(total)
            var off = 0
            for (i in addrs.indices) {
                memory.read(addrs[i], buf, off, lens[i])
                off += lens[i]
            }
            return buf
        }

        companion object {
            /** 並びを読み、範囲を確かめる。範囲外なら `null`。合わせて [MAX_IO_BYTES] を超える分は切る */
            fun load(memory: WasmMemory, iovs: Int, iovsLen: Int): IoVecs? {
                val count = P1.u32(iovsLen.toLong())
                if (!P1.inBounds(memory, iovs, count * P1.IOVEC_SIZE)) return null
                val addrs = ArrayList<Int>()
                val lens = ArrayList<Int>()
                var total = 0L
                for (i in 0 until count.toInt()) {
                    val at = iovs + i * P1.IOVEC_SIZE
                    val addr = memory.readI32(at)
                    val len = P1.u32(memory.readI32(at + 4).toLong())
                    if (!P1.inBounds(memory, addr, len)) return null
                    val take = minOf(len, MAX_IO_BYTES - total)
                    if (take <= 0) continue
                    addrs += addr
                    lens += take.toInt()
                    total += take
                }
                return IoVecs(addrs.toIntArray(), lens.toIntArray(), total.toInt())
            }
        }
    }

    companion object {
        /** 1回の読み書きの上限（バイト）。超える分は読み書きしない（POSIX と同じく、一部だけの読み書きになる） */
        const val MAX_IO_BYTES: Long = 1L shl 20

        /** 呼び出し1回の燃料（仮の値。確定は #251） */
        const val CALL_FUEL: Long = 100

        /** 燃料 1 あたりの読み書きのバイト数（仮の値。確定は #251） */
        const val BYTES_PER_FUEL: Long = 64

        private fun putI64(b: ByteArray, at: Int, v: Long) {
            for (i in 0 until 8) b[at + i] = (v ushr (8 * i)).toByte()
        }

        private fun putI32(b: ByteArray, at: Int, v: Int) {
            for (i in 0 until 4) b[at + i] = (v ushr (8 * i)).toByte()
        }
    }
}
