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
import pro.ikili.kibicomputers.core.kernel.MountListing
import pro.ikili.kibicomputers.core.kernel.ProcessKilledException
import pro.ikili.kibicomputers.core.kernel.event.EventBatch
import pro.ikili.kibicomputers.core.kernel.event.EventFilter
import pro.ikili.kibicomputers.core.kernel.event.EventQueue
import pro.ikili.kibicomputers.core.kernel.event.KernelEvent
import pro.ikili.kibicomputers.core.kernel.time.Timer
import pro.ikili.kibicomputers.core.kernel.time.TimerLimits
import pro.ikili.kibicomputers.core.kernel.time.saturatingAdd
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
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
            override fun onFuelExhausted(instance: WasmInstance, remaining: Long): Long = onFuelExhaustedPlain(rt, instance, remaining)

            override fun onFuelExhaustedUnwindable(instance: WasmInstance, remaining: Long): Long =
                onFuelExhaustedUnwindable(rt, instance, remaining)
        }
        rt.context = Context(rt)
    }

    // ---- 入口 ----

    /** ブロッキング型の専用スレッドの中身 */
    fun runBlocking(rt: ProcessRuntime) {
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
            holder.fuel = 0
            // CPU を持っている間は、最後に補充した tick が手持ちを渡した tick（FuelAccount）。giveBack で書き換わる前に読む
            val grantedTick = machine.fuelAccount.lastRefillTick
            rt.process.state = ProcessState.RUNNABLE
            rt.stackActive = false
            enqueueFuelWaitLocked(rt, threadless = true)
            giveBackLocked(rt.fuelUnwindRemaining - stack.size.toLong() * FuelCosts.ASYNC_VALUE)
            rt.fuelUnwindRemaining = 0
            scheduler.deactivate(rt)
            scheduler.fuelUnwinds.incrementAndGet()
            if (now != grantedTick) scheduler.dispatcher.signal()
        }
        releasePermitAndCpu(rt, endOfStep = true)
    }

    /** 燃料待ちの列に入れる（[threadless] ならスレッドを持たない待ち手として）。カーネルロックの中で呼ぶ */
    private fun enqueueFuelWaitLocked(rt: ProcessRuntime, threadless: Boolean) {
        rt.fuelReady = false
        rt.fuelWaitThreadless = threadless
        machine.addFuelWaiterLocked(rt)
    }

    /**
     * 一時停止でなく燃料があれば、燃料を渡して grant を返す（燃料を計測しないプロセスは 0）。
     * なければ燃料待ちの列に入れて `null`。カーネルロックの中で、[observedTick] まで補充した後に呼ぶ。
     *
     * 確かめた後に tick が進んだ（か一時停止が解けた）なら、ディスパッチャーが列を見終えた後かもしれないので、もう一度見直させる
     * （起こし忘れを防ぐ）。
     */
    private fun tryGrantLocked(rt: ProcessRuntime, observedTick: Long, threadless: Boolean): Long? {
        val paused = !scheduler.tryActivate(rt)
        if (!paused) {
            if (rt.process.unmetered) return 0L
            val grant = machine.fuelAccount.take(observedTick)
            if (grant > 0) return grant
            scheduler.deactivate(rt)
        }
        enqueueFuelWaitLocked(rt, threadless)
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
     */
    private fun acquireCpu(rt: ProcessRuntime, precheck: (() -> Boolean)?): Long? {
        while (true) {
            if (!rt.holdsCpu) {
                try {
                    machine.cpu.acquire(rt)
                } catch (e: InterruptedException) {
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
                    tryGrantLocked(rt, observed, threadless = false)?.also { grant = it } == null -> Outcome.NO_FUEL
                    else -> Outcome.GO
                }
            }
            if (outcome == Outcome.GO) return grant
            releasePermitAndCpu(rt)
            if (outcome == Outcome.REARM) return null
            waitForFuel(rt)
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
     * 前借りした分は残り（負）として書き戻すので、マシンの燃料の借りになり、後の補充で返す
     */
    private fun onFuelExhaustedPlain(rt: ProcessRuntime, instance: WasmInstance, remaining: Long): Long {
        if (remaining > -FUEL_OVERDRAFT_LIMIT && rt.program.fuelYield) {
            val overdraft = lock.withLockValue {
                verifyInstanceLocked(rt, instance)
                if (rt.killed) throw ProcessKilledException()
                rt.process.kind == ProcessKind.WAIT_POINT && rt.loading == null && rt.guestCallDepth == 0
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

    /** 燃料切れの処理。グローバルを読まない・書かない（残りは引数、新しい手持ちは戻り値） */
    private fun onFuelExhausted(rt: ProcessRuntime, instance: WasmInstance, remaining: Long): Long {
        val previous = lock.withLockValue {
            verifyInstanceLocked(rt, instance)
            if (rt.killed) throw ProcessKilledException()
            giveBackLocked(remaining)
            scheduler.deactivate(rt)
            val prev = rt.process.state
            rt.process.state = ProcessState.WAITING_FUEL
            prev
        }
        scheduler.fuelBlockingWaits.incrementAndGet()
        releasePermitAndCpu(rt)
        val grant = acquireCpu(rt, null)!!
        lock.withLockValue { rt.process.state = previous }
        return grant
    }

    /**
     * 止まりうる関数の中の燃料切れ（#339。`kibi.async_fuel`）。巻き戻せるなら、状態を `UNWINDING` にして戻る
     * （`_start` まで戻ったら、ドライバーが [StepResult.WaitFuel] を返す）。
     * 燃料待ちからの巻き直し（`REWINDING`）で呼ばれたら、止まった呼び出しに着いたので `NORMAL` に戻し、step が渡した手持ちを返す。
     * 巻き戻せないところ（モジュールの start 関数の中、ホスト関数の中から呼んだゲストの中。#73）では、[onFuelExhausted] と同じくスレッドを持ったまま待つ
     */
    private fun onFuelExhaustedUnwindable(rt: ProcessRuntime, instance: WasmInstance, remaining: Long): Long {
        val unwindable = lock.withLockValue {
            verifyInstanceLocked(rt, instance)
            if (rt.killed) throw ProcessKilledException()
            // ホスト関数の中からゲストを呼んでいる間（#73。cabi_realloc など）は、ホスト関数の途中を巻き戻せないので待つ
            rt.process.kind == ProcessKind.WAIT_POINT && rt.loading == null && rt.guestCallDepth == 0
        }
        if (!unwindable) return onFuelExhausted(rt, instance, remaining)
        if (instance.asyncState == WasmAsyncState.REWINDING) {
            check(rt.rewindingForFuel) { "燃料待ちからでない巻き直しが、燃料切れの呼び出しに着いた" }
            check(rt.process.asyncStack?.isEmpty != false) { "巻き直しの後に退避した値が残っている" }
            rt.rewindingForFuel = false
            instance.asyncState = WasmAsyncState.NORMAL
            return lock.withLockValue { instance.fuel }
        }
        check(instance.asyncState == WasmAsyncState.NORMAL) { "巻き戻しの途中で燃料切れの呼び出しが走った" }
        rt.fuelOverdraft = false
        rt.fuelUnwound = true
        rt.fuelUnwindRemaining = remaining
        instance.asyncState = WasmAsyncState.UNWINDING
        return remaining
    }

    /** ホスト関数・燃料切れの処理の入口の照合。違えばマシンを CRASHED にして投げる */
    private fun verifyInstanceLocked(rt: ProcessRuntime, instance: WasmInstance) {
        val frame = rt.loading
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
        builder.writtenMemoryLimit(maxOf(ADDRESS_SPACE_PAGES, ramPages), rt.memory) { rt.memoryGrowRefused = true }
        // テーブル（#361）: 要素数の上限は読み込みの上限と同じ値にし、要素の分はメモリと同じくプロセスと RAM に数える
        check(builder.tableBudget == null && builder.maxTableElements == Int.MAX_VALUE) { "ABI 層は builder.tableLimit を呼ばない（テーブルの上限はカーネルが設定する）" }
        val maxTableElements = (scheduler.options.moduleLimits ?: ModuleLimits.DEFAULT).maxTableElements
        builder.tableLimit(maxTableElements, rt.memory)
    }

    /** プロセスの始まり: LOADING で configure → 燃料の設定 → instantiate */
    private fun load(rt: ProcessRuntime, grant: Long) {
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
        val instance = rt.program.module.instantiate(builder.build())
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
        releasePermitAndCpu(rt, endOfStep = true)
    }

    /**
     * `CRASHED` の理由（#41）。RAM の上限を超えた書き込みのトラップは上限を添え、`memory.grow` を RAM の上限で断った後の
     * ほかのトラップには、メモリ不足が原因かもしれないと添える（#407）。`kibi:computer/memory` の `commit` を断った後も同じ（#408）
     */
    private fun processCrashReason(rt: ProcessRuntime, t: Throwable): String {
        if (t is WasmTrapException && t.kind == WasmTrapKind.OUT_OF_MEMORY) return outOfMemoryReason(rt.memory.effectiveLimitBytes)
        val reason = crashReason(t)
        val note = refusedMemoryNote(rt)
        return if (note != null) "$reason ($note)" else reason
    }

    /** `memory.grow`・`kibi:computer/memory` の `commit` を RAM の上限で断っていたら、そう書いた文（#407・#408）。なければ `null` */
    private fun refusedMemoryNote(rt: ProcessRuntime): String? =
        memoryRefusedNote(rt.memoryGrowRefused, rt.memoryCommitRefused, rt.memory.effectiveLimitBytes)

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
        rt.fuelHolder = null
        // fd をすべて閉じる（ゾンビは fd を持たない。#58）
        FdTable(process, machine.kernel.objects, machine.wakeSink).closeAll()
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
            return machine.execWithHandle(path, drivers, options, rt.pid) { future -> awaitHost(future) }
        }

        override fun finishExecWithHandle(wait: ExecWait): ProcessHandle {
            checkThread()
            lock.withLockValue { takeLocked(wait.wait) }
            return machine.finishExecWithHandle(wait.pending)
        }

        override fun checkHandleRoom() = machine.checkHandleRoom(rt.pid)

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
            val queueId = if (eventQueue == null) {
                0
            } else {
                val q = eventQueueLocked(eventQueue)
                if (!q.filter.accepts(TIMER_EVENT_PROBE)) fsError(Errno.INVAL, "fd $eventQueue のキューは ${TimerLimits.EVENT_TYPE} を受け取らない")
                q.id
            }
            FdTable(rt.process, machine.kernel.objects, machine.wakeSink).open(Timer(queueId, tag), HandleRights.READ)
        }

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

        override val kernel: KernelState get() = machine.kernel

        override val vfs: Vfs get() = machine.vfs

        override val wakeSink: WakeSink get() = machine.wakeSink

        override fun <T> withKernelLock(block: () -> T): T = lock.withLockValue(block)

        private fun checkThread() {
            check(Thread.currentThread() === rt.thread) { "プロセス ${rt.pid} のスレッドから呼ぶ" }
        }

        override fun hostFunction(f: KernelHostFunction): WasmHostFunction = WasmHostFunction { caller, args ->
            lock.withLockValue {
                if (rt.killed) throw ProcessKilledException()
                verifyInstanceLocked(rt, caller)
            }
            f.call(this, caller, args)
        }

        override fun switchFuelHolder(target: WasmInstance) {
            checkThread()
            lock.withLockValue {
                check(rt.loading == null) { "作っている途中は switchFuelHolder を呼べない" }
                require(rt.instances.any { it === target }) { "このプロセスのインスタンスでない" }
                val holder = rt.fuelHolder!!
                if (!rt.process.unmetered && holder !== target) {
                    val r = holder.fuel
                    holder.fuel = 0
                    target.fuel = r
                }
                rt.fuelHolder = target
            }
        }

        override fun instantiate(module: WasmModule, configurer: InstanceConfigurer): WasmInstance {
            checkThread()
            lock.withLockValue {
                check(rt.loading == null) { "作っている途中は instantiate を入れ子で呼べない" }
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
            val created = try {
                module.instantiate(builder.build())
            } catch (t: Throwable) {
                // CPU と許可は返さない。燃料は捨てる（元の holder は 0 のまま）
                lock.withLockValue { rt.loading = null }
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
                    val grant = acquireCpu(rt) {
                        ready = ws.readyKeys(machine.kernel.objects)
                        if (ready.isEmpty()) {
                            ws.rearm()
                            false
                        } else {
                            true
                        }
                    } ?: continue
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

        override fun openDevice(address: String): Int {
            checkThread()
            val attached = machine.devices.find(address) ?: throw DeviceCallException(CallError.Detached)
            return lock.withLockValue {
                if (rt.killed) throw ProcessKilledException()
                FdTable(rt.process, machine.kernel.objects, machine.wakeSink).open(DeviceHandle(attached.address, attached.kind), HandleRights.READ)
            }
        }

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
            if (!committed) rt.memoryCommitRefused = true
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
 * 止まりうる関数でない関数の中で燃料が切れたとき、止まりうる関数に戻って巻き戻すまでに前借りしてよい燃料の上限（#477）。
 * 届いたら、スレッドを持ったまま燃料を待つ。前借りの間は燃料を確かめるたびにホスト関数を呼ぶので、大きくしすぎない
 */
internal const val FUEL_OVERDRAFT_LIMIT: Long = 50_000
