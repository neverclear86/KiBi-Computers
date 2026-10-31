package pro.ikili.kibicomputers.core.display

/**
 * 画面の中身を、ブロックの正面にどう並べるか（#139）。
 *
 * 中身の大きさは文字のセルで決める。1セルは横 [CELL_WIDTH]・縦 [CELL_HEIGHT] の単位（フォントの単位。半角の文字1つが
 * ちょうど入る大きさ）。ピクセル画面は文字画面と同じ範囲に重ねる（3段目では 1 セルあたり 2x4 ピクセル）。
 *
 * 中身は縦横比を保ったまま、正面から余白を除いた範囲に収まる最大の大きさにし、真ん中に置く。
 * 長さはブロックを 1 とする単位（大型モニター #138 では、結合した画面全体の大きさを渡す）。
 *
 * @property columns 文字画面の横のセル数
 * @property rows 文字画面の縦のセル数
 * @property scale フォントの 1 単位が、ブロックの単位でいくつになるか
 * @property offsetX 正面の左端から中身の左端までの長さ（ブロックの単位）
 * @property offsetY 正面の上端から中身の上端までの長さ（ブロックの単位）
 */
public class ScreenLayout private constructor(
    @JvmField public val columns: Int,
    @JvmField public val rows: Int,
    @JvmField public val scale: Float,
    @JvmField public val offsetX: Float,
    @JvmField public val offsetY: Float,
) {
    /** 中身の幅（フォントの単位）。 */
    public val contentWidth: Int
        get() = columns * CELL_WIDTH

    /** 中身の高さ（フォントの単位）。 */
    public val contentHeight: Int
        get() = rows * CELL_HEIGHT

    override fun toString(): String = "ScreenLayout(${columns}x$rows, scale=$scale, offset=($offsetX, $offsetY))"

    public companion object {
        /** 1セルの幅（フォントの単位）。 */
        public const val CELL_WIDTH: Int = 4

        /** 1セルの高さ（フォントの単位）。 */
        public const val CELL_HEIGHT: Int = 8

        /** 正面の縁から中身までの余白（ブロックの単位）。画面の枠の太さで、テクスチャの 2 ピクセル分（2/16）。 */
        public const val DEFAULT_MARGIN: Float = 2f / 16f

        /**
         * [columns]x[rows] セルの中身を、幅 [faceWidth]・高さ [faceHeight] の正面の、縁から [margin] 内側に収める。
         *
         * @throws IllegalArgumentException セル数が正でないとき、または余白を除いた範囲が残らないとき
         */
        @JvmStatic
        @JvmOverloads
        @Throws(IllegalArgumentException::class)
        public fun fit(
            columns: Int,
            rows: Int,
            faceWidth: Float = 1f,
            faceHeight: Float = 1f,
            margin: Float = DEFAULT_MARGIN,
        ): ScreenLayout {
            require(columns > 0 && rows > 0) { "cells must be positive: ${columns}x$rows" }
            val innerWidth = faceWidth - 2 * margin
            val innerHeight = faceHeight - 2 * margin
            require(innerWidth > 0 && innerHeight > 0) {
                "no room inside the margin: ${faceWidth}x$faceHeight, margin $margin"
            }
            val scale = minOf(innerWidth / (columns * CELL_WIDTH), innerHeight / (rows * CELL_HEIGHT))
            val offsetX = (faceWidth - columns * CELL_WIDTH * scale) / 2
            val offsetY = (faceHeight - rows * CELL_HEIGHT * scale) / 2
            return ScreenLayout(columns, rows, scale, offsetX, offsetY)
        }
    }
}
