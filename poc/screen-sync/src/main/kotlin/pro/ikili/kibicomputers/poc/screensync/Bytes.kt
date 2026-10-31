package pro.ikili.kibicomputers.poc.screensync

import java.util.zip.Deflater
import java.util.zip.Inflater

/** 伸びるバイト列。パケットの組み立てに使う。 */
class Buf(capacity: Int = 256) {
    var bytes = ByteArray(capacity)
        private set
    var size = 0
        private set

    fun clear() {
        size = 0
    }

    private fun ensure(extra: Int) {
        if (size + extra > bytes.size) {
            bytes = bytes.copyOf(maxOf(bytes.size * 2, size + extra))
        }
    }

    fun byte(v: Int) {
        ensure(1)
        bytes[size++] = v.toByte()
    }

    /** 符号なし LEB128。 */
    fun varint(v: Int) {
        ensure(5)
        var x = v
        while (x and 0x7F.inv() != 0) {
            bytes[size++] = ((x and 0x7F) or 0x80).toByte()
            x = x ushr 7
        }
        bytes[size++] = x.toByte()
    }

    /** 符号付き（ZigZag）。 */
    fun svarint(v: Int) = varint((v shl 1) xor (v shr 31))

    fun rgb(c: Int) {
        ensure(3)
        bytes[size++] = (c shr 16).toByte()
        bytes[size++] = (c shr 8).toByte()
        bytes[size++] = c.toByte()
    }

    fun put(src: ByteArray, off: Int = 0, len: Int = src.size) {
        ensure(len)
        System.arraycopy(src, off, bytes, size, len)
        size += len
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)
}

/** [Buf] で書いたものを読む。 */
class Reader(private val a: ByteArray, private var p: Int = 0, private val end: Int = a.size) {
    val hasMore: Boolean get() = p < end

    fun byte(): Int = a[p++].toInt() and 0xFF

    fun varint(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = byte()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    fun svarint(): Int {
        val v = varint()
        return (v ushr 1) xor -(v and 1)
    }

    fun rgb(): Int {
        val r = byte()
        val g = byte()
        val b = byte()
        return (r shl 16) or (g shl 8) or b
    }

    fun get(dst: ByteArray, off: Int, len: Int) {
        System.arraycopy(a, p, dst, off, len)
        p += len
    }
}

object Zip {
    fun deflate(src: ByteArray, len: Int = src.size, level: Int = Deflater.BEST_SPEED): ByteArray {
        val d = Deflater(level)
        try {
            d.setInput(src, 0, len)
            d.finish()
            val out = Buf(len / 4 + 64)
            val chunk = ByteArray(64 * 1024)
            while (!d.finished()) {
                val n = d.deflate(chunk)
                out.put(chunk, 0, n)
            }
            return out.toByteArray()
        } finally {
            d.end()
        }
    }

    fun inflate(src: ByteArray, off: Int, len: Int, outSize: Int): ByteArray {
        val i = Inflater()
        try {
            i.setInput(src, off, len)
            val out = ByteArray(outSize)
            var n = 0
            while (n < outSize) {
                val k = i.inflate(out, n, outSize - n)
                if (k == 0 && (i.finished() || i.needsInput())) break
                n += k
            }
            return out
        } finally {
            i.end()
        }
    }

    /**
     * Minecraft のパケット圧縮を真似たときの、回線に乗るバイト数。
     * バニラは既定で 256 バイト以上のパケットを zlib（既定のレベル）で圧縮する。
     */
    fun wireSize(payload: ByteArray, threshold: Int = 256): Int {
        val header = 3 // パケット長・ID などのおおよそのヘッダー
        if (payload.size < threshold) return payload.size + header
        return deflate(payload, payload.size, Deflater.DEFAULT_COMPRESSION).size + header + 3
    }
}
