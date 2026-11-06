package pro.ikili.kibicomputers.core.wasi.kibi

import pro.ikili.kibicomputers.core.kernel.ResourceLimitException
import pro.ikili.kibicomputers.core.kernel.event.EventBatch
import pro.ikili.kibicomputers.core.kernel.event.EventFilter
import pro.ikili.kibicomputers.core.kernel.event.EventLimits
import pro.ikili.kibicomputers.core.kernel.event.EventQueue
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.fsError

/**
 * 独自拡張 `kibi:kernel/events`（#73。ADR 0073）の意味。ABI の層（p1 の import、後の Component Model）に依らない部分。
 *
 * - `[static]event-queue.open(kinds, capacity)`: [open]
 * - `[method]event-queue.take(max)`: [take]
 * - `[resource-drop]event-queue`: [drop]
 *
 * ゲストの入力は、カーネルの `EventFilter`・`EventQueue` の `require` より前にここで確かめ、外れれば `INVAL` にする
 * （ゲストの入力で `IllegalArgumentException` が出ないように）。上限の値は [EventLimits] と共通。
 */
internal object KibiEvents {
    /**
     * キューを開いて、そのハンドル（= fd）を返す。[kinds] が空ならすべての種類。[capacity] は `u32` を符号なしで広げた値（`null` なら既定）。
     * 確かめる順は kinds の数 → 各要素のバイト数 → capacity。外れれば `INVAL`。マシンのキューの数が上限なら `NFILE`、
     * ハンドル表が満杯なら `MFILE`
     */
    @Throws(FsException::class)
    fun open(ctx: ProcessContext, kinds: List<String>, capacity: Long?): Int {
        if (kinds.size > EventLimits.MAX_FILTER_TYPES) fsError(Errno.INVAL, "kinds は ${EventLimits.MAX_FILTER_TYPES} 個まで（${kinds.size}）")
        for (k in kinds) {
            val n = k.toByteArray(Charsets.UTF_8).size
            if (n !in 1..EventLimits.MAX_TYPE_BYTES) fsError(Errno.INVAL, "kind は 1..${EventLimits.MAX_TYPE_BYTES} バイト（$n）")
        }
        val cap = capacity ?: EventLimits.DEFAULT_CAPACITY.toLong()
        if (cap !in 1L..EventLimits.MAX_CAPACITY.toLong()) fsError(Errno.INVAL, "capacity は 1..${EventLimits.MAX_CAPACITY}（$cap）")
        return try {
            ctx.openEventQueue(EventFilter(kinds.toSet()), cap.toInt())
        } catch (e: ResourceLimitException) {
            fsError(Errno.NFILE, e.message)
        }
    }

    /**
     * キュー [handle] から待たずに最大 [max] 個取り出す。[max] は `u32` を符号なしで広げた値（0..2^32-1）で、0 なら `INVAL`。
     * `Int.MAX_VALUE` で頭打ちにする（それ以上は「全部」と同じ）。キューでなければ `BADF`
     */
    @Throws(FsException::class)
    fun take(ctx: ProcessContext, handle: Int, max: Long): EventBatch {
        if (max < 1) fsError(Errno.INVAL, "max は 1 以上（$max）")
        return ctx.takeEvents(handle, max.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    /** キュー [handle] を閉じる（`[resource-drop]`）。開いていない・キューでなければ `BADF`（p1 の層がトラップにする） */
    @Throws(FsException::class)
    fun drop(ctx: ProcessContext, handle: Int) {
        ctx.withKernelLock {
            val table = FdTable(checkNotNull(ctx.kernel.processes[ctx.pid]), ctx.kernel.objects, ctx.wakeSink)
            table.objectOf(handle) as? EventQueue ?: fsError(Errno.BADF, "fd $handle はイベントキューではない")
            table.close(handle)
        }
    }
}
