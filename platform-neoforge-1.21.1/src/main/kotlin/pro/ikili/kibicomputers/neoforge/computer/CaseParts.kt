package pro.ikili.kibicomputers.neoforge.computer

import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.items.ItemStackHandler
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.InstalledPartTiers
import pro.ikili.kibicomputers.core.computer.InstalledParts
import pro.ikili.kibicomputers.core.computer.PartKind

/**
 * ケースの部品スロット（#121）。スロットの並びは [CaseTier.slots]。
 *
 * - 各スロットには、その種類のアイテムタグ（[tagOf]。`kibicomputers:computer_parts/<種類>`）の部品を1つだけ挿せる
 * - 中身が変わると [onChanged] を呼ぶ
 * - セーブデータには [ItemStackHandler] の形（`Items` と `Size`）で書く。読み込んだ `Size` がティアと違えば、
 *   ティアの数に合わせる（はみ出た分は捨てる）
 */
class CaseParts(val tier: CaseTier, private val onChanged: Runnable = Runnable {}) : ItemStackHandler(tier.slotCount) {
    /** 部品の挿さり具合（`core` に渡す） */
    val installed: InstalledParts = InstalledParts { slot -> !getStackInSlot(slot).isEmpty }

    /** 挿さっている部品のティア（`core` の [pro.ikili.kibicomputers.core.computer.MachineSpecs] に渡す）。部品のアイテムでなければ `null` */
    val tiers: InstalledPartTiers = InstalledPartTiers { slot -> (getStackInSlot(slot).item as? ComputerPartItem)?.tier }

    override fun getSlotLimit(slot: Int): Int = 1

    override fun isItemValid(slot: Int, stack: ItemStack): Boolean =
        slot in 0 until slots && stack.`is`(tagOf(tier.kindOf(slot)))

    override fun onContentsChanged(slot: Int) {
        onChanged.run()
    }

    override fun deserializeNBT(provider: HolderLookup.Provider, nbt: CompoundTag) {
        super.deserializeNBT(provider, nbt)
        if (stacks.size != tier.slotCount) {
            val fitted = NonNullList.withSize(tier.slotCount, ItemStack.EMPTY)
            for (i in 0 until minOf(stacks.size, fitted.size)) fitted[i] = stacks[i]
            stacks = fitted
        }
    }

    companion object {
        private val TAGS: Map<PartKind, TagKey<Item>> = PartKind.entries.associateWith { kind ->
            TagKey.create(
                Registries.ITEM,
                ResourceLocation.fromNamespaceAndPath(KiBiComputers.MOD_ID, "computer_parts/${kind.name.lowercase()}"),
            )
        }

        /** [kind] のスロットに挿せる部品のアイテムタグ */
        fun tagOf(kind: PartKind): TagKey<Item> = TAGS.getValue(kind)
    }
}
