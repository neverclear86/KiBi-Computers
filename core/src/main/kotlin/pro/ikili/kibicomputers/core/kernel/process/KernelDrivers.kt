package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.fsError
import pro.ikili.kibicomputers.core.runtime.WasmExternKind
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentHost
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1Driver
import pro.ikili.kibicomputers.core.wasi.p2.WasiP2Driver
import pro.ikili.kibicomputers.core.wasi.p2.WasiP2Host

/** カーネルが自分で実行ファイルを動かすとき（起動の init など）のドライバーの選び方 */
internal object KernelDrivers {
    /**
     * コンポーネントの実行ファイルをリンクするホスト（#687）。サーバー全体で 1 つの固定のもの（今は [WasiP2Host]。`kibi:*` を
     * 足すとき（#685）も、同じ固定の合成ホストにする）。
     *
     * リンクは読み込みのときに 1 回だけで、計画（`ComponentPlan`）は `ProgramCache` で全マシンが共有する。`ProgramCache` のキーは
     * ホストを含まないので、マシンやデバイスの構成でホストを変えない（変えると、リンクの失敗が覚えられて別のマシンにも返る）。
     * デバイスがあるかどうかは、リンクのときではなく呼び出しのとき（`ComponentCall.env`）に見る
     */
    val COMPONENT_HOST: ComponentHost get() = WasiP2Host

    /**
     * 既定: `_start` を export する p1 のコマンドは [WasiP1Driver]、それ以外は `NOEXEC`。
     * p1 に許していない import（[WasiP1.checkImports]。#40）があっても `NOEXEC`。
     * p1 の止まりうる import（[WasiP1.WAIT_IMPORTS]）で待機点への変換をかけさせ（#329）、変換できたものは `WAIT_POINT` で動かす。
     * コンポーネント（[Program.component]）は p2 のコマンドとして [WasiP2Driver] で動かす（#687。`BLOCKING`。
     * `wasi:cli/run@0.2.<n>` の `run` がなければ `NOEXEC`。ADR 0267）
     */
    val DEFAULT: ProcessDriverFactory = object : ProcessDriverFactory {
        override val waitImports: Set<String> get() = WasiP1.WAIT_IMPORTS

        override fun create(program: Program): ProcessDriver {
            program.component?.let { return WasiP2Driver.create(it.plan) }
            val module = program.module
            val isCommand = module.exports.any { it.name == WasiP1Driver.START && it.kind == WasmExternKind.FUNCTION }
            if (!isCommand) fsError(Errno.NOEXEC, "動かせる形でない（${WasiP1Driver.START} がない）")
            WasiP1.checkImports(module)
            return WasiP1Driver(module)
        }
    }
}
