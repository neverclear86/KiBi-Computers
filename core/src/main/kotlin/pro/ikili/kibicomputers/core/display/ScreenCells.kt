package pro.ikili.kibicomputers.core.display

/**
 * 文字画面のバッファを描くときの、セルのまとめ方（#139）。描画そのものは接続層が行う。
 *
 * 描く数を減らすため、背景は同じ色が横に続くセルを1つの矩形にまとめ、文字は描くものがあるセルだけを返す。
 */
public object ScreenCells {
    private const val RGB_MASK = 0xFFFFFF
    private const val IDEOGRAPHIC_SPACE = 0x3000

    /**
     * [buffer] の背景を、行ごとに同じ色が横に続くセルをまとめた矩形として [consumer] に渡す。
     * 色が [transparent] のセルは描かなくてよい（画面の地の色と同じ）ので渡さない。
     *
     * 上の行から、各行の中では左から順に渡す。
     */
    @JvmStatic
    public fun forEachBackgroundRun(buffer: TextBuffer, transparent: Int, consumer: BackgroundRunConsumer) {
        val skip = transparent and RGB_MASK
        for (y in 0 until buffer.height) {
            var x = 0
            while (x < buffer.width) {
                val rgb = buffer.getBackground(x, y)
                var end = x + 1
                while (end < buffer.width && buffer.getBackground(end, y) == rgb) end++
                if (rgb != skip) consumer.accept(x, y, end - x, rgb)
                x = end
            }
        }
    }

    /**
     * [buffer] の、描く文字があるセルを [consumer] に渡す。空白と制御文字のセルは渡さない。
     *
     * 全角の文字（[CharWidth]）は幅 2 で渡し、右隣のセルは飛ばす。右端の列にある全角の文字は、
     * 描く場所が足りないので渡さない。上の行から、各行の中では左から順に渡す。
     */
    @JvmStatic
    public fun forEachGlyph(buffer: TextBuffer, consumer: GlyphConsumer) {
        for (y in 0 until buffer.height) {
            var x = 0
            while (x < buffer.width) {
                val cp = buffer.getCodePoint(x, y)
                val cells = CharWidth.of(cp)
                if (isVisible(cp) && x + cells <= buffer.width) {
                    consumer.accept(x, y, cp, buffer.getForeground(x, y), cells)
                }
                // 全角の文字の右隣のセルの文字は描かない（右端で描けなかったときも同じ）
                x += cells
            }
        }
    }

    /** [codePoint] が描くべき文字か（空白・和文の空白・制御文字でないか）。 */
    @JvmStatic
    public fun isVisible(codePoint: Int): Boolean =
        codePoint != TextBuffer.BLANK && codePoint != IDEOGRAPHIC_SPACE && !Character.isISOControl(codePoint)
}

/** [ScreenCells.forEachBackgroundRun] が渡す、同じ背景色が横に続くセルの並び。 */
public fun interface BackgroundRunConsumer {
    /** 行 [y] の、列 [x] から [length] セルが背景色 [rgb]（`0xRRGGBB`）。 */
    public fun accept(x: Int, y: Int, length: Int, rgb: Int)
}

/** [ScreenCells.forEachGlyph] が渡す、描く文字のあるセル。 */
public fun interface GlyphConsumer {
    /** 列 [x]・行 [y] から [cells] セル分（1 か 2）に、文字 [codePoint] を前景色 [foreground]（`0xRRGGBB`）で描く。 */
    public fun accept(x: Int, y: Int, codePoint: Int, foreground: Int, cells: Int)
}
