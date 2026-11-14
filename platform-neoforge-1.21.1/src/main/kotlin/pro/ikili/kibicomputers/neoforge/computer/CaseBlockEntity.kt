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
import net.minecraft.world.level.chunk.LevelChunk
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.ComputerCase
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.computer.MachineSpecs
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.computer.PartTier
import pro.ikili.kibicomputers.core.computer.RestoreReady
import pro.ikili.kibicomputers.core.kernel.BootFailure
import pro.ikili.kibicomputers.core.kernel.DiskSnapshot
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.kernel.MachineEnvironment
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.ClaimResult
import pro.ikili.kibicomputers.core.persist.MachineIdentity
import pro.ikili.kibicomputers.core.persist.MachineOwner
import pro.ikili.kibicomputers.core.persist.PendingSave
import pro.ikili.kibicomputers.core.persist.RestoreListener
import pro.ikili.kibicomputers.core.persist.RestoreOutcome
import pro.ikili.kibicomputers.core.persist.RestoreSource
import pro.ikili.kibicomputers.core.persist.SaveCompletion
import pro.ikili.kibicomputers.core.persist.adoptsCompletion
import pro.ikili.kibicomputers.neoforge.machine.MachineKey
import pro.ikili.kibicomputers.neoforge.machine.WorldMachineSaves
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
 * セーブデータには部品（`Parts`）と、マシンの ID の参照（`MachineId`・`SaveGeneration`・`OwnerEpoch`。#81、ADR 0081）と、
 * 電源の状態（`PoweredOn`。#85、ADR 0085）を書く。電源が入っていたら、サーバーで読み込まれたとき（[onLoad]）・チャンクが FULL に
 * 戻ったときに復元の順番待ち（[ServerMachines.offerRestore]）に入り、順番が来たら（[startRestore]）保存した世代から戻す
 * （戻せなければ冷えた起動）。`PoweredOn` のない古いセーブデータは、電源が切れているとして読む。
 *
 * マシンの ID（#81）:
 * - NBT から読んだ生の値（[rawId] など）と、確定した ID（[identity]）を分けて持つ。[saveAdditional] は確定した ID があればそれを、
 *   なければ生の値をそのまま書き戻す（参照を書くだけで、何も記録しない）
 * - サーバーで読み込まれたら（[onLoad]）、生の値で持ち主を確かめ（`MachineSaveStore.claim`）、ID の索引に入れる。
 *   アンロード・`setRemoved` で索引から外す
 * - 確定した ID があるときの [loadAdditional]（`/data merge` など）は、ID を変えない。読み込み済みで ID がまだなければ、その場で確かめる
 * - ID はマシンを初めて取り出すときに振る（電源を入れていないケースには振らない）
 *
 * 書き込みのスレッド（#84、ADR 0084）: 取り出した写しは書き込みに回し（[captureAndSubmit]。予約は [pendingSave]）、NBT に書く [identity] は、
 * 書き込みの完了（[onSaveCompleted]）か、アンロードで予約の世代を採るとき（[adoptPendingSave]）にだけ進める。
 * 書き込み中の「今の ID」は [currentIdentity]（予約の ID。なければ [identity]）で、次の予約・削除・索引の外しはこれを使う
 */
class CaseBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(KiBiBlocks.CASE_BLOCK_ENTITY.get(), pos, state), MenuProvider {

    /** このケースのティア。ブロックから決まる。 */
    val tier: CaseTier = (state.block as CaseBlock).tier

    /** 部品スロット */
    val parts: CaseParts = CaseParts(tier) { onPartsChanged() }

    /** テスト用コンピューターの仮の結線（#307）。#129・#99 ができたら置き換える */
    internal val wiring: TestComputerWiring = TestComputerWiring()

    /**
     * マシンからの通知を [wiring] に中継する。ケース（[computer]）が自分の判断でマシンを外した（CRASHED・ゲストが止めた。#640、
     * 起動に失敗した。#641）ときは、結線も外す（キーボードの持ち主・画面の listener を残さない）。外すかどうかは [ComputerCase] が決め、ここでは結果
     * （[ComputerCase.machine] が `null`）だけを見る。[ComputerCase] は自分の処理の後に呼ぶので、このときにはもう外れている。
     * 結線に先に渡してから外す（起動の失敗の理由 [TestComputerWiring.bootFailure] は、つないでいるマシンのものだけを覚え、外しても残る）
     */
    private val machineEvents = object : MachineHost {
        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) {
            wiring.onStateChanged(machine, state, cause)
            if (computer.machine == null && wiring.connectedMachine === machine) wiring.disconnect()
        }

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) = wiring.onConsoleOutput(machine, bytes)

        override fun onBootFailed(machine: Machine, failure: BootFailure, detail: String) {
            wiring.onBootFailed(machine, failure, detail)
            if (computer.machine == null && wiring.connectedMachine === machine) wiring.disconnect()
        }

        override fun onRebootRequested(machine: Machine) = wiring.onRebootRequested(machine)
    }

    /** 電源と状態。サーバーでだけ動く（クライアントでは常に [ComputerStatus.OFF]） */
    val computer: ComputerCase<MachineKey> = ComputerCase(tier, machineEvents) { setChanged() }

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

    /**
     * 書き込み中の最後の予約（#84）。完了を受け取って、それが最後の予約なら外す。書き込み中に次の予約をしたら置き換える
     */
    var pendingSave: PendingSave? = null
        private set

    /** 今の ID（書き込み中なら最後の予約の ID、なければ [identity]。#84 の規則 7c） */
    val currentIdentity: MachineIdentity?
        get() = pendingSave?.identity ?: identity

    /** FULL でなくなったときのチャンク（#545・#84。書き込みの完了で `setUnsaved(true)` を付ける。FULL に戻っても同じもの） */
    internal var demotedChunk: LevelChunk? = null
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
        // 電源が入っていたら、復元の順番待ちに入る（#85。始めるのは ServerMachines の tick）
        if (computer.beginRestore()) ServerMachines.offerRestore(this)
    }

    /** 最後に [startRestore] で決めた戻す世代（テスト・#575 用。冷えた起動なら `null`。まだなら `null`） */
    internal var lastRestoreSource: RestoreSource? = null
        private set

    /**
     * [startRestore] で復元を始めた回数（テスト・#708 用。メモリにだけ持ち、保存しない）。共有の [ServerMachines.Stats] は並行する
     * ほかのテストのケースでも動くので、テストはこのケースの記録を見る
     */
    internal var restoreStarts: Int = 0
        private set

    /** 最後に [startRestore] へ渡った `writesTimedOut`（テスト・#708 用。まだなら `false`） */
    internal var lastRestoreTimedOut: Boolean = false
        private set

    /**
     * 最後に始めた復元の知らせ（テスト・#708 用）。始めた時点で `null` に戻す。知らせが来ないとき（冷えた起動を直接始めた・
     * `restore()` が受け付けなかった）も `null` のまま。前の復元の遅れた知らせでは上書きしない
     */
    internal var lastRestoreOutcome: RestoreOutcome? = null
        private set

    /**
     * 復元の順番が来た（#85。[ServerMachines] の tick から）。順番待ちでなければ何もしない。今の ID（[currentIdentity]。
     * 書き込み中なら予約の世代。#575）が `claim` に通っていれば、その世代から戻す（書けなかった世代なら、置き場が直下の世代に落とす。
     * ADR 0084 の規則 9）。ID がない・新しい ID を振ってからまだ1つも書いていない・書き込みが期限までに終わらない（[writesTimedOut]）なら
     * 冷えた起動にする
     */
    internal fun startRestore(writesTimedOut: Boolean) {
        val level = level as? ServerLevel ?: return
        if (!computer.isRestorePending || isRemoved) return
        val config = MachineSpecs.configOf(tier, parts.tiers)
        val disks = prepareDisks()
        val environment = MachineEnvironment.builder().disks(disks.snapshot).build()
        val boot = TestComputerWiring.bootConfig(disks.bootOrder, fallbackBootDisk)
        val store = WorldMachineSaves.current
        // 予約の世代を使う: FULL でなくなったときの取り出しは identity を進めず（規則 5）、完了を受け取る前に順番が来ることがある（#575）
        val id = currentIdentity
        val source = when {
            store == null || id == null -> null
            writesTimedOut -> null
            // 新しい ID は振った直後（世代 0）だけ冷えた起動にする。その後に書いた世代があれば戻す（FULL に戻ったときなど。#575）
            lastClaim?.isNewId == true && id.generation == 0L -> null
            else -> RestoreSource(store, id.id, id.generation)
        }
        lastRestoreSource = source
        lastRestoreTimedOut = writesTimedOut
        lastRestoreOutcome = null
        val serial = ++restoreStarts
        if (source == null) LOGGER.info("Cold-booting the computer at {} (id={}, claim={}, writesTimedOut={})", blockPos, id, lastClaim, writesTimedOut)
        ServerMachines.recordRestoreStart(source != null, writesTimedOut)
        computer.startRestore(
            ServerMachines.current,
            MachineKey.of(level, blockPos),
            installedForBoot,
            true,
            config,
            environment,
            boot,
            source,
            RestoreReady { machine -> wiring.attach(level, blockPos, machine, speakerAddress) },
            RestoreListener { _, outcome, cause ->
                noteRestoreOutcome(serial, outcome)
                ServerMachines.recordRestoreFinished(blockPos, outcome, cause)
            },
        )
        if (computer.machine == null) wiring.disconnect()
    }

    /**
     * [serial] 番目に始めた復元の知らせを [lastRestoreOutcome] に記録する（#708）。知らせは close の後も届くので、最後に始めた復元
     * （[restoreStarts]）の知らせだけを記録し、取り消した前の復元の遅れた知らせでは上書きしない。GameTest が直接呼んで確かめる
     */
    internal fun noteRestoreOutcome(serial: Int, outcome: RestoreOutcome) {
        if (serial == restoreStarts) lastRestoreOutcome = outcome
    }

    /** 生の値で持ち主を確かめる（ID がなければ何もしない）。NBT と違う値になったら、次の tick に `setChanged` する */
    private fun claimIdentity() {
        // 先に取り出して ID が決まっていれば（書き込み中を含む）、それを使う（置いた直後の tick に取り出したときなど）
        if (currentIdentity != null) return
        val store = WorldMachineSaves.current ?: return
        val self = owner ?: return
        val raw = rawId ?: return
        val claim = store.claim(raw, rawGeneration ?: 0, rawEpoch ?: 0, self)
        lastClaim = claim.result
        identity = claim.identity
        val id = claim.identity
        if (id == null || id.id.toString() != raw || id.generation != rawGeneration || id.epoch != rawEpoch) ServerMachines.markChangedLater(this)
    }

    /** 索引から外す（自分を指しているときだけ。書き込み中なら予約の ID） */
    private fun leaveIndex() {
        if (!loadedOnServer) return
        loadedOnServer = false
        val id = currentIdentity ?: return
        val self = owner ?: return
        WorldMachineSaves.current?.index?.removeIfOwner(id.id, self)
    }

    /**
     * 動いているマシンの写しを取り、世代のファイルの書き込みを予約して書き込みのスレッドに回す（#81・#84）。マシンがない・取り出し済み・
     * [policy] が [CapturePolicy.SKIP_IF_TRANSIENT] で TRANSIENT・復元の途中（終わりの知らせが返るまで）・冷えた起動待ち・CRASHED の台（#698）なら何もせず `null`（#589）。CRASHED の台の電源を切るのは呼び手（[ServerMachines] のアンロード・停止の経路）。ID がなければ置き場が振る（`owner.bin` も書き込みのスレッドで書く）。
     * 今の ID（[currentIdentity]）があれば、置き場の今の基準からの差分の世代にする（#83。基準が使えなければ全体を取り直す）。
     * [identity] は変えない（完了か [adoptPendingSave] で進む）。取り出しの時間は [ServerMachines.Stats] に数える（負荷試験用。#553）
     *
     * @return 予約した書き込み（[PendingSave.writes] が書き込みの回数の見積もり）
     */
    internal fun captureAndSubmit(policy: CapturePolicy): PendingSave? {
        val store = WorldMachineSaves.current ?: return null
        val self = owner ?: return null
        val capturer = computer.capturer(policy, ServerMachines.saveConfig.fullSaveChangedRatio)
        val submitted = store.captureAndSubmit(currentIdentity, self, capturer, this) ?: return null
        pendingSave = submitted.pending
        ServerMachines.recordSubmit(submitted)
        return submitted.pending
    }

    /**
     * 書き込みの完了を受け取る（#84。[ServerMachines] がメインスレッドで呼ぶ）。書けた世代を採るかは [adoptsCompletion] で決める。
     * 書けなければ NBT は変えない（前の世代のまま）。最後の予約の完了なら [pendingSave] を外す
     *
     * @return [identity] を進めた（呼び出し側が `setChanged` する）
     */
    internal fun onSaveCompleted(completion: SaveCompletion): Boolean {
        var adopted = false
        val result = completion.result
        if (result != null) {
            if (adoptsCompletion(identity, currentIdentity, result.identity)) {
                identity = result.identity
                adopted = true
            }
        } else {
            LOGGER.warn(
                "Failed to save the computer at {} as {} (keeping the previous generation)", blockPos, completion.pending.identity, completion.error,
            )
        }
        if (pendingSave === completion.pending) pendingSave = null
        return adopted
    }

    /**
     * アンロード（#84 の規則 4）: 書き込み中の予約の世代を、終わる前に NBT に載せる（続く `ChunkMap.save` が書く）。
     * 予約がなければ何もしない
     *
     * @return 採った
     */
    internal fun adoptPendingSave(): Boolean {
        val pending = pendingSave ?: return false
        if (identity == pending.identity) return false
        identity = pending.identity
        return true
    }

    /** 自動保存の書き込みの回数の見積もり（今の ID があれば 1、ID を振る台は 2） */
    internal val plannedWrites: Int get() = if (currentIdentity == null) 2 else 1

    /**
     * プレイヤーの破壊・爆発: このマシンの保存データのディレクトリを消す（ほかの経路で消えたブロックのディレクトリは残る）。
     * 書き込み中なら予約の ID を消す（消すのは書き込みの列の仕事で、書き込みの後に走る。#84 の規則 7）
     */
    internal fun deleteSaves() {
        val id = currentIdentity ?: return
        WorldMachineSaves.current?.delete(id.id)
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
            environment = MachineEnvironment.builder().disks(disks.snapshot).build()
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
    private class PreparedDisks(val snapshot: DiskSnapshot, val bootOrder: List<String>)

    /**
     * ディスクのスロットの、識別子のないディスクに識別子を付け（形の正しくない値は上書きしない）、
     * 挿さっているディスクの写し（[pro.ikili.kibicomputers.core.computer.DiskStore.snapshot]）を作る。
     * サーバーのディスクの置き場（[WorldDisks]）がなければ、ディスクなし
     */
    private fun prepareDisks(): PreparedDisks {
        val store = WorldDisks.current ?: return PreparedDisks(DiskSnapshot.EMPTY, emptyList())
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
     * 隣のブロックが変わった。止まっていなくて（再起動の途中も含む。#626）画面をつないでいなければ、次の tick に [reconnectScreen] を回す（#531。仮の結線）。
     * 置いたブロックのデータ（アイテムやコマンドの NBT の address）は `setBlock` の後で読み込むので、ここではまだつながない（#535）
     */
    internal fun neighborChanged() {
        val level = level as? ServerLevel ?: return
        if (wiring.canReconnectScreen(computer.machine)) level.scheduleTick(blockPos, blockState.block, 1)
    }

    /** [neighborChanged] で回した次の tick。止まっていなくて（再起動の途中も含む）画面をつないでいなければ、隣の画面とキーボードをつなぎ直す（#531・#535） */
    internal fun reconnectScreen() {
        val level = level as? ServerLevel ?: return
        wiring.reconnectScreen(level, blockPos, computer.machine)
    }

    private fun onPartsChanged() {
        setChanged()
        val level = level ?: return
        if (level.isClientSide) return
        // 動いている間だけ、今挿さっているディスクの表を作り直してマシンに渡す（#56。挿したディスクに識別子も付ける）
        val disks = if (computer.status == ComputerStatus.RUNNING) prepareDisks().snapshot else DiskSnapshot.EMPTY
        if (computer.partsChanged(installedForBoot, disks)) wiring.disconnect()
    }

    override fun onChunkUnloaded() {
        super.onChunkUnloaded()
        leaveIndex()
        ServerMachines.untrack(this)
        ServerMachines.cancelRestore(this)
        computer.chunkUnloaded()
        wiring.disconnect()
    }

    /**
     * チャンクが FULL でなくなった（ティックしなくなったが、メモリには残る。#545）。[ServerMachines] が取り出して書いた後に呼ぶ。
     * マシンを外して電源を切る（アンロードと同じ）。ブロックエンティティは残るので、ID の索引と、読み込まれたケースの一覧には残す。
     * 電源の状態（[ComputerCase.poweredOn]）は変えないので、チャンクが FULL に戻ったら、読み込み直したのと同じく復元する（#85）
     */
    internal fun chunkDemoted(chunk: LevelChunk) {
        demotedChunk = chunk
        ServerMachines.cancelRestore(this)
        computer.chunkUnloaded()
        wiring.disconnect()
    }

    override fun setRemoved() {
        super.setRemoved()
        leaveIndex()
        ServerMachines.untrack(this)
        ServerMachines.cancelRestore(this)
        computer.removed()
        wiring.disconnect()
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        tag.put(PARTS_TAG, parts.serializeNBT(registries))
        speakerSlot.save(tag)
        tag.putBoolean(POWERED_ON_TAG, computer.poweredOn)
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
        // 古いセーブデータ（タグがない）は電源が切れている（#85）
        computer.loadPoweredOn(tag.getBoolean(POWERED_ON_TAG))
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

        /** セーブデータの、電源が入っているかのキー（#85、ADR 0085） */
        const val POWERED_ON_TAG: String = "PoweredOn"

        private val LOGGER = LogUtils.getLogger()
    }
}
