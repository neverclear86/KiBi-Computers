package pro.ikili.kibicomputers.core.display

/**
 * 文字画面のバッファ。幅 [width]・高さ [height] の文字セルを持つ。
 *
 * 1つのセルは、文字（Unicode のコードポイント）・前景色・背景色からなる。
 * 色は `0xRRGGBB` の 24 ビット RGB で持ち、上位 8 ビットは捨てる。画面のティアに応じた色の丸め
 * （単色・色数の制限）はこのバッファではせず、書き込む側（ANSI の解釈など）が行う。
 *
 * 1セルに1コードポイントを置く。全角文字を2セルで扱うかどうかは、書き込む側とフォント（#139）で決める。
 *
 * 座標は左上が `(0, 0)`。1セルを読む関数は画面の外を指すと例外を投げ、書き込む関数は
 * 画面の内側だけに切り取って書く（画面からはみ出す描画をそのまま渡せるように）。
 * 描画は整数演算だけで行い、同じ操作を同じ順に行えばどこでも同じ内容になる。
 *
 * スレッドセーフではない。
 */
public class TextBuffer
@Throws(IllegalArgumentException::class)
public constructor(width: Int, height: Int) {
    /** 横のセル数。 */
    public var width: Int = width
        private set

    /** 縦のセル数。 */
    public var height: Int = height
        private set

    private var codePoints: IntArray
    private var foregrounds: IntArray
    private var backgrounds: IntArray

    init {
        val n = Rects.area(width, height)
        codePoints = IntArray(n).also { it.fill(BLANK) }
        foregrounds = IntArray(n).also { it.fill(DEFAULT_FOREGROUND) }
        backgrounds = IntArray(n).also { it.fill(DEFAULT_BACKGROUND) }
    }

    /** `(x, y)` のセルの文字（コードポイント）。 */
    @Throws(IndexOutOfBoundsException::class)
    public fun getCodePoint(x: Int, y: Int): Int = codePoints[index(x, y)]

    /** `(x, y)` のセルの前景色（`0xRRGGBB`）。 */
    @Throws(IndexOutOfBoundsException::class)
    public fun getForeground(x: Int, y: Int): Int = foregrounds[index(x, y)]

    /** `(x, y)` のセルの背景色（`0xRRGGBB`）。 */
    @Throws(IndexOutOfBoundsException::class)
    public fun getBackground(x: Int, y: Int): Int = backgrounds[index(x, y)]

    /**
     * `(x, y)` のセルを書き換える。画面の外なら何もしない。
     *
     * @throws IllegalArgumentException [codePoint] が Unicode のコードポイントでないとき
     */
    @Throws(IllegalArgumentException::class)
    public fun set(x: Int, y: Int, codePoint: Int, foreground: Int, background: Int) {
        checkCodePoint(codePoint)
        if (x !in 0 until width || y !in 0 until height) return
        val i = y * width + x
        codePoints[i] = codePoint
        foregrounds[i] = foreground and RGB_MASK
        backgrounds[i] = background and RGB_MASK
    }

    /**
     * `(x, y)` から右へ [text] を1コードポイントずつ1セルに書く。折り返しはせず、画面の外の分は捨てる。
     * 対になっていないサロゲートは U+FFFD に置き換える。
     *
     * @return 最後に書いた文字の次の x 座標（`x + コードポイントの数`）。画面の外に出た分も数える
     */
    public fun write(x: Int, y: Int, text: String, foreground: Int, background: Int): Int {
        val fg = foreground and RGB_MASK
        val bg = background and RGB_MASK
        val inRow = y in 0 until height
        var cx = x.toLong()
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            i += Character.charCount(c)
            if (inRow && cx in 0 until width) {
                val k = y * width + cx.toInt()
                codePoints[k] = if (c in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) REPLACEMENT else c
                foregrounds[k] = fg
                backgrounds[k] = bg
            }
            cx++
        }
        return cx.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * 矩形 `(x, y, w, h)` のセルをすべて同じ内容にする。画面の外の部分は無視する。
     *
     * @throws IllegalArgumentException [codePoint] が Unicode のコードポイントでないとき
     */
    @Throws(IllegalArgumentException::class)
    public fun fill(x: Int, y: Int, w: Int, h: Int, codePoint: Int, foreground: Int, background: Int) {
        checkCodePoint(codePoint)
        val fg = foreground and RGB_MASK
        val bg = background and RGB_MASK
        val x0 = Rects.start(x, width)
        val x1 = Rects.end(x, w, width)
        if (x0 >= x1) return
        for (row in Rects.start(y, height) until Rects.end(y, h, height)) {
            val from = row * width + x0
            val to = row * width + x1
            codePoints.fill(codePoint, from, to)
            foregrounds.fill(fg, from, to)
            backgrounds.fill(bg, from, to)
        }
    }

    /**
     * 矩形 `(x, y, w, h)` の内容を `(dx, dy)` だけずらした位置に写す（スクロールに使う）。
     *
     * 写し元・写し先とも画面の内側に切り取る。重なっていても写す前の内容が写る。
     * 写し先にならなかったセルは元のまま残るので、スクロールで空いた行は呼ぶ側が [fill] で消す。
     */
    public fun copy(x: Int, y: Int, w: Int, h: Int, dx: Int, dy: Int) {
        Rects.copy(arrayOf(codePoints, foregrounds, backgrounds), width, height, x, y, w, h, dx, dy)
    }

    /** 画面全体を空白にする。 */
    @JvmOverloads
    public fun clear(foreground: Int = DEFAULT_FOREGROUND, background: Int = DEFAULT_BACKGROUND) {
        fill(0, 0, width, height, BLANK, foreground, background)
    }

    /**
     * 大きさを変える。左上の重なる部分の内容は残し、増えた部分は空白（既定の色）にする。
     *
     * @throws IllegalArgumentException 大きさが正でないとき、または大きすぎるとき
     */
    @Throws(IllegalArgumentException::class)
    public fun resize(newWidth: Int, newHeight: Int) {
        Rects.area(newWidth, newHeight)
        codePoints = Rects.resize(codePoints, width, height, newWidth, newHeight, BLANK)
        foregrounds = Rects.resize(foregrounds, width, height, newWidth, newHeight, DEFAULT_FOREGROUND)
        backgrounds = Rects.resize(backgrounds, width, height, newWidth, newHeight, DEFAULT_BACKGROUND)
        width = newWidth
        height = newHeight
    }

    /** 大きさとすべてのセルが [other] と同じか。 */
    public fun contentEquals(other: TextBuffer): Boolean = width == other.width &&
        height == other.height &&
        codePoints.contentEquals(other.codePoints) &&
        foregrounds.contentEquals(other.foregrounds) &&
        backgrounds.contentEquals(other.backgrounds)

    /** 同じ内容の、独立したバッファを作る。 */
    public fun copyOf(): TextBuffer = TextBuffer(width, height).also {
        codePoints.copyInto(it.codePoints)
        foregrounds.copyInto(it.foregrounds)
        backgrounds.copyInto(it.backgrounds)
    }

    private fun index(x: Int, y: Int): Int {
        Rects.checkIndex(x, y, width, height)
        return y * width + x
    }

    public companion object {
        /** 空白のセルの文字（U+0020）。 */
        public const val BLANK: Int = ' '.code

        /** 既定の前景色（白）。 */
        public const val DEFAULT_FOREGROUND: Int = 0xFFFFFF

        /** 既定の背景色（黒）。 */
        public const val DEFAULT_BACKGROUND: Int = 0x000000

        private const val RGB_MASK = 0xFFFFFF
        private const val REPLACEMENT = 0xFFFD

        private fun checkCodePoint(codePoint: Int) {
            require(Character.isValidCodePoint(codePoint)) { "invalid code point: $codePoint" }
        }
    }
}
