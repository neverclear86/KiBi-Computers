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

    /**
     * 参照カウントが 0 になり、表から外れた後に呼ばれる。このオブジェクトが持っているほかのオブジェクトの参照を
     * [objects] に返す（パイプの端が中身のストリームを持つなど。#59）
     */
    open fun releaseReferences(objects: ObjectTable, sink: WakeSink) {}

    /**
     * 保存の写しに入れる、このオブジェクトの写し（#81、ADR 0081）。写しの中の後始末（`onLastClose`・`releaseReferences`）が
     * 元を変えないよう、状態を写した新しいインスタンスを返す。既定は `null`（写せない。写しの表は元の実体を指すだけにし、
     * 写しの中で参照が 0 になっても後始末を呼ばない）。#81 で写せるのは `ProcessObject` だけ（ほかの種類は #82）
     */
    open fun copyForSnapshot(): KernelObject? = null
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

    /**
     * 保存の写しの表のときだけ: 写していない（元の実体を指すだけの）オブジェクトのID。参照が 0 になっても後始末を呼ばない（#81）
     */
    private var shared: HashSet<Int>? = null

    /** 保存の写しの表のときだけ: 写していない実体のうち、写しの中で参照が 0 になったもののID（閉じた順） */
    private var closedShared: ArrayList<Int>? = null

    /** 保存の写しの表か（[copyForSnapshot] で作った） */
    val isSnapshotCopy: Boolean get() = shared != null

    /** 写していない実体のうち、写しの中で参照が 0 になったもののID（閉じた順。#82 が、写した実体の側の後始末に置き換える） */
    val closedInCopy: IntArray get() = closedShared?.toIntArray() ?: IntArray(0)

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

    /** 表にあるオブジェクトをすべて渡す（ID の順は決めない。中で表を変えない） */
    fun forEachObject(action: (KernelObject) -> Unit) {
        entries?.values?.forEach { action(it.obj) }
    }

    /** 参照カウント（テスト用）。なければ 0 */
    fun refCount(id: Int): Int = entries?.get(id)?.refCount ?: 0

    /** 参照を1つ増やす（fd の複製など） */
    fun retain(id: Int) {
        val e = requireNotNull(entries?.get(id)) { "オブジェクト $id がない" }
        e.refCount++
    }

    /**
     * 参照を1つ減らす。0 になったら `onLastClose` → `sink.wakeObject(id)` → 表から外す → `releaseReferences`。
     * 保存の写しの表で、写していない実体なら、後始末はどれも呼ばずに表から外し、[closedInCopy] に記録するだけ
     */
    fun release(id: Int, sink: WakeSink) {
        val map = entries
        val e = requireNotNull(map?.get(id)) { "オブジェクト $id がない" }
        e.refCount--
        if (e.refCount > 0) return
        val sharedIds = shared
        if (sharedIds != null && id in sharedIds) {
            map!!.remove(id)
            sharedIds.remove(id)
            closedShared!!.add(id)
            return
        }
        e.obj.onLastClose(sink)
        sink.wakeObject(id)
        map!!.remove(id)
        e.obj.releaseReferences(this, sink)
    }

    /**
     * 保存の写しの表を作る（#81、ADR 0081）。全エントリの参照の数と [nextObjectId] を写す。実体は、[KernelObject.copyForSnapshot] が
     * 返せば写したもの、返さなければ元の実体を指すだけ（写しの中で参照が 0 になっても、ホストの資源を閉じない）
     */
    fun copyForSnapshot(): ObjectTable {
        val copy = ObjectTable()
        copy.nextObjectId = nextObjectId
        val sharedIds = HashSet<Int>()
        val map = HashMap<Int, Entry>()
        entries?.forEach { (id, e) ->
            val obj = e.obj.copyForSnapshot()?.also { it.id = id }
            if (obj == null) sharedIds += id
            map[id] = Entry(obj ?: e.obj).also { it.refCount = e.refCount }
        }
        copy.entries = map
        copy.shared = sharedIds
        copy.closedShared = ArrayList()
        return copy
    }
}
