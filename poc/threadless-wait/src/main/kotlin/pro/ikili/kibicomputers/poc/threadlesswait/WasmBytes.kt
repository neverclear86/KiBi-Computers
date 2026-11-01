package pro.ikili.kibicomputers.poc.threadlesswait

import java.io.ByteArrayOutputStream

/** 変換できない WASM。 */
class AsyncifyException(message: String) : RuntimeException(message)

/** バイト列の書き出し（core の `FuelInjector` と同じもの。core のものは internal なので写した） */
internal class Sink : ByteArrayOutputStream() {
    fun u8(b: Int) = write(b and 0xFF)

    fun bytes(src: ByteArray, off: Int, len: Int) = write(src, off, len)

    fun append(other: Sink) = other.writeTo(this)

    fun leb(value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                u8(b)
                return
            }
            u8(b or 0x80)
        }
    }

    fun leb(value: Int) = leb(value.toLong())

    fun sleb(value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v shr 7
            val done = (v == 0L && b and 0x40 == 0) || (v == -1L && b and 0x40 != 0)
            if (done) {
                u8(b)
                return
            }
            u8(b or 0x80)
        }
    }

    fun name(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        leb(b.size.toLong())
        write(b)
    }
}

/** バイト列の読み込み */
internal class Reader(val buf: ByteArray, var pos: Int, val end: Int) {
    fun u8(): Int {
        if (pos >= end) throw AsyncifyException("セクションの終わりを超えて読んだ")
        return buf[pos++].toInt() and 0xFF
    }

    fun leb(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = u8()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    fun sleb(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = u8()
            result = result or ((b and 0x7F).toLong() shl shift)
            shift += 7
            if (b and 0x80 == 0) {
                if (shift < 64 && b and 0x40 != 0) result = result or (-1L shl shift)
                return result
            }
        }
    }

    fun skipLeb() {
        while (u8() and 0x80 != 0) Unit
    }

    fun name(): String {
        val len = leb().toInt()
        val s = String(buf, pos, len, Charsets.UTF_8)
        pos += len
        return s
    }

    fun copyValType(out: Sink) {
        val start = pos
        val t = u8()
        if (t == 0x63 || t == 0x64) skipLeb()
        out.bytes(buf, start, pos - start)
    }

    /** 定数式を写す。`ref.func` の番号だけ付け替える */
    fun copyConstExpr(out: Sink, remap: (Long) -> Long) {
        while (true) {
            val start = pos
            when (val op = u8()) {
                0x0B -> {
                    out.u8(op)
                    return
                }
                0x41, 0x42, 0x23 -> {
                    skipLeb()
                    out.bytes(buf, start, pos - start)
                }
                0x43 -> {
                    pos += 4
                    out.bytes(buf, start, pos - start)
                }
                0x44 -> {
                    pos += 8
                    out.bytes(buf, start, pos - start)
                }
                0xD0 -> {
                    skipLeb()
                    out.bytes(buf, start, pos - start)
                }
                0xD2 -> {
                    out.u8(op)
                    out.leb(remap(leb()))
                }
                0x6A, 0x6B, 0x6C, 0x7C, 0x7D, 0x7E -> out.u8(op)
                else -> throw AsyncifyException("定数式の命令 0x%02X には対応していない".format(op))
            }
        }
    }
}
