package pro.ikili.kibicomputers.neoforge.computer

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.computer.PartTier
import pro.ikili.kibicomputers.core.config.KiBiConfig
import pro.ikili.kibicomputers.neoforge.config.KiBiConfigs

/**
 * ケースに挿す部品のアイテム（#347）。種類（[kind]）とティア（[tier]）で性能が決まる（[PartTier]）。
 *
 * ケースのスロットに挿せるかは、アイテムタグ（[CaseParts.tagOf]）で決まる。登録名は [idOf]。
 * 説明欄に性能（CPU の燃料、RAM・ディスクの容量）を出す。ディスクは [DiskItem]。
 */
open class ComputerPartItem(val kind: PartKind, val tier: PartTier, properties: Properties) : Item(properties) {
    override fun appendHoverText(stack: ItemStack, context: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        super.appendHoverText(stack, context, tooltip, flag)
        // 性能値はコンフィグ（#719）から。クライアントでは、サーバーから同期された写し（同期の前は既定の値）
        val spec = KiBiConfig.machineProfile(KiBiConfigs.server).spec(tier)
        val line = when (kind) {
            PartKind.CPU -> Component.translatable("$TOOLTIP.cpu", spec.cpuFuelPerTick)
            PartKind.MEMORY -> Component.translatable("$TOOLTIP.memory", spec.memoryBytes / (1024 * 1024))
            PartKind.STORAGE -> Component.translatable("$TOOLTIP.storage", spec.diskBytes / (1024 * 1024), spec.diskEntries)
            else -> null
        }
        line?.let { tooltip.add(it.withStyle(ChatFormatting.GRAY)) }
        if (tier == PartTier.CREATIVE) tooltip.add(Component.translatable("$TOOLTIP.creative").withStyle(ChatFormatting.DARK_PURPLE))
    }

    companion object {
        private const val TOOLTIP = "tooltip.${KiBiComputers.MOD_ID}.part"

        /** 部品の登録名。正式なティアは `<種類>_<番号>`（例: `storage_1`）、検証用は `creative_<種類>`（例: `creative_cpu`） */
        fun idOf(kind: PartKind, tier: PartTier): String =
            if (tier == PartTier.CREATIVE) "creative_${kind.name.lowercase()}" else "${kind.name.lowercase()}_${tier.level}"
    }
}
