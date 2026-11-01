package pro.ikili.kibicomputers.core.kernel

import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.process.ExitStatus
import pro.ikili.kibicomputers.core.kernel.process.Process
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessObject
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey

// 終了待ち・kill・親が終わったときの子のテスト（#46）が共通で使うもの

/** tick を回しながら [condition] を待つ */
internal fun Harness.tickUntilTrue(message: String = "条件がそろわない", condition: () -> Boolean) = eventually(10_000, message) {
    scheduler.tick()
    Thread.sleep(1)
    condition()
}

/** 待ちの門（書けば開く ByteStream）。[open] で開ける */
internal class Gate(private val m: Machine) {
    val id: Int = m.lock.withLockValue { m.kernel.objects.add(ByteStream(1)) }

    val key: WaitKey get() = WaitKey(id, WaitCondition.READABLE)

    fun open() = m.lock.withLockValue { (m.kernel.objects[id] as ByteStream).closeWrite(m.wakeSink) }
}

/** ブロッキング型: [gate] が開くまで待ってから [code] で終わる */
internal fun gated(gate: Gate, code: Int = 0, kind: ProcessKind = ProcessKind.BLOCKING): TestDriver = when (kind) {
    ProcessKind.BLOCKING -> TestDriver(kind) { ctx ->
        ctx.block(listOf(gate.key))
        StepResult.Exit(code)
    }
    ProcessKind.WAIT_POINT -> TestDriver(kind, onResume = { _, _ -> StepResult.Exit(code) }) { StepResult.Wait(listOf(gate.key)) }
}

/** すぐに [code] で終わる */
internal fun exiting(code: Int, kind: ProcessKind = ProcessKind.BLOCKING): TestDriver = TestDriver(kind) { StepResult.Exit(code) }

/** ブロッキング型: [body] を実行してから [code] で終わる */
internal fun doing(code: Int = 0, body: (ProcessContext) -> Unit): TestDriver = TestDriver(ProcessKind.BLOCKING) { ctx ->
    body(ctx)
    StepResult.Exit(code)
}

internal fun Machine.process(pid: Int): Process? = lock.withLockValue { kernel.processes[pid] }

/** [pid] の `ProcessObject` の記録（なければ `null`） */
internal fun Machine.exitStatusOf(pid: Int): ExitStatus? = lock.withLockValue {
    val p = kernel.processes[pid] ?: return@withLockValue null
    (kernel.objects[p.objectId] as? ProcessObject)?.status
}

/** 燃料を1 tick 分入れる */
internal fun Machine.fillFuel() = lock.withLockValue { kernel.fuel = config.fuelPerTick }
