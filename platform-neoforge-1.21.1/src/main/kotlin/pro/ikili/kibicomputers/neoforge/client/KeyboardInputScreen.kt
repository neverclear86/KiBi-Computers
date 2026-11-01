package pro.ikili.kibicomputers.neoforge.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor
import org.lwjgl.glfw.GLFW
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.input.Key
import pro.ikili.kibicomputers.core.input.KeyModifiers
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.input.KeyboardEventCodec
import pro.ikili.kibicomputers.core.input.KeyboardText
import pro.ikili.kibicomputers.core.input.Utf16Joiner
import pro.ikili.kibicomputers.neoforge.input.KeyboardBlockEntity
import pro.ikili.kibicomputers.neoforge.input.KeyboardInput
import pro.ikili.kibicomputers.neoforge.input.KeyboardInputPayload

/**
 * キーボードへの入力 GUI（#142）。開いている間、キー入力をすべて取り込んで、キーボード [keyboardPos] に送る。
 *
 * - キーの押し下げ・離し（キーコード）と、打った文字（IME で確定した文字を含む）の両方を送る
 * - Ctrl+V・Shift+Insert はクリップボードの貼り付けにする（キーとしては送らない）
 * - Esc もコンピューターに送るので、閉じるのは Shift+Esc か「閉じる」のボタン。キーボードが消えたり、
 *   離れすぎたりしても閉じる
 * - 背景は暗くしない（後ろの画面ブロックを見ながら打てるように）。ゲームも止めない
 * - 送るのは client tick ごとに1回まとめて
 *
 * @param screenPos 右クリックした画面（キーボードを直接右クリックしたなら null）。今は表示に使っていない
 */
class KeyboardInputScreen(val keyboardPos: BlockPos, val screenPos: BlockPos?) :
    Screen(Component.translatable("$PREFIX.title")) {

    private val pending = ArrayList<KeyboardEvent>()
    private val text = StringBuilder()
    private val joiner = Utf16Joiner()

    /** 押している（離したと送っていない）キー。GLFW のキーコードから、送ったキー */
    private val held = LinkedHashMap<Int, Key>()

    override fun init() {
        addRenderableWidget(
            Button.builder(Component.translatable("$PREFIX.close")) { onClose() }
                .bounds(width - CLOSE_WIDTH - 4, 2, CLOSE_WIDTH, BAR_HEIGHT - 4)
                .build(),
        )
    }

    override fun isPauseScreen(): Boolean = false

    override fun shouldCloseOnEsc(): Boolean = false

    // 後ろの世界（画面ブロック）が見えるように、ぼかしも暗くもしない。案内は画面の上端の帯に出す（下端はホットバーと重なる）
    override fun renderBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {}

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        graphics.fill(0, 0, width, BAR_HEIGHT, BAR_COLOR)
        graphics.drawString(font, Component.translatable("$PREFIX.hint"), 6, (BAR_HEIGHT - 8) / 2, TEXT_COLOR)
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, glfwModifiers: Int): Boolean {
        val modifiers = withHeldModifiers(glfwModifiers)
        val shift = modifiers and KeyModifiers.SHIFT != 0
        val ctrl = modifiers and KeyModifiers.CONTROL != 0
        val alt = modifiers and KeyModifiers.ALT != 0
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && shift && !ctrl && !alt) {
            onClose()
            return true
        }
        if ((keyCode == GLFW.GLFW_KEY_V && ctrl && !alt) || (keyCode == GLFW.GLFW_KEY_INSERT && shift && !ctrl && !alt)) {
            paste()
            return true
        }
        val key = GlfwKeys.toKey(keyCode)
        val repeat = held.containsKey(keyCode)
        held[keyCode] = key
        flushText()
        pending += KeyboardEvent.KeyDown(key, modifiers, repeat)
        return true
    }

    override fun keyReleased(keyCode: Int, scanCode: Int, glfwModifiers: Int): Boolean {
        // 押したと送っていないキー（貼り付け・閉じるのに使ったもの）は、離したとも送らない
        val key = held.remove(keyCode) ?: return true
        flushText()
        pending += KeyboardEvent.KeyUp(key, withHeldModifiers(glfwModifiers))
        return true
    }

    /**
     * GLFW の修飾キーのビットに、押している修飾キーの分を足す。GLFW のビットが付かない経路（自動操作など）でも
     * Ctrl+C・Shift+Esc が効くようにする。
     */
    private fun withHeldModifiers(glfwModifiers: Int): Int {
        var m = glfwModifiers and KeyModifiers.ALL
        for (key in held.values) {
            m = m or when (key) {
                Key.LEFT_SHIFT, Key.RIGHT_SHIFT -> KeyModifiers.SHIFT
                Key.LEFT_CONTROL, Key.RIGHT_CONTROL -> KeyModifiers.CONTROL
                Key.LEFT_ALT, Key.RIGHT_ALT -> KeyModifiers.ALT
                Key.LEFT_SUPER, Key.RIGHT_SUPER -> KeyModifiers.SUPER
                else -> 0
            }
        }
        return m
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        val s = joiner.feed(codePoint) ?: return true
        if (KeyboardText.isValidText(s)) text.append(s)
        return true
    }

    override fun tick() {
        val mc = minecraft ?: return
        val player = mc.player
        val level = mc.level
        if (player == null || level == null || level.getBlockEntity(keyboardPos) !is KeyboardBlockEntity ||
            !KeyboardInput.canReach(player, keyboardPos)
        ) {
            onClose()
            return
        }
        flush()
    }

    override fun removed() {
        // 押したままのキーは、離したことにする（コンピューターの側でキーが押されたままにならないように）
        flushText()
        for (key in held.values) pending += KeyboardEvent.KeyUp(key, KeyModifiers.NONE)
        held.clear()
        joiner.reset()
        flush()
        super.removed()
    }

    private fun paste() {
        val raw = minecraft?.keyboardHandler?.clipboard ?: return
        val clean = KeyboardText.sanitizePaste(raw)
        if (clean.isEmpty()) return
        flushText()
        pending += KeyboardEvent.Paste(clean)
    }

    private fun flushText() {
        if (text.isEmpty()) return
        pending += KeyboardEvent.Text(text.toString())
        text.setLength(0)
    }

    private fun flush() {
        flushText()
        if (pending.isEmpty()) return
        val connection = minecraft?.connection
        if (connection != null && connection.hasChannel(KeyboardInputPayload.TYPE)) {
            for (data in KeyboardEventCodec.encode(pending)) {
                PacketDistributor.sendToServer(KeyboardInputPayload(keyboardPos, data))
            }
        }
        pending.clear()
    }

    private companion object {
        const val PREFIX = "gui.${KiBiComputers.MOD_ID}.keyboard"
        const val BAR_HEIGHT = 20
        const val CLOSE_WIDTH = 60
        const val BAR_COLOR = 0xA0000000.toInt()
        const val TEXT_COLOR = 0xFFFFFFFF.toInt()
    }
}
