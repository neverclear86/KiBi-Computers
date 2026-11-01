package pro.ikili.kibicomputers.core.display.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TerminalColorsTest {
    @Test
    fun `palette matches xterm cube and grays`() {
        assertEquals(0x000000, TerminalColors.palette(16))
        assertEquals(0xFFFFFF, TerminalColors.palette(231))
        assertEquals(0x5F87AF, TerminalColors.palette(16 + 36 * 1 + 6 * 2 + 3))
        assertEquals(0x080808, TerminalColors.palette(232))
        assertEquals(0xEEEEEE, TerminalColors.palette(255))
    }

    @Test
    fun `nearest256 returns exact palette colors`() {
        for (i in 16..255) {
            assertEquals(TerminalColors.palette(i), TerminalColors.palette(TerminalColors.nearest256(TerminalColors.palette(i))), "index $i")
        }
    }

    @Test
    fun `nearest16 picks closest basic color`() {
        assertEquals(1, TerminalColors.nearest16(0xC00000))
        assertEquals(9, TerminalColors.nearest16(0xF01010))
        assertEquals(0, TerminalColors.nearest16(0x101010))
        assertEquals(8, TerminalColors.nearest16(0x808080))
    }

    @Test
    fun `color mode from color count`() {
        assertEquals(ColorMode.MONOCHROME, ColorMode.forColors(2))
        assertEquals(ColorMode.ANSI16, ColorMode.forColors(16))
        assertEquals(ColorMode.ANSI256, ColorMode.forColors(256))
        assertEquals(ColorMode.TRUECOLOR, ColorMode.forColors(1 shl 24))
    }

    @Test
    fun `resolve with defaults`() {
        val v = TerminalColors.resolve(TerminalColors.DEFAULT, TerminalColors.DEFAULT, false, false, false, ColorMode.ANSI16)
        assertEquals(0xFFFFFF, (v ushr 32).toInt())
        assertEquals(0x000000, v.toInt())
    }
}
