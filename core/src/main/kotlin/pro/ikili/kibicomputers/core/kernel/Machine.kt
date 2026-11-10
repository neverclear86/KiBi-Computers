package pro.ikili.kibicomputers.core.kernel

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.core.device.DeviceBus
import pro.ikili.kibicomputers.core.device.DeviceDispatcher
import pro.ikili.kibicomputers.core.device.DeviceLimits
import pro.ikili.kibicomputers.core.device.PendingDeviceCall
import pro.ikili.kibicomputers.core.kernel.boot.BootCancelledException
import pro.ikili.kibicomputers.core.kernel.boot.BootSequence
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.MachineRestore
import pro.ikili.kibicomputers.core.kernel.boot.PreparedRestore
import pro.ikili.kibicomputers.core.kernel.wait.ProcessWaiter
import pro.ikili.kibicomputers.core.kernel.wait.WaitSet
import pro.ikili.kibicomputers.core.persist.ColdBootCause
import pro.ikili.kibicomputers.core.persist.RestoreOutcome
import pro.ikili.kibicomputers.core.persist.RestoreRequest
import pro.ikili.kibicomputers.core.kernel.event.EventFilter
import pro.ikili.kibicomputers.core.kernel.event.EventLimits
import pro.ikili.kibicomputers.core.kernel.event.EventQueue
import pro.ikili.kibicomputers.core.kernel.event.EventValue
import pro.ikili.kibicomputers.core.kernel.event.KernelEvent
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.fd.STDERR
import pro.ikili.kibicomputers.core.kernel.fd.STDIN
import pro.ikili.kibicomputers.core.kernel.fd.STDOUT
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.obj.HandleEntry
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.DEFAULT_HANDLE_LIMIT
import pro.ikili.kibicomputers.core.kernel.process.KillResult
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.PendingExec
import pro.ikili.kibicomputers.core.kernel.process.Process
import pro.ikili.kibicomputers.core.kernel.process.ProcessArgs
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriver
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriverFactory
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessLifecycle
import pro.ikili.kibicomputers.core.kernel.process.ProcessHandle
import pro.ikili.kibicomputers.core.kernel.process.ProcessObject
import pro.ikili.kibicomputers.core.kernel.process.ProcessRunner
import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.ProgramLoadBusyException
import pro.ikili.kibicomputers.core.kernel.process.Shebang
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.sched.KernelThread
import pro.ikili.kibicomputers.core.kernel.sched.MachineCpu
import pro.ikili.kibicomputers.core.kernel.time.ClockSnapshot
import pro.ikili.kibicomputers.core.kernel.time.MachineTimers
import pro.ikili.kibicomputers.core.kernel.time.TimeJump
import pro.ikili.kibicomputers.core.kernel.time.Timer
import pro.ikili.kibicomputers.core.kernel.time.TimerLimits
import pro.ikili.kibicomputers.core.kernel.time.TimerService
import pro.ikili.kibicomputers.core.kernel.time.saturatingSub
import pro.ikili.kibicomputers.core.kernel.tty.Tty
import pro.ikili.kibicomputers.core.kernel.tty.TtyLimits
import pro.ikili.kibicomputers.core.kernel.tty.TtySignal
import pro.ikili.kibicomputers.core.kernel.tty.TtySize
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.DevFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.FileType
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.HostFileBudget
import pro.ikili.kibicomputers.core.kernel.vfs.OpenFile
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TmpFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.Vfs
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.vfs.fsError
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.wait.WaitRegistry
import pro.ikili.kibicomputers.core.kernel.wait.WakeSink
import pro.ikili.kibicomputers.core.machine.isFatal
import pro.ikili.kibicomputers.core.kernel.process.BaseRef
import pro.ikili.kibicomputers.core.kernel.process.MemoryBase
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.DeltaBase
import pro.ikili.kibicomputers.core.persist.MachineSaveConfig
import pro.ikili.kibicomputers.core.runtime.StateCaptureMode
import pro.ikili.kibicomputers.core.persist.ColdBootReason
import pro.ikili.kibicomputers.core.persist.KernelStateCopy
import pro.ikili.kibicomputers.core.kernel.process.SnapshotCapableDriver
import pro.ikili.kibicomputers.core.persist.MachineSnapshotSource
import pro.ikili.kibicomputers.core.persist.QuiesceHandle
import pro.ikili.kibicomputers.core.persist.SaveReadiness
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.validation.ModuleLimits
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/** マシンの状態 */
@ApiStatus.Internal
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

/**
 * マシンの設定。[builder] で作る。値はどれも仮の値（確定は #47・#251）。
 */
@ApiStatus.Internal
public class MachineConfig private constructor(builder: Builder) {
    /** 1 tick に配る燃料。1回に渡す燃料の上限でもある（仮: 1 tick 約 2 ms 相当） */
    public val fuelPerTick: Long = builder.fuelPerTick

    /** プロセスの数の上限（終わっていないプロセスと、親が刈り取っていないゾンビの合計。仮: 64。#46） */
    public val maxProcesses: Int = builder.maxProcesses

    /** 1 tick にメインスレッドで実行するホスト呼び出し（ワールド操作など）の数の上限（仮: 64）。残りは次の tick へ */
    public val maxHostCallsPerTick: Int = builder.maxHostCallsPerTick

    /**
     * コンピューター全体の RAM（バイト。#39）。全プロセスの線形メモリ（初めて書き込んだ 4 KiB の区画の数。#407）などの合計の上限で、
     * 64 KiB（1 ページ）単位に切り捨てて使う。
     * 仮: 32 MiB（RAM のアイテムで変わる。#123。wasi-libc の既定のスタックが 16 MiB なので、それより大きくしている）
     */
    public val memoryBytes: Long = builder.memoryBytes

    /**
     * 1 プロセスの線形メモリ（書き込んだ区画。#407）の合計の上限（バイト。#39）。64 KiB 単位に切り捨てて使う。
     * 仮: [memoryBytes] と同じ（1つのプロセスが RAM をすべて使ってよい）
     */
    public val maxProcessMemoryBytes: Long = builder.maxProcessMemoryBytes ?: builder.memoryBytes

    /** RAM のページ数 */
    internal val memoryPages: Int get() = pagesOf(memoryBytes)

    /** 1 プロセスのページ数の上限 */
    internal val maxProcessMemoryPages: Int get() = pagesOf(maxProcessMemoryBytes)

    /** [MachineConfig] を組み立てる。 */
    public class Builder internal constructor() {
        internal var fuelPerTick: Long = 1_000_000
        internal var maxProcesses: Int = 64
        internal var maxHostCallsPerTick: Int = 64
        internal var memoryBytes: Long = 32L * 1024 * 1024
        internal var maxProcessMemoryBytes: Long? = null

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

        /** 1 tick に実行するホスト呼び出しの数の上限（1 以上） */
        public fun maxHostCallsPerTick(value: Int): Builder = apply {
            require(value >= 1) { "maxHostCallsPerTick は 1 以上" }
            maxHostCallsPerTick = value
        }

        /** コンピューター全体の RAM（バイト。1 ページ = 64 KiB 以上） */
        public fun memoryBytes(value: Long): Builder = apply {
            require(value >= WasmMemory.PAGE_SIZE) { "memoryBytes は ${WasmMemory.PAGE_SIZE} 以上" }
            memoryBytes = value
        }

        /** 1 プロセスの線形メモリの上限（バイト。1 ページ = 64 KiB 以上）。設定しなければ [memoryBytes] と同じ */
        public fun maxProcessMemoryBytes(value: Long): Builder = apply {
            require(value >= WasmMemory.PAGE_SIZE) { "maxProcessMemoryBytes は ${WasmMemory.PAGE_SIZE} 以上" }
            maxProcessMemoryBytes = value
        }

        /** 設定を作る。 */
        public fun build(): MachineConfig = MachineConfig(this)
    }

    public companion object {
        /** バイト数をページ数にする（切り捨て。32 ビットのメモリの上限まで） */
        private fun pagesOf(bytes: Long): Int = minOf(bytes / WasmMemory.PAGE_SIZE, WasmMemory.MAX_PAGES.toLong()).toInt()

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
@ApiStatus.Internal
public class Machine private constructor(
    internal val scheduler: MachineScheduler,
    /** スケジューラの中の番号（保存しない。実行時だけ） */
    public val id: Long,
    internal val host: MachineHost,
    /** 設定 */
    public val config: MachineConfig,
    /** 動く環境（時計、乱数、ディスク、保存先。#44） */
    public val environment: MachineEnvironment,
) : AutoCloseable {
    /** カーネルロック。持ったまま待たない・WASM を呼ばない・[MachineHost] を呼ばない */
    internal val lock = ReentrantLock()

    /** カーネルの状態（保存の対象） */
    internal val kernel = KernelState(config.maxProcesses).also {
        it.clocks.start(environment.clock, scheduler.now)
    }

    /** タイマーの期限の予定（実行時だけ。#64、ADR 0064） */
    internal val timers: MachineTimers = MachineTimers(scheduler.timerService) { timerId, generation -> onTimerDue(timerId, generation) }

    /** 燃料の遅延補充 */
    internal val fuelAccount = FuelAccount(kernel, config.fuelPerTick, scheduler.now)

    /** 線形メモリの使用量（RAM。#39）。親はサーバー全体の勘定（#362） */
    internal val memoryAccount = MemoryAccount(config.memoryPages, scheduler.memoryAccount)

    /**
     * 仮想ファイルシステム（マウントの表は [kernel] の中。ファイルシステムは実行時だけ）。ファイルシステムに届く操作は
     * カーネルロックの外で使う（#52。ADR 0052）。閉じたファイルの知らせは、起動用のスレッドでロックの外で行う
     */
    internal val vfs = Vfs(kernel.mounts) { lock.isHeldByCurrentThread }.also { v ->
        v.onDeferred = { scheduleVfsDrain() }
    }

    /** このマシンが開いたままにできるホストのファイルの枠（ディスク。#52） */
    internal val hostFiles = HostFileBudget()

    /** [vfs] の後で行う知らせを、起動用のスレッドに積んだか（積むのは1つだけ） */
    private val vfsDrainScheduled = AtomicBoolean()

    /** 待ち手の表（実行時だけ） */
    internal val registry = WaitRegistry()

    /**
     * オブジェクトの操作に渡す知らせ口。[registry] に渡し、コンソールの TTY に出力がたまったら、次の tick で配るよう印を付ける。
     * カーネルの中でオブジェクトの状態を変えるときは、[registry] ではなくこれを使う
     */
    internal val wakeSink: WakeSink = object : WakeSink {
        override fun wake(key: WaitKey) = registry.wake(key)

        override fun wakeObject(objectId: Int) = registry.wakeObject(objectId)

        override fun outputPending(objectId: Int) {
            if (objectId != 0 && objectId == kernel.consoleTtyId) markConsoleDirty()
        }

        override fun timerClosed(objectId: Int) = timers.cancel(objectId)

        override fun deviceCallClosed(objectId: Int) {
            pendingDeviceCalls.remove(objectId)?.cancelLocked()
        }

        override fun fileClosed(file: OpenFile) = vfs.releaseLater(file)

        override fun processHandleClosed(holder: Process, obj: ProcessObject) = lifecycle.onProcessHandleClosedLocked(holder, obj)
    }

    /** [vfs] の後で行う知らせ（閉じたファイル、外したファイルシステム）を、起動用のスレッドでロックの外で行う（カーネルロックの中から呼んでよい） */
    private fun scheduleVfsDrain() {
        if (!vfsDrainScheduled.compareAndSet(false, true)) return
        val drain = Runnable {
            vfsDrainScheduled.set(false)
            vfs.drainDeferred()
        }
        try {
            scheduler.bootExecutor.execute(drain)
        } catch (e: RejectedExecutionException) {
            // スケジューラが閉じた。ロックを持っていなければこのスレッドで行い、持っていれば次の VFS の操作に任せる
            vfsDrainScheduled.set(false)
            if (!lock.isHeldByCurrentThread) vfs.drainDeferred()
        }
    }

    /**
     * コンソールの TTY のシグナル（Ctrl+C など）の受け口。既定は、TTY の前のプロセスに `INTERRUPT`・`QUIT` を送る
     * （前のプロセスがない・終わっている・init なら何もしない。#46、ADR 0046）。テストで差し替える
     */
    @Volatile
    internal var ttySignalHandler: TtySignalHandler = DEFAULT_TTY_SIGNAL_HANDLER

    /** コンソールの出力に、まだ配っていないバイトがあるかもしれない */
    private val consoleDirty = AtomicBoolean(false)

    /** pid → 実行時だけのもの */
    internal val runtimes = HashMap<Int, ProcessRuntime>()

    internal val runner = ProcessRunner(this)

    /** 終わりの記録・刈り取り・シグナル（#46） */
    internal val lifecycle = ProcessLifecycle(this)

    /** マシンの CPU */
    internal val cpu = MachineCpu(
        // CPU を渡したら、許可の待ち行列に並べる。許可を受け取ってからワーカーに渡す（#350）
        onThreadlessGranted = { rt -> scheduler.permits.submit(rt) { runner.runStep(rt) } },
        onThreadlessRevoked = { rt -> scheduler.permits.remove(rt) },
    )

    @Volatile
    private var stateValue = MachineState.STOPPED

    private var crashCause: Throwable? = null

    /** 登録されているスレッドの数（カーネルロック） */
    private var registeredThreads = 0

    private val terminated = lock.newCondition()

    private val notifications = ConcurrentLinkedQueue<HostNotification>()
    private val queuedForNotify = AtomicBoolean(false)

    /**
     * コンソールの出力を止めている相手（#357。画面の処理が追いつかないとき [holdConsoleOutput] で止める）。
     * `null` なら止めていない。メインスレッドだけで書く（[consoleSink] があれば、そのロックの中で）。
     * プロセスのスレッドは [pumpConsole] で、[consoleSink] のロックの中で読む（#355）
     */
    @Volatile
    private var consoleHold: ConsoleHoldListener? = null

    /**
     * コンソールの出力を書く先（表示デバイス。#355）。あれば、コンソールの TTY に書いたプロセスが、自分のスレッドで
     * [pumpConsole] から書く（端末の処理の重さは、そのプロセスの燃料で払う）。カーネルのメッセージと、プロセス以外が TTY に
     * 積んだ出力（キー入力のエコーなど）は、これまでどおり [MachineHost.onConsoleOutput] でメインスレッドに配る。
     * メインスレッドから入れ替える（`ScreenDevice.machine`）
     */
    @Volatile
    internal var consoleSink: ConsoleSink? = null

    /**
     * 止めている間に配らなかったコンソールの出力（[HostNotification.Console] と、TTY の出力の印 [HostNotification.TtyOutput]）。
     * 書いた順に並べる。TTY から取り出すのは再開したとき。TTY の印は [consoleDirty] を下ろさずに持つので、多くても1つ
     * （印の後に TTY に書いた分は、カーネルのメッセージの目印 [HostNotification.Console.ttyMark] で前後を分ける。#381）。
     * メインスレッドだけで読み書きする
     */
    private var deferredConsole: ArrayList<HostNotification>? = null

    /** 配っていないコンソール出力のバイト数（上限を超えた分は捨てる） */
    private val pendingConsoleBytes = AtomicLong()

    /** 捨てたコンソール出力のバイト数（数え口） */
    internal val droppedConsoleBytes = AtomicLong()

    /** メインスレッドで実行するのを待つホスト呼び出し（カーネルロック） */
    private val hostCalls = ArrayDeque<PendingHostCall<*>>()
    private val queuedForHostCalls = AtomicBoolean(false)

    /**
     * 実行に回して、まだ終わっていない待たずに積んだデバイスの呼び出し（`PendingDeviceCall` のオブジェクトID → 始めた呼び出し。#532）。
     * 最後の fd を閉じたら取り消す（[WakeSink.deviceCallClosed]）。終わったら外す。カーネルロック
     */
    private val pendingDeviceCalls = HashMap<Int, DeviceDispatcher.Started>()

    /**
     * つないでいるデバイスの表（#65、ADR 0065）。接続層が `attach`・`detach` でデバイスをつなぐ（どのスレッドからも呼べる。待たない）。
     * 表を変えるたびに世代（`DeviceBus.generation`）が上がる（ADR 0044 の変更の知らせを置き換えた。ADR 0065）
     */
    public val devices: DeviceBus = DeviceBus(this)

    // ---- 起動（#49）。どれも実行時だけで、保存しない（カーネルロック）。init の pid は [KernelState.initPid]（保存の対象。#365） ----

    /**
     * 起動の手順が動いている（受け付けてから init を作るか、失敗・取り消しで終わるまで）。
     * 実行時だけの値。立っている間は [isAtWaitPoint] が `false` なので、起動の途中の状態は保存されない（#365）
     */
    @Volatile
    private var bootingValue = false

    /** 起動の途中で `stop()`・`reboot()` された */
    @Volatile
    private var bootCancelled = false

    /**
     * 起動の取り消しを、init の読み込み（コンパイル）を待つ起動の手順に伝える（#344）。[boot] で作り直し、[stopLocked] で終える。
     * カーネルロックの中で書く
     */
    @Volatile
    private var bootCancelSignal: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)

    /** 最後に受け付けた [boot] の設定。実行時だけの値（戻したマシンでは、接続層が渡し直す。ADR 0049・#81） */
    @Volatile
    private var lastBootConfigValue: BootConfig? = null

    /** 再起動を求められ、プロセスがすべて止まるのを待っている。実行時だけの値。立っている間は [isAtWaitPoint] が `false`（#365） */
    private var rebootRequested = false

    /**
     * 再起動の求めを通知の列に積み、まだ配っていない（`stop()` で取り消す）。実行時だけの値。
     * この間はプロセスが0個の [MachineState.STOPPED] だが、ここで保存すると再起動が消えるので、[isAtWaitPoint] は `false`（#365）
     */
    private var rebootNotified = false

    // ---- 復元（#85、ADR 0085）。どれも実行時だけで、保存しない ----

    /**
     * 復元を受け付けてから、表に入れ終える（後段）か、冷えた起動待ちに切り替える（前段の失敗）か、取り消すまで立つ。
     * 立っている間は、コンソールの入力・大きさの変更を受け付けず、コンソールの TTY を作らない（表を空のまま保つ）
     */
    @Volatile
    private var restoringValue = false

    /**
     * 復元の間このマシンを止める [quiesce]。受け付けたときに取り、終わりの知らせ（[RestoreRequest.listener]）が返った後か、
     * [stopLocked] で閉じる。`null` でない間は [isRestoring]
     */
    @Volatile
    private var restoreHandle: QuiesceHandle? = null

    /** 前段で戻せなかったので、[coldBootAfterRestore] を待っている */
    @Volatile
    private var coldBootPending = false

    /** 復元が `stop()`・`close()` で取り消された（前段が見る） */
    @Volatile
    private var restoreCancelled = false

    /** 復元の取り消しを、モジュールの読み込みを待つ前段に伝える。[restore] で作り直し、[stopLocked] で終える */
    @Volatile
    internal var restoreCancelSignal: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
        private set

    /** 受け付けた復元（終わりの知らせの宛先） */
    @Volatile
    private var restoreRequest: RestoreRequest? = null

    /** 前段から: 取り消されたか */
    internal val isRestoreCancelled: Boolean get() = restoreCancelled || scheduler.isClosed

    /** 復元か、その後の冷えた起動待ちの間（保存しない・待機点にしない） */
    private val restoreTransient: Boolean get() = restoringValue || coldBootPending

    /** 刈り取ったプロセス・前の起動のプロセスの専用スレッドで、まだ終わっていないかもしれないもの（[awaitTermination] で待つ） */
    private val retiredThreads = ArrayList<Thread>()

    /** 状態 */
    public val state: MachineState get() = stateValue

    /**
     * 保存してよい待機点にいるなら `true`（#81 の判定の土台）。次をすべて満たすとき:
     *
     * - 起動の手順が動いていない（[isBooting] でない）
     * - 再起動を求められて待っていない（[reboot] を受け付けてから、[MachineHost.onRebootRequested] を呼ぶまで）
     * - 全プロセスが待機点にいる（または1つもない）
     *
     * 起動中・再起動の待ちの印は実行時だけの値で保存しないので、その間は待機点にしない（#365）。
     */
    public val isAtWaitPoint: Boolean
        get() = lock.withLockValue {
            !bootingValue && !rebootRequested && !rebootNotified && !restoreTransient && kernel.processes.all().all { isAtWaitPointLocked(it) }
        }

    /** 起動の手順が動いている（[boot] を受け付けてから、init を作るか、失敗・取り消しで終わるまで） */
    public val isBooting: Boolean get() = bootingValue

    /**
     * 復元の途中（#85、ADR 0085）: [restore] を受け付けてから、終わりの知らせ（[RestoreRequest.listener]）が返るか、取り消されるまで。
     * この間はマシンが [quiesce] で止まっている（プロセスに step を割り当てない）
     */
    public val isRestoring: Boolean get() = restoreHandle != null

    /** 再起動を求められて、まだ [MachineHost.onRebootRequested] を呼んでいない（#85。`STOPPED` が再起動の途中かを見分ける） */
    public val isRebootPending: Boolean get() = lock.withLockValue { rebootRequested || rebootNotified }

    // ---- 待機点保存（#81、ADR 0081） ----

    /** [quiesce] の数（0 より大きい間は、スケジューラがこのマシンに新しい step を割り当てない） */
    private val quiesceCount = AtomicInteger()

    /** [quiesce] で止められている */
    public val isQuiesced: Boolean get() = quiesceCount.get() > 0

    /**
     * このマシンだけを止める（#81）。スケジューラの `tryActivate` が、このマシンのプロセスに新しい step（と燃料）を割り当てなくなる。
     * 走っている step は待機点（または燃料切れ）まで走る。返した [QuiesceHandle] の `close()` で続ける。重ねて呼んでもよい
     * （すべて `close()` するまで止まる）。全体の一時停止（[MachineScheduler.pauseAll]）とは別に働く。どのスレッドから呼んでもよい。待たない
     */
    public fun quiesce(): QuiesceHandle {
        quiesceCount.incrementAndGet()
        return object : QuiesceHandle {
            private val done = AtomicBoolean(false)

            override val readiness: SaveReadiness get() = saveReadiness()

            override fun close() {
                if (done.compareAndSet(false, true)) endQuiesce()
            }
        }
    }

    private fun endQuiesce() {
        if (quiesceCount.decrementAndGet() > 0) return
        // 止めている間に燃料待ちに入ったものを起こす（ディスパッチャーも見直す）
        lock.withLockValue { wakeFuelWaitersLocked() }
        scheduler.dispatcher.signal()
    }

    /**
     * 今保存できるか（#81）。起動中・再起動の待ちなら [SaveReadiness.TRANSIENT]。そうでなければ、プロセスの判定のうち
     * TRANSIENT があれば TRANSIENT、UNSAVEABLE があれば UNSAVEABLE、どちらもなければ READY（プロセスがなくても READY）
     */
    public fun saveReadiness(): SaveReadiness = lock.withLockValue { saveReadinessLocked() }

    private fun saveReadinessLocked(): SaveReadiness {
        if (bootingValue || rebootRequested || rebootNotified || restoreTransient) return SaveReadiness.TRANSIENT
        var result = SaveReadiness.READY
        for (process in kernel.processes.all()) {
            when (processReadinessLocked(process)) {
                SaveReadiness.TRANSIENT -> return SaveReadiness.TRANSIENT
                SaveReadiness.UNSAVEABLE -> result = SaveReadiness.UNSAVEABLE
                SaveReadiness.READY -> {}
            }
        }
        return result
    }

    /**
     * プロセス1つの判定。終わったプロセス（ゾンビ）は READY（ドライバー・ストアは見ない。#82）。終わっていないプロセスは、
     * 静的に保存できないもの（[isStaticallySaveableLocked]）なら UNSAVEABLE、[isAtWaitPointLocked] が真なら READY、
     * 待機点型は TRANSIENT、ブロッキング型は UNSAVEABLE
     */
    private fun processReadinessLocked(process: Process): SaveReadiness {
        if (process.state.isTerminal) return SaveReadiness.READY
        // 終わっていないのに実行時の部分がない（今は起きない。守りだけ）
        val rt = runtimes[process.pid] ?: return SaveReadiness.UNSAVEABLE
        if (!isStaticallySaveableLocked(rt)) return SaveReadiness.UNSAVEABLE
        return when {
            isAtWaitPointLocked(process) -> SaveReadiness.READY
            process.kind == ProcessKind.WAIT_POINT -> SaveReadiness.TRANSIENT
            else -> SaveReadiness.UNSAVEABLE
        }
    }

    /**
     * 待っても直らない「保存できないもの」でないか（#82）: ドライバーが保存に対応している（[SnapshotCapableDriver]）、
     * モジュールがストアにある（[pro.ikili.kibicomputers.core.persist.ModuleStore.contains]。メモリの集合を引くだけ）、
     * インスタンスが1つ以下（`CREATED` は 0）。型の判定と集合の引きだけなので軽い
     */
    private fun isStaticallySaveableLocked(rt: ProcessRuntime): Boolean =
        rt.driver is SnapshotCapableDriver &&
            rt.instances.size <= 1 &&
            scheduler.moduleStore.contains(rt.program.moduleSource.originalHash)

    /**
     * カーネルの状態の写しを取る（#81）。カーネルロックの中で写し（[pro.ikili.kibicomputers.core.persist.KernelStateCopy]）を作り、
     * 元のマシンは止めない・変えない。
     *
     * - [CapturePolicy.SKIP_IF_TRANSIENT] で [SaveReadiness.TRANSIENT] なら `null`（自動保存は前の世代を残す）
     * - そうでなければ必ず写しを返す。`UNSAVEABLE` のプロセス（[CapturePolicy.KILL_TRANSIENT] なら `TRANSIENT` のものも）は、
     *   写しの中でだけ `SIGKILL` で殺し、殺した pid を記録する
     * - マシン全体が TRANSIENT（起動中・再起動の待ち）で [CapturePolicy.KILL_TRANSIENT] なら、殺さずにそのまま写し、
     *   冷えた起動の理由（[ColdBootReason.REBOOT_PENDING]、なければ [ColdBootReason.BOOTING]）を立てる
     *
     * [deltaBase] があれば差分の写しにする（#83、ADR 0083）: その基準に全体の形で入っているプロセスは、そこから変わった区画だけを取る。
     * ただし「基準から変わった区画の数 ÷ 書き換えた区画の数」が [fullSaveChangedRatio] を超えたら全体の写しにする。
     * 全体の写しでは、変わった区画を追えるプロセスの区画を読み取り専用に戻し、置き場が書けたら（[MachineSnapshotSource.committed]）
     * その世代を基準にする
     */
    @JvmOverloads
    public fun captureSnapshot(
        policy: CapturePolicy,
        deltaBase: DeltaBase? = null,
        fullSaveChangedRatio: Double = MachineSaveConfig.DEFAULT_FULL_SAVE_CHANGED_RATIO,
    ): MachineSnapshotSource? = captureCopy(policy, deltaBase, fullSaveChangedRatio)?.toSource()

    /** 取り出しの番号（#83。カーネルロックの中で増やす） */
    private var captureToken = 0L

    /** [captureSnapshot] の写しそのもの（テストと #82 用） */
    internal fun captureCopy(
        policy: CapturePolicy,
        deltaBase: DeltaBase? = null,
        fullSaveChangedRatio: Double = MachineSaveConfig.DEFAULT_FULL_SAVE_CHANGED_RATIO,
    ): KernelStateCopy? = lock.withLockValue {
        // 復元の途中・冷えた起動待ちは写さない（表が空か、入れたばかりで、前の世代を残す。#85）
        if (restoreTransient) return@withLockValue null
        val wholeTransient = bootingValue || rebootRequested || rebootNotified
        if (policy == CapturePolicy.SKIP_IF_TRANSIENT && saveReadinessLocked() == SaveReadiness.TRANSIENT) return@withLockValue null
        val hostWaits = HashMap<Int, IntArray>()
        for ((pid, rt) in runtimes) if (rt.hostWaits.isNotEmpty()) hostWaits[pid] = rt.hostWaits.keys.toIntArray()
        val copy = KernelStateCopy.of(kernel, kernel.clocks.snapshot(environment.clock, scheduler.now), hostWaits)
        // 戻した直後の起こしをまだ使っていないプロセスは、次の保存でも起こす（#85）
        for ((pid, rt) in runtimes) if (rt.restoreWake) copy.markWakeOnRestore(pid)
        if (wholeTransient) {
            copy.markColdBoot(if (rebootRequested || rebootNotified) ColdBootReason.REBOOT_PENDING else ColdBootReason.BOOTING)
        } else {
            // ① #81 の判定（静的に保存できないものを含む）で殺す
            for (process in kernel.processes.all()) {
                when (processReadinessLocked(process)) {
                    SaveReadiness.UNSAVEABLE -> copy.killInCopy(process.pid)
                    SaveReadiness.TRANSIENT -> if (policy == CapturePolicy.KILL_TRANSIENT) copy.killInCopy(process.pid)
                    SaveReadiness.READY -> {}
                }
            }
        }
        // 全体か差分かを決める（#83）
        val token = ++captureToken
        val full = deltaBase == null || changedRatioLocked(BaseRef(deltaBase.id, deltaBase.generation)) > fullSaveChangedRatio
        copy.token = token
        copy.fullSave = full
        copy.deltaBase = if (full) null else deltaBase
        copy.onCommitted = { c, id, generation -> commitCapture(c, id, generation) }
        // ② 理由が NONE のときだけ中身を取る（NONE でなければ、待機点にいないプロセスのインスタンスを読まない。#82 の 12.）
        if (copy.coldBootReason == ColdBootReason.NONE) captureContentLocked(copy)
        // 写しの中で殺したプロセスは基準なし（古い基準を残さない。#83）
        for (pid in copy.killedPids) runtimes[pid]?.memoryBase = MemoryBase.None
        // ③ ②で init を殺して冷えた起動になったら、取った中身を捨てる。この写しで読み取り専用に戻したプロセスは基準なしに戻す
        if (copy.coldBootReason != ColdBootReason.NONE) {
            copy.discardContent()
            copy.commitable = false
            for (rt in runtimes.values) {
                val base = rt.memoryBase
                if (base is MemoryBase.Pending && base.token == token) rt.memoryBase = MemoryBase.None
            }
        }
        copy
    }

    /**
     * 差分の写しで書く量の比（#83）: 生きているプロセスのうち、[ref] を基準にしているものは変わった区画、ほかは書き換えた区画すべてを
     * 「変わった量」、全プロセスの書き換えた区画を「全体の量」とする。区画を数えられないプロセスは毎回全体の形なので数えない
     */
    private fun changedRatioLocked(ref: BaseRef): Double {
        var changed = 0L
        var total = 0L
        for (rt in runtimes.values) {
            if (rt.process.state.isTerminal) continue
            val instance = rt.instances.firstOrNull() ?: continue
            val counts = instance.memoryChunkCounts() ?: continue
            changed += if (MemoryBase.matches(rt.memoryBase, ref, instance)) counts.changed else counts.written
            total += counts.written
        }
        return if (total == 0L) 0.0 else changed.toDouble() / total
    }

    /**
     * 置き場が [copy] の写しを [id] の世代 [generation] として書けた（#83）。全体の写しで冷えた起動でなければ、その写しで
     * 全体の形（`FULL_AND_TRACK`）で取ったプロセスのうち、今も同じインスタンスのものの基準をこの世代にする。
     * この写しで読み取り専用に戻したほかのプロセスは基準なしにする
     */
    private fun commitCapture(copy: KernelStateCopy, id: java.util.UUID, generation: Long) = lock.withLockValue {
        if (!copy.fullSave || !copy.commitable) return@withLockValue
        val ref = BaseRef(id, generation)
        for ((pid, rt) in runtimes) {
            val base = rt.memoryBase as? MemoryBase.Pending ?: continue
            if (base.token != copy.token) continue
            val tracked = copy.abiCaptures[pid]?.trackedFull == true
            rt.memoryBase = if (tracked && rt.instances.firstOrNull() === base.instance) {
                MemoryBase.Committed(ref, base.instance)
            } else {
                MemoryBase.None
            }
        }
    }

    /**
     * 写しの中身を取る（#82 の 11. ②。カーネルロックの中）: 生きているプロセスのホスト側の処理の待ちを外し、ABI 層の写しを取り
     * （取れなければ写しの中で殺す）、`/tmp` の写しと、開いたファイルの FS の中のパスを取る
     */
    private fun captureContentLocked(copy: KernelStateCopy) {
        val live = kernel.processes.all().filter { copy.processes[it.pid]?.state?.isTerminal == false }.map { it.pid }
        for (pid in live) copy.dropHostWaitsInCopy(pid)
        for (pid in live) {
            if (copy.processes[pid]?.state?.isTerminal != false) continue
            val rt = runtimes[pid]
            val driver = rt?.driver as? SnapshotCapableDriver
            // インスタンスの状態の取り方（#83）: 差分なら基準に入っているものだけ変わった区画、全体なら追えるものは追い始める
            val instance = rt?.instances?.firstOrNull()
            val delta = copy.deltaBase
            val mode = when {
                rt == null || instance == null -> StateCaptureMode.FULL
                delta != null -> if (MemoryBase.matches(rt.memoryBase, BaseRef(delta.id, delta.generation), instance)) {
                    StateCaptureMode.CHANGED
                } else {
                    StateCaptureMode.FULL
                }
                instance.tracksChanges -> StateCaptureMode.FULL_AND_TRACK
                else -> StateCaptureMode.FULL
            }
            val payload = if (rt == null || driver == null) {
                null
            } else {
                try {
                    driver.capturePayload(rt, mode)
                } catch (e: Exception) {
                    LOG.log(System.Logger.Level.WARNING, "マシン $id の pid $pid の状態を取り出せない: $e")
                    null
                }
            }
            if (payload == null || driver == null) {
                LOG.log(System.Logger.Level.WARNING, "マシン $id の pid $pid は保存できないので、保存データの中では止める")
                copy.killInCopy(pid)
                if (copy.coldBootReason != ColdBootReason.NONE) return
            } else {
                copy.putAbi(pid, driver.abiLayer, driver.abiLayerVersion, payload, mode == StateCaptureMode.FULL_AND_TRACK)
                // 全体の写しでは、追い始めたものは置き場が書けるのを待つ。ほかは基準なし（差分の写しでは変えない）
                if (copy.fullSave) {
                    rt!!.memoryBase = if (mode == StateCaptureMode.FULL_AND_TRACK && instance != null) {
                        MemoryBase.Pending(copy.token, instance)
                    } else {
                        MemoryBase.None
                    }
                }
            }
        }
        for (entry in kernel.mounts.entries) {
            if (entry.source != MachineLimits.TMP_SOURCE) continue
            val fs = vfs.fileSystemLocked(entry.id) as? TmpFileSystem ?: continue
            fs.snapshot()?.let { copy.putTmpfs(entry.id, it) }
        }
        copy.objects.forEachObject { obj ->
            if (obj is OpenFile && obj.mountId != 0) vfs.pathHintLocked(obj)?.let { copy.putFilePath(obj.id, it) }
        }
    }


    /** 最後に受け付けた [boot] の設定（[reboot] と、[MachineHost.onRebootRequested] の既定の実装が使う）。まだなければ `null` */
    public val lastBootConfig: BootConfig? get() = lastBootConfigValue

    /**
     * 起動する（#49）。どのスレッドから呼んでもよい。待たずに返す（ディスクを探し、`/sbin/init` を読み込むのは起動用のスレッド `KiBi-boot-*`。#350）。
     *
     * 起動部品がない・起動ディスクがない・init がない・動かせないときは、コンソールにメッセージを書いて
     * [MachineHost.onBootFailed] で知らせ、マシンは [MachineState.STOPPED] のまま。init を作れたら [MachineState.RUNNING] になる。
     * init が自分で終わったら、ほかのプロセスもすべて止めて [MachineState.STOPPED] にする（自動では再起動しない）。
     *
     * @return 受け付けたら `true`。[MachineState.STOPPED] でない、起動の途中、スケジューラが閉じているなら `false`
     */
    public fun boot(config: BootConfig): Boolean {
        lock.lock()
        try {
            if (scheduler.isClosed || stateValue != MachineState.STOPPED || bootingValue) return false
            // 復元の途中・冷えた起動待ちは断る（冷えた起動は coldBootAfterRestore。#85）
            if (restoreHandle != null || coldBootPending) return false
            bootingValue = true
            bootCancelled = false
            bootCancelSignal = CompletableFuture()
            rebootRequested = false
            rebootNotified = false
            lastBootConfigValue = config
        } finally {
            lock.unlock()
        }
        scheduler.bootExecutor.execute { BootSequence(this, config).run() }
        return true
    }

    /**
     * 保存データから戻す（#85、ADR 0085）。どのスレッドから呼んでもよい。待たずに返す（読み・復号・モジュールの読み込み・
     * インスタンスの作り直しは起動用のスレッド）。受け付けたらマシンを [quiesce] で止め（[isRestoring]）、終わったら
     * [RestoreRequest.listener] に結果を知らせる（メインスレッドで、通知の列から）。知らせが返った後に止めるのをやめる。
     *
     * - 戻せたら [MachineState.RUNNING] にして [RestoreOutcome.RESTORED]（`StateChanged` は知らせの前に届く）
     * - 戻せなかったら（カーネルの表は空のまま）[RestoreOutcome.COLD_BOOT]。受け手は [coldBootAfterRestore] で冷えた起動を始める
     *   （受け手がなければマシンが自分で始める）。その間 [boot] は断る
     * - `stop()`・`close()` で取り消したら [RestoreOutcome.CANCELLED]（マシンは STOPPED のまま）
     * - 表に入れる途中で失敗したら、カーネルの不整合として [MachineState.CRASHED] にし、知らせは送らない
     *
     * @return 受け付けたら `true`。[MachineState.STOPPED] でない、起動・復元の途中、一度でも動いた（表が空でない）、
     *   スケジューラが閉じているなら `false`
     */
    public fun restore(request: RestoreRequest): Boolean {
        lock.lock()
        try {
            if (scheduler.isClosed || closedValue || stateValue != MachineState.STOPPED || bootingValue) return false
            if (restoreHandle != null || coldBootPending || rebootRequested || rebootNotified) return false
            if (kernel.processes.size != 0 || kernel.mounts.entries.isNotEmpty() || kernel.consoleTtyId != 0 || runtimes.isNotEmpty()) return false
            restoringValue = true
            restoreCancelled = false
            restoreCancelSignal = CompletableFuture()
            restoreRequest = request
            restoreHandle = quiesce()
            lastBootConfigValue = request.bootConfig
        } finally {
            lock.unlock()
        }
        try {
            scheduler.bootExecutor.execute { MachineRestore(this, request).run() }
        } catch (e: RejectedExecutionException) {
            // スケジューラが閉じた: 取り消しと同じにする
            stop()
        }
        return true
    }

    /**
     * 復元が [RestoreOutcome.COLD_BOOT] で終わったマシンの、冷えた起動を始める（#85）。[RestoreRequest.bootConfig] で [boot] と同じことをする。
     * 冷えた起動待ちでない・取り消された・スケジューラが閉じているなら何もせず `false`。どのスレッドから呼んでもよい
     */
    public fun coldBootAfterRestore(): Boolean {
        val config: BootConfig
        lock.lock()
        try {
            if (!coldBootPending) return false
            coldBootPending = false
            config = lastBootConfigValue ?: return false
            if (scheduler.isClosed || closedValue || stateValue != MachineState.STOPPED || bootingValue) return false
            bootingValue = true
            bootCancelled = false
            bootCancelSignal = CompletableFuture()
            rebootRequested = false
            rebootNotified = false
        } finally {
            lock.unlock()
        }
        closeRestoreHandle()
        scheduler.bootExecutor.execute { BootSequence(this, config).run() }
        return true
    }

    /** 復元の [quiesce] を閉じる（まだ閉じていなければ）。どのスレッドから呼んでもよい */
    private fun closeRestoreHandle() {
        val handle = lock.withLockValue { restoreHandle.also { restoreHandle = null } }
        handle?.close()
    }

    /**
     * 後段（#85）: 前段の [prepared] を表に入れる（起動用のスレッドから）。取り消されていたら何もせず `false`（呼び手が [prepared] を返す）。
     * 入れる途中で失敗したら、カーネルの不整合として CRASHED にする（`true` を返す。作ったものは表とともに止まる）
     */
    internal fun completeRestore(prepared: PreparedRestore): Boolean = lock.withLockValue {
        if (!restoringValue || restoreCancelled || scheduler.isClosed) return@withLockValue false
        try {
            installRestoredLocked(prepared)
        } catch (t: Throwable) {
            if (t is VirtualMachineError && t !is StackOverflowError) throw t
            LOG.log(System.Logger.Level.ERROR, "マシン $id を表に戻す途中で失敗した: $t")
            restoringValue = false
            inconsistencyLocked("保存データを表に戻せない: $t")
            return@withLockValue true
        }
        restoringValue = false
        setStateLocked(MachineState.RUNNING, null)
        postRestoreFinishedLocked(RestoreOutcome.RESTORED, null)
        true
    }

    /** 前段から（#85）: 戻せなかったので冷えた起動待ちにして知らせる（取り消されていたら何もしない） */
    internal fun restoreFellBack(cause: ColdBootCause): Unit = lock.withLockValue {
        if (!restoringValue || restoreCancelled) return@withLockValue
        restoringValue = false
        coldBootPending = true
        postRestoreFinishedLocked(RestoreOutcome.COLD_BOOT, cause)
    }

    /**
     * 復元の終わりを、通知の列に積む（カーネルロックの中）。配るときに受け手を呼び（受け手がなく COLD_BOOT なら冷えた起動を始め）、
     * その後で、この復元の [quiesce] がまだ同じなら閉じる（取り消しでは、閉じるのは [stopLocked]）
     */
    private fun postRestoreFinishedLocked(outcome: RestoreOutcome, cause: ColdBootCause?) {
        val request = restoreRequest ?: return
        val handle = restoreHandle
        postNotification(
            HostNotification.Callback {
                try {
                    val listener = request.listener
                    if (listener != null) {
                        safely { listener.onRestoreFinished(this, outcome, cause) }
                    } else if (outcome == RestoreOutcome.COLD_BOOT) {
                        coldBootAfterRestore()
                    }
                } finally {
                    if (outcome != RestoreOutcome.CANCELLED && handle != null) {
                        val close = lock.withLockValue {
                            if (restoreHandle === handle) {
                                restoreHandle = null
                                true
                            } else {
                                false
                            }
                        }
                        if (close) handle.close()
                    }
                }
            },
        )
    }

    /**
     * 前段で作ったものを表に入れ、実行時の部分を作る（#85、ADR 0085。カーネルロックの中）。表は空のはず。
     * プロセスの割り当ては、復元の [quiesce] を閉じるまで止まっている
     */
    private fun installRestoredLocked(prepared: PreparedRestore) {
        val d = prepared.decoded.kernel
        check(kernel.processes.size == 0 && kernel.mounts.entries.isEmpty() && kernel.consoleTtyId == 0 && runtimes.isEmpty()) {
            "空でない表には戻さない"
        }
        kernel.processes.restore(d.processes.all().toList(), d.processes.orphanZombies.toList(), d.processes.nextPid)
        kernel.objects.restoreFrom(d.objects)
        kernel.events.restoreIndex(d.events.index())
        kernel.mounts.restore(d.mounts.entries.toList(), d.mounts.nextMountId)
        for ((mountId, fs) in prepared.fileSystems) vfs.attach(mountId, fs)
        kernel.fuel = minOf(d.fuel, config.fuelPerTick)
        kernel.consoleTtyId = d.consoleTtyId
        kernel.initPid = d.initPid
        restoreClocks(d.clocks)
        scheduler.hooks.duringRestoreInstall?.invoke(this)
        for ((rt, instance) in prepared.runtimes) {
            val process = kernel.processes[rt.pid]
            check(process != null && process === rt.process) { "pid ${rt.pid} のプロセスが表と違う" }
            runtimes[rt.pid] = rt
            if (instance != null) runner.adoptRestoredInstanceLocked(rt, instance)
        }
        for ((rt, _) in prepared.runtimes) {
            val process = rt.process
            val wake = process.pid in d.wakeOnRestore
            when (process.state) {
                ProcessState.CREATED -> cpu.enqueueThreadless(rt)
                ProcessState.WAITING_EVENT -> {
                    val keys = process.wait.orEmpty()
                    if (keys.isNotEmpty()) registerRestoredWaitLocked(rt, keys)
                    if (wake || keys.isEmpty()) rt.restoreWake = true
                    val ready = rt.waitSet?.readyKeys(kernel.objects).orEmpty()
                    if (rt.restoreWake || ready.isNotEmpty()) scheduleThreadlessLocked(rt)
                }
                ProcessState.RUNNABLE -> {
                    val keys = process.wait
                    if (!keys.isNullOrEmpty()) registerRestoredWaitLocked(rt, keys)
                    if (wake || (keys != null && keys.isEmpty())) rt.restoreWake = true
                    // 燃料をもらってから続ける（燃料待ちの列から。quiesce を閉じるまでは割り当てない）
                    rt.fuelWaitThreadless = true
                    rt.fuelReady = false
                    addFuelWaiterLocked(rt)
                }
                else -> throw IllegalStateException("pid ${process.pid} の状態 ${process.state} は戻せない")
            }
        }
    }

    private fun registerRestoredWaitLocked(rt: ProcessRuntime, keys: List<WaitKey>) {
        val waiter = ProcessWaiter { scheduleThreadlessLocked(rt) }
        rt.waitSet = WaitSet.register(registry, kernel.objects, keys, waiter)
    }

    /**
     * 戻したマシンの、まだ実行していないデバイスの呼び出し（[PendingDeviceCall.State.Queued]）を実行に回し直す（#85。メインスレッドから、
     * デバイスをつないだ後）。カーネルロックを持たずに呼ぶ。回した数を返す
     */
    internal fun redispatchRestoredDeviceCalls(): Int {
        val queued = lock.withLockValue {
            val owners = HashMap<Int, Int>()
            for (process in kernel.processes.all().sortedBy { it.pid }) {
                if (process.state.isTerminal) continue
                for (entry in process.handles.entries().values) owners.putIfAbsent(entry.objectId, process.pid)
            }
            val list = ArrayList<Pair<Int, PendingDeviceCall>>()
            kernel.objects.forEachObject { obj ->
                if (obj is PendingDeviceCall && obj.state is PendingDeviceCall.State.Queued && obj.id !in pendingDeviceCalls) {
                    val pid = owners[obj.id] ?: return@forEachObject
                    list += pid to obj
                }
            }
            list
        }
        for ((pid, obj) in queued) dispatchPendingDeviceCall(pid, obj)
        return queued.size
    }

    /**
     * 再起動する（#49）。動いていれば [stop] と同じく止め、プロセスがすべて終わって [MachineState.STOPPED] になったら、
     * 次の `tick()` で [MachineHost.onRebootRequested] を呼ぶ（既定の実装は [lastBootConfig] で [boot] する）。
     * 起動の途中なら、それを取り消してから同じようにする。どのスレッドから呼んでもよい。待たずに返す。
     * 求めた後に [stop] すると、再起動を取り消す。
     *
     * @return 受け付けたら `true`。一度も [boot] していない、[MachineState.CRASHED]、スケジューラが閉じているなら `false`
     */
    public fun reboot(): Boolean {
        lock.lock()
        try {
            if (lastBootConfigValue == null || scheduler.isClosed || stateValue == MachineState.CRASHED) return false
            rebootRequested = true
            stopLocked(null)
            maybePostRebootLocked()
            return true
        } finally {
            lock.unlock()
        }
    }

    /**
     * 停止の印を立て、プロセスのスレッドに割り込んで、待たずに返す。どのスレッドから呼んでもよい
     * （メインスレッド、`onStateChanged` の中、そのマシンのプロセスのホスト関数の中も含む）。2回呼んでもよい。
     * 起動の途中なら取り消し、求めた再起動（[reboot]）も取り消す。
     */
    public fun stop() {
        lock.lock()
        try {
            rebootRequested = false
            rebootNotified = false
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
            threads = runtimes.values.mapNotNull { it.dedicatedThread } + retiredThreads
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
        lock.withLockValue {
            closedValue = true
            timers.cancelAll()
        }
        scheduler.removeMachine(this)
    }

    override fun toString(): String = "Machine($id, $stateValue)"

    // ---- カーネルの内部 ----

    /**
     * プロセスを作る（#45・#49 が使う）。Process を `CREATED` で表に足し、型に応じて専用スレッドを作る（BLOCKING）か、
     * CPU の待ち行列に入れる（WAIT_POINT）。インスタンスはここでは作らない。カーネルロックを自分で取る。
     * 作業ディレクトリ（[SpawnOptions.cwd]）か preopen（[StdioSource.Directory]）を指定するときは、VFS をロックの外で使うので、
     * カーネルロックを持たずに呼ぶ（持っていれば [IllegalStateException]。#52）。
     *
     * 引数・環境変数・作業ディレクトリ・fd は [options] で決める（[SpawnOptions]）。どれも表を変える前に確かめ、
     * 失敗したら何も変えない。
     *
     * @param parentPid 0 は親なし（init）。0 以外は、表にある、終わっていないプロセスの pid でなければ [IllegalArgumentException]
     * @param asInit 起動の手順（#49）が init を作る。起動が取り消されていれば [BootCancelledException]。作れたら init として覚え、起動の手順を終える
     * @return 割り当てた pid
     * @throws IllegalArgumentException 燃料を計測しないモジュールで `allowUnmetered` が `false`、または親がない・終わっている
     * @throws FsException 引数・環境変数が正しくない（`INVAL`）・大きすぎる（`TOOBIG`）、作業ディレクトリがない（`NOENT`）・
     *   ディレクトリでない（`NOTDIR`）、渡す fd が開いていない・範囲外（`BADF`）、preopen（[StdioSource.Directory]）の
     *   ディレクトリがない（`NOENT`）・ディレクトリでない（`NOTDIR`）
     * @throws ResourceLimitException プロセスの上限・ブロッキング型の上限を超えた
     * @throws IllegalStateException マシンが STOPPING・CRASHED、またはスケジューラが閉じている
     */
    @Throws(FsException::class)
    internal fun spawn(
        program: Program,
        driver: ProcessDriver,
        options: SpawnOptions = SpawnOptions(),
        parentPid: Int = 0,
        asInit: Boolean = false,
    ): Int = spawnInternal(program, driver, options, parentPid, asInit, withHandle = false).pid

    /**
     * [spawn] と同じだが、子の `ProcessObject` を指す fd（読む権利だけ）を親 [parentPid] の fd 表に入れ、子の pid と一緒に返す
     * （`kibi:process` の `[static]process.spawn`。#74、ADR 0074）。fd は子を表に足すのと同じカーネルロックの中で入れるので、
     * 子は fd が入る前に動き出さない。親の fd 表が満杯なら、子を作らずに `MFILE`。[parentPid] は 0 にできない
     */
    @Throws(FsException::class)
    internal fun spawnWithHandle(program: Program, driver: ProcessDriver, options: SpawnOptions, parentPid: Int): ProcessHandle {
        require(parentPid != 0) { "親のないプロセスにはハンドルを返せない" }
        return spawnInternal(program, driver, options, parentPid, asInit = false, withHandle = true)
    }

    @Throws(FsException::class)
    private fun spawnInternal(
        program: Program,
        driver: ProcessDriver,
        options: SpawnOptions,
        parentPid: Int,
        asInit: Boolean,
        withHandle: Boolean,
    ): ProcessHandle {
        val paths = resolveSpawnPaths(options, parentPid)
        lock.lock()
        try {
            // 起動の init: 取り消されていれば作らない（stop() と取り違えないよう、表を変えるのと同じロックの中で確かめる）
            if (asInit && (!bootingValue || bootCancelled)) throw BootCancelledException()
            check(!scheduler.isClosed) { "スケジューラは閉じている" }
            check(stateValue != MachineState.STOPPING && stateValue != MachineState.CRASHED) { "マシンは $stateValue" }
            val metered = program.module.isFuelMetered
            require(metered || options.allowUnmetered) { "燃料を計測しないモジュールはプロセスにできない" }
            val parent = parentLocked(parentPid)
            if (kernel.processes.limitedCount() >= config.maxProcesses) throw ResourceLimitException("プロセスの数が上限（${config.maxProcesses}）")
            val env = options.env ?: parent?.env ?: emptyMap()
            ProcessArgs.check(options.args, env)
            // 前もって決めた作業ディレクトリ・preopen は、親の作業ディレクトリが同じときだけ使う（親の cwd を変えるのは親自身のスレッドだけ）
            if (paths != null && (parent?.cwd ?: VfsPath.ROOT) != paths.base) fsError(Errno.AGAIN, "親の作業ディレクトリが変わった")
            val cwd = paths?.cwd ?: (parent?.cwd ?: VfsPath.ROOT)
            val fdEntries = resolveFdsLocked(options, parent, paths)
            // 親に返すハンドルの空きは、表を変える前に確かめる（子を作ってから MFILE で断らない）
            if (withHandle && checkNotNull(parent).handles.isFull) fsError(Errno.MFILE, "fd の数が上限（${parent.handles.limit}）")
            val kind = driver.kind
            if (kind == ProcessKind.BLOCKING && !scheduler.tryReserveBlocking()) {
                throw ResourceLimitException("ブロッキング型のプロセスの数が上限（${scheduler.config.maxBlockingProcesses}）")
            }
            reapOrphanZombiesLocked()
            val pid = kernel.processes.allocatePid()
            val process = Process(pid, parentPid, kind, program.hash, !metered, ArrayList(options.args), LinkedHashMap(env), cwd)
            kernel.processes.add(process)
            process.objectId = kernel.objects.add(ProcessObject(pid))
            if (asInit) {
                kernel.initPid = pid
                bootingValue = false
            }
            installFdsLocked(FdTable(process, kernel.objects, wakeSink), fdEntries)
            // 親に返すハンドル（空きは上で確かめた。子の fd に親の fd を写した後なので、子には渡らない）
            val handleFd = if (withHandle) {
                kernel.objects.retain(process.objectId)
                FdTable(checkNotNull(parent), kernel.objects, wakeSink).install(process.objectId, HandleRights.READ)
            } else {
                -1
            }
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
            return ProcessHandle(pid, handleFd)
        } finally {
            lock.unlock()
        }
    }

    /**
     * VFS の実行ファイル [path] を読み込んで、プロセスを作る（#45）。ドライバーは [drivers] が、読み込んだ [Program] から作る。
     *
     * - [path] の相対パスは、親の作業ディレクトリから辿る（親がなければ `/`）。`PATH` からは探さない（シェルの役目）
     * - 普通のファイルでなければ `ACCES`、[MachineLimits.MAX_EXECUTABLE_BYTES] を超えたら `FBIG`、WASM として読めなければ `NOEXEC`
     * - 読み込むときに、[drivers] の止まりうる import（[ProcessDriverFactory.waitImports]）で待機点への変換をかける（#329）。
     *   変換できない作り（v128 のローカル、末尾呼び出しなど）は `NOEXEC` で断り、メッセージにどの命令・どの機能かを書く（#367。
     *   `BLOCKING` 型には落とさない）。予約した名前（`kibi.async_push` など）をすでに使っている WASM も `NOEXEC`
     * - 読み込むときに燃料の計測コードを挿入する（#38）。計測の名前（`kibi.fuel`・`kibi_fuel`）をすでに使っている WASM も `NOEXEC`
     * - 読み込む前に構造の上限（関数・ローカル変数・テーブルの要素の数など）を確かめ、挿入・パース・検証に時間の上限を設ける
     *   （#40。[pro.ikili.kibicomputers.core.runtime.validation.ModuleLimits]）。超えたら `NOEXEC`。import の確認はドライバー（[drivers]）がする
     * - `options.args` が空なら、argv を [path] の文字列1つにする
     * - 先頭が `#!` のファイル（シバン付きのスクリプト。#93）は、その行のインタプリタを代わりに読み込む（解釈は [Shebang]）。
     *   argv は `[インタプリタ, (シバンの引数), スクリプトの絶対パス, 元の argv[1..]]` にする（Linux と同じ並び。
     *   スクリプトのパスは、WASI に作業ディレクトリがないので絶対パスで渡す）。インタプリタのパスの相対パスも親の作業ディレクトリから辿る。
     *   インタプリタもシバン付きならさらにたどり、[Shebang.MAX_DEPTH] 回を超えたら `LOOP`。拡張子は見ない
     * - ファイルを読むのはカーネルロックの外（#52）、WASM の読み込み（変換・挿入・パース・検証・コンパイル）はロックの外の、
     *   コンパイル用のスレッドで行う（#37）。同じ中身・同じ読み込み方の WASM は、読み込んだ結果をサーバー全体で共有する
     *   （[pro.ikili.kibicomputers.core.kernel.process.ProgramCache]）。読み込みが終わっていなければ [await] で待つ
     *   （プロセスからの呼び出しは、CPU と許可を返して待つ）。重いので、メインスレッドからは呼ばない
     * - 読み込みの待ち行列はマシンごとに分けて巡回する（#363）。このマシンの読み込み中・待ち行列にある読み込みが上限
     *   （[pro.ikili.kibicomputers.core.kernel.process.ProgramCache.DEFAULT_MAX_PENDING_PER_OWNER]）に達していたら `AGAIN`。
     *   待つのをやめたら（止められた・起動の取り消し）、ほかに待つ側がなく、まだ始まっていない読み込みは取り消す。
     *   ほかの台が相乗りしていても、このマシンの数からは外す（#383。始まっていない読み込みは相乗りした台の数に移る）。
     *   最近 WASM として読めなかったものは、読み込み直さずに `NOEXEC`
     *
     * カーネルロックを持たずに呼ぶ。ほかの失敗は [spawn] と同じ。[asInit] は起動の手順だけが使う（[spawn]）。
     */
    @Throws(FsException::class)
    internal fun exec(
        path: String,
        drivers: ProcessDriverFactory,
        options: SpawnOptions = SpawnOptions(),
        parentPid: Int = 0,
        asInit: Boolean = false,
        await: ((CompletableFuture<Program>) -> Program)? = null,
    ): Int {
        val pending = beginExec(path, drivers, options, parentPid)
        awaitExecLoad(pending, asInit, await)
        return finishExec(pending, asInit)
    }

    /**
     * [exec] と同じだが、子のハンドルを親 [parentPid] の fd 表に入れて返す（[spawnWithHandle]。#74）。
     * 親の fd 表が満杯なら、読み込みの前に `MFILE`（子を作ってから断らない）
     */
    @Throws(FsException::class)
    internal fun execWithHandle(
        path: String,
        drivers: ProcessDriverFactory,
        options: SpawnOptions,
        parentPid: Int,
        await: ((CompletableFuture<Program>) -> Program)? = null,
    ): ProcessHandle {
        checkHandleRoom(parentPid)
        val pending = beginExec(path, drivers, options, parentPid)
        awaitExecLoad(pending, asInit = false, await)
        return finishExecWithHandle(pending)
    }

    /** 親 [parentPid] の fd 表に、子のハンドルを入れる空きがあるか確かめる（なければ `MFILE`）。カーネルロックを自分で取る */
    @Throws(FsException::class)
    internal fun checkHandleRoom(parentPid: Int): Unit = lock.withLockValue {
        val parent = requireNotNull(parentLocked(parentPid)) { "親のないプロセスにはハンドルを返せない" }
        if (parent.handles.isFull) fsError(Errno.MFILE, "fd の数が上限（${parent.handles.limit}）")
    }

    /** [exec] の読み込みの終わりを待つ（[await] がなければこのスレッドで）。待つのをやめたら、始まっていない読み込みは取り消す */
    private fun awaitExecLoad(pending: PendingExec, asInit: Boolean, await: ((CompletableFuture<Program>) -> Program)?) {
        val future = pending.future
        if (future.isDone) return
        try {
            when {
                await != null -> await(future)
                // 起動の init は、起動の取り消し（stop()・reboot()・スケジューラの停止）で待つのをやめる
                else -> awaitProgram(future, if (asInit) bootCancelSignal else null)
            }
        } catch (e: Throwable) {
            // 待つのをやめた（止められた・起動の取り消し）。ほかに待つ側がなく、まだ始まっていなければ読み込まない（#363）
            future.cancel(false)
            // 読み込みの失敗そのものなら、後の finishExec で errno にする
            if (loadFailureOf(future) !== e) throw e
        }
    }

    /**
     * [exec] の前半（#358）: 実行ファイルを読み（シバンをたどる）、読み込み（コンパイル）を始めて、待たずに返す。
     * 読み込みが終わったら（`future` が終わったら）[finishExec] でプロセスを作る。待つのをやめるときは `future.cancel(false)`。
     * ファイルの失敗（`NOENT`・`ACCES`・`FBIG`・`LOOP` など）はここで投げる。読み込みの失敗（`NOEXEC`・`AGAIN`）は [finishExec] で投げる。
     * カーネルロックを持たずに呼ぶ
     */
    @Throws(FsException::class)
    internal fun beginExec(path: String, drivers: ProcessDriverFactory, options: SpawnOptions, parentPid: Int): PendingExec {
        check(!lock.isHeldByCurrentThread) { "カーネルロックを持ったまま exec を呼ばない" }
        val base = lock.withLockValue { parentLocked(parentPid)?.cwd ?: VfsPath.ROOT }
        // 実行ファイルを読むのはロックの外（#52）
        val executable = loadExecutable(path, options.args.ifEmpty { listOf(path) }, base)
        val runtime = scheduler.options.runtime
        val limits = scheduler.options.moduleLimits
        val metered = scheduler.options.meterExecutables
        val waitImports = if (scheduler.options.asyncifyExecutables) drivers.waitImports else emptySet()
        val future = scheduler.programs.load(id, executable.bytes, metered, waitImports) { bytes ->
            if (metered) Program.loadMetered(runtime, bytes, limits, waitImports) else Program.load(runtime, bytes, limits)
        }
        // 読み込みが終わったら、元のバイト列をモジュールの置き場に書く（#82。読み込みのスレッドで。メインスレッドでは書かない）
        val original = executable.bytes
        future.whenComplete { program, _ -> if (program != null) storeModule(program, original) }
        return PendingExec(executable.path.toString(), executable.args, drivers, options, parentPid, future, original)
    }

    /**
     * [program] の元のバイト列 [original] をモジュールの置き場に書く（#82）。カーネルロックの外で呼ぶ。
     * [ModuleStore.contains] で飛ばさず、いつも [ModuleStore.put] を呼ぶ（#551）。`contains` は作るときの種（ファイル名だけ）でも
     * 真になるので、飛ばすと壊れた種のファイルを確かめる機会がなくなる。`put` は中身を確かめたものなら I/O をせずに戻る。
     * 書けなくても続ける（そのプロセスは保存できない）
     */
    private fun storeModule(program: Program, original: ByteArray?) {
        if (original == null) return
        val store = scheduler.moduleStore
        val hash = program.moduleSource.originalHash
        try {
            store.put(hash, original)
        } catch (e: RuntimeException) {
            LOG.log(System.Logger.Level.WARNING, "モジュールを置き場に書けない: $e")
        }
    }

    /**
     * [exec] の後半（#358）: 終わった読み込み [pending] からドライバーとプロセスを作る。読み込みの失敗は errno にして投げる
     * （待機点に変換できない作り・WASM として読めない → `NOEXEC`、読み込みが多すぎる → `AGAIN`）。
     * 読み込みが終わっていなければ [IllegalStateException]。ほかの失敗は [spawn] と同じ。カーネルロックを持たずに呼ぶ
     */
    @Throws(FsException::class)
    internal fun finishExec(pending: PendingExec, asInit: Boolean = false): Int {
        val program = loadedProgram(pending)
        storeModule(program, pending.originalBytes)
        val driver = pending.drivers.create(program)
        return spawn(program, driver, pending.options.copy(args = pending.args), pending.parentPid, asInit)
    }

    /** [finishExec] と同じだが、子のハンドルを親の fd 表に入れて返す（[spawnWithHandle]。#74） */
    @Throws(FsException::class)
    internal fun finishExecWithHandle(pending: PendingExec): ProcessHandle {
        val program = loadedProgram(pending)
        storeModule(program, pending.originalBytes)
        val driver = pending.drivers.create(program)
        return spawnWithHandle(program, driver, pending.options.copy(args = pending.args), pending.parentPid)
    }

    /** 終わった読み込み [pending] の [Program]。失敗は errno にして投げる。終わっていなければ [IllegalStateException] */
    @Throws(FsException::class)
    private fun loadedProgram(pending: PendingExec): Program {
        val future = pending.future
        check(future.isDone) { "読み込みが終わっていない" }
        return try {
            awaitProgram(future, null)
        } catch (e: WasmLoadException) {
            Program.unsuspendableReason(e)?.let { fsError(Errno.NOEXEC, "${pending.path} は待機点に変換できない作りなので動かせない: $it") }
            fsError(Errno.NOEXEC, "${pending.path} は WASM として読めない: ${e.message}")
        } catch (e: ProgramLoadBusyException) {
            fsError(Errno.AGAIN, "${pending.path} を読み込めない: ${e.message}")
        }
    }

    /**
     * 起動の init の読み込み（[pending]）の終わりを、スレッドを持たずに待つ（#358）。終わったら、起動用のスレッドで [onLoaded] を呼ぶ。
     * 先に起動が取り消されたら（`stop()`・`reboot()`・スケジューラの停止）、読み込みを待つのをやめ（始まっていなければ読み込まない。#363）、
     * 起動用のスレッドで何も知らせずに起動を終える。どちらか一方だけを1回呼ぶ
     */
    internal fun whenInitLoaded(pending: PendingExec, onLoaded: () -> Unit) {
        val cancel = lock.withLockValue { bootCancelSignal }
        val fired = AtomicBoolean()
        pending.future.whenComplete { _, _ -> if (fired.compareAndSet(false, true)) runBootStep(onLoaded) }
        cancel.whenComplete { _, _ ->
            // stopLocked の中（カーネルロックの中）から呼ばれる。取り消しは読み込みの側のロックだけを取るので、ここで行ってよい
            if (fired.compareAndSet(false, true)) {
                pending.future.cancel(false)
                runBootStep { endBoot(null, "") }
            }
        }
    }

    /** 起動の手順の続きを、起動用のスレッドで動かす（スケジューラが閉じて受け付けなければ、このスレッドで） */
    private fun runBootStep(step: () -> Unit) {
        try {
            scheduler.bootExecutor.execute(step)
        } catch (e: RejectedExecutionException) {
            step()
        }
    }

    /** 失敗で終わった（取り消しでない）[future] の例外。成功・終わっていない・取り消されたなら `null` */
    private fun loadFailureOf(future: CompletableFuture<Program>): Throwable? {
        if (!future.isCompletedExceptionally || future.isCancelled) return null
        return try {
            future.getNow(null)
            null
        } catch (e: CompletionException) {
            e.cause ?: e
        }
    }

    /**
     * 読み込みの終わりをこのスレッドで待つ（起動の手順など、許可を持たないスレッド）。読み込みの例外はそのまま投げる。
     * [cancel] が先に終わったら、待つのをやめて [BootCancelledException] を投げる（読み込みは続き、終われば共有される）
     */
    private fun awaitProgram(future: CompletableFuture<Program>, cancel: CompletableFuture<Unit>?): Program = try {
        if (cancel != null) {
            // anyOf の失敗（読み込みの失敗）は、下の future.get() で同じ例外として投げ直す
            try {
                CompletableFuture.anyOf(future, cancel).get()
            } catch (e: ExecutionException) {
                // 何もしない
            }
            if (!future.isDone) throw BootCancelledException()
        }
        future.get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IllegalStateException("WASM の読み込みを待つ間に割り込まれた", e)
    }

    /** 読み込む WASM（[path] はそのファイル。シバンをたどった後ならインタプリタ）と、プロセスに渡す argv */
    private class Executable(val path: VfsPath, val bytes: ByteArray, val args: List<String>)

    /** 実行ファイルの中身。WASM（ほかの形も、読み込むときに断る）か、シバン付きのスクリプト */
    private sealed interface ExecutableContent {
        class Binary(val bytes: ByteArray) : ExecutableContent

        class Script(val line: Shebang.Line) : ExecutableContent
    }

    /** [text] を読み、シバンがあればインタプリタへたどる（カーネルロックの外。相対パスは [base] から。#93） */
    private fun loadExecutable(text: String, args: List<String>, base: VfsPath): Executable {
        var target = text
        var argv = args
        repeat(Shebang.MAX_DEPTH + 1) {
            val path = VfsPath.resolve(base, target)
            when (val content = readExecutable(path, VfsPath.hasTrailingSlash(target))) {
                is ExecutableContent.Binary -> return Executable(path, content.bytes, argv)
                is ExecutableContent.Script -> {
                    val line = content.line
                    argv = buildList {
                        add(line.interpreter)
                        line.arg?.let { add(it) }
                        add(path.toString())
                        addAll(argv.drop(1))
                    }
                    target = line.interpreter
                }
            }
        }
        fsError(Errno.LOOP, "$text のシバンをたどる回数が上限（${Shebang.MAX_DEPTH}）を超えた")
    }

    /** [pid] の子の pid（ゾンビを含む。割り当ての順） */
    internal fun children(pid: Int): List<Int> = lock.withLockValue { kernel.processes.childrenOf(pid) }

    /** 親のプロセス（0 なら `null`）。ない・終わっているなら [IllegalArgumentException] */
    private fun parentLocked(parentPid: Int): Process? {
        if (parentPid == 0) return null
        val parent = requireNotNull(kernel.processes[parentPid]) { "親のプロセス $parentPid がない" }
        require(!parent.state.isTerminal) { "親のプロセス $parentPid は終わっている" }
        return parent
    }

    /** [spawn] の前に VFS で決めたもの（[base] は決めたときの親の作業ディレクトリ） */
    private class SpawnPaths(val base: VfsPath, val cwd: VfsPath, val directories: Map<Int, OpenFile>)

    /**
     * 作業ディレクトリと preopen のディレクトリを、カーネルロックの外で決める（#52）。どちらも指定がなければ `null`（VFS を使わない）。
     * 作業ディレクトリは、指定がなければ親と同じ（親がなければ `/`。確かめない）。指定があれば、ディレクトリか確かめる
     */
    private fun resolveSpawnPaths(options: SpawnOptions, parentPid: Int): SpawnPaths? {
        val sources = listOf(STDIN, STDOUT, STDERR).associateWith { options.stdio.source(it) } + options.fds
        val directories = sources.filterValues { it is StdioSource.Directory }.mapValues { it.value as StdioSource.Directory }
        if (options.cwd == null && directories.isEmpty()) return null
        check(!lock.isHeldByCurrentThread) { "作業ディレクトリ・preopen を指定する spawn は、カーネルロックを持たずに呼ぶ" }
        val base = lock.withLockValue { parentLocked(parentPid)?.cwd ?: VfsPath.ROOT }
        val cwd = options.cwd?.let { text ->
            val path = VfsPath.resolve(base, text)
            if (vfs.stat(path).type != FileType.DIRECTORY) fsError(Errno.NOTDIR, "$path はディレクトリではない")
            path
        } ?: base
        // OpenFile はファイルシステムを参照せず、ディレクトリは開いたことを知らせないので、表に足す前に捨てても何も漏れない
        val opened = LinkedHashMap<Int, OpenFile>()
        for ((fd, d) in directories) opened[fd] = vfs.open(VfsPath.resolve(base, d.path), OpenOptions(read = true, directory = true))
        return SpawnPaths(base, cwd, opened)
    }

    /**
     * 実行ファイル [path] の中身を読む（カーネルロックの外）。先頭が `#!` なら、シバンの行だけを読んで [ExecutableContent.Script] を返す
     * （スクリプトには [MachineLimits.MAX_EXECUTABLE_BYTES] の上限をかけない）
     */
    private fun readExecutable(path: VfsPath, trailingSlash: Boolean): ExecutableContent {
        val stat = vfs.stat(path)
        if (stat.type != FileType.REGULAR_FILE) fsError(Errno.ACCES, "$path は普通のファイルではない")
        val file = vfs.open(path, OpenOptions(read = true), trailingSlash)
        try {
            return readExecutableContent(path, stat.size, file)
        } finally {
            vfs.close(file)
        }
    }

    private fun readExecutableContent(path: VfsPath, size: Long, file: OpenFile): ExecutableContent {
        // 先頭（シバンの行の上限まで）を読んで、スクリプトか見分ける
        val head = ByteArray(Shebang.MAX_LINE_BYTES)
        var headLength = 0
        while (headLength < head.size) {
            val n = vfs.read(file, head, headLength, head.size - headLength)
            if (n <= 0) break
            headLength += n
        }
        Shebang.parse(head, headLength)?.let { return ExecutableContent.Script(it) }
        if (size > MachineLimits.MAX_EXECUTABLE_BYTES) fsError(Errno.FBIG, "$path は大きすぎる（${size} バイト。上限 ${MachineLimits.MAX_EXECUTABLE_BYTES}）")
        val out = ByteArrayOutputStream(maxOf(size.toInt(), headLength))
        out.write(head, 0, headLength)
        if (headLength == head.size) {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = vfs.read(file, buf, 0, buf.size)
                if (n <= 0) break
                out.write(buf, 0, n)
                if (out.size() > MachineLimits.MAX_EXECUTABLE_BYTES) fsError(Errno.FBIG, "$path は大きすぎる（上限 ${MachineLimits.MAX_EXECUTABLE_BYTES}）")
            }
        }
        return ExecutableContent.Binary(out.toByteArray())
    }

    /** 割り当てる fd 1つ。表にあるオブジェクト（[Existing]）か、まだ表に足していないもの（[NewObject]） */
    private sealed interface PendingFd {
        /** 表にあるオブジェクトのエントリ（参照はまだ増やさない） */
        class Existing(val entry: HandleEntry) : PendingFd

        /** まだ表にない [obj]。[installFdsLocked] が表に足してから割り当てる */
        class NewObject(val obj: OpenFile, val rights: Int) : PendingFd
    }

    /**
     * fd 0/1/2（[SpawnOptions.stdio]）と fd 3 以降（[SpawnOptions.fds]）の割り当てを決める（参照はまだ増やさない）。
     * 表は変えない（コンソールは作ることがある。preopen のディレクトリは開くだけで、表に足すのは [installFdsLocked]）。
     * 親の fd が開いていない・範囲外なら `BADF`、preopen のディレクトリがない・ディレクトリでないなら `NOENT`・`NOTDIR`、
     * オブジェクトがなければ [IllegalArgumentException]
     */
    private fun resolveFdsLocked(options: SpawnOptions, parent: Process?, paths: SpawnPaths?): Map<Int, PendingFd> {
        val out = LinkedHashMap<Int, PendingFd>()
        for (fd in listOf(STDIN, STDOUT, STDERR)) resolveFdLocked(fd, options.stdio.source(fd), parent, paths)?.let { out[fd] = it }
        for ((fd, source) in options.fds.entries.sortedBy { it.key }) {
            if (fd <= STDERR || fd >= DEFAULT_HANDLE_LIMIT) fsError(Errno.BADF, "fd $fd は渡せない（3 以上 $DEFAULT_HANDLE_LIMIT 未満）")
            resolveFdLocked(fd, source, parent, paths)?.let { out[fd] = it }
        }
        return out
    }

    /** fd [fd] の割り当て1つ。割り当てないなら `null` */
    private fun resolveFdLocked(fd: Int, source: StdioSource, parent: Process?, paths: SpawnPaths?): PendingFd? = when (source) {
        StdioSource.Inherit -> when {
            parent != null -> parent.handles[fd]?.let { PendingFd.Existing(it) }
            fd <= STDERR -> PendingFd.Existing(consoleEntryLocked(fd))
            else -> null
        }
        is StdioSource.ParentFd -> {
            val p = requireNotNull(parent) { "親のないプロセスに親の fd は渡せない" }
            PendingFd.Existing(p.handles[source.fd] ?: fsError(Errno.BADF, "親の fd ${source.fd} は開いていない"))
        }
        is StdioSource.Object -> {
            require(kernel.objects[source.objectId] != null) { "オブジェクト ${source.objectId} がない" }
            PendingFd.Existing(HandleEntry(source.objectId, rights = source.rights))
        }
        is StdioSource.Directory -> {
            // 開くのはロックの外（resolveSpawnPaths）
            PendingFd.NewObject(checkNotNull(paths?.directories?.get(fd)) { "fd $fd の preopen を前もって開いていない" }, HandleRights.READ)
        }
        StdioSource.Console -> PendingFd.Existing(consoleEntryLocked(fd))
        StdioSource.Closed -> null
    }

    /**
     * [pending] を [fds] に割り当てる。[PendingFd.NewObject] は割り当ての直前に表に足し（参照 1）、割り当てで参照 2 にしてから、
     * 最後に作ったときの参照を必ず返す。成功すれば fd の参照だけが残り、途中で失敗すれば 0 になって表から消える
     */
    private fun installFdsLocked(fds: FdTable, pending: Map<Int, PendingFd>) {
        val created = ArrayList<Int>()
        try {
            for ((fd, p) in pending) {
                val entry = when (p) {
                    is PendingFd.Existing -> p.entry
                    is PendingFd.NewObject -> HandleEntry(kernel.objects.add(p.obj).also { created += it }, rights = p.rights)
                }
                fds.installAt(fd, entry)
            }
        } finally {
            for (id in created) kernel.objects.release(id, wakeSink)
        }
    }

    private fun consoleEntryLocked(fd: Int): HandleEntry {
        val id = consoleTtyLocked().id
        return HandleEntry(id, rights = if (fd == STDIN) HandleRights.READ else HandleRights.WRITE)
    }

    /** コンソールの `Tty` を、なければ作る（カーネルが参照を1つ持ち、閉じない） */
    internal fun ensureConsoleLocked() {
        if (kernel.consoleTtyId == 0) kernel.consoleTtyId = kernel.objects.add(Tty(MachineLimits.CONSOLE_INPUT_BYTES, MachineLimits.CONSOLE_OUTPUT_BYTES))
    }

    /** コンソールの `Tty`（なければ作る） */
    internal fun consoleTtyLocked(): Tty {
        ensureConsoleLocked()
        return kernel.objects[kernel.consoleTtyId] as Tty
    }

    /**
     * コンソールの TTY にキー入力 [bytes] を渡す（キーボードから）。どのスレッドから呼んでもよい。
     * TTY がモードに従って全部処理し（行編集、エコー、Ctrl+C）、受け付けたバイト数（あふれて捨てた分を除く）を返す。
     * Ctrl+C などのシグナルは、カーネルロックの中で、起きた順に [ttySignalHandler] に渡す
     */
    internal fun offerConsoleInput(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int = lock.withLockValue {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "範囲が配列の外" }
        // 復元の途中は受け付けない（表を空のまま保つ。#85）
        if (restoringValue) return@withLockValue 0
        val tty = consoleTtyLocked()
        val r = tty.input(bytes, offset, length, wakeSink)
        if (r.signals.isNotEmpty()) {
            val handler = ttySignalHandler
            for (s in r.signals) handler.onSignal(this, s, tty.foregroundPid)
        }
        length - r.dropped
    }

    /**
     * コンソールの画面の大きさを変える（画面のデバイスから。#65・#66）。どのスレッドから呼んでもよい。1 未満は [IllegalArgumentException]。
     * 大きさが実際に変わったときだけ、同じロックの中で `tty-resize` のイベント（値は列・行・世代。ADR 0427）を入れる
     */
    internal fun resizeConsole(columns: Int, rows: Int) {
        val size = TtySize(columns, rows)
        lock.withLockValue {
            // 復元の途中は受け付けない（戻した後に画面のデバイスが渡し直す。#85）
            if (restoringValue) return@withLockValue
            val tty = consoleTtyLocked()
            if (tty.resize(size)) {
                val values = listOf(EventValue.Integer(columns.toLong()), EventValue.Integer(rows.toLong()), EventValue.Integer(tty.sizeGeneration))
                kernel.events.post(KernelEvent(TtyLimits.RESIZE_EVENT_TYPE, TtyLimits.CONSOLE_SOURCE, values), kernel.objects, wakeSink)
            }
        }
    }

    /** コンソールの画面の大きさ */
    internal val consoleSize: TtySize get() = lock.withLockValue { if (restoringValue && kernel.consoleTtyId == 0) TtySize(Tty.DEFAULT_COLUMNS, Tty.DEFAULT_ROWS) else consoleTtyLocked().size }

    /** コンソールの画面の大きさが変わった回数 */
    internal val consoleSizeGeneration: Long get() = lock.withLockValue { if (restoringValue && kernel.consoleTtyId == 0) 0L else consoleTtyLocked().sizeGeneration }

    private fun markConsoleDirty() {
        // 通知の列に印を積んで、TTY の出力の順番を、ほかの通知（カーネルのメッセージなど）との前後で決める
        if (consoleDirty.compareAndSet(false, true)) postNotification(HostNotification.TtyOutput)
    }

    /** コンソールの TTY にたまった出力を取り出す（書き手を起こす）。なければ `null` */
    private fun drainConsoleOutput(): ByteArray? = lock.withLockValue { drainConsoleOutputLocked() }

    private fun drainConsoleOutputLocked(): ByteArray? {
        val id = kernel.consoleTtyId
        val tty = (if (id == 0) null else kernel.objects[id] as? Tty) ?: return null
        return tty.drainOutput(wakeSink)
    }

    /**
     * コンソールの TTY にたまった出力を、呼んだスレッドで [consoleSink] に書く（#355）。コンソールの TTY に書いたプロセスの
     * スレッドから呼ぶ（`ProcessContext.flushConsole`）。触ったセルの数（燃料で払わせる重さ）を返す。
     *
     * 書いた順を保つため、次のときは書かずに 0 を返す（残りはメインスレッドが [deliverNotifications] で配る）:
     * - [consoleSink] がない、またはカーネルロックを持っている（ロックの順は「表示デバイス → カーネル」）
     * - 配っていないカーネルのメッセージがある（その前後の TTY の出力は、メインスレッドが目印で分けて配る。#381）
     * - メインスレッドがコンソールの出力を止めている（画面の処理が追いついていない。#357）
     *
     * [reserved] なら、[reserveConsolePumpLocked] の予約を1つ消す（書いても書かなくても。#419）。予約は、TTY から取り出すのと
     * 同じカーネルロックの中で消すので、書く先のロックを持つメインスレッドからは、予約が消えたときには TTY が空になっている
     * （書かずに 0 を返したときは、残りをメインスレッドが配る）
     */
    internal fun pumpConsole(reserved: Boolean = false): Long {
        var unreserved = !reserved
        try {
            val sink = consoleSink ?: return 0
            if (lock.isHeldByCurrentThread) return 0
            sink.lock.lock()
            try {
                if (consoleSink !== sink) return 0
                val bytes = lock.withLockValue {
                    if (!unreserved) {
                        consolePumpsReserved--
                        unreserved = true
                    }
                    if (consoleHold != null || pendingConsoleBytes.get() > 0L) null else drainConsoleOutputLocked()
                } ?: return 0
                return sink.write(bytes)
            } finally {
                sink.lock.unlock()
            }
        } finally {
            if (!unreserved) lock.withLockValue { consolePumpsReserved-- }
        }
    }

    /**
     * コンソールの TTY に書いて、まだ [pumpConsole] で書いていないプロセスの数（#419。カーネルロック）。
     * 0 より大きい間は、TTY に残った出力を書き手がいないものとして、メインスレッドが取り出さない（[drainStaleConsoleOutput]）
     */
    private var consolePumpsReserved = 0

    /**
     * コンソールの TTY に書いたプロセスが、この後に [pumpConsole]（`reserved = true`）で書くと予約する（#419）。
     * TTY に書いたのと同じカーネルロックの中で呼ぶ
     */
    internal fun reserveConsolePumpLocked() {
        consolePumpsReserved++
    }

    /**
     * イベントを、種類の合うイベントキューに配る（#63、ADR 0063。出す側はタイマー #64、デバイス #65・#67、入力 #143、ネットワーク #164）。
     * どのスレッドから呼んでもよい。待たない（カーネルロックを短く取る）。一時停止中・止まったマシンでも受け付ける（キューがなければ捨てる）。
     * 配ったキューの数を返す（あふれて捨てた分は数えない）
     */
    internal fun postEvent(event: KernelEvent): Int = lock.withLockValue { kernel.events.post(event, kernel.objects, wakeSink) }

    // ---- デバイスの呼び出し（#65、ADR 0065） ----

    /**
     * プロセス [pid] に、待たずに積むデバイスの呼び出し（[PendingDeviceCall]、[state]）を作って fd 表に入れ、fd とオブジェクトを返す
     * （読む権利だけ）。カーネルロックの中で呼ぶ。実行に回すのは、ロックを放してから [dispatchPendingDeviceCall] で行う。
     * そのプロセスの `pending-call` が [DeviceLimits.MAX_PENDING_CALLS] 個あるなら `AGAIN`、fd 表が満杯なら `MFILE`
     */
    @Throws(FsException::class)
    internal fun openPendingDeviceCallLocked(pid: Int, state: PendingDeviceCall.State): Pair<Int, PendingDeviceCall> {
        val process = checkNotNull(kernel.processes[pid]) { "プロセス $pid がない" }
        val fds = FdTable(process, kernel.objects, wakeSink)
        val open = fds.entries().values.count { kernel.objects[it.objectId] is PendingDeviceCall }
        if (open >= DeviceLimits.MAX_PENDING_CALLS) {
            fsError(Errno.AGAIN, "待たずに積んだデバイスの呼び出しは ${DeviceLimits.MAX_PENDING_CALLS} 個まで")
        }
        val obj = PendingDeviceCall(state)
        return fds.open(obj, HandleRights.READ) to obj
    }

    /**
     * まだ実行していない [obj]（表に入っていること）を、デバイスの呼び出しとして実行に回す（保存から戻したものの積み直しにも使う）。
     * 直接のメソッドは、このスレッドで実行してから返る。終わったら [obj] を終わりにして待ち手を起こす（閉じた後なら何もしない）。
     * カーネルロックを持たずに呼ぶ（デバイスのハンドラーを呼ぶため）。払う燃料を返す（まだ実行していないものでなければ 0）
     */
    internal fun dispatchPendingDeviceCall(pid: Int, obj: PendingDeviceCall): Long {
        check(!lock.isHeldByCurrentThread) { "カーネルロックを持ってデバイスを呼ばない" }
        val queued = lock.withLockValue { obj.state as? PendingDeviceCall.State.Queued } ?: return 0
        val started = devices.dispatcher.start(pid, queued.address, queued.method, queued.args) {
            lock.withLockValue { obj.markRunning() }
        }
        lock.withLockValue {
            // 終わるまでに最後の fd を閉じたら取り消す（閉じた後なら、その場で取り消す。#532）
            if (started.future.isDone) return@withLockValue
            if (obj.id != 0 && kernel.objects[obj.id] === obj) pendingDeviceCalls[obj.id] = started else started.cancelLocked()
        }
        started.future.whenComplete { _, _ ->
            val result = DeviceDispatcher.resultOf(started.future)
            lock.withLockValue {
                if (pendingDeviceCalls[obj.id] === started) pendingDeviceCalls.remove(obj.id)
                if (obj.id != 0 && kernel.objects[obj.id] === obj && obj.finish(result)) {
                    wakeSink.wake(WaitKey(obj.id, WaitCondition.READABLE))
                }
            }
        }
        return started.fuel
    }

    /**
     * プロセス [pid] にイベントキューを作ってハンドル表に入れ、ハンドル番号を返す（読む権利だけ）。カーネルロックの中で呼ぶ。
     * 確かめる順は capacity（範囲外は `INVAL`）→ マシンのキューの数（[ResourceLimitException]）→ ハンドル表（満杯は `MFILE`）。
     * 失敗したら何も残さない
     */
    internal fun openEventQueueLocked(pid: Int, filter: EventFilter, capacity: Int): Int {
        if (capacity !in 1..EventLimits.MAX_CAPACITY) fsError(Errno.INVAL, "キューの件数は 1..${EventLimits.MAX_CAPACITY}（$capacity）")
        val process = checkNotNull(kernel.processes[pid]) { "プロセス $pid がない" }
        kernel.events.ensureCapacity(kernel.objects)
        val queue = EventQueue(filter, capacity)
        val fd = FdTable(process, kernel.objects, wakeSink).open(queue, HandleRights.READ)
        kernel.events.subscribe(queue, kernel.objects)
        return fd
    }

    // ---- 時計・タイマー（#64、ADR 0064） ----

    /** [close] した（カーネルロック）。この後はタイマーの予定を入れない */
    private var closedValue = false

    /** マシンの単調時計（ナノ秒。マシンを作ったときが 0）。カーネルロックの中で呼ぶ */
    internal fun monotonicNanosLocked(): Long = kernel.clocks.monotonicNanos(environment.clock)

    /** マシンの単調時計。どのスレッドから呼んでもよい（カーネルロックを短く取る） */
    internal fun monotonicNanos(): Long = lock.withLockValue { monotonicNanosLocked() }

    /** 起動からの経過（tick）。どのスレッドから呼んでもよい */
    internal fun uptimeTicks(): Long = lock.withLockValue { kernel.clocks.uptimeTicks(scheduler.now) }

    /**
     * タイマー [timer]（オブジェクト表に入っていること）を、単調時計の [deadline] に鳴るように設定する（[interval] が正なら繰り返す）。
     * カーネルロックの中で呼ぶ。[interval] が負か、正で `MIN_INTERVAL_NANOS` 未満なら `INVAL`。
     * 予定を入れていないタイマーを足すと、マシンの動いているタイマーの数が上限を超えるなら [ResourceLimitException]
     */
    @Throws(FsException::class, ResourceLimitException::class)
    internal fun armTimerLocked(timer: Timer, deadline: Long, interval: Long) {
        if (interval < 0 || (interval in 1 until TimerLimits.MIN_INTERVAL_NANOS)) {
            fsError(Errno.INVAL, "繰り返しの間隔は 0 か ${TimerLimits.MIN_INTERVAL_NANOS} ns 以上（$interval）")
        }
        if (!timers.isScheduled(timer.id) && timers.scheduledCount >= TimerLimits.MAX_ARMED_TIMERS) {
            throw ResourceLimitException("動いているタイマーの数が上限（${TimerLimits.MAX_ARMED_TIMERS}）")
        }
        timer.arm(deadline, interval)
        scheduleTimerLocked(timer)
    }

    /** タイマー [timer] を止める（数えた回数も 0 に戻す）。カーネルロックの中で呼ぶ */
    internal fun disarmTimerLocked(timer: Timer) {
        timer.disarm()
        timers.cancel(timer.id)
    }

    /**
     * タイマー [timer] の鳴った回数を取り出して 0 に戻す。止めておいた繰り返しのタイマー（[MachineTimers]）は、
     * ここで過ぎた回をまとめて数えてから取り出し、次の期限の予定を入れ直す。カーネルロックの中で呼ぶ
     */
    internal fun readTimerLocked(timer: Timer): Long {
        if (timer.armed && !timers.isScheduled(timer.id)) timer.expire(monotonicNanosLocked(), wakeSink)
        val n = timer.read()
        if (timer.armed && !timers.isScheduled(timer.id)) scheduleTimerLocked(timer)
        return n
    }

    /** [timer] の今の期限に予定を入れる（期限を過ぎていればすぐ）。カーネルロックの中で呼ぶ */
    private fun scheduleTimerLocked(timer: Timer) {
        if (closedValue || !timer.armed) {
            timers.cancel(timer.id)
            return
        }
        timers.schedule(timer.id, timer.generation, saturatingSub(timer.deadlineNanos, monotonicNanosLocked()))
    }

    /** 期限の予定が来た（[TimerService] のスレッドから）。カーネルロックを短く取って、タイマーを鳴らす */
    private fun onTimerDue(timerId: Int, generation: Long): Unit = lock.withLockValue {
        val timer = kernel.objects[timerId] as? Timer
        if (timer == null || timer.generation != generation) return@withLockValue
        if (!timer.armed) {
            timers.forget(timerId)
            return@withLockValue
        }
        val now = monotonicNanosLocked()
        val count = timer.expire(now, wakeSink)
        if (count > 0 && timer.sendsEvents) deliverTimerEventLocked(timer, count)
        // 読まれていない繰り返しのタイマー（イベントを出さないもの）は、読まれるまで予定を入れない
        if (timer.armed && (timer.sendsEvents || timer.expirations == 0L)) {
            scheduleTimerLocked(timer)
        } else {
            timers.forget(timerId)
        }
    }

    /** [timer] のイベント（`timer`。値は tag と回数）を、そのキューにだけ入れる。キューが閉じていれば捨てる */
    private fun deliverTimerEventLocked(timer: Timer, count: Long) {
        val queue = kernel.objects[timer.eventQueueId] as? EventQueue ?: return
        // イベントで知らせた回数は読んだことにする（数えた回数は READABLE のためだけに残さない）
        timer.read()
        val event = KernelEvent(TimerLimits.EVENT_TYPE, "", listOf(EventValue.Integer(timer.eventTag), EventValue.Integer(count)))
        kernel.events.deliverTo(queue, event, kernel.objects, wakeSink)
    }

    /** 今の時計の値（保存に書き出す。#81）。どのスレッドから呼んでもよい */
    internal fun captureClocks(): ClockSnapshot = lock.withLockValue { kernel.clocks.snapshot(environment.clock, scheduler.now) }

    /**
     * [snapshot] の時点から単調時計と経過時間が続くように決め直し、動いているタイマーの予定を入れ直す（復元。#81）。
     * タイマーの期限は単調時計で持つので、止まっていた間の分は進まない（残りの時間から続く）。
     * 実時間とゲーム内時間がどれだけ飛んだかを返す（イベントにするのは #87）。どのスレッドから呼んでもよい
     */
    internal fun restoreClocks(snapshot: ClockSnapshot): TimeJump = lock.withLockValue {
        val jump = kernel.clocks.restore(snapshot, environment.clock, scheduler.now)
        timers.cancelAll()
        kernel.objects.forEachObject { obj ->
            if (obj is Timer && obj.armed && (obj.sendsEvents || obj.expirations == 0L)) scheduleTimerLocked(obj)
        }
        jump
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
        // 復元の途中・冷えた起動待ちなら取り消す（#85）。止めるのをやめるのは、プロセスを止めた後
        val restoreHandleToClose = restoreHandle
        val wasRestoring = restoreHandleToClose != null && (restoringValue || coldBootPending)
        if (restoreHandleToClose != null || coldBootPending) {
            restoreCancelled = true
            restoringValue = false
            coldBootPending = false
            restoreHandle = null
            restoreCancelSignal.complete(Unit)
        }
        if (bootingValue) {
            bootCancelled = true
            // 後続（init の読み込みの待ち。whenInitLoaded）は、読み込みを取り消して起動用のスレッドに続きを積むだけなので、ロックの中で終えてよい
            bootCancelSignal.complete(Unit)
        }
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
            killRuntimeLocked(rt, null)
        }
        // 取りこぼし（すでに終わったプロセスが残した分など）を防ぐため、残りもすべて取り消す
        cancelHostCallsLocked()
        // 後で返すデバイスの呼び出し（#65）は、実行中のまま残さず取り消す（isCancelled が立つ）
        devices.dispatcher.cancelAllLocked()
        // ホストのディスク（#52）は、この後の操作を断る（止まり切っていない古いスレッドが触らないように）。閉じるのはロックの外
        vfs.revokeAll()
        checkAllDoneLocked()
        if (restoreHandleToClose != null) {
            // 止めるのをやめても、プロセスはすべて止めた後なので動かない
            restoreHandleToClose.close()
            if (wasRestoring && crash == null) postRestoreFinishedLocked(RestoreOutcome.CANCELLED, null)
            restoreRequest = null
        }
    }

    /**
     * プロセス1つを止める（[stopLocked] と、シグナルの [ProcessLifecycle.killLocked] から。カーネルロックの中）。
     * 停止の印を立て、まだ実行していないホスト呼び出しを取り消し、スレッドがあれば割り込む。スレッドのない待機点型はその場で終える。
     * スレッドがまだ登録されていないブロッキング型は、登録のときに印を見て終わる。
     *
     * @param signal 止めたシグナル（`stop()` なら `null`）。すでに止めているときは、最初のものを残す
     */
    internal fun killRuntimeLocked(rt: ProcessRuntime, signal: KillSignal?) {
        if (rt.process.state.isTerminal) return
        if (!rt.killed) rt.killSignal = signal
        rt.killed = true
        cancelHostCallsLocked(rt.pid)
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
    }

    /** [pid] にシグナルを送る（[ProcessLifecycle.killLocked]）。カーネルロックの中で呼ぶ */
    internal fun killLocked(pid: Int, signal: KillSignal): KillResult = lifecycle.killLocked(pid, signal)

    /** [pid] が今の起動の init か（カーネルロックの中） */
    internal fun isInitLocked(pid: Int): Boolean = kernel.initPid != 0 && pid == kernel.initPid

    /**
     * 終わったプロセス [pid] を刈り取る（#46）。表と実行時の部分から外し、カーネルが持つ `ProcessObject` の参照を返す。
     * 専用スレッドがまだ生きていれば [retiredThreads] に足し、同時に終わったスレッドを外す（再起動しなくても増え続けない）。
     * カーネルロックの中で呼ぶ。表にないか、終わっていなければ何もしない
     */
    internal fun reapLocked(pid: Int) {
        val process = kernel.processes[pid] ?: return
        if (!process.state.isTerminal) return
        kernel.processes.remove(pid)
        val rt = runtimes.remove(pid)
        if (rt != null) {
            fuelWaiters.remove(rt)
            rt.dedicatedThread?.let { if (it.isAlive) retiredThreads += it }
        }
        retiredThreads.removeAll { !it.isAlive }
        lifecycle.onReapedLocked(process)
    }

    /**
     * 表（ゾンビを含む）が `maxProcesses` × 2 に達していたら、親のないゾンビを古い順に刈り取って空ける
     * （仮の値。確定は #251）。カーネルロックの中で呼ぶ
     */
    private fun reapOrphanZombiesLocked() {
        val cap = config.maxProcesses.toLong() * ORPHAN_ZOMBIE_TABLE_FACTOR
        while (kernel.processes.size >= cap) {
            val oldest = kernel.processes.orphanZombies.firstOrNull() ?: break
            reapLocked(oldest)
        }
    }

    /** 専用スレッドのうち、まだ終わっていないかもしれないものの数（テスト用） */
    internal val retiredThreadCount: Int get() = lock.withLockValue { retiredThreads.size }

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
        if (fuelWaiters.isEmpty() || scheduler.isPausedFor(this)) return 0
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

    /**
     * 最後のプロセスが終わったら STOPPED にする。カーネルロックの中で呼ぶ。
     *
     * 起動の途中（[bootingValue]）でなければ、`/tmp`（RAM に数えるファイルシステム）のマウントを外して RAM を返す（#362）。
     * ディスクなどほかのマウントは、次の起動まで残す。
     * 次の起動を待たずに、止まったマシン・閉じたマシン（`close()` はプロセスの終わりを待たない）が
     * サーバー全体の勘定（[MachineScheduler.memoryAccount]）を持ち続けないようにする。
     * 起動の途中の取り消しは [endBoot] が外す。
     */
    internal fun checkAllDoneLocked() {
        if (kernel.processes.liveCount() > 0) return
        if (!bootingValue) unmountTmpLocked()
        if (stateValue == MachineState.RUNNING || stateValue == MachineState.STOPPING) setStateLocked(MachineState.STOPPED, null)
        if (registeredThreads == 0) {
            terminated.signalAll()
            maybePostRebootLocked()
        }
    }

    // ---- 起動・init の終わり・再起動（#49） ----

    /** 起動の途中で取り消された */
    internal val isBootCancelled: Boolean get() = bootCancelled

    /**
     * プロセス [process] が終わった（`ProcessRunner.finishLocked` から。カーネルロックの中）。
     *
     * - init が `stop()`・再起動によらずに終わったら、コンソールに書いて、残りのプロセスをすべて止める
     * - init でないプロセスが例外で `CRASHED` になったら、理由をコンソールに書く（#41）。ほかのプロセスはそのまま
     *
     * - init でないプロセスが 0 以外の終了コードで終わり、[exitNote] があれば（`memory.grow` を RAM の上限で断られていた。#407）、そう書く
     *
     * @param crashReason `CRASHED` の理由（[crashReason][pro.ikili.kibicomputers.core.kernel.process.crashReason]）。それ以外は `null`
     * @param exitNote `EXITED` で 0 以外の終了コードのときに添える文（メモリ不足。#407）。なければ `null`
     */
    internal fun onProcessFinishedLocked(process: Process, crashReason: String?, exitNote: String?) {
        val isInit = kernel.initPid != 0 && process.pid == kernel.initPid && process.parentPid == 0
        if (isInit) kernel.initPid = 0
        // STOPPING（stop()・reboot()）と CRASHED（カーネルの不整合）では何も言わない
        if (stateValue != MachineState.RUNNING) return
        val note = if (exitNote != null) " ($exitNote)" else ""
        if (!isInit) {
            when {
                process.state == ProcessState.CRASHED -> writeConsoleLine("kibi: ${processLabel(process)} crashed: ${crashReason ?: "unknown"}")
                process.state == ProcessState.EXITED && exitNote != null ->
                    writeConsoleLine("kibi: ${processLabel(process)} exited with code ${process.exitCode}$note")
            }
            return
        }
        val message = when (process.state) {
            ProcessState.EXITED -> "kibi: init exited with code ${process.exitCode}$note"
            ProcessState.CRASHED -> "kibi: init crashed: ${crashReason ?: "unknown"}"
            else -> "kibi: init was killed"
        }
        writeConsoleLine(message)
        if (kernel.processes.liveCount() > 0) stopLocked(null)
    }

    /** コンソールのメッセージでプロセスを指す名前（`argv[0] (pid n)`。引数がなければ `pid n`） */
    private fun processLabel(process: Process): String {
        val name = process.args.firstOrNull()?.takeIf { it.isNotEmpty() }?.let(::sanitizeForConsole)
        return if (name == null) "pid ${process.pid}" else "$name (pid ${process.pid})"
    }

    /** 制御文字を `?` にし、長すぎれば切る（プレイヤーが決めた名前で端末を操作させない） */
    private fun sanitizeForConsole(s: String): String {
        val cleaned = buildString {
            for (c in s) append(if (Character.isISOControl(c)) '?' else c)
        }
        return if (cleaned.length <= MAX_LABEL_CHARS) cleaned else cleaned.take(MAX_LABEL_CHARS) + "..."
    }

    /** 再起動を求められていて、プロセスもスレッドも残っていなければ、再起動の求めを通知の列に積む（カーネルロックの中） */
    private fun maybePostRebootLocked() {
        if (!rebootRequested || bootingValue || stateValue != MachineState.STOPPED) return
        if (kernel.processes.liveCount() > 0 || registeredThreads > 0) return
        rebootRequested = false
        rebootNotified = true
        postNotification(HostNotification.RebootRequested)
    }

    /**
     * 起動の手順の始め（起動用のスレッドから）。前の起動のスレッドが登録を外すのを待ち、ゾンビを外して pid を 1 に戻し、
     * マウントをすべて外す（コンソールの TTY は残す）。取り消されたら `false`
     */
    internal fun beginBoot(): Boolean = lock.withLockValue {
        try {
            while (registeredThreads > 0 && !bootCancelled && !scheduler.isClosed) terminated.awaitNanos(BOOT_WAIT_NANOS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return@withLockValue false
        }
        if (bootCancelled || scheduler.isClosed) return@withLockValue false
        check(kernel.processes.liveCount() == 0) { "起動の前に終わっていないプロセスがある" }
        for (rt in runtimes.values) rt.dedicatedThread?.let { retiredThreads += it }
        retiredThreads.removeAll { !it.isAlive }
        runtimes.clear()
        fuelWaiters.removeAll { it.process.state.isTerminal }
        // 前の起動のゾンビの ProcessObject を返す（#46）
        for (process in kernel.processes.all()) {
            if (process.objectId != 0 && kernel.objects[process.objectId] != null) kernel.objects.release(process.objectId, wakeSink)
        }
        kernel.processes.reset()
        // 起動からの経過を 0 に戻す（ADR 0064）
        kernel.clocks.resetUptime(scheduler.now)
        // 閉じたイベントキューの ID を索引から外す（ADR 0063）
        kernel.events.sweep(kernel.objects)
        kernel.initPid = 0
        unmountAllLocked()
        if (kernel.consoleTtyId != 0) consoleTtyLocked().foregroundPid = 0
        true
    }

    /** 起動ディスク [diskId] の [volume] を `/` にマウントする（起動用のスレッドから）。取り消されていれば何もせず `false` */
    internal fun mountBootRoot(diskId: String, volume: BootVolume): Boolean {
        // 位置の確かめ（ファイルシステムを呼ぶことがある）はロックの外、表を変えるのはロックの中（#52）
        vfs.checkMountPoint(VfsPath.ROOT)
        return lock.withLockValue {
            if (bootCancelled) return@withLockValue false
            vfs.mountLocked(VfsPath.ROOT, volume.fs, volume.readOnly, "${MachineLimits.DISK_SOURCE_PREFIX}$diskId")
            true
        }
    }

    /**
     * 空の `/tmp`（[TmpFileSystem]。中身は RAM に数える。#53）を [MachineLimits.TMP_PATH] にマウントする（起動用のスレッドから、根のマウントの後）。
     * 根のディスクの `/tmp` がディレクトリ以外のものなら、マウントせずにその [Errno] を返す（起動は続ける）
     */
    internal fun mountTmp(): Errno? {
        val fs = TmpFileSystem(memoryAccount) { environment.clock.realtimeNanos() }
        return try {
            // 根のディスクの /tmp の確かめ（ホストの I/O のことがある）はロックの外（#52）
            vfs.checkMountPoint(MachineLimits.TMP_PATH)
            lock.withLockValue { vfs.mountLocked(MachineLimits.TMP_PATH, fs, readOnly = false, source = MachineLimits.TMP_SOURCE) }
            null
        } catch (e: FsException) {
            e.errno
        }
    }

    /**
     * デバイスファイル（[DevFileSystem]。`null`・`zero`・`tty`。#485）を [MachineLimits.DEV_PATH] にマウントする（起動用のスレッドから、`/tmp` のマウントの後）。
     * 根のディスクの `/dev` がディレクトリ以外のものなら、マウントせずにその [Errno] を返す（起動は続ける）。
     * 中身は状態を持たず RAM も使わないので、ディスクと同じく、外すのは次の起動の始め（か起動の取り消し）
     */
    internal fun mountDev(): Errno? = try {
        vfs.checkMountPoint(MachineLimits.DEV_PATH)
        lock.withLockValue { vfs.mountLocked(MachineLimits.DEV_PATH, DevFileSystem(), readOnly = false, source = MachineLimits.DEV_SOURCE) }
        null
    } catch (e: FsException) {
        e.errno
    }

    /**
     * [volume]（ディスク [diskId]）を [MachineLimits.HOME_PATH] に書き込める形でマウントする（起動用のスレッドから、`/tmp` のマウントの後。#461）。
     * 根の `/home` がディレクトリ以外のものなら、マウントせずにその [Errno] を返す（起動は続ける）。マウントしなかったら [volume] を閉じる。
     * 起動が取り消されていれば [BootCancelledException]
     */
    internal fun mountHome(diskId: String, volume: BootVolume): Errno? {
        try {
            vfs.checkMountPoint(MachineLimits.HOME_PATH)
            val mounted = lock.withLockValue {
                if (bootCancelled) return@withLockValue false
                vfs.mountLocked(MachineLimits.HOME_PATH, volume.fs, readOnly = false, source = "${MachineLimits.DISK_SOURCE_PREFIX}$diskId")
                true
            }
            if (!mounted) {
                volume.fs.retire()
                throw BootCancelledException()
            }
            return null
        } catch (e: FsException) {
            volume.fs.retire()
            return e.errno
        } catch (t: Throwable) {
            if (t !is BootCancelledException) volume.fs.retire()
            throw t
        }
    }

    /**
     * 起動の手順を、init を作らずに終える（起動用のスレッドから）。マウントを外す。
     * [failure] が `null`（取り消し）か、取り消されていれば何も知らせない。そうでなければコンソールに書いて [MachineHost.onBootFailed] で知らせる
     *
     * @param reason コンソールのメッセージに入れる短い英語（errno の名前など）
     * @param detail [MachineHost.onBootFailed] に渡す理由（`null` なら [reason]）。[BootFailure.INIT_NOT_EXECUTABLE] なら、コンソールにも次の行に書く
     */
    internal fun endBoot(failure: BootFailure?, reason: String, detail: String? = null): Unit = lock.withLockValue {
        if (!bootingValue) return@withLockValue
        bootingValue = false
        if (kernel.processes.liveCount() == 0) unmountAllLocked()
        if (failure != null && !bootCancelled) {
            writeConsoleLine(BootSequence.consoleMessage(failure, reason))
            // init を実行できない理由（待機点に変換できない作りなら、どの命令・どの機能か。#367）は、次の行に出す
            if (failure == BootFailure.INIT_NOT_EXECUTABLE && detail != null) writeConsoleLine("kibi: $detail")
            postNotification(HostNotification.BootFailed(failure, detail ?: reason))
        }
        bootCancelled = false
        maybePostRebootLocked()
    }

    /** `/tmp`（[MachineLimits.TMP_SOURCE]）のマウントを外し、中身が持つ RAM を返す（カーネルロックの中） */
    private fun unmountTmpLocked() {
        for (entry in kernel.mounts.entries.filter { it.source == MachineLimits.TMP_SOURCE }) vfs.unmount(entry.id)
    }

    /** マウントを深いほうからすべて外す（カーネルロックの中） */
    private fun unmountAllLocked() {
        for (entry in kernel.mounts.entries.sortedByDescending { it.point.depth }) vfs.unmount(entry.id)
    }

    /** カーネルのメッセージを1行、コンソールに書く（改行を足す） */
    internal fun writeConsoleLine(line: String) {
        writeConsole((line + "\n").toByteArray(Charsets.UTF_8))
    }

    private fun setStateLocked(state: MachineState, cause: Throwable?) {
        if (stateValue == state) return
        stateValue = state
        postNotification(HostNotification.StateChanged(state, cause))
    }

    private fun postNotification(n: HostNotification) {
        notifications.add(n)
        if (queuedForNotify.compareAndSet(false, true)) scheduler.enqueueNotify(this)
    }

    // ---- カーネル → 接続層（#44） ----

    /**
     * コンソールへ出力する（次の `tick()` で [MachineHost.onConsoleOutput] に渡す）。どのスレッドから呼んでもよい。
     * TTY を通らないカーネルのメッセージ用（TTY の桁の数えには入らない）。TTY の出力とそろえて、LF を CR LF にする（#60）。
     * 配っていない分（CR LF にした後のバイト数）が [MachineLimits.MAX_PENDING_CONSOLE_BYTES] を超えるなら、超える分を捨てて `false`。
     * 書き手を待たせる流量の制御は、ABI 層が TTY（fd 1・2）で行う（#58・#60）。
     * TTY の出力との前後を保つため、カーネルロックの中で、その時点の TTY の出力の位置を目印に付けて積む（#381）
     */
    internal fun writeConsole(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Boolean {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "範囲が配列の外" }
        if (length == 0) return true
        return postConsole(nlToCrNl(bytes, offset, length))
    }

    /** LF を CR LF にした [bytes] を通知の列に積む（上限を超える分は捨てる） */
    private fun postConsole(bytes: ByteArray): Boolean = lock.withLockValue {
        val length = bytes.size
        var accepted: Int
        while (true) {
            val pending = pendingConsoleBytes.get()
            accepted = (MachineLimits.MAX_PENDING_CONSOLE_BYTES - pending).coerceIn(0, length.toLong()).toInt()
            if (accepted == 0 || pendingConsoleBytes.compareAndSet(pending, pending + accepted)) break
        }
        if (accepted < length) droppedConsoleBytes.addAndGet((length - accepted).toLong())
        if (accepted > 0) {
            // 目印と列の位置を、TTY の印（markConsoleDirty。TTY に書くときにカーネルロックの中で積む）とそろえる
            val tty = kernel.consoleTtyId.let { if (it == 0) null else kernel.objects[it] as? Tty }
            val chunk = if (accepted == length) bytes else bytes.copyOf(accepted)
            postNotification(HostNotification.Console(chunk, tty, tty?.outputWritten ?: 0L))
        }
        accepted == length
    }

    /** [bytes] の範囲を写し、LF を CR LF にする（ONLCR と同じ） */
    private fun nlToCrNl(bytes: ByteArray, offset: Int, length: Int): ByteArray {
        var lf = 0
        for (i in offset until offset + length) if (bytes[i] == LF_BYTE) lf++
        if (lf == 0) return bytes.copyOfRange(offset, offset + length)
        val out = ByteArray(length + lf)
        var j = 0
        for (i in offset until offset + length) {
            val b = bytes[i]
            if (b == LF_BYTE) out[j++] = CR_BYTE
            out[j++] = b
        }
        return out
    }

    /**
     * [callback] を次の `tick()` の中で、メインスレッドから、ほかの通知と起きた順に呼ぶ（スピーカーのデバイスの音など。#65）。
     * どのスレッドから呼んでもよい。待たない。中で投げた例外は捨てる
     */
    internal fun postHostCallback(callback: Runnable) {
        postNotification(HostNotification.Callback(callback))
    }

    /**
     * `tick()` の中で、メインスレッドから通知を配る（ロックの外）。続いたコンソール出力は1回にまとめる。
     *
     * TTY の出力は、たまった最初のときに通知の列へ印（[HostNotification.TtyOutput]）を積む。印より後は、TTY から取り出して、
     * 前後の通知と書いた順に並べる（起動のログ → init の出力 → init の終了のメッセージ → RUNNING など）。カーネルのメッセージの前では、
     * そのメッセージを書いた時点までに TTY に書いた分だけを取り出す（[HostNotification.Console.ttyMark]。#381）
     */
    internal fun deliverNotifications() {
        queuedForNotify.set(false)
        val out = ConsoleCollector()
        // 止めている相手に、新しい tick になったことを知らせる（続きを処理して、追いつけば止めるのをやめる）
        consoleHold?.let { safely { it.resume() } }
        if (consoleHold == null) resumeDeferredConsole(out)
        while (true) {
            val n = notifications.poll() ?: break
            if (n is HostNotification.Console || n === HostNotification.TtyOutput) {
                // 止めている間は、書いた順に取っておく（配っていないバイト数は、配るまで減らさない。TTY の印は下ろさない）
                if (consoleHold != null) deferConsole(n) else out.add(n)
                continue
            }
            out.flush()
            when (n) {
                is HostNotification.StateChanged -> safely { host.onStateChanged(this, n.state, n.cause) }
                is HostNotification.Callback -> safely { n.callback.run() }
                is HostNotification.BootFailed -> safely { host.onBootFailed(this, n.failure, n.detail) }
                HostNotification.RebootRequested -> {
                    // 積んだ後に stop() されたら、再起動しない
                    val go = lock.withLockValue { rebootNotified.also { rebootNotified = false } }
                    if (go) safely { host.onRebootRequested(this) }
                }
                is HostNotification.Console, HostNotification.TtyOutput -> Unit
            }
        }
        // 通知の中で止めるのをやめた（つなぎを外したなど）なら、取っておいた分もここで配る
        if (consoleHold == null) resumeDeferredConsole(out)
        out.flush()
        // 止めている間は、毎 tick 回ってくる（止めている台だけ。待っている台の負荷は増えない）
        if (consoleHold != null) requestNotify()
        // TTY に残った出力を、次の tick に見直す（この tick の中では配り直さない）
        if (recheckTtyOutput) {
            recheckTtyOutput = false
            if (consoleDirty.compareAndSet(false, true)) postNotification(HostNotification.TtyOutput)
        }
    }

    /**
     * 1回の通知で配るコンソール出力を、書いた順にまとめる（メインスレッドだけで使う）。
     * TTY の印を見たら、TTY から取り出すのを、次のカーネルのメッセージの前（その目印まで）か、ほかの通知の前・終わり（全部）まで遅らせる
     */
    private inner class ConsoleCollector {
        private var bytes: ByteArrayOutputStream? = null

        // TTY の印を見て、まだ全部は取り出していない
        private var ttyPending = false

        // TTY から取り出してから、配り終えるまで持つ、書く先のロック（#355。その間にプロセスのスレッドが後の出力を先に書かないように）
        private var locked: ConsoleSink? = null

        // TTY の印を見たが、書く先をプロセスのスレッドが書いている最中だったので、取り出すのを次の tick に回した（#351）
        private var ttySkipped = false

        private fun lockSink() {
            if (locked != null) return
            val sink = consoleSink ?: return
            sink.lock.lock()
            locked = sink
        }

        // 書く先のロックを、待たずに取ってみる。書く先がない（メインスレッドで取り出して配る）なら、取れたものとする
        private fun tryLockSink(): Boolean {
            if (locked != null) return true
            val sink = consoleSink ?: return true
            if (!sink.lock.tryLock()) return false
            locked = sink
            return true
        }

        private fun unlockSink() {
            val sink = locked ?: return
            locked = null
            sink.lock.unlock()
        }

        fun add(n: HostNotification) {
            if (n === HostNotification.TtyOutput) {
                // 印を下ろしてから取り出す（下ろした後の出力は、新しい印で積まれる）
                consoleDirty.set(false)
                if (!tryLockSink()) {
                    // プロセスのスレッドが書く先に書いている最中なら、TTY の出力はそのスレッドが書く（#355）。
                    // メインスレッドはロックを待たず、残ったままの出力がないかを次の tick に見直す（#351。待つと、出力し続ける台の数に比例して
                    // メインスレッドが止まる）。この tick にカーネルのメッセージが来たら、そのときにロックを待って取り出す
                    ttySkipped = true
                    recheckTtyOutput = true
                    return
                }
                ttyPending = true
                return
            }
            lockSink()
            if (ttySkipped) {
                ttySkipped = false
                ttyPending = true
            }
            n as HostNotification.Console
            if (ttyPending) drainTtyBefore(n)?.let(::append)
            pendingConsoleBytes.addAndGet(-n.bytes.size.toLong())
            append(n.bytes)
        }

        /** たまった分を [host] に渡す（TTY の残りも取り出す） */
        fun flush() {
            try {
                ttySkipped = false
                if (ttyPending) {
                    ttyPending = false
                    // 書く先があれば、TTY の出力は書いたプロセスが自分のスレッドで書く。残ったままのものだけ取り出す（#355）
                    (if (locked == null) drainConsoleOutput() else drainStaleConsoleOutput())?.let(::append)
                }
                val b = bytes ?: return
                bytes = null
                val bytes = b.toByteArray()
                // 画面のデバイスをつないでいればそこへ（#65、ADR 0065）、なければ接続層へ
                val screen = devices.primaryScreen
                if (screen != null && screen.machine === this@Machine) safely { screen.write(bytes) } else safely { host.onConsoleOutput(this@Machine, bytes) }
            } finally {
                unlockSink()
            }
        }

        private fun append(b: ByteArray) {
            (bytes ?: ByteArrayOutputStream().also { bytes = it }).write(b, 0, b.size)
        }
    }

    // 前の通知で見た、コンソールの TTY の出力の位置（-1 = 見ていない）。メインスレッドだけで使う（#355）
    private var lastSeenTtyWritten = -1L

    // 次の tick の通知で、TTY の出力が残ったままか見直す
    private var recheckTtyOutput = false

    /**
     * 書く先（[consoleSink]）があるとき、コンソールの TTY に残ったままの出力を取り出す（#355）。
     * TTY の出力は、ふつうは書いたプロセスが [pumpConsole] で自分のスレッドで書くので、メインスレッドでは書かない。
     * 前の tick の通知から書き足されずに残っているもの（キー入力のエコー、プロセスが書けなかった分など）だけを取り出す。
     * まだ書き足されているか、TTY に書いたプロセスがまだ書いていない（[consolePumpsReserved]。書き手のスレッドが遅れているだけ。#419）
     * なら取り出さず、次の tick の通知で見直す
     */
    private fun drainStaleConsoleOutput(): ByteArray? = lock.withLockValue {
        val id = kernel.consoleTtyId
        val tty = (if (id == 0) null else kernel.objects[id] as? Tty) ?: return@withLockValue null
        when {
            tty.outputAvailable == 0 -> {
                lastSeenTtyWritten = -1
                null
            }
            consolePumpsReserved > 0 -> {
                lastSeenTtyWritten = -1
                recheckTtyOutput = true
                null
            }
            tty.outputWritten == lastSeenTtyWritten -> {
                lastSeenTtyWritten = -1
                tty.drainOutput(wakeSink)
            }
            else -> {
                lastSeenTtyWritten = tty.outputWritten
                recheckTtyOutput = true
                null
            }
        }
    }

    /**
     * カーネルのメッセージ [message] を書いた時点までにコンソールの TTY に書いた分を取り出す（書き手を起こす）。なければ `null`。
     * TTY が替わっていたら（状態を読み込んだなど）全部取り出す
     */
    private fun drainTtyBefore(message: HostNotification.Console): ByteArray? = lock.withLockValue {
        val id = kernel.consoleTtyId
        val tty = (if (id == 0) null else kernel.objects[id] as? Tty) ?: return@withLockValue null
        when {
            // 書いたときは TTY がなかった（それより前の TTY の出力はない）
            message.tty == null -> null
            message.tty === tty -> tty.drainOutput(wakeSink, message.ttyMark)
            else -> tty.drainOutput(wakeSink)
        }
    }

    /**
     * コンソールの出力を配るのを止める（#357）。メインスレッドから呼ぶ。止めている間は、TTY の出力を取り出さないので、
     * TTY がいっぱいになると書き手（プロセス）は待つ。カーネルのメッセージも、書いた順に取っておく。
     * 止めている間、毎 tick の通知の始めに [listener] の `resume` を呼ぶ。[releaseConsoleOutput] で、取っておいた分を
     * 次の通知で（`resume` の中で止めるのをやめたなら、その tick のうちに）書いた順に配る。
     * すでに止めていれば、知らせる先を [listener] に替える
     */
    internal fun holdConsoleOutput(listener: ConsoleHoldListener) {
        consoleHold = listener
        requestNotify()
    }

    /** [holdConsoleOutput] で止めたのをやめる。メインスレッドから呼ぶ。止めていなければ何もしない */
    internal fun releaseConsoleOutput() {
        if (consoleHold == null) return
        consoleHold = null
        if (deferredConsole != null) requestNotify()
    }

    /** コンソールの出力を止めているか（#357）。メインスレッドから呼ぶ */
    internal val consoleOutputHeld: Boolean get() = consoleHold != null

    private fun requestNotify() {
        if (queuedForNotify.compareAndSet(false, true)) scheduler.enqueueNotify(this)
    }

    private fun deferConsole(n: HostNotification) {
        (deferredConsole ?: ArrayList<HostNotification>().also { deferredConsole = it }).add(n)
    }

    /** 止めている間に取っておいた出力を、書いた順に [out] へ渡す */
    private fun resumeDeferredConsole(out: ConsoleCollector) {
        val deferred = deferredConsole ?: return
        deferredConsole = null
        for (n in deferred) out.add(n)
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            // 接続層の例外はほかのマシン・後の通知に伝えない
        }
    }

    // ---- メインスレッドでのホスト呼び出し（#44） ----

    /**
     * [call] を次の `tick()` の中でメインスレッドから実行する（1 tick に [MachineConfig.maxHostCallsPerTick] まで。
     * 残りは次の tick へ、出した順に）。プロセスのスレッドからは `ProcessContext.callOnMainThread` で使う。
     * マシンが止めている途中・止まった（CRASHED）とき、スケジューラが閉じているときは、[ProcessKilledException] で終えた future を返す。
     * 実行する前にマシンを止めたときも、同じ例外で終える（実行しない）。
     *
     * [pid] は出したプロセス（0 = プロセスでない。テスト・カーネル）。そのプロセスが止められている・終わっていれば、
     * 同じロックの中で確かめて積まずに断る。積んだ後にそのプロセスを止めたら、実行する前のものを取り消す（#46）
     */
    internal fun <T> submitHostCall(pid: Int, call: HostCall<T>): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        lock.lock()
        try {
            val stopped = pid != 0 && runtimes[pid].let { it == null || it.killed || it.process.state.isTerminal }
            if (stopped || stateValue == MachineState.STOPPING || stateValue == MachineState.CRASHED || scheduler.isClosed) {
                future.completeExceptionally(ProcessKilledException())
                return future
            }
            hostCalls.addLast(PendingHostCall(pid, call, future))
        } finally {
            lock.unlock()
        }
        if (queuedForHostCalls.compareAndSet(false, true)) scheduler.enqueueHostCalls(this)
        return future
    }

    /** `tick()` の中で、メインスレッドからホスト呼び出しを実行する（ロックの外）。まだ残っていれば `true` */
    internal fun runHostCalls(): Boolean {
        var ran = 0
        while (ran < config.maxHostCallsPerTick) {
            val pending = lock.withLockValue { hostCalls.removeFirstOrNull() } ?: break
            ran++
            pending.run()
        }
        return lock.withLockValue {
            val remains = hostCalls.isNotEmpty()
            // 空だと確かめたのと同じロックの中で印を下ろす（submitHostCall の追加と取り違えない）
            if (!remains) queuedForHostCalls.set(false)
            remains
        }
    }

    /** 実行を待つホスト呼び出しの数（テスト用） */
    internal val pendingHostCalls: Int get() = lock.withLockValue { hostCalls.size }

    private fun cancelHostCallsLocked() {
        while (true) {
            val pending = hostCalls.removeFirstOrNull() ?: break
            // future の後続は、待つプロセスのスレッドの get() か、待ちのオブジェクトの印（カーネルロックを取り直すだけ。#358）なので、ロックの中で終えてよい
            pending.future.completeExceptionally(ProcessKilledException())
        }
    }

    /** まだ実行していないホスト呼び出し [future] を取り消す（実行し始めたものはそのまま。#358）。カーネルロックの中で呼ぶ */
    internal fun cancelHostCallLocked(future: CompletableFuture<*>) {
        if (hostCalls.isEmpty()) return
        hostCalls.removeAll { pending ->
            (pending.future === future).also { if (it) pending.future.completeExceptionally(ProcessKilledException()) }
        }
    }

    /** プロセス [pid] が出して、まだ実行していないホスト呼び出しを取り消す（ほかのプロセスの分は順番を変えずに残す。#46） */
    internal fun cancelHostCallsLocked(pid: Int) {
        if (hostCalls.isEmpty()) return
        hostCalls.removeAll { pending ->
            (pending.pid == pid).also { if (it) pending.future.completeExceptionally(ProcessKilledException()) }
        }
    }

    public companion object {
        /** マシンを作る（[MachineScheduler.createMachine] から呼ぶ）。 */
        @JvmSynthetic
        internal fun create(
            scheduler: MachineScheduler,
            id: Long,
            host: MachineHost,
            config: MachineConfig,
            environment: MachineEnvironment,
        ): Machine = Machine(scheduler, id, host, config, environment)

        /** 保存まわりの警告を出す口（#82） */
        private val LOG: System.Logger = System.getLogger("kibicomputers.machine")

        /** 親のないゾンビを刈り取り始める、表の大きさの `maxProcesses` に対する倍率（仮の値。確定は #251） */
        private const val ORPHAN_ZOMBIE_TABLE_FACTOR: Int = 2

        /** 既定の TTY のシグナルの受け口: 前のプロセスに送る（結果は捨てる） */
        private val DEFAULT_TTY_SIGNAL_HANDLER = TtySignalHandler { machine, signal, foregroundPid ->
            if (foregroundPid != 0) {
                val kill = when (signal) {
                    TtySignal.INTERRUPT -> KillSignal.INTERRUPT
                    TtySignal.QUIT -> KillSignal.QUIT
                }
                machine.killLocked(foregroundPid, kill)
            }
        }

        /** 起動の始めに、前のスレッドを待つときの見直しの間隔 */
        private const val BOOT_WAIT_NANOS: Long = 10_000_000

        /** コンソールのメッセージに出すプロセスの名前の最大の文字数 */
        private const val MAX_LABEL_CHARS: Int = 64

        private const val LF_BYTE: Byte = 0x0A
        private const val CR_BYTE: Byte = 0x0D
    }
}

/** マシンの内部の上限など（公開 API の外） */
internal object MachineLimits {
    /** 配っていないコンソール出力の上限（バイト。仮の値） */
    internal const val MAX_PENDING_CONSOLE_BYTES: Long = 64L * 1024

    /** 実行ファイルの大きさの上限（バイト。仮の値。確定は #251） */
    internal const val MAX_EXECUTABLE_BYTES: Int = ModuleLimits.DEFAULT_MAX_BYTES

    /** コンソールの TTY の入力の上限（読める入力＋編集中の行。仮の値。確定は #251） */
    internal const val CONSOLE_INPUT_BYTES: Int = 4 * 1024

    /** コンソールの TTY の出力のバッファ。1 tick に配る量の上限を兼ね、超えると書き手が待つ（仮の値。確定は #251） */
    internal const val CONSOLE_OUTPUT_BYTES: Int = 16 * 1024

    /** 表示デバイスに書くときに、触ったセル1つあたりにプロセスが払う燃料（#355。仮の値。確定は #251） */
    internal const val CONSOLE_FUEL_PER_CELL: Long = 1

    /** 起動ディスクのマウントの `source`（`disk:<識別子>`） */
    internal const val DISK_SOURCE_PREFIX: String = "disk:"

    /** `/tmp`（メモリ上のファイルシステム。#53）のマウントの `source` */
    internal const val TMP_SOURCE: String = "tmp"

    /** `/dev`（デバイスファイル。#485）のマウントの `source` */
    internal const val DEV_SOURCE: String = "dev"

    /** `/dev` をマウントする場所 */
    internal val DEV_PATH: VfsPath = VfsPath.parse("/dev")

    /** `/tmp` をマウントする場所 */
    internal val TMP_PATH: VfsPath = VfsPath.parse("/tmp")

    /** 根が読み取り専用のときに [BootConfig.homeDisk] をマウントする場所（#461） */
    internal val HOME_PATH: VfsPath = VfsPath.parse("/home")

    /** ホストのディレクトリのディスク（#52）1つの容量（バイト。仮の値。ディスクの種類ごとの値は #124、確定は #251） */
    internal const val DISK_CAPACITY_BYTES: Long = 2L * 1024 * 1024

    /** ディスク1つのファイル・ディレクトリの数の上限（根を除く。仮の値。確定は #251） */
    internal const val DISK_MAX_ENTRIES: Int = 4096

    /** マシン1台が開いたままにできるホストのファイルの数（#52。CC: Tweaked の既定と同じ。仮の値。確定は #251） */
    internal const val HOST_FILES_PER_MACHINE: Int = 128

    /** サーバー全体で開いたままにできるホストのファイルの数（#52。仮の値。確定は #251） */
    internal const val HOST_FILES_TOTAL: Int = 2048
}

/**
 * コンソールの TTY のシグナル（Ctrl+C・Ctrl+\）の受け口（#60）。[Machine.ttySignalHandler] に入れる。
 * カーネルロックの中で、キー入力を渡したスレッドから呼ばれる
 */
internal fun interface TtySignalHandler {
    /** [signal] が起きた。[foregroundPid] は TTY の前にいるプロセス（0 = なし。終わっていることもある） */
    fun onSignal(machine: Machine, signal: TtySignal, foregroundPid: Int)
}

/** メインスレッドで実行する処理（ワールド操作、デバイスの呼び出しなど）。[Machine.submitHostCall] に渡す */
internal fun interface HostCall<T> {
    fun run(): T
}

private class PendingHostCall<T>(
    /** 出したプロセス（0 = プロセスでない） */
    val pid: Int,
    val call: HostCall<T>,
    val future: CompletableFuture<T>,
) {
    fun run() {
        if (future.isDone) return
        try {
            future.complete(call.run())
        } catch (t: Throwable) {
            future.completeExceptionally(t)
            // JVM が続けられないエラーは、待ち手を終えてからそのまま投げる（`tick()` へ。デバイスの直接のメソッドと同じ。#532）
            if (isFatal(t)) throw t
        }
    }
}

/**
 * コンソールの出力を書く先（表示デバイス。#355、[Machine.consoleSink]）。
 * ロックの順は「[lock] → カーネルロック」。カーネルロックを持ったまま [lock] を取らない
 */
internal interface ConsoleSink {
    /** 書く先の状態を守るロック */
    val lock: ReentrantLock

    /** [lock] を持って呼ぶ。[bytes]（TTY を通ったバイト列）を端末として書き、触ったセルの数を返す */
    fun write(bytes: ByteArray): Long
}

/** コンソールの出力を止めている相手（#357。[Machine.holdConsoleOutput]） */
internal fun interface ConsoleHoldListener {
    /** 止めている間、毎 tick の通知の始めにメインスレッドから呼ぶ。追いついたら中で [Machine.releaseConsoleOutput] を呼んでよい */
    fun resume()
}

/** マシンごとの通知の列の要素 */
private sealed class HostNotification {
    class StateChanged(val state: MachineState, val cause: Throwable?) : HostNotification()

    /**
     * カーネルのメッセージ。[tty] は書いたときのコンソールの TTY（なければ `null`）、[ttyMark] はそのときの
     * [Tty.outputWritten]（TTY の出力との前後の目印。#381）
     */
    class Console(val bytes: ByteArray, val tty: Tty?, val ttyMark: Long) : HostNotification()

    /** コンソールの TTY に出力がたまった印（取り出すのは配るとき。列の中の位置が、書いた順の目印になる） */
    data object TtyOutput : HostNotification()

    /** [Machine.postHostCallback] で積んだ処理 */
    class Callback(val callback: Runnable) : HostNotification()

    class BootFailed(val failure: BootFailure, val detail: String) : HostNotification()

    data object RebootRequested : HostNotification()
}

private inline fun <T> ReentrantLock.withLockValue(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
