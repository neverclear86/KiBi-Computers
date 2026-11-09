package pro.ikili.kibicomputers.core.runtime.endive

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmTableBudget
import run.endive.wasm.types.MemoryLimits

/** 変わった区画の追跡（#83、ADR 0083。[WrittenPagesMemory.parkWritten]） */
class WrittenPagesMemoryTrackTest {
    private val page = WasmMemory.PAGE_SIZE
    private val chunk = WrittenPagesMemory.CHUNK_SIZE

    private class Budget : WasmTableBudget {
        var used = 0L

        override fun tryReserveBytes(bytes: Long): Boolean = tryReserveBytes(bytes, 0)

        override fun tryReserveBytes(bytes: Long, keepBytes: Long): Boolean {
            used += bytes
            return true
        }

        override fun releaseBytes(bytes: Long) {
            used -= bytes
        }
    }

    private fun changed(m: WrittenPagesMemory): List<Int> = m.writtenChunkSnapshot(changedOnly = true).first.toList()

    private fun written(m: WrittenPagesMemory): List<Int> = m.writtenChunkSnapshot(changedOnly = false).first.toList()

    @Test
    fun `parkWrittenの後は書いた区画だけが変わった区画になり、RAMは数え直さない`() {
        val budget = Budget()
        val m = WrittenPagesMemory(MemoryLimits(2, 4), budget)
        m.writeI32(0, 1)
        m.writeI32(3 * chunk, 2)
        m.writeI32(page + 8, 3)
        assertEquals(listOf(0, 3, 16), changed(m))
        val countedBefore = budget.used
        m.parkWritten()
        assertEquals(emptyList<Int>(), changed(m))
        assertEquals(listOf(0, 3, 16), written(m))
        assertEquals(3 to 0, m.chunkCounts())

        m.writeI32(3 * chunk + 4, 7) // parked の区画
        m.writeI32(5 * chunk, 9) // ゼロの区画
        assertEquals(listOf(3, 5), changed(m))
        assertEquals(listOf(0, 3, 5, 16), written(m))
        assertEquals(4 to 2, m.chunkCounts())
        assertEquals(countedBefore + chunk, budget.used, "parked の区画に書いても数えない")
        assertEquals(4, m.writtenChunks)
        // 中身はそのまま
        assertEquals(1, m.readInt(0))
        assertEquals(2, m.readInt(3 * chunk))
        assertEquals(7, m.readInt(3 * chunk + 4))

        // releaseAll の数は変わらない
        m.releaseAll()
        assertEquals(0L, budget.used)
    }

    @Test
    fun `parkWrittenの後も複数バイトの値をリトルエンディアンで読める（区画の境をまたぐものも）`() {
        val m = WrittenPagesMemory(MemoryLimits(1, 1), Budget())
        m.writeShort(10, 0x1234.toShort())
        m.writeI32(20, 0x12345678)
        m.writeLong(40, 0x0102030405060708L)
        m.writeF32(60, 1.5f)
        m.writeF64(80, -2.25)
        m.writeI32(chunk - 2, 0x0A0B0C0D) // 区画の境をまたぐ
        m.writeLong(2 * chunk - 4, 0x1122334455667788L)
        m.parkWritten()
        assertEquals(0x1234.toShort(), m.readShort(10))
        assertEquals(0x12345678, m.readInt(20))
        assertEquals(0x0102030405060708L, m.readLong(40))
        assertEquals(1.5f, m.readFloat(60))
        assertEquals(-2.25, m.readDouble(80))
        assertEquals(0x0A0B0C0D, m.readInt(chunk - 2))
        assertEquals(0x1122334455667788L, m.readLong(2 * chunk - 4))
        assertEquals(0x1234L, m.readU16(10))
    }

    @Test
    fun `parkWrittenの後の全体の写しは例外を投げず、parkedの区画の中身を返す`() {
        val m = WrittenPagesMemory(MemoryLimits(1, 1), Budget())
        m.write(chunk + 5, byteArrayOf(1, 2, 3), 0, 3)
        m.parkWritten()
        val (indices, data) = m.writtenChunkSnapshot(changedOnly = false)
        assertArrayEquals(intArrayOf(1), indices)
        assertArrayEquals(byteArrayOf(1, 2, 3), data[0].copyOfRange(5, 8))
        // 2回目の parkWritten も同じ
        m.parkWritten()
        assertEquals(listOf(1), written(m))
    }

    @Test
    fun `commitRangeとgrowとrestorePagesとrestoreChunkとcopyがparkedの区画で正しく動く`() {
        val budget = Budget()
        val m = WrittenPagesMemory(MemoryLimits(1, 4), budget)
        m.writeI32(0, 11)
        m.writeI32(chunk, 22)
        m.parkWritten()
        val before = budget.used

        // commitRange: parked の区画は数えずに戻すだけ、ゼロの区画は数える
        assertTrue(m.commitRange(0, 3 * chunk, 0))
        assertEquals(before + chunk, budget.used)
        assertEquals(listOf(0, 1, 2), changed(m))
        assertEquals(11, m.readInt(0))
        m.parkWritten()

        // grow: parked の表も伸びる（伸ばした所の端は書いた区画）
        assertEquals(1, m.grow(1))
        assertEquals(listOf(16, 31), changed(m))
        m.parkWritten()
        assertTrue(m.restorePages(3))
        m.writeI32(2 * page + 4, 5)
        assertEquals(listOf(32), changed(m))

        // restoreChunk: parked の区画は数えずに中身を書いて戻す
        val used = budget.used
        assertTrue(m.restoreChunk(1, ByteArray(chunk) { 9 }))
        assertEquals(used, budget.used)
        assertEquals(0x09090909, m.readInt(chunk))
        assertTrue(1 in changed(m))
        // 戻した区画に書いても数えない
        m.writeI32(chunk + 8, 1)
        assertEquals(used, budget.used)

        // copy: parked の区画から読み、parked の区画へ書く
        m.parkWritten()
        m.copy(0, chunk, 16)
        assertEquals(0x09090909, m.readInt(0))
        assertEquals(listOf(0), changed(m))
        assertEquals(used, budget.used)
    }
}
