package pro.ikili.kibicomputers.neoforge.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.ComputerError
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.neoforge.computer.CaseMenu

/**
 * ケースの GUI（#121）。部品スロット・電源ボタン・状態の表示。
 *
 * 背景は画像を使わず、ティアのスロットの数に合わせてコードで描く（仮の見た目。正式なデザインは #250）。
 * 空の部品スロットには種類の略号を薄く描き、指すと種類の名前を出す。
 */
class CaseScreen(menu: CaseMenu, playerInventory: Inventory, title: Component) :
    AbstractContainerScreen<CaseMenu>(menu, playerInventory, title) {

    init {
        imageWidth = CaseMenu.IMAGE_WIDTH
        imageHeight = CaseMenu.IMAGE_HEIGHT
        inventoryLabelY = CaseMenu.INVENTORY_LABEL_Y
    }

    override fun init() {
        super.init()
        addRenderableWidget(
            Button.builder(Component.translatable(POWER_KEY)) {
                minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, CaseMenu.POWER_BUTTON)
            }.bounds(leftPos + POWER_X, topPos + POWER_Y, POWER_WIDTH, 20).build(),
        )
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        renderTooltip(graphics, mouseX, mouseY)
    }

    override fun renderTooltip(graphics: GuiGraphics, x: Int, y: Int) {
        val slot = hoveredSlot
        if (menu.carried.isEmpty && slot != null && !slot.hasItem() && slot.index < menu.partSlotCount) {
            graphics.renderTooltip(font, partName(menu.tier.kindOf(slot.index)), x, y)
            return
        }
        super.renderTooltip(graphics, x, y)
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        val x = leftPos
        val y = topPos
        bevel(graphics, x, y, imageWidth, imageHeight, PANEL, LIGHT, DARK)
        for (slot in menu.slots) {
            bevel(graphics, x + slot.x - 1, y + slot.y - 1, 18, 18, SLOT, DARK, LIGHT)
        }
        // 空の部品スロットの略号（半分の大きさ）
        for (i in 0 until menu.partSlotCount) {
            val slot = menu.slots[i]
            if (slot.hasItem()) continue
            val label = Component.translatable(shortKey(menu.tier.kindOf(i)))
            graphics.pose().pushPose()
            graphics.pose().translate((x + slot.x + 8).toFloat(), (y + slot.y + 6).toFloat(), 0f)
            graphics.pose().scale(0.5f, 0.5f, 1f)
            graphics.drawString(font, label, -font.width(label) / 2, 0, SLOT_LABEL, false)
            graphics.pose().popPose()
        }
        // 状態のランプ
        bevel(graphics, x + 8, y + CaseMenu.STATUS_Y, 8, 8, statusColor(menu.status), DARK, LIGHT)
    }

    override fun renderLabels(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        super.renderLabels(graphics, mouseX, mouseY)
        // 2行まで折り返す
        val lines = font.split(statusText(), imageWidth - 19 - 8).take(2)
        lines.forEachIndexed { i, line ->
            graphics.drawString(font, line, 19, CaseMenu.STATUS_Y + i * 9, statusTextColor(menu.status), false)
        }
    }

    /** 状態の行（エラーの理由・足りない部品を含む） */
    private fun statusText(): Component = when (menu.status) {
        ComputerStatus.ERROR -> when (menu.error) {
            ComputerError.MISSING_PARTS -> {
                val names = menu.missingParts.map { partName(it) }
                val joined = names.drop(1).fold(names.firstOrNull() ?: Component.empty()) { acc, name ->
                    Component.empty().append(acc).append(", ").append(name)
                }
                Component.translatable("$PREFIX.error.missing_parts", joined)
            }
            ComputerError.CRASHED -> Component.translatable("$PREFIX.error.crashed")
            ComputerError.UNAVAILABLE, null -> Component.translatable("$PREFIX.error.unavailable")
        }
        else -> Component.translatable(statusKey(menu.status))
    }

    private companion object {
        const val PREFIX = "gui.${KiBiComputers.MOD_ID}.case"
        const val POWER_KEY = "$PREFIX.power"

        const val POWER_X = 124
        const val POWER_Y = 18
        const val POWER_WIDTH = 44

        const val PANEL = 0xFFC6C6C6.toInt()
        const val LIGHT = 0xFFFFFFFF.toInt()
        const val DARK = 0xFF555555.toInt()
        const val SLOT = 0xFF8B8B8B.toInt()
        const val SLOT_LABEL = 0xFF5A5A5A.toInt()
        const val LABEL = 0xFF404040.toInt()

        fun statusKey(status: ComputerStatus): String = "$PREFIX.status.${status.name.lowercase()}"

        fun partName(kind: PartKind): Component =
            Component.translatable("gui.${KiBiComputers.MOD_ID}.part.${kind.name.lowercase()}")

        fun shortKey(kind: PartKind): String = "gui.${KiBiComputers.MOD_ID}.part.${kind.name.lowercase()}.short"

        fun statusColor(status: ComputerStatus): Int = when (status) {
            ComputerStatus.OFF -> 0xFF3A3A3A.toInt()
            ComputerStatus.RUNNING -> 0xFF3FD13F.toInt()
            ComputerStatus.ERROR -> 0xFFE03A3A.toInt()
            ComputerStatus.NO_POWER -> 0xFFE0C03A.toInt()
        }

        fun statusTextColor(status: ComputerStatus): Int = when (status) {
            ComputerStatus.ERROR -> 0xFFA01010.toInt()
            else -> LABEL
        }

        /** 枠つきの四角。左上を [topLeft]、右下を [bottomRight] の色にして、立体に見せる */
        fun bevel(graphics: GuiGraphics, x: Int, y: Int, w: Int, h: Int, fill: Int, topLeft: Int, bottomRight: Int) {
            graphics.fill(x, y, x + w, y + h, fill)
            graphics.fill(x, y, x + w - 1, y + 1, topLeft)
            graphics.fill(x, y, x + 1, y + h - 1, topLeft)
            graphics.fill(x + 1, y + h - 1, x + w, y + h, bottomRight)
            graphics.fill(x + w - 1, y + 1, x + w, y + h, bottomRight)
        }
    }
}
