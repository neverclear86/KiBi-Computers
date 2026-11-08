package pro.ikili.kibicomputers.neoforge.computer

import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.ComputerCase
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.computer.MachineSpecs
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.computer.PartTier
import pro.ikili.kibicomputers.core.kernel.DiskResolver
import pro.ikili.kibicomputers.core.kernel.MachineEnvironment
import pro.ikili.kibicomputers.neoforge.machine.MachineKey
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import pro.ikili.kibicomputers.neoforge.registry.KiBiDataComponents

/**
 * ケースブロックのブロックエンティティ（#121）。部品スロット（[parts]）と、電源・状態（`core` の [ComputerCase]）を持つ。
 *
 * - 電源ボタン（[pressPowerButton]）で、サーバーのマシン管理（[ServerMachines]）にマシンを作る・止める
 * - 動いている間に起動に要る部品・電源を入れたときにあったディスクを抜くと止まる（判断は `core` の [ComputerCase]）
 * - 電源を入れるとき、識別子のないディスクに識別子を付け（#124）、挿さっているディスクの写しをマシンの環境に渡す
 * - チャンクのアンロード・ブロックの破壊でマシンを外す
 * - 電源を入れると、隣の画面とキーボードにつないで、ディスク → 標準 OS の同梱イメージの順に起動する
 *   （仮の結線。[TestComputerWiring]、#307・#461）。同梱イメージから起動したら、最初のディスクが `/home` になる
 *
 * セーブデータには部品だけを書く（`Parts`）。電源の状態は書かず、読み込んだら電源が切れている（マシンの状態の保存は #81）。
 */
class CaseBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(KiBiBlocks.CASE_BLOCK_ENTITY.get(), pos, state), MenuProvider {

    /** このケースのティア。ブロックから決まる。 */
    val tier: CaseTier = (state.block as CaseBlock).tier

    /** 部品スロット */
    val parts: CaseParts = CaseParts(tier) { onPartsChanged() }

    /** テスト用コンピューターの仮の結線（#307）。#129・#99 ができたら置き換える */
    internal val wiring: TestComputerWiring = TestComputerWiring()

    /** 電源と状態。サーバーでだけ動く（クライアントでは常に [ComputerStatus.OFF]） */
    val computer: ComputerCase<MachineKey> = ComputerCase(tier, wiring)

    /**
     * 起動ディスクの候補の最後に足す同梱イメージ（既定は標準 OS。[TestComputerWiring.OS_BOOT_DISK]）。
     * GameTest が Lua の REPL（[TestComputerWiring.TEST_BOOT_DISK]）で動かすために差し替える。保存しない
     */
    internal var fallbackBootDisk: String = TestComputerWiring.OS_BOOT_DISK

    /** ケースに入っているスピーカー（`kibicomputers:speaker`）のデバイスの address（#65）。最初に使うときに作り、セーブデータの [SPEAKER_ADDRESS_TAG] に書く */
    val speakerAddress: String get() = speakerSlot.get()

    private val speakerSlot = DeviceAddressSlot(SPEAKER_ADDRESS_TAG) { setChanged() }

    // 起動部品（#125）はまだないので、挿さっているとみなす（仮。#307）
    private val installedForBoot = TestComputerWiring.withBootPart(tier, parts.installed)

    /**
     * 電源ボタン。サーバーでだけ働く。電力の有無（#128）は、今は常にあるとして扱う。
     *
     * @return 押した後の状態
     */
    fun pressPowerButton(): ComputerStatus {
        val level = level as? ServerLevel ?: return computer.status
        val before = computer.machine
        // マシンの設定は挿さっている CPU・RAM から決める（#347。正式な式は #122・#123）
        val config = MachineSpecs.configOf(tier, parts.tiers)
        var environment = MachineEnvironment.DEFAULT
        var boot = TestComputerWiring.bootConfig(emptyList(), fallbackBootDisk)
        if (computer.status != ComputerStatus.RUNNING) {
            val disks = prepareDisks()
            environment = MachineEnvironment.builder().disks(disks.resolver).build()
            boot = TestComputerWiring.bootConfig(disks.bootOrder, fallbackBootDisk)
        }
        val status = computer.pressPowerButton(
            ServerMachines.current,
            MachineKey.of(level, blockPos),
            installedForBoot,
            true,
            config,
            environment,
        )
        val machine = computer.machine
        if (machine == null) {
            wiring.disconnect()
        } else if (machine !== before) {
            wiring.connect(level, blockPos, machine, speakerAddress, boot)
        }
        return status
    }

    /** 電源を入れる前のディスクの用意: 挿さっているディスクの写しと、起動の候補（ディスクのスロットの順） */
    private class PreparedDisks(val resolver: DiskResolver, val bootOrder: List<String>)

    /**
     * ディスクのスロットの、識別子のないディスクに識別子を付け（形の正しくない値は上書きしない）、
     * 挿さっているディスクの写し（[pro.ikili.kibicomputers.core.computer.DiskStore.snapshot]）を作る。
     * サーバーのディスクの置き場（[WorldDisks]）がなければ、ディスクなし
     */
    private fun prepareDisks(): PreparedDisks {
        val store = WorldDisks.current ?: return PreparedDisks(DiskResolver.NONE, emptyList())
        val disks = LinkedHashMap<String, PartTier>()
        var assigned = false
        for (slot in 0 until tier.slotCount) {
            if (tier.kindOf(slot) != PartKind.STORAGE) continue
            val stack = parts.getStackInSlot(slot)
            val item = stack.item as? DiskItem ?: continue
            if (DiskItem.needsId(stack)) {
                stack.set(KiBiDataComponents.DISK_ID.get(), store.newId())
                assigned = true
            }
            DiskItem.diskIdOf(stack)?.let { disks.putIfAbsent(it, item.tier) }
        }
        if (assigned) setChanged()
        return PreparedDisks(store.snapshot(disks), disks.keys.toList())
    }

    private fun onPartsChanged() {
        setChanged()
        val level = level ?: return
        if (!level.isClientSide && computer.partsChanged(installedForBoot)) wiring.disconnect()
    }

    override fun onChunkUnloaded() {
        super.onChunkUnloaded()
        computer.chunkUnloaded()
        wiring.disconnect()
    }

    override fun setRemoved() {
        super.setRemoved()
        computer.removed()
        wiring.disconnect()
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        tag.put(PARTS_TAG, parts.serializeNBT(registries))
        speakerSlot.save(tag)
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        if (tag.contains(PARTS_TAG, Tag.TAG_COMPOUND.toInt())) parts.deserializeNBT(registries, tag.getCompound(PARTS_TAG))
        speakerSlot.load(tag)
    }

    override fun getDisplayName(): Component = blockState.block.name

    override fun createMenu(containerId: Int, playerInventory: Inventory, player: Player): AbstractContainerMenu =
        CaseMenu.server(containerId, playerInventory, this)

    companion object {
        /** セーブデータの部品のキー */
        const val PARTS_TAG: String = "Parts"

        /** セーブデータの、スピーカーのデバイスの address のキー（#65） */
        const val SPEAKER_ADDRESS_TAG: String = "SpeakerAddress"
    }
}
