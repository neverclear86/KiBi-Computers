package pro.ikili.kibicomputers.neoforge.client

import org.lwjgl.glfw.GLFW
import pro.ikili.kibicomputers.core.input.Key

/** GLFW のキーコードを `core` の [Key] にする（#142）。 */
object GlfwKeys {
    private val TABLE: Map<Int, Key> = buildMap {
        for (i in 0 until 26) put(GLFW.GLFW_KEY_A + i, Key.entries[Key.A.ordinal + i])
        for (i in 0 until 10) {
            put(GLFW.GLFW_KEY_0 + i, Key.entries[Key.DIGIT_0.ordinal + i])
            put(GLFW.GLFW_KEY_KP_0 + i, Key.entries[Key.KEYPAD_0.ordinal + i])
        }
        for (i in 0 until 12) put(GLFW.GLFW_KEY_F1 + i, Key.entries[Key.F1.ordinal + i])
        put(GLFW.GLFW_KEY_SPACE, Key.SPACE)
        put(GLFW.GLFW_KEY_APOSTROPHE, Key.APOSTROPHE)
        put(GLFW.GLFW_KEY_COMMA, Key.COMMA)
        put(GLFW.GLFW_KEY_MINUS, Key.MINUS)
        put(GLFW.GLFW_KEY_PERIOD, Key.PERIOD)
        put(GLFW.GLFW_KEY_SLASH, Key.SLASH)
        put(GLFW.GLFW_KEY_SEMICOLON, Key.SEMICOLON)
        put(GLFW.GLFW_KEY_EQUAL, Key.EQUAL)
        put(GLFW.GLFW_KEY_LEFT_BRACKET, Key.LEFT_BRACKET)
        put(GLFW.GLFW_KEY_BACKSLASH, Key.BACKSLASH)
        put(GLFW.GLFW_KEY_RIGHT_BRACKET, Key.RIGHT_BRACKET)
        put(GLFW.GLFW_KEY_GRAVE_ACCENT, Key.GRAVE_ACCENT)
        put(GLFW.GLFW_KEY_WORLD_1, Key.WORLD_1)
        put(GLFW.GLFW_KEY_WORLD_2, Key.WORLD_2)
        put(GLFW.GLFW_KEY_ESCAPE, Key.ESCAPE)
        put(GLFW.GLFW_KEY_ENTER, Key.ENTER)
        put(GLFW.GLFW_KEY_TAB, Key.TAB)
        put(GLFW.GLFW_KEY_BACKSPACE, Key.BACKSPACE)
        put(GLFW.GLFW_KEY_INSERT, Key.INSERT)
        put(GLFW.GLFW_KEY_DELETE, Key.DELETE)
        put(GLFW.GLFW_KEY_RIGHT, Key.RIGHT)
        put(GLFW.GLFW_KEY_LEFT, Key.LEFT)
        put(GLFW.GLFW_KEY_DOWN, Key.DOWN)
        put(GLFW.GLFW_KEY_UP, Key.UP)
        put(GLFW.GLFW_KEY_PAGE_UP, Key.PAGE_UP)
        put(GLFW.GLFW_KEY_PAGE_DOWN, Key.PAGE_DOWN)
        put(GLFW.GLFW_KEY_HOME, Key.HOME)
        put(GLFW.GLFW_KEY_END, Key.END)
        put(GLFW.GLFW_KEY_CAPS_LOCK, Key.CAPS_LOCK)
        put(GLFW.GLFW_KEY_SCROLL_LOCK, Key.SCROLL_LOCK)
        put(GLFW.GLFW_KEY_NUM_LOCK, Key.NUM_LOCK)
        put(GLFW.GLFW_KEY_PRINT_SCREEN, Key.PRINT_SCREEN)
        put(GLFW.GLFW_KEY_PAUSE, Key.PAUSE)
        put(GLFW.GLFW_KEY_KP_DECIMAL, Key.KEYPAD_DECIMAL)
        put(GLFW.GLFW_KEY_KP_DIVIDE, Key.KEYPAD_DIVIDE)
        put(GLFW.GLFW_KEY_KP_MULTIPLY, Key.KEYPAD_MULTIPLY)
        put(GLFW.GLFW_KEY_KP_SUBTRACT, Key.KEYPAD_SUBTRACT)
        put(GLFW.GLFW_KEY_KP_ADD, Key.KEYPAD_ADD)
        put(GLFW.GLFW_KEY_KP_ENTER, Key.KEYPAD_ENTER)
        put(GLFW.GLFW_KEY_KP_EQUAL, Key.KEYPAD_EQUAL)
        put(GLFW.GLFW_KEY_LEFT_SHIFT, Key.LEFT_SHIFT)
        put(GLFW.GLFW_KEY_LEFT_CONTROL, Key.LEFT_CONTROL)
        put(GLFW.GLFW_KEY_LEFT_ALT, Key.LEFT_ALT)
        put(GLFW.GLFW_KEY_LEFT_SUPER, Key.LEFT_SUPER)
        put(GLFW.GLFW_KEY_RIGHT_SHIFT, Key.RIGHT_SHIFT)
        put(GLFW.GLFW_KEY_RIGHT_CONTROL, Key.RIGHT_CONTROL)
        put(GLFW.GLFW_KEY_RIGHT_ALT, Key.RIGHT_ALT)
        put(GLFW.GLFW_KEY_RIGHT_SUPER, Key.RIGHT_SUPER)
        put(GLFW.GLFW_KEY_MENU, Key.MENU)
    }

    /** GLFW のキーコード [glfwKey] に当たるキー。知らないキーなら [Key.UNKNOWN]。 */
    fun toKey(glfwKey: Int): Key = TABLE[glfwKey] ?: Key.UNKNOWN
}
