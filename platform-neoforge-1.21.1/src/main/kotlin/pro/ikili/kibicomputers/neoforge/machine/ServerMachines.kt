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
import pro.ikili.kibicomputers.core.persist.CaptureSubmitResult
import pro.ikili.kibicomputers.core.persist.ColdBootCause
import pro.ikili.kibicomputers.core.persist.ColdBootLog
import pro.ikili.kibicomputers.core.persist.ChunkMarkResult
import pro.ikili.kibicomputers.core.persist.FileModuleStore
import pro.ikili.kibicomputers.core.persist.MachineIdentityLimits
import pro.ikili.kibicomputers.core.persist.MachineOwner
import pro.ikili.kibicomputers.core.persist.MachineSaveConfig
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.core.persist.PendingSave
import pro.ikili.kibicomputers.core.persist.QuiesceHandle
import pro.ikili.kibicomputers.core.persist.RestoreOutcome
import pro.ikili.kibicomputers.core.persist.RestoreQueue
import pro.ikili.kibicomputers.core.persist.SaveReadiness
import pro.ikili.kibicomputers.core.persist.WriteResult
import pro.ikili.kibicomputers.core.util.CanonicalUuid
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.util.IdentityHashMap
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
 * - 毎 tick の始めに、書き込みのスレッドで終わった書き込みを受け取り（[processCompletions]）、ケースの NBT の世代を進める（#84）
 * - チャンクのアンロード（[ChunkEvent.Unload]）: そのチャンクのケースを止め、待ち（tick ごとの予算）、取り出して書き込みに回し、
 *   予約した世代を NBT に載せてチャンクに `setUnsaved(true)` を付ける（続く `ChunkMap.save` が書く。書き終わりは待たない。#84 の規則 4）
 * - チャンクが FULL でなくなった（[ChunkTicketLevelUpdatedEvent] でチケットの段階が 33 を超えた。#545）: アンロードと同じく
 *   止め、待ち、取り出して書き込みに回してから、マシンを外す（[CaseBlockEntity.chunkDemoted]）。書き終わったら、そのチャンクに
 *   `setUnsaved(true)` を付ける（#84 の規則 5）。
 *   スポーンのチャンク・強制読み込み・チャンクローダーのまわりのチャンクは、FULL でなくなってもアンロードされずメモリに残るため。
 *   イベントの中では印を付けるだけで、tick の終わり（[ServerTickEvent.Post]）に、tick の時間が残っている間だけ流す（1 tick に最低1チャンク）。
 *   流す前に FULL に戻ったチャンクは何もしない。FULL に戻ったチャンクのケースは、アンロードの後に読み込み直したのと同じく電源が切れている
 * - チャンクの保存（[ChunkDataEvent.Save]）: NBT に入ったケースの世代を、持ち主の位置のタグだけで `markChunkGeneration` に渡す
 * - サーバーの停止（[ServerStoppingEvent]。`saveAllChunks` の前）: 全台を止め、待ち、取り出して書き込みに回し、書き込みのスレッドが
 *   空になるのを待って（[drainSaves]）、書けた台に `setChanged` を付ける（#84 の規則 3）。
 *   止めた台は [ServerStoppedEvent] で捨てるまで止めたまま。そのあと、チャンクのアンロードの列を流しきる（[ChunkUnloadDrain]。#540）
 * - サーバーが止まった後（[ServerStoppedEvent]）に全マシンを止め、スレッドが終わるのを待つ（上限 [SHUTDOWN_TIMEOUT_MILLIS]）
 *
 * 世代のファイルの符号化と書き込みは、書き込みのスレッドで行う（#84、ADR 0084）。メインスレッドは取り出しと予約だけをする。
 * 書き込み待ちの写しの合計が上限（[MachineSaveConfig.writerMaxPendingBytes]）以上なら、自動保存は次の tick に回し、
 * アンロード・停止は台ごとに待つ（期限はチャンク・停止全体で共有）。
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
            WorldMachineSaves.current?.let {
                it.maxPendingBytes = value.writerMaxPendingBytes
                it.claimWaitMillis = value.writerFlushMillis
            }
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

    /** FULL に戻ったチャンク（#85。次の tick の終わりに、電源が入っていたケースを復元の順番待ちに入れる）。メインスレッドだけで使う */
    private val promoted = LinkedHashMap<ChunkHolder, ServerLevel>()

    /** 復元の順番待ち（#85）。メインスレッドだけで使う */
    private val restoreQueue = RestoreQueue<CaseBlockEntity>()

    /** 自動保存の周期を数える tick */
    private var autosaveTicks = 0L

    /** 冷えた起動のログの出し方（MOD のバージョン違いの要約は、サーバーの起動ごとに1回。#88） */
    private var coldBootLog = ColdBootLog()

    /** この tick のアンロードで待機点を待った時間の合計（ナノ秒） */
    private var unloadWaitedNanos = 0L

    /** 数え口（負荷試験・GameTest 用）。メインスレッドだけで読み書きする */
    internal object Stats {
        /** 受けた [ChunkEvent.Unload] のうち、ケースのあったもの */
        var unloadEventsWithCases: Long = 0

        /** 復元（#85）を始めた台のうち、保存した世代から戻そうとした数 */
        var restoresFromSave: Long = 0

        /** 復元を始めた台のうち、最初から冷えた起動にした数（ID がない・新しい ID・書き込みが終わらない） */
        var restoresColdStart: Long = 0

        /** 書き込みが期限までに終わらず、冷えた起動にした数 */
        var restoreWriteTimeouts: Long = 0

        /** 保存した世代から戻せた数 */
        var restored: Long = 0

        /** 戻せずに冷えた起動にした数 */
        var restoreFallbacks: Long = 0

        /** [restoreFallbacks] のうち、保存データを書いた MOD のバージョンが今のものと違ったもの（#88） */
        var restoreVersionMismatches: Long = 0

        /** FULL でなくなったチャンク（[ChunkTicketLevelUpdatedEvent]）のうち、動いているケースのあったもの（#545） */
        var demotedChunksWithCases: Long = 0

        /** FULL でなくなったチャンクで外したマシンの数（#545。取り出して書いた数は [unloadCaptures] に入る） */
        var demotedMachines: Long = 0

        /** アンロード・FULL でなくなったチャンクで取り出して書いた台の数 */
        var unloadCaptures: Long = 0

        /** アンロードで行った書き込み（fsync）の回数 */
        var unloadWrites: Long = 0

        /** 自動保存で書き込みに回した回数（tick ごとの最大） */
        var autosaveMaxTickWrites: Int = 0

        /** 自動保存で書いた台・飛ばした台の数（周期をまたいだ合計） */
        var autosaveWritten: Long = 0
        var autosaveSkipped: Long = 0

        /** 終わった自動保存の周期の、一巡にかかった tick 数（古い順） */
        val autosaveCycleTicks: MutableList<Int> = ArrayList()

        /** 自動保存で、取り出して書き込みに回すのにかかった時間の合計（ナノ秒。メインスレッド。#84 から書き込みは含まない） */
        var autosaveWriteNanos: Long = 0

        /**
         * 保存（自動保存・アンロード・停止。`CaseBlockEntity.captureAndSubmit`）で、取り出し（`captureCopy`。カーネルロックの中。
         * メインスレッド）にかかった時間の合計（ナノ秒。#553）
         */
        var captureNanos: Long = 0

        /**
         * 保存で、符号化（写しを保存データのバイト列にする。区画の圧縮を含む）にかかった時間の合計（ナノ秒。#553）。
         * 書き込みのスレッドで測り、完了を受け取ったときに足す（#84）
         */
        var encodeNanos: Long = 0

        /**
         * 保存で、ファイルの書き込み（tmp → fsync → rename。世代ファイルと、振った `owner.bin`）にかかった時間の合計（ナノ秒。#553）。
         * 書き込みのスレッドで測り、完了を受け取ったときに足す（#84）
         */
        var fileWriteNanos: Long = 0

        /** 書けなかった書き込みの数（#84） */
        var saveFailures: Long = 0

        /** 書き込み待ちの仕事の数・写しの見積もりの合計の最大（予約の直後に見る。#84） */
        var maxPendingCount: Int = 0
        var maxPendingBytes: Long = 0

        /** アンロード・停止で、取り出しの前に書き込み待ちの上限を確かめた台の数（#84） */
        var capacityChecks: Long = 0

        /** そのうち、上限で待った台の数と、待った時間の合計（ナノ秒）、期限を過ぎた回数（#84） */
        var capacityWaits: Long = 0
        var capacityWaitNanos: Long = 0
        var capacityTimeouts: Long = 0

        /** 自動保存で、書き込み待ちの上限のために次の tick に回した回数（#84） */
        var autosaveNoCapacity: Long = 0

        /** 消えたケース（アンロード済み・壊した）への完了の数（#84。数えるだけ） */
        var completionsForGone: Long = 0

        /**
         * 世代のファイルを書くたびに [saves] に1件残すか（負荷試験が有効にする。ふだんは残さない）。
         * 有効な間は、予約を区間（[window]。[reset] で次に進む）ごとに分け、前の区間に予約した完了（と、無効な間に予約した完了）は
         * [saves] や時間・数の数え口に入れず、[staleCompletions] に数える（#614）
         */
        var recordSaves: Boolean = false

        /** 今の区間の番号（[reset] のたびに増える。[recordSaves] のときだけ使う。#614） */
        var window: Long = 0
            private set

        /** 今の区間に予約した書き込みの数（[recordSaves] のときだけ数える。#614） */
        var windowSubmits: Long = 0

        /** 今の区間に予約した書き込みのうち、完了（書けた・書けなかった）を受け取った数（[recordSaves] のときだけ数える。#614） */
        var windowCompletions: Long = 0

        /** 今の区間に予約して、まだ完了を受け取っていない書き込みの数（[recordSaves] のときだけ意味を持つ。#614） */
        val windowOutstanding: Long get() = windowSubmits - windowCompletions

        /** 今の区間より前に予約した書き込みの完了の数（[recordSaves] のときだけ数える。数え口には入れない。#614） */
        var staleCompletions: Long = 0

        /** 世代のファイルを書いた1回ごとの記録（[recordSaves] のときだけ。古い順） */
        val saves: MutableList<SaveSample> = ArrayList()

        /** 世代のファイルを1つ書けた（完了を受け取ったとき。[captureNanos] は予約の前の取り出しの時間） */
        fun recordSave(pos: BlockPos, captureNanos: Long, result: WriteResult) {
            encodeNanos += result.encodeNanos
            fileWriteNanos += result.fileWriteNanos
            if (result.delta) deltaSaves++ else fullSaves++
            if (recordSaves) {
                saves += SaveSample(
                    pos, captureNanos, result.encodeNanos, result.fileWriteNanos, result.fileBytes, result.tmpfsBytes, result.delta,
                )
            }
        }

        /** 書いた全体の世代・差分の世代の数（#83） */
        var fullSaves: Long = 0
        var deltaSaves: Long = 0

        /** 差分の基準が使えず、全体を取り直して書いた回数（#83） */
        var deltaRetries: Long = 0

        fun reset() {
            restoresFromSave = 0
            restoresColdStart = 0
            restoreWriteTimeouts = 0
            restored = 0
            restoreFallbacks = 0
            restoreVersionMismatches = 0
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
            saveFailures = 0
            maxPendingCount = 0
            maxPendingBytes = 0
            capacityChecks = 0
            capacityWaits = 0
            capacityWaitNanos = 0
            capacityTimeouts = 0
            autosaveNoCapacity = 0
            completionsForGone = 0
            saves.clear()
            window++
            windowSubmits = 0
            windowCompletions = 0
            staleCompletions = 0
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
        promoted.clear()
        restoreQueue.clear()
        submitOf.clear()
        coldBootLog = ColdBootLog()
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

    /** 電源が入っていたケースを、復元の順番待ちに入れる（#85。[CaseBlockEntity.onLoad] から） */
    internal fun offerRestore(be: CaseBlockEntity) {
        restoreQueue.offer(be, be.currentIdentity?.id, System.nanoTime())
    }

    /** 復元の順番待ちから外す（アンロード・`setRemoved`・FULL でなくなった） */
    internal fun cancelRestore(be: CaseBlockEntity) {
        restoreQueue.remove(be)
    }

    /** 復元の順番待ちの台数（テスト用） */
    internal val pendingRestores: Int get() = restoreQueue.size

    /** 復元を始めた（[CaseBlockEntity.startRestore]）。数え口に入れる */
    internal fun recordRestoreStart(fromSave: Boolean, writesTimedOut: Boolean) {
        if (fromSave) Stats.restoresFromSave++ else Stats.restoresColdStart++
        if (writesTimedOut) Stats.restoreWriteTimeouts++
    }

    /** 復元が終わった（メインスレッド）。数え口に入れ（[countRestoreFinished]）、冷えた起動にしたら理由をログに残す */
    internal fun recordRestoreFinished(pos: BlockPos, outcome: RestoreOutcome, cause: ColdBootCause?) {
        countRestoreFinished(outcome, cause)
        when (outcome) {
            RestoreOutcome.RESTORED -> {}
            RestoreOutcome.COLD_BOOT -> {
                when (coldBootLog.classify(cause)) {
                    ColdBootLog.Kind.EACH -> LOGGER.info("Could not restore the computer at {} ({}); cold-booting", pos, cause)
                    ColdBootLog.Kind.SUMMARY -> {
                        LOGGER.info(
                            "Computer saves were written by a different version of {}; running computers will cold-boot instead of being restored",
                            KiBiComputers.MOD_ID,
                        )
                        LOGGER.debug("Cold-booting the computer at {} ({})", pos, cause)
                    }
                    ColdBootLog.Kind.QUIET -> LOGGER.debug("Cold-booting the computer at {} ({})", pos, cause)
                }
            }
            RestoreOutcome.CANCELLED -> {}
        }
    }

    /**
     * 復元の終わりを数え口にだけ入れる（ログは出さず、[coldBootLog] の「起動ごとに1回の要約」も使わない）。
     * [recordRestoreFinished] から呼ぶほか、GameTest が数え方だけを確かめるのに使う（#708）
     */
    internal fun countRestoreFinished(outcome: RestoreOutcome, cause: ColdBootCause?) {
        when (outcome) {
            RestoreOutcome.RESTORED -> Stats.restored++
            RestoreOutcome.COLD_BOOT -> {
                Stats.restoreFallbacks++
                if (cause == ColdBootCause.MOD_VERSION) Stats.restoreVersionMismatches++
            }
            RestoreOutcome.CANCELLED -> {}
        }
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
        rotation.startCycle(tracked.filter { it.computer.machine != null && !it.computer.isRestoring })
    }

    /** 自動保存の周期が終わっている（負荷試験用） */
    internal val isAutosaveCycleDone: Boolean
        get() = rotation.isCycleDone

    private fun onServerTick(event: ServerTickEvent.Pre) {
        processCompletions()
        manager?.tick()
        // 復元を始める（#85。tick ごとの台数の上限つき。書き込みの終わっていない台は後回し）
        if (manager != null) {
            restoreQueue.tick(System.nanoTime(), WorldMachineSaves.current, saveConfig) { be, timedOut ->
                if (!be.isRemoved) be.startRestore(timedOut)
            }
        }
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
            rotation.startCycle(tracked.filter { it.computer.machine != null && !it.computer.isRestoring })
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
            // 書き込み待ちの上限（#84）: 台ごとに、取り出しの直前に見る。余地がなければ、この台と後ろの台は次の tick
            val store = WorldMachineSaves.current
            if (store != null && !store.hasCapacity) {
                Stats.autosaveNoCapacity++
                return AutosaveTarget.NO_CAPACITY
            }
            val start = System.nanoTime()
            return try {
                // NBT の世代は、書き終わった完了を受け取ったときに進めて setChanged する（規則 2）
                machine.captureAndSubmit(CapturePolicy.SKIP_IF_TRANSIENT)?.writes ?: 0
            } finally {
                Stats.autosaveWriteNanos += System.nanoTime() - start
            }
        }
    }

    // ---- 書き込みの完了（#84） ----

    /** 予約ごとの取り出しの時間と区間（[Stats.recordSaves] のときだけ。完了で [Stats.saves] に入れる） */
    private val submitOf = IdentityHashMap<PendingSave, SubmitInfo>()

    /** 予約の記録（負荷試験用。[captureNanos] は取り出しの時間、[window] は予約した区間の番号。#614） */
    private class SubmitInfo(val captureNanos: Long, val window: Long)

    /** ケースが書き込みを予約した（[CaseBlockEntity.captureAndSubmit] から）。数え口に入れる */
    internal fun recordSubmit(submitted: CaptureSubmitResult) {
        Stats.captureNanos += submitted.captureNanos
        if (submitted.retried) Stats.deltaRetries++
        if (Stats.recordSaves) {
            submitOf[submitted.pending] = SubmitInfo(submitted.captureNanos, Stats.window)
            Stats.windowSubmits++
        }
        val store = WorldMachineSaves.current ?: return
        Stats.maxPendingCount = maxOf(Stats.maxPendingCount, store.pendingCount)
        Stats.maxPendingBytes = maxOf(Stats.maxPendingBytes, store.pendingBytes)
    }

    /**
     * 書き込みのスレッドで終わった書き込みを受け取り、ケースに返す（メインスレッド。毎 tick の始めと [drainSaves]）。
     * ケースが世代を採ったら（[CaseBlockEntity.onSaveCompleted]）`setChanged` し、FULL でなくなったチャンクなら `setUnsaved(true)` を付ける。
     * 消えたケースへの完了は数えるだけ。[Stats.recordSaves] の間は、今の区間に予約した完了だけを数え口に入れる（#614。
     * ケースへは、区間によらずすべての完了を返す）
     */
    internal fun processCompletions() {
        val store = WorldMachineSaves.current ?: return
        for (completion in store.pollCompleted()) {
            val be = completion.pending.tag as? CaseBlockEntity
            val info = submitOf.remove(completion.pending)
            val result = completion.result
            if (Stats.recordSaves && (info == null || info.window != Stats.window)) {
                Stats.staleCompletions++
            } else {
                if (Stats.recordSaves) Stats.windowCompletions++
                if (result != null) Stats.recordSave(be?.blockPos ?: BlockPos.ZERO, info?.captureNanos ?: 0L, result) else Stats.saveFailures++
            }
            if (be == null) continue
            val adopted = be.onSaveCompleted(completion)
            if (be.isRemoved) {
                Stats.completionsForGone++
                continue
            }
            if (adopted) {
                be.setChanged()
                // FULL でないチャンクでは setChanged が届かない（規則 5）
                be.demotedChunk?.isUnsaved = true
            }
        }
    }

    /**
     * 書き込みのスレッドが空になるまで待ち（上限 [MachineSaveConfig.writerFlushMillis]）、終わった書き込みを受け取る（停止の手順と GameTest）
     *
     * @return 上限までに空になった
     */
    internal fun drainSaves(): Boolean {
        val store = WorldMachineSaves.current ?: return true
        val flushed = try {
            store.flush(saveConfig.writerFlushMillis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!flushed) LOGGER.warn("Computer saves did not finish within {} ms", saveConfig.writerFlushMillis)
        processCompletions()
        return flushed
    }

    /**
     * アンロード・停止で、取り出しの前に書き込み待ちの上限を確かめ、余地がなければ [deadline]（`System.nanoTime` の値。チャンク・停止全体で共有）
     * まで待つ（#84）。期限を過ぎたら warn して `false`（呼び出し側は残りの台を待たずに回す）
     */
    private fun awaitCapacity(store: MachineSaveStore, deadline: Long): Boolean {
        Stats.capacityChecks++
        if (store.hasCapacity) return true
        Stats.capacityWaits++
        val start = System.nanoTime()
        val ok = try {
            store.awaitCapacity(TimeUnit.NANOSECONDS.toMillis((deadline - start).coerceAtLeast(0)))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        Stats.capacityWaitNanos += System.nanoTime() - start
        if (!ok) {
            Stats.capacityTimeouts++
            LOGGER.warn(
                "Pending computer saves stayed over {} bytes for {} ms; queueing the rest without waiting",
                store.maxPendingBytes, saveConfig.writerFlushMillis,
            )
        }
        return ok
    }

    /**
     * [cases] に、待てば保存の判定が READY に近づく台（[pro.ikili.kibicomputers.core.computer.ComputerCase.isSettlingForSave]）があるか。
     * アンロードと停止の待ちは、メインスレッドで待つので、再起動の通知を積んだ後の台（起動し直すまでの待ちの間を含む。#635）は
     * 待っても解けない。待たずに取り出す（`REBOOT_PENDING` の冷えた起動になるのは、待っても変わらない）
     */
    internal fun anySettling(cases: List<CaseBlockEntity>): Boolean = cases.any { it.computer.isSettlingForSave }

    /**
     * 止まった台（CRASHED・ゲストが止めた）を、知らせを待たずに電源の切れた状態にそろえる（#698。
     * [pro.ikili.kibicomputers.core.computer.ComputerCase.settleStoppedMachine]）。アンロード・FULL でなくなった・停止の経路で、
     * 取り出す前と後に呼ぶ（これらの経路では、知らせが届く前に NBT を書き、外した後の知らせは捨てられるため）。
     * CRASHED で外した台の原因（カーネルの不整合の例外）は、外した後は知らせも tick も届かないので、[CaseBlockEntity.settleStopped] がエラーログに残す。
     * [quiet]（アンロード・FULL でなくなった経路）なら、電源の状態が変わっても `setChanged` を呼ばない
     * （隣のブロックの `onNeighborChange` が、アンロード中・FULL でないチャンクを読み直すのを避ける）。呼び手がチャンクに `isUnsaved` を付ける
     *
     * @return 電源の状態を変えた
     */
    private fun settleStopped(be: CaseBlockEntity, quiet: Boolean): Boolean = be.settleStopped(quiet)

    // ---- チャンクのアンロード ----

    private fun onChunkUnload(event: ChunkEvent.Unload) {
        val chunk = event.chunk as? LevelChunk ?: return
        if (chunk.level !is ServerLevel || manager == null) return
        val cases = chunk.blockEntities.values.filterIsInstance<CaseBlockEntity>()
        if (cases.isEmpty()) return
        Stats.unloadEventsWithCases++
        saveForUnload(chunk, cases, adopt = true)
    }

    /**
     * アンロードされる [chunk] の [cases] を取り出して書き込みに回す。止める → 待つ（tick ごとの予算 [MachineSaveConfig.unloadWaitBudgetMillis]）→
     * 台ごとに書き込み待ちの上限を確かめる（期限はチャンクで共有）→ 取り出す（`KILL_TRANSIENT`）→ 書き込みに回す。
     * [adopt]（アンロード）なら、書き込み中の予約の世代を NBT に載せ、チャンクに `setUnsaved(true)` を付ける（アンロードでは `setChanged` が
     * 効かないため。#84 の規則 4）。FULL でなくなっただけなら（[adopt] が偽）、書き終わってから進める（規則 5）
     */
    internal fun saveForUnload(chunk: LevelChunk, cases: List<CaseBlockEntity>, adopt: Boolean) {
        val store = WorldMachineSaves.current ?: return
        // 止まった台は電源を切ってから書く（アンロードでは setChanged が効かないので、変わったらチャンクに印を付ける。#698）
        for (be in cases) if (settleStopped(be, quiet = true)) chunk.isUnsaved = true
        val targets = ArrayList<CaseBlockEntity>()
        for (be in cases) {
            if (be.computer.machine == null) continue
            // 復元の途中の台は取り出さない（NBT は前の世代と電源の状態を指したまま。外すと復元を取り消す。#85）
            if (be.computer.isRestoring) continue
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
        if (targets.isNotEmpty()) {
            val budget = TimeUnit.MILLISECONDS.toNanos(saveConfig.unloadWaitBudgetMillis)
            val start = System.nanoTime()
            while (unloadWaitedNanos + (System.nanoTime() - start) < budget && anySettling(targets)) {
                LockSupport.parkNanos(WAIT_STEP_NANOS)
            }
            unloadWaitedNanos += System.nanoTime() - start
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(saveConfig.writerFlushMillis)
            var waiting = true
            for (be in targets) {
                if (waiting) waiting = awaitCapacity(store, deadline) else Stats.capacityChecks++
                val pending = be.captureAndSubmit(CapturePolicy.KILL_TRANSIENT) ?: continue
                Stats.unloadCaptures++
                Stats.unloadWrites += pending.writes
            }
            // 待ち・取り出しの間に止まった台（別のスレッドで CRASHED になる）も、電源を切った状態で NBT に載せる（#698）
            for (be in targets) if (settleStopped(be, quiet = true)) chunk.isUnsaved = true
        }
        // 書き終わりは待たずに、予約した世代を NBT に載せる（書き込み中だった自動保存の予約も同じ。規則 4）
        if (adopt) for (be in cases) if (be.adoptPendingSave()) chunk.isUnsaved = true
    }

    // ---- FULL でなくなったチャンク（#545） ----

    /**
     * チケットの段階が変わった。FULL（33 以下）から FULL でない段階に下がったチャンクを、[demoted] に積む。
     * チケットの更新の途中で呼ばれる（`ChunkMap.updateChunkScheduling`。チャンクの状態はまだ下がっていない）ので、ここでは何もしない。
     * 多くのチャンクで頻繁に来るので、段階をまたいだかだけを先に見る
     */
    private fun onChunkTicketLevelUpdated(event: ChunkTicketLevelUpdatedEvent) {
        val down = event.oldTicketLevel <= FULL_LEVEL && event.newTicketLevel > FULL_LEVEL
        val up = event.oldTicketLevel > FULL_LEVEL && event.newTicketLevel <= FULL_LEVEL
        if (!down && !up) return
        val holder = event.chunkHolder ?: return
        if (manager == null) return
        if (down) {
            promoted.remove(holder)
            demoted[holder] = event.level
        } else {
            promoted[holder] = event.level
        }
    }

    private fun onServerTickPost(event: ServerTickEvent.Post) {
        if (promoted.isNotEmpty() && manager != null) restorePromoted()
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
        val live = chunk.blockEntities.values.filterIsInstance<CaseBlockEntity>().filter { !it.isRemoved }
        // 復元の順番待ちの台（マシンがまだない）は、取り出すものがないので、順番待ちから外して電源を切った見た目に戻すだけ（#545）。
        // 外さないと順番が来たときに、FULL でないチャンクにマシンを作ってしまう
        for (be in live) if (be.computer.machine == null && be.computer.isRestorePending) be.chunkDemoted(chunk)
        val cases = live.filter { it.computer.machine != null }
        if (cases.isEmpty()) return false
        Stats.demotedChunksWithCases++
        saveForUnload(chunk, cases, adopt = false)
        for (be in cases) {
            be.chunkDemoted(chunk)
            Stats.demotedMachines++
        }
        return true
    }

    /**
     * FULL に戻ったチャンク（#85）の、電源が入っていてマシンのないケースを、復元の順番待ちに入れる（読み込み直したのと同じ）。
     * まだ FULL でない・チャンクがもうないものは何もしない
     */
    private fun restorePromoted() {
        val list = promoted.keys.toList()
        promoted.clear()
        for (holder in list) {
            if (!holder.fullStatus.isOrAfter(FullChunkStatus.FULL)) continue
            val chunk = holder.latestChunk as? LevelChunk ?: continue
            for (be in chunk.blockEntities.values) {
                if (be !is CaseBlockEntity || be.isRemoved) continue
                if (be.computer.beginRestore()) offerRestore(be)
            }
        }
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
     * [targets] を止め、まとめて待ち（上限 [MachineSaveConfig.stopQuiesceMillis]）、全台を `KILL_TRANSIENT` で取り出して書き込みに回し
     * （台ごとに書き込み待ちの上限を確かめる。期限は全体で共有）、書き込みのスレッドが空になるのを待って（[drainSaves]）、
     * 書けた台に `setChanged` を付ける（続く `saveAllChunks(true)` が、強制読み込みのチャンクも含めて NBT を書く。#84 の規則 3）。
     * [keepQuiesced] なら止めたままにする（サーバーの停止。取り出した後に状態が進まないように）。GameTest は `false` で続ける
     *
     * @return 書き込みに回した台の数
     */
    internal fun saveForStop(targets: List<CaseBlockEntity>, keepQuiesced: Boolean): Int {
        rotation.clear()
        // 止まった台（CRASHED・ゲストが止めた）は、知らせがもう届かないので、ここで電源を切る（setChanged で NBT に載る。#698）
        for (be in targets) if (!be.isRemoved) settleStopped(be, quiet = false)
        // 復元の途中の台は取り出さない（NBT は前の世代を指したまま。次に読み込んだときに戻し直す。#85）
        val running = targets.filter { !it.isRemoved && it.computer.machine != null && !it.computer.isRestoring }
        for (be in running) be.computer.prepareUnload()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(saveConfig.stopQuiesceMillis)
        while (System.nanoTime() < deadline && anySettling(running)) {
            LockSupport.parkNanos(WAIT_STEP_NANOS)
        }
        var saved = 0
        val store = WorldMachineSaves.current
        val capacityDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(saveConfig.writerFlushMillis)
        var waiting = true
        for (be in running) {
            if (store != null) {
                if (waiting) waiting = awaitCapacity(store, capacityDeadline) else Stats.capacityChecks++
            }
            if (be.captureAndSubmit(CapturePolicy.KILL_TRANSIENT) != null) saved++
        }
        // 待ち・取り出しの間に止まった台も、電源を切った状態で NBT に載せる（写しは取らないか、取っても電源が切れているので戻されない。#698）
        for (be in running) settleStopped(be, quiet = false)
        // 書き終わってから NBT の世代を進めて setChanged する（規則 3）
        drainSaves()
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
        promoted.clear()
        restoreQueue.clear()
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
