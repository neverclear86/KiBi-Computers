package pro.ikili.kibicomputers.core.display

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ScreenRenderingTest {
    @Test
    fun `かな・漢字・全角記号は全角、英数字と半角カナは半角`() {
        for (c in listOf('あ', 'ア', '漢', '　', 'Ａ', '。', '한')) assertEquals(2, CharWidth.of(c.code), "$c")
        for (c in listOf('a', 'Z', '0', ' ', '~', 'ｱ', 'é')) assertEquals(1, CharWidth.of(c.code), "$c")
        assertTrue(CharWidth.isWide(0x20B9F)) // 𠮟（拡張 B）
        assertTrue(CharWidth.isWide(0x1F600)) // 絵文字
        assertFalse(CharWidth.isWide(-1))
        assertFalse(CharWidth.isWide(Character.MAX_CODE_POINT))
    }

    @Test
    fun `背景は同じ色が続くところをまとめ、透明の色は渡さない`() {
        val b = TextBuffer(6, 2)
        b.fill(1, 0, 3, 1, ' '.code, 0xFFFFFF, 0xFF0000)
        b.set(4, 0, ' '.code, 0xFFFFFF, 0x00FF00)
        b.fill(0, 1, 6, 1, ' '.code, 0xFFFFFF, 0x0000FF)
        val runs = mutableListOf<List<Int>>()
        ScreenCells.forEachBackgroundRun(b, TextBuffer.DEFAULT_BACKGROUND) { x, y, len, rgb -> runs += listOf(x, y, len, rgb) }
        assertEquals(
            listOf(listOf(1, 0, 3, 0xFF0000), listOf(4, 0, 1, 0x00FF00), listOf(0, 1, 6, 0x0000FF)),
            runs,
        )
    }

    @Test
    fun `文字は空白と制御文字を飛ばし、全角は2セルで右隣を飛ばす`() {
        val b = TextBuffer(6, 1)
        b.write(0, 0, "a\u0001あxb", 0x112233, 0)
        b.set(5, 0, '漢'.code, 0x445566, 0) // 右端の全角は描かない
        val glyphs = mutableListOf<List<Int>>()
        ScreenCells.forEachGlyph(b) { x, y, cp, fg, cells -> glyphs += listOf(x, y, cp, fg, cells) }
        // "x" は「あ」の右隣のセルにあるので描かない
        assertEquals(
            listOf(listOf(0, 0, 'a'.code, 0x112233, 1), listOf(2, 0, 'あ'.code, 0x112233, 2), listOf(4, 0, 'b'.code, 0x112233, 1)),
            glyphs,
        )
        assertFalse(ScreenCells.isVisible('　'.code))
    }

    @Test
    fun `中身は縦横比を保って余白の内側の真ん中に収まる`() {
        // 50x16 セル = 200x128 単位。幅で決まる
        val l = ScreenLayout.fit(50, 16)
        assertEquals(200, l.contentWidth)
        assertEquals(128, l.contentHeight)
        assertEquals(0.75f / 200, l.scale, 1e-7f)
        assertEquals(2f / 16, l.offsetX, 1e-6f)
        assertEquals((1f - 128 * l.scale) / 2, l.offsetY, 1e-6f)
        // 縦長の面では高さで決まる
        val tall = ScreenLayout.fit(10, 10, 1f, 0.5f, 0f)
        assertEquals(0.5f / 80, tall.scale, 1e-7f)
        assertEquals((1f - 40 * tall.scale) / 2, tall.offsetX, 1e-6f)
        assertEquals(0f, tall.offsetY, 1e-6f)
        assertThrows<IllegalArgumentException> { ScreenLayout.fit(0, 1) }
        assertThrows<IllegalArgumentException> { ScreenLayout.fit(1, 1, 0.1f, 0.1f, 0.05f) }
    }

    @Test
    fun `遠い画面ほど省いて描き、大きい画面は遠くまで描く`() {
        assertEquals(ScreenDetail.FULL, ScreenDetail.forDistance(0.0))
        assertEquals(ScreenDetail.FULL, ScreenDetail.forDistance(24.0 * 24.0))
        assertEquals(ScreenDetail.COLORS_ONLY, ScreenDetail.forDistance(25.0 * 25.0))
        assertEquals(ScreenDetail.NONE, ScreenDetail.forDistance(65.0 * 65.0))
        assertEquals(ScreenDetail.FULL, ScreenDetail.forDistance(40.0 * 40.0, 2))
    }

    @Test
    fun `書き込むたびに revision が増える`() {
        val t = TextBuffer(2, 2)
        var r = t.revision
        for (op in listOf<(TextBuffer) -> Unit>(
            { it.set(0, 0, 'a'.code, 0, 0) },
            { it.write(0, 1, "b", 0, 0) },
            { it.fill(0, 0, 1, 1, 'c'.code, 0, 0) },
            { it.copy(0, 0, 1, 1, 1, 0) },
            { it.clear() },
            { it.resize(3, 3) },
            { it.set(99, 99, 'z'.code, 0, 0) }, // 画面の外でも増える
        )) {
            op(t)
            assertNotEquals(r, t.revision)
            r = t.revision
        }
        val p = PixelBuffer(2, 2)
        var pr = p.revision
        for (op in listOf<(PixelBuffer) -> Unit>(
            { it.set(0, 0, 1) },
            { it.fill(0, 0, 2, 2, 2) },
            { it.copy(0, 0, 1, 1, 1, 1) },
            { it.setPixels(0, 0, 1, 1, intArrayOf(3), 0, 1) },
            { it.clear() },
            { it.resize(3, 3) },
        )) {
            op(p)
            assertNotEquals(pr, p.revision)
            pr = p.revision
        }
    }
}
