package pro.ikili.kibicomputers.neoforge.computer

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.DiskStore
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.computer.PartTier
import pro.ikili.kibicomputers.neoforge.registry.KiBiDataComponents

/**
 * 内蔵ディスク（HDD）のアイテム（#124）。容量はティアで決まる（[PartTier.diskBytes]）。
 *
 * 識別子はデータコンポーネント `kibicomputers:disk_id`（[KiBiDataComponents.DISK_ID]）に持つ。クラフトしたときには付けず、
 * ケースに挿して電源を入れたときに付ける（[CaseBlockEntity]）。中身はワールドの `kibicomputers/disks/<識別子>/`（[WorldDisks]）。
 * 説明欄に容量と、識別子の頭 8 文字を出す。
 */
class DiskItem(tier: PartTier, properties: Properties) : ComputerPartItem(PartKind.STORAGE, tier, properties) {
    override fun appendHoverText(stack: ItemStack, context: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        super.appendHoverText(stack, context, tooltip, flag)
        val raw = stack.get(KiBiDataComponents.DISK_ID.get())
        val line = when {
            raw == null -> Component.translatable("$TOOLTIP.uninitialized")
            DiskStore.isValidId(raw) -> Component.translatable("$TOOLTIP.id", raw.substring(0, 8))
            else -> Component.translatable("$TOOLTIP.invalid_id")
        }
        tooltip.add(line.withStyle(ChatFormatting.DARK_GRAY))
    }

    companion object {
        private const val TOOLTIP = "tooltip.${KiBiComputers.MOD_ID}.disk"

        /** [stack] のディスクの識別子。ディスクでない・識別子がない・形が正しくない（[DiskStore.isValidId]）なら `null` */
        fun diskIdOf(stack: ItemStack): String? {
            if (stack.item !is DiskItem) return null
            return stack.get(KiBiDataComponents.DISK_ID.get())?.takeIf { DiskStore.isValidId(it) }
        }

        /** [stack] がディスクで、識別子のデータを持っていない（形の正しくない値も「持っている」とみなす。上書きしない） */
        fun needsId(stack: ItemStack): Boolean = stack.item is DiskItem && !stack.has(KiBiDataComponents.DISK_ID.get())
    }
}
