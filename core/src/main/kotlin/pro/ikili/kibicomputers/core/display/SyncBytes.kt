package pro.ikili.kibicomputers.core.display

/**
 * 画面の同期（ADR 0031）で使う、伸びるバイト列。
 *
 * 整数は可変長（LEB128）で書く。符号のある値（座標・ずれ）は ZigZag にしてから書く。色は 3 バイト（RGB）で書く。
 */
internal class SyncWriter(initialCapacity: Int = 256) {
    var bytes: ByteArray = ByteArray(initialCapacity.coerceAtLeast(16))
        private set

    var size: Int = 0
        private set

    fun reset() {
        size = 0
    }

    fun byte(v: Int) {
        ensure(1)
        bytes[size++] = v.toByte()
    }

    /** 負でない整数を可変長で書く。 */
    fun uint(v: Int) {
        ensure(5)
        var x = v
        while (x and 0x7F.inv() != 0) {
            bytes[size++] = ((x and 0x7F) or 0x80).toByte()
            x = x ushr 7
        }
        bytes[size++] = x.toByte()
    }

    /** 符号のある整数を ZigZag にして可変長で書く。 */
    fun sint(v: Int) = uint((v shl 1) xor (v shr 31))

    fun rgb(v: Int) {
        ensure(3)
        bytes[size++] = (v shr 16).toByte()
        bytes[size++] = (v shr 8).toByte()
        bytes[size++] = v.toByte()
    }

    fun put(src: ByteArray, offset: Int, length: Int) {
        ensure(length)
        System.arraycopy(src, offset, bytes, size, length)
        size += length
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)

    private fun ensure(n: Int) {
        if (size + n <= bytes.size) return
        val need = size.toLong() + n
        require(need <= Int.MAX_VALUE - 8) { "sync data too large" }
        var cap = bytes.size.toLong()
        while (cap < need) cap *= 2
        bytes = bytes.copyOf(cap.coerceAtMost(Int.MAX_VALUE - 8L).toInt())
    }
}

/** [SyncWriter] で書いたバイト列を読む。壊れたデータを読むと [IllegalArgumentException] を投げる。 */
internal class SyncReader(private val bytes: ByteArray, private var pos: Int = 0, private val end: Int = bytes.size) {
    val hasMore: Boolean
        get() = pos < end

    fun byte(): Int {
        require(pos < end) { "unexpected end of sync data" }
        return bytes[pos++].toInt() and 0xFF
    }

    fun uint(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = byte()
            require(shift < 32) { "varint too long" }
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    fun sint(): Int {
        val v = uint()
        return (v ushr 1) xor -(v and 1)
    }

    fun rgb(): Int {
        require(end - pos >= 3) { "unexpected end of sync data" }
        val v = ((bytes[pos].toInt() and 0xFF) shl 16) or
            ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
            (bytes[pos + 2].toInt() and 0xFF)
        pos += 3
        return v
    }

    /** 負でない整数を読み、[max] 以下であることを確かめる。 */
    fun count(max: Int): Int {
        val v = uint()
        require(v in 0..max) { "count out of range: $v" }
        return v
    }

    /** 残りのバイト数。 */
    val remaining: Int
        get() = end - pos
}
