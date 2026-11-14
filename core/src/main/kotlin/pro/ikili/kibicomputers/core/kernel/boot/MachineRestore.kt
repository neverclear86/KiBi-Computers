package pro.ikili.kibicomputers.core.kernel.boot

import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineLimits
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ModuleSource
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.ProcessTable
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.ProgramLoadBusyException
import pro.ikili.kibicomputers.core.kernel.process.SnapshotCapableDriver
import pro.ikili.kibicomputers.core.kernel.vfs.DevFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.DeviceDirectory
import pro.ikili.kibicomputers.core.kernel.vfs.DiskFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.FileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.OpenFile
import pro.ikili.kibicomputers.core.kernel.vfs.TmpFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.persist.ColdBootCause
import pro.ikili.kibicomputers.core.persist.RestoreRequest
import pro.ikili.kibicomputers.core.persist.SnapshotFormatException
import pro.ikili.kibicomputers.core.persist.SnapshotSection
import pro.ikili.kibicomputers.core.persist.StoredSnapshot
import pro.ikili.kibicomputers.core.persist.snapshot.DecodedSnapshot
import pro.ikili.kibicomputers.core.persist.snapshot.P1SectionCodec
import pro.ikili.kibicomputers.core.persist.snapshot.SnapshotDecoder
import pro.ikili.kibicomputers.core.runtime.Instrumentation
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmLinkException
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 復元を冷えた起動に切り替える（前段の失敗。カーネルの表は空のまま） */
internal class RestoreFallbackException(val cause0: ColdBootCause, message: String, cause: Throwable? = null) : Exception(message, cause)

/** 復元が取り消された（前段の途中） */
internal class RestoreCancelledException : RuntimeException("復元を取り消した")

/**
 * 前段で組み立てた、戻すものすべて（#85、ADR 0085）。後段（`Machine.completeRestore`）はこれを表に入れるだけ。
 * 入れなかったとき（取り消し・後段に入る前の失敗）は [discard] で返す
 *
 * @property decoded 復号した保存データ
 * @property runtimes 終わっていないプロセスの実行時の部分と、作り直したインスタンス（`CREATED` は `null`）
 * @property fileSystems マウントの ID → 開き直したファイルシステム
 * @property droppedDisks 前段で開けずに表から落としたマウントの [識別子, 保存したときの位置]（#56。戻せたら `disk-removed` で知らせる）
 * @property droppedMountIds 表から落としたマウントの ID（後段が表に入れない）
 * @property claims 開き直したディスクの段の予約（#56）。後段が戻せたら `completeRestore` が外し、戻さなかったら呼び手が外す
 */
internal class PreparedRestore(
    val decoded: DecodedSnapshot,
    val runtimes: List<Pair<ProcessRuntime, WasmInstance?>>,
    val fileSystems: Map<Int, FileSystem>,
    val droppedDisks: List<Pair<String, String>> = emptyList(),
    val droppedMountIds: Set<Int> = emptySet(),
    val claims: List<Machine.DiskClaim> = emptyList(),
) {
    /**
     * 作ったものを返す（インスタンスを閉じ、ファイルシステムを閉じ、`/tmp` の RAM を返す）。カーネルロックの外で呼ぶ。
     * 後段の途中で失敗したときは、表に入れ終えて [Machine.stopLocked] が閉じたものにも重ねて呼ぶ（インスタンスの `close`・
     * [FileSystem.retire]・[TmpFileSystem.onUnmounted] は2回目に何もしない。#576）
     */
    fun discard() {
        for ((_, instance) in runtimes) closeQuietly(instance)
        for (fs in fileSystems.values) retireQuietly(fs)
    }

    companion object {
        fun closeQuietly(instance: WasmInstance?) {
            try {
                instance?.close()
            } catch (_: Throwable) {
            }
        }

        /** 開いたファイルシステムを閉じる（`/tmp` は中身の RAM を返す） */
        fun retireQuietly(fs: FileSystem) {
            try {
                if (fs is TmpFileSystem) fs.onUnmounted() else fs.retire()
            } catch (_: Throwable) {
            }
        }
    }
}

/**
 * 復元の手順（#85、ADR 0085）。[Machine.restore] が受け付けた後、起動用のスレッド（[BootSequence] と同じ `bootExecutor`）で段ごとに動く。
 * モジュールの読み込みを待つ間は、起動用のスレッドを持たない（#577）。読み込みが終わったら、続きを起動用のスレッドに積み直す。
 *
 * 前段（カーネルを変えない）:
 * 1. 戻す世代を決め（[pro.ikili.kibicomputers.core.persist.MachineSaveStore.restorableGeneration]）、外枠の頭だけを読んで
 *    MOD のバージョンを今のものと比べ（違えば中身を解釈せずに `MOD_VERSION`。#88、ADR 0088）、全体を読み（差分なら基準も、
 *    同じように版を比べてから）、[SnapshotDecoder.moduleSources] で p1 の層が参照するモジュールだけを読む（区画は展開しない）。
 * 2. 別々のモジュールを順に1つずつ、置き場の元のバイト列から同じ読み込み方で読み込む（ProgramCache でサーバー全体と共有）。
 *    読み込みが終わっていなければ、終わったときに続きを積み直して、スレッドを返す。混んでいたら（[ProgramLoadBusyException]）、
 *    少し後にタイマーで積み直す。待つ間に持つのは、読んだままの（圧縮のままの）写しと、読み込んだ [Program] だけ。
 * 3. [SnapshotDecoder] で復号・検査し（`SnapshotInspection` と同じ読み手）、プロセスごとに入口と状態の組み合わせを確かめ、
 *    ハッシュを照らし、マウントを開き直し（`/tmp` は `TMPFS` 節から）、開いたファイルの node をディスクに登録し（I/O なし）、
 *    インスタンスを作り直す。
 *
 * どこかで失敗したら、作ったものをすべて返して冷えた起動（`Machine.restoreFellBack`）にする。モジュールを読み込めなかったこと
 * （置き場にない・読み込めない）は、3. でそのモジュールを使う終わっていないプロセスに当たったときに理由にする（読み込みを
 * 復号より先にしても、冷えた起動の理由の優先は変えない）。
 *
 * 後段（`Machine.completeRestore`。カーネルロックの中）: 表に入れて、実行時の部分（待ちの登録・燃料待ち・CPU の待ち行列）を作る。
 *
 * 取り消し（`stop()`・スケジューラの停止）は、待っている読み込みを取り消し（始まっていなければ読み込まない。#363）、
 * 積み直した続きの頭で黙って抜ける。段から段へは、起動用のスレッドへの積み直しか読み込みの完了で渡すので、欄は一度に1つのスレッドだけが触る
 */
internal class MachineRestore(private val machine: Machine, private val request: RestoreRequest) {
    /** 今挿さっているディスクを開く口（開くたびに今の表を引く。#56） */
    private val opener: BootVolumeOpener
        get() = machine.openerForCurrentDisks()

    /** 読んだ写し（1. で読み、3. で復号する） */
    private var stored: StoredSnapshot? = null

    /** 差分の基準 */
    private var base: StoredSnapshot? = null

    /** 読み込む別々のモジュール（参照の順） */
    private var sources: List<ModuleSource> = emptyList()

    /** [sources] のうち、次に読み込むもの */
    private var next = 0

    /** 読み込んだモジュール（[moduleKey] → [Program]。キャッシュから外れても消えないように強く持つ） */
    private val programs = HashMap<String, Program>()

    /** 読み込めなかったモジュール（[moduleKey] → 冷えた起動の理由）。使うプロセスがあれば 3. で冷えた起動にする */
    private val failures = HashMap<String, RestoreFallbackException>()

    /** 今のモジュールが最初に混んでいた時刻（[System.nanoTime]）。混んでいなければ `null` */
    private var busySince: Long? = null

    /**
     * 終わるのを待っている読み込み（取り消しの登録が見る）。登録はこの入れ物だけを持ち、[MachineRestore] を捕まえない（#587）:
     * 信号を終え損ねても、[programs]・[stored]・[base] は回収される。入れ物は読み込みの完了（`loaded`）で空にするが、
     * 完了の後の積み直しが断られたとき（スケジューラが閉じた後）は、終わった読み込み（その [Program]）を持ったまま残る。
     * そのときは `close()` が呼ぶ `stop()`（`stopLocked`）が信号を終えるので、登録ごと外れる
     */
    private val waiting = AtomicReference<CompletableFuture<Program>?>()

    /** 最初の段（起動用のスレッドで1回呼ぶ） */
    fun run() {
        // 取り消しの登録は復元1回につき1つ（stopLocked の中、カーネルロックの中から呼ばれる。読み込みを取り消すだけ）。
        // 信号は前段の終わり（completeRestore・restoreFellBack）でも終えるので、登録はそこで外れる（#587）
        val holder = waiting
        machine.restoreCancelSignal.whenComplete { _, _ -> holder.get()?.cancel(false) }
        step { begin() }
    }

    /**
     * 段 [body] を動かす（起動用のスレッドで）。取り消しなら黙って抜け、前段の失敗なら冷えた起動にする。
     * [body] が前段を終えたら（[PreparedRestore] を返したら）、後段に入る。`null` なら続きは積み直してある
     */
    private fun step(body: () -> PreparedRestore?) {
        val prepared = try {
            body() ?: return
        } catch (e: RestoreCancelledException) {
            return
        } catch (e: RestoreFallbackException) {
            // MOD のバージョン違いは更新直後に全台が当たるので、1台ごとは DEBUG（要約は接続層が1回出す。ADR 0088）
            val level = if (e.cause0 == ColdBootCause.MOD_VERSION) System.Logger.Level.DEBUG else System.Logger.Level.WARNING
            LOG.log(level, "マシン ${request.source.id} を戻せないので冷えた起動にする（${e.cause0}）: ${e.message}")
            machine.restoreFellBack(e.cause0)
            return
        } catch (t: Throwable) {
            LOG.log(System.Logger.Level.WARNING, "マシン ${request.source.id} を戻す途中で例外。冷えた起動にする: $t")
            machine.restoreFellBack(ColdBootCause.RESTORE_FAILED)
            if (t is VirtualMachineError && t !is StackOverflowError) throw t
            return
        }
        try {
            machine.scheduler.hooks.beforeRestoreInstall?.invoke(machine)
            // 取り消し・後段の途中の失敗（CRASHED）では、持ち主がカーネルに移らないので、ロックの外で返す（#576）。段の予約も外す（#56）
            if (!machine.completeRestore(prepared)) {
                prepared.discard()
                machine.releaseDiskClaims(prepared.claims)
            }
        } catch (t: Throwable) {
            // 後段に入る前の失敗（後段の中の失敗は completeRestore が CRASHED にして印を下ろすので、restoreFellBack は何もしない）
            prepared.discard()
            machine.releaseDiskClaims(prepared.claims)
            machine.restoreFellBack(ColdBootCause.RESTORE_FAILED)
            if (t is VirtualMachineError && t !is StackOverflowError) throw t
        }
    }

    /**
     * 続きの段 [body] を起動用のスレッドに積む。読み込み用・タイマーのスレッドから呼ぶので、積む以外のことはしない（#577）。
     * 受け付けられなければ（スケジューラが閉じた後。閉じるときは先に全台を止めるので、この復元は取り消し済み）何もしない
     */
    private fun resume(body: () -> PreparedRestore?) {
        try {
            machine.scheduler.bootExecutor.execute { step(body) }
        } catch (e: RejectedExecutionException) {
            // 取り消しと同じ（知らせは stopLocked が積んだ）
        }
    }

    private fun checkCancelled() {
        if (machine.isRestoreCancelled) throw RestoreCancelledException()
    }

    private fun fallback(cause: ColdBootCause, message: String, t: Throwable? = null): Nothing = throw RestoreFallbackException(cause, message, t)

    /** 1.: 世代を決め、MOD のバージョンを比べてから読み、読み込むモジュールを知る */
    private fun begin(): PreparedRestore? {
        checkCancelled()
        val source = request.source
        val store = source.store
        val id = source.id
        val generation = store.restorableGeneration(id, source.generation)
        if (!generation.isPresent) fallback(ColdBootCause.NO_GENERATION, "世代 ${source.generation} 以下のファイルがない")
        val gen = generation.asLong
        if (gen != source.generation) {
            // NBT が欠番（失敗した予約）を指すのは正常（ADR 0084 の規則 9）
            LOG.log(System.Logger.Level.WARNING, "マシン $id は NBT の世代 ${source.generation} がないので、世代 $gen に戻す")
        }
        // MOD のバージョンは、ファイルを読む前に、外枠の頭だけを読んで比べる（#88、ADR 0088）。
        // 版の違うファイルは、全体の読み込み・CRC・節の解釈（KILLED 節・基準）をしない
        checkModVersion(peekModVersion(id, gen), "世代 $gen")
        val stored = read(id, gen)
        val snapshot = stored.snapshot
        if (snapshot.requiresColdBoot) {
            val cause = if (snapshot.coldBootReason == null) ColdBootCause.UNREADABLE else ColdBootCause.KILLED_SECTION
            fallback(cause, "冷えた起動の写し（理由 ${snapshot.coldBootCode}）")
        }
        base = snapshot.deltaBase?.let { ref ->
            checkModVersion(peekModVersion(ref.id, ref.generation), "差分の基準 ${ref.generation}")
            read(ref.id, ref.generation)
        }
        this.stored = stored
        val referenced = try {
            SnapshotDecoder.moduleSources(snapshot, machine.config.maxProcesses)
        } catch (e: SnapshotFormatException) {
            fallback(ColdBootCause.UNREADABLE, "世代 $gen を読めない: ${e.message}", e)
        }
        val seen = HashSet<String>()
        sources = referenced.filter { seen.add(moduleKey(it)) }
        return loadModules()
    }

    /**
     * 2.: 残りのモジュールを順に1つずつ読み込む（並べて頼むと、自分の読み込みで待ち行列の上限に当たる。#363）。
     * 読み込みを待つ・混んでいてやり直すなら、続きを積む手配をして `null` を返す（スレッドを返す）。全部終えたら 3. に進む
     */
    private fun loadModules(): PreparedRestore? {
        while (next < sources.size) {
            checkCancelled()
            val source = sources[next]
            val future = startLoad(source) ?: continue
            if (!future.isDone) {
                waiting.set(future)
                // 登録より先に取り消されていたら、登録の側は waiting を見ていない: ここで取り消す（stopLocked は印を立ててから知らせる）
                if (machine.isRestoreCancelled) future.cancel(false)
                future.whenComplete { _, _ -> resume { if (loaded(source, future)) loadModules() else null } }
                return null
            }
            if (!loaded(source, future)) return null
        }
        return prepare()
    }

    /**
     * 保存データを書いた MOD のバージョン [saved] が今のもの（置き場の [pro.ikili.kibicomputers.core.persist.MachineSaveStore.modVersion]）
     * と違えば冷えた起動（#88、ADR 0088）。文字列の完全一致で比べる
     */
    private fun checkModVersion(saved: String, what: String) {
        val current = request.source.store.modVersion
        if (saved != current) fallback(ColdBootCause.MOD_VERSION, "$what を書いた MOD のバージョン $saved と今の $current が違う")
    }

    /**
     * [source] の読み込みを頼む。置き場にない・変換器の版が今のものと違うなら、失敗を覚えて次に進み、`null`
     * （使う終わっていないプロセスがあるときだけ 3. で理由にする。ADR 0085・0088）
     */
    private fun startLoad(source: ModuleSource): CompletableFuture<Program>? {
        if (source.metered && source.instrumentationVersion != Instrumentation.VERSION) {
            failures[moduleKey(source)] = RestoreFallbackException(
                ColdBootCause.MODULE_MISMATCH,
                "モジュール ${source.originalHex} の変換器の版 ${source.instrumentationVersion} と今の ${Instrumentation.VERSION} が違う",
            )
            next++
            busySince = null
            return null
        }
        val bytes = machine.scheduler.moduleStore.get(source.originalHash)
        if (bytes == null) {
            failures[moduleKey(source)] = RestoreFallbackException(ColdBootCause.MODULE_MISSING, "モジュール ${source.originalHex} が置き場にない")
            next++
            busySince = null
            return null
        }
        val runtime = machine.scheduler.options.runtime
        val limits = machine.scheduler.options.moduleLimits
        val waitImports = source.waitImports.toSet()
        return machine.scheduler.programs.load(machine.id, bytes, source.metered, waitImports) { b ->
            if (source.metered) Program.loadMetered(runtime, b, limits, waitImports) else Program.load(runtime, b, limits)
        }
    }

    /**
     * 終わった読み込み [future]（[source] のもの）を受け取る（起動用のスレッドで）。次に進めるなら `true`。
     * 混んでいたら、上限の内ならタイマーでやり直しを積んで `false`、上限を過ぎたら冷えた起動
     */
    private fun loaded(source: ModuleSource, future: CompletableFuture<Program>): Boolean {
        waiting.set(null)
        checkCancelled()
        val key = moduleKey(source)
        val program = try {
            future.get()
        } catch (e: CancellationException) {
            // 読み込みを取り消すのは、復元の取り消しの登録（run）だけ（スケジューラの停止は失敗で終わらせるが、上の checkCancelled で抜ける）
            throw RestoreCancelledException()
        } catch (e: ExecutionException) {
            when (val cause = e.cause ?: e) {
                is ProgramLoadBusyException -> {
                    // 読み込みの待ち行列が上限（#363）: 少し後にやり直す（冷えた起動には落とさない）。タイマーのスレッドでは積むだけ
                    val now = System.nanoTime()
                    val since = busySince ?: now.also { busySince = it }
                    if (now - since > TimeUnit.MILLISECONDS.toNanos(BUSY_RETRY_MILLIS)) {
                        fallback(ColdBootCause.RESTORE_FAILED, "モジュールの読み込みが混んでいる: ${cause.message}", cause)
                    }
                    machine.scheduler.timerService.schedule(TimeUnit.MILLISECONDS.toNanos(BUSY_SLEEP_MILLIS)) { resume { loadModules() } }
                    return false
                }
                is WasmLoadException -> {
                    failures[key] = RestoreFallbackException(
                        ColdBootCause.MODULE_MISMATCH,
                        "モジュール ${source.originalHex} を読み込めない: ${cause.message}",
                        cause,
                    )
                    null
                }
                else -> throw cause
            }
        }
        if (program != null) programs[key] = program
        next++
        busySince = null
        return true
    }

    /** 3.: 復号して確かめ、マウントとインスタンスを作り直す。失敗したら作ったものを返してから投げる */
    private fun prepare(): PreparedRestore {
        checkCancelled()
        val stored = checkNotNull(stored)
        val gen = stored.snapshot.generation
        val config = machine.config
        val decoded = try {
            SnapshotDecoder.decode(stored.snapshot, config.maxProcesses, config.memoryPages, base)
        } catch (e: SnapshotFormatException) {
            fallback(ColdBootCause.UNREADABLE, "世代 $gen を読めない: ${e.message}", e)
        }
        // 復号した後は、読んだ写しを持たない
        this.stored = null
        base = null
        validate(decoded)
        checkCancelled()

        val byPid = HashMap<Int, Program>()
        for (p in decoded.kernel.processes.all()) {
            if (p.state.isTerminal) continue
            val layer = decoded.p1.getValue(p.pid)
            val key = moduleKey(layer.moduleSource)
            failures[key]?.let { throw it }
            // 先読みは同じ節から読んだので、ないのは写しが不正なときだけ
            val program = programs[key] ?: fallback(ColdBootCause.UNREADABLE, "pid ${p.pid} のモジュールの参照が先読みと合わない")
            if (program.hash != layer.loadedHash || program.hash != p.moduleHash) {
                fallback(ColdBootCause.MODULE_MISMATCH, "pid ${p.pid} のモジュールを読み込み直したハッシュが合わない")
            }
            byPid[p.pid] = program
        }
        checkCancelled()

        val fileSystems = LinkedHashMap<Int, FileSystem>()
        val runtimes = ArrayList<Pair<ProcessRuntime, WasmInstance?>>()
        val mounts = OpenedMounts()
        try {
            openMounts(decoded, fileSystems, mounts)
            adoptOpenFiles(decoded, fileSystems)
            checkCancelled()
            for (p in decoded.kernel.processes.all().sortedBy { it.pid }) {
                if (p.state.isTerminal) continue
                val program = byPid.getValue(p.pid)
                val driver = try {
                    KernelDrivers.DEFAULT.create(program)
                } catch (e: FsException) {
                    fallback(ColdBootCause.UNSUPPORTED_PROCESS, "pid ${p.pid} を動かせない: ${e.message}", e)
                }
                if (driver !is SnapshotCapableDriver || driver.kind != ProcessKind.WAIT_POINT) {
                    fallback(ColdBootCause.UNSUPPORTED_PROCESS, "pid ${p.pid} のドライバーは戻せない（${driver.kind}）")
                }
                val layer = decoded.p1.getValue(p.pid)
                val rt = ProcessRuntime(machine, p, program, driver)
                machine.runner.initRuntime(rt)
                p.asyncWait = layer.asyncWait
                val state = layer.instance
                val instance = if (state == null) {
                    null
                } else {
                    try {
                        machine.runner.instantiateForRestore(rt, state, layer.asyncStack)
                    } catch (e: WasmLinkException) {
                        // RAM の不足（区画・テーブル）は、プロセスの勘定が確保を断ったかで見分ける（#576）。作り直す間は WASM のコードが
                        // 動かないので、断られたら必ず失敗し、断られずに失敗したら RAM 以外（状態が合わない・start 関数・リンクできない）
                        fallback(restoreInstanceFailureCause(rt), "pid ${p.pid} のインスタンスを作り直せない: ${e.message}", e)
                    } catch (e: WasmLoadException) {
                        fallback(ColdBootCause.RESTORE_FAILED, "pid ${p.pid} のインスタンスを作り直せない: ${e.message}", e)
                    }
                }
                runtimes += rt to instance
                checkCancelled()
            }
        } catch (t: Throwable) {
            PreparedRestore(decoded, runtimes, fileSystems).discard()
            machine.releaseDiskClaims(mounts.claims)
            throw t
        }
        return PreparedRestore(decoded, runtimes, fileSystems, mounts.dropped, mounts.droppedIds, mounts.claims)
    }

    /** [openMounts] が集めるもの（#56） */
    private class OpenedMounts {
        val claims = ArrayList<Machine.DiskClaim>()
        val dropped = ArrayList<Pair<String, String>>()
        val droppedIds = HashSet<Int>()
    }

    /** 世代のファイルの外枠から MOD のバージョンだけを読む（読めなければ `UNREADABLE`） */
    private fun peekModVersion(id: java.util.UUID, generation: Long): String = try {
        request.source.store.readGenerationModVersion(id, generation)
    } catch (e: IOException) {
        fallback(ColdBootCause.UNREADABLE, "世代 $id/$generation の外枠を読めない: $e", e)
    }

    private fun read(id: java.util.UUID, generation: Long): StoredSnapshot = try {
        request.source.store.readGeneration(id, generation)
    } catch (e: IOException) {
        fallback(ColdBootCause.UNREADABLE, "世代 $id/$generation を読めない: $e", e)
    }

    /**
     * プロセスごとの入口と状態の組み合わせ（ADR 0085 の表）と、init・pid 表を確かめる。どれにも当たらなければ冷えた起動
     */
    private fun validate(decoded: DecodedSnapshot) {
        val kernel = decoded.kernel
        val table = kernel.processes
        try {
            ProcessTable.validateRestore(table.all(), table.orphanZombies, table.nextPid)
        } catch (e: IllegalArgumentException) {
            fallback(ColdBootCause.UNREADABLE, "pid 表が不正: ${e.message}", e)
        }
        val init = table[kernel.initPid]
        if (kernel.initPid == 0 || init == null || init.state.isTerminal) fallback(ColdBootCause.NO_LIVE_INIT, "init（pid ${kernel.initPid}）が生きていない")
        for (p in table.all()) {
            if (p.state.isTerminal) continue
            if (p.kind != ProcessKind.WAIT_POINT) fallback(ColdBootCause.UNSUPPORTED_PROCESS, "pid ${p.pid} は ${p.kind}")
            val layer = decoded.layers[p.pid]
            val p1 = decoded.p1[p.pid]
            if (layer != SnapshotSection.ABI_LAYER_P1 || p1 == null) fallback(ColdBootCause.UNSUPPORTED_PROCESS, "pid ${p.pid} の ABI 層 $layer は戻せない")
            val wait = p.wait
            val wake = p.pid in kernel.wakeOnRestore
            val ok = when (p.state) {
                ProcessState.CREATED -> p1.entry == P1SectionCodec.ENTRY_NO_INSTANCE && wait == null
                ProcessState.WAITING_EVENT -> p1.entry == P1SectionCodec.ENTRY_REWIND && wait != null && (wait.isNotEmpty() || wake)
                ProcessState.RUNNABLE -> p1.entry == P1SectionCodec.ENTRY_REWIND && (wait == null || wait.isNotEmpty() || wake)
                else -> false
            }
            if (!ok) fallback(ColdBootCause.UNSUPPORTED_PROCESS, "pid ${p.pid} の状態 ${p.state}・入口 ${p1.entry}・待ち ${wait?.size} は戻せない")
        }
    }

    /**
     * マウントの表のファイルシステムを開き直す。ディスクは段の予約をしてから開く（#56）。根・`/home` のディスクが開けなければ冷えた起動、
     * それ以外のディスクが開けなければ（今の表にない・開けない）表から落として続ける（[OpenedMounts.dropped]）。ほかが開けなければ冷えた起動
     */
    private fun openMounts(decoded: DecodedSnapshot, out: MutableMap<Int, FileSystem>, mounts: OpenedMounts) {
        for (entry in decoded.kernel.mounts.entries) {
            checkCancelled()
            val source = entry.source
            val fs: FileSystem = when {
                source.startsWith(MachineLimits.DISK_SOURCE_PREFIX) -> {
                    val diskId = source.substring(MachineLimits.DISK_SOURCE_PREFIX.length)
                    val claim = machine.claimPhaseDisk(diskId)
                    val volume = if (claim == null) {
                        null
                    } else {
                        try {
                            opener.open(diskId, machine.restoreClaimCancelled(claim))
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (volume == null) {
                        if (claim != null) machine.releaseDiskClaim(claim)
                        // 取り消されて開かなかったのなら、冷えた起動に流さずに黙って抜ける（#588）
                        checkCancelled()
                        // 予約の後に抜かれた: disksChanged が止めるので、取り消しと同じに黙って抜ける（#56）
                        if (claim != null && machine.isClaimCancelled(claim)) throw RestoreCancelledException()
                        // /home は FS の規則で比べる（根は先に開いて印を付けてあるので、根がディスクなら /HOME も /home。#606）
                        if (entry.point == VfsPath.ROOT || decoded.kernel.mounts.isAt(entry, MachineLimits.HOME_PATH)) {
                            fallback(ColdBootCause.MOUNT, "ディスク $diskId（${entry.point}）を開けない")
                        }
                        LOG.log(System.Logger.Level.WARNING, "マシン ${request.source.id} のディスク $diskId（${entry.point}）を開けないので、マウントを落とす")
                        mounts.dropped += diskId to entry.point.toString()
                        mounts.droppedIds += entry.id
                        continue
                    }
                    mounts.claims += checkNotNull(claim)
                    volume.fs
                }
                source == MachineLimits.TMP_SOURCE -> {
                    val tmp = decoded.tmpfs[entry.id] ?: fallback(ColdBootCause.MOUNT, "${entry.point} の TMPFS 節がない")
                    TmpFileSystem.restore(machine.memoryAccount, { machine.environment.clock.realtimeNanos() }, tmp.snapshot)
                        ?: fallback(ColdBootCause.NO_MEMORY, "${entry.point} の中身の RAM が足りない")
                }
                source == MachineLimits.DEV_SOURCE -> DevFileSystem(machine.environment.entropy, DeviceDirectory.of(machine.devices))
                else -> fallback(ColdBootCause.MOUNT, "知らないマウント $source（${entry.point}）")
            }
            out[entry.id] = fs
            // 後のマウントの /home の見分けに、この FS が大文字小文字を区別するかを先に知らせる（つなぐときにも知らせる。#606）
            decoded.kernel.mounts.setIgnoresCase(entry.id, fs.ignoresCase)
        }
    }

    /** 開いたファイルの node を、まだマウントしていないディスクに登録する（ホストの I/O はしない。ADR 0085） */
    private fun adoptOpenFiles(decoded: DecodedSnapshot, fileSystems: Map<Int, FileSystem>) {
        val kernel = decoded.kernel
        kernel.objects.forEachObject { obj ->
            if (obj !is OpenFile || obj.mountId == 0) return@forEachObject
            val fs = fileSystems[obj.mountId] as? DiskFileSystem ?: return@forEachObject
            val path = kernel.filePaths[obj.id] ?: return@forEachObject
            fs.adoptNode(obj.node, path, obj.type)
        }
    }

    companion object {
        private val LOG: System.Logger = System.getLogger("kibicomputers.restore")

        /** インスタンスを作り直せなかった理由（#576）: [rt] の RAM の勘定が確保を断っていたら NO_MEMORY、ほかは RESTORE_FAILED */
        internal fun restoreInstanceFailureCause(rt: ProcessRuntime): ColdBootCause =
            if (rt.memory.refusedReservation) ColdBootCause.NO_MEMORY else ColdBootCause.RESTORE_FAILED

        /** 読み込みの待ち行列が混んでいるときに、やり直し続ける上限（仮の値） */
        private const val BUSY_RETRY_MILLIS: Long = 60_000

        /** 読み込みのやり直しの間隔（タイマーで積み直す。#577） */
        private const val BUSY_SLEEP_MILLIS: Long = 20

        /** 同じ読み込み方のモジュールを1つにまとめるキー（元のハッシュ・計測・止まりうる import） */
        private fun moduleKey(source: ModuleSource): String = source.originalHex + "/" + source.metered + "/" + source.waitImports
    }
}
