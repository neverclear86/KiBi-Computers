package pro.ikili.kibicomputers.core.wasi.p2

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
import pro.ikili.kibicomputers.core.runtime.component.link.ExportItem
import pro.ikili.kibicomputers.core.runtime.component.link.FuncRef

/**
 * p2 のコマンド（`wasi:cli/run` を export するコンポーネント）のドライバー（#267。ADR 0267）。`BLOCKING` で動き、`run` を 1 回呼ぶ。
 *
 * - 呼ぶ export は、import と同じ規則（[WasiP2Versions]）に合う `wasi:cli/run@0.2.<n>` のどれか 1 つ（2 つ以上あれば断る）。
 *   中の `run` は `func() -> result` であること
 * - 終了コード（ADR 0074 の表）: `run` が `ok` → 0、`err` → 1。`wasi:cli/exit` の `exit(ok)` → 0、`exit(err)` → 1、
 *   `exit-with-code(n)` → n
 * - `KernelDrivers.DEFAULT` へのつなぎ込み（exec でコンポーネントを動かす）は #687
 */
internal class WasiP2Driver private constructor(private val run: FuncRef) : ProcessDriver {
    override val kind: ProcessKind get() = ProcessKind.BLOCKING

    override fun configure(ctx: ProcessContext, builder: WasmInstanceConfig.Builder) {
        // コンポーネントのプロセスでは呼ばれない（ホストの関数はリンクの計画が持つ）
    }

    override fun start(ctx: ProcessContext): StepResult {
        // `initial-cwd` は起動したときの cwd
        WasiP2Cli.initialCwd(ctx)
        return try {
            val r = ctx.component.call(run, emptyList()) as CanonValue.Case
            StepResult.Exit(if (r.index == 0) 0 else 1)
        } catch (e: WasiP2Exit) {
            StepResult.Exit(e.code)
        }
    }

    override fun resume(ctx: ProcessContext, ready: List<WaitKey>): StepResult =
        throw IllegalStateException("BLOCKING のドライバーは resume しない")

    companion object {
        /**
         * [plan] のコマンドのドライバー。`wasi:cli/run@0.2.<n>` の `run`（`func() -> result`）がなければ、
         * または規則に合う export が 2 つ以上あれば `NOEXEC`
         */
        fun create(plan: ComponentPlan): WasiP2Driver = WasiP2Driver(runOf(plan))

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
