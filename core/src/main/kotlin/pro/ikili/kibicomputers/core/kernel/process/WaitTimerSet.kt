package pro.ikili.kibicomputers.core.kernel.process

/**
 * プロセスが `openWaitTimer` で開いて、まだ閉じていない一時的なタイマーのオブジェクトIDの集合（#329。[Process.waitTimers]）。
 *
 * 一時的なタイマーの持ち主はここだけ（ブロッキング型の `sleep`・`poll_oneoff` も、待機点型の巻き戻しの `poll_oneoff` も）。
 * ふつうは 0〜1 個なので、`IntArray` に並べ、最初に使うときに確保する。カーネルロックの中でだけ使う。
 * 番号だけを持ち、ランタイムの型を参照しない（保存できる）。
 */
internal class WaitTimerSet {
    private var ids: IntArray? = null

    /** 入っている数 */
    var size: Int = 0
        private set

    val isEmpty: Boolean get() = size == 0

    /** [id] を足す（もう入っていれば何もしない） */
    fun add(id: Int) {
        if (contains(id)) return
        val a = ids ?: IntArray(2).also { ids = it }
        if (size == a.size) ids = a.copyOf(a.size * 2)
        ids!![size++] = id
    }

    /** [id] を外す。入っていたら `true` */
    fun remove(id: Int): Boolean {
        val a = ids ?: return false
        for (i in 0 until size) {
            if (a[i] == id) {
                a[i] = a[--size]
                return true
            }
        }
        return false
    }

    fun contains(id: Int): Boolean {
        val a = ids ?: return false
        for (i in 0 until size) if (a[i] == id) return true
        return false
    }

    /** 入っている ID を順に [action] に渡す */
    fun forEach(action: (Int) -> Unit) {
        val a = ids ?: return
        for (i in 0 until size) action(a[i])
    }

    /** 入っている ID の写し */
    fun toIntArray(): IntArray = ids?.copyOf(size) ?: IntArray(0)

    /** 空にする */
    fun clear() {
        size = 0
        ids = null
    }
}
