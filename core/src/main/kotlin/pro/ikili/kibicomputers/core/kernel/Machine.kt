package pro.ikili.kibicomputers.core.kernel

import pro.ikili.kibicomputers.core.kernel.boot.BootCancelledException
import pro.ikili.kibicomputers.core.kernel.boot.BootSequence
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.fd.STDERR
import pro.ikili.kibicomputers.core.kernel.fd.STDIN
import pro.ikili.kibicomputers.core.kernel.fd.STDOUT
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.obj.HandleEntry
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.DEFAULT_HANDLE_LIMIT
import pro.ikili.kibicomputers.core.kernel.process.Process
import pro.ikili.kibicomputers.core.kernel.process.ProcessArgs
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriver
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriverFactory
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessRunner
import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.sched.KernelThread
import pro.ikili.kibicomputers.core.kernel.sched.MachineCpu
import pro.ikili.kibicomputers.core.kernel.tty.Tty
import pro.ikili.kibicomputers.core.kernel.tty.TtySignal
import pro.ikili.kibicomputers.core.kernel.tty.TtySize
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FileType
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.Vfs
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.vfs.fsError
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.wait.WaitRegistry
import pro.ikili.kibicomputers.core.kernel.wait.WakeSink
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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

/**
 * マシンの設定。[builder] で作る。値はどれも仮の値（確定は #47・#251）。
 */
public class MachineConfig private constructor(builder: Builder) {
    /** 1 tick に配る燃料。1回に渡す燃料の上限でもある（仮: 1 tick 約 2 ms 相当） */
    public val fuelPerTick: Long = builder.fuelPerTick

    /** 終わっていないプロセスの数の上限（仮: 64） */
    public val maxProcesses: Int = builder.maxProcesses

    /** 1 tick にメインスレッドで実行するホスト呼び出し（ワールド操作など）の数の上限（仮: 64）。残りは次の tick へ */
    public val maxHostCallsPerTick: Int = builder.maxHostCallsPerTick

    /**
     * コンピューター全体の RAM（バイト。#39）。全プロセスの線形メモリの合計の上限で、64 KiB（1 ページ）単位に切り捨てて使う。
     * 仮: 32 MiB（RAM のアイテムで変わる。#123。wasi-libc の既定のスタックが 16 MiB なので、それより大きくしている）
     */
    public val memoryBytes: Long = builder.memoryBytes

    /**
     * 1 プロセスの線形メモリの合計の上限（バイト。#39）。64 KiB 単位に切り捨てて使う。
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
        it.monotonicOffsetNanos = -environment.clock.monotonicNanos()
    }

    /** 燃料の遅延補充 */
    internal val fuelAccount = FuelAccount(kernel, config.fuelPerTick, scheduler.now)

    /** 線形メモリの使用量（RAM。#39） */
    internal val memoryAccount = MemoryAccount(config.memoryPages)

    /** 仮想ファイルシステム（マウントの表は [kernel] の中。ファイルシステムは実行時だけ。カーネルロックの中で使う） */
    internal val vfs = Vfs(kernel.mounts)

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
    }

    /** コンソールの TTY のシグナル（Ctrl+C など）の受け口。既定は何もしない（強制終了につなぐのは #46） */
    @Volatile
    internal var ttySignalHandler: TtySignalHandler = TtySignalHandler { _, _, _ -> }

    /** コンソールの出力に、まだ配っていないバイトがあるかもしれない */
    private val consoleDirty = AtomicBoolean(false)

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

    private val notifications = ConcurrentLinkedQueue<HostNotification>()
    private val queuedForNotify = AtomicBoolean(false)

    /** 配っていないコンソール出力のバイト数と、ビープの数（上限を超えた分は捨てる） */
    private val pendingConsoleBytes = AtomicLong()
    private val pendingBeeps = AtomicInteger()

    /** 捨てたコンソール出力のバイト数・ビープの数（数え口） */
    internal val droppedConsoleBytes = AtomicLong()
    internal val droppedBeeps = AtomicLong()

    /** メインスレッドで実行するのを待つホスト呼び出し（カーネルロック） */
    private val hostCalls = ArrayDeque<PendingHostCall<*>>()
    private val queuedForHostCalls = AtomicBoolean(false)

    /** つながっているデバイスが変わった回数（実行時だけ。#63・#65 がイベントにする） */
    private val devicesGenerationCounter = AtomicLong()

    // ---- 起動（#49）。どれも実行時だけで、保存しない（カーネルロック） ----

    /** 起動の手順が動いている（受け付けてから init を作るか、失敗・取り消しで終わるまで） */
    @Volatile
    private var bootingValue = false

    /** 起動の途中で `stop()`・`reboot()` された */
    @Volatile
    private var bootCancelled = false

    /** 起動した init の pid（0 = いない）。init が終わったら 0 に戻す */
    private var initPid = 0

    @Volatile
    private var lastBootConfigValue: BootConfig? = null

    /** 再起動を求められ、プロセスがすべて止まるのを待っている */
    private var rebootRequested = false

    /** 再起動の求めを通知の列に積み、まだ配っていない（`stop()` で取り消す） */
    private var rebootNotified = false

    /** 前の起動のプロセスの専用スレッドで、まだ終わっていないかもしれないもの（[awaitTermination] で待つ） */
    private val retiredThreads = ArrayList<Thread>()

    /** 状態 */
    public val state: MachineState get() = stateValue

    /** 全プロセスが待機点にいる（または1つもない）なら `true`（#81 の判定の土台） */
    public val isAtWaitPoint: Boolean
        get() = lock.withLockValue { kernel.processes.all().all { isAtWaitPointLocked(it) } }

    /** 起動の手順が動いている（[boot] を受け付けてから、init を作るか、失敗・取り消しで終わるまで） */
    public val isBooting: Boolean get() = bootingValue

    /** 最後に受け付けた [boot] の設定（[reboot] と、[MachineHost.onRebootRequested] の既定の実装が使う）。まだなければ `null` */
    public val lastBootConfig: BootConfig? get() = lastBootConfigValue

    /**
     * 起動する（#49）。どのスレッドから呼んでもよい。待たずに返す（ディスクを探し、`/sbin/init` を読み込むのはワーカーのスレッド）。
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
            bootingValue = true
            bootCancelled = false
            rebootRequested = false
            rebootNotified = false
            lastBootConfigValue = config
        } finally {
            lock.unlock()
        }
        scheduler.workers.execute { BootSequence(this, config).run() }
        return true
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

    /**
     * つながっているデバイスが変わった（つながった・外れた）ことを知らせる（接続層 → カーネル）。
     * どのスレッドから呼んでもよい。待たずに返す。何が変わったかは、カーネルがホスト呼び出しで接続層に尋ねる
     * （デバイスの識別子と呼び出し方は #65。イベントとしてプロセスに届けるのは #63）
     */
    public fun notifyDevicesChanged() {
        devicesGenerationCounter.incrementAndGet()
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
     * 引数・環境変数・作業ディレクトリ・fd は [options] で決める（[SpawnOptions]）。どれも表を変える前に確かめ、
     * 失敗したら何も変えない。
     *
     * @param parentPid 0 は親なし（init）。0 以外は、表にある、終わっていないプロセスの pid でなければ [IllegalArgumentException]
     * @param asInit 起動の手順（#49）が init を作る。起動が取り消されていれば [BootCancelledException]。作れたら init として覚え、起動の手順を終える
     * @return 割り当てた pid
     * @throws IllegalArgumentException 燃料を計測しないモジュールで `allowUnmetered` が `false`、または親がない・終わっている
     * @throws FsException 引数・環境変数が正しくない（`INVAL`）・大きすぎる（`TOOBIG`）、作業ディレクトリがない（`NOENT`）・
     *   ディレクトリでない（`NOTDIR`）、渡す fd が開いていない・範囲外（`BADF`）
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
    ): Int {
        lock.lock()
        try {
            // 起動の init: 取り消されていれば作らない（stop() と取り違えないよう、表を変えるのと同じロックの中で確かめる）
            if (asInit && (!bootingValue || bootCancelled)) throw BootCancelledException()
            check(!scheduler.isClosed) { "スケジューラは閉じている" }
            check(stateValue != MachineState.STOPPING && stateValue != MachineState.CRASHED) { "マシンは $stateValue" }
            val metered = program.module.isFuelMetered
            require(metered || options.allowUnmetered) { "燃料を計測しないモジュールはプロセスにできない" }
            val parent = parentLocked(parentPid)
            if (kernel.processes.liveCount() >= config.maxProcesses) throw ResourceLimitException("プロセスの数が上限（${config.maxProcesses}）")
            val env = options.env ?: parent?.env ?: emptyMap()
            ProcessArgs.check(options.args, env)
            val cwd = resolveCwdLocked(options.cwd, parent)
            val fdEntries = resolveFdsLocked(options, parent)
            val kind = driver.kind
            if (kind == ProcessKind.BLOCKING && !scheduler.tryReserveBlocking()) {
                throw ResourceLimitException("ブロッキング型のプロセスの数が上限（${scheduler.config.maxBlockingProcesses}）")
            }
            val pid = kernel.processes.allocatePid()
            val process = Process(pid, parentPid, kind, program.hash, !metered, ArrayList(options.args), LinkedHashMap(env), cwd)
            kernel.processes.add(process)
            if (asInit) {
                initPid = pid
                bootingValue = false
            }
            val fds = FdTable(process, kernel.objects, wakeSink)
            for ((fd, entry) in fdEntries) fds.installAt(fd, entry)
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

    /**
     * VFS の実行ファイル [path] を読み込んで、プロセスを作る（#45）。ドライバーは [drivers] が、読み込んだ [Program] から作る。
     *
     * - [path] の相対パスは、親の作業ディレクトリから辿る（親がなければ `/`）。`PATH` からは探さない（シェルの役目）
     * - 普通のファイルでなければ `ACCES`、[MachineLimits.MAX_EXECUTABLE_BYTES] を超えたら `FBIG`、WASM として読めなければ `NOEXEC`
     * - 読み込むときに燃料の計測コードを挿入する（#38）。計測の名前（`kibi.fuel`・`kibi_fuel`）をすでに使っている WASM も `NOEXEC`
     * - `options.args` が空なら、argv を [path] の文字列1つにする
     * - ファイルを読むのはカーネルロックの中、WASM の読み込み（挿入・パース・検証）はロックの外。重いので、メインスレッドからは呼ばない
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
    ): Int {
        check(!lock.isHeldByCurrentThread) { "カーネルロックを持ったまま exec を呼ばない" }
        val bytes = lock.withLockValue { readExecutableLocked(path, parentLocked(parentPid)) }
        val program = try {
            val runtime = scheduler.options.runtime
            if (scheduler.options.meterExecutables) Program.loadMetered(runtime, bytes) else Program.load(runtime, bytes)
        } catch (e: WasmLoadException) {
            fsError(Errno.NOEXEC, "$path は WASM として読めない: ${e.message}")
        }
        val driver = drivers.create(program)
        val argv = options.args.ifEmpty { listOf(path) }
        return spawn(program, driver, options.copy(args = argv), parentPid, asInit)
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

    /** 作業ディレクトリを決める。指定がなければ親と同じ（親がなければ `/`。確かめない）。指定があれば、ディレクトリか確かめる */
    private fun resolveCwdLocked(text: String?, parent: Process?): VfsPath {
        val base = parent?.cwd ?: VfsPath.ROOT
        if (text == null) return base
        val path = VfsPath.resolve(base, text)
        if (vfs.stat(path).type != FileType.DIRECTORY) fsError(Errno.NOTDIR, "$path はディレクトリではない")
        return path
    }

    /** 実行ファイルの中身を読む（カーネルロックの中） */
    private fun readExecutableLocked(text: String, parent: Process?): ByteArray {
        val path = VfsPath.resolve(parent?.cwd ?: VfsPath.ROOT, text)
        val stat = vfs.stat(path)
        if (stat.type != FileType.REGULAR_FILE) fsError(Errno.ACCES, "$path は普通のファイルではない")
        if (stat.size > MachineLimits.MAX_EXECUTABLE_BYTES) fsError(Errno.FBIG, "$path は大きすぎる（${stat.size} バイト。上限 ${MachineLimits.MAX_EXECUTABLE_BYTES}）")
        val file = vfs.open(path, OpenOptions(read = true), VfsPath.hasTrailingSlash(text))
        val out = ByteArrayOutputStream(stat.size.toInt())
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = vfs.read(file, buf, 0, buf.size)
            if (n <= 0) break
            out.write(buf, 0, n)
            if (out.size() > MachineLimits.MAX_EXECUTABLE_BYTES) fsError(Errno.FBIG, "$path は大きすぎる（上限 ${MachineLimits.MAX_EXECUTABLE_BYTES}）")
        }
        return out.toByteArray()
    }

    /**
     * fd 0/1/2（[SpawnOptions.stdio]）と fd 3 以降（[SpawnOptions.fds]）の割り当てを、入れるエントリにする（参照はまだ増やさない）。
     * 表は変えない（コンソールは作ることがある）。親の fd が開いていない・範囲外なら `BADF`、オブジェクトがなければ [IllegalArgumentException]
     */
    private fun resolveFdsLocked(options: SpawnOptions, parent: Process?): Map<Int, HandleEntry> {
        val out = LinkedHashMap<Int, HandleEntry>()
        for (fd in listOf(STDIN, STDOUT, STDERR)) resolveFdLocked(fd, options.stdio.source(fd), parent)?.let { out[fd] = it }
        for ((fd, source) in options.fds.entries.sortedBy { it.key }) {
            if (fd <= STDERR || fd >= DEFAULT_HANDLE_LIMIT) fsError(Errno.BADF, "fd $fd は渡せない（3 以上 $DEFAULT_HANDLE_LIMIT 未満）")
            resolveFdLocked(fd, source, parent)?.let { out[fd] = it }
        }
        return out
    }

    /** fd [fd] の割り当て1つ。割り当てないなら `null` */
    private fun resolveFdLocked(fd: Int, source: StdioSource, parent: Process?): HandleEntry? = when (source) {
        StdioSource.Inherit -> when {
            parent != null -> parent.handles[fd]
            fd <= STDERR -> consoleEntryLocked(fd)
            else -> null
        }
        is StdioSource.ParentFd -> {
            val p = requireNotNull(parent) { "親のないプロセスに親の fd は渡せない" }
            p.handles[source.fd] ?: fsError(Errno.BADF, "親の fd ${source.fd} は開いていない")
        }
        is StdioSource.Object -> {
            require(kernel.objects[source.objectId] != null) { "オブジェクト ${source.objectId} がない" }
            HandleEntry(source.objectId, rights = source.rights)
        }
        StdioSource.Console -> consoleEntryLocked(fd)
        StdioSource.Closed -> null
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
        val tty = consoleTtyLocked()
        val r = tty.input(bytes, offset, length, wakeSink)
        if (r.signals.isNotEmpty()) {
            val handler = ttySignalHandler
            for (s in r.signals) handler.onSignal(this, s, tty.foregroundPid)
        }
        length - r.dropped
    }

    /** コンソールの画面の大きさを変える（画面のデバイスから。#65・#66）。どのスレッドから呼んでもよい。1 未満は [IllegalArgumentException] */
    internal fun resizeConsole(columns: Int, rows: Int) {
        val size = TtySize(columns, rows)
        lock.withLockValue { consoleTtyLocked().resize(size) }
    }

    /** コンソールの画面の大きさ */
    internal val consoleSize: TtySize get() = lock.withLockValue { consoleTtyLocked().size }

    /** コンソールの画面の大きさが変わった回数 */
    internal val consoleSizeGeneration: Long get() = lock.withLockValue { consoleTtyLocked().sizeGeneration }

    private fun markConsoleDirty() {
        // 通知の列に印を積んで、TTY の出力の順番を、ほかの通知（カーネルのメッセージなど）との前後で決める
        if (consoleDirty.compareAndSet(false, true)) postNotification(HostNotification.TtyOutput)
    }

    /** コンソールの TTY にたまった出力を取り出す（書き手を起こす）。なければ `null` */
    private fun drainConsoleOutput(): ByteArray? = lock.withLockValue {
        val id = kernel.consoleTtyId
        val tty = (if (id == 0) null else kernel.objects[id] as? Tty) ?: return@withLockValue null
        tty.drainOutput(wakeSink)
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
        if (bootingValue) bootCancelled = true
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
        cancelHostCallsLocked()
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
     * @param crashReason `CRASHED` の理由（[crashReason][pro.ikili.kibicomputers.core.kernel.process.crashReason]）。それ以外は `null`
     */
    internal fun onProcessFinishedLocked(process: Process, crashReason: String?) {
        val isInit = initPid != 0 && process.pid == initPid && process.parentPid == 0
        if (isInit) initPid = 0
        // STOPPING（stop()・reboot()）と CRASHED（カーネルの不整合）では何も言わない
        if (stateValue != MachineState.RUNNING) return
        if (!isInit) {
            if (process.state == ProcessState.CRASHED) writeConsoleLine("kibi: ${processLabel(process)} crashed: ${crashReason ?: "unknown"}")
            return
        }
        val message = when (process.state) {
            ProcessState.EXITED -> "kibi: init exited with code ${process.exitCode}"
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
     * 起動の手順の始め（ワーカーのスレッドから）。前の起動のスレッドが登録を外すのを待ち、ゾンビを外して pid を 1 に戻し、
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
        kernel.processes.reset()
        initPid = 0
        unmountAllLocked()
        if (kernel.consoleTtyId != 0) consoleTtyLocked().foregroundPid = 0
        true
    }

    /** 起動ディスク [diskId] の [volume] を `/` にマウントする（ワーカーのスレッドから）。取り消されていれば何もせず `false` */
    internal fun mountBootRoot(diskId: String, volume: BootVolume): Boolean = lock.withLockValue {
        if (bootCancelled) return@withLockValue false
        vfs.mount(VfsPath.ROOT, volume.fs, volume.readOnly, "${MachineLimits.DISK_SOURCE_PREFIX}$diskId")
        true
    }

    /**
     * 起動の手順を、init を作らずに終える（ワーカーのスレッドから）。マウントを外す。
     * [failure] が `null`（取り消し）か、取り消されていれば何も知らせない。そうでなければコンソールに書いて [MachineHost.onBootFailed] で知らせる
     *
     * @param reason コンソールのメッセージに入れる短い英語（errno の名前など）
     * @param detail [MachineHost.onBootFailed] に渡す理由（`null` なら [reason]）
     */
    internal fun endBoot(failure: BootFailure?, reason: String, detail: String? = null): Unit = lock.withLockValue {
        if (!bootingValue) return@withLockValue
        bootingValue = false
        if (kernel.processes.liveCount() == 0) unmountAllLocked()
        if (failure != null && !bootCancelled) {
            writeConsoleLine(BootSequence.consoleMessage(failure, reason))
            postNotification(HostNotification.BootFailed(failure, detail ?: reason))
        }
        bootCancelled = false
        maybePostRebootLocked()
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

    /** つながっているデバイスが変わった回数 */
    internal val devicesGeneration: Long get() = devicesGenerationCounter.get()

    /**
     * コンソールへ出力する（次の `tick()` で [MachineHost.onConsoleOutput] に渡す）。どのスレッドから呼んでもよい。
     * TTY を通らないカーネルのメッセージ用（TTY の桁の数えには入らない）。TTY の出力とそろえて、LF を CR LF にする（#60）。
     * 配っていない分（CR LF にした後のバイト数）が [MachineLimits.MAX_PENDING_CONSOLE_BYTES] を超えるなら、超える分を捨てて `false`。
     * 書き手を待たせる流量の制御は、ABI 層が TTY（fd 1・2）で行う（#58・#60）
     */
    internal fun writeConsole(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Boolean {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "範囲が配列の外" }
        if (length == 0) return true
        return postConsole(nlToCrNl(bytes, offset, length))
    }

    /** LF を CR LF にした [bytes] を通知の列に積む（上限を超える分は捨てる） */
    private fun postConsole(bytes: ByteArray): Boolean {
        val length = bytes.size
        var accepted: Int
        while (true) {
            val pending = pendingConsoleBytes.get()
            accepted = (MachineLimits.MAX_PENDING_CONSOLE_BYTES - pending).coerceIn(0, length.toLong()).toInt()
            if (accepted == 0 || pendingConsoleBytes.compareAndSet(pending, pending + accepted)) break
        }
        if (accepted < length) droppedConsoleBytes.addAndGet((length - accepted).toLong())
        if (accepted > 0) postNotification(HostNotification.Console(if (accepted == length) bytes else bytes.copyOf(accepted)))
        return accepted == length
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
     * ビープを鳴らす（次の `tick()` で [MachineHost.onBeep] に渡す）。値は範囲に丸める。どのスレッドから呼んでもよい。
     * 配っていないビープが [MachineLimits.MAX_PENDING_BEEPS] あるなら捨てて `false`
     */
    internal fun beep(frequencyHz: Int, durationMillis: Int): Boolean {
        while (true) {
            val n = pendingBeeps.get()
            if (n >= MachineLimits.MAX_PENDING_BEEPS) {
                droppedBeeps.incrementAndGet()
                return false
            }
            if (pendingBeeps.compareAndSet(n, n + 1)) break
        }
        postNotification(
            HostNotification.Beep(
                frequencyHz.coerceIn(MIN_BEEP_FREQUENCY_HZ, MAX_BEEP_FREQUENCY_HZ),
                durationMillis.coerceIn(1, MAX_BEEP_DURATION_MILLIS),
            ),
        )
        return true
    }

    /**
     * `tick()` の中で、メインスレッドから通知を配る（ロックの外）。続いたコンソール出力は1回にまとめる。
     *
     * TTY の出力は、たまった最初のときに通知の列へ印（[HostNotification.TtyOutput]）を積む。印のところで TTY から取り出して、
     * 前後の通知と書いた順に並べる（起動のログ → init の出力 → init の終了のメッセージ → RUNNING など）
     */
    internal fun deliverNotifications() {
        queuedForNotify.set(false)
        var console: ByteArrayOutputStream? = null
        fun appendConsole(bytes: ByteArray) {
            (console ?: ByteArrayOutputStream().also { console = it }).write(bytes, 0, bytes.size)
        }
        while (true) {
            val n = notifications.poll() ?: break
            if (n is HostNotification.Console) {
                pendingConsoleBytes.addAndGet(-n.bytes.size.toLong())
                appendConsole(n.bytes)
                continue
            }
            if (n === HostNotification.TtyOutput) {
                // 印を下ろしてから取り出す（取り出した後の出力は、新しい印で次に配る）
                consoleDirty.set(false)
                drainConsoleOutput()?.let(::appendConsole)
                continue
            }
            console?.let { out ->
                console = null
                safely { host.onConsoleOutput(this, out.toByteArray()) }
            }
            when (n) {
                is HostNotification.StateChanged -> safely { host.onStateChanged(this, n.state, n.cause) }
                is HostNotification.Beep -> {
                    pendingBeeps.decrementAndGet()
                    safely { host.onBeep(this, n.frequencyHz, n.durationMillis) }
                }
                is HostNotification.BootFailed -> safely { host.onBootFailed(this, n.failure, n.detail) }
                HostNotification.RebootRequested -> {
                    // 積んだ後に stop() されたら、再起動しない
                    val go = lock.withLockValue { rebootNotified.also { rebootNotified = false } }
                    if (go) safely { host.onRebootRequested(this) }
                }
                is HostNotification.Console, HostNotification.TtyOutput -> Unit
            }
        }
        console?.let { out -> safely { host.onConsoleOutput(this, out.toByteArray()) } }
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
     * 実行する前にマシンを止めたときも、同じ例外で終える（実行しない）
     */
    internal fun <T> submitHostCall(call: HostCall<T>): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        lock.lock()
        try {
            if (stateValue == MachineState.STOPPING || stateValue == MachineState.CRASHED || scheduler.isClosed) {
                future.completeExceptionally(ProcessKilledException())
                return future
            }
            hostCalls.addLast(PendingHostCall(call, future))
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
            // future に後続の処理はつながっていない（待つのはプロセスのスレッドの get() だけ）ので、ロックの中で終えてよい
            pending.future.completeExceptionally(ProcessKilledException())
        }
    }

    public companion object {
        /** ビープの周波数の下限（Hz） */
        public const val MIN_BEEP_FREQUENCY_HZ: Int = 20

        /** ビープの周波数の上限（Hz） */
        public const val MAX_BEEP_FREQUENCY_HZ: Int = 20_000

        /** ビープの長さの上限（ミリ秒。仮の値） */
        public const val MAX_BEEP_DURATION_MILLIS: Int = 5_000

        /** マシンを作る（[MachineScheduler.createMachine] から呼ぶ）。 */
        @JvmSynthetic
        internal fun create(
            scheduler: MachineScheduler,
            id: Long,
            host: MachineHost,
            config: MachineConfig,
            environment: MachineEnvironment,
        ): Machine = Machine(scheduler, id, host, config, environment)

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

    /** 配っていないビープの上限（仮の値） */
    internal const val MAX_PENDING_BEEPS: Int = 8

    /** 実行ファイルの大きさの上限（バイト。仮の値。確定は #251） */
    internal const val MAX_EXECUTABLE_BYTES: Int = 16 * 1024 * 1024

    /** コンソールの TTY の入力の上限（読める入力＋編集中の行。仮の値。確定は #251） */
    internal const val CONSOLE_INPUT_BYTES: Int = 4 * 1024

    /** コンソールの TTY の出力のバッファ。1 tick に配る量の上限を兼ね、超えると書き手が待つ（仮の値。確定は #251） */
    internal const val CONSOLE_OUTPUT_BYTES: Int = 16 * 1024

    /** 起動ディスクのマウントの `source`（`disk:<識別子>`） */
    internal const val DISK_SOURCE_PREFIX: String = "disk:"
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

private class PendingHostCall<T>(val call: HostCall<T>, val future: CompletableFuture<T>) {
    fun run() {
        if (future.isDone) return
        try {
            future.complete(call.run())
        } catch (t: Throwable) {
            future.completeExceptionally(t)
        }
    }
}

/** マシンごとの通知の列の要素 */
private sealed class HostNotification {
    class StateChanged(val state: MachineState, val cause: Throwable?) : HostNotification()

    class Console(val bytes: ByteArray) : HostNotification()

    /** コンソールの TTY に出力がたまった印（取り出すのは配るとき。列の中の位置が、書いた順の目印になる） */
    data object TtyOutput : HostNotification()

    class Beep(val frequencyHz: Int, val durationMillis: Int) : HostNotification()

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
