package pro.ikili.kibicomputers.core.display

import org.jetbrains.annotations.ApiStatus

/**
 * 画面をどこまで細かく描くか（#139）。遠くの画面は文字が読めないので、文字を省いて描画の負荷を下げる。
 *
 * 距離のしきい値は仮の値（#251 で確定）。
 */
@ApiStatus.Internal
public enum class ScreenDetail {
    /** 背景・ピクセル・文字をすべて描く。 */
    FULL,

    /** 背景とピクセルだけを描き、文字は描かない。 */
    COLORS_ONLY,

    /** 中身を描かない（画面の地の色だけ）。 */
    NONE,
    ;

    public companion object {
        /** 1 ブロックの画面で、これより近ければ文字まで描く（ブロックの数）。 */
        public const val FULL_DISTANCE: Double = 24.0

        /** 1 ブロックの画面で、これより近ければ背景とピクセルを描く（ブロックの数）。 */
        public const val COLORS_DISTANCE: Double = 64.0

        /**
         * 画面の中心からカメラまでの距離の2乗 [distanceSquared] に応じた細かさ。
         * [size] は画面の大きさ（長い方の辺のブロックの数）。大きい画面は遠くからでも読めるので、しきい値をその倍にする。
         */
        @JvmStatic
        @JvmOverloads
        public fun forDistance(distanceSquared: Double, size: Int = 1): ScreenDetail {
            val k = size.coerceAtLeast(1).toDouble()
            val full = FULL_DISTANCE * k
            val colors = COLORS_DISTANCE * k
            return when {
                distanceSquared <= full * full -> FULL
                distanceSquared <= colors * colors -> COLORS_ONLY
                else -> NONE
            }
        }
    }
}
