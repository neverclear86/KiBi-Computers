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
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.runtime.FakeModule
import pro.ikili.kibicomputers.core.runtime.FakeWasmRuntime
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmRuntime
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

/** テスト用の [ProcessDriver]。振る舞いを関数で渡す。 */
internal class TestDriver(
    override val kind: ProcessKind,
    private val onConfigure: (ProcessContext, WasmInstanceConfig.Builder) -> Unit = { _, _ -> },
    private val onResume: (ProcessContext, List<WaitKey>) -> StepResult = { _, _ -> fail("resume は呼ばれないはず") },
    private val onStart: (ProcessContext) -> StepResult,
) : ProcessDriver {
    val configureCalls = AtomicInteger()

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

/** ホスト関数の型 `() -> ()` */
internal val VOID: WasmFunctionType = WasmFunctionType.EMPTY

/** スケジューラとマシンをまとめて作り、後片付けで全スレッドが終わったのを確かめる。 */
internal class Harness(
    schedulerConfig: SchedulerConfig = SchedulerConfig.builder().maxConcurrentMachines(4).maxBlockingProcesses(64).build(),
    executor: ExecutorService? = null,
    workerIdleMillis: Long = 30_000,
    /** `Machine.exec` が読み込むランタイム。`null` ならダミーの [runtime] を使い、燃料の計測コードは挿入しない */
    execRuntime: WasmRuntime? = null,
) : AutoCloseable {
    val runtime = FakeWasmRuntime()
    val scheduler = MachineScheduler.create(
        schedulerConfig,
        SchedulerInternals(executor, workerIdleMillis, execRuntime ?: runtime, meterExecutables = execRuntime != null),
    )
    private var counter = 0

    /** 通知の記録（tick() の中で配られたもの） */
    val events: MutableList<Pair<Long, MachineState>> = java.util.Collections.synchronizedList(ArrayList())

    fun machine(
        fuelPerTick: Long = 100,
        maxProcesses: Int = 64,
        host: MachineHost? = null,
        maxHostCallsPerTick: Int = 64,
        environment: MachineEnvironment = MachineEnvironment.DEFAULT,
    ): Machine =
        scheduler.createMachine(
            host ?: MachineHost { m, s, _ -> events += m.id to s },
            MachineConfig.builder().fuelPerTick(fuelPerTick).maxProcesses(maxProcesses).maxHostCallsPerTick(maxHostCallsPerTick).build(),
            environment,
        )

    /** Fake のモジュールを読み込んだ [Program] にする */
    fun program(module: FakeModule): Program {
        val bytes = "fake-module-${counter++}".toByteArray()
        runtime.define(bytes, module)
        return Program.load(runtime, bytes)
    }

    fun module(program: Program): WasmModule = program.module

    override fun close() {
        scheduler.close()
        assertTrue(scheduler.awaitTermination(10_000), "スケジューラが終わらない")
        assertNoKernelThreads()
    }
}

internal fun Machine.spawnTest(program: Program, driver: ProcessDriver, unmetered: Boolean = false, parentPid: Int = 0): Int =
    spawn(program, driver, SpawnOptions(allowUnmetered = unmetered), parentPid)

internal fun Machine.rt(pid: Int): ProcessRuntime = runtime(pid)!!

internal fun Machine.stateOf(pid: Int): ProcessState = processState(pid)!!

/** [condition] が true になるまで待つ（最大 [timeoutMillis]） */
internal fun eventually(timeoutMillis: Long = 5_000, message: String = "条件がそろわない", condition: () -> Boolean) =
    eventuallyDescribe(timeoutMillis, { message }, condition)

/** [eventually] の、失敗したときの説明を後で作る形 */
internal fun eventuallyDescribe(timeoutMillis: Long = 5_000, describe: () -> String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) fail(describe())
        Thread.sleep(1)
    }
}

/** 名前が `KiBi-` で始まるスレッドが残っていない */
internal fun assertNoKernelThreads() {
    eventually(5_000, "カーネルのスレッドが残っている: ${kernelThreadNames()}") { kernelThreadNames().isEmpty() }
}

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
