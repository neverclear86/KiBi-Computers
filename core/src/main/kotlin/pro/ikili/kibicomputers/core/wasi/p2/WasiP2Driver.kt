package pro.ikili.kibicomputers.core.wasi.p2

import pro.ikili.kibicomputers.core.kernel.process.ComponentSuspend
import pro.ikili.kibicomputers.core.kernel.process.KernelLinkEnv
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriver
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.fsError
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentPlan
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentProgram
import pro.ikili.kibicomputers.core.runtime.component.link.ExportItem
import pro.ikili.kibicomputers.core.runtime.component.link.FuncRef

/**
 * p2 のコマンド（`wasi:cli/run` を export するコンポーネント）のドライバー（#267。ADR 0267）。`run` を呼ぶ。
 *
 * 待機点への変換をかけたコンポーネント（[ComponentProgram.waitPoints]。#757、ADR 0757）は `WAIT_POINT` で動く: 待ちうるホストの関数が
 * 巻き戻すと `run` から戻るので、待つ key を `StepResult.Wait` にする。再開（[resume]）は巻き直して `run` を呼び直す。
 * かけられなかったものは `BLOCKING` で、`run` を 1 回呼ぶ（待つ間はスレッドを持つ）。`exec` では変換できないものを読み込みで断る（#844）ので、
 * `BLOCKING` になるのは直接読み込んだもの（テスト・ブロッキングとの比較）だけ。
 *
 * 止まりうる関数の中の燃料切れ（`kibi.async_fuel`。[ComponentProgram.fuelYield]。#803）で巻き戻したときも `run` から戻るので、
 * そのときは `StepResult.WaitFuel` にする（スレッドを持たずに燃料を待つ。p1 の `WasiP1` と同じ）。再開は燃料のあとでも
 * ホストの待ちのあとでも同じく、巻き直して `run` を呼び直す（燃料のあとは、カーネルが巻き直しの印を立ててから [resume] を呼ぶ）
 *
 * - 呼ぶ export は、import と同じ規則（[WasiP2Versions]）に合う `wasi:cli/run@0.2.<n>` のどれか 1 つ（2 つ以上あれば断る）。
 *   中の `run` は `func() -> result` であること
 * - 終了コード（ADR 0074 の表）: `run` が `ok` → 0、`err` → 1。`wasi:cli/exit` の `exit(ok)` → 0、`exit(err)` → 1、
 *   `exit-with-code(n)` → n
 * - `Machine.exec` でコンポーネントを動かすときは、`KernelDrivers.DEFAULT` がこれを選ぶ（#687）
 */
internal class WasiP2Driver private constructor(private val run: FuncRef, waitPoints: Boolean) : ProcessDriver {
    override val kind: ProcessKind = if (waitPoints) ProcessKind.WAIT_POINT else ProcessKind.BLOCKING

    override fun configure(ctx: ProcessContext, builder: WasmInstanceConfig.Builder) {
        // コンポーネントのプロセスでは呼ばれない（ホストの関数はリンクの計画が持つ）
    }

    override fun start(ctx: ProcessContext): StepResult {
        // `initial-cwd` と `get-environment` の `PWD` は起動したときの cwd（最初の start だけで決める）
        WasiP2Cli.initialCwd(ctx)
        return run(ctx)
    }

    override fun resume(ctx: ProcessContext, ready: List<WaitKey>): StepResult {
        check(kind == ProcessKind.WAIT_POINT) { "BLOCKING のドライバーは resume しない" }
        ctx.component.beginRewind()
        return run(ctx)
    }

    private fun run(ctx: ProcessContext): StepResult {
        val component = ctx.component
        val r = try {
            component.call(run, emptyList())
        } catch (e: WasiP2Exit) {
            return StepResult.Exit(e.code)
        }
        if (component.unwound) {
            val s = suspend(ctx)
            if (ctx.takeFuelUnwind()) {
                check(!s.hasPendingKeys) { "燃料切れとホストの待ちの巻き戻しが重なった" }
                return StepResult.WaitFuel
            }
            return StepResult.Wait(s.takePendingKeys())
        }
        return StepResult.Exit(if ((r as CanonValue.Case).index == 0) 0 else 1)
    }

    private fun suspend(ctx: ProcessContext): ComponentSuspend = (ctx.component.env as KernelLinkEnv).suspend

    companion object {
        /**
         * [program] のコマンドのドライバー（待機点への変換をかけたものは `WAIT_POINT`）。`wasi:cli/run@0.2.<n>` の `run`（`func() -> result`）がなければ、
         * または規則に合う export が 2 つ以上あれば `NOEXEC`
         */
        fun create(program: ComponentProgram): WasiP2Driver = WasiP2Driver(runOf(program.plan), program.waitPoints)

        /** [plan] の `run`（[create] の規則）。合わなければ `NOEXEC` の `FsException` */
        fun runOf(plan: ComponentPlan): FuncRef {
            val names = plan.exports.keys.filter { WasiP2Versions.key(it) == WasiP2Host.RUN }
            if (names.isEmpty()) fsError(Errno.NOEXEC, "wasi:cli/run@0.2.<n> を export していない")
            if (names.size > 1) fsError(Errno.NOEXEC, "wasi:cli/run の export が 2 つ以上ある（$names）")
            val inst = plan.exports.getValue(names.single()) as? ExportItem.Instance ?: fsError(Errno.NOEXEC, "${names.single()} がインスタンスでない")
            val f = (inst.exports["run"] as? ExportItem.Func)?.ref ?: fsError(Errno.NOEXEC, "${names.single()} に run がない")
            val type = f.function
            val result = type.result
            if (type.params.isNotEmpty() || result !is CanonType.Result || result.ok != null || result.err != null) {
                fsError(Errno.NOEXEC, "${names.single()} の run の型が func() -> result でない")
            }
            return f
        }
    }
}
