package pro.ikili.kibicomputers.neoforge.machine

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestServer
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ChunkHolder
import net.minecraft.server.level.FullChunkStatus
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.level.ChunkDataEvent
import net.neoforged.neoforge.event.level.ChunkEvent
import net.neoforged.neoforge.event.level.ChunkTicketLevelUpdatedEvent
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.kernel.SchedulerConfig
import pro.ikili.kibicomputers.core.machine.MachineErrorListener
import pro.ikili.kibicomputers.core.machine.MachineManager
import pro.ikili.kibicomputers.core.persist.AutosaveRotation
import pro.ikili.kibicomputers.core.persist.AutosaveTarget
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.ChunkMarkResult
import pro.ikili.kibicomputers.core.persist.FileModuleStore
import pro.ikili.kibicomputers.core.persist.MachineIdentityLimits
import pro.ikili.kibicomputers.core.persist.MachineOwner
import pro.ikili.kibicomputers.core.persist.MachineSaveConfig
import pro.ikili.kibicomputers.core.persist.QuiesceHandle
import pro.ikili.kibicomputers.core.persist.SaveReadiness
import pro.ikili.kibicomputers.core.persist.WriteResult
import pro.ikili.kibicomputers.core.util.CanonicalUuid
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/** サーバーの中のマシンのキー: ディメンションとブロックの座標。 */
data class MachineKey(val dimension: ResourceKey<Level>, val pos: BlockPos) {
    companion object {
        /** [level] の [pos] のキー。[pos] は変わらない座標に写す。 */
        fun of(level: Level, pos: BlockPos): MachineKey = MachineKey(level.dimension(), pos.immutable())
    }
}

/**
 * サーバー側のマシン管理（#120）と待機点保存（#81、ADR 0081）の接続層。サーバーの起動・停止・tick・チャンクの出来事を、`core` につなぐ。
 *
 * - サーバーの起動（[ServerAboutToStartEvent]。ワールドとチャンクを読み込む前）に [MachineManager] を作る
 * - 毎 tick の始め（[ServerTickEvent.Pre]）に [MachineManager.tick] を呼び、自動保存（[AutosaveRotation]）を1 tick 分すすめる
 * - チャンクのアンロード（[ChunkEvent.Unload]）: そのチャンクのケースを止め、待ち（tick ごとの予算）、取り出して書き、
 *   チャンクに `setUnsaved(true)` を付ける（続く `ChunkMap.save` が新しい世代を NBT に書く）
 * - チャンクが FULL でなくなった（[ChunkTicketLevelUpdatedEvent] でチケットの段階が 33 を超えた。#545）: アンロードと同じく
 *   止め、待ち、取り出して書き、`setUnsaved(true)` を付けてから、マシンを外す（[CaseBlockEntity.chunkDemoted]）。
 *   スポーンのチャンク・強制読み込み・チャンクローダーのまわりのチャンクは、FULL でなくなってもアンロードされずメモリに残るため。
 *   イベントの中では印を付けるだけで、tick の終わり（[ServerTickEvent.Post]）に、tick の時間が残っている間だけ流す（1 tick に最低1チャンク）。
 *   流す前に FULL に戻ったチャンクは何もしない。FULL に戻ったチャンクのケースは、アンロードの後に読み込み直したのと同じく電源が切れている
 * - チャンクの保存（[ChunkDataEvent.Save]）: NBT に入ったケースの世代を、持ち主の位置のタグだけで `markChunkGeneration` に渡す
 * - サーバーの停止（[ServerStoppingEvent]。`saveAllChunks` の前）: 全台を止め、待ち、取り出して書き、`setChanged` を付ける。
 *   止めた台は [ServerStoppedEvent] で捨てるまで止めたまま。そのあと、チャンクのアンロードの列を流しきる（[ChunkUnloadDrain]。#540）
 * - サーバーが止まった後（[ServerStoppedEvent]）に全マシンを止め、スレッドが終わるのを待つ（上限 [SHUTDOWN_TIMEOUT_MILLIS]）
 *
 * #81 では書き込みはメインスレッドで同期して行う（#84 で書き込みのスレッドに移す）。
 * マシンの中や接続層の通知で起きた例外は、サーバーを落とさずログに書く。
 */
object ServerMachines {
    private val LOGGER = LogUtils.getLogger()

    /** 停止のときに、マシンのスレッドが終わるのを待つ上限 */
    const val SHUTDOWN_TIMEOUT_MILLIS: Long = 5_000

    private val ERRORS = MachineErrorListener { message, error -> LOGGER.error(message, error) }

    @Volatile
    private var manager: MachineManager<MachineKey>? = null

    /** 動いているサーバーのマシン管理。サーバーが動いていなければ `null` */
    val current: MachineManager<MachineKey>?
        get() = manager

    /** 待機点保存の仮の値（確定は #251）。負荷試験・GameTest が差し替え、終わったら戻す */
    internal var saveConfig: MachineSaveConfig = MachineSaveConfig.DEFAULT
        set(value) {
            field = value
            // 止めていた台は続けてから、新しい予算の列にする
            rotation.clear()
            rotation = AutosaveRotation(value)
        }

    /**
     * MOD の自動保存を回すか。GameTest のサーバー（`runGameTestServer`・`runLoadTestServer`）では、起動のときに切る
     * （ほかのテストのケースを止めて、結果を揺らさないように）。負荷試験（`LoadRun`）は試験の間だけ有効にし、終わったら戻す
     */
    @Volatile
    internal var autosaveEnabled: Boolean = true

    /** サーバーで読み込まれているケース（読み込んだ順）。メインスレッドだけで使う */
    private val tracked = LinkedHashSet<CaseBlockEntity>()

    /** 次の tick の始めに `setChanged` するケース（`onLoad` の中ではチャンクに触らないため） */
    private val changedLater = LinkedHashSet<CaseBlockEntity>()

    private var rotation = AutosaveRotation<CaseBlockEntity>(saveConfig)

    /** FULL でなくなって、まだ流していないチャンク（積んだ順）。メインスレッドだけで使う（#545） */
    private val demoted = LinkedHashMap<ChunkHolder, ServerLevel>()

    /** 自動保存の周期を数える tick */
    private var autosaveTicks = 0L

    /** この tick のアンロードで待機点を待った時間の合計（ナノ秒） */
    private var unloadWaitedNanos = 0L

    /** 数え口（負荷試験・GameTest 用）。メインスレッドだけで読み書きする */
    internal object Stats {
        /** 受けた [ChunkEvent.Unload] のうち、ケースのあったもの */
        var unloadEventsWithCases: Long = 0

        /** FULL でなくなったチャンク（[ChunkTicketLevelUpdatedEvent]）のうち、動いているケースのあったもの（#545） */
        var demotedChunksWithCases: Long = 0

        /** FULL でなくなったチャンクで外したマシンの数（#545。取り出して書いた数は [unloadCaptures] に入る） */
        var demotedMachines: Long = 0

        /** アンロード・FULL でなくなったチャンクで取り出して書いた台の数 */
        var unloadCaptures: Long = 0

        /** アンロードで行った書き込み（fsync）の回数 */
        var unloadWrites: Long = 0

        /** 自動保存の書き込みの回数（tick ごとの最大） */
        var autosaveMaxTickWrites: Int = 0

        /** 自動保存で書いた台・飛ばした台の数（周期をまたいだ合計） */
        var autosaveWritten: Long = 0
        var autosaveSkipped: Long = 0

        /** 終わった自動保存の周期の、一巡にかかった tick 数（古い順） */
        val autosaveCycleTicks: MutableList<Int> = ArrayList()

        /** 自動保存の書き込み（取り出して書く。fsync を含む）にかかった時間の合計（ナノ秒） */
        var autosaveWriteNanos: Long = 0

        /**
         * 保存（自動保存・アンロード・停止。`CaseBlockEntity.captureAndWrite`）で、取り出し（`captureCopy`。カーネルロックの中）に
         * かかった時間の合計（ナノ秒。#553）。取り出さずに終わった（TRANSIENT で飛ばした）分も入る
         */
        var captureNanos: Long = 0

        /** 保存で、符号化（写しを保存データのバイト列にする）にかかった時間の合計（ナノ秒。#553） */
        var encodeNanos: Long = 0

        /**
         * 保存で、ファイルの書き込み（tmp → fsync → rename。世代ファイルと `owner.bin`）にかかった時間の合計（ナノ秒。#553）。
         * 負荷試験は、tick ごとの増分を tick の時間から引いて「書き込みを除く MSPT」（取り出し・符号化は含む）を出す
         * （fsync の時間はディスクに左右されるため）
         */
        var fileWriteNanos: Long = 0

        /** 世代のファイルを書くたびに [saves] に1件残すか（負荷試験が有効にする。ふだんは残さない） */
        var recordSaves: Boolean = false

        /** 世代のファイルを書いた1回ごとの記録（[recordSaves] のときだけ。古い順） */
        val saves: MutableList<SaveSample> = ArrayList()

        /** 世代のファイルを1つ書いた（`CaseBlockEntity.captureAndWrite` から） */
        fun recordSave(pos: BlockPos, captureNanos: Long, result: WriteResult, ownerWriteNanos: Long, retried: Boolean = false) {
            encodeNanos += result.encodeNanos
            fileWriteNanos += result.fileWriteNanos
            if (result.delta) deltaSaves++ else fullSaves++
            if (retried) deltaRetries++
            if (recordSaves) {
                saves += SaveSample(
                    pos, captureNanos, result.encodeNanos, result.fileWriteNanos + ownerWriteNanos, result.fileBytes, result.tmpfsBytes,
                    result.delta,
                )
            }
        }

        /** 書いた全体の世代・差分の世代の数（#83） */
        var fullSaves: Long = 0
        var deltaSaves: Long = 0

        /** 差分の基準が使えず、全体を取り直して書いた回数（#83） */
        var deltaRetries: Long = 0

        fun reset() {
            unloadEventsWithCases = 0
            demotedChunksWithCases = 0
            demotedMachines = 0
            unloadCaptures = 0
            unloadWrites = 0
            autosaveMaxTickWrites = 0
            autosaveWritten = 0
            autosaveSkipped = 0
            autosaveCycleTicks.clear()
            autosaveWriteNanos = 0
            captureNanos = 0
            encodeNanos = 0
            fileWriteNanos = 0
            fullSaves = 0
            deltaSaves = 0
            deltaRetries = 0
            saves.clear()
        }
    }

    /**
     * 世代のファイルを1つ書いた記録（負荷試験用。#553）。
     *
     * @property pos ケースの位置
     * @property captureNanos 取り出し（`captureCopy`）の時間
     * @property encodeNanos 符号化の時間
     * @property fileWriteNanos ファイルの書き込み（fsync を含む。ID を振った `owner.bin` も）の時間
     * @property fileBytes 世代のファイルの大きさ
     * @property tmpfsBytes `TMPFS` 節の中身の大きさ
     */
    internal data class SaveSample(
        val pos: BlockPos,
        val captureNanos: Long,
        val encodeNanos: Long,
        val fileWriteNanos: Long,
        val fileBytes: Int,
        val tmpfsBytes: Int,
        /** 差分の世代（#83） */
        val delta: Boolean = false,
    )

    /** NeoForge のイベントバスに登録する。MOD の初期化で1回だけ呼ぶ。 */
    fun register() {
        NeoForge.EVENT_BUS.addListener(::onServerAboutToStart)
        NeoForge.EVENT_BUS.addListener(::onServerTick)
        NeoForge.EVENT_BUS.addListener(::onServerTickPost)
        NeoForge.EVENT_BUS.addListener(::onChunkUnload)
        NeoForge.EVENT_BUS.addListener(::onChunkTicketLevelUpdated)
        NeoForge.EVENT_BUS.addListener(::onChunkDataSave)
        NeoForge.EVENT_BUS.addListener(::onServerStopping)
        NeoForge.EVENT_BUS.addListener(::onServerStopped)
    }

    private fun onServerAboutToStart(event: ServerAboutToStartEvent) {
        // 前のサーバーのものが残っていれば止める（統合サーバーを開き直したときなど）
        manager?.let { stop(it) }
        tracked.clear()
        changedLater.clear()
        rotation.clear()
        demoted.clear()
        autosaveTicks = 0
        // GameTest のサーバーでは自動保存を切る（負荷試験は自分で有効にする）
        autosaveEnabled = event.server !is GameTestServer
        // 待機点保存が指すモジュールの置き場（#82）: <world>/kibicomputers/modules
        val world = event.server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
        val modules = FileModuleStore(world.resolve(KiBiComputers.MOD_ID).resolve("modules"))
        // 設定はまだ仮の値（コンフィグにするのは #251）
        manager = MachineManager(SchedulerConfig.builder().moduleStore(modules).build(), ERRORS)
    }

    // ---- ケースの出入り ----

    /** サーバーで読み込まれたケース（`onLoad`） */
    internal fun track(be: CaseBlockEntity) {
        tracked += be
    }

    /** アンロード・`setRemoved` されたケース。自動保存の列からも外す（止めていれば、ここで続ける） */
    internal fun untrack(be: CaseBlockEntity) {
        tracked -= be
        changedLater -= be
        rotation.remove(be)?.close()
    }

    /** 次の tick の始めに [be] の `setChanged` を呼ぶ */
    internal fun markChangedLater(be: CaseBlockEntity) {
        changedLater += be
    }

    /** 読み込まれているケース（写し） */
    internal fun trackedCases(): List<CaseBlockEntity> = tracked.toList()

    // ---- tick と自動保存 ----

    /** 自動保存の周期を、今すぐ1つ始める（負荷試験用。周期の数え方は変えない） */
    internal fun startAutosaveCycle() {
        rotation.startCycle(tracked.filter { it.computer.machine != null })
    }

    /** 自動保存の周期が終わっている（負荷試験用） */
    internal val isAutosaveCycleDone: Boolean
        get() = rotation.isCycleDone

    private fun onServerTick(event: ServerTickEvent.Pre) {
        manager?.tick()
        unloadWaitedNanos = 0
        if (changedLater.isNotEmpty()) {
            val list = changedLater.toList()
            changedLater.clear()
            for (be in list) if (!be.isRemoved) be.setChanged()
        }
        if (manager != null && autosaveEnabled) autosaveTick()
    }

    private fun autosaveTick() {
        autosaveTicks++
        if (autosaveTicks % saveConfig.autosavePeriodTicks == 0L) {
            if (!rotation.isCycleDone) LOGGER.warn("Autosave of computers did not finish within {} ticks", saveConfig.autosavePeriodTicks)
            rotation.startCycle(tracked.filter { it.computer.machine != null })
        }
        if (rotation.isCycleDone) return
        rotation.tick(AUTOSAVE_TARGET)
        Stats.autosaveMaxTickWrites = maxOf(Stats.autosaveMaxTickWrites, rotation.lastTickWrites)
        if (rotation.isCycleDone) {
            Stats.autosaveWritten += rotation.writtenInCycle
            Stats.autosaveSkipped += rotation.skippedInCycle
            Stats.autosaveCycleTicks += rotation.cycleTicks
        }
    }

    private val AUTOSAVE_TARGET = object : AutosaveTarget<CaseBlockEntity> {
        override fun quiesce(machine: CaseBlockEntity): QuiesceHandle? = if (machine.isRemoved) null else machine.computer.machine?.quiesce()

        override fun plannedWrites(machine: CaseBlockEntity): Int = machine.plannedWrites

        override fun writeIfReady(machine: CaseBlockEntity, handle: QuiesceHandle): Int {
            if (handle.readiness != SaveReadiness.READY) return AutosaveTarget.NOT_READY
            val planned = machine.plannedWrites
            val start = System.nanoTime()
            return try {
                val writes = machine.captureAndWrite(CapturePolicy.SKIP_IF_TRANSIENT)
                // 読み込み中のチャンクなので setChanged が効き、チャンクの保存が後で NBT を書く
                if (writes > 0) machine.setChanged()
                writes
            } catch (e: IOException) {
                LOGGER.warn("Failed to save the computer at {} (keeping the previous generation)", machine.blockPos, e)
                planned
            } finally {
                Stats.autosaveWriteNanos += System.nanoTime() - start
            }
        }
    }

    // ---- チャンクのアンロード ----

    private fun onChunkUnload(event: ChunkEvent.Unload) {
        val chunk = event.chunk as? LevelChunk ?: return
        if (chunk.level !is ServerLevel || manager == null) return
        val cases = chunk.blockEntities.values.filterIsInstance<CaseBlockEntity>()
        if (cases.isEmpty()) return
        Stats.unloadEventsWithCases++
        saveForUnload(chunk, cases)
    }

    /**
     * アンロードされる [chunk] の [cases] を取り出して書く。止める → 待つ（tick ごとの予算 [MachineSaveConfig.unloadWaitBudgetMillis]）→
     * 取り出す（`KILL_TRANSIENT`）→ 書く → チャンクに `setUnsaved(true)`（アンロードでは `setChanged` が効かないため）
     */
    private fun saveForUnload(chunk: LevelChunk, cases: List<CaseBlockEntity>) {
        val store = WorldMachineSaves.current ?: return
        val targets = ArrayList<CaseBlockEntity>()
        for (be in cases) {
            if (be.computer.machine == null) continue
            if (be.computer.isCaptured) {
                // 停止の手順で取り出し済み: チャンクの NBT がまだ古い世代を指していれば、保存させる
                val id = be.identity
                if (id != null && store.chunkGeneration(id.id).let { !it.isPresent || it.asLong != id.generation }) chunk.isUnsaved = true
                continue
            }
            // 自動保存で止めていた台は、アンロードの手順が引き継ぐ（止め直してから、自動保存の印を返す）
            be.computer.prepareUnload()
            rotation.remove(be)?.close()
            targets += be
        }
        if (targets.isEmpty()) return
        val budget = TimeUnit.MILLISECONDS.toNanos(saveConfig.unloadWaitBudgetMillis)
        val start = System.nanoTime()
        while (unloadWaitedNanos + (System.nanoTime() - start) < budget && targets.any { it.computer.saveReadiness() == SaveReadiness.TRANSIENT }) {
            LockSupport.parkNanos(WAIT_STEP_NANOS)
        }
        unloadWaitedNanos += System.nanoTime() - start
        for (be in targets) {
            try {
                val writes = be.captureAndWrite(CapturePolicy.KILL_TRANSIENT)
                if (writes > 0) {
                    Stats.unloadCaptures++
                    Stats.unloadWrites += writes
                    chunk.isUnsaved = true
                }
            } catch (e: IOException) {
                LOGGER.warn("Failed to save the computer at {} on chunk unload (keeping the previous generation)", be.blockPos, e)
            }
        }
    }

    // ---- FULL でなくなったチャンク（#545） ----

    /**
     * チケットの段階が変わった。FULL（33 以下）から FULL でない段階に下がったチャンクを、[demoted] に積む。
     * チケットの更新の途中で呼ばれる（`ChunkMap.updateChunkScheduling`。チャンクの状態はまだ下がっていない）ので、ここでは何もしない。
     * 多くのチャンクで頻繁に来るので、段階をまたいだかだけを先に見る
     */
    private fun onChunkTicketLevelUpdated(event: ChunkTicketLevelUpdatedEvent) {
        if (event.oldTicketLevel > FULL_LEVEL || event.newTicketLevel <= FULL_LEVEL) return
        val holder = event.chunkHolder ?: return
        if (manager == null) return
        demoted[holder] = event.level
    }

    private fun onServerTickPost(event: ServerTickEvent.Post) {
        if (demoted.isEmpty() || manager == null) return
        // 1 tick に最低1チャンク。そのあとは tick の時間が残っている間だけ（バニラのアンロードの列と同じ）
        var first = true
        while (demoted.isNotEmpty() && (first || event.hasTime())) {
            val it = demoted.entries.iterator()
            val entry = it.next()
            it.remove()
            if (saveForDemotion(entry.key)) first = false
        }
    }

    /**
     * FULL でなくなった [holder] のチャンクのケースを、アンロードと同じ手順（[saveForUnload]）で取り出して書き、マシンを外す。
     * FULL に戻っていた・チャンクがもうない・動いているケースがなければ何もしない
     *
     * @return 取り出す手順を回した（動いているケースがあった）
     */
    private fun saveForDemotion(holder: ChunkHolder): Boolean {
        if (holder.fullStatus.isOrAfter(FullChunkStatus.FULL)) return false
        val chunk = holder.latestChunk as? LevelChunk ?: return false
        val cases = chunk.blockEntities.values.filterIsInstance<CaseBlockEntity>()
            .filter { !it.isRemoved && it.computer.machine != null }
        if (cases.isEmpty()) return false
        Stats.demotedChunksWithCases++
        saveForUnload(chunk, cases)
        for (be in cases) {
            be.chunkDemoted()
            Stats.demotedMachines++
        }
        return true
    }

    // ---- チャンクの保存（NBT が指す世代の印） ----

    private fun onChunkDataSave(event: ChunkDataEvent.Save) {
        val level = event.level as? ServerLevel ?: return
        markChunkGenerations(level.dimension().location().toString(), event.data)
    }

    /**
     * チャンクの NBT [data]（`ChunkSerializer.write` の結果）の `block_entities` のうち、ケースのタグの世代を記録する。
     * タグの座標（`x`・`y`・`z`）と [dimension] を持ち主の照合に使う（ブロックエンティティのインスタンスは見ない。
     * タグに入った値だけがディスクに行く値）。持ち主でない位置のタグは debug でログに残して無視する
     *
     * @return 印を動かした（[ChunkMarkResult.ADVANCED]・[ChunkMarkResult.MOVED_BACK]・[ChunkMarkResult.UNCHANGED]）タグの数
     */
    internal fun markChunkGenerations(dimension: String, data: CompoundTag): Int {
        val store = WorldMachineSaves.current ?: return 0
        val list = data.getList("block_entities", Tag.TAG_COMPOUND.toInt())
        if (list.isEmpty()) return 0
        val caseId = BlockEntityType.getKey(KiBiBlocks.CASE_BLOCK_ENTITY.get())?.toString() ?: return 0
        var marked = 0
        for (i in 0 until list.size) {
            val tag = list.getCompound(i)
            if (tag.getString("id") != caseId) continue
            if (!tag.contains(CaseBlockEntity.MACHINE_ID_TAG, Tag.TAG_STRING.toInt())) continue
            if (!tag.contains(CaseBlockEntity.SAVE_GENERATION_TAG, Tag.TAG_LONG.toInt())) continue
            if (!tag.contains("x", Tag.TAG_INT.toInt()) || !tag.contains("y", Tag.TAG_INT.toInt()) || !tag.contains("z", Tag.TAG_INT.toInt())) continue
            val id: UUID = CanonicalUuid.parse(tag.getString(CaseBlockEntity.MACHINE_ID_TAG)) ?: continue
            val generation = tag.getLong(CaseBlockEntity.SAVE_GENERATION_TAG)
            if (!MachineIdentityLimits.isValidCounter(generation)) continue
            if (tag.contains(CaseBlockEntity.OWNER_EPOCH_TAG, Tag.TAG_LONG.toInt()) &&
                !MachineIdentityLimits.isValidCounter(tag.getLong(CaseBlockEntity.OWNER_EPOCH_TAG))
            ) {
                continue
            }
            val at = MachineOwner(dimension, tag.getInt("x"), tag.getInt("y"), tag.getInt("z"))
            when (val result = store.markChunkGeneration(id, generation, at)) {
                ChunkMarkResult.NOT_OWNER, ChunkMarkResult.UNKNOWN_ID ->
                    LOGGER.debug("Ignoring the generation {} of machine {} at {} in a chunk save ({})", generation, id, at, result)
                else -> marked++
            }
        }
        return marked
    }

    // ---- サーバーの停止 ----

    private fun onServerStopping(event: ServerStoppingEvent) {
        if (manager != null) saveForStop(tracked.toList(), keepQuiesced = true)
        // 取り出した後に流す（流した分のアンロードは、取り出し済みの台の印を見るだけ）。止める手順が回り続けないように（#540）
        ChunkUnloadDrain.drainAll(event.server)
    }

    /**
     * [targets] を止め、まとめて待ち（上限 [MachineSaveConfig.stopQuiesceMillis]）、全台を `KILL_TRANSIENT` で取り出して書き、
     * `setChanged` を付ける（続く `saveAllChunks(true)` が、強制読み込みのチャンクも含めて NBT を書く）。
     * [keepQuiesced] なら止めたままにする（サーバーの停止。取り出した後に状態が進まないように）。GameTest は `false` で続ける
     *
     * @return 書いた台の数
     */
    internal fun saveForStop(targets: List<CaseBlockEntity>, keepQuiesced: Boolean): Int {
        rotation.clear()
        val running = targets.filter { !it.isRemoved && it.computer.machine != null }
        for (be in running) be.computer.prepareUnload()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(saveConfig.stopQuiesceMillis)
        while (System.nanoTime() < deadline && running.any { it.computer.saveReadiness() == SaveReadiness.TRANSIENT }) {
            LockSupport.parkNanos(WAIT_STEP_NANOS)
        }
        var saved = 0
        for (be in running) {
            try {
                if (be.captureAndWrite(CapturePolicy.KILL_TRANSIENT) > 0) {
                    be.setChanged()
                    saved++
                }
            } catch (e: IOException) {
                LOGGER.warn("Failed to save the computer at {} on server stop (keeping the previous generation)", be.blockPos, e)
            }
        }
        if (!keepQuiesced) for (be in running) be.computer.endQuiesce()
        if (keepQuiesced && running.isNotEmpty()) LOGGER.info("Saved {} of {} running computers", saved, running.size)
        return saved
    }

    private fun onServerStopped(event: ServerStoppedEvent) {
        val m = manager ?: return
        manager = null
        tracked.clear()
        changedLater.clear()
        rotation.clear()
        demoted.clear()
        stop(m)
    }

    private fun stop(m: MachineManager<MachineKey>) {
        val count = m.size
        try {
            if (!m.shutdown(SHUTDOWN_TIMEOUT_MILLIS)) {
                LOGGER.warn("Computer threads did not finish within {} ms", SHUTDOWN_TIMEOUT_MILLIS)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            LOGGER.error("Failed to stop computers", e)
        }
        LOGGER.info("Stopped {} computers", count)
    }

    /** FULL のチャンクのチケットの段階の上限（`ChunkLevel.byStatus(FullChunkStatus.FULL)`） */
    private const val FULL_LEVEL = 33

    /** 待機点を待つ間の1回の待ち */
    private val WAIT_STEP_NANOS = TimeUnit.MICROSECONDS.toNanos(200)
}
