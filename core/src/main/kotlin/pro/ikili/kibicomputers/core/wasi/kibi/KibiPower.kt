package pro.ikili.kibicomputers.core.wasi.kibi

import pro.ikili.kibicomputers.core.kernel.ProcessKilledException
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.vfs.Errno

/**
 * 独自拡張 `kibi:computer/power`（#306。ADR 0306）の意味。ABI の層に依らない部分。どちらも待たない。
 *
 * - `shutdown`: [shutdown]。戻らない
 * - `reboot`: [reboot]。受け付けたら戻らない。断ったら `not-supported`
 *
 * どのプロセスからでも呼べる（ADR 0046。マシンの中では権限を分けない）。止め方・再起動の順番は ADR 0049 のまま。
 */
internal object KibiPower {
    /** マシンを止める。呼んだプロセスも止まるので、[ProcessKilledException] を投げて戻らない */
    @Throws(ProcessKilledException::class)
    fun shutdown(ctx: ProcessContext): Nothing = ctx.requestShutdown()

    /**
     * マシンを再起動する。受け付けたら [ProcessKilledException] を投げて戻らない。
     * 断ったら `not-supported` の `error-code` の判別子（起動も復元も通らずに作ったマシン、スケジューラを閉じる途中）
     */
    @Throws(ProcessKilledException::class)
    fun reboot(ctx: ProcessContext): Int {
        check(!ctx.requestReboot()) { "受け付けた requestReboot は戻らない" }
        return KibiErrors.code(Errno.NOTSUP)
    }
}
