package pro.ikili.kibicomputers.core.wasi.kibi

import pro.ikili.kibicomputers.core.kernel.ResourceLimitException
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.time.Timer
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.fsError

/**
 * 独自拡張 `kibi:kernel/timers`（#426。ADR 0426）の意味。ABI の層（p1 の import、後の Component Model）に依らない部分。
 *
 * - `[static]timer.create(events)`: [create]
 * - `[method]timer.set-after(duration, interval)`・`[method]timer.set-at(instant, interval)`: [set]
 * - `[method]timer.cancel()`: [cancel]
 * - `[method]timer.read()`: [read]
 * - `[resource-drop]timer`: [drop]
 *
 * どれもカーネルのタイマー（`ctx.createTimer` など。#64、ADR 0064）をそのまま写す。時間の値は、呼ぶ側が `u64` を
 * 0..`Long.MAX_VALUE` に頭打ちにしてから渡す（カーネルは期限を `saturatingAdd` で出すので溢れない）。
 * 失敗は [FsException]（`BADF`・`INVAL`・`MFILE`・`NFILE`）。
 */
internal object KibiTimers {
    /**
     * 止まった状態のタイマーを作り、そのハンドル（= fd）を返す。[events] は (キューのハンドル, tag)。`null` ならイベントを出さない。
     * キューでない・開いていなければ `BADF`、キューが `timer` を受け取らなければ `INVAL`、ハンドル表が満杯なら `MFILE`
     */
    @Throws(FsException::class)
    fun create(ctx: ProcessContext, events: Pair<Int, Long>?): Int =
        if (events == null) ctx.createTimer() else ctx.createTimer(events.first, events.second)

    /**
     * タイマー [handle] を設定する（`set-after` は [absolute] が `false`、`set-at` は `true`）。[time]・[interval] は 0..`Long.MAX_VALUE`。
     * タイマーでなければ `BADF`、[interval] が 0 でなく最小の間隔より短ければ `INVAL`、マシンの動いているタイマーが上限なら `NFILE`
     */
    @Throws(FsException::class)
    fun set(ctx: ProcessContext, handle: Int, time: Long, interval: Long, absolute: Boolean) {
        try {
            ctx.setTimer(handle, time, interval, absolute)
        } catch (e: ResourceLimitException) {
            fsError(Errno.NFILE, e.message)
        }
    }

    /** タイマー [handle] を止める（回数も 0 に戻す）。タイマーでなければ `BADF` */
    @Throws(FsException::class)
    fun cancel(ctx: ProcessContext, handle: Int) {
        ctx.cancelTimer(handle)
    }

    /** タイマー [handle] の前回から鳴った回数を取り出して 0 に戻す。待たない。タイマーでなければ `BADF` */
    @Throws(FsException::class)
    fun read(ctx: ProcessContext, handle: Int): Long = ctx.readTimer(handle)

    /** タイマー [handle] を閉じる（`[resource-drop]`）。開いていない・タイマーでなければ `BADF`（p1 の層がトラップにする） */
    @Throws(FsException::class)
    fun drop(ctx: ProcessContext, handle: Int) {
        ctx.withKernelLock {
            val table = FdTable(checkNotNull(ctx.kernel.processes[ctx.pid]), ctx.kernel.objects, ctx.wakeSink)
            table.objectOf(handle) as? Timer ?: fsError(Errno.BADF, "fd $handle はタイマーではない")
            table.close(handle)
        }
    }
}
