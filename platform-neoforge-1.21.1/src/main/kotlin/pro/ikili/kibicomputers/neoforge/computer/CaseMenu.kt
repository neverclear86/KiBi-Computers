package pro.ikili.kibicomputers.neoforge.computer

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.world.Container
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.items.SlotItemHandler
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.ComputerError
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.neoforge.registry.KiBiMenus

/**
 * ケースの GUI のメニュー（#121）。部品スロット・プレイヤーの持ち物・状態（[ContainerData] で同期）を持つ。
 *
 * - 部品スロットは、種類（[PartKind]）ごとに縦の列に並べる。列の数は種類の数で、行の数はティアで変わる
 * - 電源ボタンは、メニューのボタン（[POWER_BUTTON]。バニラの `ServerboundContainerButtonClickPacket`）で送る
 * - 状態は [status]・[error]・[missingParts] で読む（クライアントでは、サーバーから同期した値）
 */
class CaseMenu private constructor(
    containerId: Int,
    playerInventory: Inventory,
    /** ケースのティア */
    val tier: CaseTier,
    parts: CaseParts,
    private val data: ContainerData,
    private val blockEntity: CaseBlockEntity?,
) : AbstractContainerMenu(KiBiMenus.CASE.get(), containerId) {

    init {
        checkContainerDataCount(data, DATA_COUNT)
        for (slot in 0 until tier.slotCount) {
            val kind = tier.kindOf(slot)
            val row = slot - tier.firstSlot(kind)
            addSlot(SlotItemHandler(parts, slot, partSlotX(kind), partSlotY(row)))
        }
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(playerInventory, col + row * 9 + 9, INVENTORY_X + col * 18, INVENTORY_Y + row * 18))
            }
        }
        for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col, INVENTORY_X + col * 18, HOTBAR_Y))
        }
        addDataSlots(data)
    }

    /** 部品スロットの数（メニューのスロットの先頭から、この数だけが部品スロット） */
    val partSlotCount: Int get() = tier.slotCount

    /** ケースの状態 */
    val status: ComputerStatus
        get() = ComputerStatus.entries.getOrElse(data.get(DATA_STATUS)) { ComputerStatus.OFF }

    /** [ComputerStatus.ERROR] の理由 */
    val error: ComputerError?
        get() = ComputerError.entries.getOrNull(data.get(DATA_ERROR) - 1)

    /** 足りない部品 */
    val missingParts: List<PartKind>
        get() {
            val mask = data.get(DATA_MISSING)
            return PartKind.entries.filter { mask and (1 shl it.ordinal) != 0 }
        }

    override fun clickMenuButton(player: Player, id: Int): Boolean {
        if (id != POWER_BUTTON) return false
        blockEntity?.pressPowerButton()
        return true
    }

    override fun stillValid(player: Player): Boolean =
        blockEntity == null || Container.stillValidBlockEntity(blockEntity, player)

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots.getOrNull(index) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY
        val stack = slot.item
        val original = stack.copy()
        val moved = if (index < partSlotCount) {
            moveItemStackTo(stack, partSlotCount, slots.size, true)
        } else {
            moveItemStackTo(stack, 0, partSlotCount, false)
        }
        if (!moved) return ItemStack.EMPTY
        if (stack.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
        if (stack.count == original.count) return ItemStack.EMPTY
        slot.onTake(player, stack)
        return original
    }

    companion object {
        /** 電源ボタンのメニューのボタンの番号 */
        const val POWER_BUTTON: Int = 0

        private const val DATA_STATUS = 0
        private const val DATA_ERROR = 1
        private const val DATA_MISSING = 2
        private const val DATA_COUNT = 3

        /** GUI の大きさ */
        const val IMAGE_WIDTH: Int = 176
        const val IMAGE_HEIGHT: Int = 193

        /** 部品スロットの左上と間隔（アイテムの描画位置。枠はその1つ外） */
        const val PART_X: Int = 12
        const val PART_Y: Int = 20
        const val PART_COLUMN_STEP: Int = 22

        /** 状態の行（2行まで）の y */
        const val STATUS_Y: Int = 78

        /** 持ち物の位置 */
        const val INVENTORY_X: Int = 8
        const val INVENTORY_LABEL_Y: Int = 100
        const val INVENTORY_Y: Int = 111
        const val HOTBAR_Y: Int = 169

        /** [kind] の列の x */
        fun partSlotX(kind: PartKind): Int = PART_X + kind.ordinal * PART_COLUMN_STEP

        /** 列の [row] 行目の y */
        fun partSlotY(row: Int): Int = PART_Y + row * 18

        /** サーバー: [be] の GUI を作る */
        fun server(containerId: Int, playerInventory: Inventory, be: CaseBlockEntity): CaseMenu =
            CaseMenu(containerId, playerInventory, be.tier, be.parts, serverData(be), be)

        /** クライアント: サーバーが [writeOpenData] で書いた追加データから GUI を作る */
        fun client(containerId: Int, playerInventory: Inventory, buf: RegistryFriendlyByteBuf): CaseMenu {
            val tier = CaseTier.fromLevel(buf.readVarInt())
            return CaseMenu(containerId, playerInventory, tier, CaseParts(tier), SimpleContainerData(DATA_COUNT), null)
        }

        /** GUI を開くときの追加データ（ティア）を書く */
        fun writeOpenData(buf: RegistryFriendlyByteBuf, tier: CaseTier) {
            buf.writeVarInt(tier.level)
        }

        private fun serverData(be: CaseBlockEntity): ContainerData = object : ContainerData {
            override fun get(index: Int): Int {
                val computer = be.computer
                return when (index) {
                    DATA_STATUS -> computer.status.ordinal
                    DATA_ERROR -> computer.error?.let { it.ordinal + 1 } ?: 0
                    DATA_MISSING -> computer.missingParts.fold(0) { mask, kind -> mask or (1 shl kind.ordinal) }
                    else -> 0
                }
            }

            override fun set(index: Int, value: Int) {}

            override fun getCount(): Int = DATA_COUNT
        }
    }
}
