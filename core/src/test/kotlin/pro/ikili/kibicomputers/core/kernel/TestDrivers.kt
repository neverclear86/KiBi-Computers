package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.fail
import pro.ikili.kibicomputers.core.kernel.process.KernelHostFunction
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriver
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SnapshotCapableDriver
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.persist.ModuleStore
import pro.ikili.kibicomputers.core.persist.SnapshotSection
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.time.TimerService
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.runtime.FakeModule
import pro.ikili.kibicomputers.core.runtime.FakeWasmRuntime
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.StateCaptureMode
import pro.ikili.kibicomputers.core.runtime.WasmRuntime
import pro.ikili.kibicomputers.core.runtime.validation.ModuleLimits
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.withLock

/**
 * テスト用の [ProcessDriver]。振る舞いを関数で渡す。保存に対応した（[SnapshotCapableDriver]）ものとして扱い、層の中身は
 * [abiPayload]（層は [SnapshotSection.ABI_LAYER_TEST]。読む側は拒む）
 */
internal class TestDriver(
    override val kind: ProcessKind,
    private val onConfigure: (ProcessContext, WasmInstanceConfig.Builder) -> Unit = { _, _ -> },
    private val onResume: (ProcessContext, List<WaitKey>) -> StepResult = { _, _ -> fail("resume は呼ばれないはず") },
    private val onStart: (ProcessContext) -> StepResult,
) : SnapshotCapableDriver {
    val configureCalls = AtomicInteger()

    /** 層の中身（`null` なら保存できないものとして写しの中で殺される） */
    @Volatile
    var abiPayload: ByteArray? = ByteArray(0)

    override val abiLayer: Int get() = SnapshotSection.ABI_LAYER_TEST

    override val abiLayerVersion: Int get() = 1

    override fun captureAbi(rt: ProcessRuntime): ByteArray? = abiPayload

    /** [captureAbi] に来たインスタンスの状態の取り方（#83） */
    val modes: MutableList<StateCaptureMode> = java.util.concurrent.CopyOnWriteArrayList()

    override fun captureAbi(rt: ProcessRuntime, mode: StateCaptureMode): ByteArray? {
        modes += mode
        return abiPayload
    }

    override fun configure(ctx: ProcessContext, builder: WasmInstanceConfig.Builder) {
        configureCalls.incrementAndGet()
        onConfigure(ctx, builder)
    }

    override fun start(ctx: ProcessContext): StepResult = onStart(ctx)

    override fun resume(ctx: ProcessContext, ready: List<WaitKey>): StepResult {
        assertTrue(ready.isNotEmpty(), "resume に空の並びが来た")
        return onResume(ctx, ready)
    }
}

/** 保存に対応していないドライバー（#82。静的な判定で UNSAVEABLE になる）。振る舞いは [inner] に任せる */
internal class UnsaveableTestDriver(private val inner: TestDriver) : ProcessDriver {
    override val kind: ProcessKind get() = inner.kind

    override fun configure(ctx: ProcessContext, builder: WasmInstanceConfig.Builder) = inner.configure(ctx, builder)

    override fun start(ctx: ProcessContext): StepResult = inner.start(ctx)

    override fun resume(ctx: ProcessContext, ready: List<WaitKey>): StepResult = inner.resume(ctx, ready)
}

/** メモリ上のモジュールの置き場（#82。テスト用） */
internal class MemoryModuleStore : ModuleStore {
    private val modules = ConcurrentHashMap<String, ByteArray>()

    /** [put] が呼ばれた数 */
    val puts = AtomicInteger()

    /** [get] が呼ばれた数 */
    val gets = AtomicInteger()

    override fun put(sha256: ByteArray, bytes: ByteArray): Boolean {
        puts.incrementAndGet()
        require(ModuleStore.sha256(bytes).contentEquals(sha256)) { "ハッシュが合わない" }
        modules[ModuleStore.hex(sha256)] = bytes.copyOf()
        return true
    }

    override fun contains(sha256: ByteArray): Boolean = sha256.size == 32 && modules.containsKey(ModuleStore.hex(sha256))

    override fun get(sha256: ByteArray): ByteArray? {
        gets.incrementAndGet()
        return modules[ModuleStore.hex(sha256)]?.copyOf()
    }

    /** [sha256] のモジュールを消す（「ストアにない」を作る） */
    fun remove(sha256: ByteArray) {
        modules.remove(ModuleStore.hex(sha256))
    }
}

/** ホスト関数の型 `() -> ()` */
internal val VOID: WasmFunctionType = WasmFunctionType.EMPTY

/** スケジューラとマシンをまとめて作り、後片付けで全スレッドが終わったのを確かめる。 */
internal class Harness(
    schedulerConfig: SchedulerConfig = SchedulerConfig.builder().maxConcurrentMachines(4).maxBlockingProcesses(64).build(),
    executor: ExecutorService? = null,
    workerIdleMillis: Long = 30_000,
    /** `Machine.exec` が読み込むランタイム。`null` ならダミーの [runtime] を使い、燃料の計測コードは挿入しない */
    execRuntime: WasmRuntime? = null,
    /** タイマーの期限を待つ口（`null` なら本物のスレッド `KiBi-timer`） */
    timers: TimerService? = null,
    /** `Machine.exec` で待機点への変換をかける（#329）。`false` なら p1 のコマンドを `BLOCKING` で動かす */
    asyncify: Boolean = true,
    /** スケジューラの読み込みの上限（#40）。`null` ならカーネルは [ModuleLimits.DEFAULT] の値で上限をかける */
    moduleLimits: ModuleLimits? = if (execRuntime != null) ModuleLimits.DEFAULT else null,
    /** モジュールの置き場（#82）。既定はメモリ上のもの。[program] で作ったモジュールはここに置く */
    val moduleStore: ModuleStore = MemoryModuleStore(),
    /** ゲストの再起動で起動し直すまでの待ち（#635） */
    rebootBackoff: RebootBackoff = RebootBackoff.DEFAULT,
    /**
     * tick ごとの予算（#356）を測る時計。既定は止まった時計で、予算は効かない（初回のログ・クラスの読み込みで、
     * 実際の時間の予算が効いて通知が次の tick に回り、テストが不安定にならないように）。予算を確かめるテストは手で進める時計を渡す
     */
    nanoTime: () -> Long = { 0L },
) : AutoCloseable {
    val runtime = FakeWasmRuntime()
    val scheduler = MachineScheduler.create(
        schedulerConfig,
        SchedulerInternals(executor, workerIdleMillis, execRuntime ?: runtime, meterExecutables = execRuntime != null, moduleLimits = moduleLimits, timers = timers, asyncifyExecutables = asyncify, moduleStore = moduleStore, rebootBackoff = rebootBackoff, nanoTime = nanoTime),
    )
    private var counter = 0
    private val closeActions = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /**
     * [close] の最初（スケジューラを止める前）に [action] を呼ぶ（#916）。期限なしの門（フックの中の `release.await()` など）で止めた
     * スレッドを、テストが門を開ける前に落ちても放してから止めるために使う（放さないと、close がスレッドの終わりを待って落ち、
     * 残ったスレッドが後に続くテストの [assertNoKernelThreads] も落とす）。通った回は門がもう開いているので、何もしない
     */
    fun releaseOnClose(action: () -> Unit) {
        closeActions += action
    }

    /** 通知の記録（tick() の中で配られたもの） */
    val events: MutableList<Pair<Long, MachineState>> = java.util.Collections.synchronizedList(ArrayList())

    fun machine(
        fuelPerTick: Long = 100,
        maxProcesses: Int = 64,
        host: MachineHost? = null,
        maxHostCallsPerTick: Int = 64,
        environment: MachineEnvironment = MachineEnvironment.DEFAULT,
        memoryBytes: Long? = null,
        maxProcessMemoryBytes: Long? = null,
        maxDevices: Int? = null,
        fuelSlice: Long? = null,
        maxBlockingProcesses: Int? = null,
    ): Machine =
        scheduler.createMachine(
            host ?: MachineHost { m, s, _ -> events += m.id to s },
            MachineConfig.builder().fuelPerTick(fuelPerTick).maxProcesses(maxProcesses).maxHostCallsPerTick(maxHostCallsPerTick).apply {
                memoryBytes?.let { memoryBytes(it) }
                maxProcessMemoryBytes?.let { maxProcessMemoryBytes(it) }
                maxDevices?.let { maxDevices(it) }
                fuelSlice?.let { fuelSlice(it) }
                // テストの駆動役は多くがブロッキング型なので、既定は maxProcesses まで（製品の既定の 1 台の上限は ComponentExecTest で確かめる）
                maxBlockingProcesses(maxBlockingProcesses ?: maxProcesses)
            }.build(),
            environment,
        )

    /** Fake のモジュールを読み込んだ [Program] にする */
    fun program(module: FakeModule): Program {
        val bytes = "fake-module-${counter++}".toByteArray()
        runtime.define(bytes, module)
        val program = Program.load(runtime, bytes)
        moduleStore.put(ModuleStore.sha256(bytes), bytes)
        return program
    }

    fun module(program: Program): WasmModule = program.module

    override fun close() {
        closeActions.forEach { it() }
        scheduler.close()
        // 期限なしの門（フックの中の `release.await()` など）で止まったスレッドが残っていたら、割り込んで放す（#916。テストが門を開ける前に落ちた
        // ときも、スレッドを JVM に残さない。残すと、後に続く Harness のテストがすべて assertNoKernelThreads で落ちる）。
        // 放しても、スケジューラが 1 回目の待ちで終わらなかったことは失敗として残す
        val ended = scheduler.awaitTermination(10_000)
        if (!ended) {
            Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("KiBi-") }.forEach { it.interrupt() }
            scheduler.awaitTermination(10_000)
        }
        assertTrue(ended, "スケジューラが終わらない")
        assertNoKernelThreads()
    }
}

internal fun Machine.spawnTest(program: Program, driver: ProcessDriver, unmetered: Boolean = false, parentPid: Int = 0): Int =
    spawn(program, driver, SpawnOptions(allowUnmetered = unmetered), parentPid)

internal fun Machine.rt(pid: Int): ProcessRuntime = runtime(pid)!!

/**
 * [Machine.spawn] して、作ったプロセスの実行時の部分を返す。spawn と取り出しを1つのカーネルロックの区間にするので、
 * 子がすぐ終わって刈り取られ（`runtimes` から外れ）ても、取り逃さない（spawn の後に [rt] で取ると競合する）。
 * 作業ディレクトリ・preopen を指定する [options] には使えない（spawn がロックを持たずに呼ぶことを求めるため）
 */
internal fun Machine.spawnRt(program: Program, driver: ProcessDriver, options: SpawnOptions = SpawnOptions(), parentPid: Int = 0): ProcessRuntime =
    lock.withLock { runtime(spawn(program, driver, options, parentPid))!! }

internal fun Machine.stateOf(pid: Int): ProcessState = processState(pid)!!

/**
 * [condition] が true になるまで待つ。
 *
 * 期限は合否に使わない（#916）。実スレッドの完了を壁時計の短い期限で待つと、負荷の高い CI で正しく動いていても落ちるため。
 * [timeoutMillis] は遅れを報告する閾値で、過ぎたら [message] を [SLOW_WAIT_MARKER] で始まる行として標準出力に出し（core の `test`
 * タスクが Gradle のコンソールに写す）、そのまま待ち続ける。止まったときは、保険のタイムアウト（`:core:test` の既定のタイムアウトか、
 * クラス・メソッドの `@Timeout`）がテストのスレッドに割り込み、[message] を付けて落ちる
 */
internal fun eventually(timeoutMillis: Long = 5_000, message: String = "条件がそろわない", condition: () -> Boolean) =
    eventuallyDescribe(timeoutMillis, { message }, condition)

/** [eventually] の、遅れの報告と失敗の説明を後で作る形（期限は合否に使わない。#916） */
internal fun eventuallyDescribe(timeoutMillis: Long = 5_000, describe: () -> String, condition: () -> Boolean) {
    val start = System.nanoTime()
    var reported = false
    while (!condition()) {
        if (!reported && System.nanoTime() - start > timeoutMillis * 1_000_000) {
            reported = true
            reportSlowWait("${(System.nanoTime() - start) / 1_000_000} ms を超えた: ${describe()}")
        }
        failOnInterrupt(describe) { Thread.sleep(1) }
    }
}

/** 待ちが遅れたことを報告する行の頭（#916）。core の `test` タスクが、この頭の行を Gradle のコンソール（CI のログ）に写す */
internal const val SLOW_WAIT_MARKER = "#916 遅れ"

/** [text] を、行ごとに [SLOW_WAIT_MARKER] を付けて標準出力に出す（Gradle は標準出力を行ごとに受け取り、頭の付いた行だけを写すため） */
internal fun reportSlowWait(text: String) {
    println(text.lines().joinToString("\n") { "$SLOW_WAIT_MARKER: $it" })
}

/**
 * [block] が割り込まれたら、割り込みのフラグを立て直してから、[describe] の説明を付けて落とす（#916）。
 *
 * 期限なしの待ちを抜けるのは、保険のタイムアウトの割り込みだけ。フラグを立て直さないと、後始末（`finally` の `join()` など）が
 * 2 回目の割り込みを待って止まり続ける（JUnit は 1 回しか割り込まない）
 */
internal inline fun <T> failOnInterrupt(describe: () -> String, block: () -> T): T =
    try {
        block()
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw AssertionError("待っている間に割り込まれた（止まったときの保険のタイムアウト。#916）: ${describe()}", e)
    }

/** 期限なしで [CountDownLatch.await] する（#916）。割り込まれたら [message] を付けて落ちる */
internal fun CountDownLatch.awaitOrFail(message: String = "ラッチが開かない") {
    failOnInterrupt({ "$message（残り $count）" }) { await() }
}

/** 期限なしで [Thread.join] する（#916）。割り込まれたら [message] と、相手のスレッドのスタックを付けて落ちる */
internal fun Thread.joinOrFail(message: String = "スレッドが終わらない") {
    failOnInterrupt({ "$message: $name\n${stackTrace.joinToString("\n") { "    at $it" }}" }) { join() }
}

/** 期限なしで [ExecutorService.awaitTermination] する（#916。テストが作ったプールに使う）。割り込まれたら [message] を付けて落ちる */
internal fun ExecutorService.awaitTerminationOrFail(message: String = "プールのスレッドが終わらない") {
    failOnInterrupt({ message }) { while (!awaitTermination(1, TimeUnit.DAYS)) Unit }
}

/** 期限なしで [Future.get] する（#916）。割り込まれたら [message] を付けて落ちる */
internal fun <T> Future<T>.getOrFail(message: String = "結果が届かない"): T = failOnInterrupt({ message }) { get() }

/**
 * init が CPU を取って動き出し、専用スレッドの登録を済ませた（`register()` の後・`unregister()` の前）か。
 *
 * RUNNING が見えても、init の専用スレッドはまだ `register()` の前のことがある。その間に reboot・stop・写しの kill が入ると、
 * register が KILLED にして unregister（beforeUnregister のフック）を通らないので、止まり切る前の場面を作れない（#542、#624、#683）。
 * CREATED を抜けるのは register → CPU の取得 → load の後なので、BLOCKING の init（専用スレッドを実行の間ずっと登録している。
 * FakeModule の init など）なら、CREATED でも終了でもないことが、登録済みであることの保証になる。
 * 待機点型の init（step の間だけスレッドを登録する）には使えない。イベント待ちや燃料待ちでは、どの瞬間もスレッドが外れていて、
 * この条件は「step の最中」の保証にならない。待機点型で reboot などを step の最中に当てたいテストは、別の同期を使う。
 * CREATED の条件は落とさない（写しは CREATED を待機点として殺さないので、写しのテストがそれに頼る）。
 * 状態はカーネルロックの中で読む。テストのスレッドがカーネルロックを持ったまま呼ばない
 */
internal fun Machine.isInitStarted(): Boolean = lock.withLockValue {
    val pid = kernel.initPid
    val p = if (pid == 0) null else kernel.processes[pid]
    state == MachineState.RUNNING && !isBooting && p != null &&
        p.state != ProcessState.CREATED && !p.state.isTerminal
}

/** [Machine.isInitStarted] になるまで tick を進める */
internal fun Harness.tickUntilInitStarted(m: Machine, timeoutMillis: Long = 10_000) =
    eventually(timeoutMillis, "init が CPU を取らない") {
        scheduler.tick()
        Thread.sleep(1)
        m.isInitStarted()
    }

/**
 * 後段の失敗で CRASHED を見た後、後始末が終わるまで待ってから tick する（#669。MachineRestoreTest と MachineRestoreBranchesTest が使う）。
 *
 * state はロックを取らずに読むので、stopLocked の途中（setStateLocked(CRASHED) の直後で、復元の quiesce を閉じる前、
 * 通知を積む前）で見えることがある。マシンのロックを一度取って completeRestore のロックの区間が終わるのを待ち、
 * ロックの外の後始末（discard など）を走らせる起動用スレッドが空になるのも待つ。その後に tick して、知らせが積まれて
 * いないこと（積む退行があっても、先の tick に間に合わないと見えないため）と CRASHED の通知を見る
 */
internal fun Harness.awaitRestoreCleanup(m: Machine) {
    m.lock.withLockValue { }
    val drained = { scheduler.bootExecutor.activeCount == 0 && scheduler.bootExecutor.queue.isEmpty() }
    eventually(10_000, "起動用スレッドの後始末が終わらない") { drained() }
    repeat(3) { scheduler.tick() }
    eventually(10_000, "tick の後で起動用スレッドが空にならない") { drained() }
    repeat(3) { scheduler.tick() }
}

/**
 * 名前が `KiBi-` で始まるスレッドが残っていない。
 *
 * [eventually] と違い、期限で落とす（#916）。JVM 全体のスレッドを見るので、前のテストが漏らしたスレッドがあると、後に続く
 * [Harness] のテストがすべてここで止まり、保険のタイムアウト（10 分）が 1 本ずつ積み重なって CI の段の上限を超えるため。
 * 期限は、負荷の高い CI でスレッドが終わるのに十分な長さにする
 */
internal fun assertNoKernelThreads() {
    val deadline = System.nanoTime() + KERNEL_THREADS_EXIT_MILLIS * 1_000_000
    while (kernelThreadNames().isNotEmpty()) {
        if (System.nanoTime() > deadline) fail("カーネルのスレッドが残っている: ${kernelThreadNames()}")
        failOnInterrupt({ "カーネルのスレッドが残っている: ${kernelThreadNames()}" }) { Thread.sleep(1) }
    }
}

/** [assertNoKernelThreads] が、スレッドが終わるのを待つ上限 */
private const val KERNEL_THREADS_EXIT_MILLIS = 30_000L

internal fun kernelThreadNames(prefix: String = "KiBi-"): List<String> =
    Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith(prefix) }.map { it.name }

/** 終わらない計算（1 ずつ燃料を使い、[progress] を数える） */
internal fun spinForever(progress: AtomicInteger? = null, sleepMillis: Long = 0): FakeModule = FakeModule.builder()
    .fuelMetered()
    .export("run", VOID) { ctx, _ ->
        while (true) {
            ctx.step(1)
            progress?.incrementAndGet()
            if (sleepMillis > 0) Thread.sleep(sleepMillis)
        }
        @Suppress("UNREACHABLE_CODE")
        LongArray(0)
    }
    .build()

/** 何もしないモジュール */
internal fun emptyModule(metered: Boolean = true): FakeModule = FakeModule.builder().apply { if (metered) fuelMetered() }.build()

/** import `env.h: () -> ()` を持ち、export `run` がそれを呼ぶモジュール */
internal fun hostCallModule(metered: Boolean = true): FakeModule = FakeModule.builder()
    .apply { if (metered) fuelMetered() }
    .import("env", "h", VOID)
    .export("run", VOID) { ctx, _ -> ctx.callImport("env", "h") }
    .build()

internal fun WasmInstanceConfig.Builder.host(ctx: ProcessContext, name: String = "h", f: KernelHostFunction): WasmInstanceConfig.Builder =
    hostFunction("env", name, VOID, ctx.hostFunction(f))

/** ブロッキング型で export `run` を呼んで終わるドライバー */
internal fun runDriver(
    kind: ProcessKind = ProcessKind.BLOCKING,
    code: Int = 0,
    configure: (ProcessContext, WasmInstanceConfig.Builder) -> Unit = { _, _ -> },
): TestDriver = TestDriver(kind, onConfigure = configure) { ctx ->
    ctx.instance.call("run")
    StepResult.Exit(code)
}

internal fun assertState(expected: ProcessState, machine: Machine, pid: Int) {
    assertEquals(expected, machine.stateOf(pid), "pid $pid の状態")
}
