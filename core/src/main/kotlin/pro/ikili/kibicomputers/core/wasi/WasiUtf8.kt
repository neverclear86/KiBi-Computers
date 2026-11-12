package pro.ikili.kibicomputers.core.wasi

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * ゲストに渡す文字列の UTF-8 化。p1 の層（`P1Canonical`）と、ABI に依らない意味の層（`KibiProcess` の `list-processes` の切り詰め）が
 * 同じものを使う（エンコーダーを1つにして、数えたバイト数と書くバイト列がずれないようにする）。
 */
internal object WasiUtf8 {
    /** U+FFFD の UTF-8 */
    private val REPLACEMENT = byteArrayOf(0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte())

    /** [s] を UTF-8 にする。対になっていないサロゲートは U+FFFD にする */
    fun encode(s: String): ByteArray {
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .replaceWith(REPLACEMENT)
        val buf = encoder.encode(CharBuffer.wrap(s))
        return ByteArray(buf.remaining()).also { buf.get(it) }
    }
}
