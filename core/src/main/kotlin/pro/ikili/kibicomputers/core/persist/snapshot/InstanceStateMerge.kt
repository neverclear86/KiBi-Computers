package pro.ikili.kibicomputers.core.persist.snapshot

import pro.ikili.kibicomputers.core.persist.SnapshotFormatException
import pro.ikili.kibicomputers.core.runtime.WasmInstanceState

/**
 * 基準（全体の形）と差分（変わった区画だけ）のインスタンスの状態を合成する（#83、ADR 0083）。
 *
 * - ページ数は差分のもの（基準以上でなければ壊れた保存データ）
 * - 区画は「基準 ∪ 差分」で、同じ番号は差分が勝つ
 * - グローバル・テーブル・drop 済みのセグメントは差分のもの
 * - 合成の後の区画の数も [maxChunks] 以下（上限を超えれば壊れた保存データ）
 */
internal object InstanceStateMerge {
    @Throws(SnapshotFormatException::class)
    fun merge(base: WasmInstanceState, delta: WasmInstanceState, maxChunks: Long): WasmInstanceState {
        if (base.changedOnly) throw SnapshotFormatException("基準が差分の形")
        if (!delta.changedOnly) throw SnapshotFormatException("差分が全体の形")
        // 差分の形はメモリのある状態だけ（#562。WasmInstanceState が保つ）。基準にもメモリが要る
        if (base.memoryPages < 0) throw SnapshotFormatException("基準と差分でメモリの有無が違う")
        if (delta.memoryPages < base.memoryPages) {
            throw SnapshotFormatException("差分のページ数 ${delta.memoryPages} が基準 ${base.memoryPages} より少ない")
        }
        val indices = ArrayList<Int>(base.writtenChunkCount + delta.writtenChunkCount)
        val data = ArrayList<ByteArray>(base.writtenChunkCount + delta.writtenChunkCount)
        val baseIdx = base.writtenChunkIndices
        val deltaIdx = delta.writtenChunkIndices
        var i = 0
        var j = 0
        while (i < baseIdx.size || j < deltaIdx.size) {
            val b = if (i < baseIdx.size) baseIdx[i] else Int.MAX_VALUE
            val d = if (j < deltaIdx.size) deltaIdx[j] else Int.MAX_VALUE
            when {
                d <= b -> {
                    indices += d
                    data += delta.chunkUnsafe(j)
                    j++
                    if (d == b) i++
                }
                else -> {
                    indices += b
                    data += base.chunkUnsafe(i)
                    i++
                }
            }
            if (indices.size > maxChunks) throw SnapshotFormatException("合成した区画の数が上限 $maxChunks を超える")
        }
        return WasmInstanceState(
            delta.memoryPages,
            indices.toIntArray(),
            data,
            delta.globals,
            delta.tables,
            delta.droppedDataSegments,
            delta.droppedElementSegments,
        )
    }
}
