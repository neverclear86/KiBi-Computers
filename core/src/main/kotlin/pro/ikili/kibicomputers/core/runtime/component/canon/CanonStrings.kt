package pro.ikili.kibicomputers.core.runtime.component.canon

import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonContext.Companion.u32
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * `string` の lift・lower（Canonical ABI の `load_string_from_range`・`store_string_into_range`。ADR 0264）。
 * `realloc` を呼ぶ回数・順・引数は、ゲストから見えるので、仕様の手順をそのまま写す
 * （メモリへの書き込みは、間にゲストの呼び出しがない区間でまとめて書く。結果のバイト列は同じ）
 */
internal object CanonStrings {
    private const val TAG: Long = CanonLimits.UTF16_TAG

    /** 対になっていないサロゲートを U+FFFD にした文字列（なければ [s] そのもの） */
    fun toScalarValues(s: String): String {
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s[i + 1])) {
                i += 2
                continue
            }
            if (Character.isSurrogate(c)) return replaceLoneSurrogates(s)
            i++
        }
        return s
    }

    private fun replaceLoneSurrogates(s: String): String {
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                b.append(c).append(s[i + 1])
                i += 2
            } else {
                b.append(if (Character.isSurrogate(c)) '�' else c)
                i++
            }
        }
        return b.toString()
    }

    /** スカラー値だけの [s] の UTF-8 のバイト数 */
    fun utf8Length(s: String): Long {
        var n = 0L
        var i = 0
        while (i < s.length) {
            val c = s[i].code
            n += when {
                c < 0x80 -> 1
                c < 0x800 -> 2
                Character.isHighSurrogate(s[i]) -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return n
    }

    // ---- lift ----

    /** ゲストの `(ptr, tagged_code_units)`（u32 のビット列）から文字列を読む */
    @Throws(WasmTrapException::class)
    fun load(cx: CanonContext, ptrBits: Int, taggedBits: Int): CanonValue.Str {
        val ptr = u32(ptrBits)
        val tagged = u32(taggedBits)
        val align: Int
        val byteLength: Long
        val kind: Simple
        when (cx.encoding) {
            CanonStringEncoding.UTF8 -> {
                align = 1
                byteLength = tagged
                kind = Simple.UTF8
            }
            CanonStringEncoding.UTF16 -> {
                align = 2
                byteLength = 2 * tagged
                kind = Simple.UTF16
            }
            CanonStringEncoding.LATIN1_UTF16 -> {
                align = 2
                if (tagged and TAG != 0L) {
                    byteLength = 2 * (tagged xor TAG)
                    kind = Simple.UTF16
                } else {
                    byteLength = tagged
                    kind = Simple.LATIN1
                }
            }
        }
        if (byteLength > CanonLimits.MAX_STRING_BYTE_LENGTH) CanonTraps.trap("文字列が長すぎる（$byteLength バイト）")
        cx.checkAligned(ptr, align, "文字列")
        cx.checkInMemory(ptr, byteLength, "文字列")
        cx.budget.take(byteLength, 1)
        val bytes = cx.mem.read(ptr.toInt(), byteLength.toInt())
        val s = when (kind) {
            Simple.UTF8 -> decodeUtf8(bytes)
            Simple.UTF16 -> decodeUtf16(bytes)
            Simple.LATIN1 -> decodeLatin1(bytes)
        }
        return CanonValue.Str(s, cx.encoding, tagged)
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        CanonTraps.trap("文字列が正しい UTF-8 でない")
    }

    /** UTF-16LE。対になっていないサロゲートはトラップ */
    private fun decodeUtf16(bytes: ByteArray): String {
        val n = bytes.size / 2
        val chars = CharArray(n)
        for (i in 0 until n) chars[i] = ((bytes[2 * i].toInt() and 0xff) or ((bytes[2 * i + 1].toInt() and 0xff) shl 8)).toChar()
        var i = 0
        while (i < n) {
            val c = chars[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= n || !Character.isLowSurrogate(chars[i + 1])) CanonTraps.trap("文字列が正しい UTF-16 でない")
                i += 2
            } else {
                if (Character.isLowSurrogate(c)) CanonTraps.trap("文字列が正しい UTF-16 でない")
                i++
            }
        }
        return String(chars)
    }

    private fun decodeLatin1(bytes: ByteArray): String = String(bytes, Charsets.ISO_8859_1)

    // ---- lower ----

    /** 元の符号化（latin1+utf16 を分けたもの） */
    private enum class Simple { UTF8, UTF16, LATIN1 }

    /** [v] を線形メモリに置き、`(ptr, tagged_code_units)` を返す（`store_string_into_range`） */
    @Throws(WasmTrapException::class)
    fun store(cx: CanonContext, v: CanonValue.Str): LongArray {
        val tagged = v.taggedCodeUnits
        val simple: Simple
        val units: Long
        if (v.encoding == CanonStringEncoding.LATIN1_UTF16) {
            if (tagged and TAG != 0L) {
                simple = Simple.UTF16
                units = tagged xor TAG
            } else {
                simple = Simple.LATIN1
                units = tagged
            }
        } else {
            simple = if (v.encoding == CanonStringEncoding.UTF8) Simple.UTF8 else Simple.UTF16
            units = tagged
        }
        val s = v.value
        return when (cx.encoding) {
            CanonStringEncoding.UTF8 -> when (simple) {
                Simple.UTF8 -> copy(cx, units, 1, 1, utf8(s))
                Simple.UTF16 -> toUtf8(cx, s, units, units * 3)
                Simple.LATIN1 -> toUtf8(cx, s, units, units * 2)
            }
            CanonStringEncoding.UTF16 -> when (simple) {
                Simple.UTF8 -> utf8ToUtf16(cx, s, units)
                Simple.UTF16, Simple.LATIN1 -> copy(cx, units, 2, 2, utf16(s))
            }
            CanonStringEncoding.LATIN1_UTF16 -> when (v.encoding) {
                CanonStringEncoding.UTF8, CanonStringEncoding.UTF16 -> toLatin1OrUtf16(cx, s, units)
                CanonStringEncoding.LATIN1_UTF16 -> when (simple) {
                    Simple.LATIN1 -> copy(cx, units, 1, 2, latin1(s))
                    else -> probablyUtf16ToLatin1OrUtf16(cx, s, units)
                }
            }
        }
    }

    /** `store_string_copy`: 元と同じ長さで 1 回だけ取って写す */
    private fun copy(cx: CanonContext, units: Long, unitSize: Int, align: Int, encoded: ByteArray): LongArray {
        val dstLength = unitSize * units
        check(dstLength == encoded.size.toLong()) { "文字列の長さが合わない（$dstLength / ${encoded.size}）" }
        cx.budget.take(dstLength, 1)
        val ptr = cx.allocate(align, dstLength)
        write(cx, ptr, encoded, 0, encoded.size)
        return longArrayOf(ptr, units)
    }

    /** `store_string_to_utf8`: 元の長さで取り、ASCII でない文字を見たら最悪の大きさに広げ、最後に縮める */
    private fun toUtf8(cx: CanonContext, s: String, units: Long, worstCase: Long): LongArray {
        cx.budget.take(worstCase, 1)
        var ptr = cx.allocate(1, units)
        val k = s.indexOfFirst { it.code >= 0x80 }
        if (k < 0) {
            write(cx, ptr, latin1(s), 0, s.length)
            return longArrayOf(ptr, units)
        }
        // k より前は ASCII なので、文字の番号とバイトの位置が同じ
        write(cx, ptr, latin1(s.substring(0, k)), 0, k)
        ptr = cx.reallocate(ptr, units, 1, worstCase)
        val encoded = utf8(s)
        write(cx, ptr + k, encoded, k, encoded.size - k)
        if (worstCase > encoded.size) ptr = cx.reallocate(ptr, worstCase, 1, encoded.size.toLong())
        return longArrayOf(ptr, encoded.size.toLong())
    }

    /** `store_utf8_to_utf16`: 最悪の大きさ（UTF-8 のバイト数 × 2）で取り、短ければ縮める */
    private fun utf8ToUtf16(cx: CanonContext, s: String, units: Long): LongArray {
        val worstCase = 2 * units
        cx.budget.take(worstCase, 1)
        var ptr = cx.allocate(2, worstCase)
        val encoded = utf16(s)
        write(cx, ptr, encoded, 0, encoded.size)
        if (encoded.size < worstCase) ptr = cx.reallocate(ptr, worstCase, 2, encoded.size.toLong())
        return longArrayOf(ptr, encoded.size / 2L)
    }

    /** `store_string_to_latin1_or_utf16`: latin1 として取り、2^8 以上の文字を見たら UTF-16 に広げる */
    private fun toLatin1OrUtf16(cx: CanonContext, s: String, units: Long): LongArray {
        val worstCase = 2 * units
        cx.budget.take(worstCase, 1)
        var ptr = cx.allocate(2, units)
        val k = s.indexOfFirst { it.code >= 0x100 }
        if (k < 0) {
            // すべて latin1（文字の数 = バイト数）
            val dstLength = s.length.toLong()
            write(cx, ptr, latin1(s), 0, s.length)
            if (dstLength < units) ptr = cx.reallocate(ptr, units, 2, dstLength)
            return longArrayOf(ptr, dstLength)
        }
        write(cx, ptr, latin1(s.substring(0, k)), 0, k)
        ptr = cx.reallocate(ptr, units, 2, worstCase)
        // 仕様どおり、取り直した場所にある latin1 の k バイトを、その場で UTF-16 に広げる
        val head = cx.mem.read(ptr.toInt(), k)
        val widened = ByteArray(2 * k)
        for (j in 0 until k) widened[2 * j] = head[j]
        write(cx, ptr, widened, 0, widened.size)
        val encoded = utf16(s)
        write(cx, ptr + 2 * k, encoded, 2 * k, encoded.size - 2 * k)
        if (worstCase > encoded.size) ptr = cx.reallocate(ptr, worstCase, 2, encoded.size.toLong())
        return longArrayOf(ptr, (encoded.size / 2L) or TAG)
    }

    /** `store_probably_utf16_to_latin1_or_utf16`: UTF-16 で書き、すべて latin1 なら詰めて縮める */
    private fun probablyUtf16ToLatin1OrUtf16(cx: CanonContext, s: String, units: Long): LongArray {
        val srcByteLength = 2 * units
        cx.budget.take(srcByteLength, 1)
        var ptr = cx.allocate(2, srcByteLength)
        val encoded = utf16(s)
        write(cx, ptr, encoded, 0, encoded.size)
        if (s.any { it.code >= 0x100 }) return longArrayOf(ptr, (encoded.size / 2L) or TAG)
        val latin1Size = encoded.size / 2
        write(cx, ptr, latin1(s), 0, latin1Size)
        ptr = cx.reallocate(ptr, srcByteLength, 2, latin1Size.toLong())
        return longArrayOf(ptr, latin1Size.toLong())
    }

    private fun write(cx: CanonContext, ptr: Long, src: ByteArray, offset: Int, length: Int) {
        if (length > 0) cx.mem.write(ptr.toInt(), src, offset, length)
    }

    private fun utf8(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    private fun utf16(s: String): ByteArray = s.toByteArray(Charsets.UTF_16LE)

    /** すべて 0x100 未満の文字だけの [s] の latin1 */
    private fun latin1(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)
}
