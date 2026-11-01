package pro.ikili.kibicomputers.neoforge.client

import net.minecraft.Util
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.client.renderer.LightTexture
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import net.neoforged.neoforge.network.PacketDistributor
import org.lwjgl.glfw.GLFW
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.display.ScreenCells
import pro.ikili.kibicomputers.core.display.ScreenLayout
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.input.Key
import pro.ikili.kibicomputers.core.input.KeyModifiers
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.input.KeyboardEventCodec
import pro.ikili.kibicomputers.core.input.KeyboardText
import pro.ikili.kibicomputers.core.input.Utf16Joiner
import pro.ikili.kibicomputers.neoforge.display.ScreenBlockEntity
import pro.ikili.kibicomputers.neoforge.input.KeyboardBlockEntity
import pro.ikili.kibicomputers.neoforge.input.KeyboardInput
import pro.ikili.kibicomputers.neoforge.input.KeyboardInputPayload
import kotlin.math.ceil
import kotlin.math.floor

/**
 * キーボードへの入力 GUI（#142）。開いている間、キー入力をすべて取り込んで、キーボード [keyboardPos] に送る。
 *
 * - キーの押し下げ・離し（キーコード）と、打った文字（IME で確定した文字を含む）の両方を送る
 * - Ctrl+V・Shift+Insert はクリップボードの貼り付けにする（キーとしては送らない）
 * - Esc もコンピューターに送るので、閉じるのは Shift+Esc か「閉じる」のボタン。キーボードが消えたり、
 *   離れすぎたりしても閉じる
 * - つながった画面の中身（文字・色・ピクセル画面・カーソル）を、ウィンドウに収まる大きさに拡大して描く（#348）。
 *   画面が見つからなければ、その旨を出す。画面の外側は暗くしない（後ろの世界が見えるように）。ゲームも止めない
 * - 送るのは client tick ごとに1回まとめて
 *
 * @param initialScreenPos 右クリックした画面（キーボードを直接右クリックしたなら null。そのときは [KeyboardInput.findScreen] で探す）
 */
class KeyboardInputScreen(val keyboardPos: BlockPos, initialScreenPos: BlockPos?) :
    Screen(Component.translatable("$PREFIX.title")) {

    /** 中身を描く画面。見つからなければ null。画面が壊れたり置かれたりしたら、tick ごとに探し直す */
    var screenPos: BlockPos? = initialScreenPos
        private set

    private val pending = ArrayList<KeyboardEvent>()
    private val text = StringBuilder()
    private val joiner = Utf16Joiner()

    /** 押している（離したと送っていない）キー。GLFW のキーコードから、送ったキー */
    private val held = LinkedHashMap<Int, Key>()

    override fun init() {
        updateScreenPos()
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
        val be = screenPos?.let { minecraft?.level?.getBlockEntity(it) } as? ScreenBlockEntity
        if (be == null) drawNoScreen(graphics) else drawScreen(graphics, be)
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    // 画面がないとき: 中央に案内を出す
    private fun drawNoScreen(graphics: GuiGraphics) {
        val message = Component.translatable("$PREFIX.no_screen")
        val w = font.width(message)
        val cx = width / 2
        val cy = BAR_HEIGHT + (height - BAR_HEIGHT) / 2
        graphics.fill(cx - w / 2 - 8, cy - 10, cx + w / 2 + 8, cy + 10, BAR_COLOR)
        graphics.drawCenteredString(font, message, cx, cy - 4, TEXT_COLOR)
    }

    // 画面の中身を、ウィンドウに収まる大きさに拡大して描く。描く順（奥から）: 地 → ピクセル画面 → 文字の背景 → 文字 → カーソル
    private fun drawScreen(graphics: GuiGraphics, be: ScreenBlockEntity) {
        val text = be.text
        val contentWidth = text.width * ScreenLayout.CELL_WIDTH
        val contentHeight = text.height * ScreenLayout.CELL_HEIGHT
        val top = BAR_HEIGHT + PADDING
        val areaWidth = width - 2 * PADDING - 2 * FRAME
        val areaHeight = height - top - PADDING - 2 * FRAME
        if (areaWidth <= 0 || areaHeight <= 0) return
        val scale = pixelAlignedScale(minOf(areaWidth.toFloat() / contentWidth, areaHeight.toFloat() / contentHeight))
        val w = contentWidth * scale
        val h = contentHeight * scale
        val x0 = (width - w) / 2f
        val y0 = top + FRAME + (areaHeight - h) / 2f
        graphics.fill(
            floor(x0).toInt() - FRAME,
            floor(y0).toInt() - FRAME,
            ceil(x0 + w).toInt() + FRAME,
            ceil(y0 + h).toInt() + FRAME,
            FRAME_COLOR,
        )

        val pose = graphics.pose()
        pose.pushPose()
        pose.translate(x0, y0, 0f)
        pose.scale(scale, scale, 1f)
        graphics.fill(0, 0, contentWidth, contentHeight, OPAQUE or TextBuffer.DEFAULT_BACKGROUND)
        val pixels = if (be.hasPixels) be.pixels else null
        if (pixels != null) {
            val texture = PixelTexture.forScreen(be, pixels)
            graphics.blit(
                texture.location, 0, 0, contentWidth, contentHeight, 0f, 0f,
                pixels.width, pixels.height, pixels.width, pixels.height,
            )
        }
        ScreenCells.forEachBackgroundRun(text, TextBuffer.DEFAULT_BACKGROUND) { x, y, length, rgb ->
            val cx = x * ScreenLayout.CELL_WIDTH
            val cy = y * ScreenLayout.CELL_HEIGHT
            graphics.fill(cx, cy, cx + length * ScreenLayout.CELL_WIDTH, cy + ScreenLayout.CELL_HEIGHT, OPAQUE or rgb)
        }
        val matrix = pose.last().pose()
        ScreenCells.forEachGlyph(text) { x, y, codePoint, foreground, cells ->
            val glyph = FormattedCharSequence.codepoint(codePoint, ScreenRenderer.STYLE)
            // フォントの文字は幅がまちまちなので、セルの真ん中に置く（画面ブロックの描画と同じ）
            val cellWidth = cells * ScreenLayout.CELL_WIDTH
            val gx = x * ScreenLayout.CELL_WIDTH + (cellWidth - (font.width(glyph) - 1)) / 2f
            val gy = (y * ScreenLayout.CELL_HEIGHT).toFloat()
            font.drawInBatch(
                glyph, gx, gy, OPAQUE or foreground, false, matrix, graphics.bufferSource(),
                Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT,
            )
        }
        graphics.flush()
        drawCursor(graphics, text)
        pose.popPose()
    }

    // カーソル: セルの下端の線を、そのセルの文字の色で点滅させる
    private fun drawCursor(graphics: GuiGraphics, text: TextBuffer) {
        val x = text.cursorX
        val y = text.cursorY
        if (!text.cursorVisible || x >= text.width || y >= text.height) return
        if ((Util.getMillis() / CURSOR_BLINK_MILLIS) % 2 != 0L) return
        val cx = x * ScreenLayout.CELL_WIDTH
        val cy = (y + 1) * ScreenLayout.CELL_HEIGHT
        graphics.fill(cx, cy - CURSOR_HEIGHT, cx + ScreenLayout.CELL_WIDTH, cy, OPAQUE or text.getForeground(x, y))
    }

    /**
     * 拡大率 [fit]（GUI の単位）を、十分大きいときだけ実際の画素で整数倍になるように切り下げる（文字の画素の幅をそろえる）。
     * 小さいときに切り下げると半分近くまで小さくなるので、そのまま使う（読みやすさを優先する）。
     */
    private fun pixelAlignedScale(fit: Float): Float {
        val windowScale = (minecraft?.window?.guiScale ?: 1.0).toFloat()
        val pixels = fit * windowScale
        return (if (pixels >= SNAP_MIN_PIXELS) floor(pixels) else pixels) / windowScale
    }

    private fun updateScreenPos() {
        val level = minecraft?.level ?: return
        if (screenPos?.let { level.getBlockEntity(it) is ScreenBlockEntity } == true) return
        screenPos = KeyboardInput.findScreen(level, keyboardPos)
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
        updateScreenPos()
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
        const val OPAQUE = 0xFF000000.toInt()

        /** 画面の中身の周りの余白と、枠の太さ（GUI の単位） */
        const val PADDING = 4
        const val FRAME = 3
        const val FRAME_COLOR = 0xFF3A3A3A.toInt()

        /** カーソルの点滅の間隔（ミリ秒）と、線の太さ（フォントの単位） */
        const val CURSOR_BLINK_MILLIS = 530L
        const val CURSOR_HEIGHT = 1

        /** フォントの 1 単位がこの画素数以上なら、整数の画素に切り下げる */
        const val SNAP_MIN_PIXELS = 4f
    }
}
