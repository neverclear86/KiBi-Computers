package pro.ikili.kibicomputers.core.display;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画面の描画用の補助（#139）が Java から自然に使えることを、コンパイルが通るかで確かめるテスト。
 */
class ScreenRenderingJavaTest {
    @Test
    void helpersFromJava() {
        assertEquals(CharWidth.WIDE_CELLS, CharWidth.of('あ'));
        assertTrue(CharWidth.isWide('漢'));

        TextBuffer b = new TextBuffer(3, 1);
        b.write(0, 0, "ab", 0xFFFFFF, 0xFF0000);
        long revision = b.getRevision();
        List<Integer> seen = new ArrayList<>();
        ScreenCells.forEachBackgroundRun(b, TextBuffer.DEFAULT_BACKGROUND, (x, y, length, rgb) -> seen.add(length));
        ScreenCells.forEachGlyph(b, (x, y, codePoint, foreground, cells) -> seen.add(codePoint));
        assertEquals(List.of(2, (int) 'a', (int) 'b'), seen);
        assertTrue(ScreenCells.isVisible('a'));
        assertEquals(revision, b.getRevision());

        ScreenLayout layout = ScreenLayout.fit(50, 16);
        ScreenLayout custom = ScreenLayout.fit(10, 10, 2f, 1f, ScreenLayout.DEFAULT_MARGIN);
        assertEquals(ScreenLayout.CELL_WIDTH * 50, layout.getContentWidth());
        assertTrue(custom.scale > 0 && custom.offsetX > 0 && custom.columns == 10 && custom.rows == 10);

        assertEquals(ScreenDetail.FULL, ScreenDetail.forDistance(1.0));
        assertEquals(ScreenDetail.FULL, ScreenDetail.forDistance(1.0, 2));
        assertTrue(ScreenDetail.COLORS_DISTANCE > ScreenDetail.FULL_DISTANCE);
        assertEquals(0L, new PixelBuffer(1, 1).getRevision());
    }
}
