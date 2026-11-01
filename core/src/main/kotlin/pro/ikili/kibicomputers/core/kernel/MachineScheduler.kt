package pro.ikili.kibicomputers.core.kernel

import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.kernel.process.ProgramCache
import pro.ikili.kibicomputers.core.kernel.sched.Dispatcher
import pro.ikili.kibicomputers.core.kernel.sched.KernelThread
import pro.ikili.kibicomputers.core.kernel.sched.WorkerPool
import pro.ikili.kibicomputers.core.kernel.time.ExecutorTimerService
import pro.ikili.kibicomputers.core.kernel.time.TimerService
import pro.ikili.kibicomputers.core.runtime.WasmRuntime
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.validation.ModuleLimits
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * コンピューター（[Machine]）を動かすスケジューラ。サーバーに1つ。接続層がサーバーの起動で作り、停止で [close] する（#43）。
 *
 * - 同時に WASM を実行するマシンの数は、公平なセマフォで [SchedulerConfig.maxConcurrentMachines] までに抑える
 * - [tick] はメインスレッドから毎 tick 呼ぶ。tick の番号を進め、通知のあるマシンだけ [MachineHost] に配る。台数によらない
 * - 保存の前に [pauseAll] で全マシンを止める
 *
 * スレッド: [tick] はメインスレッドから呼ぶ。ほかはどのスレッドから呼んでもよい（[awaitTermination] はカーネルのスレッドから呼べない）。
 */
public class MachineScheduler private constructor(
    /** 設定 */
    public val config: SchedulerConfig,
    internal val options: SchedulerInternals,
) : AutoCloseable {
    /** [config] の設定でスケジューラを作る。ディスパッチャーのスレッドが動き始める */
    @JvmOverloads
    public constructor(config: SchedulerConfig = SchedulerConfig.DEFAULT) : this(config, SchedulerInternals())

    internal companion object {
        /** `KiBi-timer` のスタック（タイマーを鳴らすだけなので小さくてよい） */
        private const val TIMER_STACK_BYTES: Long = 1L * 1024 * 1024

        /** 内部の設定（テスト用の差し替え）を付けてスケジューラを作る。 */
        @JvmSynthetic
        internal fun create(config: SchedulerConfig, options: SchedulerInternals): MachineScheduler = MachineScheduler(config, options)
    }

    internal val permits = Semaphore(config.maxConcurrentMachines, true)
    private val tickCounter = AtomicLong()
    private val machines = ConcurrentHashMap<Long, Machine>()
    private val nextMachineId = AtomicLong(1)
    private val notifyQueue = ConcurrentLinkedQueue<Machine>()
    private val hostCallQueue = ConcurrentLinkedQueue<Machine>()
    private val blockingCount = AtomicInteger()

    /** ブロッキング型の専用スレッド（終わるのを待つため） */
    internal val dedicatedThreads: MutableSet<Thread> = Collections.newSetFromMap(ConcurrentHashMap())

    internal val workers = WorkerPool(config.threadStackBytes, options.workerIdleMillis, options.executor)
    internal val dispatcher = Dispatcher<Machine>(config.threadStackBytes) { machine -> machine.dispatchFuelWaiters() }

    internal val hooks = KernelTestHooks()

    /** 実行ファイルの読み込み（変換・挿入・パース・コンパイル）を行うスレッド（#37。[SchedulerConfig.compilerThreads] 本まで） */
    private val compilerExecutor = ProgramCache.compilerExecutor(config.compilerThreads, config.threadStackBytes)

    /** 読み込んだ実行ファイルの共有（#37。`Machine.exec` が使う） */
    internal val programs: ProgramCache = ProgramCache(config.programCacheEntries, config.programCacheBytes, compilerExecutor)

    /** タイマーの期限を待つ口（#64、ADR 0064。既定はスレッド `KiBi-timer` 1本） */
    internal val timerService: TimerService = options.timers ?: ExecutorTimerService(TIMER_STACK_BYTES)

    private val pauseLock = ReentrantLock()
    private val pauseCondition = pauseLock.newCondition()
    private var paused = false
    private var activeCount = 0

    @Volatile
    private var closed = false

    /** 許可を取った回数（数え口） */
    internal val permitAcquisitions = AtomicLong()

    /** [tick] が通知を配ったマシンの数の合計（数え口） */
    internal val tickVisitedMachines = AtomicLong()

    /** `stop()` が送った割り込みの数（数え口） */
    internal val interruptsSent = AtomicLong()

    init {
        dispatcher.start()
    }

    /** 今の tick の番号 */
    internal val now: Long get() = tickCounter.get()

    internal val isClosed: Boolean get() = closed

    /** 一時停止の印（カーネルロックの中から読む） */
    internal val isPaused: Boolean get() = pauseLock.withLockValue { paused }

    /** 一時停止の数えに入っているプロセスの数（テスト用） */
    internal val activeProcesses: Int get() = pauseLock.withLockValue { activeCount }

    /** ブロッキング型のプロセスの数 */
    internal val blockingProcesses: Int get() = blockingCount.get()

    /**
     * マシンを作る（まだ何も動かない。起動は #49）。
     *
     * @param host カーネルからの通知を受ける口
     * @param config マシンの設定
     * @param environment 時計・乱数・ディスク・保存先（#44）
     * @throws IllegalStateException [close] の後
     */
    @JvmOverloads
    public fun createMachine(
        host: MachineHost,
        config: MachineConfig,
        environment: MachineEnvironment = MachineEnvironment.DEFAULT,
    ): Machine {
        check(!closed) { "スケジューラは閉じている" }
        val machine = Machine.create(this, nextMachineId.getAndIncrement(), host, config, environment)
        machines[machine.id] = machine
        if (closed) {
            machines.remove(machine.id)
            throw IllegalStateException("スケジューラは閉じている")
        }
        return machine
    }

    /**
     * メインスレッドから毎 tick 呼ぶ。tick の番号を進めてディスパッチャーに知らせ、
     * 通知のあるマシンだけ [MachineHost] に配る。続けて、ホスト呼び出し（ワールド操作など）を待つマシンだけ、
     * その呼び出しをこのスレッドで実行する（1台あたり [MachineConfig.maxHostCallsPerTick] まで。残りは次の tick）。
     * 台数によらない（通知と呼び出しの数にだけ比例する）。
     */
    public fun tick() {
        tickCounter.incrementAndGet()
        dispatcher.signal()
        val batch = ArrayList<Machine>()
        while (true) batch += notifyQueue.poll() ?: break
        tickVisitedMachines.addAndGet(batch.size.toLong())
        for (machine in batch) machine.deliverNotifications()
        batch.clear()
        while (true) batch += hostCallQueue.poll() ?: break
        tickVisitedMachines.addAndGet(batch.size.toLong())
        for (machine in batch) {
            // 残ったマシンは次の tick へ（この tick の中では入れ直さない）
            if (machine.runHostCalls()) hostCallQueue.add(machine)
        }
    }

    /**
     * 全マシンを一時停止する。実行中のプロセスがなくなったら返す。返した [PauseHandle] の `close()` で再開する。
     *
     * 止まるまでの上限は、実行中のプロセスが手持ちの燃料（最大で `fuelPerTick`）を使い切るまで。
     * 時間内に止まらなければ、一時停止を解いてから [TimeoutException] を投げる（燃料を計測しないプロセスが計算中なら必ずこれになる）。
     *
     * @throws IllegalStateException もう一時停止している
     */
    @Throws(TimeoutException::class, InterruptedException::class)
    public fun pauseAll(timeoutMillis: Long): PauseHandle {
        pauseLock.withLockValue {
            check(!paused) { "もう一時停止している" }
            paused = true
        }
        val handle = object : PauseHandle {
            private val done = AtomicBoolean(false)

            override fun close() {
                if (done.compareAndSet(false, true)) unpause()
            }
        }
        try {
            pauseLock.lockInterruptibly()
            try {
                var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(0))
                while (activeCount > 0) {
                    if (remaining <= 0) throw TimeoutException("${timeoutMillis} ms のうちに止まらなかった（実行中 $activeCount）")
                    remaining = pauseCondition.awaitNanos(remaining)
                }
            } finally {
                pauseLock.unlock()
            }
        } catch (t: Throwable) {
            handle.close()
            throw t
        }
        return handle
    }

    private fun unpause() {
        pauseLock.withLockValue { paused = false }
        dispatcher.signal()
    }

    /**
     * 全マシンの `stop()` を呼び、新しいマシンを断り、ディスパッチャーとワーカーを閉じる。待たずに返す。
     * 実行ファイルの読み込み（コンパイル）を待っている起動・プロセスも、読み込みの終わりを待たずに戻る（#344）
     */
    override fun close() {
        if (closed) return
        closed = true
        machines.values.forEach { it.stop() }
        dispatcher.close()
        timerService.close()
        workers.shutdown()
        // 読み込みを待つ起動・プロセスを戻すため、捨てる読み込みの future も失敗させる（#344）
        programs.close()
        compilerExecutor.shutdownNow()
    }

    /**
     * プロセスのスレッド・ワーカー・ディスパッチャーがすべて終わるまで待つ。終わったら `true`。
     *
     * @throws IllegalStateException カーネルのスレッドから呼んだ（自分を待ってデッドロックするため）
     */
    @Throws(InterruptedException::class)
    public fun awaitTermination(timeoutMillis: Long): Boolean {
        check(!KernelThread.isCurrent()) { "カーネルのスレッドからは呼べない" }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(0))
        fun remainingMillis(): Long = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(0)
        // ConcurrentHashMap の values.toList() は、size を読んだ後に要素が減ると NoSuchElementException になる。toArray で写す
        for (machine in ArrayList(machines.values)) {
            if (!machine.awaitTermination(remainingMillis())) return false
        }
        for (t in ArrayList(dedicatedThreads)) {
            t.join(remainingMillis().coerceAtLeast(1))
            if (t.isAlive) return false
        }
        if (!closed) return false
        dispatcher.thread.join(remainingMillis().coerceAtLeast(1))
        if (dispatcher.thread.isAlive) return false
        if (!timerService.awaitTermination(remainingMillis())) return false
        return workers.awaitTermination(remainingMillis())
    }

    // ---- カーネルの内部 ----

    internal fun removeMachine(machine: Machine) {
        machines.remove(machine.id)
    }

    internal fun enqueueNotify(machine: Machine) {
        notifyQueue.add(machine)
    }

    internal fun enqueueHostCalls(machine: Machine) {
        hostCallQueue.add(machine)
    }

    /** ブロッキング型のプロセスの枠を1つ取る。上限なら `false` */
    internal fun tryReserveBlocking(): Boolean {
        while (true) {
            val n = blockingCount.get()
            if (n >= config.maxBlockingProcesses) return false
            if (blockingCount.compareAndSet(n, n + 1)) return true
        }
    }

    internal fun releaseBlocking() {
        blockingCount.decrementAndGet()
    }

    /** 一時停止でなければ、[rt] を実行中に数えて `true`。カーネルロックの中から呼ぶ（このロックは末端） */
    internal fun tryActivate(rt: ProcessRuntime): Boolean = pauseLock.withLockValue {
        if (paused) return@withLockValue false
        if (!rt.active) {
            rt.active = true
            activeCount++
        }
        true
    }

    /** [rt] を実行中の数えから外す */
    internal fun deactivate(rt: ProcessRuntime) {
        if (!rt.active) return
        pauseLock.withLockValue {
            if (!rt.active) return@withLockValue
            rt.active = false
            activeCount--
            if (activeCount == 0) pauseCondition.signalAll()
        }
    }
}

/** テスト用の内部の設定 */
internal class SchedulerInternals(
    /** ワーカーのプールの代わりに使う Executor（テストで1本だけのワーカーにする） */
    val executor: ExecutorService? = null,
    /** 仕事のないワーカーが終わるまでの時間（仮に 30 秒） */
    val workerIdleMillis: Long = 30_000,
    /** 実行ファイル（`Machine.exec`）を読み込むランタイム（テストではダミーにする） */
    val runtime: WasmRuntime = EndiveRuntime(),
    /** `Machine.exec` で読み込むときに燃料の計測コードを挿入する（#38）。ダミーのランタイムで、本物の WASM でないバイト列を使うテストでは `false` */
    val meterExecutables: Boolean = true,
    /** `Machine.exec` で読み込むときに待機点への変換をかける（#329）。テストで、同じプログラムをブロッキングで動かして比べるときは `false` */
    val asyncifyExecutables: Boolean = true,
    /** `Machine.exec` で読み込む WASM の構造と時間の上限（#40）。`null` なら確かめない（本物の WASM でないバイト列を使うテスト） */
    val moduleLimits: ModuleLimits? = ModuleLimits.DEFAULT,
    /** タイマーの期限を待つ口（テストでは時計を手で進めるもの）。`null` なら [ExecutorTimerService] */
    val timers: TimerService? = null,
)

/** 一時停止の解除。`close()` で再開する（2回呼んでもよい）。 */
public fun interface PauseHandle : AutoCloseable {
    /** 一時停止を解く */
    override fun close()
}

/**
 * [MachineScheduler] の設定。[builder] で作る。値はどれも仮の値（確定は #251）。
 *
 * ```java
 * SchedulerConfig config = SchedulerConfig.builder().maxConcurrentMachines(2).build();
 * ```
 */
public class SchedulerConfig private constructor(builder: Builder) {
    /** 同時に WASM を実行するマシンの数の上限（仮: CPU のスレッド数の半分。最低 1） */
    public val maxConcurrentMachines: Int = builder.maxConcurrentMachines

    /** カーネルのスレッド（専用スレッドとワーカー）のスタックの大きさ（仮: 16 MiB）。予約するだけで、使った分しかメモリを取らない */
    public val threadStackBytes: Long = builder.threadStackBytes

    /** サーバー全体のブロッキング型プロセスの数（= 専用スレッドの数）の上限（仮: 2,048） */
    public val maxBlockingProcesses: Int = builder.maxBlockingProcesses

    /**
     * 実行ファイルの読み込み（燃料の計測コードの挿入・パース・コンパイル）を行うスレッドの数（仮: 1。#37）。
     * 優先度を下げたスレッドで、仕事がなければ終わる
     */
    public val compilerThreads: Int = builder.compilerThreads

    /**
     * 読み込んだ実行ファイルのうち、どのマシンも使っていなくても残しておく数（仮: 32。#37）。
     * 使っているものは数によらず共有する。1 つで Metaspace を約 1.3 MB（lua.wasm）使う
     */
    public val programCacheEntries: Int = builder.programCacheEntries

    /** [programCacheEntries] で残すものの、WASM のバイト数の合計の上限（仮: 32 MiB。#37） */
    public val programCacheBytes: Long = builder.programCacheBytes

    /** [SchedulerConfig] を組み立てる。 */
    public class Builder internal constructor() {
        internal var maxConcurrentMachines: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        internal var threadStackBytes: Long = 16L * 1024 * 1024
        internal var maxBlockingProcesses: Int = 2048
        internal var compilerThreads: Int = 1
        internal var programCacheEntries: Int = 32
        internal var programCacheBytes: Long = 32L * 1024 * 1024

        /** 同時に WASM を実行するマシンの数の上限（1 以上） */
        public fun maxConcurrentMachines(value: Int): Builder = apply {
            require(value >= 1) { "maxConcurrentMachines は 1 以上" }
            maxConcurrentMachines = value
        }

        /** スレッドのスタックの大きさ（バイト。1 以上） */
        public fun threadStackBytes(value: Long): Builder = apply {
            require(value >= 1) { "threadStackBytes は 1 以上" }
            threadStackBytes = value
        }

        /** ブロッキング型プロセスの数の上限（0 以上） */
        public fun maxBlockingProcesses(value: Int): Builder = apply {
            require(value >= 0) { "maxBlockingProcesses は 0 以上" }
            maxBlockingProcesses = value
        }

        /** 実行ファイルを読み込むスレッドの数（1 以上） */
        public fun compilerThreads(value: Int): Builder = apply {
            require(value >= 1) { "compilerThreads は 1 以上" }
            compilerThreads = value
        }

        /** 使われていなくても残す、読み込んだ実行ファイルの数（1 以上） */
        public fun programCacheEntries(value: Int): Builder = apply {
            require(value >= 1) { "programCacheEntries は 1 以上" }
            programCacheEntries = value
        }

        /** 使われていなくても残す、読み込んだ実行ファイルのバイト数の合計（1 以上） */
        public fun programCacheBytes(value: Long): Builder = apply {
            require(value >= 1) { "programCacheBytes は 1 以上" }
            programCacheBytes = value
        }

        /** 設定を作る。 */
        public fun build(): SchedulerConfig = SchedulerConfig(this)
    }

    public companion object {
        /** 既定の設定 */
        @JvmField
        public val DEFAULT: SchedulerConfig = Builder().build()

        /** 設定を組み立て始める。 */
        @JvmStatic
        public fun builder(): Builder = Builder()
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
