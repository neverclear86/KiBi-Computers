package pro.ikili.kibicomputers.core.display

import org.jetbrains.annotations.ApiStatus

/**
 * 画面ブロックのティア。ティアで、その画面が出せる最大の解像度・色数と、ピクセル描画の可否が決まる（#136）。
 *
 * 実際に使える解像度・色数は、画面のティアとつないだ GPU カードのティア（#137）の低い方になる。
 * 単色の画面（[MONOCHROME]）は GPU なしで使える。
 *
 * 数値は仮の値で、正式な値はコンフィグのデフォルトとして #251 で確定する。
 *
 * @property level ティアの番号（1〜3）
 * @property maxColumns 文字画面の最大の横のセル数
 * @property maxRows 文字画面の最大の縦のセル数
 * @property maxColors 出せる色の数。2 は単色（前景と背景の2色）
 * @property maxPixelWidth ピクセル画面の最大の幅。ピクセル描画できないティアでは 0
 * @property maxPixelHeight ピクセル画面の最大の高さ。ピクセル描画できないティアでは 0
 */
@ApiStatus.Internal
public enum class ScreenTier(
    @JvmField public val level: Int,
    @JvmField public val maxColumns: Int,
    @JvmField public val maxRows: Int,
    @JvmField public val maxColors: Int,
    @JvmField public val maxPixelWidth: Int,
    @JvmField public val maxPixelHeight: Int,
) {
    /** 1段目: 単色の文字端末。GPU カードなしで使える。 */
    MONOCHROME(1, 50, 16, 2, 0, 0),

    /** 2段目: 色付きの文字端末（16色）。マウス入力もここから（#143）。 */
    COLOR(2, 80, 25, 16, 0, 0),

    /** 3段目: ピクセル描画ができる画面（256色）。ピクセルは 1 セルあたり 2x4。 */
    PIXEL(3, 160, 50, 256, 320, 200),
    ;

    /** ピクセル描画（#141）ができるか。Java からは `supportsPixels()`。 */
    @get:JvmName("supportsPixels")
    public val supportsPixels: Boolean
        get() = maxPixelWidth > 0 && maxPixelHeight > 0

    /** 単色（2色）だけの画面か。 */
    public val isMonochrome: Boolean
        get() = maxColors <= 2

    /** このティアの最大の大きさで、空の文字画面のバッファを作る。 */
    public fun createTextBuffer(): TextBuffer = TextBuffer(maxColumns, maxRows)

    /**
     * このティアの最大の大きさで、空のピクセル画面のバッファを作る。
     *
     * @throws IllegalStateException ピクセル描画できないティアのとき
     */
    @Throws(IllegalStateException::class)
    public fun createPixelBuffer(): PixelBuffer {
        check(supportsPixels) { "$this does not support pixel drawing" }
        return PixelBuffer(maxPixelWidth, maxPixelHeight)
    }

    public companion object {
        /** いちばん低いティアの番号。 */
        public const val MIN_LEVEL: Int = 1

        /** いちばん高いティアの番号。 */
        public const val MAX_LEVEL: Int = 3

        /**
         * 番号 [level]（1〜3）のティア。
         *
         * @throws IllegalArgumentException [level] が 1〜3 でないとき
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class)
        public fun fromLevel(level: Int): ScreenTier =
            entries.firstOrNull { it.level == level }
                ?: throw IllegalArgumentException("Unknown screen tier: $level")
    }
}
