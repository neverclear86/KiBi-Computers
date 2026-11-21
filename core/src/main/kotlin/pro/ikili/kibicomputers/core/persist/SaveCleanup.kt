package pro.ikili.kibicomputers.core.persist

import org.jetbrains.annotations.ApiStatus
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.function.Consumer

/** 「その位置に、その ID のケースがあるか」の答え（#564。[MachineReferenceProbe]） */
@ApiStatus.Internal
public enum class MachineReference {
    /** ある（ディレクトリを残す） */
    PRESENT,

    /** ない（どの NBT からも指されていない。ディレクトリを消してよい） */
    ABSENT,

    /** 判定できない（ディメンションがない・チャンクが読めない・読み込まれている途中など。残す） */
    UNKNOWN,
}

/**
 * ディレクトリの掃除（#564、ADR 0564）が、`owner.bin` の位置のチャンクにその ID のケースがあるかを接続層に問う口。
 * 接続層は、読み込まれていないチャンクならディスクの NBT（書き込み待ちを含む）を、FULL まで読み込まれているチャンクならメモリのブロックエンティティを見て答える
 * （FULL でない段階・アンロード待ちは [MachineReference.UNKNOWN]）。
 */
@ApiStatus.Internal
public fun interface MachineReferenceProbe {
    /** メインスレッドで呼ぶ。答えは、どのスレッドからでも1回だけ [answer] に渡す（呼んだ中で渡してもよい） */
    public fun probe(id: UUID, owner: MachineOwner, answer: Consumer<MachineReference>)
}

/**
 * 掃除のためにディレクトリを調べた結果（書き込みのスレッドで作る。[MachineSaveStore.inspectForCleanup]）。
 *
 * @property exists ディレクトリがある
 * @property owner 読めた `owner.bin`（ない・読めなければ `null`）
 * @property ownerError `owner.bin` が読めなかった理由（読めた・なければ `null`）
 * @property newestMillis ディレクトリとその中のファイルの最新の更新時刻（ミリ秒）
 * @property bytes 中のファイルの大きさの合計
 * @property generations 世代のファイルの数
 * @property error ディレクトリを調べられなかった理由（調べられたら `null`）
 * @property skipped 調べなかった（掃除を止めた・列が閉じていた）
 */
@ApiStatus.Internal
public class CleanupInspection(
    public val id: UUID,
    public val exists: Boolean,
    public val owner: OwnerRecord? = null,
    public val ownerError: String? = null,
    public val newestMillis: Long = 0,
    public val bytes: Long = 0,
    public val generations: Int = 0,
    public val error: String? = null,
    public val skipped: Boolean = false,
) {
    public companion object {
        /** 調べなかった */
        @JvmStatic
        public fun skipped(id: UUID): CleanupInspection = CleanupInspection(id, exists = false, skipped = true)
    }
}

/**
 * 掃除の仮の値（#564。確定は #251）。
 *
 * @property graceMillis ディレクトリの最新の更新からこれだけ経たないものは消さない（ほかの MOD で運ばれている途中の台などを守る）
 * @property maxQueuedTasks 書き込みの列に同時に入れておく掃除の仕事の数の上限（停止・アンロードの書き込みを待たせない）
 * @property maxProbesInFlight 答えを待つ [MachineReferenceProbe] の問いの数の上限（チャンクの読みでプレイヤーの読み込みを詰まらせない）
 * @property safetyMinimum 安全の上限: 消す候補がこれ以上で、かつ調べたディレクトリの [safetyRatio] 以上なら、1つも消さない
 * @property safetyRatio 安全の上限の割合
 * @property stallMillis 列に入れた仕事・問いの結果が1つも返らないまま、これだけ経ったら、掃除を打ち切る（結果が返らない仕事で、掃除が終わらなくならないように）
 */
@ApiStatus.Internal
public data class SaveCleanupSettings @JvmOverloads constructor(
    public val graceMillis: Long = DEFAULT_GRACE_MILLIS,
    public val maxQueuedTasks: Int = 8,
    public val maxProbesInFlight: Int = 8,
    public val safetyMinimum: Int = 32,
    public val safetyRatio: Double = 0.5,
    public val stallMillis: Long = DEFAULT_STALL_MILLIS,
) {
    init {
        require(graceMillis >= 0) { "graceMillis must be >= 0: $graceMillis" }
        require(maxQueuedTasks >= 1) { "maxQueuedTasks must be >= 1: $maxQueuedTasks" }
        require(maxProbesInFlight >= 1) { "maxProbesInFlight must be >= 1: $maxProbesInFlight" }
        require(safetyMinimum >= 1) { "safetyMinimum must be >= 1: $safetyMinimum" }
        require(safetyRatio > 0 && safetyRatio <= 1) { "safetyRatio must be in (0, 1]: $safetyRatio" }
        require(stallMillis >= 1) { "stallMillis must be >= 1: $stallMillis" }
    }

    public companion object {
        /** 猶予の仮の値（7 日） */
        public const val DEFAULT_GRACE_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

        /** 打ち切りまでの時間の仮の値（5 分） */
        public const val DEFAULT_STALL_MILLIS: Long = 5L * 60 * 1000

        @JvmField
        public val DEFAULT: SaveCleanupSettings = SaveCleanupSettings()
    }
}

/** 掃除の結果の数（メインスレッドで数える。[SaveCleanup.report]） */
@ApiStatus.Internal
public class SaveCleanupReport {
    /** 候補のディレクトリ（開いたときの根の、正規の小文字の UUID の名前） */
    public var candidates: Int = 0
        internal set

    /** このサーバーで見た ID なので残した */
    public var seen: Int = 0
        internal set

    /** 猶予の中なので残した */
    public var recent: Int = 0
        internal set

    /** 位置にケースがあったので残した */
    public var present: Int = 0
        internal set

    /** 判定できなかったので残した（`owner.bin` が読めない・調べられない・接続層が判定できない・`owner.bin` がないのに世代がある） */
    public var undetermined: Int = 0
        internal set

    /** 指されていないと判定した（試しの実行では消さずに数えるだけ） */
    public var unreferenced: Int = 0
        internal set

    /** 消したディレクトリ */
    public var deleted: Int = 0
        internal set

    /** 消したディレクトリのファイルの大きさの合計 */
    public var deletedBytes: Long = 0
        internal set

    /** 安全の上限に当たり、1つも消さなかった */
    public var safetyStopped: Boolean = false
        internal set

    /** モジュールの掃除の候補（置き場を作ったときにあったファイル） */
    public var moduleCandidates: Int = 0
        internal set

    /** どれかの世代のファイルが参照していたモジュール（候補のうち） */
    public var modulesReferenced: Int = 0
        internal set

    /** このセッションで使ったので残したモジュール */
    public var modulesTouched: Int = 0
        internal set

    /** 参照されていないと判定したモジュール（試しの実行では数えるだけ） */
    public var modulesUnreferenced: Int = 0
        internal set

    /** 消したモジュール */
    public var modulesDeleted: Int = 0
        internal set

    /** 消したモジュールの大きさの合計 */
    public var moduleBytes: Long = 0
        internal set

    /** 読めない世代のファイルがあったので、モジュールを1つも消さなかった */
    public var modulesAborted: Boolean = false
        internal set

    /** 消した置き場の一時ファイル */
    public var tempsDeleted: Int = 0
        internal set

    /** かかった時間（ミリ秒。終わったときに入る） */
    public var elapsedMillis: Long = 0
        internal set

    override fun toString(): String =
        "candidates=$candidates, seen=$seen, recent=$recent, present=$present, undetermined=$undetermined, unreferenced=$unreferenced, " +
            "deleted=$deleted ($deletedBytes bytes)${if (safetyStopped) ", stopped by the safety limit" else ""}; " +
            "modules: candidates=$moduleCandidates, referenced=$modulesReferenced, used=$modulesTouched, unreferenced=$modulesUnreferenced, " +
            "deleted=$modulesDeleted ($moduleBytes bytes)${if (modulesAborted) ", aborted" else ""}, temps=$tempsDeleted; $elapsedMillis ms"
}

/**
 * 残ったディレクトリ（どの NBT からも指されていない台）とモジュールの置き場の掃除（#564、ADR 0564）を、メインスレッドの tick で少しずつ進める。
 *
 * 1. 調べる: 候補（[MachineSaveStore.cleanupCandidates]）のうち、このサーバーで見ていない ID のディレクトリを、書き込みの列で調べる
 *    （同時に [SaveCleanupSettings.maxQueuedTasks] 件まで）
 * 2. 判定する（メインスレッド）: 見た ID・猶予の中は残す。`owner.bin` がなく世代もないものは消す候補、世代があれば残す（warn）。
 *    `owner.bin` が読めない・調べられないものは残す。読めたものは接続層（[MachineReferenceProbe]）に位置を問い、[MachineReference.ABSENT] だけを消す候補にする
 * 3. 消す: 安全の上限（[SaveCleanupSettings.safetyMinimum]・[SaveCleanupSettings.safetyRatio]）に当たれば、[force] でない限り1つも消さない。
 *    消す直前にメインスレッドで「見た ID」を見直し、[MachineSaveStore.deleteUnreferenced] で列に入れる（仕事の中で更新時刻を調べ直す）
 * 4. モジュールの参照を読む: 置き場があれば、残ったディレクトリ（[MachineSaveStore.referenceDirectories]）の世代のファイルの参照を列で読む。
 *    読めないファイルが1つでもあれば（根の列挙が不完全なときも）、その回はモジュールを消さない
 * 5. モジュールを消す: 作ったときにあったファイルのうち、参照されておらず、このセッションで使っていないものを列の仕事で消す
 *    （[FileModuleStore.deleteIfUnused]）。置き場の古い一時ファイルも消す
 *
 * [dryRun] なら 3. と 5. で数えるだけで、何も消さない（4. は読む）。
 *
 * スレッド: [tick]・[cancel] と、数え口はメインスレッドだけ。書き込みのスレッド・接続層からの結果は、並行の列に入れて [tick] が受け取る
 *
 * @param store マシンの保存データの置き場
 * @param modules モジュールの置き場（なければモジュールは掃除しない）
 * @param probe 位置にケースがあるかを問う口
 * @param settings 仮の値
 * @param dryRun 消さずに数えるだけ
 * @param force 安全の上限を無視する
 * @param clock 今の時刻（ミリ秒。テストが差し替える）
 */
@ApiStatus.Internal
public class SaveCleanup @JvmOverloads constructor(
    private val store: MachineSaveStore,
    private val modules: FileModuleStore?,
    private val probe: MachineReferenceProbe,
    private val settings: SaveCleanupSettings = SaveCleanupSettings.DEFAULT,
    public val dryRun: Boolean = false,
    public val force: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 進み具合 */
    public enum class Phase { INSPECT, PROBE, DELETE, MODULE_REFS, MODULE_DELETE, DONE, CANCELLED }

    /** 今の段階 */
    public var phase: Phase = Phase.INSPECT
        private set

    /** 結果の数 */
    public val report: SaveCleanupReport = SaveCleanupReport()

    /** 終わった（止めたときも） */
    public val finished: Boolean get() = phase == Phase.DONE || phase == Phase.CANCELLED

    @Volatile
    private var cancelled = false
    private val isCancelled: () -> Boolean = { cancelled }

    private val startNanos = System.nanoTime()
    private val events = ConcurrentLinkedQueue<Event>()
    private var inFlight = 0

    /** 結果が最後に返った（または、何も待っていない状態から仕事を入れた）時刻（打ち切りの判定。[SaveCleanupSettings.stallMillis]） */
    private var lastProgress = 0L

    /** [cancel] した時刻 */
    private var cancelledAt = 0L

    private val candidates: List<UUID> = store.cleanupCandidates()
    private var next = 0
    private var now = 0L

    /** 位置を問う ID と、その位置・判定に使った更新時刻 */
    private val toProbe = ArrayList<CleanupInspection>()

    /** 消す候補（ID → 判定に使った最新の更新時刻） */
    private val toDelete = LinkedHashMap<UUID, Long>()
    private var deleteQueue: List<Map.Entry<UUID, Long>> = emptyList()

    private var referenceDirs: List<String> = emptyList()
    private val referenced = HashSet<String>()
    private var moduleQueue: List<String> = emptyList()

    private sealed class Event {
        class Inspected(val result: CleanupInspection) : Event()

        class Probed(val inspection: CleanupInspection, val answer: MachineReference) : Event()

        class Deleted(val id: UUID, val bytes: Long) : Event()

        class References(val hashes: Set<String>?) : Event()

        class ModuleDeleted(val bytes: Long) : Event()

        class TempsDeleted(val count: Int) : Event()
    }

    init {
        report.candidates = candidates.size
    }

    /**
     * 止める（列に入れた調べ・読みの仕事は何もせずに戻る。入れた削除の仕事は走る）。メインスレッドだけが呼ぶ。
     * 止めた後も、入れた削除の仕事の結果を受け取るために、[settling] の間は [tick] を呼ぶ
     */
    public fun cancel() {
        if (finished) return
        cancelled = true
        phase = Phase.CANCELLED
        cancelledAt = clock()
        finish()
    }

    /** 止めた後で、列に入れた仕事・問いの結果がまだ返っていない（結果を受け取るために [tick] を呼び続ける） */
    public val settling: Boolean get() = phase == Phase.CANCELLED && inFlight > 0

    /** 1 tick 分すすめる（メインスレッド）。結果を受け取り、列・問いの余地の分だけ次を入れる */
    public fun tick() {
        if (phase == Phase.CANCELLED) {
            settle()
            return
        }
        if (finished) return
        while (true) {
            val event = events.poll() ?: break
            lastProgress = clock()
            handle(event)
        }
        if (finished) return
        if (inFlight > 0 && clock() - lastProgress > settings.stallMillis) {
            LOG.log(System.Logger.Level.WARNING, "The computer save cleanup got no result for ${settings.stallMillis} ms ($inFlight pending); cancelling it ($phase)")
            cancel()
            return
        }
        when (phase) {
            Phase.INSPECT -> tickInspect()
            Phase.PROBE -> tickProbe()
            Phase.DELETE -> tickDelete()
            Phase.MODULE_REFS -> tickModuleRefs()
            Phase.MODULE_DELETE -> tickModuleDelete()
            Phase.DONE, Phase.CANCELLED -> Unit
        }
    }

    /**
     * 止めた後に返った結果を受け取る。消さなかった削除（-1）は「このサーバーで消した」から外す（外さないと、ディレクトリが残ったまま、
     * claim が読まずに `owner.bin` なしとして扱う）。ほかの結果は捨てる。上限の間に返らなかった仕事は諦める
     */
    private fun settle() {
        while (true) {
            val event = events.poll() ?: break
            inFlight--
            lastProgress = clock()
            if (event is Event.Deleted && event.bytes < 0) store.cleanupDeletionSkipped(event.id)
        }
        if (inFlight > 0 && clock() - maxOf(lastProgress, cancelledAt) > settings.stallMillis) {
            LOG.log(System.Logger.Level.WARNING, "Giving up waiting for $inFlight results of the cancelled computer save cleanup")
            inFlight = 0
        }
    }

    /** 結果を待つ仕事・問いを1つ増やす（何も待っていなかったなら、そこから打ち切りの時間を数える） */
    private fun submitted() {
        if (inFlight == 0) lastProgress = clock()
        inFlight++
    }

    private fun handle(event: Event) {
        inFlight--
        when (event) {
            is Event.Inspected -> judge(event.result)
            is Event.Probed -> when (event.answer) {
                MachineReference.PRESENT -> report.present++
                MachineReference.UNKNOWN -> {
                    LOG.log(System.Logger.Level.DEBUG) { "Keeping the directory of ${event.inspection.id}: its position cannot be checked" }
                    report.undetermined++
                }
                MachineReference.ABSENT -> unreferenced(event.inspection.id, event.inspection.newestMillis)
            }
            is Event.Deleted -> if (event.bytes >= 0) {
                report.deleted++
                report.deletedBytes += event.bytes
                LOG.log(System.Logger.Level.DEBUG) { "Deleted the unreferenced directory of ${event.id} (${event.bytes} bytes)" }
            } else {
                store.cleanupDeletionSkipped(event.id)
            }
            is Event.References -> if (event.hashes == null) report.modulesAborted = true else referenced += event.hashes
            is Event.ModuleDeleted -> if (event.bytes >= 0) {
                report.modulesDeleted++
                report.moduleBytes += event.bytes
            }
            is Event.TempsDeleted -> report.tempsDeleted += event.count
        }
    }

    // ---- 1. 調べる ----

    private fun tickInspect() {
        if (now == 0L) now = clock()
        while (inFlight < settings.maxQueuedTasks && next < candidates.size) {
            val id = candidates[next++]
            if (store.seenThisSession(id)) {
                report.seen++
                continue
            }
            submitted()
            store.inspectForCleanup(id, isCancelled) { events += Event.Inspected(it) }
        }
        if (next >= candidates.size && inFlight == 0) {
            next = 0
            phase = Phase.PROBE
            tickProbe()
        }
    }

    // ---- 2. 判定する ----

    private fun judge(result: CleanupInspection) {
        val id = result.id
        when {
            result.skipped -> report.undetermined++
            !result.exists -> Unit
            store.seenThisSession(id) -> report.seen++
            result.error != null -> {
                LOG.log(System.Logger.Level.DEBUG) { "Keeping the directory of $id: it cannot be inspected (${result.error})" }
                report.undetermined++
            }
            result.newestMillis > now - settings.graceMillis -> report.recent++
            result.ownerError != null -> {
                LOG.log(System.Logger.Level.DEBUG) { "Keeping the directory of $id: its owner.bin is unreadable (${result.ownerError})" }
                report.undetermined++
            }
            result.owner == null && result.generations > 0 -> {
                // 書いた世代の前に owner.bin を書くので、ふつうはできない（外からの書き換え）。残す
                LOG.log(System.Logger.Level.WARNING, "Keeping the directory of $id: it has ${result.generations} generations but no owner.bin")
                report.undetermined++
            }
            // owner.bin も世代もない（作りかけ・7b の失敗）: 失うものはない。NBT が指していても claim が KEPT で作り直す
            result.owner == null -> unreferenced(id, result.newestMillis)
            else -> toProbe += result
        }
    }

    private fun unreferenced(id: UUID, newestMillis: Long) {
        report.unreferenced++
        toDelete[id] = newestMillis
    }

    private fun tickProbe() {
        while (inFlight < settings.maxProbesInFlight && next < toProbe.size) {
            val inspection = toProbe[next++]
            if (store.seenThisSession(inspection.id)) {
                report.seen++
                continue
            }
            submitted()
            val answered = java.util.concurrent.atomic.AtomicBoolean()
            try {
                probe.probe(inspection.id, inspection.owner!!.owner) { answer ->
                    if (answered.compareAndSet(false, true)) events += Event.Probed(inspection, answer)
                }
            } catch (e: RuntimeException) {
                LOG.log(System.Logger.Level.WARNING, "Failed to check the position of machine ${inspection.id}", e)
                if (answered.compareAndSet(false, true)) events += Event.Probed(inspection, MachineReference.UNKNOWN)
            }
        }
        if (next >= toProbe.size && inFlight == 0) startDelete()
    }

    // ---- 3. 消す ----

    private fun startDelete() {
        next = 0
        phase = Phase.DELETE
        val count = toDelete.size
        val inspected = report.candidates - report.seen
        if (!force && count >= settings.safetyMinimum && count >= inspected * settings.safetyRatio) {
            LOG.log(
                System.Logger.Level.WARNING,
                "The computer save cleanup found $count unreferenced directories out of $inspected; deleting none of them as a safety measure " +
                    "(run the cleanup command with force to delete them)",
            )
            report.safetyStopped = true
            toDelete.clear()
        }
        deleteQueue = if (dryRun) emptyList() else toDelete.entries.toList()
        tickDelete()
    }

    private fun tickDelete() {
        while (inFlight < settings.maxQueuedTasks && next < deleteQueue.size) {
            val (id, newest) = deleteQueue[next++]
            submitted()
            val queued = store.deleteUnreferenced(id, newest) { bytes -> events += Event.Deleted(id, bytes) }
            if (!queued) {
                inFlight--
                report.seen++
                report.unreferenced--
            }
        }
        if (next >= deleteQueue.size && inFlight == 0) startModuleRefs()
    }

    // ---- 4. モジュールの参照を読む ----

    private fun startModuleRefs() {
        next = 0
        if (modules == null) {
            done()
            return
        }
        phase = Phase.MODULE_REFS
        if (!store.scanComplete) {
            // 根の一覧が不完全（読めた分だけで始めた）。漏れたディレクトリの世代が参照するモジュールを「参照なし」にしてしまうので、消さない
            LOG.log(System.Logger.Level.WARNING, "The machine directories could not be listed completely; the module cleanup keeps all modules")
            report.modulesAborted = true
            referenceDirs = emptyList()
            tickModuleRefs()
            return
        }
        referenceDirs = store.referenceDirectories()
        tickModuleRefs()
    }

    private fun tickModuleRefs() {
        while (!report.modulesAborted && inFlight < settings.maxQueuedTasks && next < referenceDirs.size) {
            val name = referenceDirs[next++]
            submitted()
            store.readModuleReferences(name, isCancelled) { events += Event.References(it) }
        }
        if ((report.modulesAborted || next >= referenceDirs.size) && inFlight == 0) startModuleDelete()
    }

    // ---- 5. モジュールを消す ----

    private fun startModuleDelete() {
        val m = modules ?: return done()
        next = 0
        phase = Phase.MODULE_DELETE
        if (report.modulesAborted) {
            LOG.log(System.Logger.Level.INFO, "Skipping the module cleanup: some generations could not be read or listed")
            moduleQueue = emptyList()
            done()
            return
        }
        val seeds = m.sweepCandidates()
        report.moduleCandidates = seeds.size
        val queue = ArrayList<String>()
        for (hex in seeds) {
            when {
                hex in referenced -> report.modulesReferenced++
                m.isTouched(hex) -> report.modulesTouched++
                else -> {
                    report.modulesUnreferenced++
                    queue += hex
                }
            }
        }
        moduleQueue = if (dryRun) emptyList() else queue
        if (!dryRun) {
            submitted()
            if (!store.runOnWriter { events += Event.TempsDeleted(guarded(0) { if (cancelled) 0 else m.deleteStaleTemps() }) }) inFlight--
        }
        tickModuleDelete()
    }

    private fun tickModuleDelete() {
        val m = modules ?: return done()
        while (inFlight < settings.maxQueuedTasks && next < moduleQueue.size) {
            val hex = moduleQueue[next++]
            submitted()
            if (!store.runOnWriter { events += Event.ModuleDeleted(guarded(-1) { if (cancelled) -1 else m.deleteIfUnused(hex) }) }) inFlight--
        }
        if (next >= moduleQueue.size && inFlight == 0) done()
    }

    /** 書き込みのスレッドの仕事 [block] が例外で抜けても、結果（[fallback]）を返す（結果が返らないと掃除が終わらない） */
    private fun <T> guarded(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: RuntimeException) {
        LOG.log(System.Logger.Level.WARNING, "A computer save cleanup task failed", e)
        fallback
    }

    private fun done() {
        phase = Phase.DONE
        finish()
    }

    private fun finish() {
        report.elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000
        val what = when {
            phase == Phase.CANCELLED -> "was cancelled"
            dryRun -> "finished (dry run)"
            else -> "finished"
        }
        LOG.log(System.Logger.Level.INFO, "The computer save cleanup $what: $report")
    }

    private companion object {
        val LOG: System.Logger = System.getLogger("kibicomputers.persist")
    }
}
