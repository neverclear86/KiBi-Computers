package pro.ikili.kibicomputers.core.kernel.tty

/**
 * [Tty] の入力の履歴（canonical で確定した行。#96）。古い順に持ち、[maxEntries] 行・合計 [maxBytes] バイトを超えたら
 * 古いものから捨てる。中身はバイト列だけ（保存の対象）。
 */
internal class TtyHistory(private val maxEntries: Int, private val maxBytes: Int) {
    init {
        require(maxEntries >= 1 && maxBytes >= 1) { "履歴の上限は 1 以上" }
    }

    private val entries = ArrayList<ByteArray>(0)

    /** 行の数 */
    val count: Int get() = entries.size

    /** 合計のバイト数 */
    var totalBytes: Int = 0
        private set

    /** 古いほうから [index] 番目の行 */
    operator fun get(index: Int): ByteArray = entries[index]

    /** 新しい行として足す。空の行・[maxBytes] より長い行・直前と同じ行は足さない */
    fun add(line: ByteArray) {
        if (line.isEmpty() || line.size > maxBytes) return
        if (entries.isNotEmpty() && entries[entries.size - 1].contentEquals(line)) return
        entries += line
        totalBytes += line.size
        while (entries.size > maxEntries || totalBytes > maxBytes) totalBytes -= entries.removeAt(0).size
    }

    fun clear() {
        entries.clear()
        entries.trimToSize()
        totalBytes = 0
    }
}
