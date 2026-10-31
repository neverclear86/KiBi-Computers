package pro.ikili.kibicomputers.core.kernel.fd

import pro.ikili.kibicomputers.core.kernel.obj.HandleEntry
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.obj.KernelObject
import pro.ikili.kibicomputers.core.kernel.obj.ObjectTable
import pro.ikili.kibicomputers.core.kernel.process.Process
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.fsError
import pro.ikili.kibicomputers.core.kernel.wait.WakeSink

/** 標準入力の fd */
internal const val STDIN: Int = 0

/** 標準出力の fd */
internal const val STDOUT: Int = 1

/** 標準エラー出力の fd */
internal const val STDERR: Int = 2

/**
 * プロセスのファイルディスクリプタ表（[Process.handles]）の操作。p1・p2・p3 のどの ABI 層もこれを通す。
 *
 * fd はオブジェクト表（[ObjectTable]）の参照を1つ持つ。fd を閉じると参照を返し、最後の参照なら `onLastClose` が呼ばれる。
 * 複製（[dup]・[dup2]）は同じオブジェクトを指すので、`OpenFile` の位置やストリームの中身を共有する。
 * 失敗は `FsException`（`BADF`・`MFILE`）。表を変えずに失敗する。
 *
 * カーネルロックの中で使う。状態を持たないので、使うたびに作ってよい。
 */
internal class FdTable(
    private val process: Process,
    private val objects: ObjectTable,
    private val sink: WakeSink,
) {
    private val handles get() = process.handles

    /** 開いている fd の数 */
    val size: Int get() = handles.size

    /** [fd] のエントリ。開いていなければ `BADF` */
    fun entry(fd: Int): HandleEntry = handles[fd] ?: fsError(Errno.BADF, "fd $fd は開いていない")

    /** [fd] が指すオブジェクト。開いていなければ `BADF` */
    fun objectOf(fd: Int): KernelObject {
        val e = entry(fd)
        return checkNotNull(objects[e.objectId]) { "fd $fd が指すオブジェクト ${e.objectId} がない（pid ${process.pid}）" }
    }

    /** 開いている fd と、そのエントリ（番号の順） */
    fun entries(): Map<Int, HandleEntry> = handles.entries()

    /**
     * 参照を1つ受け取り、いちばん小さい空き番号に入れて返す。
     * 満杯なら `MFILE`（参照は呼び出し側に残る。閉じるのは呼び出し側）
     */
    fun install(objectId: Int, rights: Int = HandleRights.ALL, flags: Long = 0): Int {
        requireNotNull(objects[objectId]) { "オブジェクト $objectId がない" }
        val fd = handles.insert(HandleEntry(objectId, flags, rights))
        if (fd < 0) fsError(Errno.MFILE, "fd の数が上限（${handles.limit}）")
        return fd
    }

    /** [obj] をオブジェクト表に足し、いちばん小さい空き番号に入れて返す。満杯なら、表に足さずに `MFILE` */
    fun open(obj: KernelObject, rights: Int = HandleRights.ALL, flags: Long = 0): Int {
        if (handles.isFull) fsError(Errno.MFILE, "fd の数が上限（${handles.limit}）")
        val id = objects.add(obj)
        return install(id, rights, flags)
    }

    /** [fd] を、いちばん小さい空き番号に複製する（`dup`）。権利とフラグも写す */
    fun dup(fd: Int): Int {
        val e = entry(fd)
        if (handles.isFull) fsError(Errno.MFILE, "fd の数が上限（${handles.limit}）")
        objects.retain(e.objectId)
        return handles.insert(e)
    }

    /**
     * [from] を [to] に複製する（`dup2`）。[to] が開いていれば閉じてから。同じ番号なら、開いているか確かめるだけ。
     * [to] が範囲外（負、上限以上）なら `BADF`
     */
    fun dup2(from: Int, to: Int): Int {
        val e = entry(from)
        if (from == to) return to
        checkRange(to)
        objects.retain(e.objectId)
        val old = handles[to]
        handles.insertAt(to, e)
        if (old != null) objects.release(old.objectId, sink)
        return to
    }

    /**
     * [from] を [to] へ移す（WASI p1 の `fd_renumber`）。[to] が開いていれば閉じてから。[from] は閉じる。
     * 同じ番号なら、開いているか確かめるだけ
     */
    fun renumber(from: Int, to: Int) {
        val e = entry(from)
        if (from == to) return
        checkRange(to)
        val old = handles[to]
        handles.remove(from)
        handles.insertAt(to, e)
        if (old != null) objects.release(old.objectId, sink)
    }

    /** [fd] を閉じる。開いていなければ `BADF` */
    fun close(fd: Int) {
        val e = handles.remove(fd) ?: fsError(Errno.BADF, "fd $fd は開いていない")
        objects.release(e.objectId, sink)
    }

    /** すべての fd を閉じる（プロセスの終わり） */
    fun closeAll() {
        for ((fd, e) in handles.entries()) {
            handles.remove(fd)
            objects.release(e.objectId, sink)
        }
    }

    /** [fd] に [entry] を入れる（参照を1つ増やす）。プロセスを作るときの標準入出力の割り当て用。[fd] は空いていること */
    internal fun installAt(fd: Int, entry: HandleEntry) {
        check(handles[fd] == null) { "fd $fd は使われている" }
        requireNotNull(objects[entry.objectId]) { "オブジェクト ${entry.objectId} がない" }
        objects.retain(entry.objectId)
        handles.insertAt(fd, entry)
    }

    private fun checkRange(fd: Int) {
        if (fd < 0 || fd >= handles.limit) fsError(Errno.BADF, "fd $fd は範囲外（上限 ${handles.limit}）")
    }
}
