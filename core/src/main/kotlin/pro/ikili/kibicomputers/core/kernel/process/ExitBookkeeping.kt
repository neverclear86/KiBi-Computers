package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.kernel.obj.ObjectTable
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.wait.WakeSink

/** 終わりの記録が触る、カーネルの状態（実行時の `KernelState` と、保存の写しの `KernelStateCopy`。#81） */
internal interface KernelStateView {
    val processes: ProcessTable
    val objects: ObjectTable
}

/**
 * プロセスの終わりの記録と、刈り取りの後片付けのうち、カーネルの状態の側（#46・#81、ADR 0046・0081）。
 *
 * 実行時の経路（`ProcessRunner.finishLocked`・`Machine.reapLocked`）も、保存の写しの中で殺す経路（`KernelStateCopy.killInCopy`）も
 * これを通す。触るのは [view]（プロセス表・オブジェクト表）と [sink] と、刈り取りのコールバック [reap] だけで、
 * マシンの実行時の部分（スレッド・インスタンス・ホスト呼び出し）には触らない。カーネルロックの中で使う。
 *
 * @param reap 終わった子（ゾンビ）を刈り取る。実行時は `Machine.reapLocked`、写しでは表から外して [onReaped] を呼ぶだけ
 */
internal class ExitBookkeeping(
    private val view: KernelStateView,
    private val sink: WakeSink,
    private val reap: (Int) -> Unit,
) {
    private fun objectOf(process: Process): ProcessObject? =
        if (process.objectId == 0) null else view.objects[process.objectId] as? ProcessObject

    /**
     * [process] が終わった（状態・終了コード・`termSignal` は入れてある）。終わりを記録して待ち手を起こし、親へ知らせ、
     * 子を付け替える（刈り取られていない子のゾンビは [reap] で刈り取る）
     */
    fun recordExit(process: Process) {
        val status = statusOf(process)
        objectOf(process)?.let { obj ->
            obj.status = status
            sink.wake(WaitKey(obj.id, WaitCondition.EXITED))
        }
        // 親へ知らせる（親がいない・もう終わっているなら、親のないゾンビにする）
        val parent = if (process.parentPid == 0) null else view.processes[process.parentPid]?.takeIf { !it.state.isTerminal }
        if (parent == null) {
            process.parentPid = 0
            view.processes.addOrphanZombie(process.pid)
        } else {
            objectOf(parent)?.let { obj ->
                obj.pendingChildExits++
                sink.wake(WaitKey(obj.id, WaitCondition.CHILD_EXITED))
            }
        }
        // 子: 終わっていなければ親なしに付け替える（止めない）。ゾンビは待つ相手がいないので刈り取る
        for (childPid in view.processes.childrenOf(process.pid)) {
            val child = view.processes[childPid] ?: continue
            if (child.state.isTerminal) reap(childPid) else child.parentPid = 0
        }
    }

    /** 刈り取りの後片付け（親の数え、`ProcessObject` の参照を返す）。[process] は表から外した後 */
    fun onReaped(process: Process) {
        if (process.parentPid != 0) {
            view.processes[process.parentPid]?.let { parent -> objectOf(parent)?.let { it.pendingChildExits = (it.pendingChildExits - 1).coerceAtLeast(0) } }
        }
        if (process.objectId != 0 && view.objects[process.objectId] != null) view.objects.release(process.objectId, sink)
    }

    /** 終わった [process] の記録（`ProcessObject` がない、テストで直接作ったものにも使う） */
    fun statusOf(process: Process): ExitStatus {
        val reason = when (process.state) {
            ProcessState.EXITED -> ExitReason.EXITED
            ProcessState.KILLED -> ExitReason.KILLED
            else -> ExitReason.CRASHED
        }
        return ExitStatus(process.pid, reason, if (reason == ExitReason.EXITED) process.exitCode ?: 0 else 0, process.termSignal)
    }
}
