package pro.ikili.kibicomputers.core.wasi.p1

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.event.EventBatch
import pro.ikili.kibicomputers.core.kernel.event.EventValue
import pro.ikili.kibicomputers.core.kernel.event.KernelEvent
import pro.ikili.kibicomputers.core.kernel.fd.PipeFds
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.runtime.FakeMemory
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind

/** p1 向けの Canonical ABI の lift・lower（[P1Canonical]。#73）の配置と検査 */
class P1CanonicalTest {
    /** 読んだバイト数を数えるメモリ */
    private class CountingMemory(private val inner: FakeMemory = FakeMemory(1)) : WasmMemory by inner {
        var bytesRead = 0L

        override fun read(address: Int, dst: ByteArray, dstOffset: Int, length: Int) {
            bytesRead += length
            inner.read(address, dst, dstOffset, length)
        }

        override fun read(address: Int, length: Int): ByteArray = ByteArray(length).also { read(address, it, 0, length) }

        override fun readU8(address: Int): Int {
            bytesRead += 1
            return inner.readU8(address)
        }

        override fun readI32(address: Int): Int {
            bytesRead += 4
            return inner.readI32(address)
        }

        override fun readI64(address: Int): Long {
            bytesRead += 8
            return inner.readI64(address)
        }
    }

    /** 偽のゲストの `cabi_realloc`: [start] から順に、align に揃えて取る。呼び出しを記録する */
    private class BumpRealloc(var next: Int = 0x8000, private val misalign: Boolean = false, private val outside: Boolean = false) : P1Canonical.Realloc {
        val calls = mutableListOf<Pair<Int, Int>>()

        override fun alloc(align: Int, size: Int): Int {
            calls += align to size
            if (outside) return 0xffff_fff0.toInt()
            next = (next + align - 1) / align * align
            val p = next + (if (misalign && align > 1) 1 else 0)
            next += size
            return p
        }
    }

    private fun assertErrno(expected: Errno, block: () -> Unit) {
        val e = assertThrows<FsException> { block() }
        assertEquals(expected, e.errno, e.message)
    }

    private fun WasmMemory.putString(listAt: Int, index: Int, ptr: Int, len: Int) {
        writeI32(listAt + index * 8, ptr)
        writeI32(listAt + index * 8 + 4, len)
    }

    // ---- lift ----

    @Test
    fun `list_stringを読む`() {
        val m = CountingMemory()
        m.write(0x100, "ab".toByteArray())
        m.write(0x110, "あ".toByteArray())
        m.putString(0x200, 0, 0x100, 2)
        m.putString(0x200, 1, 0x110, 3)
        assertEquals(listOf("ab", "あ"), P1Canonical.liftStringList(m, 0x200, 2, 16, 1..64))
        assertEquals(emptyList<String>(), P1Canonical.liftStringList(m, 0, 0, 16, 1..64))
    }

    @Test
    fun `要素の数が上限を超えたら、要素を1つも読まずにinvalid`() {
        for (len in listOf(17, 0x7fff_ffff, -1)) {
            val m = CountingMemory()
            assertErrno(Errno.INVAL) { P1Canonical.liftStringList(m, 0x200, len, 16, 1..64) }
            assertEquals(0L, m.bytesRead, "len $len")
        }
    }

    @Test
    fun `同じ領域を指す要素が16個なら、16個分だけ読む`() {
        val m = CountingMemory()
        m.write(0x100, ByteArray(64) { 'a'.code.toByte() })
        for (i in 0 until 16) m.putString(0x400, i, 0x100, 64)
        assertEquals(16, P1Canonical.liftStringList(m, 0x400, 16, 16, 1..64).size)
        assertEquals(16L * 8 + 16L * 64, m.bytesRead)
    }

    @Test
    fun `要素の長さが範囲の外なら、その中身を読まずにinvalid`() {
        for (len in listOf(0, 65, -1)) {
            val m = CountingMemory()
            m.putString(0x200, 0, 0x100, len)
            assertErrno(Errno.INVAL) { P1Canonical.liftStringList(m, 0x200, 1, 16, 1..64) }
            assertEquals(8L, m.bytesRead, "len $len")
        }
    }

    @Test
    fun `正しくないUTF-8はinvalid`() {
        val bad = listOf(byteArrayOf(0xff.toByte()), byteArrayOf(0xe3.toByte(), 0x81.toByte()), byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()))
        for (b in bad) {
            val m = CountingMemory()
            m.write(0x100, b)
            m.putString(0x200, 0, 0x100, b.size)
            assertErrno(Errno.INVAL) { P1Canonical.liftStringList(m, 0x200, 1, 16, 1..64) }
        }
    }

    @Test
    fun `配列や要素が線形メモリの外ならfault`() {
        val m = CountingMemory()
        val end = WasmMemory.PAGE_SIZE
        assertErrno(Errno.FAULT) { P1Canonical.liftStringList(m, end - 8, 2, 16, 1..64) }
        assertErrno(Errno.FAULT) { P1Canonical.liftStringList(m, -8, 2, 16, 1..64) }
        m.putString(0x200, 0, end - 4, 8)
        assertErrno(Errno.FAULT) { P1Canonical.liftStringList(m, 0x200, 1, 16, 1..64) }
    }

    @Test
    fun `option_u32は判別子で読み、値は符号なし`() {
        assertNull(P1Canonical.liftOptionU32(0, 123))
        assertEquals(5L, P1Canonical.liftOptionU32(1, 5))
        assertEquals(0xffff_ffffL, P1Canonical.liftOptionU32(1, -1))
        assertErrno(Errno.INVAL) { P1Canonical.liftOptionU32(2, 0) }
    }

    // ---- lower ----

    @Test
    fun `result_ownはタグを0に、ハンドルを4に書く`() {
        val m = FakeMemory(1)
        m.write(0x100, ByteArray(8) { 0x55 })
        P1Canonical.lowerHandleResult(m, 0x100, 7, 0)
        assertEquals(0, m.readU8(0x100))
        assertEquals(7, m.readI32(0x104))
        P1Canonical.lowerHandleResult(m, 0x100, null, 27)
        assertEquals(1, m.readU8(0x100))
        assertEquals(27, m.readU8(0x104))
    }

    @Test
    fun `結果の書き先が範囲の外やalignに揃っていなければトラップ`() {
        val m = FakeMemory(1)
        assertEquals(WasmTrapKind.MEMORY_OUT_OF_BOUNDS, assertThrows<WasmTrapException> { P1Canonical.lowerHandleResult(m, WasmMemory.PAGE_SIZE - 4, 1, 0) }.kind)
        assertThrows<WasmTrapException> { P1Canonical.lowerHandleResult(m, 0x102, 1, 0) }
        assertThrows<WasmTrapException> { P1Canonical.lowerBatchResult(m, BumpRealloc(), 0x104, null, 1) }
        assertThrows<WasmTrapException> { P1Canonical.lowerBatchResult(m, BumpRealloc(), WasmMemory.PAGE_SIZE - 16, null, 1) }
    }

    @Test
    fun `result_event-batchのerrはタグを0に、判別子を8に書く`() {
        val m = FakeMemory(1)
        val r = BumpRealloc()
        P1Canonical.lowerBatchResult(m, r, 0x100, null, 7)
        assertEquals(1, m.readU8(0x100))
        assertEquals(7, m.readU8(0x108))
        assertEquals(0, r.calls.size, "err では場所を取らない")
    }

    @Test
    fun `result_event-batchのokは配置どおりに書き、cabi_reallocで場所を取る`() {
        val m = FakeMemory(1)
        val r = BumpRealloc()
        val values = listOf(
            EventValue.I64(-2),
            EventValue.F64(1.5),
            EventValue.Bool(true),
            EventValue.Text("h\uD800"),
            EventValue.Bytes(byteArrayOf(1, 2, 3)),
        )
        val batch = EventBatch(listOf(KernelEvent("key", "kb", values), KernelEvent("t")), dropped = 5)
        P1Canonical.lowerBatchResult(m, r, 0x100, batch, 0)

        assertEquals(0, m.readU8(0x100))
        val list = m.readI32(0x108)
        assertEquals(2, m.readI32(0x10c))
        assertEquals(5L, m.readI64(0x110))
        // 呼び出しの順: 配列 → 1つ目（kind, source, values, text, bytes）→ 2つ目（kind, source, values）
        assertEquals(
            listOf(4 to 48, 1 to 3, 1 to 2, 8 to 80, 1 to 4, 1 to 3, 1 to 1, 1 to 0, 8 to 0),
            r.calls,
        )
        assertEquals(0, list % 4)

        fun str(at: Int): ByteArray = m.read(m.readI32(at), m.readI32(at + 4))
        assertArrayEquals("key".toByteArray(), str(list))
        assertArrayEquals("kb".toByteArray(), str(list + 8))
        val vals = m.readI32(list + 16)
        assertEquals(5, m.readI32(list + 20))
        assertEquals(0, vals % 8)
        assertEquals(P1Canonical.VALUE_S64, m.readU8(vals))
        assertEquals(-2L, m.readI64(vals + 8))
        assertEquals(P1Canonical.VALUE_F64, m.readU8(vals + 16))
        assertEquals(1.5, java.lang.Double.longBitsToDouble(m.readI64(vals + 24)))
        assertEquals(P1Canonical.VALUE_BOOL, m.readU8(vals + 32))
        assertEquals(1, m.readU8(vals + 40))
        assertEquals(P1Canonical.VALUE_TEXT, m.readU8(vals + 48))
        // 対になっていないサロゲートは U+FFFD
        assertArrayEquals(byteArrayOf('h'.code.toByte(), 0xef.toByte(), 0xbf.toByte(), 0xbd.toByte()), str(vals + 56))
        assertEquals(P1Canonical.VALUE_BYTES, m.readU8(vals + 64))
        assertArrayEquals(byteArrayOf(1, 2, 3), str(vals + 72))

        val second = list + 24
        assertArrayEquals("t".toByteArray(), str(second))
        assertEquals(0, m.readI32(second + 12), "source は空")
        assertEquals(0, m.readI32(second + 20), "値はない")
    }

    @Test
    fun `空のバッチも配列の場所を取る`() {
        val m = FakeMemory(1)
        val r = BumpRealloc()
        P1Canonical.lowerBatchResult(m, r, 0x100, EventBatch(emptyList(), 0), 0)
        assertEquals(listOf(4 to 0), r.calls)
        assertEquals(0, m.readI32(0x10c))
    }

    @Test
    fun `cabi_reallocが範囲の外やalignの違う場所を返したらトラップ`() {
        val batch = EventBatch(listOf(KernelEvent("a", "", listOf(EventValue.I64(1)))), 0)
        assertEquals(
            WasmTrapKind.MEMORY_OUT_OF_BOUNDS,
            assertThrows<WasmTrapException> { P1Canonical.lowerBatchResult(FakeMemory(1), BumpRealloc(outside = true), 0x100, batch, 0) }.kind,
        )
        assertThrows<WasmTrapException> { P1Canonical.lowerBatchResult(FakeMemory(1), BumpRealloc(misalign = true), 0x100, batch, 0) }
    }

    // ---- kibi:io（#75） ----

    @Test
    fun `result_unitはtagが0でerrの場所も0、errなら判別子をオフセット1に書く`() {
        assertEquals(2, P1Canonical.UNIT_RESULT_SIZE)
        assertEquals(1, P1Canonical.UNIT_RESULT_ALIGN)
        val m = FakeMemory(1)
        m.write(0x101, byteArrayOf(0x55, 0x55, 0x55))
        P1Canonical.lowerUnitResult(m, 0x101, null)
        assertArrayEquals(byteArrayOf(0, 0, 0x55), m.read(0x101, 3))
        P1Canonical.lowerUnitResult(m, 0x101, 7)
        assertArrayEquals(byteArrayOf(1, 7, 0x55), m.read(0x101, 3))
    }

    @Test
    fun `result_pipe-endsはreadをオフセット4、writeを8に書き、errなら判別子を4に書く`() {
        assertEquals(12, P1Canonical.PIPE_RESULT_SIZE)
        assertEquals(4, P1Canonical.PIPE_RESULT_ALIGN)
        val m = FakeMemory(1)
        P1Canonical.lowerPipeResult(m, 0x100, PipeFds(3, 7), 0)
        assertEquals(0, m.readU8(0x100))
        assertEquals(3, m.readI32(0x104))
        assertEquals(7, m.readI32(0x108))
        P1Canonical.lowerPipeResult(m, 0x200, null, 32)
        assertEquals(1, m.readU8(0x200))
        assertEquals(32, m.readU8(0x204))
    }

    @Test
    fun `kibi_ioの結果の書き先が線形メモリの外か揃っていなければトラップ`() {
        val m = FakeMemory(1)
        val end = WasmMemory.PAGE_SIZE
        fun kind(block: () -> Unit) = assertThrows<WasmTrapException> { block() }.kind
        assertEquals(WasmTrapKind.MEMORY_OUT_OF_BOUNDS, kind { P1Canonical.lowerUnitResult(m, end - 1, null) })
        assertEquals(WasmTrapKind.MEMORY_OUT_OF_BOUNDS, kind { P1Canonical.lowerUnitResult(m, -1, 7) })
        assertEquals(WasmTrapKind.MEMORY_OUT_OF_BOUNDS, kind { P1Canonical.lowerPipeResult(m, end - 8, PipeFds(0, 1), 0) })
        assertEquals(WasmTrapKind.OTHER, kind { P1Canonical.lowerPipeResult(m, 0x102, PipeFds(0, 1), 0) })
        // 書き先の誤りでは何も書かない
        assertEquals(0, m.readU8(0x102))
    }
}
