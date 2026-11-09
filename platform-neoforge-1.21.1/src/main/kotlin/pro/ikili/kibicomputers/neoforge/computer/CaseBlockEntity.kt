package pro.ikili.kibicomputers.neoforge.computer

import com.mojang.logging.LogUtils
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
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.ClaimResult
import pro.ikili.kibicomputers.core.persist.MachineIdentity
import pro.ikili.kibicomputers.core.persist.MachineOwner
import pro.ikili.kibicomputers.neoforge.machine.MachineKey
import pro.ikili.kibicomputers.neoforge.machine.WorldMachineSaves
import java.io.IOException
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import pro.ikili.kibicomputers.neoforge.registry.KiBiDataComponents

/**
 * ケースブロックのブロックエンティティ（#121）。部品スロット（[parts]）と、電源・状態（`core` の [ComputerCase]）を持つ。
 *
 * - 電源ボタン（[pressPowerButton]）で、サーバーのマシン管理（[ServerMachines]）にマシンを作る・止める
 * - 動いている間に起動に要る部品・電源を入れたときにあったディスクを抜くと止まる（判断は `core` の [ComputerCase]）
 * - 電源を入れるとき、識別子のないディスクに識別子を付け（#124）、挿さっているディスクの写しをマシンの環境に渡す
 * - チャンクのアンロード・チャンクが FULL でなくなったとき（[chunkDemoted]。#545）・ブロックの破壊でマシンを外す
 * - 電源を入れると、隣の画面とキーボードにつないで、ディスク → 標準 OS の同梱イメージの順に起動する
 *   （仮の結線。[TestComputerWiring]、#307・#461）。同梱イメージから起動したら、最初のディスクが `/home` になる。
 *   動いている間に画面を置き直したら、次の tick につなぎ直す（[neighborChanged]。#531・#535）
 *
 * セーブデータには部品（`Parts`）と、マシンの ID の参照（`MachineId`・`SaveGeneration`・`OwnerEpoch`。#81、ADR 0081）を書く。
 * 電源の状態は書かず、読み込んだら電源が切れている（状態の復元は #85）。
 *
 * マシンの ID（#81）:
 * - NBT から読んだ生の値（[rawId] など）と、確定した ID（[identity]）を分けて持つ。[saveAdditional] は確定した ID があればそれを、
 *   なければ生の値をそのまま書き戻す（参照を書くだけで、何も記録しない）
 * - サーバーで読み込まれたら（[onLoad]）、生の値で持ち主を確かめ（`MachineSaveStore.claim`）、ID の索引に入れる。
 *   アンロード・`setRemoved` で索引から外す
 * - 確定した ID があるときの [loadAdditional]（`/data merge` など）は、ID を変えない。読み込み済みで ID がまだなければ、その場で確かめる
 * - ID はマシンを初めて取り出すときに振る（電源を入れていないケースには振らない）
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

    // ---- マシンの ID（#81） ----

    /** NBT から読んだ生の `MachineId`（なければ `null`） */
    private var rawId: String? = null

    /** NBT から読んだ生の `SaveGeneration`（なければ `null`） */
    private var rawGeneration: Long? = null

    /** NBT から読んだ生の `OwnerEpoch`（なければ `null`） */
    private var rawEpoch: Long? = null

    /** 確定したマシンの ID（`claim` に通った・振った）。まだなければ `null` */
    var identity: MachineIdentity? = null
        private set

    /** 最後の `claim` の判定（テスト・#85 用。まだなら `null`） */
    var lastClaim: ClaimResult? = null
        private set

    /** サーバーで読み込まれた（[onLoad] の後。アンロード・`setRemoved` で下ろす） */
    private var loadedOnServer = false

    /** このブロックエンティティの位置（持ち主の照合に使う。`owner.bin` と同じ書き方） */
    val owner: MachineOwner?
        get() = level?.let { MachineOwner(it.dimension().location().toString(), blockPos.x, blockPos.y, blockPos.z) }

    override fun onLoad() {
        super.onLoad()
        val level = level as? ServerLevel ?: return
        loadedOnServer = true
        ServerMachines.track(this)
        claimIdentity()
    }

    /** 生の値で持ち主を確かめる（ID がなければ何もしない）。NBT と違う値になったら、次の tick に `setChanged` する */
    private fun claimIdentity() {
        // 先に取り出して ID が決まっていれば、それを使う（置いた直後の tick に取り出したときなど）
        if (identity != null) return
        val store = WorldMachineSaves.current ?: return
        val self = owner ?: return
        val raw = rawId ?: return
        val claim = store.claim(raw, rawGeneration ?: 0, rawEpoch ?: 0, self)
        lastClaim = claim.result
        identity = claim.identity
        val id = claim.identity
        if (id == null || id.id.toString() != raw || id.generation != rawGeneration || id.epoch != rawEpoch) ServerMachines.markChangedLater(this)
    }

    /** 索引から外す（自分を指しているときだけ） */
    private fun leaveIndex() {
        if (!loadedOnServer) return
        loadedOnServer = false
        val id = identity ?: return
        val self = owner ?: return
        WorldMachineSaves.current?.index?.removeIfOwner(id.id, self)
    }

    /**
     * 動いているマシンの写しを取り、世代のファイルを書く（#81）。マシンがない・取り出し済み・[policy] が
     * [CapturePolicy.SKIP_IF_TRANSIENT] で TRANSIENT なら何もせず 0。ID がなければここで振る（`owner.bin` が書けなければ 0）。
     * 書けたら [identity] が新しい世代を覚える（`setChanged`・`setUnsaved` は呼び出し側）。
     * 取り出し・符号化・書き込みの時間と世代のファイルの大きさは [ServerMachines.Stats] に数える（負荷試験用。#553）
     *
     * @return 行った書き込み（fsync）の回数
     * @throws IOException 世代のファイルが書けなかった
     */
    @Throws(IOException::class)
    internal fun captureAndWrite(policy: CapturePolicy): Int {
        val store = WorldMachineSaves.current ?: return 0
        val self = owner ?: return 0
        val captureStart = System.nanoTime()
        val captured = computer.capture(policy)
        val captureNanos = System.nanoTime() - captureStart
        ServerMachines.Stats.captureNanos += captureNanos
        val source = captured ?: return 0
        var writes = 0
        var ownerWriteNanos = 0L
        val current = identity ?: run {
            val start = System.nanoTime()
            store.assignNewId(self).also { ownerWriteNanos = System.nanoTime() - start }
        }?.also {
            writes++
            identity = it
        }
        ServerMachines.Stats.fileWriteNanos += ownerWriteNanos
        if (current == null) {
            LOGGER.warn("Could not assign an id to the machine at {}; skipping this save", self)
            return 0
        }
        val result = store.write(current, self, source)
        identity = result.identity
        ServerMachines.Stats.recordSave(blockPos, captureNanos, result, ownerWriteNanos)
        return writes + result.writes
    }

    /** 自動保存の書き込みの回数の見積もり（ID があれば 1、ID を振る台は 2） */
    internal val plannedWrites: Int get() = if (identity == null) 2 else 1

    /** プレイヤーの破壊・爆発: このマシンの保存データのディレクトリを消す（ほかの経路で消えたブロックのディレクトリは残る） */
    internal fun deleteSaves() {
        val id = identity ?: return
        try {
            WorldMachineSaves.current?.delete(id.id)
        } catch (e: IOException) {
            LOGGER.warn("Failed to delete the saves of machine {}", id.id, e)
        }
    }

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

    /**
     * 隣のブロックが変わった。動いていて画面をつないでいなければ、次の tick に [reconnectScreen] を回す（#531。仮の結線）。
     * 置いたブロックのデータ（アイテムやコマンドの NBT の address）は `setBlock` の後で読み込むので、ここではまだつながない（#535）
     */
    internal fun neighborChanged() {
        val level = level as? ServerLevel ?: return
        if (wiring.canReconnectScreen(computer.machine)) level.scheduleTick(blockPos, blockState.block, 1)
    }

    /** [neighborChanged] で回した次の tick。動いていて画面をつないでいなければ、隣の画面とキーボードをつなぎ直す（#531・#535） */
    internal fun reconnectScreen() {
        val level = level as? ServerLevel ?: return
        wiring.reconnectScreen(level, blockPos, computer.machine)
    }

    private fun onPartsChanged() {
        setChanged()
        val level = level ?: return
        if (!level.isClientSide && computer.partsChanged(installedForBoot)) wiring.disconnect()
    }

    override fun onChunkUnloaded() {
        super.onChunkUnloaded()
        leaveIndex()
        ServerMachines.untrack(this)
        computer.chunkUnloaded()
        wiring.disconnect()
    }

    /**
     * チャンクが FULL でなくなった（ティックしなくなったが、メモリには残る。#545）。[ServerMachines] が取り出して書いた後に呼ぶ。
     * マシンを外して電源を切る（アンロードと同じ）。ブロックエンティティは残るので、ID の索引と、読み込まれたケースの一覧には残す。
     * チャンクが FULL に戻ったら、アンロードの後に読み込み直したのと同じく、電源が切れたまま
     */
    internal fun chunkDemoted() {
        computer.chunkUnloaded()
        wiring.disconnect()
    }

    override fun setRemoved() {
        super.setRemoved()
        leaveIndex()
        ServerMachines.untrack(this)
        computer.removed()
        wiring.disconnect()
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        tag.put(PARTS_TAG, parts.serializeNBT(registries))
        speakerSlot.save(tag)
        // 参照を書くだけ（チャンクの保存のほかに /data get block・/clone・ピックブロックからも呼ばれる。印は ChunkDataEvent.Save で付ける）
        val id = identity
        if (id != null) {
            tag.putString(MACHINE_ID_TAG, id.id.toString())
            tag.putLong(SAVE_GENERATION_TAG, id.generation)
            tag.putLong(OWNER_EPOCH_TAG, id.epoch)
        } else {
            rawId?.let { tag.putString(MACHINE_ID_TAG, it) }
            rawGeneration?.let { tag.putLong(SAVE_GENERATION_TAG, it) }
            rawEpoch?.let { tag.putLong(OWNER_EPOCH_TAG, it) }
        }
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        if (tag.contains(PARTS_TAG, Tag.TAG_COMPOUND.toInt())) parts.deserializeNBT(registries, tag.getCompound(PARTS_TAG))
        speakerSlot.load(tag)
        val id = if (tag.contains(MACHINE_ID_TAG, Tag.TAG_STRING.toInt())) tag.getString(MACHINE_ID_TAG) else null
        val generation = if (tag.contains(SAVE_GENERATION_TAG, Tag.TAG_LONG.toInt())) tag.getLong(SAVE_GENERATION_TAG) else null
        val epoch = if (tag.contains(OWNER_EPOCH_TAG, Tag.TAG_LONG.toInt())) tag.getLong(OWNER_EPOCH_TAG) else null
        val confirmed = identity
        if (confirmed != null) {
            // 確定した ID は変えない（/data merge で claim を飛ばせるため）。次の saveAdditional が確定した値で上書きする
            if (id != null && (id != confirmed.id.toString() || generation != confirmed.generation || epoch != confirmed.epoch)) {
                LOGGER.debug("Ignoring machine id change at {} ({} -> {}, {}, {})", blockPos, confirmed, id, generation, epoch)
            }
            return
        }
        rawId = id
        rawGeneration = generation
        rawEpoch = epoch
        // 読み込み済みで ID がまだなかった（/data merge で ID を足したときなど）: その場で確かめる
        if (loadedOnServer) claimIdentity()
    }

    override fun getDisplayName(): Component = blockState.block.name

    override fun createMenu(containerId: Int, playerInventory: Inventory, player: Player): AbstractContainerMenu =
        CaseMenu.server(containerId, playerInventory, this)

    companion object {
        /** セーブデータの部品のキー */
        const val PARTS_TAG: String = "Parts"

        /** セーブデータの、スピーカーのデバイスの address のキー（#65） */
        const val SPEAKER_ADDRESS_TAG: String = "SpeakerAddress"

        /** セーブデータの、マシンの ID のキー（UUID の正規の小文字の文字列。#81） */
        const val MACHINE_ID_TAG: String = "MachineId"

        /** セーブデータの、NBT が指す世代のキー（#81） */
        const val SAVE_GENERATION_TAG: String = "SaveGeneration"

        /** セーブデータの、持ち主の epoch のキー（#81） */
        const val OWNER_EPOCH_TAG: String = "OwnerEpoch"

        private val LOGGER = LogUtils.getLogger()
    }
}
