package pro.ikili.kibicomputers.core.wasi.kibi

import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmMemoryAccessException

/**
 * 独自拡張 `kibi:computer/memory`（#408。ABI は #370 で決定）の意味。ABI の層（p1 の import、後の Component Model）に依らない部分。
 *
 * RAM は、初めて書き込んだ 4 KiB の区画の数で数える（#407）。
 * - `remaining() -> u64`: 残りの RAM（バイト）。プロセスの上限とマシンの RAM のうち、小さい方の残り
 * - `commit(ptr: u32, len: u32, keep: u64) -> bool`: `[ptr, ptr+len)` の区画を今のうちに数える。数えた後の残りが `keep` 未満になるなら、
 *   何も数えずに `false`。新しく数える区画がなければ `true`。範囲が線形メモリの外なら `false`
 *
 * 燃料（仮の値。確定は #251）: 呼び出しごとの費用は ABI の層が払い、`commit` は範囲に収まったとき、範囲にかかる 4 KiB の区画の数（[commitFuel]）も払う。
 */
internal object KibiMemory {
    /** 区画の大きさ（4 KiB。RAM を数える単位） */
    private const val CHUNK_SHIFT = 12

    /** `remaining` */
    fun remaining(ctx: ProcessContext): Long = ctx.memoryRemaining()

    /** `commit`。範囲外は `false`（RAM の上限で断ったことにはしない） */
    fun commit(ctx: ProcessContext, memory: WasmMemory, ptr: Int, len: Int, keep: Long): Boolean {
        val committed = try {
            ctx.commitMemory(memory, ptr, len, keep)
        } catch (e: WasmMemoryAccessException) {
            // 範囲外は区画を引かないので、区画の分の燃料は払わない
            return false
        }
        ctx.chargeFuel(commitFuel(ptr, len))
        return committed
    }

    /** `commit` が範囲の区画を引く分の燃料（範囲にかかる 4 KiB の区画の数。符号なし 32 ビットで計算する） */
    fun commitFuel(ptr: Int, len: Int): Long {
        val length = len.toUInt().toLong()
        if (length == 0L) return 0
        val start = ptr.toUInt().toLong()
        return ((start + length - 1) ushr CHUNK_SHIFT) - (start ushr CHUNK_SHIFT) + 1
    }
}
