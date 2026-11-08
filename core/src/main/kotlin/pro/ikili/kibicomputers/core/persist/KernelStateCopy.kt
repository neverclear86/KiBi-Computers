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
import pro.ikili.kibicomputers.core.kernel.wait.NoopWakeSink

/**
 * 保存のためのカーネルの状態の写し（#81、ADR 0081）。カーネルロックの中で [of] で作り、元と次を共有しない:
 * プロセス表（`Process` ごと・fd 表・一時的なタイマー・退避した値）、`ProcessObject`、オブジェクト表（参照の数・`nextObjectId`）。
 * 写せないオブジェクト（`KernelObject.copyForSnapshot` が `null`）は元の実体を指すだけで、写しの中で参照が 0 になっても
 * 後始末を呼ばない（[closedInCopy] に記録する。ホストの資源を閉じない）。
 *
 * 写しの中では [killInCopy] でプロセスを殺せる（元のマシンは止めない）。知らせ口は [NoopWakeSink] なので、元のマシンの待ち手は起きない。
 * スレッドの割り込み・ブロッキング型の枠・ホスト呼び出しの取り消し・インスタンスの `close` は `Machine` の実行時の部分なので、ここからは呼べない。
 *
 * 作った後はどのスレッドから読んでもよい（元とつながっていない）。中身を書き出すのは #82。
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

    /** 写していない実体のうち、写しの中で参照が 0 になったもの（#82 が使う） */
    val closedInCopy: IntArray get() = objects.closedInCopy

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
        bookkeeping.recordExit(process)
        killed += pid
        if (initPid != 0 && pid == initPid) {
            initPid = 0
            coldBootReason = ColdBootReason.INIT_KILLED
        }
    }

    /** 写しを保存データにする（中身の節は #82。今は `KILLED` 節だけ） */
    fun toSource(): MachineSnapshotSource = MachineSnapshotSource { id, generation, modVersion ->
        MachineSnapshot(id, generation, modVersion, emptyList(), killed.toList(), coldBootReason.code)
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
