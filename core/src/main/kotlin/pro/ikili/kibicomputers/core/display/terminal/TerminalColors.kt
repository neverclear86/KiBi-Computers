package pro.ikili.kibicomputers.core.display.terminal

import pro.ikili.kibicomputers.core.display.TextBuffer

/**
 * 端末の色の扱い（#61）。SGR で指定された色（既定・パレットの番号・24 ビット RGB）を、画面の色数に合わせて
 * `0xRRGGBB` に丸める。計算は整数だけで行い、同じ入力ならどこでも同じ結果になる。
 *
 * 色の指定（spec）は1つの `Int` で持つ: [DEFAULT]（-1）、パレットの番号（0〜255）、[rgb] で作った RGB。
 */
internal object TerminalColors {
    /** 既定の色（前景なら [TextBuffer.DEFAULT_FOREGROUND]、背景なら [TextBuffer.DEFAULT_BACKGROUND]）。 */
    const val DEFAULT: Int = -1

    private const val RGB_FLAG = 0x1000000
    private const val RGB_MASK = 0xFFFFFF
    private const val WHITE = TextBuffer.DEFAULT_FOREGROUND
    private const val BLACK = TextBuffer.DEFAULT_BACKGROUND

    /** 24 ビット RGB の指定を作る。 */
    fun rgb(rgb: Int): Int = RGB_FLAG or (rgb and RGB_MASK)

    /** [spec] が RGB の指定か。 */
    fun isRgb(spec: Int): Boolean = spec >= RGB_FLAG

    /**
     * 基本の 16 色（xterm の既定に近い値。仮の値）。0〜7 が普通の色、8〜15 が明るい色。
     * 既定の前景色（白）・背景色（黒）は 15 番・0 番と同じ値。
     */
    private val ANSI16 = intArrayOf(
        0x000000, 0xCD0000, 0x00CD00, 0xCDCD00, 0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
        0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00, 0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
    )

    // 6x6x6 の色の立方体の、各軸の段の値（xterm と同じ）
    private val CUBE_LEVELS = intArrayOf(0, 95, 135, 175, 215, 255)

    /** パレットの [index]（0〜255）番の色。16〜231 は 6x6x6 の立方体、232〜255 は 24 段の灰色（xterm と同じ）。 */
    fun palette(index: Int): Int = when {
        index < 16 -> ANSI16[index]
        index < 232 -> {
            val i = index - 16
            (CUBE_LEVELS[i / 36] shl 16) or (CUBE_LEVELS[i / 6 % 6] shl 8) or CUBE_LEVELS[i % 6]
        }
        else -> {
            val v = 8 + (index - 232) * 10
            (v shl 16) or (v shl 8) or v
        }
    }

    /** [rgb] にいちばん近い基本の 16 色の番号。同じ近さなら番号の小さい方。 */
    fun nearest16(rgb: Int): Int {
        var best = 0
        var bestDistance = Long.MAX_VALUE
        for (i in ANSI16.indices) {
            val d = distance(rgb, ANSI16[i])
            if (d < bestDistance) {
                best = i
                bestDistance = d
            }
        }
        return best
    }

    /** [rgb] にいちばん近い 256 色のパレットの番号（16〜255 から選ぶ。0〜15 は端末ごとに色が違うので使わない）。 */
    fun nearest256(rgb: Int): Int {
        val r = rgb shr 16 and 0xFF
        val g = rgb shr 8 and 0xFF
        val b = rgb and 0xFF
        val cube = 16 + 36 * nearestLevel(r) + 6 * nearestLevel(g) + nearestLevel(b)
        // 灰色の段は 8, 18, ..., 238
        val avg = (r + g + b) / 3
        val grayStep = ((avg - 8 + 5) / 10).coerceIn(0, 23)
        val gray = 232 + grayStep
        return if (distance(rgb, palette(gray)) < distance(rgb, palette(cube))) gray else cube
    }

    private fun nearestLevel(v: Int): Int {
        var best = 0
        for (i in 1 until CUBE_LEVELS.size) {
            if (Math.abs(CUBE_LEVELS[i] - v) < Math.abs(CUBE_LEVELS[best] - v)) best = i
        }
        return best
    }

    // 人の目の感じ方に寄せた重み付きの距離（緑を重く、青を軽く）
    private fun distance(a: Int, b: Int): Long {
        val dr = (a shr 16 and 0xFF) - (b shr 16 and 0xFF)
        val dg = (a shr 8 and 0xFF) - (b shr 8 and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return 2L * dr * dr + 4L * dg * dg + 3L * db * db
    }

    /** 明るさ（0〜255）。ITU-R BT.601 の重み。 */
    fun luminance(rgb: Int): Int = ((rgb shr 16 and 0xFF) * 299 + (rgb shr 8 and 0xFF) * 587 + (rgb and 0xFF) * 114) / 1000

    /**
     * 色の指定 [spec] を、色の出し方 [mode] で `0xRRGGBB` にする。[fallback] は [DEFAULT] のときの色。
     * [ColorMode.MONOCHROME] では丸めずに返す（白黒への丸めは前景と背景の組で [resolve] が行う）。
     */
    fun toRgb(spec: Int, mode: ColorMode, fallback: Int): Int = when {
        spec == DEFAULT -> fallback
        isRgb(spec) -> {
            val v = spec and RGB_MASK
            when (mode) {
                ColorMode.TRUECOLOR, ColorMode.MONOCHROME -> v
                ColorMode.ANSI256 -> palette(nearest256(v))
                ColorMode.ANSI16 -> ANSI16[nearest16(v)]
            }
        }
        mode == ColorMode.ANSI16 && spec >= 16 -> ANSI16[nearest16(palette(spec))]
        else -> palette(spec)
    }

    /**
     * 前景色・背景色の指定と属性から、セルに書く色を求める。結果は `前景 shl 32 or 背景`。
     *
     * - [bold] で前景が 0〜7 番なら、明るい色（8〜15 番）にする
     * - [inverse] で前景と背景を入れ替える（既定の色どうしも入れ替える）
     * - 単色では、背景を明るさで白か黒にし、前景をその反対にする（文字がいつも読めるように）
     * - [conceal] で前景を背景と同じにする
     */
    fun resolve(fg: Int, bg: Int, bold: Boolean, inverse: Boolean, conceal: Boolean, mode: ColorMode): Long {
        val fgSpec = if (bold && fg in 0..7) fg + 8 else fg
        var f = toRgb(fgSpec, mode, WHITE)
        var b = toRgb(bg, mode, BLACK)
        if (inverse) {
            val t = f
            f = b
            b = t
        }
        if (mode == ColorMode.MONOCHROME) {
            b = if (luminance(b) >= 128) WHITE else BLACK
            f = if (b == WHITE) BLACK else WHITE
        }
        if (conceal) f = b
        return (f.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)
    }
}

/** 画面の色の出し方。画面（と GPU）の色数から決まる。 */
internal enum class ColorMode {
    /** 白と黒の2色。 */
    MONOCHROME,

    /** 基本の 16 色。 */
    ANSI16,

    /** 256 色のパレット。 */
    ANSI256,

    /** 24 ビット RGB をそのまま。 */
    TRUECOLOR,
    ;

    companion object {
        /** 色数 [maxColors] の画面の出し方。2 以下は単色、16〜255 は 16 色、256 は 256 色、それより多ければ RGB。 */
        fun forColors(maxColors: Int): ColorMode = when {
            maxColors > 256 -> TRUECOLOR
            maxColors >= 256 -> ANSI256
            maxColors >= 16 -> ANSI16
            else -> MONOCHROME
        }
    }
}
