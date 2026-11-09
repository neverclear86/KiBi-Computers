package pro.ikili.kibicomputers.core.runtime

import org.jetbrains.annotations.ApiStatus

/**
 * インスタンスから取り出した実行の状態（#82、ADR 0082）。待機点（呼び出しの中でないとき）で [WasmInstance.captureState] が作り、
 * [WasmModule.instantiateForRestore] が戻す。不変で、配列は写しを返す。
 *
 * - 線形メモリ（0 番）: ページ数と、**書き換えた 4 KiB の区画**だけ（ROM の区画と書いていない区画は含めない。#370）
 * - 可変のグローバル（モジュールが定義したもの。export していないものも含む）
 * - テーブル（モジュールが定義したもの。funcref の関数の番号。`null` は -1）
 * - drop 済みのパッシブなデータ・要素のセグメント（ADR 0030 の結論 2）
 *
 * @property memoryPages 線形メモリのページ数。メモリがなければ -1
 * @property changedOnly 区画は基準（最後の全体保存）から変わったものだけ（#83。[StateCaptureMode.CHANGED]）。
 *   このままでは戻せず、基準と合成する（`InstanceStateMerge`）
 */
@ApiStatus.Internal
public class WasmInstanceState @JvmOverloads constructor(
    public val memoryPages: Int,
    writtenChunkIndices: IntArray,
    chunks: List<ByteArray>,
    globals: List<GlobalValue>,
    tables: List<TableState>,
    droppedDataSegments: IntArray,
    droppedElementSegments: IntArray,
    public val changedOnly: Boolean = false,
) {
    private val chunkIndices = writtenChunkIndices.copyOf()
    private val chunkData: List<ByteArray> = chunks.map { it.copyOf() }
    private val droppedData = droppedDataSegments.copyOf()
    private val droppedElements = droppedElementSegments.copyOf()

    /** 可変のグローバル（番号の順） */
    public val globals: List<GlobalValue> = globals.toList()

    /** テーブル（番号の順） */
    public val tables: List<TableState> = tables.toList()

    init {
        require(memoryPages >= -1) { "ページ数は -1 以上（$memoryPages）" }
        require(chunkIndices.size == chunkData.size) { "区画の番号と中身の数が合わない" }
        for (i in chunkIndices.indices) {
            require(chunkData[i].size == CHUNK_BYTES) { "区画は $CHUNK_BYTES バイト" }
            require(i == 0 || chunkIndices[i - 1] < chunkIndices[i]) { "区画の番号は昇順" }
            require(chunkIndices[i] >= 0 && chunkIndices[i].toLong() < maxOf(memoryPages, 0).toLong() * CHUNKS_PER_PAGE) { "区画 ${chunkIndices[i]} がメモリの外" }
        }
    }

    /** 書き換えた区画の番号（昇順。写し） */
    public val writtenChunkIndices: IntArray get() = chunkIndices.copyOf()

    /** 書き換えた区画の数 */
    public val writtenChunkCount: Int get() = chunkIndices.size

    /** [i] 番目（[writtenChunkIndices] の並びの位置）の区画の中身（[CHUNK_BYTES] バイトの写し） */
    public fun getChunk(i: Int): ByteArray = chunkData[i].copyOf()

    /** drop 済みのパッシブなデータセグメントの番号（写し） */
    public val droppedDataSegments: IntArray get() = droppedData.copyOf()

    /** drop 済みの要素セグメントの番号（写し） */
    public val droppedElementSegments: IntArray get() = droppedElements.copyOf()

    /** [i] 番目の区画の中身を写さずに渡す（合成用。#83） */
    internal fun chunkUnsafe(i: Int): ByteArray = chunkData[i]

    /** 区画の中身を写さずに渡す（符号化用） */
    internal fun forEachChunk(action: (index: Int, data: ByteArray) -> Unit) {
        for (i in chunkIndices.indices) action(chunkIndices[i], chunkData[i])
    }

    /**
     * 可変のグローバルの値。[bits] は値のビット（i32 は符号拡張した i64、f32・f64 はビットのまま）
     */
    public class GlobalValue(public val index: Int, public val type: WasmValueType, public val bits: Long) {
        init {
            require(index >= 0) { "グローバルの番号は 0 以上" }
            require(type != WasmValueType.UNSUPPORTED) { "数値の型だけ" }
        }

        override fun equals(other: Any?): Boolean = other is GlobalValue && index == other.index && type == other.type && bits == other.bits

        override fun hashCode(): Int = (index * 31 + type.hashCode()) * 31 + bits.hashCode()

        override fun toString(): String = "GlobalValue($index, $type, $bits)"
    }

    /** テーブルの中身。[funcIndices] は要素ごとの関数の番号（`null` は -1） */
    public class TableState(public val index: Int, funcIndices: IntArray) {
        private val elements = funcIndices.copyOf()

        init {
            require(index >= 0) { "テーブルの番号は 0 以上" }
        }

        /** 要素（写し） */
        public val funcIndices: IntArray get() = elements.copyOf()

        /** 要素の数 */
        public val size: Int get() = elements.size

        internal fun element(i: Int): Int = elements[i]

        override fun equals(other: Any?): Boolean = other is TableState && index == other.index && elements.contentEquals(other.elements)

        override fun hashCode(): Int = index * 31 + elements.contentHashCode()
    }

    public companion object {
        /** 区画の大きさ（4 KiB。#407 の数え方と同じ） */
        public const val CHUNK_BYTES: Int = 4096

        /** 1 ページの区画の数 */
        public const val CHUNKS_PER_PAGE: Int = WasmMemory.PAGE_SIZE / CHUNK_BYTES
    }
}

/**
 * インスタンスの状態を取り出せない作り（#82）。v128 のグローバル、参照型の可変のグローバル、externref のテーブル、
 * ほかのインスタンスの関数を指す要素、start 関数を持つモジュール（[WasmModule.instantiateForRestore] で start を飛ばせない）など。
 * 取り出す側（カーネル）は、そのプロセスを保存できないものとして扱う
 */
@ApiStatus.Internal
public class WasmStateException @JvmOverloads constructor(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * インスタンスの状態の取り出し方（#83、ADR 0083）
 */
@ApiStatus.Internal
public enum class StateCaptureMode {
    /** 書き換えた区画すべて。変わった区画の追跡は変えない */
    FULL,

    /** 書き換えた区画すべてを取り、それを基準にして、ここから変わった区画を追い始める（全体保存） */
    FULL_AND_TRACK,

    /** 最後の [FULL_AND_TRACK] から変わった区画だけ（[WasmInstanceState.changedOnly]）。追跡は変えない */
    CHANGED,
}

/**
 * 線形メモリの区画の数（#83。全体保存に切り替えるかを決めるのに使う）
 *
 * @property written 書き換えた区画（数えた区画）の数
 * @property changed そのうち、最後の [StateCaptureMode.FULL_AND_TRACK] から変わった区画の数
 */
@ApiStatus.Internal
public class MemoryChunkCounts(public val written: Int, public val changed: Int) {
    override fun toString(): String = "MemoryChunkCounts(written=$written, changed=$changed)"
}
