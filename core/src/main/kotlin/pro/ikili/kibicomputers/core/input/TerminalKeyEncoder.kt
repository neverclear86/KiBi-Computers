package pro.ikili.kibicomputers.core.input

import org.jetbrains.annotations.ApiStatus
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * キーボードの入力を、端末（TTY）に流すバイト列にする（#142）。xterm と同じ並びにする。
 *
 * - 文字（[KeyboardEvent.Text]）は UTF-8 のまま
 * - 貼り付け（[KeyboardEvent.Paste]）は UTF-8 にし、LF を CR にする（Enter と同じ。TTY が LF に戻す）
 * - Enter は CR、Backspace は DEL（0x7F）、Esc は ESC、Tab は HT、Shift+Tab は `ESC [ Z`
 * - 矢印・Home・End・Insert・Delete・PageUp・PageDown・F1〜F12 はエスケープシーケンス。修飾キーがあれば
 *   `ESC [ 1 ; m A` の形で、m は 1 + Shift(1) + Alt(2) + Ctrl(4)
 * - Ctrl+英字は制御文字（Ctrl+A = 0x01 … Ctrl+Z = 0x1A）。Ctrl+Space・Ctrl+2 は NUL、Ctrl+[ は ESC など
 * - Alt+キーは ESC を前に付ける（Alt+x = `ESC x`）
 * - Ctrl と Alt を同時に押しているときは AltGr（欧州の配列で記号を打つキー）とみなし、キーからは何も作らない
 *   （打った文字が [KeyboardEvent.Text] で届く）
 * - キーを離したこと（[KeyboardEvent.KeyUp]）は何も作らない
 */
@ApiStatus.Internal
public object TerminalKeyEncoder {
    private const val ESC = 0x1B

    /** [event] を端末へのバイト列にする。何も送らない入力なら空の配列。 */
    @JvmStatic
    public fun encode(event: KeyboardEvent): ByteArray = when (event) {
        is KeyboardEvent.Text -> event.text.toByteArray(StandardCharsets.UTF_8)
        is KeyboardEvent.Paste -> event.text.replace('\n', '\r').toByteArray(StandardCharsets.UTF_8)
        is KeyboardEvent.KeyUp -> EMPTY
        is KeyboardEvent.KeyDown -> encodeKey(event.key, event.modifiers)
    }

    /** [events] を順に端末へのバイト列にして、つなげる。 */
    @JvmStatic
    public fun encodeAll(events: List<KeyboardEvent>): ByteArray {
        val out = ByteArrayOutputStream()
        for (e in events) out.write(encode(e))
        return out.toByteArray()
    }

    private fun encodeKey(key: Key, modifiers: Int): ByteArray {
        val shift = modifiers and KeyModifiers.SHIFT != 0
        val ctrl = modifiers and KeyModifiers.CONTROL != 0
        val alt = modifiers and KeyModifiers.ALT != 0
        if (ctrl && alt) return EMPTY // AltGr
        val param = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
        when (key) {
            Key.ENTER, Key.KEYPAD_ENTER -> return withAlt(alt, 0x0D)
            Key.TAB -> return if (shift) csi("Z") else withAlt(alt, 0x09)
            Key.BACKSPACE -> return withAlt(alt, if (ctrl) 0x08 else 0x7F)
            Key.ESCAPE -> return withAlt(alt, ESC)
            Key.UP -> return cursor('A', param)
            Key.DOWN -> return cursor('B', param)
            Key.RIGHT -> return cursor('C', param)
            Key.LEFT -> return cursor('D', param)
            Key.HOME -> return cursor('H', param)
            Key.END -> return cursor('F', param)
            Key.INSERT -> return tilde(2, param)
            Key.DELETE -> return tilde(3, param)
            Key.PAGE_UP -> return tilde(5, param)
            Key.PAGE_DOWN -> return tilde(6, param)
            Key.F1 -> return function('P', param)
            Key.F2 -> return function('Q', param)
            Key.F3 -> return function('R', param)
            Key.F4 -> return function('S', param)
            Key.F5 -> return tilde(15, param)
            Key.F6 -> return tilde(17, param)
            Key.F7 -> return tilde(18, param)
            Key.F8 -> return tilde(19, param)
            Key.F9 -> return tilde(20, param)
            Key.F10 -> return tilde(21, param)
            Key.F11 -> return tilde(23, param)
            Key.F12 -> return tilde(24, param)
            else -> {}
        }
        if (ctrl) {
            val letter = key.letter
            if (letter != null) return byteArrayOf((letter.code - 'A'.code + 1).toByte())
            return when (key) {
                Key.SPACE, Key.DIGIT_2 -> byteArrayOf(0)
                Key.LEFT_BRACKET, Key.DIGIT_3 -> byteArrayOf(ESC.toByte())
                Key.BACKSLASH, Key.DIGIT_4 -> byteArrayOf(0x1C)
                Key.RIGHT_BRACKET, Key.DIGIT_5 -> byteArrayOf(0x1D)
                Key.DIGIT_6 -> byteArrayOf(0x1E)
                Key.MINUS, Key.SLASH, Key.DIGIT_7 -> byteArrayOf(0x1F)
                Key.DIGIT_8 -> byteArrayOf(0x7F)
                else -> EMPTY
            }
        }
        if (alt) {
            // Alt を押すと文字が届かないので、US 配列の文字を ESC のあとに付ける
            val c = usChar(key, shift) ?: return EMPTY
            return byteArrayOf(ESC.toByte(), c.code.toByte())
        }
        return EMPTY
    }

    private fun withAlt(alt: Boolean, b: Int): ByteArray =
        if (alt) byteArrayOf(ESC.toByte(), b.toByte()) else byteArrayOf(b.toByte())

    private fun csi(rest: String): ByteArray = ("\u001b[$rest").toByteArray(StandardCharsets.US_ASCII)

    private fun cursor(final: Char, param: Int): ByteArray = if (param == 1) csi("$final") else csi("1;$param$final")

    private fun tilde(code: Int, param: Int): ByteArray = if (param == 1) csi("$code~") else csi("$code;$param~")

    private fun function(final: Char, param: Int): ByteArray =
        if (param == 1) "\u001bO$final".toByteArray(StandardCharsets.US_ASCII) else csi("1;$param$final")

    /** US 配列で [key] を押したときの文字（ASCII）。 */
    private fun usChar(key: Key, shift: Boolean): Char? {
        key.letter?.let { return if (shift) it else it.lowercaseChar() }
        val pair = when (key) {
            Key.DIGIT_0 -> "0)"
            Key.DIGIT_1 -> "1!"
            Key.DIGIT_2 -> "2@"
            Key.DIGIT_3 -> "3#"
            Key.DIGIT_4 -> "4$"
            Key.DIGIT_5 -> "5%"
            Key.DIGIT_6 -> "6^"
            Key.DIGIT_7 -> "7&"
            Key.DIGIT_8 -> "8*"
            Key.DIGIT_9 -> "9("
            Key.SPACE -> "  "
            Key.APOSTROPHE -> "'\""
            Key.COMMA -> ",<"
            Key.MINUS -> "-_"
            Key.PERIOD -> ".>"
            Key.SLASH -> "/?"
            Key.SEMICOLON -> ";:"
            Key.EQUAL -> "=+"
            Key.LEFT_BRACKET -> "[{"
            Key.BACKSLASH -> "\\|"
            Key.RIGHT_BRACKET -> "]}"
            Key.GRAVE_ACCENT -> "`~"
            else -> return null
        }
        return if (shift) pair[1] else pair[0]
    }

    private val EMPTY = ByteArray(0)
}
