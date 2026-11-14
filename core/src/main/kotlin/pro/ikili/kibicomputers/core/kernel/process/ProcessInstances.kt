package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.runtime.WasmInstance
import java.util.Collections
import java.util.IdentityHashMap

/**
 * プロセスが作ったインスタンス（作った順。#691）。読むときは普通の [List] として扱い、変えるときは [add]・[set]・[clear] を使う。
 *
 * ホスト関数・燃料切れの処理の入口の照合（[containsSame]）を O(1) にするため、2 つ目を足したときから同一性の集合を併せて持つ。
 * 大多数の core モジュールのプロセスはインスタンスが 1 つなので、集合は作らない（台数に比例してメモリを増やさない）。
 * カーネルロックの中で読み書きする
 */
internal class ProcessInstances : AbstractList<WasmInstance>() {
    private val list = ArrayList<WasmInstance>(1)

    /** 同一性の集合（2 つ以上あるときだけ） */
    private var identities: MutableSet<WasmInstance>? = null

    override val size: Int get() = list.size

    override fun get(index: Int): WasmInstance = list[index]

    /** 末尾に足す */
    fun add(instance: WasmInstance) {
        list += instance
        val set = identities
        if (set != null) {
            set += instance
        } else if (list.size >= 2) {
            identities = Collections.newSetFromMap(IdentityHashMap<WasmInstance, Boolean>()).also { it.addAll(list) }
        }
    }

    operator fun plusAssign(instance: WasmInstance) = add(instance)

    /** [index] 番目を [instance] に替え、前のものを返す */
    fun set(index: Int, instance: WasmInstance): WasmInstance {
        val previous = list.set(index, instance)
        identities?.let { set ->
            set.clear()
            set.addAll(list)
        }
        return previous
    }

    /** すべて除く（閉じるのは呼ぶ側） */
    fun clear() {
        list.clear()
        identities = null
    }

    /** [instance] と同じもの（`===`）が入っている */
    fun containsSame(instance: WasmInstance): Boolean {
        identities?.let { return instance in it }
        return list.size == 1 && list[0] === instance
    }
}
