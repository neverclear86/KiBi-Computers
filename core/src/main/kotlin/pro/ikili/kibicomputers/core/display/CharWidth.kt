package pro.ikili.kibicomputers.core.display

import org.jetbrains.annotations.ApiStatus

/**
 * 文字画面で1つの文字が何セル分の幅で描かれるか（#139）。
 *
 * 全角の文字（East Asian Width が W・F のもの。CJK の漢字・かな・ハングル・全角記号・絵文字の主な範囲）は 2 セル、
 * それ以外は 1 セル。表は Unicode の East Asian Width を簡略にしたもので、主な範囲だけを持つ。
 *
 * 文字画面のバッファ（[TextBuffer]）は1セルに1コードポイントを持つ。全角の文字は、そのセルと右隣のセルの2つ分に
 * 描かれ、右隣のセルの文字は描かれない（背景色だけが描かれる）。書き込む側は、全角の文字の右隣のセルに空白を置く。
 */
@ApiStatus.Internal
public object CharWidth {
    // 全角の範囲（始まりと終わり。どちらも含む）。昇順に並べる
    private val WIDE = intArrayOf(
        0x1100, 0x115F, // ハングル字母（初声）
        0x231A, 0x231B, // 時計の絵文字
        0x2E80, 0x303E, // CJK の部首・記号と句読点（和文の空白 U+3000 を含む）
        0x3041, 0x33FF, // ひらがな・カタカナ・注音・CJK の互換文字など
        0x3400, 0x4DBF, // CJK 統合漢字 拡張 A
        0x4E00, 0x9FFF, // CJK 統合漢字
        0xA000, 0xA4CF, // イ文字
        0xA960, 0xA97F, // ハングル字母 拡張 A
        0xAC00, 0xD7A3, // ハングル音節
        0xF900, 0xFAFF, // CJK 互換漢字
        0xFE10, 0xFE19, // 縦書き用の形
        0xFE30, 0xFE6F, // CJK 互換形・小字形
        0xFF00, 0xFF60, // 全角の英数字・記号
        0xFFE0, 0xFFE6, // 全角の記号
        0x1F300, 0x1F64F, // 絵文字（記号・顔）
        0x1F900, 0x1F9FF, // 絵文字（補助）
        0x20000, 0x2FFFD, // CJK 統合漢字 拡張 B〜F
        0x30000, 0x3FFFD, // CJK 統合漢字 拡張 G〜
    )

    /** 全角の文字が占めるセル数。 */
    public const val WIDE_CELLS: Int = 2

    /** 半角の文字が占めるセル数。 */
    public const val NARROW_CELLS: Int = 1

    /** [codePoint] が描かれるセル数（1 か 2）。 */
    @JvmStatic
    public fun of(codePoint: Int): Int = if (isWide(codePoint)) WIDE_CELLS else NARROW_CELLS

    /** [codePoint] が全角（2 セル）で描かれるか。 */
    @JvmStatic
    public fun isWide(codePoint: Int): Boolean {
        if (codePoint < WIDE[0]) return false
        // 範囲の数は少ないので二分探索で十分
        var lo = 0
        var hi = WIDE.size / 2 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                codePoint < WIDE[mid * 2] -> hi = mid - 1
                codePoint > WIDE[mid * 2 + 1] -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }
}
