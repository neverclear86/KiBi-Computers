package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.fsError
import pro.ikili.kibicomputers.core.runtime.WasmExternKind
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1Driver

/** カーネルが自分で実行ファイルを動かすとき（起動の init など）のドライバーの選び方 */
internal object KernelDrivers {
    /**
     * 既定: `_start` を export する p1 のコマンドは [WasiP1Driver]、それ以外は `NOEXEC`。
     * p1 に許していない import（[WasiP1.checkImports]。#40）があっても `NOEXEC`。
     * Component Model は #259 の層が足す
     */
    val DEFAULT: ProcessDriverFactory = ProcessDriverFactory { program ->
        val module = program.module
        val isCommand = module.exports.any { it.name == WasiP1Driver.START && it.kind == WasmExternKind.FUNCTION }
        if (!isCommand) fsError(Errno.NOEXEC, "動かせる形でない（${WasiP1Driver.START} がない）")
        WasiP1.checkImports(module)
        WasiP1Driver(module)
    }
}
