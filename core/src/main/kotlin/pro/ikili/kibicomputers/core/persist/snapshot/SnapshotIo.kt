package pro.ikili.kibicomputers.core.persist.snapshot

import pro.ikili.kibicomputers.core.persist.SnapshotFormatException

/**
 * 節の中身を書く（#82、ADR 0082）。数値はビッグエンディアン、可変長の整数は使わない。文字列は u32 のバイト数 + UTF-8
 * （外枠の `writeUTF` は 65,535 バイトまでで、引数・イベントの文字列はそれを超えうるため）。
 */
internal class SnapshotWriter(initialCapacity: Int = 256) {
    private var buf = ByteArray(maxOf(initialCapacity, 16))

    /** 書いたバイト数 */
    var size: Int = 0
        private set

    private fun room(n: Int) {
        val need = size.toLong() + n
        if (need <= buf.size) return
        check(need <= Int.MAX_VALUE - 8) { "節が大きすぎる" }
        var cap = buf.size.toLong()
        while (cap < need) cap *= 2
        buf = buf.copyOf(cap.coerceAtMost(Int.MAX_VALUE - 8L).toInt())
    }

    fun u8(v: Int) {
        require(v in 0..0xFF) { "u8 の範囲外（$v）" }
        room(1)
        buf[size++] = v.toByte()
    }

    fun bool(v: Boolean) = u8(if (v) 1 else 0)

    fun u16(v: Int) {
        require(v in 0..0xFFFF) { "u16 の範囲外（$v）" }
        room(2)
        buf[size++] = (v ushr 8).toByte()
        buf[size++] = v.toByte()
    }

    fun i32(v: Int) {
        room(4)
        buf[size++] = (v ushr 24).toByte()
        buf[size++] = (v ushr 16).toByte()
        buf[size++] = (v ushr 8).toByte()
        buf[size++] = v.toByte()
    }

    /** 数（u32。0 以上の Int） */
    fun count(v: Int) {
        require(v >= 0) { "数は 0 以上（$v）" }
        i32(v)
    }

    fun i64(v: Long) {
        i32((v ushr 32).toInt())
        i32(v.toInt())
    }

    /** 長さを書かずにそのまま */
    fun raw(bytes: ByteArray, off: Int = 0, len: Int = bytes.size) {
        room(len)
        System.arraycopy(bytes, off, buf, size, len)
        size += len
    }

    /** u32 の長さ + 中身 */
    fun blob(bytes: ByteArray) {
        count(bytes.size)
        raw(bytes)
    }

    /** u32 のバイト数 + UTF-8 */
    fun string(s: String) = blob(s.toByteArray(Charsets.UTF_8))

    /** 書いた中身 */
    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/**
 * 節の中身を読む（#82）。足りない・壊れているときは [SnapshotFormatException]。数は [count] で上限と残りの長さを先に確かめてから使う
 * （壊れた入力で大きな配列を確保しない）。
 */
internal class SnapshotReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {
    init {
        require(pos in 0..end && end <= buf.size) { "範囲外" }
    }

    /** 残りのバイト数 */
    val remaining: Int get() = end - pos

    private fun need(n: Int) {
        if (n < 0 || n > remaining) throw SnapshotFormatException("節が途中で切れている（$n バイト要るが残り $remaining）")
    }

    fun u8(): Int {
        need(1)
        return buf[pos++].toInt() and 0xFF
    }

    fun bool(): Boolean = when (val v = u8()) {
        0 -> false
        1 -> true
        else -> throw SnapshotFormatException("真偽の値が $v")
    }

    fun u16(): Int {
        need(2)
        val v = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
        pos += 2
        return v
    }

    fun i32(): Int {
        need(4)
        val v = ((buf[pos].toInt() and 0xFF) shl 24) or ((buf[pos + 1].toInt() and 0xFF) shl 16) or
            ((buf[pos + 2].toInt() and 0xFF) shl 8) or (buf[pos + 3].toInt() and 0xFF)
        pos += 4
        return v
    }

    fun i64(): Long = (i32().toLong() shl 32) or (i32().toLong() and 0xFFFF_FFFFL)

    /**
     * u32 の数を読み、[max] 以下で、1つあたり少なくとも [minBytesEach] バイトとして残りに収まることを確かめる
     */
    fun count(max: Int, minBytesEach: Int = 0): Int {
        val n = i32().toLong() and 0xFFFF_FFFFL
        if (n > max) throw SnapshotFormatException("数 $n が上限 $max を超える")
        if (n * minBytesEach > remaining) throw SnapshotFormatException("数 $n が残りの長さ $remaining に収まらない")
        return n.toInt()
    }

    /** 長さを読まずに [n] バイト */
    fun raw(n: Int): ByteArray {
        need(n)
        val out = buf.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    /** u32 の長さ（[max] 以下）+ 中身 */
    fun blob(max: Int = Int.MAX_VALUE): ByteArray = raw(count(max, 1))

    /** u32 のバイト数（[maxBytes] 以下）+ UTF-8 */
    fun string(maxBytes: Int = Int.MAX_VALUE): String {
        val bytes = blob(maxBytes)
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw SnapshotFormatException("UTF-8 として読めない文字列", e)
        }
    }

    /** [n] バイトの範囲だけを読む読み手を作り、その分を読み進める */
    fun sub(n: Int): SnapshotReader {
        need(n)
        val r = SnapshotReader(buf, pos, pos + n)
        pos += n
        return r
    }

    /** 読み終わったことを確かめる（余りがあれば壊れている） */
    fun expectEnd() {
        if (remaining != 0) throw SnapshotFormatException("節の終わりに $remaining バイト余っている")
    }
}
