package pro.ikili.kibicomputers.core.kernel.fd

import pro.ikili.kibicomputers.core.kernel.obj.HandleEntry
import pro.ikili.kibicomputers.core.kernel.obj.KernelObject
import pro.ikili.kibicomputers.core.kernel.obj.ObjectTable
import pro.ikili.kibicomputers.core.kernel.process.Process
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.fsError
import pro.ikili.kibicomputers.core.kernel.wait.WakeSink

/**
 * カーネルが新しく作ったオブジェクトのハンドルを置く先（#685）。p1 は fd 表（[FdPlacement]）、p2 はプロセスのホストの resource の表
 * （`HostResourceTable`。ABI 層が実装する）に置く。オブジェクトを作る・読むカーネルの中身は置き場に依らず 1 つにし、
 * 置き場だけを差し替える（`FdIo` の「解決済みのエントリで動く中身」と「fd 版の殻」の分け方と同じ。#267）。
 *
 * どれもカーネルロックの中で呼ぶ。状態を持たない（使うたびに作ってよい）
 */
internal interface HandlePlacement {
    /** あと 1 つ置けることを確かめる。置けなければ `FsException(MFILE)`（何も変えない） */
    @Throws(FsException::class)
    fun checkRoomLocked()

    /**
     * 参照を 1 つ持った [entry] を置き、番号（fd・rep）を返す。参照の持ち主は置き場になる。
     * 置けなければ `FsException(MFILE)`（参照は呼び出し側に残る。返すのは呼び出し側）
     */
    @Throws(FsException::class)
    fun placeLocked(entry: HandleEntry): Int

    /** [placeLocked] で置いた [slot] を外す（参照は返さない。返すのは呼び出し側。作りかけの巻き戻し用） */
    fun unplaceLocked(slot: Int)
}

/** fd 表に置く（p1。`FdTable.install` と同じ。満杯は `MFILE`） */
internal class FdPlacement(private val process: Process, private val objects: ObjectTable, private val sink: WakeSink) : HandlePlacement {
    override fun checkRoomLocked() {
        if (process.handles.isFull) fsError(Errno.MFILE, "fd の数が上限（${process.handles.limit}）")
    }

    override fun placeLocked(entry: HandleEntry): Int = FdTable(process, objects, sink).install(entry.objectId, entry.rights, entry.flags)

    override fun unplaceLocked(slot: Int) {
        process.handles.remove(slot)
    }
}

/**
 * [obj] の参照を新しく作ってオブジェクト表に足し、[place] に権利 [rights] で置いて番号を返す（[FdTable.open] の置き場を選べる形）。
 * 置き場が満杯なら、表に足さずに `MFILE`
 */
@Throws(FsException::class)
internal fun HandlePlacement.openLocked(objects: ObjectTable, sink: WakeSink, obj: KernelObject, rights: Int): Int {
    checkRoomLocked()
    val id = objects.add(obj)
    return try {
        placeLocked(HandleEntry(id, rights = rights))
    } catch (t: Throwable) {
        objects.release(id, sink)
        throw t
    }
}
