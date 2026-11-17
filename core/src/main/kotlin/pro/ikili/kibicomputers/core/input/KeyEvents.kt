package pro.ikili.kibicomputers.core.input

import pro.ikili.kibicomputers.api.device.DeviceValue

/**
 * キーの押下・離しのイベント（#326、ADR 0326）。キーボードのデバイスが、[KeyboardEvent.KeyDown]・[KeyboardEvent.KeyUp] を
 * 受けるたびに1件出す（同報。配り方は ADR 0063 のまま）。
 *
 * 形（source はキーボードの address）:
 * - `key-pressed`: `[text(キー名), integer(修飾キー), boolean(繰り返し)]`
 * - `key-released`: `[text(キー名), integer(修飾キー)]`
 *
 * キー名は [name] の表（[Key] を小文字のケバブにしたもの）。表は ABI なので、[Key] の名前や並びを変えても、ここの文字列は変えない。
 * 修飾キーは [KeyModifiers] のビットで、知らないビットは落とす。文字の入力（[KeyboardEvent.Text]）と貼り付け
 * （[KeyboardEvent.Paste]）は今は出さない。打った人（typist）は載せない。
 */
internal object KeyEvents {
    /** キーを押した（押し続けたときの繰り返しも同じ種類） */
    const val PRESSED: String = "key-pressed"

    /** キーを離した */
    const val RELEASED: String = "key-released"

    /** [key] のキー名（ABI）。`else` を書かないので、[Key] が増えたらここで決めるまでコンパイルが通らない */
    fun name(key: Key): String = when (key) {
        Key.UNKNOWN -> "unknown"
        Key.A -> "a"
        Key.B -> "b"
        Key.C -> "c"
        Key.D -> "d"
        Key.E -> "e"
        Key.F -> "f"
        Key.G -> "g"
        Key.H -> "h"
        Key.I -> "i"
        Key.J -> "j"
        Key.K -> "k"
        Key.L -> "l"
        Key.M -> "m"
        Key.N -> "n"
        Key.O -> "o"
        Key.P -> "p"
        Key.Q -> "q"
        Key.R -> "r"
        Key.S -> "s"
        Key.T -> "t"
        Key.U -> "u"
        Key.V -> "v"
        Key.W -> "w"
        Key.X -> "x"
        Key.Y -> "y"
        Key.Z -> "z"
        Key.DIGIT_0 -> "digit-0"
        Key.DIGIT_1 -> "digit-1"
        Key.DIGIT_2 -> "digit-2"
        Key.DIGIT_3 -> "digit-3"
        Key.DIGIT_4 -> "digit-4"
        Key.DIGIT_5 -> "digit-5"
        Key.DIGIT_6 -> "digit-6"
        Key.DIGIT_7 -> "digit-7"
        Key.DIGIT_8 -> "digit-8"
        Key.DIGIT_9 -> "digit-9"
        Key.SPACE -> "space"
        Key.APOSTROPHE -> "apostrophe"
        Key.COMMA -> "comma"
        Key.MINUS -> "minus"
        Key.PERIOD -> "period"
        Key.SLASH -> "slash"
        Key.SEMICOLON -> "semicolon"
        Key.EQUAL -> "equal"
        Key.LEFT_BRACKET -> "left-bracket"
        Key.BACKSLASH -> "backslash"
        Key.RIGHT_BRACKET -> "right-bracket"
        Key.GRAVE_ACCENT -> "grave-accent"
        Key.WORLD_1 -> "world-1"
        Key.WORLD_2 -> "world-2"
        Key.ESCAPE -> "escape"
        Key.ENTER -> "enter"
        Key.TAB -> "tab"
        Key.BACKSPACE -> "backspace"
        Key.INSERT -> "insert"
        Key.DELETE -> "delete"
        Key.RIGHT -> "right"
        Key.LEFT -> "left"
        Key.DOWN -> "down"
        Key.UP -> "up"
        Key.PAGE_UP -> "page-up"
        Key.PAGE_DOWN -> "page-down"
        Key.HOME -> "home"
        Key.END -> "end"
        Key.CAPS_LOCK -> "caps-lock"
        Key.SCROLL_LOCK -> "scroll-lock"
        Key.NUM_LOCK -> "num-lock"
        Key.PRINT_SCREEN -> "print-screen"
        Key.PAUSE -> "pause"
        Key.F1 -> "f1"
        Key.F2 -> "f2"
        Key.F3 -> "f3"
        Key.F4 -> "f4"
        Key.F5 -> "f5"
        Key.F6 -> "f6"
        Key.F7 -> "f7"
        Key.F8 -> "f8"
        Key.F9 -> "f9"
        Key.F10 -> "f10"
        Key.F11 -> "f11"
        Key.F12 -> "f12"
        Key.KEYPAD_0 -> "keypad-0"
        Key.KEYPAD_1 -> "keypad-1"
        Key.KEYPAD_2 -> "keypad-2"
        Key.KEYPAD_3 -> "keypad-3"
        Key.KEYPAD_4 -> "keypad-4"
        Key.KEYPAD_5 -> "keypad-5"
        Key.KEYPAD_6 -> "keypad-6"
        Key.KEYPAD_7 -> "keypad-7"
        Key.KEYPAD_8 -> "keypad-8"
        Key.KEYPAD_9 -> "keypad-9"
        Key.KEYPAD_DECIMAL -> "keypad-decimal"
        Key.KEYPAD_DIVIDE -> "keypad-divide"
        Key.KEYPAD_MULTIPLY -> "keypad-multiply"
        Key.KEYPAD_SUBTRACT -> "keypad-subtract"
        Key.KEYPAD_ADD -> "keypad-add"
        Key.KEYPAD_ENTER -> "keypad-enter"
        Key.KEYPAD_EQUAL -> "keypad-equal"
        Key.LEFT_SHIFT -> "left-shift"
        Key.LEFT_CONTROL -> "left-control"
        Key.LEFT_ALT -> "left-alt"
        Key.LEFT_SUPER -> "left-super"
        Key.RIGHT_SHIFT -> "right-shift"
        Key.RIGHT_CONTROL -> "right-control"
        Key.RIGHT_ALT -> "right-alt"
        Key.RIGHT_SUPER -> "right-super"
        Key.MENU -> "menu"
    }

    /** 押下 [event] の値 `[text(キー名), integer(修飾キー), boolean(繰り返し)]` */
    fun pressedValues(event: KeyboardEvent.KeyDown): Array<DeviceValue> = arrayOf(
        DeviceValue.of(name(event.key)),
        modifiers(event.modifiers),
        DeviceValue.of(event.repeat),
    )

    /** 離し [event] の値 `[text(キー名), integer(修飾キー)]` */
    fun releasedValues(event: KeyboardEvent.KeyUp): Array<DeviceValue> = arrayOf(
        DeviceValue.of(name(event.key)),
        modifiers(event.modifiers),
    )

    // 知らないビットを落とす（通信の検査を通らない経路、サーバー内やテストから直接呼ぶ入力もあるため）
    private fun modifiers(bits: Int): DeviceValue = DeviceValue.of((bits and KeyModifiers.ALL).toLong())
}
