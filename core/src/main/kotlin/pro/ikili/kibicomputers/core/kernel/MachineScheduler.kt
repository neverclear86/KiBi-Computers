package pro.ikili.kibicomputers.core.kernel

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.persist.ModuleStore
import pro.ikili.kibicomputers.core.kernel.process.ProgramCache
import pro.ikili.kibicomputers.core.kernel.sched.Dispatcher
import pro.ikili.kibicomputers.core.kernel.sched.KernelThread
import pro.ikili.kibicomputers.core.kernel.sched.PermitPool
import pro.ikili.kibicomputers.core.kernel.sched.WorkerPool
import pro.ikili.kibicomputers.core.kernel.time.ExecutorTimerService
import pro.ikili.kibicomputers.core.kernel.time.TimerService
import pro.ikili.kibicomputers.core.runtime.WasmRuntime
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.validation.ModuleLimits
import java.util.Collections
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * コンピューター（[Machine]）を動かすスケジューラ。サーバーに1つ。接続層がサーバーの起動で作り、停止で [close] する（#43）。
 *
 * - 同時に WASM を実行するマシンの数は、公平な許可（[PermitPool]）で [SchedulerConfig.maxConcurrentMachines] までに抑える
 * - [tick] はメインスレッドから毎 tick 呼ぶ。tick の番号を進め、通知のあるマシンだけ [MachineHost] に配る。台数によらない
 * - 保存の前に [pauseAll] で全マシンを止める
 *
 * スレッド: [tick] はメインスレッドから呼ぶ。ほかはどのスレッドから呼んでもよい（[awaitTermination] はカーネルのスレッドから呼べない）。
 */
@ApiStatus.Internal
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

        /** 接続層の例外の投げた場所を決めるスタックの段数（標準ライブラリのフレームは数えない。#674） */
        private const val HOST_FAILURE_FRAMES = 3

        /** スタックトレースを出した、接続層の例外の場所を覚える数の上限（#674。仮の値） */
        private const val HOST_FAILURE_TRACE_LIMIT = 64

        /** 内部の設定（テスト用の差し替え）を付けてスケジューラを作る。 */
        @JvmSynthetic
        internal fun create(config: SchedulerConfig, options: SchedulerInternals): MachineScheduler = MachineScheduler(config, options)
    }

    private val tickCounter = AtomicLong()
    private val machines = ConcurrentHashMap<Long, Machine>()
    private val nextMachineId = AtomicLong(1)
    private val notifyQueue = ConcurrentLinkedQueue<Machine>()
    private val hostCallQueue = ConcurrentLinkedQueue<Machine>()

    /**
     * 通知・ホスト呼び出しを待つマシンの、持ち越しの列（#356）。[tick] の頭で [notifyQueue]・[hostCallQueue] から移し、前から処理する。
     * 予算で打ち切ったときの残りが、次の tick に残る。保存はしない（実行時だけのもの）。
     * スレッド安全でないので、[carryOwner] を取った [tick] だけが触る（重なった [tick] は触らない）
     */
    private val notifyCarry = ArrayDeque<Machine>()
    private val hostCallCarry = ArrayDeque<Machine>()

    /**
     * 持ち越しの列を触っている [tick] がある間 `true`。[tick] が重なっても（テストがほかのスレッドから呼ぶ・配る中や呼び出しの中から呼ぶ）、
     * 列を触るのは1つだけにする。取り合いではなく compare-and-set なので待たない（マシンのロックとの順を気にしなくてよい）。
     * 取る・放すで、前の [tick] が列に書いたものが次の [tick] から見える
     */
    private val carryOwner = AtomicBoolean()
    private val blockingCount = AtomicInteger()

    /** 接続層の例外のスタックトレースを、もうログに出した場所（種類・例外のクラス・呼んだ側のスタック。#674） */
    private val hostFailureTraced: MutableSet<List<Any>> = ConcurrentHashMap.newKeySet()

    /** [claimHostFailureTrace] の結果（#674） */
    internal enum class HostFailureTrace {
        /** まだ記録になかった場所。今回スタックトレースを出す */
        NEW,

        /** 記録済みの場所（スタックトレースはもう出した） */
        KNOWN,

        /** 記録が [HOST_FAILURE_TRACE_LIMIT] 個に達していて、新しい場所を覚えられない（スタックトレースはどこにも出ていない） */
        LIMIT_REACHED,
    }

    /**
     * 接続層の例外 [t]（種類は `Machine` の `HostFailureKind` の ordinal [kind]）の投げた場所で、スタックトレースを出すのが
     * スケジューラ全体で最初なら [HostFailureTrace.NEW]（以後は [HostFailureTrace.KNOWN]。#674）。
     * 場所は、種類・例外のクラス・スタックの上から [HOST_FAILURE_FRAMES] 個で決める。ただし、JDK と Kotlin の標準ライブラリの
     * フレーム（`ArrayList.get` が `Objects.checkIndex` を通って投げるなど）は数えず、それより下の呼んだ側のフレームを使う
     * （標準ライブラリの中だけで決めると、別々の呼び出し元からの例外が同じ場所になる）。スタックがすべて標準ライブラリなら、上から使う。
     * 覚える場所は [HOST_FAILURE_TRACE_LIMIT] 個まで（超えたら、新しい場所は [HostFailureTrace.LIMIT_REACHED]）
     */
    internal fun claimHostFailureTrace(kind: Int, t: Throwable): HostFailureTrace {
        val frames = t.stackTrace
        val site = ArrayList<Any>(HOST_FAILURE_FRAMES + 2)
        site += kind
        site += t.javaClass
        for (frame in frames) {
            if (site.size - 2 >= HOST_FAILURE_FRAMES) break
            if (!isLibraryFrame(frame)) site += frame
        }
        if (site.size == 2) {
            for (i in 0 until minOf(HOST_FAILURE_FRAMES, frames.size)) site += frames[i]
        }
        if (site in hostFailureTraced) return HostFailureTrace.KNOWN
        if (hostFailureTraced.size >= HOST_FAILURE_TRACE_LIMIT) return HostFailureTrace.LIMIT_REACHED
        return if (hostFailureTraced.add(site)) HostFailureTrace.NEW else HostFailureTrace.KNOWN
    }

    /** JDK か Kotlin の標準ライブラリのフレーム（[claimHostFailureTrace] の場所に数えない） */
    private fun isLibraryFrame(frame: StackTraceElement): Boolean {
        val name = frame.className
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.") ||
            name.startsWith("sun.") || name.startsWith("com.sun.") || name.startsWith("kotlin.")
    }

    /** 期限の tick に起こすマシン（[wakeAtTick]。今はゲストの再起動の待ちだけ。#635）。期限の早い順。[wakeLock] の中で触る */
    private val wakeQueue = PriorityQueue<TickWake>(compareBy<TickWake> { it.tick }.thenBy { it.seq })
    private val wakeLock = ReentrantLock()
    private var wakeSeq = 0L

    /** ブロッキング型の専用スレッド（終わるのを待つため） */
    internal val dedicatedThreads: MutableSet<Thread> = Collections.newSetFromMap(ConcurrentHashMap())

    internal val workers = WorkerPool(config.threadStackBytes, options.workerIdleMillis, options.executor)

    /**
     * 同時に WASM を実行するマシンの数の許可（公平な FIFO。#350）。待機点型の step は、許可を受け取ってからワーカーに渡すので、
     * 許可を待つ間はスレッドを持たない
     */
    internal val permits = PermitPool(config.maxConcurrentMachines, workers)

    /**
     * 起動の手順（`BootSequence`）と復元の前段（`MachineRestore`）を動かすスレッド `KiBi-boot-*`（#350。[SchedulerConfig.maxConcurrentMachines] 本まで、
     * 残りは待ち行列）。ホストの I/O・前の起動のスレッドを待って止まるので、ワーカーとは分ける。
     * モジュールのコンパイルは、スレッドを持たずに待ち、終わったら続きをここに積み直す（init は #358、復元は #577）。
     * 1,000 台を一度に起動しても、スレッドは台数分できない（メインスレッドが作るのも、この本数まで）
     */
    internal val bootExecutor: ThreadPoolExecutor = run {
        val counter = AtomicInteger()
        val n = config.maxConcurrentMachines
        ThreadPoolExecutor(n, n, options.workerIdleMillis, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) { r ->
            KernelThread("KiBi-boot-${counter.incrementAndGet()}", config.threadStackBytes, r)
        }.apply { allowCoreThreadTimeOut(true) }
    }
    internal val dispatcher = Dispatcher<Machine>(config.threadStackBytes) { machine -> machine.dispatchFuelWaiters() }

    internal val hooks = KernelTestHooks()

    /** 実行ファイルの読み込み（変換・挿入・パース・コンパイル）を行うスレッド（#37。[SchedulerConfig.compilerThreads] 本まで） */
    private val compilerExecutor = ProgramCache.compilerExecutor(config.compilerThreads, config.threadStackBytes)

    /** 読み込んだ実行ファイルの共有（#37。`Machine.exec` が使う） */
    internal val programs: ProgramCache = ProgramCache(config.programCacheEntries, config.programCacheBytes, compilerExecutor)

    /**
     * サーバー全体の RAM の勘定（#362。上限は [SchedulerConfig.totalMemoryBytes]）。各マシンの勘定（[Machine.memoryAccount]）の親。
     * 全体の残りが足りなければ、どのマシンでも `memory.grow`・`/tmp` への書き込み・プロセスの生成を断る
     */
    internal val memoryAccount: MemoryAccount = MemoryAccount(config.totalMemoryBytes)

    /** タイマーの期限を待つ口（#64、ADR 0064。既定はスレッド `KiBi-timer` 1本） */
    internal val timerService: TimerService = options.timers ?: ExecutorTimerService(TIMER_STACK_BYTES)

    /** 待機点保存が指すモジュールの置き場（#82） */
    internal val moduleStore: ModuleStore = options.moduleStore ?: config.moduleStore

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

    /** [tick] で、通知かホスト呼び出しを予算で打ち切った（まだ残りがあるのに止めた）tick の数（数え口。#356。配り切った・実行し切った tick は数えない） */
    internal val budgetCutTicks = AtomicLong()

    /** 予算で打ち切ったとき、手を付けずに次の tick へ持ち越したマシンの数の合計（数え口。#356。段ごとに数える） */
    internal val carriedMachines = AtomicLong()

    /** [tick] が、ほかの [tick] と重なったので、通知とホスト呼び出しを処理せずに返した数（数え口。#356） */
    internal val overlappedTicks = AtomicLong()

    /** [tick] で通知を配るのにかかった時間の合計（ナノ秒。数え口。#356） */
    internal val notifyNanos = AtomicLong()

    /** [tick] でホスト呼び出しを実行するのにかかった時間の合計（ナノ秒。数え口。#356） */
    internal val hostCallNanos = AtomicLong()

    /** `stop()` が送った割り込みの数（数え口） */
    internal val interruptsSent = AtomicLong()

    /** 燃料切れで巻き戻し、スレッドを持たずに燃料を待った回数（数え口。#339） */
    internal val fuelUnwinds = AtomicLong()

    /** 燃料切れで、スレッドを持ったまま燃料を待った回数（数え口。#339） */
    internal val fuelBlockingWaits = AtomicLong()

    /** 止まりうる関数でない関数の中の燃料切れで、前借りして先へ進めた回数（数え口。#477） */
    internal val fuelOverdrafts = AtomicLong()

    init {
        dispatcher.start()
    }

    /** 今の tick の番号 */
    internal val now: Long get() = tickCounter.get()

    internal val isClosed: Boolean get() = closed

    /** 一時停止の印（カーネルロックの中から読む） */
    internal val isPaused: Boolean get() = pauseLock.withLockValue { paused }

    /** 全体の一時停止か、[machine] が止められている（`Machine.quiesce`。#81） */
    internal fun isPausedFor(machine: Machine): Boolean = machine.isQuiesced || isPaused

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
     * その呼び出しをこのスレッドで実行する（1台あたり [MachineConfig.maxHostCallsPerTick] まで）。
     * 台数によらない（通知と呼び出しの数にだけ比例する）。期限の tick に起こすマシン（再起動の待ち。#635）は、
     * 期限が来たものだけ起こしてから通知を配る（期限が来ていなければ、列の先頭を見るだけ。予算の外）。
     *
     * 通知とホスト呼び出しには、サーバー全体の tick ごとの時間の予算（[SchedulerConfig.mainThreadBudgetNanos]。#356、ADR 0356）を置く。
     * 通知は予算の半分まで、ホスト呼び出しは tick の頭から予算いっぱいまで。予算が尽きたら、手を付けていないマシンは次の tick の先頭に、
     * 途中まで処理したマシンは次の tick の後ろに回す（巡回）。どちらの段も、毎 tick 少なくとも1台（ホスト呼び出しは1件）は進める。
     *
     * ほかの [tick] と重なったとき（ほかのスレッドから、または通知・ホスト呼び出しの中から呼んだとき）は、tick の番号を進め、
     * 期限の来たマシンを起こすところまでして、通知とホスト呼び出しは処理せずに返す（列に残り、後の tick で処理する。[overlappedTicks]）
     */
    public fun tick() {
        val now = tickCounter.incrementAndGet()
        dispatcher.signal()
        val batch = ArrayList<Machine>()
        // マシンのロックの中で列のロックを取る（wakeAtTick）ので、列のロックを放してからマシンを呼ぶ
        wakeLock.withLockValue {
            while (true) {
                val head = wakeQueue.peek() ?: break
                if (head.tick > now) break
                wakeQueue.poll()
                batch += head.machine
            }
        }
        if (batch.isNotEmpty()) {
            tickVisitedMachines.addAndGet(batch.size.toLong())
            for (machine in batch) machine.wakeDeferredReboot()
            batch.clear()
        }
        // 持ち越しの列はスレッド安全でない。重なった tick は触らずに返す
        if (!carryOwner.compareAndSet(false, true)) {
            overlappedTicks.incrementAndGet()
            return
        }
        try {
            // 列から取り出すのは tick の頭の1回だけ（配る中で積み直したマシンは、次の tick に回る）
            while (true) notifyCarry.addLast(notifyQueue.poll() ?: break)
            val budget = TickBudget(options.nanoTime, config.mainThreadBudgetNanos)
            var cut = false
            if (notifyCarry.isNotEmpty()) cut = deliverNotifications(budget)
            // 通知の中で出したホスト呼び出しも、この tick で実行する（取り出すのはこの1回だけ）
            while (true) hostCallCarry.addLast(hostCallQueue.poll() ?: break)
            if (hostCallCarry.isNotEmpty()) cut = runHostCalls(budget) || cut
            if (cut) budgetCutTicks.incrementAndGet()
        } finally {
            carryOwner.set(false)
        }
    }

    /**
     * 持ち越しの列の前から、通知を配る（マシンの単位。1台の途中では切らない）。台の間で、tick の頭から予算の半分を過ぎていたら止める
     * （1台目は必ず配る）。予算のために、配っていない台を残して止めたら `true`（配り切ったなら、時間が半分を超えても `false`）。配ったマシンは列から外れる（配る中で積み直したら、[notifyQueue] から次の tick へ）。
     * JVM の致命的なエラー（[Machine] の `safely` が投げ直す）で途中で抜けるときは、投げたマシンの残りを次の tick に回してから投げ直す
     * （まだ配っていないマシンは持ち越しの列に残る。`queuedForNotify` が立ったまま、列にも載っていない状態にしない。#674）
     */
    private fun deliverNotifications(budget: TickBudget): Boolean {
        val limit = budget.limitNanos / 2
        // 時計を読み始める（tick の頭）
        val start = budget.elapsed()
        var visited = 0L
        var cut = false
        try {
            while (true) {
                // 残りがあるときだけ「打ち切った」（配り切ったなら、時間が半分を超えていても数えない）
                if (visited > 0 && notifyCarry.isNotEmpty() && budget.elapsed() >= limit) {
                    cut = true
                    break
                }
                val machine = notifyCarry.removeFirstOrNull() ?: break
                visited++
                try {
                    machine.deliverNotifications()
                } catch (t: Throwable) {
                    machine.requestNotifyAfterAbort()
                    throw t
                }
            }
        } finally {
            tickVisitedMachines.addAndGet(visited)
            notifyNanos.addAndGet(budget.elapsed() - start)
        }
        if (cut) carriedMachines.addAndGet(notifyCarry.size.toLong())
        return cut
    }

    /**
     * 持ち越しの列の前から、マシンごとにホスト呼び出しを実行する。tick の頭から予算を過ぎたら止める（毎 tick 1件目は必ず実行する）。
     * 予算のために、実行していない呼び出しを残して止めたら `true`（最後の1件で予算を超えただけで、残りがなければ `false`）。
     * 手を付けていないマシンは持ち越しの列の前に残り、実行したうえでまだ残っているマシン
     * （1台の上限か予算に達した）は、この tick の終わりに列の後ろへ回す（この tick の中では2回処理しない）。
     * JVM の致命的なエラーで抜けるときも、実行していたマシンを列に戻す（`queuedForHostCalls` が立ったまま列にない状態にしない）
     */
    private fun runHostCalls(budget: TickBudget): Boolean {
        val start = budget.elapsed()
        val again = ArrayList<Machine>()
        var visited = 0L
        // 予算が尽きたとき、止めた台に実行していない呼び出しが残っている
        var leftover = false
        try {
            while (!budget.expired) {
                val machine = hostCallCarry.removeFirstOrNull() ?: break
                visited++
                // 投げたときも列に戻す（空なら、次に回ってきたときに印を下ろす）
                again += machine
                if (!machine.runHostCalls(budget)) again.removeAt(again.size - 1) else if (budget.expired) leftover = true
            }
        } finally {
            tickVisitedMachines.addAndGet(visited)
            hostCallNanos.addAndGet(budget.elapsed() - start)
            hostCallCarry.addAll(again)
        }
        if (!budget.expired) return false
        val untouched = hostCallCarry.size - again.size
        carriedMachines.addAndGet(untouched.toLong())
        return untouched > 0 || leftover
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
        wakeLock.withLockValue { wakeQueue.clear() }
        dispatcher.close()
        timerService.close()
        workers.shutdown()
        bootExecutor.shutdown()
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
        if (!bootExecutor.awaitTermination(remainingMillis(), TimeUnit.MILLISECONDS)) return false
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

    /**
     * [machine] を tick [tick] に起こす（`Machine.wakeDeferredReboot` を、その tick の `tick()` の中、通知を配る前に呼ぶ。#635）。
     * どのスレッドから呼んでもよい（カーネルロックの中からも。この列のロックは末端）。取り消す口はない:
     * 起こされた側が、まだ待っているかを自分で確かめる
     */
    internal fun wakeAtTick(machine: Machine, tick: Long) {
        if (closed) return
        wakeLock.withLockValue { wakeQueue.add(TickWake(tick, wakeSeq++, machine)) }
    }

    /** 期限の tick に起こす列に入れたマシンの数（テスト用） */
    internal val pendingTickWakes: Int get() = wakeLock.withLockValue { wakeQueue.size }

    private class TickWake(val tick: Long, val seq: Long, val machine: Machine)

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
        // 全体の一時停止と、マシン1台だけを止める印（Machine.quiesce。#81）
        if (paused || rt.machine.isQuiesced) return@withLockValue false
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
    /** モジュールの置き場（#82。テストのメモリ上のもの）。`null` なら [SchedulerConfig.moduleStore] */
    val moduleStore: ModuleStore? = null,
    /** ゲストの再起動で起動し直すまでの待ち（#635。テストで短い値や [RebootBackoff.NONE] にする） */
    val rebootBackoff: RebootBackoff = RebootBackoff.DEFAULT,
    /** tick ごとの予算（#356）を測る時計（ナノ秒。テストで手で進めるものにする） */
    val nanoTime: () -> Long = System::nanoTime,
)

/**
 * 1回の `tick()` の中の、メインスレッドの時間の予算（#356）。[limitNanos] は tick の頭からの上限。
 * 時計は最初に要るときに読み始める（通知もホスト呼び出しもない tick では読まない）。メインスレッドだけで使う
 */
internal class TickBudget(private val clock: () -> Long, val limitNanos: Long) {
    private var start = 0L
    private var started = false

    /** 予算を使い切った（[check] が見た）。いったん立ったら下ろさない */
    var expired: Boolean = false
        private set

    /** tick の頭（最初に読んだとき）からの経過時間 */
    fun elapsed(): Long {
        val t = clock()
        if (!started) {
            started = true
            start = t
        }
        return t - start
    }

    /** 1件を処理した後に呼ぶ。予算を使い切ったら `true`（以後 [expired]） */
    fun check(): Boolean {
        if (!expired && elapsed() >= limitNanos) expired = true
        return expired
    }
}

/** 一時停止の解除。`close()` で再開する（2回呼んでもよい）。 */
@ApiStatus.Internal
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
@ApiStatus.Internal
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

    /**
     * サーバー全体の RAM の上限（バイト。#362）。全マシンの RAM の使用量（線形メモリ・テーブル・`/tmp` の中身）の合計の上限。
     * 超える `memory.grow` は -1、`/tmp` への書き込みは `ENOSPC`、最初のメモリを確保できないプロセスは作れない。
     * 仮: JVM のヒープの最大値（`Runtime.maxMemory()`）の半分（確定は #251）
     */
    public val totalMemoryBytes: Long = builder.totalMemoryBytes ?: defaultTotalMemoryBytes()

    /**
     * 待機点保存が指すモジュールの置き場（#82。ワールドごと。接続層が `FileModuleStore` を渡す）。
     * 既定は [ModuleStore.NONE]（置き場なし。プロセスは保存できない）
     */
    public val moduleStore: ModuleStore = builder.moduleStore

    /**
     * メインスレッドで行うカーネルの処理（通知の配布とホスト呼び出し）の、サーバー全体の tick ごとの時間の予算（ナノ秒。#356、ADR 0356）。
     * 通知は半分まで、ホスト呼び出しは通知の残りを含めて全部まで使う。超えた分は次の tick 以降に持ち越す（捨てない・断らない）。
     * 1件で超えることはある（呼び出しは途中で止められない）が、毎 tick 少なくとも1件は進める。
     * 仮: 5 ms（1 tick の 1 割。確定は #251）
     */
    public val mainThreadBudgetNanos: Long = builder.mainThreadBudgetNanos

    /** [SchedulerConfig] を組み立てる。 */
    public class Builder internal constructor() {
        internal var maxConcurrentMachines: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        internal var threadStackBytes: Long = 16L * 1024 * 1024
        internal var maxBlockingProcesses: Int = 2048
        internal var compilerThreads: Int = 1
        internal var programCacheEntries: Int = 32
        internal var programCacheBytes: Long = 32L * 1024 * 1024
        internal var totalMemoryBytes: Long? = null
        internal var moduleStore: ModuleStore = ModuleStore.NONE
        internal var mainThreadBudgetNanos: Long = 5_000_000

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

        /** サーバー全体の RAM の上限（バイト。0 以上）。設定しなければ JVM のヒープの最大値の半分 */
        public fun totalMemoryBytes(value: Long): Builder = apply {
            require(value >= 0) { "totalMemoryBytes は 0 以上" }
            totalMemoryBytes = value
        }

        /** 待機点保存が指すモジュールの置き場（#82） */
        public fun moduleStore(value: ModuleStore): Builder = apply { moduleStore = value }

        /** メインスレッドの tick ごとの時間の予算（ナノ秒。1 以上。`Long.MAX_VALUE` で実質なし。#356） */
        public fun mainThreadBudgetNanos(value: Long): Builder = apply {
            require(value >= 1) { "mainThreadBudgetNanos は 1 以上" }
            mainThreadBudgetNanos = value
        }

        /** 設定を作る。 */
        public fun build(): SchedulerConfig = SchedulerConfig(this)
    }

    public companion object {
        /** [totalMemoryBytes] の既定: JVM のヒープの最大値の半分（仮。#251） */
        private fun defaultTotalMemoryBytes(): Long = Runtime.getRuntime().maxMemory() / 2

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
