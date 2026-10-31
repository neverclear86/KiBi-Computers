package pro.ikili.kibicomputers.core.display;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画面のティアが Java から自然に使えることを、コンパイルが通るかで確かめるテスト。
 */
class ScreenTierJavaTest {
    @Test
    void screenTierFromJava() {
        ScreenTier tier = ScreenTier.fromLevel(ScreenTier.MAX_LEVEL);
        assertEquals(ScreenTier.PIXEL, tier);
        assertEquals(3, tier.level);
        assertTrue(tier.supportsPixels());
        assertEquals(tier.maxColumns, tier.createTextBuffer().getWidth());
        assertEquals(tier.maxPixelHeight, tier.createPixelBuffer().getHeight());
        assertTrue(ScreenTier.fromLevel(ScreenTier.MIN_LEVEL).isMonochrome());
        assertThrows(IllegalArgumentException.class, () -> ScreenTier.fromLevel(0));
    }
}
