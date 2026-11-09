package pro.ikili.kibicomputers.core.persist.snapshot

import pro.ikili.kibicomputers.core.persist.SnapshotFormatException
import pro.ikili.kibicomputers.core.runtime.WasmInstanceState
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmValueType

/**
 * インスタンスの状態（#82、ADR 0082 の 7.。#83、ADR 0083 で線形メモリの形を変えた）の形。
 *
 * - 線形メモリ（版 2。#83）: あるか（bool）、ページ数（i32）、形（u8。[FORM_FULL]・[FORM_CHANGED]）、区画の数（u32）、
 *   区画の番号の並び（i32 × 数。昇順）、圧縮の方式（u8。[ChunkCompression]）、圧縮した長さ（u32）、
 *   区画の中身を番号の順につないだもの（数 × 4,096 バイト）を方式で圧縮したもの
 * - 線形メモリ（版 1。読むだけ）: あるか（bool）、ページ数（i32）、書き換えた 4 KiB の区画の数（u32）と、番号の順に（区画の番号 i32 + 4,096 バイト）
 * - 可変のグローバル: 数（u32）と（番号 u32・型 u8・値 i64。f32・f64 はビットのまま）
 * - テーブル: 数（u32）と（番号 u32・要素の数 u32・要素 i32。`null` は -1）
 * - drop 済みのパッシブなデータ・要素のセグメントの番号（u32 + u32 の並び、2つ）
 *
 * 版は p1 の層の版（`SnapshotSection.P1_LAYER_VERSION`）と同じ。書くのは版 2 だけ。
 */
internal object InstanceStateCodec {
    /** テーブルの要素の数の上限（読むとき。守り） */
    private const val MAX_TABLE_ELEMENTS = 10_000_000

    /** 線形メモリの形: 書き換えた区画すべて */
    const val FORM_FULL: Int = 0

    /** 線形メモリの形: 基準（最後の全体保存）から変わった区画だけ（ファイルに `DELTA_BASE` 節が要る） */
    const val FORM_CHANGED: Int = 1

    /** 版 1（#82。区画をそのまま並べる） */
    const val VERSION_1: Int = 1

    /** 版 2（#83。形・圧縮） */
    const val VERSION_2: Int = 2

    fun write(out: SnapshotWriter, state: WasmInstanceState, level: Int = ChunkCompression.LEVEL) {
        val pages = state.memoryPages
        out.bool(pages >= 0)
        if (pages >= 0) {
            out.i32(pages)
            out.u8(if (state.changedOnly) FORM_CHANGED else FORM_FULL)
            out.count(state.writtenChunkCount)
            val chunks = ArrayList<ByteArray>(state.writtenChunkCount)
            state.forEachChunk { index, data ->
                out.i32(index)
                chunks += data
            }
            val compressed = ChunkCompression.compress(chunks, level)
            out.u8(compressed.method)
            out.count(compressed.length)
            val bytes = compressed.bytes
            if (bytes != null) out.raw(bytes, 0, compressed.length) else for (c in chunks) out.raw(c)
        } else {
            require(!state.changedOnly) { "メモリのない状態に差分の形はない" }
        }
        writeTail(out, state)
    }

    /** 版 1 の形で書く（テストで版 1 を読めることを確かめる用） */
    fun writeVersion1(out: SnapshotWriter, state: WasmInstanceState) {
        require(!state.changedOnly) { "版 1 に差分の形はない" }
        val pages = state.memoryPages
        out.bool(pages >= 0)
        if (pages >= 0) {
            out.i32(pages)
            out.count(state.writtenChunkCount)
            state.forEachChunk { index, data ->
                out.i32(index)
                out.raw(data)
            }
        }
        writeTail(out, state)
    }

    private fun writeTail(out: SnapshotWriter, state: WasmInstanceState) {
        out.count(state.globals.size)
        for (g in state.globals) {
            out.i32(g.index)
            out.u8(typeCode(g.type))
            out.i64(g.bits)
        }
        out.count(state.tables.size)
        for (t in state.tables) {
            out.i32(t.index)
            out.count(t.size)
            for (i in 0 until t.size) out.i32(t.element(i))
        }
        writeInts(out, state.droppedDataSegments)
        writeInts(out, state.droppedElementSegments)
    }

    /**
     * 版 [version]（[VERSION_1]・[VERSION_2]）で読む。区画の数は [budget] で数える（#83 の 7.。上限を超えれば
     * 大きな配列を確保せずに [SnapshotFormatException]）
     */
    @Throws(SnapshotFormatException::class)
    fun read(input: SnapshotReader, version: Int = VERSION_2, budget: ChunkReadBudget = ChunkReadBudget.unlimited()): WasmInstanceState {
        if (version != VERSION_1 && version != VERSION_2) throw SnapshotFormatException("インスタンスの状態の版 $version を知らない")
        var pages = -1
        var indices = IntArray(0)
        var chunks: List<ByteArray> = emptyList()
        var changedOnly = false
        if (input.bool()) {
            pages = input.i32()
            if (pages < 0 || pages > WasmMemory.MAX_PAGES) throw SnapshotFormatException("メモリのページ数 $pages が不正")
            val limit = pages.toLong() * WasmInstanceState.CHUNKS_PER_PAGE
            if (version == VERSION_1) {
                val n = input.count(Int.MAX_VALUE, 4 + WasmInstanceState.CHUNK_BYTES)
                budget.take(n, pages)
                indices = IntArray(n)
                val data = ArrayList<ByteArray>(n)
                var last = -1
                for (i in 0 until n) {
                    val index = input.i32()
                    if (index <= last || index.toLong() >= limit) throw SnapshotFormatException("メモリの区画 $index が不正")
                    last = index
                    indices[i] = index
                    data += input.raw(WasmInstanceState.CHUNK_BYTES)
                }
                chunks = data
            } else {
                changedOnly = when (val form = input.u8()) {
                    FORM_FULL -> false
                    FORM_CHANGED -> true
                    else -> throw SnapshotFormatException("メモリの形 $form が不正")
                }
                val n = input.count(Int.MAX_VALUE, 4)
                budget.take(n, pages)
                indices = IntArray(n)
                var last = -1
                for (i in 0 until n) {
                    val index = input.i32()
                    if (index <= last || index.toLong() >= limit) throw SnapshotFormatException("メモリの区画 $index が不正")
                    last = index
                    indices[i] = index
                }
                val method = input.u8()
                val length = input.count(Int.MAX_VALUE)
                chunks = ChunkCompression.decompress(input, method, length, n)
            }
        }
        val globalCount = input.count(Int.MAX_VALUE, 13)
        var lastGlobal = -1
        val globals = List(globalCount) {
            val index = input.i32()
            if (index <= lastGlobal) throw SnapshotFormatException("グローバルの番号 $index が不正")
            lastGlobal = index
            WasmInstanceState.GlobalValue(index, typeOf(input.u8()), input.i64())
        }
        val tableCount = input.count(Int.MAX_VALUE, 8)
        var lastTable = -1
        val tables = List(tableCount) {
            val index = input.i32()
            if (index <= lastTable) throw SnapshotFormatException("テーブルの番号 $index が不正")
            lastTable = index
            val n = input.count(MAX_TABLE_ELEMENTS, 4)
            val elements = IntArray(n) {
                val e = input.i32()
                if (e < -1) throw SnapshotFormatException("テーブル $index の要素 $e が不正")
                e
            }
            WasmInstanceState.TableState(index, elements)
        }
        val droppedData = readInts(input)
        val droppedElements = readInts(input)
        return WasmInstanceState(pages, indices, chunks, globals, tables, droppedData, droppedElements, changedOnly)
    }

    private fun writeInts(out: SnapshotWriter, values: IntArray) {
        out.count(values.size)
        for (v in values) out.i32(v)
    }

    private fun readInts(input: SnapshotReader): IntArray {
        val n = input.count(Int.MAX_VALUE, 4)
        var last = -1
        return IntArray(n) {
            val v = input.i32()
            if (v <= last) throw SnapshotFormatException("セグメントの番号 $v が不正")
            last = v
            v
        }
    }

    fun typeCode(type: WasmValueType): Int = when (type) {
        WasmValueType.I32 -> 0
        WasmValueType.I64 -> 1
        WasmValueType.F32 -> 2
        WasmValueType.F64 -> 3
        WasmValueType.UNSUPPORTED -> throw IllegalArgumentException("書けない型")
    }

    @Throws(SnapshotFormatException::class)
    fun typeOf(code: Int): WasmValueType = when (code) {
        0 -> WasmValueType.I32
        1 -> WasmValueType.I64
        2 -> WasmValueType.F32
        3 -> WasmValueType.F64
        else -> throw SnapshotFormatException("値の型 $code が不正")
    }
}
