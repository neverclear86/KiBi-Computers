package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.device.CallError
import pro.ikili.kibicomputers.core.device.CallResult
import pro.ikili.kibicomputers.core.device.DeviceCallException
import pro.ikili.kibicomputers.core.device.DeviceDispatcher
import pro.ikili.kibicomputers.core.device.DeviceHandle
import pro.ikili.kibicomputers.core.device.DeviceInfo
import pro.ikili.kibicomputers.core.device.DeviceLimits
import pro.ikili.kibicomputers.core.device.AttachedDevice
import pro.ikili.kibicomputers.core.device.MethodInfo
import pro.ikili.kibicomputers.core.device.PendingDeviceCall
import pro.ikili.kibicomputers.core.kernel.DiskListing
import pro.ikili.kibicomputers.core.kernel.HostCall
import pro.ikili.kibicomputers.core.kernel.KernelState
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineEnvironment
import pro.ikili.kibicomputers.core.kernel.MachineLimits
import pro.ikili.kibicomputers.core.kernel.MemoryRefusal
import pro.ikili.kibicomputers.core.kernel.MountListing
import pro.ikili.kibicomputers.core.kernel.ProcessKilledException
import pro.ikili.kibicomputers.core.kernel.event.EventBatch
import pro.ikili.kibicomputers.core.kernel.event.EventFilter
import pro.ikili.kibicomputers.core.kernel.event.EventQueue
import pro.ikili.kibicomputers.core.kernel.event.KernelEvent
import pro.ikili.kibicomputers.core.kernel.time.Timer
import pro.ikili.kibicomputers.core.kernel.time.TimerLimits
import pro.ikili.kibicomputers.core.kernel.time.saturatingAdd
import pro.ikili.kibicomputers.core.kernel.fd.FdPlacement
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.fd.HandlePlacement
import pro.ikili.kibicomputers.core.kernel.fd.openLocked
import pro.ikili.kibicomputers.core.kernel.obj.HandleEntry
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FileType
import pro.ikili.kibicomputers.core.kernel.vfs.Vfs
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.vfs.fsError
import pro.ikili.kibicomputers.core.kernel.wait.ProcessWaiter
import pro.ikili.kibicomputers.core.kernel.wait.ThreadWaiter
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.wait.WaitSet
import pro.ikili.kibicomputers.core.kernel.wait.WakeSink
import pro.ikili.kibicomputers.core.kernel.sched.MachineCpu
import pro.ikili.kibicomputers.core.runtime.WasmAsyncStack
import pro.ikili.kibicomputers.core.runtime.WasmAsyncState
import pro.ikili.kibicomputers.core.runtime.fuel.FuelCosts
import pro.ikili.kibicomputers.core.runtime.WasmFuelHandler
import pro.ikili.kibicomputers.core.runtime.WasmHostFunction
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.WasmLinkException
import pro.ikili.kibicomputers.core.runtime.WasmSharedFuel
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentProgram
import pro.ikili.kibicomputers.core.runtime.component.link.LinkedComponent
import pro.ikili.kibicomputers.core.runtime.validation.ModuleLimits
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/**
 * プロセスを動かす本体（マシンに1つ）。ブロッキング型は専用スレッドの中身、待機点型は1回の step。
 *
 * - CPU と許可の出し入れは、[ProcessRuntime.holdsCpu]・[ProcessRuntime.holdsPermit] の印で、持っているものだけを1回だけ返す
 * - ロックの順番は CPU → 許可 → カーネルロック
 * - 例外で抜けたときは燃料を書き戻さず、CPU を持っているときだけ遅延補充する
 */
internal class ProcessRunner(private val machine: Machine) {
    private val scheduler get() = machine.scheduler
    private val lock get() = machine.lock
    private val hooks get() = machine.scheduler.hooks
    private val now: Long get() = machine.scheduler.now

    /** 作ったばかりの [ProcessRuntime] に、燃料切れの処理と [ProcessContext] を付ける */
    fun initRuntime(rt: ProcessRuntime) {
        rt.fuelHandler = object : WasmFuelHandler {
            // ゲストのどの深さからでも呼ばれ、カーネルロックを取るので、先にスタックの余裕を確かめる（#828）。
            // CPU・許可を返して取り直す道は、その手前（[onFuelExhausted]）でもっと大きく確かめる。前借り（#477）の間は、
            // 残りが負の間じゅう関数の入口・loop の先頭ごとに呼ばれるので、この入口は軽く（ロックの区間の分だけ）する
            override fun onFuelExhausted(instance: WasmInstance, remaining: Long): Long {
                StackHeadroom.ensure(StackHeadroom.FUEL_HANDLER_FRAMES)
                return onFuelExhaustedPlain(rt, instance, remaining)
            }

            override fun onFuelExhaustedUnwindable(instance: WasmInstance, remaining: Long): Long {
                StackHeadroom.ensure(StackHeadroom.FUEL_HANDLER_FRAMES)
                return onFuelExhaustedUnwindable(rt, instance, remaining)
            }
        }
        rt.context = Context(rt)
    }

    // ---- 入口 ----

    /** ブロッキング型の専用スレッドの中身 */
    fun runBlocking(rt: ProcessRuntime) {
        val held = HeldLocks.of(this)
        try {
            runBlockingBody(rt)
        } finally {
            recoverLeaks(rt, held)
        }
    }

    private fun runBlockingBody(rt: ProcessRuntime) {
        if (!register(rt)) {
            scheduler.dedicatedThreads.remove(Thread.currentThread())
            return
        }
        try {
            val grant = acquireCpu(rt, null)!!
            load(rt, grant)
            val result = rt.driver.start(rt.context)
            when (result) {
                is StepResult.Exit -> exit(rt, result.code)
                is StepResult.Wait -> throw IllegalStateException("ブロッキング型のプロセスは Wait を返せない")
                StepResult.WaitFuel -> throw IllegalStateException("ブロッキング型のプロセスは WaitFuel を返せない")
            }
        } catch (t: Throwable) {
            exceptionExit(rt, t)
        } finally {
            unregister(rt)
        }
    }

    /**
     * 待機点型の1回の step。CPU と許可を受け取ったワーカーで動く（許可は `PermitPool.submit` が渡す。#350）。
     * 終わりで返した許可の次の step は、同じワーカーの続きにする（[releasePermitAndCpu] の `endOfStep`）
     */
    fun runStep(rt: ProcessRuntime) {
        val held = HeldLocks.of(this)
        try {
            runStepBody(rt)
        } finally {
            recoverLeaks(rt, held)
        }
    }

    private fun runStepBody(rt: ProcessRuntime) {
        rt.holdsPermit = true
        scheduler.permitAcquisitions.incrementAndGet()
        if (!register(rt)) {
            releasePermitAndCpu(rt, endOfStep = true)
            return
        }
        try {
            step(rt)
        } catch (t: Throwable) {
            exceptionExit(rt, t)
        } finally {
            unregister(rt)
        }
    }

    private enum class Outcome { GO, REARM, NO_FUEL }

    /** このスレッドが持っているカーネルのロックの保持数（[recoverLeaks] で、入口と出口を比べる） */
    private class HeldLocks(val machine: Int, val cpu: Int, val permits: Int) {
        companion object {
            fun of(runner: ProcessRunner): HeldLocks =
                HeldLocks(runner.lock.holdCount, runner.machine.cpu.lockHoldCount, runner.scheduler.permits.lockHoldCount)
        }
    }

    /**
     * 安全網（#828）: step・専用スレッドの終わり（スタックが空いた浅いところ）で、入口より多くカーネルのロック（マシンのロック・CPU・許可の
     * 中のロック）を持ったままでないか、終わったプロセスが CPU を持ったままでないか、CPU・許可の待ちの途中で抜けて待ち手を残していないかを確かめる。
     *
     * どれも、ゲストの深い再帰でスタックがロックや待ちの途中で尽きたときにだけ起きうる（ふつうは入口の余裕の確かめ [StackHeadroom] で防ぐ）。
     *
     * - マシンのロック・CPU の中のロックが残った、終わったプロセスが CPU を持ったまま: マシンの不整合としてマシンを CRASHED にする
     *   （ロックの中の状態が途中までしか変わっていない恐れがあるため。サーバーの tick がそのマシンのロックで止まるよりよい）。
     *   マシンのロックは、このスレッドが持ったまま（再入して）CRASHED にしてから放し、CPU も CRASHED にした後で返す。
     *   放してから CRASHED にするまでの間に、tick・保存の写し・同じ台のほかのプロセスが、途中までしか変わっていない状態に入らないように
     * - 許可の中のロックだけが残った: サーバー全体のものなので、放してログに出すだけにする
     * - CPU・許可の待ち手が残った（`MachineCpu.abandonWaiter`・`PermitPool.abandonWaiter`）: 外してログに出す。待ちの途中では、
     *   カーネルの状態を変えていないので、マシンは止めない。待ち手が抜けた後に渡されていた CPU・許可は、受け取る者がいないので次へ渡す
     *
     * ロックの順番（CPU → 許可 → カーネルロック。CPU・許可の中のロックは末端）を守るため、末端のロックは先に放す。
     * ログはロックの外で出す
     */
    private fun recoverLeaks(rt: ProcessRuntime, held: HeldLocks) {
        val thread = Thread.currentThread()
        val machineLeak = lock.holdCount - held.machine
        val cpuLeak = machine.cpu.lockHoldCount - held.cpu
        val permitLeak = scheduler.permits.lockHoldCount - held.permits
        repeat(maxOf(0, permitLeak)) { scheduler.permits.unlockLeaked() }
        repeat(maxOf(0, cpuLeak)) { machine.cpu.unlockLeaked() }
        // 終わっていないプロセスで何も残っていなければ、ロックを足さずに抜ける（ふつうの step の終わり）。
        // 待ちの途中で尽きたプロセスは、例外で終わっている（exceptionExit）ので、待ち手を探すのは終わったプロセスだけでよい
        val terminal = rt.process.state.isTerminal
        if (machineLeak <= 0 && cpuLeak <= 0 && permitLeak <= 0 && !terminal) return
        val cpuWaiter = if (terminal) machine.cpu.abandonWaiter(thread) else MachineCpu.AbandonedWaiter.NONE
        val permitWaiter = terminal && scheduler.permits.abandonWaiter(thread)
        val message: String
        var releaseFailure: IllegalStateException? = null
        lock.lock()
        try {
            // 残ったマシンのロックは、今取った 1 つを残して放す（CRASHED にするまで、ほかのスレッドを入れない）
            repeat(maxOf(0, machineLeak)) { lock.unlock() }
            // 待ちの途中で抜けた後に渡された CPU（受け取る者がいない）。カーネルの状態は変えていないので、渡し直すだけにする
            val strandedCpu = cpuWaiter == MachineCpu.AbandonedWaiter.GRANTED && machine.cpu.currentOwner === rt
            val cpuOwned = !strandedCpu && held.machine == 0 && finishedHoldingCpuLocked(rt, thread)
            val crash = machineLeak > 0 || cpuLeak > 0 || cpuOwned
            if (!crash && permitLeak <= 0 && cpuWaiter == MachineCpu.AbandonedWaiter.NONE && !permitWaiter) return
            message = "プロセス ${rt.pid} のスレッドが、終わりにカーネルのロックか待ち手を残していた" +
                "（マシン $machineLeak、CPU $cpuLeak、許可 $permitLeak、CPU の持ち主のまま $cpuOwned、" +
                "CPU の待ち手 $cpuWaiter、許可の待ち手 $permitWaiter。原因: ${rt.lastError}）"
            if (crash) machine.inconsistencyLocked(message)
            // CRASHED にした後で返す（次の持ち主は、カーネルロックを取ったところで止められたのを見る）
            if (cpuOwned || strandedCpu) {
                try {
                    machine.cpu.release(rt)
                } catch (e: IllegalStateException) {
                    releaseFailure = e
                }
            }
        } finally {
            lock.unlock()
        }
        LOG.log(System.Logger.Level.ERROR, message, rt.lastError)
        releaseFailure?.let { LOG.log(System.Logger.Level.ERROR, "取り残された CPU を返せない（pid ${rt.pid}）", it) }
    }

    /**
     * 終わったプロセスが、このスレッド [thread] の step のものとして CPU を持ったままか（[recoverLeaks]。終わったプロセスは CPU を持たない）。
     * カーネルロックの中で呼ぶ（終わったかどうかと持ち主を、そろえて読む）。
     *
     * 待機点型は、このスレッドが登録を外した後（`rt.thread = null`）に、同じ `rt` の次の step が別のワーカーで始まり、
     * `exit`・`exceptionExit` で終えた（`finishLocked`）後、CPU を返す前までの間がある。そこでは「終わったプロセスが CPU の持ち主」は
     * 正しい状態なので、持ち主かどうかだけでは見分けられない。次の step のスレッドは `rt.thread` に登録されている
     * （登録を外すのは CPU を返した後）ので、`rt.thread` が別のスレッドなら、このスレッドの漏れではない
     */
    private fun finishedHoldingCpuLocked(rt: ProcessRuntime, thread: Thread): Boolean {
        val current = rt.thread
        return rt.process.state.isTerminal && (current == null || current === thread) && machine.cpu.currentOwner === rt
    }

    private fun step(rt: ProcessRuntime) {
        hooks.beforeStepStart?.invoke(machine, rt.pid)
        val resumed = lock.withLockValue { rt.process.state != ProcessState.CREATED }
        // 燃料切れで巻き戻して燃料を待っていた（#339）: 待つ key はなく、退避した値から巻き直す
        var fuelResume = false
        // 保存から戻した直後の起こし（#85。ProcessRuntime.restoreWake）: 揃いを見ずに resume する
        var restoreWake = false
        if (resumed) {
            // 起こされたのに空なら、許可も燃料も使わずに待ち直す
            val empty = lock.withLockValue {
                if (rt.killed) throw ProcessKilledException()
                val ws = rt.waitSet
                if (rt.restoreWake) {
                    check(ws != null || rt.process.asyncStack?.isEmpty == false) { "退避した値のない復元の起こし" }
                    restoreWake = true
                    false
                } else if (ws == null) {
                    check(rt.process.asyncStack?.isEmpty == false) { "待ちのない step" }
                    fuelResume = true
                    false
                } else if (ws.readyKeys(machine.kernel.objects).isEmpty()) {
                    ws.rearm()
                    rt.process.state = ProcessState.WAITING_EVENT
                    true
                } else {
                    false
                }
            }
            if (empty) {
                releasePermitAndCpu(rt, endOfStep = true)
                return
            }
        }
        acquirePermit(rt)
        hooks.afterStepPermit?.invoke(machine, rt.pid)
        var grant = 0L
        var ready: List<WaitKey> = emptyList()
        val outcome = lock.withLockValue {
            if (rt.killed) throw ProcessKilledException()
            val observed = now
            machine.fuelAccount.refill(observed)
            val ws = rt.waitSet
            if (resumed && !fuelResume) ready = ws?.readyKeys(machine.kernel.objects).orEmpty()
            when {
                resumed && !fuelResume && !restoreWake && ready.isEmpty() -> {
                    ws!!.rearm()
                    rt.process.state = ProcessState.WAITING_EVENT
                    Outcome.REARM
                }
                tryGrantLocked(rt, observed, threadless = true)?.also { grant = it } == null -> {
                    // 一時停止中か燃料がない: スレッドを持たずに燃料待ちの列へ（待ちの登録は残す）。
                    // まだインスタンスのないプロセスは CREATED のまま
                    if (resumed) rt.process.state = ProcessState.RUNNABLE
                    Outcome.NO_FUEL
                }
                else -> {
                    if (resumed && !fuelResume) {
                        // 復元の起こしでは waitSet がないことがある（空の wait）。wait はいつも下ろす（#85）
                        ws?.unregister(machine.registry)
                        rt.waitSet = null
                        rt.process.wait = null
                    }
                    rt.restoreWake = false
                    rt.process.state = ProcessState.RUNNING
                    rt.stackActive = true
                    Outcome.GO
                }
            }
        }
        if (outcome != Outcome.GO) {
            releasePermitAndCpu(rt, endOfStep = true)
            return
        }
        var result = if (!resumed) {
            load(rt, grant)
            rt.driver.start(rt.context)
        } else {
            lock.withLockValue { setHolderFuelLocked(rt, grant) }
            // 燃料待ちからなら、止まった燃料切れの呼び出しまで巻き直す（着いたところで onFuelExhaustedUnwindable が消す）
            if (fuelResume) rt.rewindingForFuel = true
            rt.driver.resume(rt.context, ready)
        }
        while (true) {
            when (val r = result) {
                is StepResult.Exit -> {
                    exit(rt, r.code)
                    return
                }
                is StepResult.Wait -> {
                    require(r.keys.isNotEmpty()) { "待つ条件がない Wait" }
                    val immediate = lock.withLockValue {
                        if (rt.killed) throw ProcessKilledException()
                        val waiter = ProcessWaiter { machine.scheduleThreadlessLocked(rt) }
                        val ws = WaitSet.register(machine.registry, machine.kernel.objects, r.keys, waiter)
                        val readyNow = ws.readyKeys(machine.kernel.objects)
                        if (readyNow.isNotEmpty()) {
                            // 最初からそろっていれば、CPU を持ったまま続ける
                            ws.unregister(machine.registry)
                            readyNow
                        } else {
                            releaseFuelLocked(rt)
                            rt.waitSet = ws
                            rt.process.wait = ws.keys
                            rt.process.state = ProcessState.WAITING_EVENT
                            rt.stackActive = false
                            null
                        }
                    }
                    if (immediate == null) {
                        releasePermitAndCpu(rt, endOfStep = true)
                        return
                    }
                    result = rt.driver.resume(rt.context, immediate)
                }
                StepResult.WaitFuel -> {
                    waitFuelUnwound(rt)
                    return
                }
            }
        }
    }

    /**
     * 燃料切れで巻き戻した（[StepResult.WaitFuel]。#339）: スレッドを持たずに燃料待ちの列へ入り、許可と CPU を返す。
     *
     * 巻き戻し・巻き直しの費用（退避した値の数 × [FuelCosts.ASYNC_VALUE]）は、ここでマシンの燃料から引く（借りになり、後の補充で返す）。
     * 再開のときの grant から引かないのは、費用が grant より大きいと、巻き直してすぐにまた切れて先へ進めないため
     *
     * 手持ちを渡した後に tick が進んでいたら、ディスパッチャーがその tick の列を見終えた後かもしれないので、もう一度見直させる
     * （[tryGrantLocked] と同じ。#396）。そうしないと、スレッドを持って待つ側（その場で補充を受け取れる）より再開が最大 1 tick 遅れうる
     */
    private fun waitFuelUnwound(rt: ProcessRuntime) {
        hooks.beforeFuelUnwound?.invoke(machine, rt.pid)
        lock.withLockValue {
            if (rt.killed) throw ProcessKilledException()
            val stack = checkNotNull(rt.process.asyncStack) { "退避先がない" }
            check(!stack.isEmpty) { "燃料切れで巻き戻したのに退避した値がない" }
            val holder = rt.currentHolder ?: throw IllegalStateException("手持ちを持つインスタンスがない")
            // コンポーネントは、巻き戻して `run` から戻るときに、外からの呼び出しの lift の燃料（LinkedComponent.call の chargeFuel）を
            // 手持ちから引いている（#803）。その分も書き戻すよう、巻き戻すと決めたときの残りでなく、今の手持ちを読む。
            // p1 は巻き戻しの間に燃料を引かないので、手持ちは決めたときの残りのまま（読み方を変えない）
            val left = if (rt.program.component != null) holder.fuel else rt.fuelUnwindRemaining
            holder.fuel = 0
            // CPU を持っている間は、最後に補充した tick が手持ちを渡した tick（FuelAccount）。giveBack で書き換わる前に読む
            val grantedTick = machine.fuelAccount.lastRefillTick
            rt.process.state = ProcessState.RUNNABLE
            rt.stackActive = false
            // CPU を返す前に並ぶので、CPU の待ち行列にいるプロセス（この後で燃料がないと分かって並ぶ）の後ろに回る（#47）
            enqueueFuelWaitLocked(rt, threadless = true, behindCpu = true)
            val unwindCost = stack.size.toLong() * FuelCosts.ASYNC_VALUE
            giveBackLocked(left - unwindCost)
            scheduler.fuelUnwindCost.addAndGet(unwindCost)
            rt.fuelUnwindRemaining = 0
            scheduler.deactivate(rt)
            // 巻き戻すと決めたときにマシンの燃料が残っていて（譲るための巻き戻し。#47）、巻き戻しの費用を引いた後も正で、一時停止でなければ、
            // 譲ったものとして数える（自分も燃料待ちから起こされ、CPU の列の後ろに並び直す）。費用を引いて 0 以下になったら、
            // 次の tick まで燃料を待つので、巻き戻して燃料を待ったものとして数える
            if (rt.fuelUnwindYield && machine.kernel.fuel > 0 && !scheduler.isPausedFor(machine)) {
                scheduler.fuelYields.incrementAndGet()
            } else {
                scheduler.fuelUnwinds.incrementAndGet()
            }
            machine.countFuelUnwind()
            rt.fuelUnwindYield = false
            if (now != grantedTick) scheduler.dispatcher.signal()
        }
        releasePermitAndCpu(rt, endOfStep = true)
    }

    /**
     * 燃料待ちの列に入れる（[threadless] ならスレッドを持たない待ち手として。[behindCpu] は [Machine.addFuelWaiterLocked]）。
     * カーネルロックの中で呼ぶ
     */
    private fun enqueueFuelWaitLocked(rt: ProcessRuntime, threadless: Boolean, behindCpu: Boolean = false) {
        rt.fuelReady = false
        rt.fuelWaitThreadless = threadless
        machine.addFuelWaiterLocked(rt, behindCpu)
    }

    /**
     * 一時停止でなく燃料があれば、燃料を渡して grant を返す（燃料を計測しないプロセスは 0）。
     * なければ燃料待ちの列に入れて `null`。カーネルロックの中で、[observedTick] まで補充した後に呼ぶ。
     * [behindCpu] は、列の後ろに置くか（[Machine.addFuelWaiterLocked]。スライスを使った直後に取れなかったスレッドを持つ待ち手。#742）。
     * 一時停止で取れなかったときも同じ位置に入れる（再開のときは列の全員を起こすので、位置は配分に効かない）。
     *
     * 確かめた後に tick が進んだ（か一時停止が解けた）なら、ディスパッチャーが列を見終えた後かもしれないので、もう一度見直させる
     * （起こし忘れを防ぐ）。
     */
    private fun tryGrantLocked(rt: ProcessRuntime, observedTick: Long, threadless: Boolean, behindCpu: Boolean = false): Long? {
        val paused = !scheduler.tryActivate(rt)
        if (!paused) {
            if (rt.process.unmetered) return 0L
            val grant = machine.fuelAccount.take(observedTick, machine.config.fuelSlice)
            if (grant > 0) return grant
            scheduler.deactivate(rt)
        }
        enqueueFuelWaitLocked(rt, threadless, behindCpu)
        if (now != observedTick || (paused && !scheduler.isPausedFor(machine))) scheduler.dispatcher.signal()
        return null
    }

    // ---- スレッドの登録 ----

    /** スレッドを登録する。止められていれば、WASM を動かさずに終えて `false` */
    private fun register(rt: ProcessRuntime): Boolean {
        lock.lock()
        try {
            val state = rt.process.state
            if (state.isTerminal) return false
            if (rt.killed) {
                if (rt.process.kind == ProcessKind.WAIT_POINT) machine.cpu.removeThreadless(rt)
                finishLocked(rt, ProcessState.KILLED, null)
                return false
            }
            check(rt.thread == null) { "プロセス ${rt.pid} に2つのスレッドが入った" }
            rt.thread = Thread.currentThread()
            machine.threadRegisteredLocked()
            // 待機点型は CPU を受け取ってから step が始まる
            if (rt.process.kind == ProcessKind.WAIT_POINT) rt.holdsCpu = true
            return true
        } finally {
            lock.unlock()
        }
    }

    /**
     * step（専用スレッド）の終わり。カーネルロックの中で登録を外し、続けて同じロックの中で割り込みの印を消す。
     * stop() の割り込みは登録を確かめた同じロックの中で送るので、印が別のマシンの step に持ち越されない。
     */
    private fun unregister(rt: ProcessRuntime) {
        hooks.beforeUnregister?.invoke(machine, rt.pid)
        // 念のため、持ったままのものを返す（通常の道筋ではもう返している）
        releasePermitAndCpu(rt, endOfStep = true)
        lock.lock()
        try {
            rt.thread = null
            Thread.interrupted()
            if (rt.killed && !rt.process.state.isTerminal) {
                // WASM が終わった後・登録を外す前に止められた
                machine.cpu.removeThreadless(rt)
                finishLocked(rt, ProcessState.KILLED, null)
            } else if (rt.pendingSchedule) {
                rt.pendingSchedule = false
                if (!rt.process.state.isTerminal) machine.cpu.enqueueThreadless(rt)
            }
            rt.pendingSchedule = false
            if (rt.process.kind == ProcessKind.BLOCKING) {
                scheduler.dedicatedThreads.remove(Thread.currentThread())
            }
            machine.threadUnregisteredLocked()
        } finally {
            lock.unlock()
        }
        hooks.afterUnregister?.invoke(machine, rt.pid)
    }

    // ---- CPU・許可・燃料 ----

    /**
     * CPU → 許可を取り、カーネルロックの中で遅延補充してから、停止の印・[precheck]・一時停止・燃料を確かめて grant を返す。
     * 一時停止か燃料がなければ、許可と CPU を返して燃料待ちの列で待ち、やり直す。
     * [precheck] が `false` を返したら、許可と CPU を返して `null` を返す（待ち直し）。
     * [onNoFuelLocked] は、一時停止か燃料がなくて燃料待ちの列に入れたとき、待つ前にカーネルロックの中で呼ぶ（状態と数え口を合わせるのに使う）。
     *
     * [afterSlice] は、その tick の最後のスライスを使い切った直後に取り直すところ（燃料切れの処理）。最初の1回で取れなければ、燃料待ちの列の後ろに置く
     * （[Machine.addFuelWaiterLocked]。#742、ADR 0047）。燃料待ちから起こされた後に取れなかったときは、スライスを使っていないので前側に入れる
     * （後ろのままだと、起こされても取れなかった側が毎回後ろに回る）
     */
    private fun acquireCpu(
        rt: ProcessRuntime,
        precheck: (() -> Boolean)?,
        afterSlice: Boolean = false,
        onNoFuelLocked: (() -> Unit)? = null,
    ): Long? {
        var behindCpu = afterSlice
        while (true) {
            if (!rt.holdsCpu) {
                try {
                    machine.cpu.acquire(rt)
                } catch (e: InterruptedException) {
                    // 燃料待ちから起こされたときの予約を受け取る前に割り込まれたら、外す（受け取った後なら何もしない）
                    machine.cpu.cancelReservation(rt)
                    throw ProcessKilledException(e)
                }
                rt.holdsCpu = true
            }
            acquirePermit(rt)
            var grant = 0L
            val outcome = lock.withLockValue {
                if (rt.killed) throw ProcessKilledException()
                val observed = now
                machine.fuelAccount.refill(observed)
                when {
                    precheck != null && !precheck() -> Outcome.REARM
                    tryGrantLocked(rt, observed, threadless = false, behindCpu)?.also { grant = it } == null -> {
                        onNoFuelLocked?.invoke()
                        Outcome.NO_FUEL
                    }
                    else -> Outcome.GO
                }
            }
            if (outcome == Outcome.GO) return grant
            releasePermitAndCpu(rt)
            if (outcome == Outcome.REARM) return null
            try {
                waitForFuel(rt)
                behindCpu = false
                hooks.afterFuelWait?.invoke(machine, rt.pid)
            } catch (e: Throwable) {
                // 起こされた（CPU の位置を予約された）後に止められたら、CPU に着かないので予約を外す（#742）
                machine.cpu.cancelReservation(rt)
                if (e is InterruptedException) throw ProcessKilledException(e)
                throw e
            }
        }
    }

    private fun waitForFuel(rt: ProcessRuntime) {
        try {
            lock.lockInterruptibly()
            try {
                while (!rt.fuelReady) {
                    if (rt.killed) throw ProcessKilledException()
                    rt.fuelCondition.await()
                }
            } finally {
                lock.unlock()
            }
        } catch (e: InterruptedException) {
            throw ProcessKilledException(e)
        }
    }

    private fun acquirePermit(rt: ProcessRuntime) {
        if (rt.holdsPermit) return
        try {
            scheduler.permits.acquire()
        } catch (e: InterruptedException) {
            throw ProcessKilledException(e)
        }
        rt.holdsPermit = true
        scheduler.permitAcquisitions.incrementAndGet()
    }

    /**
     * 印が立っているものだけを、許可 → CPU の順に返す。
     *
     * [endOfStep] は、この後に止まる待ちがなく、スレッドが仕事を終えるところ（step・専用スレッドの終わり）。
     * ワーカーなら、許可の次の step を同じワーカーの続きにする（新しいスレッドを作らない。#350）
     */
    private fun releasePermitAndCpu(rt: ProcessRuntime, endOfStep: Boolean = false) {
        // 印は返す前に下ろす（返した後にワーカーへ渡せずに投げたとき、もう一度返して許可を増やさないため）。
        // 返す途中でスタックが尽きないことは、入口の余裕の確かめ（StackHeadroom。#828）で守る
        if (rt.holdsPermit) {
            rt.holdsPermit = false
            scheduler.permits.release(inline = endOfStep)
        }
        if (rt.holdsCpu) {
            rt.holdsCpu = false
            machine.cpu.release(rt)
        }
    }

    /** CPU を返す口（block・awaitHost・Wait・Exit）: 今の holder のグローバルを読んで 0 にし、遅延補充してから書き戻す */
    private fun releaseFuelLocked(rt: ProcessRuntime) {
        rt.fuelOverdraft = false
        if (!rt.process.unmetered) {
            val holder = rt.currentHolder ?: throw IllegalStateException("手持ちを持つインスタンスがない")
            val r = holder.fuel
            holder.fuel = 0
            giveBackLocked(r)
        }
        scheduler.deactivate(rt)
    }

    /** マシンの燃料に書き戻す。残りが正なら、同じ tick の中で燃料待ちが使えるように起こす */
    private fun giveBackLocked(remaining: Long) {
        machine.fuelAccount.giveBack(now, remaining)
        if (machine.kernel.fuel > 0) machine.wakeFuelWaitersLocked()
    }

    /**
     * スライスを使い切ったとき（#47、ADR 0047）: CPU を返さずにその場で次のスライスを足せるなら、マシンの燃料に残り [remaining] を書き戻してから
     * 次のスライスを取り、新しい手持ちを返す。足せなければ何も書き換えずに `null`。カーネルロックの中で呼ぶ。
     *
     * 足すのは、一時停止（マシンだけの quiesce も）でなく、手持ちを渡した tick の中（[FuelAccount.lastRefillTick] が今の tick）で、
     * 書き戻した後のマシンの燃料が正で、CPU を待つプロセスも燃料待ちもいないときだけ。
     * 待つ者がいれば譲る（呼んだ側が CPU を返す）。tick が進んでいれば、待つ者がいなくても CPU と許可を返す（呼んだ側が返して取り直す）。
     * 許可はサーバー全体で数が限られ、公平な順で回るので、tick をまたいで持ち続けると、ほかの台が取れなくなる（ADR 0027。#47 より前は燃料切れのたびに返していた）。
     * プロセスが1つだけの台は、同じ tick の中なら CPU と許可の出し入れも巻き戻しもせずに、1 tick に `fuelPerTick` まで使える。
     * 手持ちは呼んだ側（燃料切れの処理の戻り値）が今の holder に入れるので、作っている途中・ホスト関数の中から呼んだゲストの中でもよい
     */
    private fun renewSliceLocked(rt: ProcessRuntime, remaining: Long): Long? {
        if (rt.killed || scheduler.isPausedFor(machine)) return null
        val observed = now
        val account = machine.fuelAccount
        // 手持ちを渡した後に tick が進んでいたら、その場で足さない（許可を回す）
        if (account.lastRefillTick != observed) return null
        if (account.afterGiveBack(observed, remaining) <= 0) return null
        if (machine.cpu.waiting > 0 || machine.hasFuelWaitersLocked()) return null
        account.giveBack(observed, remaining)
        val grant = account.take(observed, machine.config.fuelSlice)
        check(grant > 0) { "書き戻した後の燃料が正なのに、スライスを渡せない" }
        // 前借りしていたら、ここで返した（巻き戻せるところを探さなくてよい）
        rt.fuelOverdraft = false
        scheduler.fuelSliceRenewals.incrementAndGet()
        return grant
    }

    /** その場で足せなかったとき、譲るのか（マシンの燃料が残っていて、一時停止でない）。数え口と状態を分けるのに使う。カーネルロックの中で呼ぶ */
    private fun yieldingLocked(remaining: Long): Boolean =
        !scheduler.isPausedFor(machine) && machine.fuelAccount.afterGiveBack(now, remaining) > 0

    private fun setHolderFuelLocked(rt: ProcessRuntime, grant: Long) {
        if (rt.process.unmetered) return
        val holder = rt.currentHolder ?: throw IllegalStateException("手持ちを持つインスタンスがない")
        holder.fuel = grant
    }

    /**
     * 止まりうる関数でない関数の中の燃料切れ（`kibi.fuel`）。
     *
     * 燃料切れも待ち点にしたモジュール（[Program.fuelYield]）の待機点型のプロセスなら、すぐにはスレッドを持って待たず、
     * 残りを負のまま返して先へ進める（前借り。#477）。残りが負の間は、次に燃料を確かめるところ（関数の入口・loop の先頭）でまた呼ばれるので、
     * 止まりうる関数の中で確かめたところ（`kibi.async_fuel`。[onFuelExhaustedUnwindable]）で巻き戻し、スレッドを持たずに燃料を待つ。
     * 前借りが [FUEL_OVERDRAFT_LIMIT] に届いたら（止まりうる関数に戻らずに計算し続けている）、前と同じくスレッドを持ったまま待つ。
     * コンポーネント（#803）では、ゲストからゲストへの呼び出しの下の callee の中でも前借りする（callee から戻れば、外からの call の
     * 1 段目の止まりうる関数で巻き戻せる）。
     * 前借りした分は残り（負）として書き戻すので、マシンの燃料の借りになり、後の補充で返す
     */
    private fun onFuelExhaustedPlain(rt: ProcessRuntime, instance: WasmInstance, remaining: Long): Long {
        if (remaining > -FUEL_OVERDRAFT_LIMIT && rt.program.fuelYield) {
            val overdraft = lock.withLockValue {
                verifyInstanceLocked(rt, instance)
                if (rt.killed) throw ProcessKilledException()
                // 譲る相手がいなければ、前借りせずにその場で次のスライスを足す（#47）
                renewSliceLocked(rt, remaining)?.let { return it }
                // コンポーネントでは、ゲストからゲストへの呼び出しの下（LinkedComponent.canUnwind が偽）でも前借りする（#803）。
                // 前借りは巻き戻さないので Kotlin の層を巻き戻すことはなく、callee が上限の中で戻れば、外の止まりうる関数の次の
                // kibi.async_fuel で巻き戻せる。戻らなければ、上限に届いたところで p1 の葉の関数と同じくスレッドを持って待つ。
                // realloc・post-return の中（ホストの関数・canon が呼んだゲスト）は guestCallDepth で弾く（戻った先でも巻き戻せないため）
                unwindableLocked(rt)
            }
            if (overdraft) {
                if (!rt.fuelOverdraft) {
                    rt.fuelOverdraft = true
                    scheduler.fuelOverdrafts.incrementAndGet()
                }
                return remaining
            }
        }
        rt.fuelOverdraft = false
        return onFuelExhausted(rt, instance, remaining)
    }

    /**
     * 燃料切れの処理（スレッドを持ったまま）。グローバルを読まない・書かない（残りは引数、新しい手持ちは戻り値）。
     *
     * 譲る相手がいなければ、その場で次のスライスを足して続ける（[renewSliceLocked]）。
     * マシンの燃料が残っていれば（スライスを使い切っただけ。#47）、CPU を返して FIFO の後ろに並び直す。状態は変えない（燃料を待つのではない）。
     * ただし、CPU を取り直したときにその tick の燃料がもう残っていない（か一時停止）なら、そこから先は燃料を待つので、
     * `WAITING_FUEL` にして `fuelBlockingWaits` に数えてから待つ（譲った回数の `fuelYields` にも数えたまま）。
     * 残っていない（か一時停止）なら、`WAITING_FUEL` にして次の tick の補充（か再開）を待つ
     */
    private fun onFuelExhausted(rt: ProcessRuntime, instance: WasmInstance, remaining: Long): Long {
        var waitingFuel = false
        val previous = lock.withLockValue {
            verifyInstanceLocked(rt, instance)
            if (rt.killed) throw ProcessKilledException()
            renewSliceLocked(rt, remaining)?.let { return it }
            // 譲る・待つ道（CPU・許可を返して取り直す。深い）に入る前に、状態を何も変えないうちに確かめる（#828）。
            // ここまでの入口の確かめで、このロックの区間を放すだけの余裕はあるので、尽きたらロックを放して StackOverflowError が上がる
            StackHeadroom.ensure(StackHeadroom.FUEL_YIELD_FRAMES)
            val yielding = yieldingLocked(remaining)
            giveBackLocked(remaining)
            scheduler.deactivate(rt)
            val prev = rt.process.state
            if (yielding) {
                scheduler.fuelYields.incrementAndGet()
            } else {
                // 数えてから状態を変える（ロックを外して数えると、WAITING_FUEL を見た側がまだ数えていない値を読む）
                scheduler.fuelBlockingWaits.incrementAndGet()
                rt.process.state = ProcessState.WAITING_FUEL
                waitingFuel = true
            }
            prev
        }
        releasePermitAndCpu(rt)
        // その tick の最後のスライスを使い切った（譲るのではない）なら、取れなければ燃料待ちの列の後ろに並ぶ（#742）。
        // 譲ったときは CPU の待ち行列の後ろに回るので、そこで燃料がないと分かったら、CPU の待ち行列にいたほかの待ち手と同じく前側に入る
        val grant = acquireCpu(rt, precheck = null, afterSlice = waitingFuel) {
            // 譲った後、CPU を取り直したら燃料が残っていなかった: ここから次の tick（か再開）を待つ
            if (!waitingFuel) {
                waitingFuel = true
                scheduler.fuelBlockingWaits.incrementAndGet()
                rt.process.state = ProcessState.WAITING_FUEL
            }
        }!!
        lock.withLockValue { rt.process.state = previous }
        return grant
    }

    /**
     * 止まりうる関数の中の燃料切れ（#339。`kibi.async_fuel`）。巻き戻せるなら、状態を `UNWINDING` にして戻る
     * （`_start` まで戻ったら、ドライバーが [StepResult.WaitFuel] を返す）。
     * 燃料待ちからの巻き直し（`REWINDING`）で呼ばれたら、止まった呼び出しに着いたので `NORMAL` に戻し、step が渡した手持ちを返す。
     * 巻き戻せないところ（モジュールの start 関数の中、ホスト関数の中から呼んだゲストの中。#73）では、[onFuelExhausted] と同じくスレッドを持ったまま待つ。
     * コンポーネントのプロセスは [onComponentFuelExhaustedUnwindable] に分ける（#803）
     */
    private fun onFuelExhaustedUnwindable(rt: ProcessRuntime, instance: WasmInstance, remaining: Long): Long {
        val unwindable = lock.withLockValue {
            verifyInstanceLocked(rt, instance)
            if (rt.killed) throw ProcessKilledException()
            unwindableLocked(rt)
        }
        if (rt.program.component != null) return onComponentFuelExhaustedUnwindable(rt, instance, remaining, unwindable)
        if (!unwindable) return onFuelExhausted(rt, instance, remaining)
        if (instance.asyncState == WasmAsyncState.REWINDING) {
            check(rt.rewindingForFuel) { "燃料待ちからでない巻き直しが、燃料切れの呼び出しに着いた" }
            check(rt.process.asyncStack?.isEmpty != false) { "巻き直しの後に退避した値が残っている" }
            rt.rewindingForFuel = false
            instance.asyncState = WasmAsyncState.NORMAL
            return lock.withLockValue { instance.fuel }
        }
        check(instance.asyncState == WasmAsyncState.NORMAL) { "巻き戻しの途中で燃料切れの呼び出しが走った" }
        val yielding = lock.withLockValue {
            if (rt.killed) throw ProcessKilledException()
            renewSliceLocked(rt, remaining)?.let { return it }
            yieldingLocked(remaining)
        }
        rt.fuelOverdraft = false
        rt.fuelUnwound = true
        rt.fuelUnwindRemaining = remaining
        rt.fuelUnwindYield = yielding
        instance.asyncState = WasmAsyncState.UNWINDING
        return remaining
    }

    /**
     * 燃料切れを巻き戻せるプロセスの状態か（待機点型で、作っている途中でなく、ホスト関数の中からゲストを呼んでいる間でない）。
     * ホスト関数の中からゲストを呼んでいる間（#73。cabi_realloc、コンポーネントの realloc・post-return など）は、ホスト関数の途中を
     * 巻き戻せないので待つ。カーネルロックの中で呼ぶ
     */
    private fun unwindableLocked(rt: ProcessRuntime): Boolean =
        rt.process.kind == ProcessKind.WAIT_POINT && rt.loading == null && rt.guestCallDepth == 0

    /**
     * コンポーネントのプロセスの、止まりうる関数の中の燃料切れ（#803。`kibi.async_fuel`）。[onFuelExhaustedUnwindable] のコンポーネント版で、
     * 状態は 1 つのインスタンスでなく、[LinkedComponent] を通して全インスタンスを同時に変える（巻き戻しは [LinkedComponent.requestUnwind]、
     * 着いたときは [LinkedComponent.endFuelRewind]）。ホストの関数の巻き戻しと同じく、退避した値はプロセスに 1 つのスタックに積み、
     * `WasiP2Driver` が `run` から戻ったところで [StepResult.WaitFuel] を返す。
     *
     * [unwindable] はプロセスの状態で巻き戻せるか（[unwindableLocked]）。それに加えて、コンポーネントが巻き戻せるところ
     * （[LinkedComponent.canUnwind]。ゲストからゲストへの呼び出しの下、realloc・post-return の中でない）でなければ、
     * [onFuelExhausted] と同じくスレッドを持ったまま待つ
     */
    private fun onComponentFuelExhaustedUnwindable(rt: ProcessRuntime, instance: WasmInstance, remaining: Long, unwindable: Boolean): Long {
        val component = rt.component
        if (!unwindable || component == null) return onFuelExhausted(rt, instance, remaining)
        if (component.isRewinding) {
            // 燃料待ちからの巻き直しが、止まった燃料切れの呼び出しに着いた（ホストの関数の巻き直しは、燃料の確かめを通らない）
            check(rt.rewindingForFuel) { "燃料待ちからでない巻き直しが、燃料切れの呼び出しに着いた" }
            check(rt.process.asyncStack?.isEmpty != false) { "巻き直しの後に退避した値が残っている" }
            rt.rewindingForFuel = false
            component.endFuelRewind()
            return lock.withLockValue { instance.fuel }
        }
        // 守りの分岐。今は通らない: ゲストからゲストへの呼び出しの下に止まりうる関数があれば、変換を断る（ComponentAsyncify）ので、
        // 止まりうる関数の燃料切れ（kibi.async_fuel）は外からの call の 1 段目でしか起きず、realloc・post-return の中は guestCallDepth で弾く
        if (!component.canUnwind()) return onFuelExhausted(rt, instance, remaining)
        val yielding = lock.withLockValue {
            if (rt.killed) throw ProcessKilledException()
            renewSliceLocked(rt, remaining)?.let { return it }
            yieldingLocked(remaining)
        }
        rt.fuelOverdraft = false
        rt.fuelUnwound = true
        rt.fuelUnwindRemaining = remaining
        rt.fuelUnwindYield = yielding
        // 計測コードは、残りを関数の中のローカルに控えていて、巻き戻して戻るときはグローバルに書き戻さない（古いままになる）。
        // waitFuelUnwound は lift の燃料を引いた後のグローバルを読むので、ここで今の残りを入れておく
        // （入れないと、グローバルに残った古い手持ちを、使い切ったはずの燃料としてマシンへ返してしまう）
        lock.withLockValue { rt.currentHolder?.fuel = remaining }
        component.requestUnwind()
        return remaining
    }

    /**
     * ホスト関数・燃料切れの処理の入口の照合。違えばマシンを CRASHED にして投げる。
     *
     * コンポーネントのプロセス（#263）は、中の core インスタンスがみな燃料のグローバルを共有し、互いに呼び合う（どれからでもホスト関数・
     * 燃料切れの処理が呼ばれる）ので、このプロセスのインスタンスのどれかであればよい。作っている途中は、作り終えていない core インスタンスの
     * start 関数からも呼ばれるので照合しない（最初に呼んだものを仮の holder にするだけ。どれも同じ残りを読み書きする）
     */
    private fun verifyInstanceLocked(rt: ProcessRuntime, instance: WasmInstance) {
        val frame = rt.loading
        if (rt.program.component != null) {
            if (frame != null) {
                if (frame.provisional == null) frame.provisional = instance
            } else if (!rt.instances.containsSame(instance)) {
                throw machine.inconsistencyLocked("このプロセスのインスタンスでないものから呼ばれた（pid ${rt.pid}）")
            }
            return
        }
        if (frame != null) {
            val provisional = frame.provisional
            if (provisional == null) {
                frame.provisional = instance
            } else if (provisional !== instance) {
                throw machine.inconsistencyLocked("作っている途中のインスタンスと違うインスタンスから呼ばれた（pid ${rt.pid}）")
            }
        } else if (instance !== rt.fuelHolder) {
            throw machine.inconsistencyLocked("手持ちを持たないインスタンスから呼ばれた（pid ${rt.pid}）")
        }
    }

    // ---- インスタンスを作る ----

    /** ABI 層の設定を確かめ、カーネルが決めるもの（燃料以外。メモリの上限）を設定する */
    private fun applyKernelLimits(rt: ProcessRuntime, builder: WasmInstanceConfig.Builder) {
        check(builder.initialFuel == 0L && builder.fuelHandler == null) { "ABI 層は builder.fuel を呼ばない（燃料はカーネルが設定する）" }
        check(builder.memoryBudget == null && !builder.writtenMemory && builder.maxMemoryPages == WasmMemory.MAX_PAGES) { "ABI 層は builder.memoryLimit を呼ばない（メモリの上限はカーネルが設定する）" }
        check(builder.asyncStack == null) { "ABI 層は builder.asyncStack を呼ばない（退避先はカーネルが設定する）" }
        // RAM は初めて書き込んだ 4 KiB の区画で数える（#407）。ページ数はアドレス空間の上限だけを見る。
        // アドレス空間は RAM より大きくてよい（書かない所は数えない）。RAM がそれより大きいマシンでは RAM まで
        val ramPages = minOf(rt.memory.limitPages, machine.memoryAccount.limitPages)
        builder.writtenMemoryLimit(maxOf(ADDRESS_SPACE_PAGES, ramPages), rt.memory) { rt.memoryGrowRefusal = rt.memory.lastRefusal }
        // テーブル（#361）: 要素数の上限は読み込みの上限と同じ値にし、要素の分はメモリと同じくプロセスと RAM に数える
        check(builder.tableBudget == null && builder.maxTableElements == Int.MAX_VALUE) { "ABI 層は builder.tableLimit を呼ばない（テーブルの上限はカーネルが設定する）" }
        val maxTableElements = (scheduler.options.moduleLimits ?: ModuleLimits.DEFAULT).maxTableElements
        builder.tableLimit(maxTableElements, rt.memory)
    }

    /** プロセスの始まり: LOADING で configure → 燃料の設定 → instantiate */
    private fun load(rt: ProcessRuntime, grant: Long) {
        rt.program.component?.let { return loadComponent(rt, it, grant) }
        lock.withLockValue {
            rt.process.state = ProcessState.LOADING
            rt.loading = LoadingFrame(null, null)
            rt.stackActive = true
        }
        val builder = WasmInstanceConfig.builder()
        rt.driver.configure(rt.context, builder)
        applyKernelLimits(rt, builder)
        if (!rt.process.unmetered) builder.fuel(grant, rt.fuelHandler)
        if (rt.program.module.isAsyncified) {
            // 退避した値はカーネルの状態（Process）に置く（#329）。WASM の実行の中から、このプロセスのスレッドだけが触る
            val stack = AsyncStack()
            lock.withLockValue { rt.process.asyncStack = stack }
            builder.asyncStack(
                object : WasmAsyncStack {
                    override fun push(value: Long) = stack.push(value)

                    override fun pop(): Long = stack.pop()
                },
            )
        }
        val refusalsBefore = rt.memory.refusalCount
        rt.instantiateRefusedMemory = false
        val instance = try {
            rt.program.module.instantiate(builder.build())
        } catch (t: Throwable) {
            // 作る間に RAM の上限で断られたなら、その後に異常終了したときにメモリ不足と書く（#48）
            if (t is WasmLinkException && rt.memory.refusalCount != refusalsBefore) rt.instantiateRefusedMemory = true
            throw t
        }
        lock.withLockValue {
            val frame = rt.loading!!
            val provisional = frame.provisional
            if (provisional != null && provisional !== instance) {
                instance.close()
                throw machine.inconsistencyLocked("作り終えたインスタンスが仮の holder と違う（pid ${rt.pid}）")
            }
            rt.instances += instance
            rt.firstInstance = instance
            rt.fuelHolder = instance
            rt.loading = null
            rt.process.state = ProcessState.RUNNING
        }
    }

    /**
     * コンポーネントのプロセスの始まり（#263。ADR 0263）: LOADING で、リンクの計画のとおりに core インスタンスを作る。
     *
     * 中の core インスタンスは、みな同じ燃料のグローバル（[WasmSharedFuel]。最初の値は配分 [grant]）を共有する。ホストの関数は計画が持つ
     * もの（`ComponentHost`）を [ProcessContext.hostFunction] で包んで渡し、`ProcessDriver.configure` は呼ばない。作ったインスタンスは
     * 作るたびに [ProcessRuntime.instances] に入れる（途中で失敗しても、プロセスの終わりで閉じる）。作り終えたら最初のインスタンスを holder にする
     */
    private fun loadComponent(rt: ProcessRuntime, program: ComponentProgram, grant: Long) {
        lock.withLockValue {
            rt.process.state = ProcessState.LOADING
            rt.loading = LoadingFrame(null, null)
            rt.stackActive = true
        }
        val metered = !rt.process.unmetered
        check(metered == program.metered) { "燃料の計測の有無がプロセスと違うコンポーネント" }
        val ctx = rt.context
        // いま作っている core インスタンスより前に断った分は、その失敗の原因にしない（#48）。core インスタンスを作り始めるたびに更新する
        var refusalsBefore = rt.memory.refusalCount
        rt.instantiateRefusedMemory = false
        val env = object : KernelLinkEnv(ctx) {
            override fun configure(builder: WasmInstanceConfig.Builder) {
                refusalsBefore = rt.memory.refusalCount
                reserveCoreInstanceOverhead(rt)
                applyKernelLimits(rt, builder)
                if (metered) builder.fuel(0, rt.fuelHandler)
            }

            override fun wrapHost(function: WasmHostFunction): WasmHostFunction =
                ctx.hostFunction { _, caller, args -> function.call(caller, args) }

            override fun <T> callGuest(block: () -> T): T = ctx.callGuest(block)

            override fun onInstanceCreated(instance: WasmInstance) {
                lock.withLockValue {
                    if (rt.killed) throw ProcessKilledException()
                    rt.instances += instance
                }
            }

            override fun chargeFuel(amount: Long) = ctx.chargeFuel(amount)
        }
        // 待機点への変換をかけたもの（#757）: 全 core インスタンスが退避した値を、プロセスの 1 つのスタック（Process.asyncStack）に積む
        val asyncStack = if (program.waitPoints) {
            val stack = AsyncStack()
            lock.withLockValue { rt.process.asyncStack = stack }
            object : WasmAsyncStack {
                override fun push(value: Long) = stack.push(value)

                override fun pop(): Long = stack.pop()
            }
        } else {
            null
        }
        val linked = try {
            LinkedComponent.instantiate(program.plan, env, if (metered) WasmSharedFuel(grant) else null, asyncStack)
        } catch (t: Throwable) {
            // 失敗した core インスタンスを作る間に RAM の上限で断られたなら、その後に異常終了したときにメモリ不足と書く（#48）
            if (t is WasmLinkException && rt.memory.refusalCount != refusalsBefore) rt.instantiateRefusedMemory = true
            throw t
        }
        lock.withLockValue {
            val first = rt.instances.firstOrNull()
            if (first == null) {
                linked.close()
                throw WasmLinkException("core インスタンスのないコンポーネントは動かせない")
            }
            rt.component = linked
            rt.firstInstance = first
            rt.fuelHolder = first
            rt.loading = null
            rt.process.state = ProcessState.RUNNING
        }
    }

    /**
     * コンポーネントの core インスタンスを 1 つ作る前に、その固定費（[CORE_INSTANCE_OVERHEAD_BYTES]）を RAM の 3 段に数える（#270）。
     * 断られたら [WasmLinkException]（断った回数が増えるので、異常終了の文はメモリ不足になる）。数えた分はプロセスの終わりにまとめて返す。
     * プロセスの終わり（別のスレッドの kill でも来る）と取り違えないよう、カーネルロックの中で数えて記録する（作るたびに 1 回だけ）。
     * `LinkEnv.configure` が「core インスタンス 1 つにつき、作る直前にちょうど 1 回」呼ばれる約束（`LinkEnv.configure` の KDoc）に頼っている
     */
    private fun reserveCoreInstanceOverhead(rt: ProcessRuntime) {
        lock.withLockValue {
            if (rt.killed || rt.process.state.isTerminal) throw ProcessKilledException()
            if (!rt.memory.tryReserveBytes(CORE_INSTANCE_OVERHEAD_BYTES)) {
                throw WasmLinkException("core インスタンスの固定費（$CORE_INSTANCE_OVERHEAD_BYTES バイト）を RAM に確保できない")
            }
            rt.coreInstanceOverheadBytes += CORE_INSTANCE_OVERHEAD_BYTES
        }
    }

    /**
     * 保存から戻すプロセスのインスタンスを作る（#85、ADR 0085）。[load] と同じ設定（`configure` → カーネルの上限 → 燃料 0 → 退避先の結び付け）で
     * [state] から作り直す（start 関数は動かさない）。退避した値 [asyncStack] を [Process.asyncStack] に入れる。
     * 起動用のスレッドで、カーネルロックを持たずに呼ぶ。[rt] はまだマシンの表に入れていない（前段。カーネルを変えない）。
     * 作ったインスタンスは返すだけで、[ProcessRuntime] にはつながない（後段の [adoptRestoredInstanceLocked]）
     *
     * @throws pro.ikili.kibicomputers.core.runtime.WasmLinkException 状態がモジュールに合わない・RAM が足りない
     */
    fun instantiateForRestore(rt: ProcessRuntime, state: pro.ikili.kibicomputers.core.runtime.WasmInstanceState, asyncStack: LongArray): WasmInstance {
        val builder = WasmInstanceConfig.builder()
        rt.driver.configure(rt.context, builder)
        applyKernelLimits(rt, builder)
        if (!rt.process.unmetered) builder.fuel(0, rt.fuelHandler)
        if (rt.program.module.isAsyncified) {
            val stack = AsyncStack()
            for (v in asyncStack) stack.push(v)
            rt.process.asyncStack = stack
            builder.asyncStack(
                object : WasmAsyncStack {
                    override fun push(value: Long) = stack.push(value)

                    override fun pop(): Long = stack.pop()
                },
            )
        } else {
            require(asyncStack.isEmpty()) { "待機点に変換していないモジュールに退避した値がある" }
        }
        return rt.program.module.instantiateForRestore(builder.build(), state)
    }

    /** [instantiateForRestore] で作った [instance] を [rt] につなぐ（後段。カーネルロックの中）。燃料のグローバルは 0 にする（ADR 0082） */
    fun adoptRestoredInstanceLocked(rt: ProcessRuntime, instance: WasmInstance) {
        rt.instances += instance
        rt.firstInstance = instance
        rt.fuelHolder = instance
        if (!rt.process.unmetered) instance.fuel = 0
    }

    // ---- 終わり方 ----

    private fun exit(rt: ProcessRuntime, code: Int) {
        lock.withLockValue {
            releaseFuelLocked(rt)
            rt.stackActive = false
            finishLocked(rt, ProcessState.EXITED, code)
        }
        hooks.afterFinish?.invoke(machine, rt.pid)
        releasePermitAndCpu(rt, endOfStep = true)
    }

    /**
     * 例外で抜けたとき: グローバルを読まず、マシンの燃料に何も書き戻さない。CPU を持っているときだけ遅延補充する。
     * WASM のトラップも JVM の例外も、このプロセスだけを `CRASHED`（停止の印があれば `KILLED`）で終える（#41）
     */
    private fun exceptionExit(rt: ProcessRuntime, t: Throwable) {
        rt.lastError = t
        lock.lock()
        try {
            machine.fuelAccount.onException(now, rt.holdsCpu)
            scheduler.deactivate(rt)
            rt.loading = null
            if (!rt.process.state.isTerminal) {
                val state = if (rt.killed) ProcessState.KILLED else ProcessState.CRASHED
                if (state == ProcessState.CRASHED) rt.crashReason = processCrashReason(rt, t)
                finishLocked(rt, state, null)
            }
        } finally {
            lock.unlock()
        }
        hooks.afterFinish?.invoke(machine, rt.pid)
        releasePermitAndCpu(rt, endOfStep = true)
    }

    /**
     * `CRASHED` の理由（#41）。RAM の上限を超えた書き込みのトラップは上限を添え、`memory.grow` を RAM の上限で断った後の
     * ほかのトラップには、メモリ不足が原因かもしれないと添える（#407）。`kibi:computer/memory` の `commit` を断った後も同じ（#408）。
     * インスタンスを作る間に RAM の上限で確保を断られて作れなかったときも、メモリ不足と書く（#48。ADR 0048）。
     * サーバー全体の勘定で断ったときは、RAM の上限ではなくサーバー全体の上限に当たったと書く（#48）
     */
    private fun processCrashReason(rt: ProcessRuntime, t: Throwable): String {
        val limit = memoryLimitText(rt)
        if (t is WasmTrapException && t.kind == WasmTrapKind.OUT_OF_MEMORY) return outOfMemoryReason(limit)
        if (t is WasmLinkException && instantiateRefusedMemory(rt)) return instantiateOutOfMemoryReason(limit)
        val reason = crashReason(t)
        val note = refusedMemoryNote(rt)
        return if (note != null) "$reason ($note)" else reason
    }

    /**
     * メモリ不足の文の、当たった上限の部分（[memoryLimitText]。プロセス全体で最後に断った段で決める。#48）。
     * 断った直後に同じスレッドで読む所（OUT_OF_MEMORY のトラップ、インスタンスを作れなかったとき）だけで使う
     */
    private fun memoryLimitText(rt: ProcessRuntime): String = memoryLimitText(rt, rt.memory.lastRefusal)

    /** [refusal] の段で断ったときの、メモリ不足の文の当たった上限の部分（[memoryLimitText]。#48） */
    private fun memoryLimitText(rt: ProcessRuntime, refusal: MemoryRefusal?): String =
        memoryLimitText(refusal == MemoryRefusal.SERVER, rt.memory.effectiveLimitBytes)

    /**
     * `memory.grow`・`kibi:computer/memory` の `commit` を RAM の上限で断っていたら、そう書いた文（#407・#408）。なければ `null`。
     * 当たった上限は、それぞれを断った段で書く（後の別の確保で `ProcessMemory.lastRefusal` が変わっていても。#48）
     */
    private fun refusedMemoryNote(rt: ProcessRuntime): String? {
        val grow = rt.memoryGrowRefusal?.let { memoryLimitText(rt, it) }
        val commit = rt.memoryCommitRefusal?.let { memoryLimitText(rt, it) }
        return memoryRefusedNote(grow, commit)
    }

    /**
     * インスタンスを作れなかった（[WasmLinkException]）のが、RAM の上限で確保を断られたせいか（#48）。
     * どの経路（[load]・[loadComponent]・[ProcessContext.instantiate]）も、失敗した core インスタンスを作る前後で断った回数が
     * 増えたかで決める（[ProcessRuntime.instantiateRefusedMemory]）。コンポーネントは core インスタンスを順に作り、先に作ったものの
     * start 関数が断ることがあるので、「それまでに一度でも断ったか」では見ない。
     * RAM と関係のない失敗（import がない、型が違う、start 関数のトラップなど）には付けない
     */
    private fun instantiateRefusedMemory(rt: ProcessRuntime): Boolean = rt.instantiateRefusedMemory

    /** プロセスを終える（待ちを外し、インスタンスを捨てる）。カーネルロックの中で呼ぶ */
    fun finishLocked(rt: ProcessRuntime, state: ProcessState, exitCode: Int?) {
        val process = rt.process
        if (process.state.isTerminal) return
        process.state = state
        process.exitCode = exitCode
        process.wait = null
        rt.waitSet?.unregister(machine.registry)
        rt.waitSet = null
        rt.stackActive = false
        rt.loading = null
        for (instance in rt.instances) {
            try {
                instance.close()
            } catch (_: Throwable) {
            }
        }
        rt.instances.clear()
        rt.component = null
        rt.fuelHolder = null
        // コンポーネントの core インスタンスの固定費を返す（#270。数えるのも返すのもカーネルロックの中なので、1 回だけ返る）
        if (rt.coreInstanceOverheadBytes > 0) {
            rt.memory.releaseBytes(rt.coreInstanceOverheadBytes)
            rt.coreInstanceOverheadBytes = 0
        }
        // fd をすべて閉じる（ゾンビは fd を持たない。#58）
        FdTable(process, machine.kernel.objects, machine.wakeSink).closeAll()
        // ホストの resource（p2 の stream・descriptor など）の参照を返す（#267。ゲストの表の dtor は呼ばない。ADR 0265）
        process.hostResources.closeAll(machine.kernel.objects, machine.wakeSink)
        // 一時的なタイマーを閉じる（持ち主は process.waitTimers だけ。待機点型で kill されたときなど。#329）。
        // 後から来た closeWaitTimer は集合にないので何もしない（同じ ID が別のオブジェクトに使い回されていても閉じない）
        process.waitTimers.forEach { machine.kernel.objects.release(it, machine.wakeSink) }
        process.waitTimers.clear()
        // 終わりを待っているホスト側の処理を閉じる（まだ列にあるホスト呼び出しは下で取り消す。読み込みは待つのをやめる。#358）
        if (rt.hostWaits.isNotEmpty()) {
            val waits = rt.hostWaits.toList()
            rt.hostWaits.clear()
            for ((id, pending) in waits) {
                machine.kernel.objects.release(id, machine.wakeSink)
                if (!pending.future.isDone) pending.abandon?.invoke()
            }
        }
        process.asyncWait = null
        process.asyncStack?.clear()
        if (process.kind == ProcessKind.BLOCKING) scheduler.releaseBlocking()
        // まだ実行していないホスト呼び出しを取り消す（ふつうは 0 件。#46）
        machine.cancelHostCallsLocked(process.pid)
        // 終わりを記録し、親へ知らせ、子を付け替える（#46）
        machine.lifecycle.onFinishedLocked(rt)
        // init なら、マシンを止める（#49）
        // 0 以外の終了コードで終わったプロセスが、その前に memory.grow・kibi:computer/memory の commit を RAM の上限で断られていたら、そう添える（#407・#408）
        val exitNote = if (state == ProcessState.EXITED && exitCode != 0) refusedMemoryNote(rt) else null
        machine.onProcessFinishedLocked(process, rt.crashReason, exitNote)
        machine.checkAllDoneLocked()
    }

    // ---- ProcessContext ----

    /** プロセスごとの口。状態は持たない（一時的なタイマーの持ち主は [Process.waitTimers]。#329） */
    private inner class Context(private val rt: ProcessRuntime) : ProcessContext {
        override val pid: Int get() = rt.pid

        override val parentPid: Int get() = rt.process.parentPid

        override val args: List<String> get() = rt.process.args

        override val env: Map<String, String> get() = rt.process.env

        override val cwd: VfsPath get() = lock.withLockValue { rt.process.cwd }

        override fun changeDirectory(path: String) {
            // cwd を変えるのはこのプロセスのスレッドだけ。確かめ（VFS）はロックの外（#52）
            val target = VfsPath.resolve(lock.withLockValue { rt.process.cwd }, path)
            if (machine.vfs.stat(target).type != FileType.DIRECTORY) fsError(Errno.NOTDIR, "$target はディレクトリではない")
            lock.withLockValue { rt.process.cwd = target }
        }

        override fun mount(source: String, point: String, readOnly: Boolean) {
            // ホスト側の仕事（ディスクを開いて木をたどる・リースを取る）は、ふつうの呼び出しより桁違いに重い。固定分を先に、
            // 木の大きさに比例する分を開いた後に払う（unmount → mount を繰り返してワーカーとホストの I/O を使い続けられない。#56、ADR 0056）
            chargeFuel(MachineLimits.MOUNT_FUEL)
            val target = VfsPath.resolve(lock.withLockValue { rt.process.cwd }, point)
            val entries = machine.mountDisk(source, target, readOnly)
            chargeFuel(entries * MachineLimits.MOUNT_FUEL_PER_ENTRY)
        }

        override fun unmount(point: String) {
            machine.unmountAt(VfsPath.resolve(lock.withLockValue { rt.process.cwd }, point))
        }

        override fun listMounts(): List<MountListing> = machine.listMounts()

        override fun listDisks(): List<DiskListing> = machine.listDisks()

        override fun spawn(program: Program, driver: ProcessDriver, options: SpawnOptions): Int =
            machine.spawn(program, driver, options, rt.pid)

        override fun exec(path: String, drivers: ProcessDriverFactory, options: SpawnOptions): Int {
            checkMayBlock("exec")
            // 読み込み（コンパイル）を待つ間は、CPU と許可を返す（#37。ADR 0029）
            return machine.exec(path, drivers, options, rt.pid) { future -> awaitHost(future) }
        }

        override fun beginExec(path: String, drivers: ProcessDriverFactory, options: SpawnOptions): ExecWait {
            checkThread()
            val pending = machine.beginExec(path, drivers, options, rt.pid)
            val wait = try {
                watch(pending.future) { pending.future.cancel(false) }
            } catch (t: Throwable) {
                pending.future.cancel(false)
                throw t
            }
            return ExecWait(wait, pending)
        }

        override fun finishExec(wait: ExecWait): Int {
            checkThread()
            // 読み込みの失敗は、machine.finishExec が errno にする
            lock.withLockValue { takeLocked(wait.wait) }
            return machine.finishExec(wait.pending)
        }

        override fun execWithHandle(path: String, drivers: ProcessDriverFactory, options: SpawnOptions): ProcessHandle {
            checkMayBlock("exec")
            return machine.execWithHandle(path, drivers, options, rt.pid, await = { future -> awaitHost(future) })
        }

        override fun finishExecWithHandle(wait: ExecWait): ProcessHandle {
            checkThread()
            lock.withLockValue { takeLocked(wait.wait) }
            return machine.finishExecWithHandle(wait.pending)
        }

        override fun checkHandleRoom() = machine.checkHandleRoom(rt.pid)

        override fun finishExecWithHandle(wait: ExecWait, place: HandlePlacement): ProcessHandle {
            checkThread()
            lock.withLockValue { takeLocked(wait.wait) }
            return machine.finishExecWithHandle(wait.pending, place)
        }

        override fun checkHandleRoom(place: HandlePlacement) = machine.checkHandleRoom(rt.pid, place)

        override fun processHandleStatus(fd: Int): ExitStatus? =
            lock.withLockValue { machine.lifecycle.processHandleStatusLocked(rt.process, processObjectLocked(fd)) }

        override fun killProcessHandle(fd: Int, signal: KillSignal): KillResult {
            val (target, result) = lock.withLockValue {
                val obj = processObjectLocked(fd)
                obj.pid to machine.lifecycle.killProcessHandleLocked(obj, signal)
            }
            if (target == rt.pid && result == KillResult.DELIVERED) throw ProcessKilledException()
            return result
        }

        override fun processHandlePid(fd: Int): Int = lock.withLockValue { processObjectLocked(fd).pid }

        override fun execWithHandle(path: String, drivers: ProcessDriverFactory, options: SpawnOptions, place: HandlePlacement): ProcessHandle {
            checkMayBlock("exec")
            return machine.execWithHandle(path, drivers, options, rt.pid, { future -> awaitHost(future) }, place)
        }

        override fun processHandleStatus(handle: HandleEntry): ExitStatus? =
            lock.withLockValue { machine.lifecycle.processHandleStatusLocked(rt.process, processObjectOfLocked(handle)) }

        override fun killProcessHandle(handle: HandleEntry, signal: KillSignal): KillResult {
            val (target, result) = lock.withLockValue {
                val obj = processObjectOfLocked(handle)
                obj.pid to machine.lifecycle.killProcessHandleLocked(obj, signal)
            }
            if (target == rt.pid && result == KillResult.DELIVERED) throw ProcessKilledException()
            return result
        }

        override fun processHandlePid(handle: HandleEntry): Int = lock.withLockValue { processObjectOfLocked(handle).pid }

        /** 解決済みのハンドル [h] の子のハンドル。子のハンドルでなければ `BADF` */
        private fun processObjectOfLocked(h: HandleEntry): ProcessObject =
            machine.kernel.objects[h.objectId] as? ProcessObject ?: fsError(Errno.BADF, "ハンドル（オブジェクト ${h.objectId}）は子のハンドルではない")

        /** 自分の fd [fd] が指す子のハンドル（カーネルロックの中）。開いていない・子のハンドルでなければ `BADF` */
        private fun processObjectLocked(fd: Int): ProcessObject =
            FdTable(rt.process, machine.kernel.objects, machine.wakeSink).objectOf(fd) as? ProcessObject
                ?: fsError(Errno.BADF, "fd $fd は子のハンドルではない")

        override fun children(): List<Int> = machine.children(rt.pid)

        override fun tryReap(selector: ChildSelector): ReapResult = lock.withLockValue { machine.lifecycle.tryReapLocked(rt.pid, selector) }

        override fun childExitKeys(selector: ChildSelector): List<WaitKey> =
            lock.withLockValue { machine.lifecycle.childExitKeysLocked(rt.pid, selector) }

        override fun waitChild(selector: ChildSelector): ReapResult {
            checkThread()
            while (true) {
                val (result, keys) = lock.withLockValue {
                    val r = machine.lifecycle.tryReapLocked(rt.pid, selector)
                    r to (if (r == ReapResult.Running) machine.lifecycle.childExitKeysLocked(rt.pid, selector) else emptyList())
                }
                if (result != ReapResult.Running) return result
                // 確かめてから block で登録するまでに終わっても、条件は残る（EXITED・CHILD_EXITED）ので取りこぼさない
                block(keys)
            }
        }

        override fun kill(pid: Int, signal: KillSignal): KillResult {
            val result = lock.withLockValue { machine.killLocked(pid, signal) }
            if (pid == rt.pid && result == KillResult.DELIVERED) throw ProcessKilledException()
            return result
        }

        override fun setSignalIgnored(signal: KillSignal, ignored: Boolean) {
            require(signal.ignorable) { "$signal は無視できない" }
            lock.withLockValue {
                if (ignored) rt.process.ignoredSignals.add(signal) else rt.process.ignoredSignals.remove(signal)
            }
        }

        override fun openEventQueue(filter: EventFilter, capacity: Int): Int =
            lock.withLockValue { machine.openEventQueueLocked(rt.pid, filter, capacity) }

        override fun openEventQueue(filter: EventFilter, capacity: Int, place: HandlePlacement): Int =
            lock.withLockValue { machine.openEventQueueLocked(rt.pid, filter, capacity, place) }

        override fun takeEvents(queue: HandleEntry, max: Int): EventBatch {
            if (max < 1) fsError(Errno.INVAL, "取り出す数は 1 以上（$max）")
            return lock.withLockValue { eventQueueOfLocked(queue).take(max) }
        }

        /** 解決済みのハンドル [h] のイベントキュー。読む権利がない・キューでなければ `BADF` */
        private fun eventQueueOfLocked(h: HandleEntry): EventQueue {
            if (h.rights and HandleRights.READ == 0) fsError(Errno.BADF, "ハンドル（オブジェクト ${h.objectId}）は読めない")
            return machine.kernel.objects[h.objectId] as? EventQueue ?: fsError(Errno.BADF, "ハンドル（オブジェクト ${h.objectId}）はイベントキューではない")
        }

        /** 自分の fd 表に置く（p1） */
        private fun fdPlacement(): HandlePlacement = FdPlacement(rt.process, machine.kernel.objects, machine.wakeSink)

        override fun takeEvents(handle: Int, max: Int): EventBatch {
            if (max < 1) fsError(Errno.INVAL, "取り出す数は 1 以上（$max）")
            return lock.withLockValue { eventQueueLocked(handle).take(max) }
        }

        override fun eventWaitKey(handle: Int): WaitKey = lock.withLockValue { WaitKey(eventQueueLocked(handle).id, WaitCondition.READABLE) }

        override fun awaitEvents(handle: Int, max: Int): EventBatch {
            checkThread()
            if (max < 1) fsError(Errno.INVAL, "取り出す数は 1 以上（$max）")
            while (true) {
                val (batch, key) = lock.withLockValue {
                    val q = eventQueueLocked(handle)
                    q.take(max) to WaitKey(q.id, WaitCondition.READABLE)
                }
                if (!batch.isEmpty) return batch
                // 確かめてから block で登録するまでに届いても、READABLE は残るので取りこぼさない
                block(listOf(key))
            }
        }

        private fun eventQueueLocked(handle: Int): EventQueue {
            val table = FdTable(rt.process, machine.kernel.objects, machine.wakeSink)
            val entry = table.entry(handle)
            if (entry.rights and HandleRights.READ == 0) fsError(Errno.BADF, "fd $handle は読めない")
            return table.objectOf(handle) as? EventQueue ?: fsError(Errno.BADF, "fd $handle はイベントキューではない")
        }

        override fun monotonicNanos(): Long = machine.monotonicNanos()

        override fun uptimeTicks(): Long = machine.uptimeTicks()

        override fun createTimer(eventQueue: Int?, tag: Long): Int = lock.withLockValue {
            createTimerLocked(eventQueue?.let { eventQueueLocked(it) }, tag, fdPlacement())
        }

        override fun createTimer(eventQueue: HandleEntry?, tag: Long, place: HandlePlacement): Int = lock.withLockValue {
            createTimerLocked(eventQueue?.let { eventQueueOfLocked(it) }, tag, place)
        }

        /** タイマーを作って [place] に置く（[q] はイベントを送るキュー）。キューが `timer` を受け取らなければ `INVAL` */
        private fun createTimerLocked(q: EventQueue?, tag: Long, place: HandlePlacement): Int {
            if (q != null && !q.filter.accepts(TIMER_EVENT_PROBE)) fsError(Errno.INVAL, "キュー ${q.id} は ${TimerLimits.EVENT_TYPE} を受け取らない")
            return place.openLocked(machine.kernel.objects, machine.wakeSink, Timer(q?.id ?: 0, tag), HandleRights.READ)
        }

        override fun setTimer(timer: HandleEntry, time: Long, intervalNanos: Long, absolute: Boolean) = lock.withLockValue {
            val t = timerOfLocked(timer)
            val deadline = if (absolute) time else saturatingAdd(machine.monotonicNanosLocked(), time.coerceAtLeast(0))
            machine.armTimerLocked(t, deadline, intervalNanos)
        }

        override fun cancelTimer(timer: HandleEntry) = lock.withLockValue { machine.disarmTimerLocked(timerOfLocked(timer)) }

        override fun readTimer(timer: HandleEntry): Long = lock.withLockValue { machine.readTimerLocked(timerOfLocked(timer)) }

        /** 解決済みのハンドル [h] のタイマー。タイマーでなければ `BADF` */
        private fun timerOfLocked(h: HandleEntry): Timer =
            machine.kernel.objects[h.objectId] as? Timer ?: fsError(Errno.BADF, "ハンドル（オブジェクト ${h.objectId}）はタイマーではない")

        override fun setTimer(handle: Int, time: Long, intervalNanos: Long, absolute: Boolean) = lock.withLockValue {
            val timer = timerLocked(handle)
            val deadline = if (absolute) time else saturatingAdd(machine.monotonicNanosLocked(), time.coerceAtLeast(0))
            machine.armTimerLocked(timer, deadline, intervalNanos)
        }

        override fun cancelTimer(handle: Int) = lock.withLockValue { machine.disarmTimerLocked(timerLocked(handle)) }

        override fun readTimer(handle: Int): Long = lock.withLockValue { machine.readTimerLocked(timerLocked(handle)) }

        override fun timerWaitKey(handle: Int): WaitKey = lock.withLockValue { WaitKey(timerLocked(handle).id, WaitCondition.READABLE) }

        override fun openWaitTimer(deadlineNanos: Long): WaitKey = lock.withLockValue {
            val objects = machine.kernel.objects
            val timer = Timer()
            val id = objects.add(timer)
            try {
                machine.armTimerLocked(timer, deadlineNanos, 0)
            } catch (t: Throwable) {
                objects.release(id, machine.wakeSink)
                throw t
            }
            rt.process.waitTimers.add(id)
            WaitKey(id, WaitCondition.READABLE)
        }

        override fun resetWaitTimer(key: WaitKey, deadlineNanos: Long) = lock.withLockValue {
            require(rt.process.waitTimers.contains(key.objectId)) { "一時的なタイマーではない: $key" }
            val timer = machine.kernel.objects[key.objectId] as Timer
            machine.armTimerLocked(timer, deadlineNanos, 0)
        }

        override fun closeWaitTimer(key: WaitKey) = lock.withLockValue {
            if (!rt.process.waitTimers.remove(key.objectId)) return@withLockValue
            machine.kernel.objects.release(key.objectId, machine.wakeSink)
        }

        override fun sleep(nanos: Long) {
            checkThread()
            if (nanos <= 0) return
            val key = openWaitTimer(saturatingAdd(monotonicNanos(), nanos))
            try {
                block(listOf(key))
            } finally {
                closeWaitTimer(key)
            }
        }

        private fun timerLocked(handle: Int): Timer {
            val table = FdTable(rt.process, machine.kernel.objects, machine.wakeSink)
            table.entry(handle)
            return table.objectOf(handle) as? Timer ?: fsError(Errno.BADF, "fd $handle はタイマーではない")
        }

        override val instance: WasmInstance
            get() = lock.withLockValue { rt.firstInstance ?: throw IllegalStateException("インスタンスはまだない（LOADING）") }

        override val fuelHolder: WasmInstance
            get() = lock.withLockValue { rt.fuelHolder ?: throw IllegalStateException("インスタンスはまだない（LOADING）") }

        override val component: LinkedComponent
            get() {
                checkThread()
                return rt.component ?: throw IllegalStateException(
                    if (rt.program.component == null) "コンポーネントのプロセスでない" else "コンポーネントはまだない（LOADING）",
                )
            }

        override val kernel: KernelState get() = machine.kernel

        override val vfs: Vfs get() = machine.vfs

        override val wakeSink: WakeSink get() = machine.wakeSink

        override fun <T> withKernelLock(block: () -> T): T = lock.withLockValue(block)

        private fun checkThread() {
            check(Thread.currentThread() === rt.thread) { "プロセス ${rt.pid} のスレッドから呼ぶ" }
        }

        override fun hostFunction(f: KernelHostFunction): WasmHostFunction = WasmHostFunction { caller, args ->
            // ゲストのどの深さからでも入るので、ロックを取る前にスタックの余裕を確かめる（#828）
            StackHeadroom.ensure(StackHeadroom.HOST_CALL_FRAMES)
            lock.withLockValue {
                if (rt.killed) throw ProcessKilledException()
                verifyInstanceLocked(rt, caller)
            }
            f.call(this, caller, args)
        }

        override fun instantiate(module: WasmModule, configurer: InstanceConfigurer): WasmInstance {
            checkThread()
            lock.withLockValue {
                check(rt.loading == null) { "作っている途中は instantiate を入れ子で呼べない" }
                check(rt.program.component == null) { "コンポーネントのプロセスは instantiate を呼べない（core インスタンスはリンクの計画が作る）" }
                check(rt.fuelHolder != null) { "最初のインスタンスがまだない" }
            }
            require(module.isFuelMetered == !rt.process.unmetered) { "燃料の計測の有無がプロセスと違うモジュール" }
            require(!module.isAsyncified) { "待機点への変換をかけたモジュールは、最初のインスタンスにしかできない" }
            val builder = WasmInstanceConfig.builder()
            configurer.configure(builder)
            applyKernelLimits(rt, builder)
            val r = lock.withLockValue {
                if (rt.killed) throw ProcessKilledException()
                val holder = rt.fuelHolder!!
                rt.loading = LoadingFrame(null, holder)
                if (rt.process.unmetered) {
                    0L
                } else {
                    val v = holder.fuel
                    holder.fuel = 0
                    v
                }
            }
            if (!rt.process.unmetered) builder.fuel(r, rt.fuelHandler)
            val refusalsBefore = rt.memory.refusalCount
            rt.instantiateRefusedMemory = false
            val created = try {
                module.instantiate(builder.build())
            } catch (t: Throwable) {
                // CPU と許可は返さない。燃料は捨てる（元の holder は 0 のまま）
                lock.withLockValue { rt.loading = null }
                // 作る間に RAM の上限で断られたなら、その後に異常終了したときにメモリ不足と書く（#48）
                if (t is WasmLinkException && rt.memory.refusalCount != refusalsBefore) rt.instantiateRefusedMemory = true
                throw t
            }
            lock.withLockValue {
                val frame = rt.loading!!
                rt.loading = null
                val provisional = frame.provisional
                if (provisional != null && provisional !== created) {
                    created.close()
                    throw machine.inconsistencyLocked("作り終えたインスタンスが仮の holder と違う（pid ${rt.pid}）")
                }
                if (!rt.process.unmetered) {
                    val rest = created.fuel
                    created.fuel = 0
                    frame.restoreTo!!.fuel = rest
                }
                rt.instances += created
            }
            return created
        }

        override fun block(keys: List<WaitKey>): List<WaitKey> {
            checkThread()
            checkNotInGuestCall("block")
            require(keys.isNotEmpty()) { "待つ条件がない" }
            val waiter = ThreadWaiter(lock.newCondition())
            var previous = ProcessState.RUNNING
            val ws: WaitSet
            // CPU・許可を返して取り直す深い道に入る前に、状態を何も変えないうちに確かめる（#828。燃料切れの処理と同じ）
            StackHeadroom.ensure(StackHeadroom.FUEL_YIELD_FRAMES)
            lock.lock()
            try {
                check(rt.process.kind == ProcessKind.BLOCKING || rt.loading != null) { "待機点型は StepResult.Wait で待つ" }
                if (rt.killed) throw ProcessKilledException()
                ws = WaitSet.register(machine.registry, machine.kernel.objects, keys, waiter)
                val ready = ws.readyKeys(machine.kernel.objects)
                if (ready.isNotEmpty()) {
                    ws.unregister(machine.registry)
                    return ready
                }
                releaseFuelLocked(rt)
                rt.waitSet = ws
                rt.process.wait = ws.keys
                previous = rt.process.state
                rt.process.state = ProcessState.WAITING_EVENT
            } finally {
                lock.unlock()
            }
            releasePermitAndCpu(rt)
            try {
                while (true) {
                    lock.lockInterruptibly()
                    try {
                        while (!waiter.woken) {
                            if (rt.killed) throw ProcessKilledException()
                            waiter.condition.await()
                        }
                        if (ws.readyKeys(machine.kernel.objects).isEmpty()) {
                            // 起こされたのに空: CPU・許可を取らずに待ち直す
                            ws.rearm()
                            continue
                        }
                    } finally {
                        lock.unlock()
                    }
                    var ready: List<WaitKey> = emptyList()
                    val grant = acquireCpu(rt, precheck = {
                        ready = ws.readyKeys(machine.kernel.objects)
                        if (ready.isEmpty()) {
                            ws.rearm()
                            false
                        } else {
                            true
                        }
                    }) ?: continue
                    lock.withLockValue {
                        ws.unregister(machine.registry)
                        rt.waitSet = null
                        rt.process.wait = null
                        rt.process.state = previous
                        setHolderFuelLocked(rt, grant)
                    }
                    return ready
                }
            } catch (e: InterruptedException) {
                throw ProcessKilledException(e)
            }
        }

        override fun <T> awaitHost(future: CompletableFuture<T>): T {
            checkMayBlock("awaitHost")
            // CPU・許可を返して取り直す深い道に入る前に、状態を何も変えないうちに確かめる（#828。燃料切れの処理と同じ）
            StackHeadroom.ensure(StackHeadroom.FUEL_YIELD_FRAMES)
            var previous = ProcessState.RUNNING
            lock.withLockValue {
                if (rt.killed) throw ProcessKilledException()
                releaseFuelLocked(rt)
                previous = rt.process.state
                rt.process.state = ProcessState.WAITING_HOST
            }
            releasePermitAndCpu(rt)
            var failure: Throwable? = null
            var value: T? = null
            try {
                value = future.get()
            } catch (e: InterruptedException) {
                throw ProcessKilledException(e)
            } catch (e: ExecutionException) {
                failure = e.cause ?: e
            }
            val grant = acquireCpu(rt, null)!!
            lock.withLockValue {
                rt.process.state = previous
                setHolderFuelLocked(rt, grant)
            }
            failure?.let { throw it }
            @Suppress("UNCHECKED_CAST")
            return value as T
        }

        override val environment: MachineEnvironment get() = machine.environment

        override fun postHostCallback(callback: Runnable) = machine.postHostCallback(callback)

        override fun <T> watchHost(future: CompletableFuture<T>): HostWait<T> {
            checkThread()
            return watch(future, null)
        }

        override fun <T> submitHostCall(call: HostCall<T>): HostWait<T> {
            checkThread()
            // 止められていれば、submitHostCall が積まずに ProcessKilledException で終えた future を返す（watch が投げる）
            val future = machine.submitHostCall(rt.pid, call)
            return watch(future) { machine.cancelHostCallLocked(future) }
        }

        /**
         * [future] の終わりを待つ [HostCompletion] を作る。[abandon] は、結果を受け取らずに閉じるときに（カーネルロックの中で）呼ぶ。
         * 終わったら、終えたスレッドでカーネルロックを取り直し、印を付けて待ち手を起こす
         */
        private fun <T> watch(future: CompletableFuture<T>, abandon: (() -> Unit)?): HostWait<T> {
            val key = lock.withLockValue {
                if (rt.killed || rt.process.state.isTerminal) {
                    abandon?.invoke()
                    throw ProcessKilledException()
                }
                val id = machine.kernel.objects.add(HostCompletion())
                rt.hostWaits[id] = PendingHostWait(future, abandon)
                WaitKey(id, WaitCondition.READABLE)
            }
            future.whenComplete { _, _ ->
                lock.withLockValue {
                    // 閉じた後（プロセスの終わり・closeHostWait）なら何もしない
                    if (rt.hostWaits[key.objectId]?.future !== future) return@withLockValue
                    val obj = machine.kernel.objects[key.objectId] as HostCompletion
                    obj.done = true
                    machine.wakeSink.wake(key)
                }
            }
            return HostWait(key, future)
        }

        /** 終わった [wait] を表から外して閉じる。カーネルロックの中で呼ぶ */
        private fun takeLocked(wait: HostWait<*>) {
            if (rt.killed) throw ProcessKilledException()
            val id = wait.key.objectId
            require(rt.hostWaits[id]?.future === wait.future) { "このプロセスの開いている待ちではない: ${wait.key}" }
            check(wait.future.isDone) { "まだ終わっていない: ${wait.key}" }
            rt.hostWaits.remove(id)
            machine.kernel.objects.release(id, machine.wakeSink)
        }

        override fun <T> takeHostResult(wait: HostWait<T>): T {
            lock.withLockValue { takeLocked(wait) }
            return try {
                wait.future.getNow(null)
            } catch (e: CompletionException) {
                throw e.cause ?: e
            } catch (e: CancellationException) {
                throw ProcessKilledException(e)
            }
        }

        override fun closeHostWait(wait: HostWait<*>) = lock.withLockValue {
            val id = wait.key.objectId
            val pending = rt.hostWaits[id]
            if (pending?.future !== wait.future) return@withLockValue
            rt.hostWaits.remove(id)
            machine.kernel.objects.release(id, machine.wakeSink)
            if (!wait.future.isDone) pending.abandon?.invoke()
        }

        /**
         * ホスト関数の中から呼んだゲスト（[callGuest]。`cabi_realloc`）の中では、止まって待つ口を使えない（トラップ。ADR 0073。#430）。
         * ホスト関数の途中は巻き戻せず、ブロッキング型でも呼び出し元のホスト関数の途中で止まることになるため。
         * ABI の層が入口で弾き忘れた import も、IllegalStateException ではなくトラップで終わる。プロセスのスレッドだけが読み書きするのでロックは要らない
         */
        private fun checkNotInGuestCall(name: String) {
            if (rt.guestCallDepth > 0) throw WasmTrapException(WasmTrapKind.OTHER, "止まりうるホストの処理 $name を cabi_realloc の中で呼んだ")
        }

        /** 止まって待つ口（block を除く）は、ブロッキング型か、作っている途中だけ（待機点型が step の途中でスレッドを持ったまま止まらない。#358） */
        private fun checkMayBlock(name: String) {
            checkThread()
            checkNotInGuestCall(name)
            lock.withLockValue {
                check(rt.process.kind == ProcessKind.BLOCKING || rt.loading != null) { "待機点型は $name で止まらず、StepResult.Wait で待つ" }
            }
        }

        override fun <T> callOnMainThread(call: HostCall<T>): T {
            checkMayBlock("callOnMainThread")
            // 止められたプロセスは、メインスレッドでの処理を出さない（マシンが STOPPED に戻った後でも）。
            // 止められたかの確かめと列への積み込みは、submitHostCall の同じロックの中で行う（#46）
            return awaitHost(machine.submitHostCall(rt.pid, call))
        }

        override fun writeConsole(bytes: ByteArray, offset: Int, length: Int): Boolean = machine.writeConsole(bytes, offset, length)

        override fun listProcesses(): List<ProcessListing> = lock.withLockValue { machine.lifecycle.listProcessesLocked() }

        override fun listDevices(): List<DeviceInfo> = machine.devices.list()

        override fun openDevice(address: String): Int = openDevice(address, fdPlacement())

        override fun openDevice(address: String, place: HandlePlacement): Int {
            checkThread()
            val attached = machine.devices.find(address) ?: throw DeviceCallException(CallError.Detached)
            return lock.withLockValue {
                if (rt.killed) throw ProcessKilledException()
                place.openLocked(machine.kernel.objects, machine.wakeSink, DeviceHandle(attached.address, attached.kind), HandleRights.READ)
            }
        }

        override fun deviceInfo(device: HandleEntry): DeviceInfo = lock.withLockValue { deviceHandleOfLocked(device) }.info

        override fun deviceMethods(device: HandleEntry): List<MethodInfo> {
            val handle = lock.withLockValue { deviceHandleOfLocked(device) }
            return machine.devices.dispatcher.resolve(handle.address, handle.kind)?.methodInfos ?: emptyList()
        }

        override fun invokeDevice(device: HandleEntry, method: String, args: List<DeviceValue>): List<DeviceValue> {
            checkThread()
            val handle = lock.withLockValue { deviceHandleOfLocked(device) }
            return invokeResolved(machine.devices.dispatcher.resolve(handle.address, handle.kind), method, args)
        }

        override fun startDeviceCall(device: HandleEntry, method: String, args: List<DeviceValue>, place: HandlePlacement): Int {
            checkThread()
            val (slot, obj, kind) = lock.withLockValue {
                if (rt.killed) throw ProcessKilledException()
                val handle = deviceHandleOfLocked(device)
                val (slot, obj) = machine.openPendingDeviceCallLocked(
                    rt.pid,
                    PendingDeviceCall.State.Queued(handle.address, method, args.toList()),
                    place = place,
                )
                Triple(slot, obj, handle.kind)
            }
            payDeviceFuel(machine.dispatchPendingDeviceCall(rt.pid, obj, kind))
            return slot
        }

        override fun takeDeviceCall(call: HandleEntry): CallResult? {
            checkThread()
            val result = lock.withLockValue {
                val obj = machine.kernel.objects[call.objectId] as? PendingDeviceCall
                    ?: fsError(Errno.BADF, "ハンドル（オブジェクト ${call.objectId}）は pending-call でない")
                obj.result
            }
            if (result is CallResult.Values) payDeviceFuel(DeviceLimits.payloadBytes(result.values) * DeviceLimits.FUEL_PER_BYTE)
            return result
        }

        /** 解決済みのハンドル [h] の device。device でなければ `BADF` */
        private fun deviceHandleOfLocked(h: HandleEntry): DeviceHandle =
            machine.kernel.objects[h.objectId] as? DeviceHandle ?: fsError(Errno.BADF, "ハンドル（オブジェクト ${h.objectId}）は device でない")

        override fun invokeDevice(address: String, method: String, args: List<DeviceValue>): List<DeviceValue> {
            checkThread()
            return invokeResolved(machine.devices.dispatcher.resolve(address), method, args)
        }

        override fun invokeDevice(fd: Int, method: String, args: List<DeviceValue>): List<DeviceValue> {
            checkThread()
            val handle = deviceHandleOf(fd)
            return invokeResolved(machine.devices.dispatcher.resolve(handle.address, handle.kind), method, args)
        }

        /** 引いたデバイス [device]（`null` = 外れている）のメソッドを呼んで待つ。デバイスは1回だけ引いて、確かめと実行に同じものを使う */
        private fun invokeResolved(device: AttachedDevice?, method: String, args: List<DeviceValue>): List<DeviceValue> {
            // メインスレッドのメソッドを待てるのはブロッキング型だけ（積む前に確かめる）
            if (device?.methods?.find(method)?.mainThread == true) checkMayBlock("invokeDevice")
            val started = machine.devices.dispatcher.start(rt.pid, device, method, args)
            payDeviceFuel(started.fuel)
            val future = started.future
            if (!future.isDone) {
                try {
                    awaitHost(future)
                } catch (e: ProcessKilledException) {
                    // 待っていたプロセスが止められた。結果はもう要らない（後で返す呼び出しは isCancelled が立つ。#532）
                    lock.withLockValue { started.cancelLocked() }
                    throw e
                } catch (e: Exception) {
                    // 結果は下で future から読む
                }
            }
            return when (val result = DeviceDispatcher.resultOf(future)) {
                is CallResult.Values -> {
                    payDeviceFuel(DeviceLimits.payloadBytes(result.values) * DeviceLimits.FUEL_PER_BYTE)
                    result.values
                }
                is CallResult.Error -> throw DeviceCallException(result.error)
            }
        }

        override fun startDeviceCall(address: String, method: String, args: List<DeviceValue>): Int {
            checkThread()
            val (fd, obj) = lock.withLockValue {
                if (rt.killed) throw ProcessKilledException()
                machine.openPendingDeviceCallLocked(rt.pid, PendingDeviceCall.State.Queued(address, method, args.toList()))
            }
            payDeviceFuel(machine.dispatchPendingDeviceCall(rt.pid, obj))
            return fd
        }

        override fun startDeviceCall(fd: Int, method: String, args: List<DeviceValue>): Int {
            checkThread()
            val (callFd, obj, kind) = lock.withLockValue {
                if (rt.killed) throw ProcessKilledException()
                val handle = deviceHandleOfLocked(fd)
                val (callFd, obj) = machine.openPendingDeviceCallLocked(rt.pid, PendingDeviceCall.State.Queued(handle.address, method, args.toList()))
                Triple(callFd, obj, handle.kind)
            }
            payDeviceFuel(machine.dispatchPendingDeviceCall(rt.pid, obj, kind))
            return callFd
        }

        override fun beginDeviceInvoke(fd: Int, method: String, args: List<DeviceValue>, mayWait: Boolean): DeviceInvokeStart {
            checkThread()
            return beginDeviceInvoke(deviceHandleOf(fd), method, args, mayWait, null)
        }

        override fun beginDeviceInvoke(
            device: HandleEntry,
            method: String,
            args: List<DeviceValue>,
            mayWait: Boolean,
            place: HandlePlacement,
        ): DeviceInvokeStart {
            checkThread()
            return beginDeviceInvoke(lock.withLockValue { deviceHandleOfLocked(device) }, method, args, mayWait, place)
        }

        /** [beginDeviceInvoke] の本体。待つときの `pending-call` は [place]（`null` なら fd 表）に置く */
        private fun beginDeviceInvoke(
            handle: DeviceHandle,
            method: String,
            args: List<DeviceValue>,
            mayWait: Boolean,
            place: HandlePlacement?,
        ): DeviceInvokeStart {
            // デバイスを引くのはここの1回だけ（ADR 0066）。確かめと実行に同じものを使う
            val device = machine.devices.dispatcher.resolve(handle.address, handle.kind)
            hooks.afterDeviceInvokeResolve?.invoke(machine, rt.pid)
            if (device?.methods?.find(method)?.mainThread != true) {
                // 直接のメソッドと、その場で失敗するもの（外れた・メソッドがない・上限）: pending-call を作らずに返す
                val started = machine.devices.dispatcher.start(rt.pid, device, method, args)
                payDeviceFuel(started.fuel)
                check(started.future.isDone) { "直接の呼び出しが終わらずに返った" }
                val result = DeviceDispatcher.resultOf(started.future)
                if (result is CallResult.Values) payDeviceFuel(DeviceLimits.payloadBytes(result.values) * DeviceLimits.FUEL_PER_BYTE)
                return DeviceInvokeStart.Done(result)
            }
            // メインスレッドのメソッドは結果を待つ（待機点型は巻き戻し、ブロッキング型は block で待つ）。
            // callGuest の中ではホスト関数の途中で巻き戻すことになるので、mayWait に依らず pending-call を積む前にトラップにする（ADR 0073）
            checkNotInGuestCall("beginDeviceInvoke")
            if (!mayWait) return DeviceInvokeStart.Done(CallResult.Error(CallError.Kernel(Errno.AGAIN)))
            val (callFd, obj) = lock.withLockValue {
                if (rt.killed) throw ProcessKilledException()
                machine.openPendingDeviceCallLocked(
                    rt.pid,
                    PendingDeviceCall.State.Queued(handle.address, method, args.toList()),
                    countTowardLimit = false,
                    place = place,
                )
            }
            payDeviceFuel(machine.dispatchPendingDeviceCall(rt.pid, obj, device))
            return DeviceInvokeStart.Waiting(callFd, WaitKey(obj.id, WaitCondition.READABLE))
        }

        override fun pendingDeviceCallFdOf(key: WaitKey): Int? {
            if (key.condition != WaitCondition.READABLE) return null
            return lock.withLockValue {
                if (machine.kernel.objects[key.objectId] !is PendingDeviceCall) return@withLockValue null
                rt.process.handles.entries().entries.firstOrNull { it.value.objectId == key.objectId }?.key
            }
        }

        override fun deviceInfo(fd: Int): DeviceInfo = deviceHandleOf(fd).info

        override fun deviceMethods(fd: Int): List<MethodInfo> {
            val handle = deviceHandleOf(fd)
            return machine.devices.dispatcher.resolve(handle.address, handle.kind)?.methodInfos ?: emptyList()
        }

        /** fd [fd] の device のハンドル（中身は変わらないので、ロックを放してから読んでよい）。device でなければ `FsException(BADF)` */
        private fun deviceHandleOf(fd: Int): DeviceHandle = lock.withLockValue { deviceHandleOfLocked(fd) }

        private fun deviceHandleOfLocked(fd: Int): DeviceHandle =
            FdTable(rt.process, machine.kernel.objects, machine.wakeSink).objectOf(fd) as? DeviceHandle
                ?: fsError(Errno.BADF, "fd $fd は device でない")

        override fun takeDeviceCall(fd: Int): CallResult? {
            checkThread()
            val result = lock.withLockValue {
                val obj = FdTable(rt.process, machine.kernel.objects, machine.wakeSink).objectOf(fd) as? PendingDeviceCall
                    ?: fsError(Errno.BADF, "fd $fd は pending-call でない")
                obj.result
            }
            if (result is CallResult.Values) payDeviceFuel(DeviceLimits.payloadBytes(result.values) * DeviceLimits.FUEL_PER_BYTE)
            return result
        }

        /** デバイスの呼び出しの燃料を、手持ちがあれば払う（手持ちがない呼び出し元では払わない。flushConsole と同じ） */
        private fun payDeviceFuel(amount: Long) {
            if (amount <= 0 || rt.process.unmetered) return
            lock.withLockValue { rt.currentHolder?.let { it.fuel -= amount } }
        }

        override fun requestShutdown(): Nothing {
            powerRequest { machine.stop() }
            // stop() は呼んだこのプロセスも止める（killed を立てる）
            throw ProcessKilledException()
        }

        override fun requestReboot(): Boolean {
            val accepted = powerRequest { machine.reboot() }
            // 受け付けたら、このプロセスも止まっている。断っても、CRASHED などで止められていれば戻らない
            if (accepted || lock.withLockValue { rt.killed }) throw ProcessKilledException()
            return false
        }

        /**
         * 電源の求め（#306）。止められていないことを確かめてから [request] を呼ぶまでを、1つのカーネルロックの区間にする。
         * [hostFunction] の包みの確認と本体の間に、ほかのスレッドの `Machine.stop()` が入ると、その後の `reboot()` が
         * 止めたはずのマシンをまた再起動させてしまうため。ロックは再入できる（`machine.lock` と同じもの）。
         * `Machine.stop()`・`reboot()` は、ロックの外で後処理をしないことを前提にしている（入れ子にしても意味が変わらない）。
         * 受け付けた後は、割り込みで止まる処理（待ち・`flushConsole` など）を挟まずにすぐ投げる
         * （`killRuntimeLocked` が自分のスレッドにも割り込みをかけるため）
         */
        private inline fun <T> powerRequest(request: () -> T): T {
            checkThread()
            hooks.beforePowerRequest?.invoke(machine, rt.pid)
            return lock.withLockValue {
                if (rt.killed) throw ProcessKilledException()
                request()
            }
        }

        override fun takeFuelUnwind(): Boolean {
            val unwound = rt.fuelUnwound
            rt.fuelUnwound = false
            return unwound
        }

        override fun memoryRemaining(): Long = rt.memory.remainingBytes

        override fun commitMemory(memory: WasmMemory, address: Int, length: Int, keepBytes: Long): Boolean {
            val committed = memory.commit(address, length, keepBytes)
            // 範囲外は例外で抜けるので、false は RAM が理由で断ったとき
            if (!committed) rt.memoryCommitRefusal = rt.memory.lastRefusal
            return committed
        }

        override fun <T> callGuest(block: () -> T): T {
            checkThread()
            rt.guestCallDepth++
            try {
                return block()
            } finally {
                rt.guestCallDepth--
            }
        }

        override val inGuestCall: Boolean get() = rt.guestCallDepth > 0

        override fun chargeFuel(amount: Long) {
            if (rt.process.unmetered) return
            lock.withLockValue {
                val holder = rt.currentHolder ?: throw IllegalStateException("手持ちを持つインスタンスがない")
                holder.fuel -= amount
            }
        }

        override fun reserveConsoleFlushLocked() = machine.reserveConsolePumpLocked()

        override fun flushConsole() {
            hooks.beforeConsoleFlush?.invoke(machine, rt.pid)
            val cells = machine.pumpConsole(reserved = true)
            if (cells <= 0 || rt.process.unmetered) return
            // 書いた分は、手持ちがあれば払う（手持ちがない呼び出し元では払わない）
            lock.withLockValue { rt.currentHolder?.let { it.fuel -= cells * MachineLimits.CONSOLE_FUEL_PER_CELL } }
        }
    }
}

private val LOG: System.Logger = System.getLogger("kibicomputers.kernel")

private inline fun <T> java.util.concurrent.locks.ReentrantLock.withLockValue(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

/** キューのフィルターが `timer` を受け取るか確かめるためのイベント */
private val TIMER_EVENT_PROBE = KernelEvent(TimerLimits.EVENT_TYPE)

/**
 * プロセスの線形メモリ1つのアドレス空間の上限（ページ数。#407）。RAM は書き込んだ区画で数えるので、RAM のティアより大きくてよい。
 * 64 MiB（#370 の PoC と同じ）。マシンの RAM がこれより大きければ、RAM の大きさまで広げる
 */
internal const val ADDRESS_SPACE_PAGES: Int = 1024

/**
 * コンポーネントの core インスタンス 1 つあたりに RAM へ数える固定費（バイト。#270。仮の値で、確定は #251）。
 * Endive のインスタンス（関数・グローバル・テーブルの配列など）やリンクの表は線形メモリの外にあり、書き込んだ区画では数えられない。
 * 1 つのコンポーネントは core インスタンスを最大 `ComponentLimits.maxExpandedCoreInstances` 個作れるので、数で抑える。
 * 書き込みの区画と同じ 4 KiB にする（p1 の単体のモジュールには数えない）
 */
internal const val CORE_INSTANCE_OVERHEAD_BYTES: Long = 4096

/**
 * 止まりうる関数でない関数の中で燃料が切れたとき、止まりうる関数に戻って巻き戻すまでに前借りしてよい燃料の上限（#477）。
 * 届いたら、スレッドを持ったまま燃料を待つ。前借りの間は燃料を確かめるたびにホスト関数を呼ぶので、大きくしすぎない
 */
internal const val FUEL_OVERDRAFT_LIMIT: Long = 50_000
