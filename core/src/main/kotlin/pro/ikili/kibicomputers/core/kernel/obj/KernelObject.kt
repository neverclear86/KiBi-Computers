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
     * 保存の写しに入れる、このオブジェクトの写し（#81・#82、ADR 0081・0082）。写しの中の後始末（`onLastClose`・`releaseReferences`）が
     * 元を変えないよう、状態を写した新しいインスタンスを返す（`this` は返さない）。実行時だけの値（`OpenFile.retained` など）は写さない。
     * 新しい種類を足したら、`KernelObjectCodec` の種類の番号の表にも足す
     */
    abstract fun copyForSnapshot(): KernelObject
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

    /** 参照を1つ減らす。0 になったら `onLastClose` → `sink.wakeObject(id)` → 表から外す → `releaseReferences` */
    fun release(id: Int, sink: WakeSink) {
        val map = entries
        val e = requireNotNull(map?.get(id)) { "オブジェクト $id がない" }
        e.refCount--
        if (e.refCount > 0) return
        e.obj.onLastClose(sink)
        sink.wakeObject(id)
        map!!.remove(id)
        e.obj.releaseReferences(this, sink)
    }

    /**
     * 保存の写しの表を作る（#81・#82、ADR 0081・0082）。全エントリの参照の数と [nextObjectId] を写し、実体はすべて
     * [KernelObject.copyForSnapshot] で写す（写しの中の後始末は、写した実体の側で起きる。元を変えない）
     */
    fun copyForSnapshot(): ObjectTable {
        val copy = ObjectTable()
        copy.nextObjectId = nextObjectId
        val map = HashMap<Int, Entry>()
        entries?.forEach { (id, e) ->
            val obj = e.obj.copyForSnapshot()
            check(obj !== e.obj && obj.id == 0) { "copyForSnapshot は新しい実体を返す（${e.obj::class.simpleName}）" }
            obj.id = id
            map[id] = Entry(obj).also { it.refCount = e.refCount }
        }
        copy.entries = map
        return copy
    }

    /**
     * 読んだ保存データから表を組み立てる（#82。戻すのは #85）。[objects] は ID → (実体, 参照の数)。実体の ID は 0 であること
     */
    fun restoreFrom(nextObjectId: Int, objects: Map<Int, Pair<KernelObject, Int>>) {
        check(entries.isNullOrEmpty()) { "空の表にだけ戻す" }
        val map = HashMap<Int, Entry>()
        for ((id, pair) in objects) {
            val (obj, refs) = pair
            require(id > 0 && refs > 0 && obj.id == 0) { "オブジェクト $id の値が不正" }
            obj.id = id
            map[id] = Entry(obj).also { it.refCount = refs }
        }
        entries = map
        this.nextObjectId = nextObjectId
    }

    /**
     * 読んだ保存データの表 [other]（`KernelSectionCodec.read` が組み立てたもの）の中身を、空のこの表に移す（#85。[other] は空になる）
     */
    fun restoreFrom(other: ObjectTable) {
        check(entries.isNullOrEmpty()) { "空の表にだけ戻す" }
        entries = other.entries
        nextObjectId = other.nextObjectId
        other.entries = null
    }

    /** ID → 参照の数を ID の順に渡す（保存の書き出し用） */
    fun forEachEntrySorted(action: (id: Int, obj: KernelObject, refCount: Int) -> Unit) {
        val map = entries ?: return
        for (id in map.keys.sorted()) {
            val e = map.getValue(id)
            action(id, e.obj, e.refCount)
        }
    }
}
