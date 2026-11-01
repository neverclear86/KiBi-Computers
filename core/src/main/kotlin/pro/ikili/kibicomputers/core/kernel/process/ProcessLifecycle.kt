package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey

/**
 * プロセスの終わりの記録・刈り取り・親が終わったときの子の扱い・シグナルの判定（#46、ADR 0046）。マシンに1つ。
 *
 * どれもカーネルロックの中で呼ぶ。表から外す・スレッドを止めるといった、マシンの実行時の部分に触る操作は
 * [Machine.reapLocked]・[Machine.killRuntimeLocked] に任せる。
 */
internal class ProcessLifecycle(private val machine: Machine) {
    private val kernel get() = machine.kernel

    private fun objectOf(process: Process): ProcessObject? =
        if (process.objectId == 0) null else kernel.objects[process.objectId] as? ProcessObject

    /**
     * [rt] が終わった（`ProcessRunner.finishLocked` から。状態・終了コードは入れてある）。
     * 終わりを記録して待ち手を起こし、親へ知らせ、子を付け替える（刈り取られていない子のゾンビは刈り取る）
     */
    fun onFinishedLocked(rt: ProcessRuntime) {
        val process = rt.process
        process.termSignal = if (process.state == ProcessState.KILLED) rt.killSignal else null
        val status = statusOf(process)
        objectOf(process)?.let { obj ->
            obj.status = status
            machine.wakeSink.wake(WaitKey(obj.id, WaitCondition.EXITED))
        }
        // 親へ知らせる（親がいない・もう終わっているなら、親のないゾンビにする）
        val parent = if (process.parentPid == 0) null else kernel.processes[process.parentPid]?.takeIf { !it.state.isTerminal }
        if (parent == null) {
            process.parentPid = 0
            kernel.processes.addOrphanZombie(process.pid)
        } else {
            objectOf(parent)?.let { obj ->
                obj.pendingChildExits++
                machine.wakeSink.wake(WaitKey(obj.id, WaitCondition.CHILD_EXITED))
            }
        }
        // 子: 終わっていなければ親なしに付け替える（止めない）。ゾンビは待つ相手がいないので刈り取る
        for (childPid in kernel.processes.childrenOf(process.pid)) {
            val child = kernel.processes[childPid] ?: continue
            if (child.state.isTerminal) machine.reapLocked(childPid) else child.parentPid = 0
        }
    }

    /** 刈り取りの後片付けのうち、カーネルの状態の側（親の数え、`ProcessObject` の参照）。[Machine.reapLocked] から呼ぶ */
    fun onReapedLocked(process: Process) {
        if (process.parentPid != 0) {
            kernel.processes[process.parentPid]?.let { parent -> objectOf(parent)?.let { it.pendingChildExits = (it.pendingChildExits - 1).coerceAtLeast(0) } }
        }
        if (process.objectId != 0 && kernel.objects[process.objectId] != null) kernel.objects.release(process.objectId, machine.wakeSink)
    }

    /** [parentPid] の子のうち [selector] に当たるものを1つ刈り取る（待たない） */
    fun tryReapLocked(parentPid: Int, selector: ChildSelector): ReapResult {
        val target: Process = when (selector) {
            is ChildSelector.Pid -> {
                val child = kernel.processes[selector.pid]
                if (child == null || selector.pid == parentPid || child.parentPid != parentPid) return ReapResult.NoChild
                if (!child.state.isTerminal) return ReapResult.Running
                child
            }
            ChildSelector.Any -> {
                val children = kernel.processes.childrenOf(parentPid)
                if (children.isEmpty()) return ReapResult.NoChild
                children.firstNotNullOfOrNull { pid -> kernel.processes[pid]?.takeIf { it.state.isTerminal } } ?: return ReapResult.Running
            }
        }
        val status = objectOf(target)?.status ?: statusOf(target)
        machine.reapLocked(target.pid)
        return ReapResult.Reaped(status)
    }

    /** 終わった [process] の記録（`ProcessObject` がない、テストで直接作ったものにも使う） */
    private fun statusOf(process: Process): ExitStatus {
        val reason = when (process.state) {
            ProcessState.EXITED -> ExitReason.EXITED
            ProcessState.KILLED -> ExitReason.KILLED
            else -> ExitReason.CRASHED
        }
        return ExitStatus(process.pid, reason, if (reason == ExitReason.EXITED) process.exitCode ?: 0 else 0, process.termSignal)
    }

    /** [parentPid] が [selector] の子の終わりを待つための key。待つ子がいなければ空 */
    fun childExitKeysLocked(parentPid: Int, selector: ChildSelector): List<WaitKey> = when (selector) {
        is ChildSelector.Pid -> {
            val child = kernel.processes[selector.pid]
            if (child == null || selector.pid == parentPid || child.parentPid != parentPid || child.objectId == 0) {
                emptyList()
            } else {
                listOf(WaitKey(child.objectId, WaitCondition.EXITED))
            }
        }
        ChildSelector.Any -> {
            val self = kernel.processes[parentPid]
            if (self == null || self.objectId == 0 || kernel.processes.childrenOf(parentPid).isEmpty()) {
                emptyList()
            } else {
                listOf(WaitKey(self.objectId, WaitCondition.CHILD_EXITED))
            }
        }
    }

    /** [pid] に [signal] を送る（待たない）。init には送れない */
    fun killLocked(pid: Int, signal: KillSignal): KillResult {
        val process = kernel.processes[pid] ?: return KillResult.NOT_FOUND
        if (process.state.isTerminal) return KillResult.ALREADY_EXITED
        if (machine.isInitLocked(pid)) return KillResult.NOT_PERMITTED
        if (signal.ignorable && signal in process.ignoredSignals) return KillResult.IGNORED
        val rt = machine.runtimes[pid] ?: return KillResult.NOT_FOUND
        machine.killRuntimeLocked(rt, signal)
        return KillResult.DELIVERED
    }
}
