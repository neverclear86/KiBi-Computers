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
import pro.ikili.kibicomputers.core.kernel.process.ExitReason
import pro.ikili.kibicomputers.core.kernel.process.ExitStatus
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ProcessArgs
import pro.ikili.kibicomputers.core.wasi.kibi.KibiFs
import pro.ikili.kibicomputers.core.wasi.kibi.KibiProcess
import pro.ikili.kibicomputers.core.kernel.vfs.FsUsage
import pro.ikili.kibicomputers.core.kernel.tty.TtySize
import pro.ikili.kibicomputers.core.kernel.tty.TtySizeInfo
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

    /** 揃っていない list の ptr は、メモリを読まずにトラップ（error-code ではない。#430） */
    private fun assertMisalignedTrap(block: (WasmMemory) -> Unit) {
        val m = CountingMemory()
        val e = assertThrows<WasmTrapException> { block(m) }
        assertEquals(WasmTrapKind.OTHER, e.kind)
        assertEquals(0L, m.bytesRead)
    }

    @Test
    fun `list_stringのptrがalign4に揃っていなければ、長さ0や上限超えでもトラップ`() {
        for (ptr in listOf(0x201, 0x202, 0x203, -1, 0xffff_fff9.toInt())) {
            for (len in listOf(0, 1, 17, -1)) assertMisalignedTrap { P1Canonical.liftStringList(it, ptr, len, 16, 1..64) }
        }
        // 文字列の中身は align 1 なので、要素の ptr は揃っていなくてよい
        val m = CountingMemory()
        m.write(0x101, "ab".toByteArray())
        m.putString(0x200, 0, 0x101, 2)
        assertEquals(listOf("ab"), P1Canonical.liftStringList(m, 0x200, 1, 16, 1..64))
    }

    @Test
    fun `kibi_processのlistのptrもalign4に揃っていなければトラップ`() {
        for (ptr in listOf(0x201, 0x202, -1)) {
            for (len in listOf(0, 1, 5)) {
                assertMisalignedTrap { P1Canonical.liftArgList(it, ptr, len, 4, P1Canonical.ArgBudget()) }
                assertMisalignedTrap { P1Canonical.liftEnvList(it, ptr, len, 4, P1Canonical.ArgBudget()) }
                assertMisalignedTrap { P1Canonical.liftFdAssigns(it, ptr, len, 4) }
            }
        }
    }

    @Test
    fun `option_u32は判別子で読み、値は符号なし`() {
        assertNull(P1Canonical.liftOptionU32(0, 123))
        assertEquals(5L, P1Canonical.liftOptionU32(1, 5))
        assertEquals(0xffff_ffffL, P1Canonical.liftOptionU32(1, -1))
        assertErrno(Errno.INVAL) { P1Canonical.liftOptionU32(2, 0) }
    }

    @Test
    fun `optionの判別子は0と1だけを読み、ほかはnull`() {
        assertEquals(false, P1Canonical.liftOptionDisc(0))
        assertEquals(true, P1Canonical.liftOptionDisc(1))
        assertNull(P1Canonical.liftOptionDisc(2))
        assertNull(P1Canonical.liftOptionDisc(-1))
    }

    @Test
    fun `u64の引数は2の63乗以上をLong_MAX_VALUEに頭打ちにする`() {
        assertEquals(0L, P1Canonical.u64Arg(0))
        assertEquals(Long.MAX_VALUE, P1Canonical.u64Arg(Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, P1Canonical.u64Arg(Long.MIN_VALUE))
        assertEquals(Long.MAX_VALUE, P1Canonical.u64Arg(-1))
    }

    // ---- lower ----

    @Test
    fun `result_u64はタグを0に、値と判別子を8に書き、使わない所を0にする`() {
        val m = FakeMemory(1)
        m.write(0x100, ByteArray(16) { 0x55 })
        P1Canonical.lowerU64Result(m, 0x100, -2L, 0)
        assertEquals(0, m.readU8(0x100))
        assertArrayEquals(ByteArray(7), m.read(0x101, 7))
        assertEquals(-2L, m.readI64(0x108))
        m.write(0x100, ByteArray(16) { 0x55 })
        P1Canonical.lowerU64Result(m, 0x100, null, 7)
        assertEquals(1, m.readU8(0x100))
        assertEquals(7, m.readU8(0x108))
        assertArrayEquals(ByteArray(7), m.read(0x109, 7))
        // 範囲外・align 違いはトラップ
        assertEquals(WasmTrapKind.MEMORY_OUT_OF_BOUNDS, assertThrows<WasmTrapException> { P1Canonical.lowerU64Result(m, WasmMemory.PAGE_SIZE - 8, 1, 0) }.kind)
        assertThrows<WasmTrapException> { P1Canonical.lowerU64Result(m, 0x104, 1, 0) }
    }

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
            EventValue.Integer(-2),
            EventValue.Float(1.5),
            EventValue.Boolean(true),
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
        assertEquals(P1Canonical.VALUE_INTEGER, m.readU8(vals))
        assertEquals(-2L, m.readI64(vals + 8))
        assertEquals(P1Canonical.VALUE_FLOAT, m.readU8(vals + 16))
        assertEquals(1.5, java.lang.Double.longBitsToDouble(m.readI64(vals + 24)))
        assertEquals(P1Canonical.VALUE_BOOLEAN, m.readU8(vals + 32))
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
        val batch = EventBatch(listOf(KernelEvent("a", "", listOf(EventValue.Integer(1)))), 0)
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

    // ---- kibi:io/tty（#427） ----

    @Test
    fun `flagsは宣言した下位ビットだけを読み、上のビットは捨てる`() {
        assertEquals(0, P1Canonical.liftFlags(0, 5))
        assertEquals(0x1f, P1Canonical.liftFlags(-1, 5))
        assertEquals(0, P1Canonical.liftFlags(0xffff_ffe0.toInt(), 5))
        assertEquals(0x15, P1Canonical.liftFlags(0x35, 5))
        assertEquals(1, P1Canonical.liftFlags(3, 1))
        assertEquals(-1, P1Canonical.liftFlags(-1, 32))
        assertThrows<IllegalArgumentException> { P1Canonical.liftFlags(0, 0) }
        assertThrows<IllegalArgumentException> { P1Canonical.liftFlags(0, 33) }
    }

    @Test
    fun `result_modeはタグを0に、okもerrもオフセット1に書く`() {
        assertEquals(2, P1Canonical.U8_RESULT_SIZE)
        assertEquals(1, P1Canonical.U8_RESULT_ALIGN)
        val m = FakeMemory(1)
        m.write(0x101, byteArrayOf(0x55, 0x55, 0x55))
        P1Canonical.lowerU8Result(m, 0x101, 0x1d, 0)
        assertArrayEquals(byteArrayOf(0, 0x1d, 0x55), m.read(0x101, 3))
        P1Canonical.lowerU8Result(m, 0x101, null, 58)
        assertArrayEquals(byteArrayOf(1, 58, 0x55), m.read(0x101, 3))
    }

    @Test
    fun `result_sizeは列を8、行を12、世代を16に書き、errなら判別子を8に書き、使わない所を0にする`() {
        assertEquals(24, P1Canonical.TTY_SIZE_RESULT_SIZE)
        assertEquals(8, P1Canonical.TTY_SIZE_RESULT_ALIGN)
        val m = FakeMemory(1)
        m.write(0x100, ByteArray(32) { 0x55 })
        P1Canonical.lowerTtySizeResult(m, 0x100, TtySizeInfo(TtySize(80, 25), 0x1_0000_0003L), 0)
        assertEquals(0, m.readU8(0x100))
        assertArrayEquals(ByteArray(7), m.read(0x101, 7))
        assertEquals(80, m.readI32(0x108))
        assertEquals(25, m.readI32(0x10c))
        assertEquals(0x1_0000_0003L, m.readI64(0x110))
        assertEquals(0x55, m.readU8(0x118))
        m.write(0x100, ByteArray(24) { 0x55 })
        P1Canonical.lowerTtySizeResult(m, 0x100, null, 7)
        assertEquals(1, m.readU8(0x100))
        assertEquals(7, m.readU8(0x108))
        assertArrayEquals(ByteArray(7), m.read(0x101, 7))
        assertArrayEquals(ByteArray(15), m.read(0x109, 15))
    }

    @Test
    fun `kibi_io_ttyの結果の書き先が線形メモリの外か揃っていなければトラップし、何も書かない`() {
        val m = FakeMemory(1)
        val end = WasmMemory.PAGE_SIZE
        fun kind(block: () -> Unit) = assertThrows<WasmTrapException> { block() }.kind
        val info = TtySizeInfo(TtySize(1, 1), 0)
        assertEquals(WasmTrapKind.MEMORY_OUT_OF_BOUNDS, kind { P1Canonical.lowerU8Result(m, end - 1, 1, 0) })
        assertEquals(WasmTrapKind.MEMORY_OUT_OF_BOUNDS, kind { P1Canonical.lowerTtySizeResult(m, end - 16, info, 0) })
        assertEquals(WasmTrapKind.MEMORY_OUT_OF_BOUNDS, kind { P1Canonical.lowerTtySizeResult(m, -8, null, 7) })
        assertEquals(WasmTrapKind.OTHER, kind { P1Canonical.lowerTtySizeResult(m, 0x104, info, 0) })
        assertArrayEquals(ByteArray(24), m.read(0x104, 24))
    }

    // ---- kibi:process（#74） ----

    @Test
    fun `stringは上限を超えたら読まずに指定のerrno、範囲外はfault、UTF-8でなければinvalid`() {
        val m = CountingMemory()
        m.write(0x100, "/bin".toByteArray())
        assertEquals("/bin", P1Canonical.liftString(m, 0x100, 4, 16, Errno.NAMETOOLONG))
        assertEquals("", P1Canonical.liftString(m, 0, 0, 16, Errno.NAMETOOLONG))
        m.bytesRead = 0
        assertErrno(Errno.NAMETOOLONG) { P1Canonical.liftString(m, 0x100, 17, 16, Errno.NAMETOOLONG) }
        assertErrno(Errno.NAMETOOLONG) { P1Canonical.liftString(m, 0x100, -1, 16, Errno.NAMETOOLONG) }
        assertEquals(0L, m.bytesRead)
        assertErrno(Errno.FAULT) { P1Canonical.liftString(m, WasmMemory.PAGE_SIZE - 2, 4, 16, Errno.NAMETOOLONG) }
        m.write(0x200, byteArrayOf(0xff.toByte()))
        assertErrno(Errno.INVAL) { P1Canonical.liftString(m, 0x200, 1, 16, Errno.NAMETOOLONG) }
    }

    @Test
    fun `argsは数が多すぎれば要素を読まずにtoo-big、合計が予算を超えれば中身を読まずにtoo-big`() {
        val m = CountingMemory()
        assertErrno(Errno.TOOBIG) { P1Canonical.liftArgList(m, 0x200, 5, 4, P1Canonical.ArgBudget()) }
        assertErrno(Errno.TOOBIG) { P1Canonical.liftArgList(m, 0x200, -1, 4, P1Canonical.ArgBudget()) }
        assertEquals(0L, m.bytesRead)
        m.write(0x100, "abc".toByteArray())
        m.putString(0x200, 0, 0x100, 3)
        m.putString(0x200, 1, 0x100, 3)
        // "abc " が2つで 8 バイト
        assertEquals(listOf("abc", "abc"), P1Canonical.liftArgList(m, 0x200, 2, 4, P1Canonical.ArgBudget(8)))
        m.bytesRead = 0
        assertErrno(Errno.TOOBIG) { P1Canonical.liftArgList(m, 0x200, 2, 4, P1Canonical.ArgBudget(7)) }
        // 2つ目の中身は読まない（要素の (ptr, len) 2つと、1つ目の中身だけ）
        assertEquals(16L + 3L, m.bytesRead)
        // 巨大な長さは、範囲を確かめる前に予算で断る
        m.putString(0x300, 0, 0x100, 0x7fff_ffff)
        assertErrno(Errno.TOOBIG) { P1Canonical.liftArgList(m, 0x300, 1, 4, P1Canonical.ArgBudget()) }
        assertErrno(Errno.FAULT) { P1Canonical.liftArgList(m, WasmMemory.PAGE_SIZE - 4, 1, 4, P1Canonical.ArgBudget()) }
        assertEquals(ProcessArgs.MAX_BYTES.toLong(), P1Canonical.ArgBudget().remaining)
    }

    @Test
    fun `envは16バイトの組で、名前=値のNULまでを予算から引く`() {
        val m = CountingMemory()
        m.write(0x100, "HOME".toByteArray())
        m.write(0x110, "/home".toByteArray())
        m.writeI32(0x200, 0x100)
        m.writeI32(0x204, 4)
        m.writeI32(0x208, 0x110)
        m.writeI32(0x20c, 5)
        val budget = P1Canonical.ArgBudget(100)
        assertEquals(listOf("HOME" to "/home"), P1Canonical.liftEnvList(m, 0x200, 1, 4, budget))
        assertEquals(100L - (4 + 1 + 5 + 1), budget.remaining)
        assertErrno(Errno.TOOBIG) { P1Canonical.liftEnvList(m, 0x200, 1, 4, P1Canonical.ArgBudget(10)) }
        assertErrno(Errno.TOOBIG) { P1Canonical.liftEnvList(m, 0x200, 5, 4, P1Canonical.ArgBudget()) }
        assertErrno(Errno.FAULT) { P1Canonical.liftEnvList(m, WasmMemory.PAGE_SIZE - 8, 1, 4, P1Canonical.ArgBudget()) }
    }

    @Test
    fun `fd-assignは12バイトで、判別子0はparent、1はclosed、ほかはinvalid`() {
        val m = CountingMemory()
        m.writeI32(0x200, 1)
        m.writeU8(0x204, 0)
        m.writeI32(0x208, -1)
        m.writeI32(0x20c, 5)
        m.writeU8(0x210, 1)
        m.writeI32(0x214, 99)
        assertEquals(
            listOf(KibiProcess.FdAssign(1, 0xffff_ffffL), KibiProcess.FdAssign(5, null)),
            P1Canonical.liftFdAssigns(m, 0x200, 2, 4),
        )
        m.writeU8(0x210, 2)
        assertErrno(Errno.INVAL) { P1Canonical.liftFdAssigns(m, 0x200, 2, 4) }
        m.bytesRead = 0
        assertErrno(Errno.INVAL) { P1Canonical.liftFdAssigns(m, 0x200, 5, 4) }
        assertEquals(0L, m.bytesRead)
        assertErrno(Errno.FAULT) { P1Canonical.liftFdAssigns(m, WasmMemory.PAGE_SIZE - 12, 2, 4) }
    }

    @Test
    fun `result_option_exit-statusの配置`() {
        val m = FakeMemory(1)
        fun at(status: ExitStatus?, error: Int?): ByteArray {
            m.write(0x100, ByteArray(16) { 0x55 })
            P1Canonical.lowerStatusResult(m, 0x100, status, error)
            return m.read(0x100, 16)
        }
        fun bytes(vararg pairs: Pair<Int, Int>) = ByteArray(16).also { b -> for ((i, v) in pairs) b[i] = v.toByte() }
        assertArrayEquals(bytes(), at(null, null))
        assertArrayEquals(bytes(0 to 1, 4 to 7), at(null, 7))
        assertArrayEquals(
            bytes(4 to 1, 8 to 0, 12 to 0x00, 13 to 0x28, 14 to 0x6b, 15 to 0xee),
            at(ExitStatus(2, ExitReason.EXITED, 4_000_000_000L.toInt(), null), null),
        )
        assertArrayEquals(bytes(4 to 1, 8 to 1, 12 to 1, 13 to 3), at(ExitStatus(2, ExitReason.KILLED, 0, KillSignal.KILL), null))
        assertArrayEquals(bytes(4 to 1, 8 to 1), at(ExitStatus(2, ExitReason.KILLED, 0, null), null))
        assertArrayEquals(bytes(4 to 1, 8 to 2), at(ExitStatus(2, ExitReason.CRASHED, 0, null), null))
        assertThrows<WasmTrapException> { P1Canonical.lowerStatusResult(m, 0x102, null, null) }
        assertThrows<WasmTrapException> { P1Canonical.lowerStatusResult(m, WasmMemory.PAGE_SIZE - 8, null, null) }
    }

    @Test
    fun `stringの結果はcabi_reallocで場所を取り、ptrを0に、lenを4に書く`() {
        val m = FakeMemory(1)
        val r = BumpRealloc()
        P1Canonical.lowerStringResult(m, r, 0x100, "/home")
        val p = m.readI32(0x100)
        assertEquals(5, m.readI32(0x104))
        assertEquals("/home", String(m.read(p, 5)))
        assertEquals(1, r.calls.size)
        assertThrows<WasmTrapException> { P1Canonical.lowerStringResult(m, BumpRealloc(), 0x102, "/") }
    }

    // ---- list<process-info>（#452、ADR 0452） ----

    private fun entry(pid: Int, parent: Int, state: KibiProcess.ListState, exit: ExitStatus?, vararg args: String) =
        KibiProcess.Entry(pid, parent, state, exit, args.map { it.toByteArray(Charsets.UTF_8) })

    @Test
    fun `list_process-infoは28バイトの要素を配置どおりに書き、詰め物を0にする`() {
        val m = FakeMemory(1)
        // 前の中身を残さないことを確かめるため、場所を取る範囲を 0x55 で埋めておく
        m.write(0x8000, ByteArray(0x200) { 0x55 })
        val r = BumpRealloc()
        val entries = listOf(
            entry(1, 0, KibiProcess.ListState.RUNNING, null, "kp", "ps"),
            entry(2, 1, KibiProcess.ListState.FINISHED, ExitStatus(2, ExitReason.EXITED, 4_000_000_000L.toInt(), null)),
            entry(3, 0, KibiProcess.ListState.FINISHED, ExitStatus(3, ExitReason.KILLED, 0, KillSignal.TERMINATE), ""),
            entry(4, 1, KibiProcess.ListState.FINISHED, ExitStatus(4, ExitReason.KILLED, 0, null)),
            entry(5, 1, KibiProcess.ListState.FINISHED, ExitStatus(5, ExitReason.CRASHED, 0, null)),
            entry(6, 1, KibiProcess.ListState.STARTING, null),
            entry(7, 1, KibiProcess.ListState.WAITING, null),
        )
        P1Canonical.lowerProcessListResult(m, r, 0x100, entries)
        val listPtr = m.readI32(0x100)
        assertEquals(entries.size, m.readI32(0x104))
        assertEquals(0, listPtr % 4)
        fun element(i: Int) = m.read(listPtr + i * P1Canonical.PROCESS_INFO_SIZE, P1Canonical.PROCESS_INFO_SIZE)
        fun bytes(vararg pairs: Pair<Int, Int>) = ByteArray(20).also { b -> for ((i, v) in pairs) b[i] = v.toByte() }
        // 先頭 20 バイト（pid・parent-pid・state）
        assertArrayEquals(bytes(0 to 1, 8 to 1), element(0).copyOf(20))
        assertArrayEquals(bytes(0 to 2, 4 to 1, 8 to 3, 12 to 0, 16 to 0x00, 17 to 0x28, 18 to 0x6b, 19 to 0xee), element(1).copyOf(20))
        assertArrayEquals(bytes(0 to 3, 8 to 3, 12 to 1, 16 to 1, 17 to 2), element(2).copyOf(20))
        assertArrayEquals(bytes(0 to 4, 4 to 1, 8 to 3, 12 to 1), element(3).copyOf(20))
        assertArrayEquals(bytes(0 to 5, 4 to 1, 8 to 3, 12 to 2), element(4).copyOf(20))
        assertArrayEquals(bytes(0 to 6, 4 to 1, 8 to 0), element(5).copyOf(20))
        assertArrayEquals(bytes(0 to 7, 4 to 1, 8 to 2), element(6).copyOf(20))
        // args
        fun args(i: Int): List<String> {
            val at = listPtr + i * P1Canonical.PROCESS_INFO_SIZE
            val p = m.readI32(at + 20)
            return (0 until m.readI32(at + 24)).map { j -> String(m.read(m.readI32(p + j * 8), m.readI32(p + j * 8 + 4)), Charsets.UTF_8) }
        }
        assertEquals(listOf("kp", "ps"), args(0))
        assertEquals(emptyList<String>(), args(1))
        assertEquals(listOf(""), args(2))
        // cabi_realloc の順: 外側の配列 → 要素ごとに args の配列 → 各文字列（0 件・0 バイトでも呼ぶ）
        assertEquals(
            listOf(
                4 to 28 * 7,
                4 to 16, 1 to 2, 1 to 2,
                4 to 0,
                4 to 8, 1 to 0,
                4 to 0, 4 to 0, 4 to 0, 4 to 0,
            ),
            r.calls,
        )
    }

    @Test
    fun `空のlist_process-infoも配列の場所を取る`() {
        val m = FakeMemory(1)
        val r = BumpRealloc()
        P1Canonical.lowerProcessListResult(m, r, 0x100, emptyList())
        assertEquals(listOf(4 to 0), r.calls)
        assertEquals(0, m.readI32(0x104))
    }

    @Test
    fun `list_process-infoは書き先の誤りをcabi_reallocより先にトラップし、cabi_reallocの返した場所の誤りもトラップ`() {
        val m = FakeMemory(1)
        val entries = listOf(entry(1, 0, KibiProcess.ListState.RUNNING, null, "kp"))
        for (retPtr in listOf(0x102, WasmMemory.PAGE_SIZE - 4)) {
            val r = BumpRealloc()
            assertThrows<WasmTrapException> { P1Canonical.lowerProcessListResult(m, r, retPtr, entries) }
            assertEquals(emptyList<Pair<Int, Int>>(), r.calls)
        }
        assertEquals(
            WasmTrapKind.MEMORY_OUT_OF_BOUNDS,
            assertThrows<WasmTrapException> { P1Canonical.lowerProcessListResult(m, BumpRealloc(outside = true), 0x100, entries) }.kind,
        )
        assertThrows<WasmTrapException> { P1Canonical.lowerProcessListResult(m, BumpRealloc(misalign = true), 0x100, entries) }
        // args の配列の場所だけが揃っていない
        var n = 0
        val second = P1Canonical.Realloc { _, _ -> if (n++ == 1) 0x2002 else 0x1000 }
        assertThrows<WasmTrapException> { P1Canonical.lowerProcessListResult(m, second, 0x100, entries) }
    }

    @Test
    fun `list_mount-infoはalign8の配列に72バイトずつ書き、usageのnoneと上限なしも書ける`() {
        val m = FakeMemory(1)
        val r = BumpRealloc()
        val entries = listOf(
            KibiFs.MountInfo("/", "disk:r", KibiFs.Kind.DISK, false, 1, FsUsage(0x1_0000_0002L, 3, 4, 5, 6)),
            KibiFs.MountInfo("/tmp", "tmp", KibiFs.Kind.TMP, true, 2, FsUsage(10, 20, 30, null, 7)),
            KibiFs.MountInfo("/x", "", KibiFs.Kind.DEV, false, 9, null),
        )
        P1Canonical.lowerMountListResult(m, r, 0x100, entries)
        val listPtr = m.readI32(0x100)
        assertEquals(3, m.readI32(0x104))
        assertEquals(0, listPtr % 8)
        fun at(i: Int) = listPtr + i * P1Canonical.MOUNT_INFO_SIZE
        fun str(p: Int) = String(m.read(m.readI32(p), m.readI32(p + 4)), Charsets.UTF_8)
        assertEquals("/", str(at(0)))
        assertEquals("disk:r", str(at(0) + 8))
        assertEquals(0, m.readU8(at(0) + 16))
        assertEquals(0, m.readU8(at(0) + 17))
        assertEquals(1, m.readI32(at(0) + 20))
        assertEquals(1, m.readU8(at(0) + 24))
        assertEquals(0x1_0000_0002L, m.readI64(at(0) + 32))
        assertEquals(3L, m.readI64(at(0) + 40))
        assertEquals(4L, m.readI64(at(0) + 48))
        assertEquals(1, m.readU8(at(0) + 56))
        assertEquals(5, m.readI32(at(0) + 60))
        assertEquals(6, m.readI32(at(0) + 64))
        // tmp: read-only・上限なし
        assertEquals(2, m.readU8(at(1) + 16))
        assertEquals(1, m.readU8(at(1) + 17))
        assertEquals(0, m.readU8(at(1) + 56))
        assertEquals(0, m.readI32(at(1) + 60))
        assertEquals(7, m.readI32(at(1) + 64))
        // usage が none なら、判別子より後ろは 0
        assertEquals(3, m.readU8(at(2) + 16))
        assertEquals(9, m.readI32(at(2) + 20))
        assertArrayEquals(ByteArray(48), m.read(at(2) + 24, 48))
        // 詰め物は 0
        assertArrayEquals(ByteArray(2), m.read(at(0) + 18, 2))
        assertArrayEquals(ByteArray(7), m.read(at(0) + 25, 7))
        assertArrayEquals(ByteArray(3), m.read(at(0) + 57, 3))
        assertArrayEquals(ByteArray(4), m.read(at(0) + 68, 4))
        // cabi_realloc の順: 外側の配列 → 要素ごとに point → source（0 バイトでも呼ぶ）
        assertEquals(listOf(8 to 72 * 3, 1 to 1, 1 to 6, 1 to 4, 1 to 3, 1 to 2, 1 to 0), r.calls)
    }

    @Test
    fun `list_disk-infoはalign8の配列に32バイトずつ書き、mount-pointはsomeのときだけ場所を取る`() {
        val m = FakeMemory(1)
        val r = BumpRealloc()
        val entries = listOf(
            KibiFs.DiskInfo("disk:a", 0x1_0000_0000L, 64, "/mnt/a"),
            KibiFs.DiskInfo("disk:b", 1024, 8, null),
        )
        P1Canonical.lowerDiskListResult(m, r, 0x100, entries)
        val listPtr = m.readI32(0x100)
        assertEquals(2, m.readI32(0x104))
        assertEquals(0, listPtr % 8)
        fun at(i: Int) = listPtr + i * P1Canonical.DISK_INFO_SIZE
        fun str(p: Int) = String(m.read(m.readI32(p), m.readI32(p + 4)), Charsets.UTF_8)
        assertEquals("disk:a", str(at(0)))
        assertEquals(0x1_0000_0000L, m.readI64(at(0) + 8))
        assertEquals(64, m.readI32(at(0) + 16))
        assertEquals(1, m.readU8(at(0) + 20))
        assertEquals("/mnt/a", str(at(0) + 24))
        assertEquals("disk:b", str(at(1)))
        assertEquals(8, m.readI32(at(1) + 16))
        assertArrayEquals(ByteArray(12), m.read(at(1) + 20, 12))
        assertEquals(listOf(8 to 64, 1 to 6, 1 to 6, 1 to 6), r.calls)
    }

    @Test
    fun `空のlist_mount-infoとlist_disk-infoも配列の場所を取り、書き先の誤りはcabi_reallocより先にトラップ`() {
        val m = FakeMemory(1)
        val r = BumpRealloc()
        P1Canonical.lowerMountListResult(m, r, 0x100, emptyList())
        P1Canonical.lowerDiskListResult(m, r, 0x108, emptyList())
        assertEquals(listOf(8 to 0, 8 to 0), r.calls)
        val one = listOf(KibiFs.DiskInfo("disk:a", 1, 1, null))
        for (retPtr in listOf(0x102, WasmMemory.PAGE_SIZE - 4)) {
            val r2 = BumpRealloc()
            assertThrows<WasmTrapException> { P1Canonical.lowerDiskListResult(m, r2, retPtr, one) }
            assertThrows<WasmTrapException> { P1Canonical.lowerMountListResult(m, r2, retPtr, emptyList()) }
            assertEquals(emptyList<Pair<Int, Int>>(), r2.calls)
        }
        // align 8 に揃っていない配列の場所はトラップ
        val four = P1Canonical.Realloc { _, _ -> 0x1004 }
        assertThrows<WasmTrapException> { P1Canonical.lowerDiskListResult(m, four, 0x100, one) }
    }
}
