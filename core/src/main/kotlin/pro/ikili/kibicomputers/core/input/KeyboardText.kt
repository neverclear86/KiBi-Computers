package pro.ikili.kibicomputers.core.input

/**
 * キーボードから送る文字列の検査と整形（#142）。
 */
public object KeyboardText {
    /** 1回の貼り付けで送る UTF-8 のバイト数の上限（仮の値。#251 で確定）。超えた分は捨てる。 */
    public const val MAX_PASTE_BYTES: Int = KeyboardEventCodec.MAX_TEXT_BYTES

    /** [text] が [KeyboardEvent.Text] に入れてよい文字列か（空でなく、制御文字と対になっていないサロゲートを含まない）。 */
    @JvmStatic
    public fun isValidText(text: String): Boolean = text.isNotEmpty() && isClean(text, allowLayout = false)

    /** [text] が [KeyboardEvent.Paste] に入れてよい文字列か（空でなく、タブと LF のほかの制御文字を含まない）。 */
    @JvmStatic
    public fun isValidPaste(text: String): Boolean = text.isNotEmpty() && isClean(text, allowLayout = true)

    /**
     * クリップボードの文字列を、貼り付けで送れる形にする。改行（CR LF・CR）を LF にそろえ、タブと LF のほかの制御文字と
     * 対になっていないサロゲートを除き、UTF-8 で [maxBytes] バイトまでに切り詰める（文字の途中では切らない）。
     */
    @JvmStatic
    @JvmOverloads
    public fun sanitizePaste(raw: String, maxBytes: Int = MAX_PASTE_BYTES): String {
        val sb = StringBuilder(minOf(raw.length, maxBytes))
        var bytes = 0
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            var cp: Int
            var n = 1
            if (c == '\r') {
                cp = '\n'.code
                if (i + 1 < raw.length && raw[i + 1] == '\n') n = 2
            } else if (Character.isHighSurrogate(c) && i + 1 < raw.length && Character.isLowSurrogate(raw[i + 1])) {
                cp = Character.toCodePoint(c, raw[i + 1])
                n = 2
            } else {
                cp = c.code
            }
            i += n
            if (Character.isSurrogate(c) && cp == c.code) continue
            if (isControl(cp) && cp != '\n'.code && cp != '\t'.code) continue
            val b = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + b > maxBytes) break
            bytes += b
            sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    /** 制御文字（C0・DEL・C1）か。 */
    @JvmStatic
    public fun isControl(codePoint: Int): Boolean = codePoint < 0x20 || codePoint in 0x7F..0x9F

    private fun isClean(text: String, allowLayout: Boolean): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1])) return false
                i += 2
                continue
            }
            if (Character.isLowSurrogate(c)) return false
            if (isControl(c.code) && !(allowLayout && (c == '\n' || c == '\t'))) return false
            i++
        }
        return true
    }
}

/**
 * UTF-16 の文字（`char`）を1つずつ受け取り、サロゲートの対をつないで1文字にする。
 *
 * Minecraft の画面は、BMP の外の文字（絵文字など）を2つの `char` に分けて渡すので、つなぎ直すのに使う。
 */
public class Utf16Joiner {
    private var high: Char? = null

    /** [c] を足す。1文字そろえばその文字列、まだ（サロゲートの前半を受け取った）なら null。対にならないサロゲートは捨てる。 */
    public fun feed(c: Char): String? {
        val pending = high
        if (Character.isHighSurrogate(c)) {
            high = c
            return null
        }
        high = null
        if (Character.isLowSurrogate(c)) {
            return if (pending != null) String(charArrayOf(pending, c)) else null
        }
        return c.toString()
    }

    /** 途中のサロゲートを捨てる。 */
    public fun reset() {
        high = null
    }
}
