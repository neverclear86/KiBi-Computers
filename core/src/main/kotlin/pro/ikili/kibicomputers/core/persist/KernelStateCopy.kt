package pro.ikili.kibicomputers.core.persist

import pro.ikili.kibicomputers.core.kernel.KernelState
import pro.ikili.kibicomputers.core.kernel.event.EventHub
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.obj.ObjectTable
import pro.ikili.kibicomputers.core.kernel.process.ExitBookkeeping
import pro.ikili.kibicomputers.core.kernel.process.KernelStateView
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.Process
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.ProcessTable
import pro.ikili.kibicomputers.core.kernel.time.ClockSnapshot
import pro.ikili.kibicomputers.core.kernel.vfs.MountTable
import pro.ikili.kibicomputers.core.kernel.vfs.TmpFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.wait.NoopWakeSink
import pro.ikili.kibicomputers.core.persist.snapshot.KernelSectionCodec
import pro.ikili.kibicomputers.core.persist.snapshot.SnapshotWriter
import pro.ikili.kibicomputers.core.persist.snapshot.TmpfsSectionCodec
import java.util.TreeMap

/**
 * 保存のためのカーネルの状態の写し（#81・#82、ADR 0081・0082）。カーネルロックの中で [of] で作り、元と次を共有しない:
 * プロセス表（`Process` ごと・fd 表・一時的なタイマー・退避した値）、オブジェクト表（全種類の実体・参照の数・`nextObjectId`）。
 * 写しの中の後始末（`onLastClose`・`releaseReferences`）は、写した実体の側で [NoopWakeSink] を知らせ口にして起きる
 * （パイプの相手の端の EOF なども写しの中で起きる。元のマシンの待ち手は起きない）。
 *
 * 写しの中では [killInCopy] でプロセスを殺せる（元のマシンは止めない）。スレッドの割り込み・ブロッキング型の枠・ホスト呼び出しの取り消し・
 * インスタンスの `close` は `Machine` の実行時の部分なので、ここからは呼べない。
 *
 * 冷えた起動の理由が `NONE` のときだけ、`Machine.captureCopy` が中身（ABI 層の写し [putAbi]・`/tmp` の写し [putTmpfs]・
 * 開いたファイルのパス [putFilePath]）を足す。作った後はどのスレッドから読んでもよい（元とつながっていない）。
 */
internal class KernelStateCopy private constructor(
    override val processes: ProcessTable,
    override val objects: ObjectTable,
    val events: EventHub,
    val mounts: MountTable,
    val clocks: ClockSnapshot,
    val fuel: Long,
    val consoleTtyId: Int,
    initPid: Int,
    /** pid → そのプロセスが終わりを待っているホスト側の処理のオブジェクトID（`ProcessRuntime.hostWaits`。写すときに添える） */
    private val hostWaits: HashMap<Int, IntArray>,
) : KernelStateView {
    /** init の pid（写しの中で init を殺したら 0） */
    var initPid: Int = initPid
        private set

    private val killed = ArrayList<Int>()

    /** 写しの中で殺した pid（殺した順） */
    val killedPids: List<Int> get() = killed

    /** 冷えた起動が要る理由 */
    var coldBootReason: ColdBootReason = ColdBootReason.NONE
        private set

    /** 写しの中でホスト側の処理の待ちの key を `wait` から外した pid（`KERNEL` 節の `wakeOnRestore`。#82） */
    private val wakeOnRestorePids = HashSet<Int>()

    /** ABI 層の写し（pid の順） */
    private val abi = TreeMap<Int, AbiCapture>()

    /** `/tmp` の写し（マウントの ID の順） */
    private val tmpfs = TreeMap<Int, TmpFileSystem.TmpSnapshot>()

    /** 開いたファイルのオブジェクトID → FS の中の今のパス（#82。`Vfs.pathHintLocked` で引いたもの） */
    private val paths = HashMap<Int, VfsPath>()

    /** ABI 層の写し1つ */
    class AbiCapture(val layer: Int, val layerVersion: Int, val payload: ByteArray)

    /** [pid] が `wakeOnRestore` か */
    fun wakeOnRestore(pid: Int): Boolean = pid in wakeOnRestorePids

    /** ABI 層の写し（pid → 写し。pid の順） */
    val abiCaptures: Map<Int, AbiCapture> get() = abi

    /** `/tmp` の写し（マウントの ID → 写し） */
    val tmpfsCaptures: Map<Int, TmpFileSystem.TmpSnapshot> get() = tmpfs

    /** 開いたファイルのオブジェクトID → FS の中の今のパス */
    val filePaths: Map<Int, VfsPath> get() = paths

    private val bookkeeping: ExitBookkeeping = ExitBookkeeping(this, NoopWakeSink) { pid ->
        // 写しの刈り取り: 写しの表から外し、写しの ProcessObject の参照を返すだけ
        val process = processes[pid]
        if (process != null && process.state.isTerminal) {
            processes.remove(pid)
            onReaped(process)
        }
    }

    private fun onReaped(process: Process) {
        bookkeeping.onReaped(process)
    }

    /** 起動中・再起動待ちのマシンの写しに、冷えた起動の理由を立てる（プロセスは殺さない） */
    fun markColdBoot(reason: ColdBootReason) {
        coldBootReason = reason
    }

    /**
     * 写しの中だけで [pid] を `SIGKILL` で殺す（`ProcessRunner.finishLocked` のうち、カーネルの状態の側だけ）。
     * 終わっている・ないなら何もしない。init でも殺す（シグナルの許可は確かめない）。init を殺したら理由を [ColdBootReason.INIT_KILLED] にする
     */
    fun killInCopy(pid: Int) {
        val process = processes[pid] ?: return
        if (process.state.isTerminal) return
        process.state = ProcessState.KILLED
        process.termSignal = KillSignal.KILL
        process.exitCode = null
        process.wait = null
        process.asyncWait = null
        process.asyncStack?.clear()
        FdTable(process, objects, NoopWakeSink).closeAll()
        process.waitTimers.forEach { id -> if (objects[id] != null) objects.release(id, NoopWakeSink) }
        process.waitTimers.clear()
        hostWaits.remove(pid)?.forEach { id -> if (objects[id] != null) objects.release(id, NoopWakeSink) }
        wakeOnRestorePids.remove(pid)
        abi.remove(pid)
        bookkeeping.recordExit(process)
        killed += pid
        if (initPid != 0 && pid == initPid) {
            initPid = 0
            coldBootReason = ColdBootReason.INIT_KILLED
        }
    }

    /**
     * 生きている [pid] の、ホスト側の処理の待ち（`hostWaits`）を写しから外す（#82、ADR 0082）。結果は実行時だけのものなので保存しない。
     * `HostCompletion` の参照を返し（表から消える）、写しの `wait` からその objectId の key を外す。外した key があれば
     * `wakeOnRestore` を立てる（状態は変えない。外した結果 `wait` が空になれば空の並びのまま）
     */
    fun dropHostWaitsInCopy(pid: Int) {
        val ids = hostWaits.remove(pid) ?: return
        val process = processes[pid] ?: return
        if (process.state.isTerminal) return
        val removed = ids.toHashSet()
        val wait = process.wait
        if (wait != null) {
            val kept = wait.filter { it.objectId !in removed }
            if (kept.size != wait.size) {
                process.wait = kept
                wakeOnRestorePids += pid
            }
        }
        for (id in ids) if (objects[id] != null) objects.release(id, NoopWakeSink)
    }

    /** [pid] の ABI 層の写しを足す（`Machine.captureCopy` の②） */
    fun putAbi(pid: Int, layer: Int, layerVersion: Int, payload: ByteArray) {
        require(layer in 0..0xFFFF && layerVersion in 0..0xFFFF) { "層の ID と版は u16" }
        abi[pid] = AbiCapture(layer, layerVersion, payload)
    }

    /** マウント [mountId] の `/tmp` の写しを足す */
    fun putTmpfs(mountId: Int, snapshot: TmpFileSystem.TmpSnapshot) {
        tmpfs[mountId] = snapshot
    }

    /** 開いたファイル [objectId] の FS の中の今のパスを足す */
    fun putFilePath(objectId: Int, path: VfsPath) {
        paths[objectId] = path
    }

    /** 中身（ABI 層・`/tmp`・パス）を捨てる（②で init を殺して冷えた起動になったとき。#82 の 12.） */
    fun discardContent() {
        abi.clear()
        tmpfs.clear()
        paths.clear()
    }

    /**
     * 写しを保存データにする。冷えた起動の理由が `NONE` なら `KERNEL`・ABI 層ごとの `PROCESS_ABI`・`TMPFS` を書く。
     * `NONE` でなければ `KILLED` 節だけ（#82 の 12.）。バイト列にするのは返したものの `build` の中（ロックの外）
     */
    fun toSource(): MachineSnapshotSource = MachineSnapshotSource { id, generation, modVersion ->
        val sections = ArrayList<SnapshotSection>()
        if (coldBootReason == ColdBootReason.NONE) {
            sections += SnapshotSection.wrap(SnapshotSection.KERNEL, SnapshotSection.KERNEL_VERSION, KernelSectionCodec.write(this))
            for ((pid, capture) in abi) {
                val out = SnapshotWriter(8 + capture.payload.size)
                out.i32(pid)
                out.u16(capture.layer)
                out.u16(capture.layerVersion)
                out.raw(capture.payload)
                sections += SnapshotSection.wrap(SnapshotSection.PROCESS_ABI, SnapshotSection.PROCESS_ABI_VERSION, out.toByteArray())
            }
            for ((mountId, snapshot) in tmpfs) {
                sections += SnapshotSection.wrap(SnapshotSection.TMPFS, SnapshotSection.TMPFS_VERSION, TmpfsSectionCodec.write(mountId, snapshot))
            }
        }
        MachineSnapshot(id, generation, modVersion, sections, killed.toList(), coldBootReason.code)
    }

    companion object {
        /** [kernel] の写しを作る（カーネルロックの中で呼ぶ）。[hostWaits] は pid → 終わりを待っているホスト側の処理のID */
        fun of(kernel: KernelState, clocks: ClockSnapshot, hostWaits: Map<Int, IntArray>): KernelStateCopy = KernelStateCopy(
            kernel.processes.copyForSnapshot(),
            kernel.objects.copyForSnapshot(),
            kernel.events.copyForSnapshot(),
            kernel.mounts.snapshot(),
            clocks,
            kernel.fuel,
            kernel.consoleTtyId,
            kernel.initPid,
            HashMap(hostWaits),
        )
    }
}
