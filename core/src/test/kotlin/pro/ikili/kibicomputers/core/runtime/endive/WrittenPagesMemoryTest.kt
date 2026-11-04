package pro.ikili.kibicomputers.core.runtime.endive

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.runtime.TestModules
import pro.ikili.kibicomputers.core.runtime.TestWasiP1
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmTableBudget
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import run.endive.runtime.ByteBufferMemory
import run.endive.runtime.WasmRuntimeException
import run.endive.wasm.types.MemoryLimits
import kotlin.random.Random

/** 初めて書き込んだ 4 KiB の区画の数で RAM を数える線形メモリ（#407） */
class WrittenPagesMemoryTest {
    private val page = WasmMemory.PAGE_SIZE
    private val chunk = WrittenPagesMemory.CHUNK_SIZE

    /** 上限 [limitBytes] のバイト数の勘定 */
    private class Budget(val limitBytes: Long) : WasmTableBudget {
        var used = 0L

        override fun tryReserveBytes(bytes: Long): Boolean {
            if (used + bytes > limitBytes) return false
            used += bytes
            return true
        }

        override fun releaseBytes(bytes: Long) {
            used -= bytes
            check(used >= 0)
        }
    }

    @Test
    fun `初めて書いた4KiBだけを数え、読むだけなら数えない`() {
        val budget = Budget(Long.MAX_VALUE)
        val m = WrittenPagesMemory(MemoryLimits(2, 4), budget)
        assertEquals(0, m.readInt(page + 100))
        assertEquals(0L, m.readLong(chunk - 4)) // 4 KiB をまたぐ
        assertArrayEquals(ByteArray(chunk + 10), m.readBytes(chunk - 5, chunk + 10))
        assertEquals(0, m.writtenChunks)

        m.writeI32(8, 0x12345678)
        m.writeI32(16, 1) // 同じ 4 KiB
        assertEquals(1, m.writtenChunks)
        assertEquals(0x12345678, m.readInt(8))
        // 4 KiB をまたぐ書き込みは両方を数える
        m.writeLong(3 * chunk - 4, -1L)
        assertEquals(3, m.writtenChunks)
        assertEquals(-1L, m.readLong(3 * chunk - 4))
        assertEquals(3L * chunk, budget.used)
    }

    @Test
    fun `ゼロだけの書き込みとゼロのfillとゼロのcopyは数えない`() {
        val m = WrittenPagesMemory(MemoryLimits(1, 1), Budget(Long.MAX_VALUE))
        m.write(10, ByteArray(page - 10))
        m.fill(0, 0, page)
        m.zero()
        m.copy(chunk, 0, chunk)
        assertEquals(0, m.writtenChunks)
        m.fill(7, chunk - 1, chunk + 1)
        assertEquals(2, m.writtenChunks)
        // ゼロのページから写すと、写し先はゼロになる
        m.copy(chunk - 1, 5 * chunk, 2)
        assertEquals(0.toByte(), m.read(chunk - 1))
        assertEquals(0.toByte(), m.read(chunk))
        assertEquals(2, m.writtenChunks)
    }

    @Test
    fun `上限を超える書き込みはRamExhaustedになり、数は上限を超えない`() {
        val budget = Budget(2L * chunk)
        val m = WrittenPagesMemory(MemoryLimits(1, 1), budget)
        m.writeByte(0, 1)
        m.writeByte(chunk, 1)
        val e = assertThrows<RamExhausted> { m.writeByte(2 * chunk, 1) }
        assertTrue("out of memory" in e.message!!, e.message)
        assertThrows<RamExhausted> { m.fill(3, 3 * chunk, 3 * chunk + 1) }
        assertEquals(2, m.writtenChunks)
        assertEquals(2L * chunk, budget.used)
        // 数えたページへの書き込みはできる
        m.writeLong(chunk + 8, 5)
        assertEquals(5L, m.readLong(chunk + 8))
    }

    @Test
    fun `書き直しても数は減らず、閉じるとまとめて返す`() {
        val budget = Budget(Long.MAX_VALUE)
        val m = WrittenPagesMemory(MemoryLimits(1, 1), budget)
        for (i in 0 until 4) m.writeByte(i * chunk, 1)
        // 「解放」してゼロに戻しても、数は最高値のまま
        m.fill(0, 0, page)
        assertEquals(4, m.writtenChunks)
        assertEquals(4L * chunk, budget.used)
        m.releaseAll()
        m.releaseAll()
        assertEquals(0L, budget.used)
        // 閉じた後は数えない
        m.writeByte(10 * chunk, 1)
        assertEquals(0L, budget.used)
    }

    @Test
    fun `growは増やした大きさを数えず両端の4KiBだけを数え、範囲外はEndiveと同じエラー`() {
        val budget = Budget(Long.MAX_VALUE)
        val m = WrittenPagesMemory(MemoryLimits(1, 3), budget)
        assertThrows<WasmRuntimeException> { m.readInt(page) }.also { assertTrue("out of bounds memory" in it.message!!, it.message) }
        assertThrows<WasmRuntimeException> { m.writeByte(page, 1) }
        assertThrows<WasmRuntimeException> { m.writeI32(-4, 1) }
        assertThrows<WasmRuntimeException> { m.readBytes(page - 1, 2) }
        assertThrows<WasmRuntimeException> { m.copy(0, page - 1, 2) }
        assertEquals(1, m.grow(2))
        assertEquals(3, m.pages())
        assertEquals(-1, m.grow(1))
        // 増やした所の最初と最後の 4 KiB（アロケーターが管理の情報を書く所）だけを先に数える
        assertEquals(2L * chunk, budget.used)
        m.writeByte(3 * page - 1, 5)
        m.writeByte(page, 5)
        assertEquals(5.toByte(), m.read(3 * page - 1))
        assertEquals(2, m.writtenChunks)
        m.writeByte(2 * page, 5)
        assertEquals(3, m.writtenChunks)
        assertThrows<WasmRuntimeException> { m.readShort(3 * page - 1) }
    }

    @Test
    fun `両端の4KiBを数えられなければgrowは失敗し、断ったことを知らせる`() {
        val budget = Budget(chunk.toLong())
        var refused = 0
        val m = WrittenPagesMemory(MemoryLimits(1, 4), budget) { refused++ }
        assertEquals(-1, m.grow(1))
        assertEquals(1, m.pages())
        assertEquals(0L, budget.used)
        assertEquals(1, refused)
        // アドレス空間の上限で断ったときは、RAM のせいではないので知らせない
        val wide = WrittenPagesMemory(MemoryLimits(1, 2), Budget(Long.MAX_VALUE)) { refused++ }
        assertEquals(-1, wide.grow(2))
        assertEquals(1, refused)
    }

    @Test
    fun `データセグメントのページはROMで数えず、書くと中身を写して1回だけ数える`() {
        val module = EndiveRuntime().load(TestModules.MEMORY)
        val budget = Budget(Long.MAX_VALUE)
        val config = WasmInstanceConfig.builder()
            .hostFunction("env", "print", module.imports.single().functionType!!) { _, _ -> LongArray(0) }
            .writtenMemoryLimit(4, budget)
            .build()
        module.instantiate(config).use { instance ->
            val memory = instance.memory!!
            assertEquals(1, romChunks(memory))
            assertEquals(0, writtenChunks(memory))
            assertEquals("hello", String(memory.read(16, 5)))
            memory.writeU8(100, 1)
            memory.writeU8(200, 2)
            assertEquals(1, writtenChunks(memory))
            assertEquals("hello", String(memory.read(16, 5)))
            assertEquals(chunk.toLong(), budget.used)
        }
        assertEquals(0L, budget.used)
    }

    @Test
    fun `ROMのページから写しても中身が合う`() {
        val module = EndiveRuntime().load(TestModules.MEMORY)
        val config = WasmInstanceConfig.builder()
            .hostFunction("env", "print", module.imports.single().functionType!!) { _, _ -> LongArray(0) }
            .writtenMemoryLimit(4, Budget(Long.MAX_VALUE))
            .build()
        module.instantiate(config).use { instance ->
            val raw = instance.memory!!
            // 別の 4 KiB へ写す（写し元は ROM のまま）
            raw.write(chunk + 16, raw.read(16, 5))
            assertEquals("hello", String(raw.read(chunk + 16, 5)))
            assertEquals(1, writtenChunks(raw))
        }
    }

    @Test
    fun `同じ4KiBの中の重なる写しが合う`() {
        val m = WrittenPagesMemory(MemoryLimits(1, 1), Budget(Long.MAX_VALUE))
        m.write(10, "abcdef".toByteArray())
        m.copy(12, 10, 6)
        assertEquals("ababcdef", String(m.readBytes(10, 8)))
    }

    @Test
    fun `ランダムな読み書きがByteBufferMemoryと同じ結果になる`() {
        val rnd = Random(370)
        val pages = 2
        val m = WrittenPagesMemory(MemoryLimits(pages, pages), Budget(Long.MAX_VALUE))
        val ref = ByteBufferMemory(MemoryLimits(pages, pages))
        val size = pages * page
        fun addr(len: Int): Int = if (rnd.nextBoolean()) {
            (rnd.nextInt(1, size / chunk) * chunk + rnd.nextInt(-16, 16)).coerceIn(0, size - len)
        } else {
            rnd.nextInt(0, size - len + 1)
        }
        repeat(20_000) {
            when (rnd.nextInt(9)) {
                0 -> addr(1).let { a -> rnd.nextInt().toByte().let { v -> m.writeByte(a, v); ref.writeByte(a, v) } }
                1 -> addr(2).let { a -> rnd.nextInt().toShort().let { v -> m.writeShort(a, v); ref.writeShort(a, v) } }
                2 -> addr(4).let { a -> rnd.nextInt().let { v -> m.writeI32(a, v); ref.writeI32(a, v) } }
                3 -> addr(8).let { a -> rnd.nextLong().let { v -> m.writeLong(a, v); ref.writeLong(a, v) } }
                4 -> {
                    val len = rnd.nextInt(0, 300)
                    val a = addr(len)
                    val data = if (rnd.nextBoolean()) ByteArray(len) else rnd.nextBytes(len)
                    m.write(a, data)
                    ref.write(a, data)
                }
                5 -> {
                    val len = rnd.nextInt(0, 3 * chunk)
                    val a = addr(len)
                    val v = if (rnd.nextBoolean()) 0 else rnd.nextInt().toByte()
                    m.fill(v, a, a + len)
                    ref.fill(v, a, a + len)
                }
                6 -> {
                    val len = rnd.nextInt(0, 3 * chunk)
                    val src = addr(len)
                    val dest = if (rnd.nextBoolean()) (src + rnd.nextInt(-100, 100)).coerceIn(0, size - len) else addr(len)
                    m.copy(dest, src, len)
                    ref.copy(dest, src, len)
                }
                7 -> addr(8).let { a ->
                    assertEquals(ref.readLong(a), m.readLong(a))
                    assertEquals(ref.readInt(a), m.readInt(a))
                    assertEquals(ref.readShort(a), m.readShort(a))
                    assertEquals(ref.read(a), m.read(a))
                }
                else -> {
                    val len = rnd.nextInt(0, 500)
                    val a = addr(len)
                    assertArrayEquals(ref.readBytes(a, len), m.readBytes(a, len))
                }
            }
        }
        assertArrayEquals(ref.readBytes(0, size), m.readBytes(0, size))
    }

    @Test
    fun `ホストの書き込みが上限を超えたら、OUT_OF_MEMORYのトラップになる`() {
        val module = EndiveRuntime().load(TestModules.MEMORY)
        val config = WasmInstanceConfig.builder()
            .hostFunction("env", "print", module.imports.single().functionType!!) { _, _ -> LongArray(0) }
            .writtenMemoryLimit(4, Budget(chunk.toLong()))
            .build()
        module.instantiate(config).use { instance ->
            val memory = instance.memory!!
            memory.writeI32(0, 1)
            val e = assertThrows<WasmTrapException> { memory.writeI32(chunk, 1) }
            assertEquals(WasmTrapKind.OUT_OF_MEMORY, e.kind)
        }
    }

    @Test
    fun `Luaが書き換えた区画で数えるメモリで動き、上限を超えるとトラップになる`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/lua.wasm")) { "lua.wasm がない" }.use { it.readBytes() }
        val module = EndiveRuntime().load(bytes)
        val ok = TestWasiP1(listOf("lua", "-e", "local t = {} for i = 1, 1000 do t[i] = tostring(i) end print(#t)"))
        module.instantiate(ok.config(module) { writtenMemoryLimit(1024, Budget(Long.MAX_VALUE)) }).use { instance ->
            try {
                instance.call("_start")
            } catch (e: TestWasiP1.Exit) {
                assertEquals(0, e.code, ok.stderr.toString(Charsets.UTF_8))
            }
            assertEquals("1000\n", ok.stdout.toString(Charsets.UTF_8))
            val memory = instance.memory!!
            val written = writtenChunks(memory)
            assertTrue(written in 1 until memory.pages * (page / chunk), "書き換え $written")
        }
        // 64 KiB では足りない（lua.wasm はトラップで止まる。not enough memory で止まるのは #409）
        val small = TestWasiP1(listOf("lua", "-e", "local t = {} for i = 1, 100000 do t[i] = tostring(i) end"))
        module.instantiate(small.config(module) { writtenMemoryLimit(1024, Budget(64L * 1024)) }).use { instance ->
            val e = assertThrows<WasmTrapException> { instance.call("_start") }
            assertEquals(WasmTrapKind.OUT_OF_MEMORY, e.kind)
        }
    }
}
