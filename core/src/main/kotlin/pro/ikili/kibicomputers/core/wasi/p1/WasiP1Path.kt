package pro.ikili.kibicomputers.core.wasi.p1

import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FileStat
import pro.ikili.kibicomputers.core.kernel.vfs.FileType
import pro.ikili.kibicomputers.core.kernel.vfs.OpenFile
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.vfs.fsError
import pro.ikili.kibicomputers.core.runtime.WasmMemory

/**
 * WASI p1 の `path_*`（#71。ADR 0071）。ディレクトリの fd から辿ったパスで、VFS のパスの操作を呼ぶ薄い層。
 *
 * - 基点の fd はディレクトリの `OpenFile`（開いていなければ `BADF`、ディレクトリでなければ `NOTDIR`）。
 *   パスは `VfsPath.resolveBeneath(開いたときのパス, 文字列)` で解く。絶対パスと、基点の外へ出る `..` は `NOTCAPABLE`
 * - パスの文字列はゲストのメモリの `(ptr, len)`（[P1.readPath]）。範囲外のポインタは、何かを変える前に `FAULT`
 * - 末尾の `/` は「ディレクトリでなければ `NOTDIR`」
 * - `lookupflags` の `SYMLINK_FOLLOW` は受け取って無視する（シンボリックリンクはない。ADR 0051）。知らないビットは `INVAL`
 * - 失敗は `FsException` の errno（[WasiP1.register] が errno にする）。燃料は呼び出しごとの `CALL_FUEL` だけ（仮。確定は #251）
 */
internal class WasiP1Path(private val ctx: ProcessContext) {
    /** `path_create_directory(fd, path, path_len)`。最後の名前が `.`・`..` なら `EXIST` */
    fun createDirectory(memory: WasmMemory, fd: Int, path: Int, pathLen: Int): Int {
        val text = P1.readPath(memory, path, pathLen)
        ctx.withKernelLock {
            val target = resolve(fd, text)
            if (target.lastIsDot) fsError(Errno.EXIST)
            ctx.vfs.createDirectory(target.path)
        }
        return 0
    }

    /** `path_remove_directory(fd, path, path_len)`。最後の名前が `.` なら `INVAL`、`..` なら `NOTEMPTY`（Linux と同じ） */
    fun removeDirectory(memory: WasmMemory, fd: Int, path: Int, pathLen: Int): Int {
        val text = P1.readPath(memory, path, pathLen)
        ctx.withKernelLock {
            val target = resolve(fd, text)
            when (target.lastName) {
                "." -> fsError(Errno.INVAL)
                ".." -> fsError(Errno.NOTEMPTY)
            }
            ctx.vfs.removeDirectory(target.path)
        }
        return 0
    }

    /** `path_unlink_file(fd, path, path_len)`。ディレクトリは `ISDIR`。末尾の `/` があり、普通のファイルなら `NOTDIR` */
    fun unlinkFile(memory: WasmMemory, fd: Int, path: Int, pathLen: Int): Int {
        val text = P1.readPath(memory, path, pathLen)
        ctx.withKernelLock {
            val target = resolve(fd, text)
            if (target.trailingSlash) checkDirectory(ctx.vfs.stat(target.path))
            ctx.vfs.removeFile(target.path)
        }
        return 0
    }

    /**
     * `path_rename(fd, old_path, old_len, new_fd, new_path, new_len)`。マウントをまたげば `XDEV`。
     * 最後の名前が `.`・`..` なら `INVAL`。末尾の `/` があれば、元がディレクトリでなければ `NOTDIR`。置き換えの規則は VFS（POSIX と同じ）
     */
    fun rename(memory: WasmMemory, oldFd: Int, oldPath: Int, oldLen: Int, newFd: Int, newPath: Int, newLen: Int): Int {
        val oldText = P1.readPath(memory, oldPath, oldLen)
        val newText = P1.readPath(memory, newPath, newLen)
        ctx.withKernelLock {
            val from = resolve(oldFd, oldText)
            val to = resolve(newFd, newText)
            if (from.lastIsDot || to.lastIsDot) fsError(Errno.INVAL)
            if (from.trailingSlash || to.trailingSlash) checkDirectory(ctx.vfs.stat(from.path))
            ctx.vfs.rename(from.path, to.path)
        }
        return 0
    }

    /** `path_filestat_get(fd, flags, path, path_len, filestat*)` */
    fun filestatGet(memory: WasmMemory, fd: Int, lookupFlags: Int, path: Int, pathLen: Int, bufPtr: Int): Int {
        checkLookupFlags(lookupFlags)
        val text = P1.readPath(memory, path, pathLen)
        if (!P1.inBounds(memory, bufPtr, P1.FILESTAT_SIZE.toLong())) return Errno.FAULT.code
        val out = ctx.withKernelLock {
            val target = resolve(fd, text)
            val st = ctx.vfs.stat(target.path)
            if (target.trailingSlash) checkDirectory(st)
            P1.filestat(st)
        }
        memory.write(bufPtr, out)
        return 0
    }

    /** `path_filestat_set_times(fd, flags, path, path_len, atim, mtim, fst_flags)`。`fstflags` は `fd_filestat_set_times` と同じ */
    fun filestatSetTimes(memory: WasmMemory, fd: Int, lookupFlags: Int, path: Int, pathLen: Int, atim: Long, mtim: Long, fstFlags: Int): Int {
        checkLookupFlags(lookupFlags)
        val times = P1.times(fstFlags, atim, mtim) { ctx.environment.clock.realtimeNanos() }
        val text = P1.readPath(memory, path, pathLen)
        ctx.withKernelLock {
            val target = resolve(fd, text)
            if (target.trailingSlash) checkDirectory(ctx.vfs.stat(target.path))
            ctx.vfs.setTimes(target.path, times.accessedNanos, times.modifiedNanos)
        }
        return 0
    }

    /**
     * `path_open(fd, dirflags, path, path_len, oflags, rights_base, rights_inheriting, fdflags, fd*)`。
     *
     * - `oflags` → `OpenOptions` の `create`・`directory`・`exclusive`・`truncate`
     * - `rights_base` の `FD_READ` → 読む、`FD_WRITE` → 書く。ほかの権利と `rights_inheriting` は見ない（ADR 0070）
     * - `fdflags`: `APPEND` → 追記、`NONBLOCK` → fd のフラグ。`DSYNC`・`RSYNC`・`SYNC` は `NOTSUP`、知らないビットは `INVAL`
     * - fd 表が満杯なら、開く前に `MFILE`（`O_TRUNC` で中身を消してから失敗しないように）
     */
    fun open(
        memory: WasmMemory,
        fd: Int,
        dirFlags: Int,
        path: Int,
        pathLen: Int,
        oflags: Int,
        rightsBase: Long,
        @Suppress("UNUSED_PARAMETER") rightsInheriting: Long,
        fdflags: Int,
        fdPtr: Int,
    ): Int {
        checkLookupFlags(dirFlags)
        if (oflags and P1.OFLAGS_ALL.inv() != 0) return Errno.INVAL.code
        if (fdflags and P1.FDFLAGS_ALL.inv() != 0) return Errno.INVAL.code
        if (fdflags and (P1.FDFLAGS_DSYNC or P1.FDFLAGS_RSYNC or P1.FDFLAGS_SYNC) != 0) return Errno.NOTSUP.code
        val text = P1.readPath(memory, path, pathLen)
        if (!P1.inBounds(memory, fdPtr, 4)) return Errno.FAULT.code
        val read = rightsBase and P1.RIGHT_FD_READ != 0L
        val write = rightsBase and P1.RIGHT_FD_WRITE != 0L
        val options = OpenOptions(
            read = read,
            write = write,
            append = fdflags and P1.FDFLAGS_APPEND != 0,
            create = oflags and P1.OFLAGS_CREAT != 0,
            exclusive = oflags and P1.OFLAGS_EXCL != 0,
            truncate = oflags and P1.OFLAGS_TRUNC != 0,
            directory = oflags and P1.OFLAGS_DIRECTORY != 0,
        )
        val rights = (if (read) HandleRights.READ else 0) or (if (write) HandleRights.WRITE else 0)
        val flags = (fdflags and P1.FDFLAGS_NONBLOCK).toLong()
        val newFd = ctx.withKernelLock {
            val target = resolve(fd, text)
            val table = table()
            if (table.isFull) fsError(Errno.MFILE)
            val file = ctx.vfs.open(target.path, options, target.trailingSlash)
            table.open(file, rights, flags)
        }
        memory.writeI32(fdPtr, newFd)
        return 0
    }

    /** `path_readlink(fd, path, path_len, buf, buf_len, bufused*)`。リンクはないので、パスがあれば `INVAL`（POSIX と同じ） */
    fun readlink(memory: WasmMemory, fd: Int, path: Int, pathLen: Int, buf: Int, bufLen: Int, bufusedPtr: Int): Int {
        val text = P1.readPath(memory, path, pathLen)
        if (!P1.inBounds(memory, buf, P1.u32(bufLen.toLong())) || !P1.inBounds(memory, bufusedPtr, 4)) return Errno.FAULT.code
        ctx.withKernelLock {
            val target = resolve(fd, text)
            val st = ctx.vfs.stat(target.path)
            if (target.trailingSlash) checkDirectory(st)
        }
        return Errno.INVAL.code
    }

    /** `path_symlink(old_path, old_len, fd, new_path, new_len)`。基点の fd を確かめてから `NOTSUP`（ADR 0051） */
    fun symlink(memory: WasmMemory, oldPath: Int, oldLen: Int, fd: Int, newPath: Int, newLen: Int): Int {
        P1.readPath(memory, oldPath, oldLen)
        P1.readPath(memory, newPath, newLen)
        ctx.withKernelLock { base(fd) }
        return Errno.NOTSUP.code
    }

    /** `path_link(old_fd, old_flags, old_path, old_len, new_fd, new_path, new_len)`。基点の fd を確かめてから `NOTSUP`（ADR 0051） */
    fun link(memory: WasmMemory, oldFd: Int, oldFlags: Int, oldPath: Int, oldLen: Int, newFd: Int, newPath: Int, newLen: Int): Int {
        checkLookupFlags(oldFlags)
        P1.readPath(memory, oldPath, oldLen)
        P1.readPath(memory, newPath, newLen)
        ctx.withKernelLock {
            base(oldFd)
            base(newFd)
        }
        return Errno.NOTSUP.code
    }

    // ---- 中身 ----

    /** 辿った先。[lastName] は文字列の最後の名前（`.`・`..` を見分けるため。正規化の前） */
    private class Target(val path: VfsPath, val trailingSlash: Boolean, val lastName: String) {
        val lastIsDot: Boolean get() = lastName == "." || lastName == ".."
    }

    /** 基点の fd [fd] から [text] を辿る（カーネルロックの中） */
    private fun resolve(fd: Int, text: String): Target {
        val dir = base(fd)
        val path = VfsPath.resolveBeneath(dir.path, text)
        val last = text.split('/').lastOrNull { it.isNotEmpty() } ?: ""
        return Target(path, VfsPath.hasTrailingSlash(text), last)
    }

    /** 基点の fd。開いていなければ `BADF`、ディレクトリの `OpenFile` でなければ `NOTDIR` */
    private fun base(fd: Int): OpenFile {
        val obj = table().objectOf(fd)
        if (obj !is OpenFile || !obj.isDirectory) fsError(Errno.NOTDIR, "fd $fd はディレクトリではない")
        return obj
    }

    private fun checkDirectory(st: FileStat) {
        if (st.type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
    }

    private fun checkLookupFlags(flags: Int) {
        if (flags and P1.LOOKUPFLAGS_SYMLINK_FOLLOW.inv() != 0) fsError(Errno.INVAL)
    }

    private fun table(): FdTable = FdTable(checkNotNull(ctx.kernel.processes[ctx.pid]), ctx.kernel.objects, ctx.wakeSink)
}
