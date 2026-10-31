package pro.ikili.kibicomputers.core.display

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ScreenTierTest {
    @Test
    fun levelsAreOneToThreeInOrder() {
        assertEquals(listOf(1, 2, 3), ScreenTier.entries.map { it.level })
        for (tier in ScreenTier.entries) {
            assertEquals(tier, ScreenTier.fromLevel(tier.level))
        }
        assertEquals(ScreenTier.MIN_LEVEL, ScreenTier.entries.first().level)
        assertEquals(ScreenTier.MAX_LEVEL, ScreenTier.entries.last().level)
    }

    @Test
    fun unknownLevelIsRejected() {
        assertThrows<IllegalArgumentException> { ScreenTier.fromLevel(0) }
        assertThrows<IllegalArgumentException> { ScreenTier.fromLevel(4) }
    }

    @Test
    fun higherTiersNeverShrink() {
        for ((low, high) in ScreenTier.entries.zipWithNext()) {
            assertTrue(high.maxColumns >= low.maxColumns, "$high columns")
            assertTrue(high.maxRows >= low.maxRows, "$high rows")
            assertTrue(high.maxColors >= low.maxColors, "$high colors")
            assertTrue(high.maxPixelWidth >= low.maxPixelWidth, "$high pixel width")
            assertTrue(high.maxPixelHeight >= low.maxPixelHeight, "$high pixel height")
        }
    }

    @Test
    fun onlyFirstTierIsMonochromeAndOnlyThirdHasPixels() {
        assertEquals(listOf(true, false, false), ScreenTier.entries.map { it.isMonochrome })
        assertEquals(listOf(false, false, true), ScreenTier.entries.map { it.supportsPixels })
    }

    @Test
    fun buffersHaveTheTierSize() {
        for (tier in ScreenTier.entries) {
            val text = tier.createTextBuffer()
            assertEquals(tier.maxColumns, text.width)
            assertEquals(tier.maxRows, text.height)
        }
        val pixels = ScreenTier.PIXEL.createPixelBuffer()
        assertEquals(320, pixels.width)
        assertEquals(200, pixels.height)
        assertFalse(ScreenTier.COLOR.supportsPixels)
        assertThrows<IllegalStateException> { ScreenTier.COLOR.createPixelBuffer() }
    }
}
