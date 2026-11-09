package pro.ikili.kibicomputers.core.runtime.endive

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.persist.SnapshotFormatException
import pro.ikili.kibicomputers.core.persist.snapshot.ChunkCompression
import pro.ikili.kibicomputers.core.persist.snapshot.ChunkReadBudget
import pro.ikili.kibicomputers.core.persist.snapshot.InstanceStateCodec
import pro.ikili.kibicomputers.core.persist.snapshot.InstanceStateMerge
import pro.ikili.kibicomputers.core.persist.snapshot.SnapshotReader
import pro.ikili.kibicomputers.core.persist.snapshot.SnapshotWriter
import pro.ikili.kibicomputers.core.runtime.StateCaptureMode
import pro.ikili.kibicomputers.core.runtime.WasmBinary
import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.I32
import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.I64
import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.code
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmInstanceState
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmTableBudget
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import java.util.zip.Deflater
import kotlin.random.Random

/** 差分の取り出し・版 2 の形・圧縮・合成（#83、ADR 0083） */
class DeltaStateTest {
    private class Budget : WasmTableBudget {
        override fun tryReserveBytes(bytes: Long): Boolean = true

        override fun tryReserveBytes(bytes: Long, keepBytes: Long): Boolean = true

        override fun releaseBytes(bytes: Long) {}
    }

    private val chunk = WasmInstanceState.CHUNK_BYTES

    /** export: store(addr, v)・load(addr)（i64）、store32(addr, v)・load32(addr)、inc・get（i64 のグローバル） */
    private fun module(pages: Int = 2): ByteArray {
        val b = WasmBinary()
        b.memory(pages)
        val g = b.mutableI64Global(0)
        b.exportFunc("store", b.func(listOf(I32, I64), emptyList(), code(0x20, 0, 0x20, 1, 0x37, 0x03, 0x00)))
        b.exportFunc("load", b.func(listOf(I32), listOf(I64), code(0x20, 0, 0x29, 0x03, 0x00)))
        b.exportFunc("store32", b.func(listOf(I32, I32), emptyList(), code(0x20, 0, 0x20, 1, 0x36, 0x02, 0x00)))
        b.exportFunc("load32", b.func(listOf(I32), listOf(I32), code(0x20, 0, 0x28, 0x02, 0x00)))
        b.exportFunc("inc", b.func(emptyList(), emptyList(), code(0x23, g, 0x42, 1, 0x7c, 0x24, g)))
        b.exportFunc("get", b.func(emptyList(), listOf(I64), code(0x23, g)))
        return b.build()
    }

    private fun instance(pages: Int = 2): WasmInstance =
        EndiveRuntime().load(module(pages)).instantiate(WasmInstanceConfig.builder().writtenMemoryLimit(64, Budget()).build())

    private fun roundTrip(state: WasmInstanceState, level: Int = ChunkCompression.LEVEL): WasmInstanceState {
        val out = SnapshotWriter()
        InstanceStateCodec.write(out, state, level)
        val input = SnapshotReader(out.toByteArray())
        return InstanceStateCodec.read(input).also { input.expectEnd() }
    }

    private fun assertSameState(expected: WasmInstanceState, actual: WasmInstanceState) {
        assertEquals(expected.memoryPages, actual.memoryPages)
        assertEquals(expected.changedOnly, actual.changedOnly)
        assertArrayEquals(expected.writtenChunkIndices, actual.writtenChunkIndices)
        for (i in 0 until expected.writtenChunkCount) assertArrayEquals(expected.getChunk(i), actual.getChunk(i), "区画 $i")
        assertEquals(expected.globals, actual.globals)
        assertEquals(expected.tables, actual.tables)
    }

    @Test
    fun `FULL_AND_TRACKの後もWASMから読む値は変わらず、CHANGEDは書いた区画だけを返す`() {
        instance().use { inst ->
            assertTrue(inst.tracksChanges)
            inst.call("store", 8, 0x0102030405060708L)
            inst.call("store32", (chunk * 2 + 4).toLong(), 0x11223344)
            inst.call("store", (chunk * 3 - 4).toLong(), -5) // 区画の境をまたぐ
            val full = inst.captureState(StateCaptureMode.FULL_AND_TRACK)
            assertFalse(full.changedOnly)
            assertArrayEquals(intArrayOf(0, 2, 3), full.writtenChunkIndices)
            // バイト順: WASM から読み直しても同じ値
            assertEquals(0x0102030405060708L, inst.call("load", 8)[0])
            assertEquals(0x11223344L, inst.call("load32", (chunk * 2 + 4).toLong())[0])
            assertEquals(-5L, inst.call("load", (chunk * 3 - 4).toLong())[0])
            // 読んで書き戻す
            inst.call("store", 8, inst.call("load", 8)[0])
            assertEquals(0x0102030405060708L, inst.call("load", 8)[0])
            assertEquals(3, inst.memoryChunkCounts()!!.written)
            assertEquals(1, inst.memoryChunkCounts()!!.changed)

            inst.call("inc")
            inst.call("store32", (chunk * 5).toLong(), 1)
            val changed = inst.captureState(StateCaptureMode.CHANGED)
            assertTrue(changed.changedOnly)
            assertArrayEquals(intArrayOf(0, 5), changed.writtenChunkIndices)
            assertEquals(listOf(WasmInstanceState.GlobalValue(0, WasmValueType.I64, 1)), changed.globals)
            // FULL は追跡を変えない
            val fullAgain = inst.captureState(StateCaptureMode.FULL)
            assertArrayEquals(intArrayOf(0, 2, 3, 5), fullAgain.writtenChunkIndices)
            assertArrayEquals(intArrayOf(0, 5), inst.captureState(StateCaptureMode.CHANGED).writtenChunkIndices)

            // 合成は同じ時点の全体と一致する
            assertSameState(fullAgain, InstanceStateMerge.merge(full, changed, Long.MAX_VALUE))
        }
    }

    @Test
    fun `版2の往復（全体・差分・圧縮あり・なし）と版1の読み`() {
        instance().use { inst ->
            inst.call("store", 8, 42)
            Random(1).nextBytes(ByteArray(chunk)).forEachIndexed { i, v -> if (i % 8 == 0) inst.call("store", (chunk + i).toLong(), v.toLong()) }
            val full = inst.captureState(StateCaptureMode.FULL_AND_TRACK)
            inst.call("store", 16, 7)
            val delta = inst.captureState(StateCaptureMode.CHANGED)
            for (level in listOf(Deflater.NO_COMPRESSION, Deflater.BEST_SPEED, 6)) {
                assertSameState(full, roundTrip(full, level))
                assertSameState(delta, roundTrip(delta, level))
            }
            // 版 1 も読める（全体・圧縮なし）
            val out = SnapshotWriter()
            InstanceStateCodec.writeVersion1(out, full)
            val input = SnapshotReader(out.toByteArray())
            assertSameState(full, InstanceStateCodec.read(input, InstanceStateCodec.VERSION_1))
            input.expectEnd()
        }
        // 区画なし・メモリなし
        val empty = WasmInstanceState(1, IntArray(0), emptyList(), emptyList(), emptyList(), IntArray(0), IntArray(0))
        assertSameState(empty, roundTrip(empty))
        val noMemory = WasmInstanceState(-1, IntArray(0), emptyList(), emptyList(), emptyList(), IntArray(0), IntArray(0))
        assertSameState(noMemory, roundTrip(noMemory))
    }

    /** ページ数 [pages]、区画の番号 [indices]、方式 [method]・長さ [length]・中身 [body] の版 2 のメモリの部分（後ろは空） */
    private fun rawState(pages: Int, indices: IntArray, method: Int, length: Int, body: ByteArray, form: Int = 0): ByteArray {
        val out = SnapshotWriter()
        out.bool(true)
        out.i32(pages)
        out.u8(form)
        out.count(indices.size)
        for (i in indices) out.i32(i)
        out.u8(method)
        out.count(length)
        out.raw(body)
        repeat(4) { out.count(0) }
        return out.toByteArray()
    }

    private fun deflate(bytes: ByteArray, finish: Boolean = true): ByteArray {
        val d = Deflater(Deflater.BEST_SPEED, true)
        d.setInput(bytes)
        if (finish) d.finish()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(65536)
        if (finish) {
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        } else {
            while (!d.needsInput()) out.write(buf, 0, d.deflate(buf, 0, buf.size, Deflater.SYNC_FLUSH))
        }
        d.end()
        return out.toByteArray()
    }

    private fun read(bytes: ByteArray, budget: ChunkReadBudget = ChunkReadBudget.unlimited()) =
        InstanceStateCodec.read(SnapshotReader(bytes), InstanceStateCodec.VERSION_2, budget)

    @Test
    fun `壊れた版2は SnapshotFormatException`() {
        val two = deflate(ByteArray(2 * chunk) { 3 })
        // 正しいもの
        assertEquals(2, read(rawState(1, intArrayOf(0, 1), 1, two.size, two)).writtenChunkCount)
        // 圧縮した長さが足りない
        assertThrows<SnapshotFormatException> { read(rawState(1, intArrayOf(0, 1), 1, two.size - 2, two.copyOf(two.size - 2))) }
        // 余る
        assertThrows<SnapshotFormatException> { read(rawState(1, intArrayOf(0, 1), 1, two.size + 3, two + byteArrayOf(1, 2, 3))) }
        // 伸ばした長さが違う（区画 1 つ分しかない・3 つ分ある）
        val one = deflate(ByteArray(chunk) { 3 })
        assertThrows<SnapshotFormatException> { read(rawState(1, intArrayOf(0, 1), 1, one.size, one)) }
        val three = deflate(ByteArray(3 * chunk) { 3 })
        assertThrows<SnapshotFormatException> { read(rawState(1, intArrayOf(0, 1), 1, three.size, three)) }
        // 終わりの印がない
        val open = deflate(ByteArray(2 * chunk) { 3 }, finish = false)
        assertThrows<SnapshotFormatException> { read(rawState(1, intArrayOf(0, 1), 1, open.size, open)) }
        // 方式の番号を知らない
        assertThrows<SnapshotFormatException> { read(rawState(1, intArrayOf(0, 1), 9, two.size, two)) }
        // 方式 0 の長さが合わない
        assertThrows<SnapshotFormatException> { read(rawState(1, intArrayOf(0), 0, chunk - 1, ByteArray(chunk - 1))) }
        // 形の番号を知らない
        assertThrows<SnapshotFormatException> { read(rawState(1, intArrayOf(0, 1), 1, two.size, two, form = 2)) }
        // 番号が昇順でない・ページの外
        assertThrows<SnapshotFormatException> { read(rawState(1, intArrayOf(1, 0), 1, two.size, two)) }
        assertThrows<SnapshotFormatException> { read(rawState(1, intArrayOf(0, 16), 1, two.size, two)) }
    }

    @Test
    fun `伸ばすときの上限、区画の数がRAMの上限を超えるものは大きな配列を確保せずに拒む`() {
        // ページ数 65,536・区画の数 1,048,576・中身は数バイト
        val huge = 1_048_576
        val bytes = SnapshotWriter().apply {
            bool(true)
            i32(WasmMemory.MAX_PAGES)
            u8(0)
            count(huge)
            for (i in 0 until huge) i32(i)
            u8(1)
            val body = deflate(ByteArray(16))
            count(body.size)
            raw(body)
            repeat(4) { count(0) }
        }.toByteArray()
        // RAM の上限 16 ページ（256 区画）
        assertThrows<SnapshotFormatException> { read(bytes, ChunkReadBudget.forPages(16)) }
        // 上限が足りても、中身が短ければ区画ごとに伸ばすところで止まる（数 × 4,096 は 4 GiB で Int を超えるが溢れない）
        assertThrows<SnapshotFormatException> { read(bytes, ChunkReadBudget.unlimited()) }

        // 複数のプロセスの合計が上限を超える
        val one = deflate(ByteArray(chunk * 10))
        val ten = rawState(1, IntArray(10) { it }, 1, one.size, one)
        val budget = ChunkReadBudget.forPages(1) // 16 区画
        read(ten, budget)
        assertThrows<SnapshotFormatException> { read(ten, budget) }

        // 少ないデータが大きく伸びる（爆弾）: 区画 2 つ分のところに 1 MiB のゼロ
        val bomb = deflate(ByteArray(1 shl 20))
        assertTrue(bomb.size < 16 * 1024)
        assertThrows<SnapshotFormatException> { read(rawState(1, intArrayOf(0, 1), 1, bomb.size, bomb)) }
    }

    @Test
    fun `合成、ページが増えた・差分が上書き・基準にない区画・ページが減った差分と上限を超えたものは拒む`() {
        fun state(pages: Int, changed: Boolean, vararg chunks: Pair<Int, Byte>) = WasmInstanceState(
            pages, chunks.map { it.first }.toIntArray(), chunks.map { c -> ByteArray(chunk) { c.second } },
            emptyList(), emptyList(), IntArray(0), IntArray(0), changed,
        )
        val base = state(1, false, 0 to 1, 3 to 2)
        val delta = state(2, true, 3 to 9, 20 to 7)
        val merged = InstanceStateMerge.merge(base, delta, 100)
        assertEquals(2, merged.memoryPages)
        assertFalse(merged.changedOnly)
        assertArrayEquals(intArrayOf(0, 3, 20), merged.writtenChunkIndices)
        assertEquals(1, merged.getChunk(0)[0])
        assertEquals(9, merged.getChunk(1)[0])
        assertEquals(7, merged.getChunk(2)[0])
        assertThrows<SnapshotFormatException> { InstanceStateMerge.merge(state(2, false), state(1, true), 100) }
        assertThrows<SnapshotFormatException> { InstanceStateMerge.merge(base, delta, 2) }
        assertThrows<SnapshotFormatException> { InstanceStateMerge.merge(delta, delta, 100) }
        assertThrows<SnapshotFormatException> { InstanceStateMerge.merge(base, base, 100) }
    }

    @Test
    fun `性質、乱数で書きながら全体→差分→…と取り出すと、基準と差分の合成が同じ時点の全体と一致する`() {
        val random = Random(83)
        instance(pages = 4).use { inst ->
            val chunks = 4 * WasmInstanceState.CHUNKS_PER_PAGE
            var base: WasmInstanceState? = null
            var fullSaves = 0
            repeat(40) { round ->
                repeat(random.nextInt(0, 12)) {
                    // 一部の区画に集めて書く（差分が小さい回と大きい回を作る）
                    val c = if (round % 7 == 6) random.nextInt(chunks) else random.nextInt(8)
                    inst.call("store", (c * chunk + random.nextInt(chunk / 8) * 8).toLong(), random.nextLong())
                }
                if (random.nextInt(4) == 0) inst.call("inc")
                val counts = inst.memoryChunkCounts()!!
                val b = base
                if (b == null || counts.changed.toDouble() / maxOf(counts.written, 1) > 0.5) {
                    base = roundTrip(inst.captureState(StateCaptureMode.FULL_AND_TRACK))
                    fullSaves++
                } else {
                    val delta = roundTrip(inst.captureState(StateCaptureMode.CHANGED))
                    val full = inst.captureState(StateCaptureMode.FULL)
                    assertSameState(full, InstanceStateMerge.merge(b, delta, Long.MAX_VALUE))
                }
            }
            assertTrue(fullSaves in 2..39, "全体保存に切り替わる回と差分の回がある（$fullSaves）")
        }
    }
}
