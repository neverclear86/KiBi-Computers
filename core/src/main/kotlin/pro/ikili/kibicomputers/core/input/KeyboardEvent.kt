package pro.ikili.kibicomputers.core.input

import org.jetbrains.annotations.ApiStatus
import java.util.UUID

/**
 * キーボードから届く入力の1つ（#142）。キーの押し下げ・離し（キーコード）と、打った文字を別々に送る。
 *
 * 文字（[Text]）は、キーボードの配列・Shift・IME（日本語入力の確定）を通したあとのもの。
 * 1回のキーの押し下げから、[KeyDown] と [Text] の両方が届くことがある（Ctrl などを押しているときは [Text] は来ない）。
 */
@ApiStatus.Internal
public sealed class KeyboardEvent {
    /**
     * キーを押した。押し続けたときの繰り返しなら [repeat] が true。
     *
     * @property modifiers 押したときの修飾キー（[KeyModifiers] のビット）
     */
    public data class KeyDown(
        @JvmField public val key: Key,
        @JvmField public val modifiers: Int,
        @JvmField public val repeat: Boolean,
    ) : KeyboardEvent()

    /**
     * キーを離した。
     *
     * @property modifiers 離したときの修飾キー（[KeyModifiers] のビット）
     */
    public data class KeyUp(
        @JvmField public val key: Key,
        @JvmField public val modifiers: Int,
    ) : KeyboardEvent()

    /** 打った文字（IME で確定した文字列を含む）。制御文字は入らない。 */
    public data class Text(@JvmField public val text: String) : KeyboardEvent()

    /** クリップボードから貼り付けた文字列。改行は LF にそろえてあり、タブと改行のほかの制御文字は入らない。 */
    public data class Paste(@JvmField public val text: String) : KeyboardEvent()
}

/**
 * キーボードからの入力を受け取る相手。キーボードのブロックに登録し、サーバーのメインスレッドで呼ばれる。
 *
 * 1つのキーボードにつながる相手は1つだけ（#360）。
 */
@ApiStatus.Internal
public fun interface KeyboardListener {
    /**
     * [event] が届いた。
     *
     * @param typist 打ったプレイヤー（入力のセッション [KeyboardSession] を開いた人）の UUID。
     *   プロセスに渡すかどうかは ABI の論点なので、#63・#326 で決める
     */
    public fun onKeyboardEvent(typist: UUID, event: KeyboardEvent)
}
