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
import pro.ikili.kibicomputers.core.machine.MachineManager
import pro.ikili.kibicomputers.core.runtime.FakeModule
import pro.ikili.kibicomputers.core.runtime.FakeWasmRuntime
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.StateCaptureMode
import pro.ikili.kibicomputers.core.runtime.WasmRuntime
import pro.ikili.kibicomputers.core.runtime.validation.ModuleLimits
import pro.ikili.kibicomputers.core.runtime.validation.ValidationWatchdog
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
    /**
     * 読み込んだ実行ファイルを、JVM の中の共有の表（[SharedPrograms]）で使い回す（#991）。共有するのは、[execRuntime] が引数なしで作った
     * `EndiveRuntime` で、[moduleLimits] が [ModuleLimits.DEFAULT] で、[schedulerConfig] のスレッドのスタックが既定より小さくないときだけ
     * （それ以外はいつも読み込む。[SharedPrograms.shareable]）。読み込みの副作用（回数・時間・読み込み時のログ）に頼るテストは `false` にし、
     * 理由をコメントに書く
     */
    sharePrograms: Boolean = true,
    /** [close] が、スケジューラの終わりを 1 回目に待つ長さ（過ぎたら残った `KiBi-` スレッドに割り込んでもう 1 回待つ。#916） */
    private val firstCloseWaitMillis: Long = 10_000,
) : AutoCloseable {
    val runtime = FakeWasmRuntime()
    val scheduler = MachineScheduler.create(
        schedulerConfig,
        SchedulerInternals(
            executor,
            workerIdleMillis,
            execRuntime ?: runtime,
            meterExecutables = execRuntime != null,
            moduleLimits = moduleLimits,
            timers = timers,
            asyncifyExecutables = asyncify,
            moduleStore = moduleStore,
            rebootBackoff = rebootBackoff,
            nanoTime = nanoTime,
            programLoader = if (sharePrograms && SharedPrograms.shareable(schedulerConfig)) SharedPrograms.executableLoader else null,
        ),
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

    /**
     * このスケジューラの起動用（`KiBi-boot-*`）・ワーカー（`KiBi-worker-*`）のプールが作ったスレッド（#916）。
     * プールの [java.util.concurrent.ThreadFactory] を包んで記録する（プールは作ったスレッドを外に見せないため）。
     * 名前の連番はスケジューラごとなので、名前では選ばない
     */
    private val poolThreads: MutableSet<Thread> = java.util.Collections.newSetFromMap(ConcurrentHashMap())

    /** ワーカーのプールの executor（差し替えたときはそれ）。本番の [pro.ikili.kibicomputers.core.kernel.sched.WorkerPool] にテスト用の口を足さないように、反射で読む */
    private val workerExecutor: ExecutorService = workerPoolField("executor") as ExecutorService

    init {
        // 起動用・ワーカーのスレッドは、使われたときに初めてできる（プールはコア 0 か、コアのスレッドを先に作らない）ので、ここで包めば取り逃さない
        val workerPool = workerPoolField("pool") as java.util.concurrent.ThreadPoolExecutor?
        for (pool in listOfNotNull(scheduler.bootExecutor, workerPool)) {
            val inner = pool.threadFactory
            pool.threadFactory = java.util.concurrent.ThreadFactory { r -> inner.newThread(r)?.also { poolThreads += it } }
        }
    }

    private fun workerPoolField(name: String): Any? =
        scheduler.workers.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(scheduler.workers)

    /** このスケジューラのスレッド（マシンのプロセスの専用スレッド、ディスパッチャー、起動用・ワーカーのプールのスレッド）のうち、まだ生きているもの */
    private fun ownKernelThreads(): List<Thread> =
        (ArrayList(scheduler.dedicatedThreads) + scheduler.dispatcher.thread + poolThreads).distinct().filter { it.isAlive }

    /** [own] と、このスケジューラの起動用・ワーカーのプールのスレッド（`shutdownNow` が割り込む）に割り込む。ほかのスケジューラ・テストのスレッドには触れない */
    private fun interruptOwnThreads(own: List<Thread>) {
        own.forEach { it.interrupt() }
        scheduler.bootExecutor.shutdownNow()
        workerExecutor.shutdownNow()
    }

    override fun close() {
        // 保険のタイムアウトで割り込まれた後（failOnInterrupt がフラグを立て直している）でも後始末の待ちが動くように、フラグを下ろしてから
        // 待ち、最後に戻す（#916。立ったままだと最初の待ちがその場で InterruptedException を投げ、何も待たず、割り込みの後始末にも届かない）
        withInterruptCleared {
            closeActions.forEach { it() }
            scheduler.close()
            // 期限なしの門（フックの中の `release.await()` など）で止まったスレッドが残っていたら、このスケジューラのスレッドだけに割り込んで放す
            // （#916。テストが門を開ける前に落ちたときも、スレッドを JVM に残さない。残すと、後に続く Harness のテストがすべて
            // assertNoKernelThreads で落ちる）。JVM の `KiBi-` スレッド全部には割り込まない: 保険のタイムアウトで捨てられた本体のこの close は、
            // 次のテストと並行に走るので、名前だけで選ぶと次のテストのスレッドや、同じテストの別の Harness・MachineSaveStore のスレッドまで巻き込む。
            // 放しても、スケジューラが 1 回目の待ちで終わらなかったことは失敗として残す
            val ended = scheduler.awaitTermination(firstCloseWaitMillis)
            var stuck = ""
            if (!ended) {
                val own = ownKernelThreads()
                stuck = own.joinToString("\n") { "  ${it.name}\n" + it.stackTrace.take(12).joinToString("\n") { f -> "    at $f" } }
                reportSlowWait("スケジューラが $firstCloseWaitMillis ms で終わらない。残ったスレッドに割り込む:\n$stuck")
                interruptOwnThreads(own)
                scheduler.awaitTermination(10_000)
            }
            assertTrue(ended, "スケジューラが終わらない。残ったスレッド:\n$stuck")
            assertNoKernelThreads()
        }
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
 * クラス・メソッドの `@Timeout`）がテストのスレッドに割り込み、[message] を付けて落ちる。
 * 本体を `assertTimeoutPreemptively` で包んでいて、それが先に切れたときは、JUnit は「execution timed out」だけを報告し、この [message] の
 * AssertionError は捨てられたスレッドに残る。そのため、割り込まれた時点で [message] を [SLOW_WAIT_MARKER] の行として標準出力にも出す
 * （[failOnInterrupt]）。止まった原因は、レポートの失敗の本文でなく、コンソール・CI のログと system-out のこの行で追う
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
        val message = "待っている間に割り込まれた（止まったときの保険のタイムアウト。#916）: ${describe()}"
        // 外側の assertTimeoutPreemptively が先に切れたときは、JUnit は「execution timed out」を報告し終えていて、この例外は捨てられた実行スレッドの
        // Future に入ったまま誰にも読まれない。説明（何を待っていたか）が失敗の手がかりとして残るように、標準出力にも出す
        // （core の `test` タスクがコンソールと CI のログに写す）
        reportSlowWait("$message\n${e.stackTrace.take(12).joinToString("\n") { "    at $it" }}")
        throw AssertionError(message, e)
    }

/**
 * 割り込みのフラグを下ろして [block] を呼び、終わったら（[block] が落ちても）フラグを元に戻す（#916）。
 *
 * 保険のタイムアウトで割り込まれた後の後始末（[Harness.close] など）に使う。[failOnInterrupt] がフラグを立て直しているので、
 * 下ろさないと後始末の待ちがその場で `InterruptedException` を投げ、何も待たない
 */
internal inline fun <T> withInterruptCleared(block: () -> T): T {
    val interrupted = Thread.interrupted()
    try {
        return block()
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}

/**
 * 割り込みのフラグが立ったまま呼ばれた待ち（保険のタイムアウトで落ちている途中の、`finally` の後始末）を、期限つきで済ませる（#916）。
 *
 * フラグが立っていれば、フラグを下ろして [timedWait]（待つ上限のミリ秒を受け取り、終わったら `true`）で最大 [graceMillis] 待ち、
 * フラグを戻して `true` を返す。待ち切れなくても投げずに、[SLOW_WAIT_MARKER] の行で報告して返す（投げると、`finally` の外へ出ていく
 * 元の失敗の説明を置き換えるため。テストは保険のタイムアウトで既に落ちている）。
 * フラグが立っていなければ何もせず `false` を返す（呼び出し側が期限なしで待つ）
 */
internal fun cleanupWaitIfInterrupted(describe: () -> String, graceMillis: Long = CLEANUP_WAIT_MILLIS, timedWait: (Long) -> Boolean): Boolean {
    if (!Thread.currentThread().isInterrupted) return false
    withInterruptCleared {
        val done = try {
            timedWait(graceMillis)
        } catch (e: InterruptedException) {
            false
        }
        if (!done) reportSlowWait("割り込まれた後の後始末で、$graceMillis ms 待っても終わらない: ${describe()}")
    }
    return true
}

/** [cleanupWaitIfInterrupted] が待つ上限 */
internal const val CLEANUP_WAIT_MILLIS = 10_000L

/**
 * `finally` の後始末の、期限つきの待ち（[timedWait] は待つ上限のミリ秒を受け取り、終わったら `true`）を、最大 [timeoutMillis] 待つ（#916）。
 *
 * 割り込みのフラグが立っていれば（保険のタイムアウトで落ちている途中。[failOnInterrupt] がフラグを立て直している）、
 * [cleanupWaitIfInterrupted] でフラグを下ろして待ち、`true` を返す（待ち切れなくても投げず、元の失敗の説明を置き換えない。
 * 立ったまま待つと、その場で `InterruptedException` を投げ、何も待たずに元の失敗を置き換える）。
 * フラグがなければ、そのまま待って結果を返す
 */
internal fun awaitInCleanup(describe: () -> String, timeoutMillis: Long, timedWait: (Long) -> Boolean): Boolean =
    cleanupWaitIfInterrupted(describe, timeoutMillis, timedWait) || timedWait(timeoutMillis)

/** `finally` の後始末で [MachineManager.shutdown] する（#916。割り込まれた後の扱いは [awaitInCleanup]） */
internal fun MachineManager<*>.shutdownInCleanup(timeoutMillis: Long = 10_000): Boolean =
    awaitInCleanup({ "MachineManager が止まらない: $this" }, timeoutMillis) { shutdown(it) }

/**
 * 期限なしで [CountDownLatch.await] する（#916）。割り込まれたら [message] を付けて落ちる。
 * 割り込みのフラグが立ったまま呼ばれたら（割り込まれた後の後始末）、[cleanupWaitIfInterrupted] で期限つきで待って返す
 */
internal fun CountDownLatch.awaitOrFail(message: String = "ラッチが開かない") {
    val describe = { "$message（残り $count）" }
    if (cleanupWaitIfInterrupted(describe) { await(it, TimeUnit.MILLISECONDS) }) return
    failOnInterrupt(describe) { await() }
}

/**
 * 期限なしで [Thread.join] する（#916）。割り込まれたら [message] と、相手のスレッドのスタックを付けて落ちる。
 * 割り込みのフラグが立ったまま呼ばれたら（割り込まれた後の後始末）、[cleanupWaitIfInterrupted] で期限つきで待って返す
 */
internal fun Thread.joinOrFail(message: String = "スレッドが終わらない") {
    val describe = { "$message: $name\n${stackTrace.joinToString("\n") { "    at $it" }}" }
    if (cleanupWaitIfInterrupted(describe) { join(it); !isAlive }) return
    failOnInterrupt(describe) { join() }
}

/**
 * 期限なしで [ExecutorService.awaitTermination] する（#916。テストが作ったプールに使う）。割り込まれたら [message] を付けて落ちる。
 * 割り込みのフラグが立ったまま呼ばれたら（割り込まれた後の後始末）、[cleanupWaitIfInterrupted] で期限つきで待って返す
 */
internal fun ExecutorService.awaitTerminationOrFail(message: String = "プールのスレッドが終わらない") {
    if (cleanupWaitIfInterrupted({ message }) { awaitTermination(it, TimeUnit.MILLISECONDS) }) return
    failOnInterrupt({ message }) { while (!awaitTermination(1, TimeUnit.DAYS)) Unit }
}

/** 期限なしで [Future.get] する（#916）。割り込まれたら [message] を付けて落ちる（値が要るので、後始末の期限つきの待ちはしない） */
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
 * カーネルのスレッドの残りとして数えない、JVM に 1 本のスレッドの名前（#990）。
 *
 * コンポーネントの検証の見張り（[ValidationWatchdog.THREAD_NAME]）は、スケジューラのものではなく JVM で共有する 1 本で、
 * 仕事がなくなってから 1 秒で終わる（ADR 0666）。数えると、コンポーネントを読み込んだテストが終わるたびに約 1 秒待つ。
 * 仕事がなければ終わることは `ValidationWatchdogTest` が受け持ち、`Watch.finish` の呼び忘れ（予定が残ってスレッドが生き続ける）は
 * [assertNoKernelThreads] が予定の数で見る。名前の完全一致で除く（接頭辞で除くと `KiBi-wasm-compiler-N` の残りまで見えなくなる）
 */
private val JVM_WIDE_THREAD_NAMES: Set<String> = setOf(ValidationWatchdog.THREAD_NAME)

/**
 * 名前が `KiBi-` で始まるスレッド（[JVM_WIDE_THREAD_NAMES] を除く）が残っておらず、共有の見張りの予定も残っていない。
 *
 * [eventually] と違い、期限で落とす（#916）。JVM 全体のスレッドを見るので、前のテストが漏らしたスレッドがあると、後に続く
 * [Harness] のテストがすべてここで止まり、保険のタイムアウト（10 分）が 1 本ずつ積み重なって CI の段の上限を超えるため。
 * 期限は、負荷の高い CI でスレッドが終わるのに十分な長さにする
 */
internal fun assertNoKernelThreads(timeoutMillis: Long = KERNEL_THREADS_EXIT_MILLIS, prefix: String = "KiBi-") {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    fun leftover(): String =
        "カーネルのスレッドか見張りの予定が残っている: threads=${kernelThreadNames(prefix)}, " +
            "watchdogPending=${ValidationWatchdog.sharedPendingCount()}"
    while (kernelThreadNames(prefix).isNotEmpty() || ValidationWatchdog.sharedPendingCount() != 0) {
        if (System.nanoTime() > deadline) fail(leftover())
        failOnInterrupt({ leftover() }) { Thread.sleep(1) }
    }
}

/** [assertNoKernelThreads] が、スレッドが終わるのを待つ上限 */
private const val KERNEL_THREADS_EXIT_MILLIS = 30_000L

/** 名前が [prefix] で始まる、生きているスレッドの名前（[JVM_WIDE_THREAD_NAMES] を除く） */
internal fun kernelThreadNames(prefix: String = "KiBi-"): List<String> =
    Thread.getAllStackTraces().keys
        .filter { it.isAlive && it.name.startsWith(prefix) && it.name !in JVM_WIDE_THREAD_NAMES }
        .map { it.name }

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
