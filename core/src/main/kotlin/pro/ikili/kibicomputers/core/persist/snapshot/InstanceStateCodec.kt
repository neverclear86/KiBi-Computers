package pro.ikili.kibicomputers.core.persist.snapshot

import pro.ikili.kibicomputers.core.persist.SnapshotFormatException
import pro.ikili.kibicomputers.core.runtime.WasmInstanceState
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmValueType

/**
 * インスタンスの状態（#82、ADR 0082 の 7.）の形。
 *
 * - 線形メモリ: あるか（bool）、ページ数（i32）、書き換えた 4 KiB の区画の数（u32）と、番号の順に（区画の番号 i32 + 4,096 バイト）
 * - 可変のグローバル: 数（u32）と（番号 u32・型 u8・値 i64。f32・f64 はビットのまま）
 * - テーブル: 数（u32）と（番号 u32・要素の数 u32・要素 i32。`null` は -1）
 * - drop 済みのパッシブなデータ・要素のセグメントの番号（u32 + u32 の並び、2つ）
 */
internal object InstanceStateCodec {
    /** テーブルの要素の数の上限（読むとき。守り） */
    private const val MAX_TABLE_ELEMENTS = 10_000_000

    fun write(out: SnapshotWriter, state: WasmInstanceState) {
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

    @Throws(SnapshotFormatException::class)
    fun read(input: SnapshotReader): WasmInstanceState {
        var pages = -1
        var indices = IntArray(0)
        var chunks: List<ByteArray> = emptyList()
        if (input.bool()) {
            pages = input.i32()
            if (pages < 0 || pages > WasmMemory.MAX_PAGES) throw SnapshotFormatException("メモリのページ数 $pages が不正")
            val n = input.count(Int.MAX_VALUE, 4 + WasmInstanceState.CHUNK_BYTES)
            val limit = pages.toLong() * WasmInstanceState.CHUNKS_PER_PAGE
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
        return WasmInstanceState(pages, indices, chunks, globals, tables, droppedData, droppedElements)
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
