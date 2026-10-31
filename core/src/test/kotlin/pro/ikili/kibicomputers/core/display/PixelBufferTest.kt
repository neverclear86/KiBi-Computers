package pro.ikili.kibicomputers.core.display

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PixelBufferTest {
    private fun PixelBuffer.all(): IntArray = IntArray(width * height).also { getPixels(0, 0, width, height, it, 0, width) }

    @Test
    fun `作ったばかりのバッファは黒`() {
        val b = PixelBuffer(3, 2)
        assertArrayEquals(IntArray(6), b.all())
        assertThrows<IllegalArgumentException> { PixelBuffer(0, 1) }
        assertThrows<IllegalArgumentException> { PixelBuffer(1 shl 20, 1 shl 20) }
    }

    @Test
    fun `setとget`() {
        val b = PixelBuffer(2, 2)
        b.set(1, 0, 0xFF123456.toInt())
        assertEquals(0x123456, b.get(1, 0))
        b.set(2, 0, 1)
        b.set(0, -1, 1)
        assertArrayEquals(intArrayOf(0, 0x123456, 0, 0), b.all())
        assertThrows<IndexOutOfBoundsException> { b.get(2, 0) }
    }

    @Test
    fun `fillは画面の内側だけを塗る`() {
        val b = PixelBuffer(3, 3)
        b.fill(1, -1, 5, 2, 7)
        assertArrayEquals(intArrayOf(0, 7, 7, 0, 0, 0, 0, 0, 0), b.all())
        b.fill(Int.MIN_VALUE, 0, Int.MAX_VALUE, 3, 9)
        assertEquals(7, b.get(1, 0))
    }

    @Test
    fun `copyは重なっても写す前の内容を写す`() {
        val b = PixelBuffer(3, 3)
        b.setPixels(0, 0, 3, 3, intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9), 0, 3)
        b.copy(0, 0, 2, 2, 1, 1)
        assertArrayEquals(intArrayOf(1, 2, 3, 4, 1, 2, 7, 4, 5), b.all())
        b.copy(1, 1, 2, 2, -1, -1)
        assertArrayEquals(intArrayOf(1, 2, 3, 4, 5, 2, 7, 4, 5), b.all())
    }

    @Test
    fun `setPixelsはoffsetとstrideに従い画面の外を捨てる`() {
        val b = PixelBuffer(3, 2)
        // 2x2 の画像を (2, -1) に置くと、画像の左下の1ピクセルだけが (2, 0) に入る
        val src = intArrayOf(-1, 10, 11, 99, 12, 13, 99)
        b.setPixels(2, -1, 2, 2, src, 1, 3)
        assertArrayEquals(intArrayOf(0, 0, 12, 0, 0, 0), b.all())
        assertThrows<IndexOutOfBoundsException> { b.setPixels(0, 0, 2, 2, src, 3, 3) }
        assertThrows<IndexOutOfBoundsException> { b.setPixels(0, 0, 1, 1, src, -1, 1) }
        assertThrows<IllegalArgumentException> { b.setPixels(0, 0, 3, 1, src, 0, 2) }
        b.setPixels(0, 0, 0, 5, IntArray(0), 0, 0)
    }

    @Test
    fun `getPixelsは画面の外の部分に書かない`() {
        val b = PixelBuffer(2, 2)
        b.setPixels(0, 0, 2, 2, intArrayOf(1, 2, 3, 4), 0, 2)
        val dst = IntArray(9) { -1 }
        b.getPixels(-1, -1, 3, 3, dst, 0, 3)
        assertArrayEquals(intArrayOf(-1, -1, -1, -1, 1, 2, -1, 3, 4), dst)
    }

    @Test
    fun `clearとresizeとcopyOf`() {
        val b = PixelBuffer(2, 1)
        b.clear(0xABCDEF)
        assertArrayEquals(intArrayOf(0xABCDEF, 0xABCDEF), b.all())
        b.resize(3, 2)
        assertArrayEquals(intArrayOf(0xABCDEF, 0xABCDEF, 0, 0, 0, 0), b.all())
        val c = b.copyOf()
        assertTrue(c.contentEquals(b))
        c.set(0, 0, 1)
        assertFalse(c.contentEquals(b))
        b.clear()
        assertArrayEquals(IntArray(6), b.all())
    }
}
