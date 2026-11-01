package pro.ikili.kibicomputers.core.wasi.p1

import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FileStat
import pro.ikili.kibicomputers.core.kernel.vfs.FileType
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.vfs.fsError
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * WASI p1（`wasi_snapshot_preview1`）の値と構造体の配置。値は WASI p1 の witx（`typenames.witx`）のとおり。
 *
 * 構造体はリトルエンディアンで、オフセットと大きさは wasm32 の C の配置（wasi-libc の `wasi/api.h` の `_Static_assert` と同じ）。
 */
internal object P1 {
    /** import のモジュール名 */
    const val MODULE: String = "wasi_snapshot_preview1"

    // ---- filetype（u8） ----
    const val FILETYPE_UNKNOWN: Int = 0
    const val FILETYPE_BLOCK_DEVICE: Int = 1
    const val FILETYPE_CHARACTER_DEVICE: Int = 2
    const val FILETYPE_DIRECTORY: Int = 3
    const val FILETYPE_REGULAR_FILE: Int = 4

    // ---- clockid（u32） ----
    const val CLOCK_REALTIME: Int = 0
    const val CLOCK_MONOTONIC: Int = 1
    const val CLOCK_PROCESS_CPUTIME: Int = 2
    const val CLOCK_THREAD_CPUTIME: Int = 3

    // ---- fdflags（u16） ----
    const val FDFLAGS_APPEND: Int = 1
    const val FDFLAGS_DSYNC: Int = 2
    const val FDFLAGS_NONBLOCK: Int = 4
    const val FDFLAGS_RSYNC: Int = 8
    const val FDFLAGS_SYNC: Int = 16
    const val FDFLAGS_ALL: Int = FDFLAGS_APPEND or FDFLAGS_DSYNC or FDFLAGS_NONBLOCK or FDFLAGS_RSYNC or FDFLAGS_SYNC

    // ---- whence（u8） ----
    const val WHENCE_SET: Int = 0
    const val WHENCE_CUR: Int = 1
    const val WHENCE_END: Int = 2

    // ---- advice（u8）。normal から noreuse まで ----
    const val ADVICE_MAX: Int = 5

    // ---- fstflags（u16） ----
    const val FSTFLAGS_ATIM: Int = 1
    const val FSTFLAGS_ATIM_NOW: Int = 2
    const val FSTFLAGS_MTIM: Int = 4
    const val FSTFLAGS_MTIM_NOW: Int = 8

    const val FSTFLAGS_ALL: Int = FSTFLAGS_ATIM or FSTFLAGS_ATIM_NOW or FSTFLAGS_MTIM or FSTFLAGS_MTIM_NOW

    // ---- oflags（u16） ----
    const val OFLAGS_CREAT: Int = 1
    const val OFLAGS_DIRECTORY: Int = 2
    const val OFLAGS_EXCL: Int = 4
    const val OFLAGS_TRUNC: Int = 8
    const val OFLAGS_ALL: Int = OFLAGS_CREAT or OFLAGS_DIRECTORY or OFLAGS_EXCL or OFLAGS_TRUNC

    // ---- lookupflags（u32） ----
    const val LOOKUPFLAGS_SYMLINK_FOLLOW: Int = 1

    // ---- preopentype（u8） ----
    const val PREOPENTYPE_DIR: Int = 0

    // ---- rights（u64） ----
    const val RIGHT_FD_DATASYNC: Long = 1L shl 0
    const val RIGHT_FD_READ: Long = 1L shl 1
    const val RIGHT_FD_SEEK: Long = 1L shl 2
    const val RIGHT_FD_FDSTAT_SET_FLAGS: Long = 1L shl 3
    const val RIGHT_FD_SYNC: Long = 1L shl 4
    const val RIGHT_FD_TELL: Long = 1L shl 5
    const val RIGHT_FD_WRITE: Long = 1L shl 6
    const val RIGHT_FD_ADVISE: Long = 1L shl 7
    const val RIGHT_FD_ALLOCATE: Long = 1L shl 8
    const val RIGHT_PATH_CREATE_DIRECTORY: Long = 1L shl 9
    const val RIGHT_PATH_CREATE_FILE: Long = 1L shl 10
    const val RIGHT_PATH_LINK_SOURCE: Long = 1L shl 11
    const val RIGHT_PATH_LINK_TARGET: Long = 1L shl 12
    const val RIGHT_PATH_OPEN: Long = 1L shl 13
    const val RIGHT_FD_READDIR: Long = 1L shl 14
    const val RIGHT_PATH_READLINK: Long = 1L shl 15
    const val RIGHT_PATH_RENAME_SOURCE: Long = 1L shl 16
    const val RIGHT_PATH_RENAME_TARGET: Long = 1L shl 17
    const val RIGHT_PATH_FILESTAT_GET: Long = 1L shl 18
    const val RIGHT_PATH_FILESTAT_SET_SIZE: Long = 1L shl 19
    const val RIGHT_PATH_FILESTAT_SET_TIMES: Long = 1L shl 20
    const val RIGHT_FD_FILESTAT_GET: Long = 1L shl 21
    const val RIGHT_FD_FILESTAT_SET_SIZE: Long = 1L shl 22
    const val RIGHT_FD_FILESTAT_SET_TIMES: Long = 1L shl 23
    const val RIGHT_PATH_SYMLINK: Long = 1L shl 24
    const val RIGHT_PATH_REMOVE_DIRECTORY: Long = 1L shl 25
    const val RIGHT_PATH_UNLINK_FILE: Long = 1L shl 26
    const val RIGHT_POLL_FD_READWRITE: Long = 1L shl 27
    const val RIGHT_SOCK_SHUTDOWN: Long = 1L shl 28
    const val RIGHT_SOCK_ACCEPT: Long = 1L shl 29

    /** p1 のすべての権利 */
    const val RIGHTS_ALL: Long = (1L shl 30) - 1

    /** 普通のファイルの、読み書きに関わらない権利 */
    const val RIGHTS_FILE_BASE: Long = RIGHT_FD_DATASYNC or RIGHT_FD_SEEK or RIGHT_FD_FDSTAT_SET_FLAGS or RIGHT_FD_SYNC or
        RIGHT_FD_TELL or RIGHT_FD_ADVISE or RIGHT_FD_FILESTAT_GET or RIGHT_FD_FILESTAT_SET_TIMES or RIGHT_POLL_FD_READWRITE

    /** 普通のファイルの、書くときの権利 */
    const val RIGHTS_FILE_WRITE: Long = RIGHT_FD_WRITE or RIGHT_FD_ALLOCATE or RIGHT_FD_FILESTAT_SET_SIZE

    /** ディレクトリの権利 */
    const val RIGHTS_DIRECTORY: Long = RIGHT_FD_DATASYNC or RIGHT_FD_FDSTAT_SET_FLAGS or RIGHT_FD_SYNC or
        RIGHT_PATH_CREATE_DIRECTORY or RIGHT_PATH_CREATE_FILE or RIGHT_PATH_LINK_SOURCE or RIGHT_PATH_LINK_TARGET or
        RIGHT_PATH_OPEN or RIGHT_FD_READDIR or RIGHT_PATH_READLINK or RIGHT_PATH_RENAME_SOURCE or RIGHT_PATH_RENAME_TARGET or
        RIGHT_PATH_FILESTAT_GET or RIGHT_PATH_FILESTAT_SET_SIZE or RIGHT_PATH_FILESTAT_SET_TIMES or RIGHT_FD_FILESTAT_GET or
        RIGHT_FD_FILESTAT_SET_TIMES or RIGHT_PATH_SYMLINK or RIGHT_PATH_REMOVE_DIRECTORY or RIGHT_PATH_UNLINK_FILE

    /** ストリーム（コンソール、パイプ）の、読み書きに関わらない権利（`SEEK`・`TELL` は持たない。wasi-libc の `isatty` が見る） */
    const val RIGHTS_STREAM_BASE: Long = RIGHT_FD_FDSTAT_SET_FLAGS or RIGHT_FD_FILESTAT_GET or RIGHT_POLL_FD_READWRITE

    // ---- 構造体 ----

    /** `iovec` / `ciovec`: buf（u32）@0、buf_len（u32）@4 */
    const val IOVEC_SIZE: Int = 8

    /** `fdstat`: fs_filetype（u8）@0、fs_flags（u16）@2、fs_rights_base（u64）@8、fs_rights_inheriting（u64）@16 */
    const val FDSTAT_SIZE: Int = 24

    /** `filestat`: dev @0、ino @8、filetype（u8）@16、nlink @24、size @32、atim @40、mtim @48、ctim @56（u64） */
    const val FILESTAT_SIZE: Int = 64

    /** `prestat`: tag（u8）@0、pr_name_len（u32）@4 */
    const val PRESTAT_SIZE: Int = 8

    /** `dirent`: d_next（u64）@0、d_ino（u64）@8、d_namlen（u32）@16、d_type（u8）@20。この後に名前（NUL なし）が続く */
    const val DIRENT_SIZE: Int = 24

    /** VFS の種類を p1 の filetype にする */
    fun fileType(type: FileType): Int = when (type) {
        FileType.UNKNOWN -> FILETYPE_UNKNOWN
        FileType.CHARACTER_DEVICE -> FILETYPE_CHARACTER_DEVICE
        FileType.DIRECTORY -> FILETYPE_DIRECTORY
        FileType.REGULAR_FILE -> FILETYPE_REGULAR_FILE
    }

    /** i32 の値を符号なしにする（ポインタ・長さ） */
    fun u32(value: Long): Long = value and 0xffff_ffffL

    /** [memory] の [address]（符号なし）から [length] バイトが範囲の中か */
    fun inBounds(memory: WasmMemory, address: Int, length: Long): Boolean =
        length >= 0 && u32(address.toLong()) + length <= memory.byteSize

    /** `filestat`（[FILESTAT_SIZE] バイト）の中身。`fd_filestat_get` と `path_filestat_get` が使う */
    fun filestat(st: FileStat): ByteArray {
        val out = ByteArray(FILESTAT_SIZE)
        putI64(out, 0, st.device)
        putI64(out, 8, st.node)
        out[16] = fileType(st.type).toByte()
        putI64(out, 24, st.linkCount)
        putI64(out, 32, st.size)
        putI64(out, 40, st.accessedNanos)
        putI64(out, 48, st.modifiedNanos)
        putI64(out, 56, st.changedNanos)
        return out
    }

    /** 変える時刻（`null` は変えない） */
    class Times(val accessedNanos: Long?, val modifiedNanos: Long?)

    /**
     * `fstflags` と時刻を、変える時刻にする。`*_NOW` は [now] の時刻（使うときだけ呼ぶ）。
     * 知らないビットと、`ATIM` と `ATIM_NOW`（`MTIM` も）を一緒に使うと `INVAL`
     */
    @Throws(FsException::class)
    fun times(fstFlags: Int, atim: Long, mtim: Long, now: () -> Long): Times {
        if (fstFlags and FSTFLAGS_ALL.inv() != 0) fsError(Errno.INVAL)
        if (fstFlags and FSTFLAGS_ATIM != 0 && fstFlags and FSTFLAGS_ATIM_NOW != 0) fsError(Errno.INVAL)
        if (fstFlags and FSTFLAGS_MTIM != 0 && fstFlags and FSTFLAGS_MTIM_NOW != 0) fsError(Errno.INVAL)
        val nowNanos = if (fstFlags and (FSTFLAGS_ATIM_NOW or FSTFLAGS_MTIM_NOW) != 0) now() else 0L
        val a = when {
            fstFlags and FSTFLAGS_ATIM != 0 -> atim
            fstFlags and FSTFLAGS_ATIM_NOW != 0 -> nowNanos
            else -> null
        }
        val m = when {
            fstFlags and FSTFLAGS_MTIM != 0 -> mtim
            fstFlags and FSTFLAGS_MTIM_NOW != 0 -> nowNanos
            else -> null
        }
        return Times(a, m)
    }

    /**
     * ゲストのメモリの `(ptr, len)` のパスの文字列（NUL 終端なし）を読む。
     * [len] が [VfsPath.MAX_PATH_BYTES] を超えればメモリを読まずに `NAMETOOLONG`、範囲外なら `FAULT`、UTF-8 として壊れていれば `ILSEQ`
     */
    @Throws(FsException::class)
    fun readPath(memory: WasmMemory, ptr: Int, len: Int): String {
        val n = u32(len.toLong())
        if (n > VfsPath.MAX_PATH_BYTES) fsError(Errno.NAMETOOLONG, "パスが長すぎる")
        if (!inBounds(memory, ptr, n)) fsError(Errno.FAULT)
        val bytes = memory.read(ptr, n.toInt())
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            fsError(Errno.ILSEQ, "パスが UTF-8 ではない")
        }
    }

    /** [b] の [at] に、リトルエンディアンで [v] を書く */
    fun putI64(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = (v ushr (8 * i)).toByte()
    }

    /** [b] の [at] に、リトルエンディアンで [v] を書く */
    fun putI32(b: ByteArray, at: Int, v: Int) {
        for (i in 0 until 4) b[at + i] = (v ushr (8 * i)).toByte()
    }
}
