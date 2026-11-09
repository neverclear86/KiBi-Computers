package pro.ikili.kibicomputers.core.persist.snapshot

import pro.ikili.kibicomputers.core.persist.SnapshotFormatException
import pro.ikili.kibicomputers.core.runtime.WasmInstanceState
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * インスタンスの状態の区画の中身の圧縮（#83、ADR 0083）。JDK の Deflate（raw = `nowrap`）。
 *
 * 方式の番号は u8 で書く（[METHOD_NONE]・[METHOD_DEFLATE]。ほかの値は壊れた保存データ）。後で方式を足すときに版を上げずに済む。
 * レベルは書かない（読む側には要らない）。
 */
internal object ChunkCompression {
    /** 圧縮しない */
    const val METHOD_NONE: Int = 0

    /** Deflate（raw） */
    const val METHOD_DEFLATE: Int = 1

    /**
     * 圧縮のレベル（#83）。#84 までは符号化がメインスレッドなので、速い 1（`BEST_SPEED`）。
     * ADR 0030 のレベル 6 は大きなメモリで秒単位になる
     */
    const val LEVEL: Int = Deflater.BEST_SPEED

    private const val CHUNK = WasmInstanceState.CHUNK_BYTES

    /** 圧縮した結果。[method] と、中身の [length] バイト（[METHOD_NONE] なら [bytes] は `null` で、区画をそのままつなぐ） */
    class Compressed(val method: Int, val bytes: ByteArray?, val length: Int)

    /**
     * [chunks]（それぞれ [CHUNK] バイト）をつないだものを圧縮する。圧縮した方が小さくなければ [METHOD_NONE] でそのまま
     */
    fun compress(chunks: List<ByteArray>, level: Int = LEVEL): Compressed {
        if (chunks.isEmpty()) return Compressed(METHOD_NONE, null, 0)
        val rawSize = chunks.size.toLong() * CHUNK
        val deflater = Deflater(level, true)
        try {
            val out = java.io.ByteArrayOutputStream(minOf(rawSize, 1L shl 16).toInt())
            val buf = ByteArray(16 * 1024)
            for (chunk in chunks) {
                deflater.setInput(chunk)
                while (!deflater.needsInput()) {
                    out.write(buf, 0, deflater.deflate(buf))
                    // 元より大きくなるなら、そこでやめる（そのまま書く）
                    if (out.size() >= rawSize) return uncompressed(chunks)
                }
            }
            deflater.finish()
            while (!deflater.finished()) {
                out.write(buf, 0, deflater.deflate(buf))
                if (out.size() >= rawSize) return uncompressed(chunks)
            }
            val bytes = out.toByteArray()
            return Compressed(METHOD_DEFLATE, bytes, bytes.size)
        } finally {
            deflater.end()
        }
    }

    private fun uncompressed(chunks: List<ByteArray>): Compressed {
        require(chunks.size <= Int.MAX_VALUE / CHUNK) { "区画が多すぎる（${chunks.size}）" }
        return Compressed(METHOD_NONE, null, chunks.size * CHUNK)
    }

    /**
     * [input] から、方式 [method] で圧縮した [compressedLength] バイトを読み、[count] 個の区画に伸ばす。
     * 区画ごとに [CHUNK] バイトの配列を確保してちょうど埋まるまで伸ばす（ひとつながりの「数 × 4,096」の配列は作らない）。
     * 埋まる前に終わる・伸ばし終わった後に入力が余る・余分に伸びる（爆弾）・知らない方式なら [SnapshotFormatException]
     */
    @Throws(SnapshotFormatException::class)
    fun decompress(input: SnapshotReader, method: Int, compressedLength: Int, count: Int): List<ByteArray> {
        if (compressedLength < 0 || compressedLength > input.remaining) {
            throw SnapshotFormatException("圧縮した長さ $compressedLength が残りの長さ ${input.remaining} を超える")
        }
        return when (method) {
            METHOD_NONE -> {
                if (compressedLength.toLong() != count.toLong() * CHUNK) {
                    throw SnapshotFormatException("圧縮していない区画の長さ $compressedLength が区画の数 $count と合わない")
                }
                List(count) { input.raw(CHUNK) }
            }
            METHOD_DEFLATE -> input.region(compressedLength) { bytes, offset -> inflate(bytes, offset, compressedLength, count) }
            else -> throw SnapshotFormatException("圧縮の方式 $method を知らない")
        }
    }

    private fun inflate(bytes: ByteArray, offset: Int, length: Int, count: Int): List<ByteArray> {
        val inflater = Inflater(true)
        try {
            inflater.setInput(bytes, offset, length)
            val result = ArrayList<ByteArray>(minOf(count, 1024))
            for (i in 0 until count) {
                val chunk = ByteArray(CHUNK)
                var filled = 0
                while (filled < CHUNK) {
                    val n = inflater.inflate(chunk, filled, CHUNK - filled)
                    if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) {
                        throw SnapshotFormatException("圧縮した区画が途中で終わる（区画 $i）")
                    }
                    filled += n
                }
                result += chunk
            }
            if (!inflater.finished()) {
                // 区画の数で決まる長さを伸ばし終えたのに、まだ終わっていない: 余分に伸びるか、終わりの印だけが残っている
                val extra = ByteArray(1)
                val n = inflater.inflate(extra)
                if (n != 0 || !inflater.finished()) throw SnapshotFormatException("圧縮した区画が区画の数より長い")
            }
            if (inflater.remaining != 0) throw SnapshotFormatException("圧縮した区画の後に ${inflater.remaining} バイト余っている")
            return result
        } catch (e: DataFormatException) {
            throw SnapshotFormatException("圧縮した区画が壊れている", e)
        } finally {
            inflater.end()
        }
    }
}

/**
 * 伸ばすときの区画の数の上限（#83、ADR 0083 の 7.）。1つの保存データの中のプロセスの区画の数の合計が [limitChunks] を超えない
 * （書き換えた区画はすべてマシンの RAM に数えているので、正しい保存データでは必ず収まる）。
 * 1つのプロセスの上限は `min(ページ数 × 16, limitChunks)`。
 *
 * @param limitChunks マシンの RAM の上限 ÷ 4,096
 */
internal class ChunkReadBudget(val limitChunks: Long) {
    init {
        require(limitChunks >= 0) { "上限は 0 以上" }
    }

    /** 使った区画の数 */
    var used: Long = 0
        private set

    /** ページ数 [pages] のメモリの [count] 個の区画を数える。上限を超えるなら [SnapshotFormatException] */
    @Throws(SnapshotFormatException::class)
    fun take(count: Int, pages: Int) {
        val perProcess = minOf(pages.toLong() * WasmInstanceState.CHUNKS_PER_PAGE, limitChunks)
        if (count < 0 || count > perProcess) throw SnapshotFormatException("区画の数 $count が上限 $perProcess を超える")
        if (used + count > limitChunks) throw SnapshotFormatException("区画の数の合計 ${used + count} が上限 $limitChunks を超える")
        used += count
    }

    companion object {
        /** ページ数 [maxMemoryPages] のマシンの上限 */
        fun forPages(maxMemoryPages: Int): ChunkReadBudget = ChunkReadBudget(maxOf(maxMemoryPages, 0).toLong() * WasmInstanceState.CHUNKS_PER_PAGE)

        /** 上限なし（ページ数の上限 [pro.ikili.kibicomputers.core.runtime.WasmMemory.MAX_PAGES] だけ） */
        fun unlimited(): ChunkReadBudget = forPages(pro.ikili.kibicomputers.core.runtime.WasmMemory.MAX_PAGES)
    }
}
