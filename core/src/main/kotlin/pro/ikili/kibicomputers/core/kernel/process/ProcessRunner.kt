package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.kernel.HostCall
import pro.ikili.kibicomputers.core.kernel.KernelState
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineEnvironment
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
import pro.ikili.kibicomputers.core.runtime.WasmFuelHandler
import pro.ikili.kibicomputers.core.runtime.WasmHostFunction
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.validation.ModuleLimits
import java.util.concurrent.CompletableFuture
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
        rt.fuelHandler = WasmFuelHandler { instance, remaining -> onFuelExhausted(rt, instance, remaining) }
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
            }
        } catch (t: Throwable) {
            exceptionExit(rt, t)
        } finally {
            unregister(rt)
        }
    }

    /** 待機点型の1回の step。CPU を受け取ったワーカーで動く */
    fun runStep(rt: ProcessRuntime) {
        if (!register(rt)) return
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
        if (resumed) {
            // 起こされたのに空なら、許可も燃料も使わずに待ち直す
            val empty = lock.withLockValue {
                if (rt.killed) throw ProcessKilledException()
                val ws = rt.waitSet ?: throw IllegalStateException("待ちのない step")
                if (ws.readyKeys(machine.kernel.objects).isEmpty()) {
                    ws.rearm()
                    rt.process.state = ProcessState.WAITING_EVENT
                    true
                } else {
                    false
                }
            }
            if (empty) {
                releasePermitAndCpu(rt)
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
            if (resumed) ready = ws!!.readyKeys(machine.kernel.objects)
            when {
                resumed && ready.isEmpty() -> {
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
                    if (resumed) {
                        ws!!.unregister(machine.registry)
                        rt.waitSet = null
                        rt.process.wait = null
                    }
                    rt.process.state = ProcessState.RUNNING
                    rt.stackActive = true
                    Outcome.GO
                }
            }
        }
        if (outcome != Outcome.GO) {
            releasePermitAndCpu(rt)
            return
        }
        var result = if (!resumed) {
            load(rt, grant)
            rt.driver.start(rt.context)
        } else {
            lock.withLockValue { setHolderFuelLocked(rt, grant) }
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
                        releasePermitAndCpu(rt)
                        return
                    }
                    result = rt.driver.resume(rt.context, immediate)
                }
            }
        }
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
        rt.fuelReady = false
        rt.fuelWaitThreadless = threadless
        machine.addFuelWaiterLocked(rt)
        if (now != observedTick || (paused && !scheduler.isPaused)) scheduler.dispatcher.signal()
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
        releasePermitAndCpu(rt)
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

    /** 印が立っているものだけを、許可 → CPU の順に返す */
    private fun releasePermitAndCpu(rt: ProcessRuntime) {
        if (rt.holdsPermit) {
            rt.holdsPermit = false
            scheduler.permits.release()
        }
        if (rt.holdsCpu) {
            rt.holdsCpu = false
            machine.cpu.release(rt)
        }
    }

    /** CPU を返す口（block・awaitHost・Wait・Exit）: 今の holder のグローバルを読んで 0 にし、遅延補充してから書き戻す */
    private fun releaseFuelLocked(rt: ProcessRuntime) {
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
        releasePermitAndCpu(rt)
        val grant = acquireCpu(rt, null)!!
        lock.withLockValue { rt.process.state = previous }
        return grant
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
        check(builder.memoryBudget == null && builder.maxMemoryPages == WasmMemory.MAX_PAGES) { "ABI 層は builder.memoryLimit を呼ばない（メモリの上限はカーネルが設定する）" }
        check(builder.asyncStack == null) { "ABI 層は builder.asyncStack を呼ばない（退避先はカーネルが設定する）" }
        // メモリ1つの上限も、プロセスの上限と RAM のうち小さい方（Endive が上限の分の表を先に作るので、小さく渡す）
        builder.memoryLimit(minOf(rt.memory.limitPages, machine.memoryAccount.limitPages), rt.memory)
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

    // ---- 終わり方 ----

    private fun exit(rt: ProcessRuntime, code: Int) {
        lock.withLockValue {
            releaseFuelLocked(rt)
            rt.stackActive = false
            finishLocked(rt, ProcessState.EXITED, code)
        }
        releasePermitAndCpu(rt)
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
                if (state == ProcessState.CRASHED) rt.crashReason = crashReason(t)
                finishLocked(rt, state, null)
            }
        } finally {
            lock.unlock()
        }
        releasePermitAndCpu(rt)
    }

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
        process.asyncWait = null
        process.asyncStack?.clear()
        if (process.kind == ProcessKind.BLOCKING) scheduler.releaseBlocking()
        // まだ実行していないホスト呼び出しを取り消す（ふつうは 0 件。#46）
        machine.cancelHostCallsLocked(process.pid)
        // 終わりを記録し、親へ知らせ、子を付け替える（#46）
        machine.lifecycle.onFinishedLocked(rt)
        // init なら、マシンを止める（#49）
        machine.onProcessFinishedLocked(process, rt.crashReason)
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

        override fun changeDirectory(path: String) = lock.withLockValue {
            val target = VfsPath.resolve(rt.process.cwd, path)
            if (machine.vfs.stat(target).type != FileType.DIRECTORY) fsError(Errno.NOTDIR, "$target はディレクトリではない")
            rt.process.cwd = target
        }

        override fun spawn(program: Program, driver: ProcessDriver, options: SpawnOptions): Int =
            machine.spawn(program, driver, options, rt.pid)

        override fun exec(path: String, drivers: ProcessDriverFactory, options: SpawnOptions): Int =
            // 読み込み（コンパイル）を待つ間は、CPU と許可を返す（#37。ADR 0029）
            machine.exec(path, drivers, options, rt.pid) { future -> awaitHost(future) }

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
            checkThread()
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

        override fun <T> callOnMainThread(call: HostCall<T>): T {
            checkThread()
            // 止められたプロセスは、メインスレッドでの処理を出さない（マシンが STOPPED に戻った後でも）。
            // 止められたかの確かめと列への積み込みは、submitHostCall の同じロックの中で行う（#46）
            return awaitHost(machine.submitHostCall(rt.pid, call))
        }

        override fun writeConsole(bytes: ByteArray, offset: Int, length: Int): Boolean = machine.writeConsole(bytes, offset, length)

        override fun beep(frequencyHz: Int, durationMillis: Int): Boolean = machine.beep(frequencyHz, durationMillis)

        override fun requestShutdown() = machine.stop()

        override fun requestReboot(): Boolean = machine.reboot()

        override fun chargeFuel(amount: Long) {
            if (rt.process.unmetered) return
            lock.withLockValue {
                val holder = rt.currentHolder ?: throw IllegalStateException("手持ちを持つインスタンスがない")
                holder.fuel -= amount
            }
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
