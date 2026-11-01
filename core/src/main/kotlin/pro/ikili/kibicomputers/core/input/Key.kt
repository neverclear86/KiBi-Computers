package pro.ikili.kibicomputers.core.input

/**
 * キーボードのキー（#142）。物理的なキーの位置を表す（US 配列での名前）。打った文字は [KeyboardEvent.Text] で別に送る。
 *
 * ローダーやウィンドウのライブラリ（GLFW）のキーコードとは独立させ、接続層で対応づける。
 * プログラムから見えるキーの番号（イベントの ABI）は、イベントキュー（#63）で決める。ここでの並び（[ordinal]）は
 * キーボードの通信（[KeyboardEventCodec]）でだけ使う。並びを変えたら、通信のプロトコルの版を上げる。
 */
public enum class Key {
    UNKNOWN,

    A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z,

    DIGIT_0, DIGIT_1, DIGIT_2, DIGIT_3, DIGIT_4, DIGIT_5, DIGIT_6, DIGIT_7, DIGIT_8, DIGIT_9,

    SPACE, APOSTROPHE, COMMA, MINUS, PERIOD, SLASH, SEMICOLON, EQUAL,
    LEFT_BRACKET, BACKSLASH, RIGHT_BRACKET, GRAVE_ACCENT,

    /** US 配列にない、ISO 配列の左 Shift の隣などのキー */
    WORLD_1,

    /** US 配列にない、その他の国別のキー（日本語配列の「ろ」「¥」など） */
    WORLD_2,

    ESCAPE, ENTER, TAB, BACKSPACE, INSERT, DELETE,
    RIGHT, LEFT, DOWN, UP, PAGE_UP, PAGE_DOWN, HOME, END,
    CAPS_LOCK, SCROLL_LOCK, NUM_LOCK, PRINT_SCREEN, PAUSE,

    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,

    KEYPAD_0, KEYPAD_1, KEYPAD_2, KEYPAD_3, KEYPAD_4, KEYPAD_5, KEYPAD_6, KEYPAD_7, KEYPAD_8, KEYPAD_9,
    KEYPAD_DECIMAL, KEYPAD_DIVIDE, KEYPAD_MULTIPLY, KEYPAD_SUBTRACT, KEYPAD_ADD, KEYPAD_ENTER, KEYPAD_EQUAL,

    LEFT_SHIFT, LEFT_CONTROL, LEFT_ALT, LEFT_SUPER,
    RIGHT_SHIFT, RIGHT_CONTROL, RIGHT_ALT, RIGHT_SUPER,
    MENU,
    ;

    /** A〜Z なら、その大文字（`'A'`〜`'Z'`）。それ以外は null。 */
    public val letter: Char?
        get() = if (ordinal in A.ordinal..Z.ordinal) 'A' + (ordinal - A.ordinal) else null

    /** 修飾キー（Shift・Ctrl・Alt・Super）か。 */
    public val isModifier: Boolean
        get() = ordinal in LEFT_SHIFT.ordinal..RIGHT_SUPER.ordinal

    public companion object {
        private val VALUES = entries.toTypedArray()

        /** 通信での番号（[ordinal]）からキー。知らない番号なら null。 */
        @JvmStatic
        public fun fromOrdinal(ordinal: Int): Key? = VALUES.getOrNull(ordinal)
    }
}

/**
 * キーを押したときの修飾キーの状態（ビットの組）。ビットの値は GLFW の `GLFW_MOD_*` と同じにしてある。
 */
public object KeyModifiers {
    public const val NONE: Int = 0
    public const val SHIFT: Int = 0x01
    public const val CONTROL: Int = 0x02
    public const val ALT: Int = 0x04
    public const val SUPER: Int = 0x08
    public const val CAPS_LOCK: Int = 0x10
    public const val NUM_LOCK: Int = 0x20

    /** 知っているビットすべて。 */
    public const val ALL: Int = SHIFT or CONTROL or ALT or SUPER or CAPS_LOCK or NUM_LOCK
}
