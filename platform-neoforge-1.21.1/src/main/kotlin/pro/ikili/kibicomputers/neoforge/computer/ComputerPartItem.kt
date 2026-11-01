package pro.ikili.kibicomputers.neoforge.computer

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.computer.PartTier

/**
 * ケースに挿す部品のアイテム（#347）。種類（[kind]）とティア（[tier]）で性能が決まる（[PartTier]）。
 *
 * ケースのスロットに挿せるかは、アイテムタグ（[CaseParts.tagOf]）で決まる。登録名は `<ティア>_<種類>`（[idOf]）。
 * 説明欄に性能（CPU の燃料、RAM の容量）を出す。
 */
class ComputerPartItem(val kind: PartKind, val tier: PartTier, properties: Properties) : Item(properties) {
    override fun appendHoverText(stack: ItemStack, context: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        super.appendHoverText(stack, context, tooltip, flag)
        val line = when (kind) {
            PartKind.CPU -> Component.translatable("$TOOLTIP.cpu", tier.cpuFuelPerTick)
            PartKind.MEMORY -> Component.translatable("$TOOLTIP.memory", tier.memoryBytes / (1024 * 1024))
            else -> null
        }
        line?.let { tooltip.add(it.withStyle(ChatFormatting.GRAY)) }
        if (tier == PartTier.CREATIVE) tooltip.add(Component.translatable("$TOOLTIP.creative").withStyle(ChatFormatting.DARK_PURPLE))
    }

    companion object {
        private const val TOOLTIP = "tooltip.${KiBiComputers.MOD_ID}.part"

        /** 部品の登録名（例: `creative_cpu`） */
        fun idOf(kind: PartKind, tier: PartTier): String = "${tier.name.lowercase()}_${kind.name.lowercase()}"
    }
}
