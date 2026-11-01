package pro.ikili.kibicomputers.core.runtime.endive

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.runtime.TestWasiP1
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import run.endive.runtime.ByteBufferMemory
import run.endive.runtime.WasmRuntimeException
import run.endive.wasm.types.MemoryLimits
import kotlin.random.Random

/** 書き込んだページだけ確保する線形メモリ（#334） */
class LazyMemoryTest {
    private val page = WasmMemory.PAGE_SIZE

    @Test
    fun `書き込むまでページを確保せず、読むとゼロ`() {
        val m = LazyMemory(MemoryLimits(4, 8))
        assertEquals(4, m.pages())
        assertEquals(0, m.committedPages)
        assertEquals(0, m.readInt(3 * page + 100))
        assertEquals(0L, m.readLong(page - 4)) // ページをまたぐ
        assertArrayEquals(ByteArray(page + 10), m.readBytes(page - 5, page + 10))
        assertEquals(0, m.committedPages)

        m.writeI32(page + 8, 0x12345678)
        assertEquals(1, m.committedPages)
        assertEquals(0x12345678, m.readInt(page + 8))
        assertEquals(0x78.toByte(), m.read(page + 8))
        // ページをまたぐ書き込みは両方のページを確保する
        m.writeLong(3 * page - 4, -1L)
        assertEquals(3, m.committedPages)
        assertEquals(-1L, m.readLong(3 * page - 4))
    }

    @Test
    fun `ゼロだけの書き込みとゼロのfillは確保しない`() {
        val m = LazyMemory(MemoryLimits(2, 2))
        m.write(10, ByteArray(page * 2 - 10))
        m.fill(0, 0, 2 * page)
        m.zero()
        m.copy(page, 0, page)
        assertEquals(0, m.committedPages)
        m.fill(7, page - 1, page + 1)
        assertEquals(2, m.committedPages)
        assertEquals(7.toByte(), m.read(page - 1))
        assertEquals(7.toByte(), m.read(page))
        // ゼロのページから写すと、写し先はゼロになる
        m.copy(page - 1, page + 100, 2)
        assertEquals(0.toByte(), m.read(page - 1))
        assertEquals(0.toByte(), m.read(page))
    }

    @Test
    fun `growは確保せず、範囲外の読み書きはEndiveと同じ範囲外のエラー`() {
        val m = LazyMemory(MemoryLimits(1, 3))
        assertThrows<WasmRuntimeException> { m.readInt(page) }.also { assertTrue("out of bounds memory" in it.message!!, it.message) }
        assertThrows<WasmRuntimeException> { m.writeByte(page, 1) }
        assertThrows<WasmRuntimeException> { m.writeI32(-4, 1) }
        assertThrows<WasmRuntimeException> { m.read(-1) }
        assertThrows<WasmRuntimeException> { m.readBytes(page - 1, 2) }
        assertThrows<WasmRuntimeException> { m.write(page - 1, ByteArray(2) { 1 }) }
        assertThrows<WasmRuntimeException> { m.copy(0, page - 1, 2) }
        assertThrows<WasmRuntimeException> { m.fill(1, page - 1, page + 1) }
        assertEquals(1, m.grow(2))
        assertEquals(3, m.pages())
        assertEquals(-1, m.grow(1))
        assertEquals(0, m.committedPages)
        m.writeByte(3 * page - 1, 5)
        assertEquals(5.toByte(), m.read(3 * page - 1))
        assertEquals(1, m.committedPages)
        assertThrows<WasmRuntimeException> { m.readShort(3 * page - 1) }
    }

    @Test
    fun `ランダムな読み書きがByteBufferMemoryと同じ結果になる`() {
        val rnd = Random(334)
        val pages = 4
        val lazy = LazyMemory(MemoryLimits(pages, pages))
        val ref = ByteBufferMemory(MemoryLimits(pages, pages))
        val size = pages * page
        // ページの境目の近くを多めに選ぶ
        fun addr(len: Int): Int = if (rnd.nextBoolean()) {
            (rnd.nextInt(1, pages) * page + rnd.nextInt(-16, 16)).coerceIn(0, size - len)
        } else {
            rnd.nextInt(0, size - len + 1)
        }
        repeat(20_000) {
            when (rnd.nextInt(9)) {
                0 -> addr(1).let { a -> rnd.nextInt().toByte().let { v -> lazy.writeByte(a, v); ref.writeByte(a, v) } }
                1 -> addr(2).let { a -> rnd.nextInt().toShort().let { v -> lazy.writeShort(a, v); ref.writeShort(a, v) } }
                2 -> addr(4).let { a -> rnd.nextInt().let { v -> lazy.writeI32(a, v); ref.writeI32(a, v) } }
                3 -> addr(8).let { a -> rnd.nextLong().let { v -> lazy.writeLong(a, v); ref.writeLong(a, v) } }
                4 -> {
                    val len = rnd.nextInt(0, 300)
                    val a = addr(len)
                    val data = if (rnd.nextBoolean()) ByteArray(len) else rnd.nextBytes(len)
                    lazy.write(a, data)
                    ref.write(a, data)
                }
                5 -> {
                    val len = rnd.nextInt(0, 2 * page)
                    val a = addr(len)
                    val v = if (rnd.nextBoolean()) 0 else rnd.nextInt().toByte()
                    lazy.fill(v, a, a + len)
                    ref.fill(v, a, a + len)
                }
                6 -> {
                    val len = rnd.nextInt(0, 2 * page)
                    val src = addr(len)
                    // 重なる写しも混ぜる
                    val dest = if (rnd.nextBoolean()) (src + rnd.nextInt(-100, 100)).coerceIn(0, size - len) else addr(len)
                    lazy.copy(dest, src, len)
                    ref.copy(dest, src, len)
                }
                7 -> addr(8).let { a ->
                    assertEquals(ref.readLong(a), lazy.readLong(a))
                    assertEquals(ref.readInt(a), lazy.readInt(a))
                    assertEquals(ref.readShort(a), lazy.readShort(a))
                    assertEquals(ref.read(a), lazy.read(a))
                }
                else -> {
                    val len = rnd.nextInt(0, 500)
                    val a = addr(len)
                    assertArrayEquals(ref.readBytes(a, len), lazy.readBytes(a, len))
                }
            }
        }
        assertArrayEquals(ref.readBytes(0, size), lazy.readBytes(0, size))
    }

    @Test
    fun `EndiveRuntimeで作るwasi-libcのプログラムのメモリは、触ったページだけ確保する`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/lua.wasm")) { "lua.wasm がない" }.use { it.readBytes() }
        val module = EndiveRuntime().load(bytes)
        val wasi = TestWasiP1(listOf("lua", "-e", "local t = {} for i = 1, 1000 do t[i] = tostring(i) end print(#t)"))
        module.instantiate(wasi.config(module)).use { instance ->
            try {
                instance.call("_start")
            } catch (e: TestWasiP1.Exit) {
                assertEquals(0, e.code, wasi.stderr.toString(Charsets.UTF_8))
            }
            assertEquals("1000\n", wasi.stdout.toString(Charsets.UTF_8))
            val memory = instance.memory!!
            val committed = committedPages(memory)
            assertTrue(committed in 1 until memory.pages, "確保 $committed / 宣言 ${memory.pages}")
        }
    }
}
