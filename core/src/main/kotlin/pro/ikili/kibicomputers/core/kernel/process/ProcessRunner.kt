package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.kernel.KernelState
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.ProcessKilledException
import pro.ikili.kibicomputers.core.kernel.wait.ProcessWaiter
import pro.ikili.kibicomputers.core.kernel.wait.ThreadWaiter
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.wait.WaitSet
import pro.ikili.kibicomputers.core.runtime.WasmFuelHandler
import pro.ikili.kibicomputers.core.runtime.WasmHostFunction
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmModule
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

    private fun checkNoFuel(builder: WasmInstanceConfig.Builder) {
        check(builder.initialFuel == 0L && builder.fuelHandler == null) { "ABI 層は builder.fuel を呼ばない（燃料はカーネルが設定する）" }
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
        checkNoFuel(builder)
        if (!rt.process.unmetered) builder.fuel(grant, rt.fuelHandler)
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

    /** 例外で抜けたとき: グローバルを読まず、マシンの燃料に何も書き戻さない。CPU を持っているときだけ遅延補充する */
    private fun exceptionExit(rt: ProcessRuntime, t: Throwable) {
        rt.lastError = t
        lock.lock()
        try {
            machine.fuelAccount.onException(now, rt.holdsCpu)
            scheduler.deactivate(rt)
            rt.loading = null
            if (!rt.process.state.isTerminal) {
                val state = if (rt.killed) ProcessState.KILLED else ProcessState.CRASHED
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
        if (process.kind == ProcessKind.BLOCKING) scheduler.releaseBlocking()
        // 親への知らせは #46
        machine.checkAllDoneLocked()
    }

    // ---- ProcessContext ----

    private inner class Context(private val rt: ProcessRuntime) : ProcessContext {
        override val pid: Int get() = rt.pid

        override val instance: WasmInstance
            get() = lock.withLockValue { rt.firstInstance ?: throw IllegalStateException("インスタンスはまだない（LOADING）") }

        override val fuelHolder: WasmInstance
            get() = lock.withLockValue { rt.fuelHolder ?: throw IllegalStateException("インスタンスはまだない（LOADING）") }

        override val kernel: KernelState get() = machine.kernel

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
            val builder = WasmInstanceConfig.builder()
            configurer.configure(builder)
            checkNoFuel(builder)
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
