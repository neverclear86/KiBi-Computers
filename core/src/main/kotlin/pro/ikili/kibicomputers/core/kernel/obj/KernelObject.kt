package pro.ikili.kibicomputers.core.kernel.obj

import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WakeSink

/**
 * カーネルオブジェクト（stream、ファイル、パイプ、タイマー、デバイス…）の基底。マシンの [ObjectTable] に置く。
 *
 * 保存の対象なので、実行時だけのもの（`Waiter`、`WaitRegistry`、`WakeSink`、スレッド、ロック）をフィールドに持たない。
 * ほかのオブジェクトは Kotlin の参照ではなくオブジェクトIDで指す。
 */
internal abstract class KernelObject {
    /** [ObjectTable] が割り当てる ID。表に入る前は 0 */
    var id: Int = 0
        internal set

    /** その条件を待てるか（待てなければ ABI 層はエラーにする） */
    open fun canWait(condition: WaitCondition): Boolean = false

    /** 今その条件がそろっているか */
    open fun isReady(condition: WaitCondition): Boolean = false

    /** 参照カウントが 0 になった。この後 [ObjectTable] が `sink.wakeObject(id)` を呼ぶ */
    open fun onLastClose(sink: WakeSink) {}
}

/**
 * オブジェクトID → [KernelObject] と参照カウントの表。カーネルロックの中で使う。
 *
 * ID は 1 から順に割り当てる（使い終わった ID はすぐには使い回さない）。表は最初に使うときに作る。
 */
internal class ObjectTable {
    private class Entry(val obj: KernelObject) {
        var refCount: Int = 1
    }

    private var entries: HashMap<Int, Entry>? = null

    /** 次に割り当てる ID */
    var nextObjectId: Int = 1
        private set

    /** 表にあるオブジェクトの数 */
    val size: Int get() = entries?.size ?: 0

    /** ID を割り当てて表に足す。参照カウントは 1 */
    fun add(obj: KernelObject): Int {
        require(obj.id == 0) { "別の表に入っているオブジェクト" }
        val map = entries ?: HashMap<Int, Entry>().also { entries = it }
        var id = nextObjectId
        while (id <= 0 || map.containsKey(id)) id = if (id == Int.MAX_VALUE || id <= 0) 1 else id + 1
        nextObjectId = if (id == Int.MAX_VALUE) 1 else id + 1
        obj.id = id
        map[id] = Entry(obj)
        return id
    }

    operator fun get(id: Int): KernelObject? = entries?.get(id)?.obj

    /** 参照カウント（テスト用）。なければ 0 */
    fun refCount(id: Int): Int = entries?.get(id)?.refCount ?: 0

    /** 参照を1つ増やす（fd の複製など） */
    fun retain(id: Int) {
        val e = requireNotNull(entries?.get(id)) { "オブジェクト $id がない" }
        e.refCount++
    }

    /** 参照を1つ減らす。0 になったら `onLastClose` → `sink.wakeObject(id)` → 表から外す */
    fun release(id: Int, sink: WakeSink) {
        val map = entries
        val e = requireNotNull(map?.get(id)) { "オブジェクト $id がない" }
        e.refCount--
        if (e.refCount > 0) return
        e.obj.onLastClose(sink)
        sink.wakeObject(id)
        map!!.remove(id)
    }
}
