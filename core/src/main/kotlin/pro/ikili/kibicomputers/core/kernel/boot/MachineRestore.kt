package pro.ikili.kibicomputers.core.kernel.boot

import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineLimits
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.ProcessTable
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.ProgramLoadBusyException
import pro.ikili.kibicomputers.core.kernel.process.SnapshotCapableDriver
import pro.ikili.kibicomputers.core.kernel.vfs.DevFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.DiskFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.FileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.OpenFile
import pro.ikili.kibicomputers.core.kernel.vfs.TmpFileSystem
import pro.ikili.kibicomputers.core.persist.ColdBootCause
import pro.ikili.kibicomputers.core.persist.RestoreRequest
import pro.ikili.kibicomputers.core.persist.SnapshotFormatException
import pro.ikili.kibicomputers.core.persist.SnapshotSection
import pro.ikili.kibicomputers.core.persist.StoredSnapshot
import pro.ikili.kibicomputers.core.persist.snapshot.DecodedSnapshot
import pro.ikili.kibicomputers.core.persist.snapshot.P1SectionCodec
import pro.ikili.kibicomputers.core.persist.snapshot.SnapshotDecoder
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmLinkException
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

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
 */
internal class PreparedRestore(
    val decoded: DecodedSnapshot,
    val runtimes: List<Pair<ProcessRuntime, WasmInstance?>>,
    val fileSystems: Map<Int, FileSystem>,
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
 * 復元の手順（#85、ADR 0085）。[Machine.restore] が受け付けた後、起動用のスレッド（[BootSequence] と同じ `bootExecutor`）で1回だけ動く。
 *
 * 前段（カーネルを変えない）: 戻す世代を決め（[pro.ikili.kibicomputers.core.persist.MachineSaveStore.restorableGeneration]）、読み
 * （差分なら基準も）、[SnapshotDecoder] で復号・検査し（`SnapshotInspection` と同じ読み手）、プロセスごとに入口と状態の組み合わせを確かめ、
 * モジュールを置き場の元のバイト列から同じ読み込み方で読み込み直してハッシュを照らし、マウントを開き直し（`/tmp` は `TMPFS` 節から）、
 * 開いたファイルの node をディスクに登録し（I/O なし）、インスタンスを作り直す。どこかで失敗したら、作ったものをすべて返して
 * 冷えた起動（`Machine.restoreFellBack`）にする。
 *
 * 後段（`Machine.completeRestore`。カーネルロックの中）: 表に入れて、実行時の部分（待ちの登録・燃料待ち・CPU の待ち行列）を作る。
 */
internal class MachineRestore(private val machine: Machine, private val request: RestoreRequest) {
    private val opener: BootVolumeOpener
        get() = machine.scheduler.hooks.bootVolumes ?: BootVolumeOpener.forDisks(machine.environment.disks, machine.hostFiles)

    fun run() {
        val prepared = try {
            prepare()
        } catch (e: RestoreCancelledException) {
            return
        } catch (e: RestoreFallbackException) {
            LOG.log(System.Logger.Level.WARNING, "マシン ${request.source.id} を戻せないので冷えた起動にする（${e.cause0}）: ${e.message}")
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
            // 取り消し・後段の途中の失敗（CRASHED）では、持ち主がカーネルに移らないので、ロックの外で返す（#576）
            if (!machine.completeRestore(prepared)) prepared.discard()
        } catch (t: Throwable) {
            // 後段に入る前の失敗（後段の中の失敗は completeRestore が CRASHED にして印を下ろすので、restoreFellBack は何もしない）
            prepared.discard()
            machine.restoreFellBack(ColdBootCause.RESTORE_FAILED)
            if (t is VirtualMachineError && t !is StackOverflowError) throw t
        }
    }

    private fun checkCancelled() {
        if (machine.isRestoreCancelled) throw RestoreCancelledException()
    }

    private fun fallback(cause: ColdBootCause, message: String, t: Throwable? = null): Nothing = throw RestoreFallbackException(cause, message, t)

    /** 前段。失敗したら作ったものを返してから投げる */
    private fun prepare(): PreparedRestore {
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
        val stored = read(id, gen)
        val snapshot = stored.snapshot
        if (snapshot.requiresColdBoot) {
            val cause = if (snapshot.coldBootReason == null) ColdBootCause.UNREADABLE else ColdBootCause.KILLED_SECTION
            fallback(cause, "冷えた起動の写し（理由 ${snapshot.coldBootCode}）")
        }
        val base = snapshot.deltaBase?.let { read(it.id, it.generation) }
        checkCancelled()
        val config = machine.config
        val decoded = try {
            SnapshotDecoder.decode(snapshot, config.maxProcesses, config.memoryPages, base)
        } catch (e: SnapshotFormatException) {
            fallback(ColdBootCause.UNREADABLE, "世代 $gen を読めない: ${e.message}", e)
        }
        validate(decoded)
        checkCancelled()

        // プログラムを読み込み直す（同じモジュールは1回。ProgramCache でサーバー全体と共有）
        val programs = HashMap<Int, Program>()
        val byKey = HashMap<String, Program>()
        for (p in decoded.kernel.processes.all()) {
            if (p.state.isTerminal) continue
            val layer = decoded.p1.getValue(p.pid)
            val key = layer.moduleSource.originalHex + "/" + layer.moduleSource.metered + "/" + layer.moduleSource.waitImports
            val program = byKey[key] ?: loadProgram(layer.moduleSource).also { byKey[key] = it }
            if (program.hash != layer.loadedHash || program.hash != p.moduleHash) {
                fallback(ColdBootCause.MODULE_MISMATCH, "pid ${p.pid} のモジュールを読み込み直したハッシュが合わない")
            }
            programs[p.pid] = program
            checkCancelled()
        }

        val fileSystems = LinkedHashMap<Int, FileSystem>()
        val runtimes = ArrayList<Pair<ProcessRuntime, WasmInstance?>>()
        try {
            openMounts(decoded, fileSystems)
            adoptOpenFiles(decoded, fileSystems)
            checkCancelled()
            for (p in decoded.kernel.processes.all().sortedBy { it.pid }) {
                if (p.state.isTerminal) continue
                val program = programs.getValue(p.pid)
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
            throw t
        }
        return PreparedRestore(decoded, runtimes, fileSystems)
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

    /** [source] のモジュールを置き場の元のバイト列から、保存したときと同じ読み込み方で読み込む（ProgramCache で共有） */
    private fun loadProgram(source: pro.ikili.kibicomputers.core.kernel.process.ModuleSource): Program {
        val bytes = machine.scheduler.moduleStore.get(source.originalHash)
            ?: fallback(ColdBootCause.MODULE_MISSING, "モジュール ${source.originalHex} が置き場にない")
        val runtime = machine.scheduler.options.runtime
        val limits = machine.scheduler.options.moduleLimits
        val waitImports = source.waitImports.toSet()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BUSY_RETRY_MILLIS)
        while (true) {
            checkCancelled()
            val future = machine.scheduler.programs.load(machine.id, bytes, source.metered, waitImports) { b ->
                if (source.metered) Program.loadMetered(runtime, b, limits, waitImports) else Program.load(runtime, b, limits)
            }
            try {
                return await(future)
            } catch (e: ProgramLoadBusyException) {
                // 読み込みの待ち行列が上限（#363）: 少し待ってやり直す（冷えた起動には落とさない）
                if (System.nanoTime() > deadline) fallback(ColdBootCause.RESTORE_FAILED, "モジュールの読み込みが混んでいる: ${e.message}", e)
                Thread.sleep(BUSY_SLEEP_MILLIS)
            } catch (e: WasmLoadException) {
                fallback(ColdBootCause.MODULE_MISMATCH, "モジュール ${source.originalHex} を読み込めない: ${e.message}", e)
            }
        }
    }

    /** 読み込みの終わりを待つ。取り消されたら待つのをやめる（始まっていない読み込みは取り消す） */
    private fun await(future: CompletableFuture<Program>): Program {
        val cancel = machine.restoreCancelSignal
        try {
            CompletableFuture.anyOf(future, cancel).get()
        } catch (e: ExecutionException) {
            // 下の future.get() で投げ直す
        }
        if (!future.isDone) {
            future.cancel(false)
            throw RestoreCancelledException()
        }
        return try {
            future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** マウントの表のファイルシステムを開き直す（1つでも開けなければ冷えた起動） */
    private fun openMounts(decoded: DecodedSnapshot, out: MutableMap<Int, FileSystem>) {
        for (entry in decoded.kernel.mounts.entries) {
            checkCancelled()
            val source = entry.source
            val fs: FileSystem = when {
                source.startsWith(MachineLimits.DISK_SOURCE_PREFIX) -> {
                    val diskId = source.substring(MachineLimits.DISK_SOURCE_PREFIX.length)
                    val volume = try {
                        opener.open(diskId)
                    } catch (e: Exception) {
                        null
                    } ?: fallback(ColdBootCause.MOUNT, "ディスク $diskId（${entry.point}）を開けない")
                    volume.fs
                }
                source == MachineLimits.TMP_SOURCE -> {
                    val tmp = decoded.tmpfs[entry.id] ?: fallback(ColdBootCause.MOUNT, "${entry.point} の TMPFS 節がない")
                    TmpFileSystem.restore(machine.memoryAccount, { machine.environment.clock.realtimeNanos() }, tmp.snapshot)
                        ?: fallback(ColdBootCause.NO_MEMORY, "${entry.point} の中身の RAM が足りない")
                }
                source == MachineLimits.DEV_SOURCE -> DevFileSystem()
                else -> fallback(ColdBootCause.MOUNT, "知らないマウント $source（${entry.point}）")
            }
            out[entry.id] = fs
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

        /** 読み込みのやり直しの間隔 */
        private const val BUSY_SLEEP_MILLIS: Long = 20
    }
}
