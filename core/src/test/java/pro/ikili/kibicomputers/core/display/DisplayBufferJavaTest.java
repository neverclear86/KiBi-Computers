package pro.ikili.kibicomputers.core.display;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 画面バッファが Java から自然に使えることを、コンパイルが通るかで確かめるテスト。
 */
class DisplayBufferJavaTest {
    @Test
    void textBufferFromJava() {
        TextBuffer b = new TextBuffer(4, 2);
        int next = b.write(0, 0, "hi", TextBuffer.DEFAULT_FOREGROUND, TextBuffer.DEFAULT_BACKGROUND);
        assertEquals(2, next);
        b.set(3, 1, 'x', 0xFF0000, 0x000000);
        b.fill(0, 1, 2, 1, TextBuffer.BLANK, 0, 0);
        b.copy(0, 0, 4, 1, 0, 1);
        b.clear();
        b.clear(0xFFFFFF, 0x0000FF);
        b.resize(8, 3);
        assertEquals(8, b.getWidth());
        assertEquals(3, b.getHeight());
        assertEquals(0x0000FF, b.getBackground(0, 0));
        assertEquals(TextBuffer.BLANK, b.copyOf().getCodePoint(0, 0));
        b.setCursor(2, 1, true);
        assertEquals(2, b.getCursorX());
        assertEquals(1, b.getCursorY());
        assertEquals(true, b.getCursorVisible());
    }

    @Test
    void pixelBufferFromJava() {
        PixelBuffer b = new PixelBuffer(2, 2);
        b.set(0, 0, 0x112233);
        b.fill(0, 1, 2, 1, 0x445566);
        b.setPixels(1, 0, 1, 1, new int[] {7}, 0, 1);
        int[] out = new int[4];
        b.getPixels(0, 0, 2, 2, out, 0, 2);
        assertEquals(0x112233, out[0]);
        assertEquals(7, out[1]);
        b.copy(0, 0, 1, 1, 1, 1);
        b.clear();
        b.clear(PixelBuffer.DEFAULT_COLOR);
        b.resize(3, 3);
        assertEquals(3, b.getWidth());
        assertEquals(0, b.get(2, 2));
        assertEquals(true, b.contentEquals(b.copyOf()));
    }
}
