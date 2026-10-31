package pro.ikili.kibicomputers.core.kernel.obj

/**
 * ハンドル表（番号 → エントリ）。p1 の fd 表も、Component Model の resource ハンドル表（#265）も、この一種にする。
 *
 * 新しいハンドルには、いちばん小さい空き番号を使う。表は小さい配列（8）から倍に広げ、[limit] を超えない。
 */
internal class HandleTable<E : Any>(val limit: Int) {
    init {
        require(limit > 0) { "上限は 1 以上" }
    }

    private var slots: Array<Any?> = arrayOfNulls(minOf(INITIAL_SIZE, limit))

    /** 使っているハンドルの数 */
    var size: Int = 0
        private set

    /** 空き番号がない（[insert] が -1 を返す） */
    val isFull: Boolean get() = size >= limit

    /** いちばん小さい空き番号に [entry] を入れ、その番号を返す。満杯なら -1 */
    fun insert(entry: E): Int {
        for (i in slots.indices) {
            if (slots[i] == null) {
                slots[i] = entry
                size++
                return i
            }
        }
        if (slots.size >= limit) return -1
        val handle = slots.size
        grow(handle)
        slots[handle] = entry
        size++
        return handle
    }

    /** [handle] に [entry] を入れる（dup2 相当）。既存のエントリは置き換える（閉じるのは呼び出し側） */
    fun insertAt(handle: Int, entry: E) {
        require(handle in 0 until limit) { "ハンドル $handle は範囲外（上限 $limit）" }
        if (handle >= slots.size) grow(handle)
        if (slots[handle] == null) size++
        slots[handle] = entry
    }

    @Suppress("UNCHECKED_CAST")
    operator fun get(handle: Int): E? = if (handle in slots.indices) slots[handle] as E? else null

    /** [handle] のエントリを外して返す。なければ `null` */
    fun remove(handle: Int): E? {
        val old = get(handle) ?: return null
        slots[handle] = null
        size--
        return old
    }

    /** 番号の順に並べたエントリ */
    @Suppress("UNCHECKED_CAST")
    fun entries(): Map<Int, E> {
        val result = LinkedHashMap<Int, E>()
        for (i in slots.indices) slots[i]?.let { result[i] = it as E }
        return result
    }

    private fun grow(minIndex: Int) {
        var n = slots.size.coerceAtLeast(1)
        while (n <= minIndex) n *= 2
        slots = slots.copyOf(n.coerceAtMost(limit))
    }

    private companion object {
        const val INITIAL_SIZE = 8
    }
}

/**
 * プロセスのハンドル表のエントリ（保存の対象）。オブジェクトIDと、ハンドル固有の情報（権利、フラグ）を持つ。
 *
 * [rights] はカーネルが見る（[HandleRights]。fd の読み書きで確かめる。#58）。[flags] の意味は ABI 層が決める
 * （p1 の `fdflags` など。#72・#265）。複製（dup）は両方を写す。
 */
internal data class HandleEntry(val objectId: Int, val flags: Long = 0, val rights: Int = HandleRights.ALL)

/** [HandleEntry.rights] のビット */
internal object HandleRights {
    /** 読める */
    const val READ: Int = 1

    /** 書ける */
    const val WRITE: Int = 2

    /** 読み書きできる（オブジェクトの側の権利は別に確かめる。`OpenFile` の読む・書くなど） */
    const val ALL: Int = READ or WRITE
}
