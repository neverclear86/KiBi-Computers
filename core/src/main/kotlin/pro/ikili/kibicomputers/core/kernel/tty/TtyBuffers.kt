package pro.ikili.kibicomputers.core.kernel.tty

/**
 * [Tty] のバイトの列（先頭から取り出し、後ろに足す）。使うときに小さく確保して [limit] まで倍に広げ、空になったら手放す。
 * 中身は番号とバイト列だけ（保存の対象）。
 */
internal class TtyBytes(private val limit: Int) {
    private var buffer: ByteArray? = null
    private var head = 0

    /** 入っているバイト数 */
    var length: Int = 0
        private set

    /** 確保しているバッファの大きさ（テスト用） */
    val allocatedBytes: Int get() = buffer?.size ?: 0

    /** 先頭から [index] 番目のバイト */
    operator fun get(index: Int): Byte {
        require(index in 0 until length) { "範囲外: $index" }
        return buffer!![head + index]
    }

    /** 後ろに1バイト足す（呼び出し側が [limit] を超えないことを確かめる） */
    fun add(b: Byte) {
        ensureRoom(1)
        buffer!![head + length] = b
        length++
    }

    /** 後ろに [other] の中身を全部足す */
    fun addAll(other: TtyBytes) {
        if (other.length == 0) return
        ensureRoom(other.length)
        System.arraycopy(other.buffer!!, other.head, buffer!!, head + length, other.length)
        length += other.length
    }

    /** 先頭から [n] バイトを [dst] の [off] に写して取り除く */
    fun take(dst: ByteArray, off: Int, n: Int) {
        require(n in 0..length) { "範囲外: $n" }
        if (n == 0) return
        System.arraycopy(buffer!!, head, dst, off, n)
        head += n
        length -= n
        if (length == 0) clear()
    }

    /** 中身を全部取り出す */
    fun takeAll(): ByteArray {
        val out = ByteArray(length)
        take(out, 0, length)
        return out
    }

    /** 後ろから [n] バイトを取り除く */
    fun dropLast(n: Int) {
        require(n in 0..length) { "範囲外: $n" }
        length -= n
        if (length == 0) clear()
    }

    /** 空にしてバッファを手放す */
    fun clear() {
        buffer = null
        head = 0
        length = 0
    }

    private fun ensureRoom(n: Int) {
        val needed = length + n
        check(needed <= limit) { "上限 $limit を超える" }
        val current = buffer
        if (current != null && head + needed <= current.size) return
        if (current != null && needed <= current.size) {
            System.arraycopy(current, head, current, 0, length)
            head = 0
            return
        }
        var size = (current?.size ?: INITIAL_BUFFER).coerceAtMost(limit)
        while (size < needed) size = (size.toLong() * 2).coerceAtMost(limit.toLong()).toInt()
        val next = ByteArray(size)
        if (current != null) System.arraycopy(current, head, next, 0, length)
        buffer = next
        head = 0
    }

    private companion object {
        /** 最初に確保する大きさ（仮の値） */
        const val INITIAL_BUFFER = 64
    }
}

/** [Tty] の行の区切り（行ごとの長さの列。0 は Ctrl+D の「終わり」の印）。番号だけを持つ */
internal class TtyLines {
    private var lengths: IntArray? = null
    private var head = 0

    /** 区切りの数 */
    var count: Int = 0
        private set

    /** 先頭の長さ */
    val first: Int get() = check(count > 0).let { lengths!![head] }

    /** 最後の長さ */
    val last: Int get() = check(count > 0).let { lengths!![head + count - 1] }

    fun add(length: Int) {
        val current = lengths
        if (current == null) {
            lengths = IntArray(INITIAL).also { it[0] = length }
            head = 0
            count = 1
            return
        }
        if (head + count == current.size) {
            val next = if (count < current.size / 2) current else IntArray(current.size * 2)
            System.arraycopy(current, head, next, 0, count)
            if (next === current) java.util.Arrays.fill(current, count, current.size, 0)
            lengths = next
            head = 0
        }
        lengths!![head + count] = length
        count++
    }

    /** 先頭の長さを [n] 減らす。0 になったら取り除く */
    fun consumeFirst(n: Int) {
        val arr = lengths!!
        arr[head] -= n
        if (arr[head] == 0) removeFirst()
    }

    fun removeFirst() {
        check(count > 0)
        head++
        count--
        if (count == 0) clear()
    }

    fun clear() {
        lengths = null
        head = 0
        count = 0
    }

    private companion object {
        const val INITIAL = 8
    }
}
