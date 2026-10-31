package pro.ikili.kibicomputers.core.kernel

import pro.ikili.kibicomputers.core.kernel.process.Process
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriver
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessRunner
import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.sched.KernelThread
import pro.ikili.kibicomputers.core.kernel.sched.MachineCpu
import pro.ikili.kibicomputers.core.kernel.wait.WaitRegistry
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/** マシンの状態 */
public enum class MachineState {
    /** 動いているプロセスがない */
    STOPPED,

    /** 動いている */
    RUNNING,

    /** 止めている途中（`stop()` の後、プロセスが終わるまで） */
    STOPPING,

    /** カーネルの不整合で止まった */
    CRASHED,
}

/** カーネル → 接続層の口。#44 で足していく。 */
public fun interface MachineHost {
    /**
     * マシンの状態が変わった。[MachineScheduler.tick] の中で、メインスレッドから呼ばれる。
     * 呼ばれるとき、カーネルはロックも CPU も許可も持っていない。中から `stop()`・`close()`・`createMachine` を呼んでよい。
     *
     * @param cause [MachineState.CRASHED] になったときの原因。それ以外は `null`
     */
    public fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?)
}

/**
 * マシンの設定。[builder] で作る。値はどれも仮の値（確定は #47・#251）。
 */
public class MachineConfig private constructor(builder: Builder) {
    /** 1 tick に配る燃料。1回に渡す燃料の上限でもある（仮: 1 tick 約 2 ms 相当） */
    public val fuelPerTick: Long = builder.fuelPerTick

    /** 終わっていないプロセスの数の上限（仮: 64） */
    public val maxProcesses: Int = builder.maxProcesses

    /** [MachineConfig] を組み立てる。 */
    public class Builder internal constructor() {
        internal var fuelPerTick: Long = 1_000_000
        internal var maxProcesses: Int = 64

        /** 1 tick に配る燃料（1 以上） */
        public fun fuelPerTick(value: Long): Builder = apply {
            require(value >= 1) { "fuelPerTick は 1 以上" }
            fuelPerTick = value
        }

        /** プロセスの数の上限（1 以上） */
        public fun maxProcesses(value: Int): Builder = apply {
            require(value >= 1) { "maxProcesses は 1 以上" }
            maxProcesses = value
        }

        /** 設定を作る。 */
        public fun build(): MachineConfig = MachineConfig(this)
    }

    public companion object {
        /** 既定の設定 */
        @JvmField
        public val DEFAULT: MachineConfig = Builder().build()

        /** 設定を組み立て始める。 */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}

/**
 * コンピューター1台（= 1カーネル）。[MachineScheduler.createMachine] で作る。
 *
 * カーネルの状態（プロセス表、オブジェクト表、燃料）はマシンごとの1つのロック（カーネルロック）で守る。
 */
public class Machine internal constructor(
    internal val scheduler: MachineScheduler,
    /** スケジューラの中の番号（保存しない。実行時だけ） */
    public val id: Long,
    internal val host: MachineHost,
    /** 設定 */
    public val config: MachineConfig,
) : AutoCloseable {
    /** カーネルロック。持ったまま待たない・WASM を呼ばない・[MachineHost] を呼ばない */
    internal val lock = ReentrantLock()

    /** カーネルの状態（保存の対象） */
    internal val kernel = KernelState(config.maxProcesses)

    /** 燃料の遅延補充 */
    internal val fuelAccount = FuelAccount(kernel, config.fuelPerTick, scheduler.now)

    /** 待ち手の表（実行時だけ） */
    internal val registry = WaitRegistry()

    /** pid → 実行時だけのもの */
    internal val runtimes = HashMap<Int, ProcessRuntime>()

    internal val runner = ProcessRunner(this)

    /** マシンの CPU */
    internal val cpu = MachineCpu { rt -> scheduler.workers.execute { runner.runStep(rt) } }

    @Volatile
    private var stateValue = MachineState.STOPPED

    private var crashCause: Throwable? = null

    /** 登録されているスレッドの数（カーネルロック） */
    private var registeredThreads = 0

    private val terminated = lock.newCondition()

    private val notifications = ConcurrentLinkedQueue<Pair<MachineState, Throwable?>>()
    private val queuedForNotify = AtomicBoolean(false)

    /** 状態 */
    public val state: MachineState get() = stateValue

    /** 全プロセスが待機点にいる（または1つもない）なら `true`（#81 の判定の土台） */
    public val isAtWaitPoint: Boolean
        get() = lock.withLockValue { kernel.processes.all().all { isAtWaitPointLocked(it) } }

    /**
     * 停止の印を立て、プロセスのスレッドに割り込んで、待たずに返す。どのスレッドから呼んでもよい
     * （メインスレッド、`onStateChanged` の中、そのマシンのプロセスのホスト関数の中も含む）。2回呼んでもよい。
     */
    public fun stop() {
        lock.lock()
        try {
            stopLocked(null)
        } finally {
            lock.unlock()
        }
    }

    /**
     * プロセスのスレッドがすべて終わるまで待つ。終わったら `true`。
     *
     * @throws IllegalStateException カーネルのスレッドから呼んだ
     */
    @Throws(InterruptedException::class)
    public fun awaitTermination(timeoutMillis: Long): Boolean {
        check(!KernelThread.isCurrent()) { "カーネルのスレッドからは呼べない" }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(0))
        val threads: List<Thread>
        lock.lockInterruptibly()
        try {
            while (kernel.processes.liveCount() > 0 || registeredThreads > 0) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                terminated.awaitNanos(remaining)
            }
            threads = runtimes.values.mapNotNull { it.dedicatedThread }
        } finally {
            lock.unlock()
        }
        for (t in threads) {
            t.join(TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1))
            if (t.isAlive) return false
        }
        return true
    }

    /** `stop()` してスケジューラから外す。待たずに返す */
    override fun close() {
        stop()
        scheduler.removeMachine(this)
    }

    override fun toString(): String = "Machine($id, $stateValue)"

    // ---- カーネルの内部 ----

    /**
     * プロセスを作る（#45・#49 が使う）。Process を `CREATED` で表に足し、型に応じて専用スレッドを作る（BLOCKING）か、
     * CPU の待ち行列に入れる（WAIT_POINT）。インスタンスはここでは作らない。カーネルロックを自分で取る（中から呼んでもよい）。
     *
     * @param parentPid 0 は親なし（init）。0 以外は、表にある pid でなければ [IllegalArgumentException]
     * @return 割り当てた pid
     * @throws IllegalArgumentException 燃料を計測しないモジュールで `allowUnmetered` が `false`、または親がない
     * @throws ResourceLimitException プロセスの上限・ブロッキング型の上限を超えた
     * @throws IllegalStateException マシンが STOPPING・CRASHED、またはスケジューラが閉じている
     */
    internal fun spawn(program: Program, driver: ProcessDriver, options: SpawnOptions = SpawnOptions(), parentPid: Int = 0): Int {
        lock.lock()
        try {
            check(!scheduler.isClosed) { "スケジューラは閉じている" }
            check(stateValue != MachineState.STOPPING && stateValue != MachineState.CRASHED) { "マシンは $stateValue" }
            val metered = program.module.isFuelMetered
            require(metered || options.allowUnmetered) { "燃料を計測しないモジュールはプロセスにできない" }
            require(parentPid == 0 || parentPid in kernel.processes) { "親のプロセス $parentPid がない" }
            if (kernel.processes.liveCount() >= config.maxProcesses) throw ResourceLimitException("プロセスの数が上限（${config.maxProcesses}）")
            val kind = driver.kind
            if (kind == ProcessKind.BLOCKING && !scheduler.tryReserveBlocking()) {
                throw ResourceLimitException("ブロッキング型のプロセスの数が上限（${scheduler.config.maxBlockingProcesses}）")
            }
            val pid = kernel.processes.allocatePid()
            val process = Process(pid, parentPid, kind, program.hash, !metered)
            kernel.processes.add(process)
            val rt = ProcessRuntime(this, process, program, driver)
            runner.initRuntime(rt)
            runtimes[pid] = rt
            if (stateValue == MachineState.STOPPED) setStateLocked(MachineState.RUNNING, null)
            if (kind == ProcessKind.BLOCKING) {
                val thread = KernelThread("KiBi-m$id-p$pid", scheduler.config.threadStackBytes) { runner.runBlocking(rt) }
                rt.dedicatedThread = thread
                scheduler.dedicatedThreads.add(thread)
                thread.start()
            } else {
                cpu.enqueueThreadless(rt)
            }
            return pid
        } finally {
            lock.unlock()
        }
    }

    internal fun runtime(pid: Int): ProcessRuntime? = lock.withLockValue { runtimes[pid] }

    internal fun processState(pid: Int): ProcessState? = lock.withLockValue { kernel.processes[pid]?.state }

    /** 待機点か（#30・ADR 0043 の「データ形式」） */
    private fun isAtWaitPointLocked(process: Process): Boolean {
        val state = process.state
        if (state.isTerminal || state == ProcessState.CREATED) return true
        if (process.kind != ProcessKind.WAIT_POINT) return false
        val rt = runtimes[process.pid] ?: return true
        if (rt.stackActive || rt.loading != null) return false
        return state == ProcessState.WAITING_EVENT || (state == ProcessState.RUNNABLE && rt.thread == null)
    }

    /** 停止（[crash] があればマシンの不整合）。カーネルロックの中で呼ぶ */
    internal fun stopLocked(crash: Throwable?) {
        if (crash != null) {
            if (crashCause == null) {
                crashCause = crash
                setStateLocked(MachineState.CRASHED, crash)
            }
        } else if (stateValue == MachineState.RUNNING) {
            setStateLocked(MachineState.STOPPING, null)
        }
        for (rt in runtimes.values.toList()) {
            if (rt.process.state.isTerminal) continue
            rt.killed = true
            val thread = rt.thread
            if (thread != null) {
                scheduler.hooks.stopBeforeInterrupt?.invoke(this, rt.pid)
                scheduler.interruptsSent.incrementAndGet()
                thread.interrupt()
            } else if (rt.process.kind == ProcessKind.WAIT_POINT) {
                // スレッドを持たないプロセスは、その場で終える
                cpu.removeThreadless(rt)
                runner.finishLocked(rt, ProcessState.KILLED, null)
            }
            // スレッドがまだ登録されていないブロッキング型は、登録のときに印を見て終わる
        }
        checkAllDoneLocked()
    }

    /** マシンの不整合: マシンを CRASHED にして、投げる例外を返す。カーネルロックの中で呼ぶ */
    internal fun inconsistencyLocked(message: String): KernelInconsistencyException {
        val e = KernelInconsistencyException(message)
        stopLocked(e)
        return e
    }

    /** スレッドを持たない待機点型のプロセスを動けるようにする（step の途中ならスレッドが外れてから） */
    internal fun scheduleThreadlessLocked(rt: ProcessRuntime) {
        if (rt.process.state.isTerminal) return
        if (rt.process.state == ProcessState.WAITING_EVENT) rt.process.state = ProcessState.RUNNABLE
        if (rt.thread != null) rt.pendingSchedule = true else cpu.enqueueThreadless(rt)
    }

    /** 燃料待ち（・一時停止の解除待ち）のプロセス（カーネルロック） */
    private val fuelWaiters = ArrayList<ProcessRuntime>()
    private var inDispatcher = false
    private var fuelRotation = 0

    /** 燃料待ちの数（テスト用） */
    internal val fuelWaiterCount: Int get() = lock.withLockValue { fuelWaiters.size }

    /** 燃料待ちに入れる。カーネルロックの中で呼ぶ */
    internal fun addFuelWaiterLocked(rt: ProcessRuntime) {
        if (fuelWaiters.none { it === rt }) fuelWaiters.add(rt)
        if (!inDispatcher) {
            inDispatcher = true
            scheduler.dispatcher.add(this)
        }
    }

    /**
     * 燃料待ちを起こす（燃料があるか、燃料を計測しないプロセスで、一時停止でなければ）。カーネルロックの中で呼ぶ。
     * 起こすだけで燃料は渡さない（渡すのは、起こされたプロセスが CPU を取るとき）。起こした数を返す。
     */
    internal fun wakeFuelWaitersLocked(): Int {
        if (fuelWaiters.isEmpty() || scheduler.isPaused) return 0
        val hasFuel = fuelAccount.estimate(scheduler.now) > 0
        val n = fuelWaiters.size
        val start = Math.floorMod(fuelRotation++, n)
        val order = List(n) { fuelWaiters[(start + it) % n] }
        var woken = 0
        for (rt in order) {
            if (rt.process.state.isTerminal) {
                fuelWaiters.remove(rt)
                continue
            }
            if (!hasFuel && !rt.process.unmetered) continue
            fuelWaiters.remove(rt)
            woken++
            if (rt.fuelWaitThreadless) {
                rt.fuelWaitThreadless = false
                scheduleThreadlessLocked(rt)
            } else {
                rt.fuelReady = true
                rt.fuelCondition.signalAll()
            }
        }
        return woken
    }

    /** ディスパッチャーから: 燃料待ちを起こし、（起こした数, まだ残っているか）を返す */
    internal fun dispatchFuelWaiters(): Pair<Int, Boolean> {
        lock.lock()
        try {
            val woken = wakeFuelWaitersLocked()
            val remaining = fuelWaiters.isNotEmpty()
            if (!remaining) inDispatcher = false
            return woken to remaining
        } finally {
            lock.unlock()
        }
    }

    internal fun threadRegisteredLocked() {
        registeredThreads++
    }

    internal fun threadUnregisteredLocked() {
        registeredThreads--
        checkAllDoneLocked()
    }

    /** 最後のプロセスが終わったら STOPPED にする。カーネルロックの中で呼ぶ */
    internal fun checkAllDoneLocked() {
        if (kernel.processes.liveCount() > 0) return
        if (stateValue == MachineState.RUNNING || stateValue == MachineState.STOPPING) setStateLocked(MachineState.STOPPED, null)
        if (registeredThreads == 0) terminated.signalAll()
    }

    private fun setStateLocked(state: MachineState, cause: Throwable?) {
        if (stateValue == state) return
        stateValue = state
        notifications.add(state to cause)
        if (queuedForNotify.compareAndSet(false, true)) scheduler.enqueueNotify(this)
    }

    /** `tick()` の中で、メインスレッドから通知を配る（ロックの外） */
    internal fun deliverNotifications() {
        queuedForNotify.set(false)
        while (true) {
            val (state, cause) = notifications.poll() ?: break
            try {
                host.onStateChanged(this, state, cause)
            } catch (t: Throwable) {
                // 接続層の例外はほかのマシンに伝えない
            }
        }
    }
}

private inline fun <T> ReentrantLock.withLockValue(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
