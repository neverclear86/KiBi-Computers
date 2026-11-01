package pro.ikili.kibicomputers.core.display

import org.jetbrains.annotations.ApiStatus

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
@ApiStatus.Internal
public class TextBuffer
@Throws(IllegalArgumentException::class)
public constructor(width: Int, height: Int) {
    /** 横のセル数。 */
    public var width: Int = width
        private set

    /** 縦のセル数。 */
    public var height: Int = height
        private set

    /**
     * 書き込みのたびに増える番号。内容を変えうる操作（画面の外への書き込みも含む）を呼ぶと必ず増える。
     * 描画側（#139）が、前に描いたときから変わったかを安く調べるのに使う。
     */
    public var revision: Long = 0
        private set

    /** カーソルの列（0 から）。画面の外を指していてもよい（描く側が画面の内側のときだけ描く）。 */
    public var cursorX: Int = 0
        private set

    /** カーソルの行（0 から）。画面の外を指していてもよい。 */
    public var cursorY: Int = 0
        private set

    /** カーソルを表示するか。既定では表示しない（端末として書く側 [setCursor] で表示する）。 */
    public var cursorVisible: Boolean = false
        private set

    private var codePoints: IntArray
    private var foregrounds: IntArray
    private var backgrounds: IntArray

    /** 描画命令の記録先（画面の同期 #140）。見ているプレイヤーがいる間だけ [ScreenSyncSender] がつなぐ。 */
    internal var log: DrawLog? = null

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
        revision++
        if (x !in 0 until width || y !in 0 until height) return
        log?.record {
            byte(SyncOps.TEXT_SET)
            uint(x)
            uint(y)
            uint(codePoint)
            rgb(foreground)
            rgb(background)
        }
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
        revision++
        val fg = foreground and RGB_MASK
        val bg = background and RGB_MASK
        val inRow = y in 0 until height
        // 画面の内側に書いた文字だけを、書いた順に記録する（再生では同じ位置から書けば同じになる）
        val log = log?.takeIf { inRow && !it.overflow }
        var first = -1
        var count = 0
        var cx = x.toLong()
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            i += Character.charCount(c)
            if (inRow && cx in 0 until width) {
                val k = y * width + cx.toInt()
                val cp = if (c in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) REPLACEMENT else c
                codePoints[k] = cp
                foregrounds[k] = fg
                backgrounds[k] = bg
                if (first < 0) first = cx.toInt()
                count++
            }
            cx++
        }
        if (log != null && count > 0) {
            log.record {
                byte(SyncOps.TEXT_WRITE)
                uint(first)
                uint(y)
                rgb(fg)
                rgb(bg)
                uint(count)
                for (k in y * width + first until y * width + first + count) uint(codePoints[k])
            }
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
        revision++
        val fg = foreground and RGB_MASK
        val bg = background and RGB_MASK
        val x0 = Rects.start(x, width)
        val x1 = Rects.end(x, w, width)
        if (x0 >= x1) return
        val y0 = Rects.start(y, height)
        val y1 = Rects.end(y, h, height)
        if (y0 >= y1) return
        log?.record {
            byte(SyncOps.TEXT_FILL)
            uint(x0)
            uint(y0)
            uint(x1 - x0)
            uint(y1 - y0)
            uint(codePoint)
            rgb(fg)
            rgb(bg)
        }
        for (row in y0 until y1) {
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
        revision++
        log?.record {
            byte(SyncOps.TEXT_COPY)
            sint(x)
            sint(y)
            sint(w)
            sint(h)
            sint(dx)
            sint(dy)
        }
        Rects.copy(arrayOf(codePoints, foregrounds, backgrounds), width, height, x, y, w, h, dx, dy)
    }

    /**
     * カーソルを列 [x]・行 [y] に置き、表示するか [visible] を決める。前と同じなら何もしない。
     * セルの中身は変えない（カーソルを描くのは描画側。#348 の入力 GUI など）。
     *
     * @throws IllegalArgumentException 位置が負のとき
     */
    @Throws(IllegalArgumentException::class)
    public fun setCursor(x: Int, y: Int, visible: Boolean) {
        require(x >= 0 && y >= 0) { "cursor position must not be negative: ($x, $y)" }
        if (x == cursorX && y == cursorY && visible == cursorVisible) return
        revision++
        log?.record {
            byte(SyncOps.TEXT_CURSOR)
            uint(x)
            uint(y)
            byte(if (visible) 1 else 0)
        }
        cursorX = x
        cursorY = y
        cursorVisible = visible
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
        revision++
        log?.record {
            byte(SyncOps.TEXT_RESIZE)
            uint(newWidth)
            uint(newHeight)
        }
        codePoints =Rects.resize(codePoints, width, height, newWidth, newHeight, BLANK)
        foregrounds = Rects.resize(foregrounds, width, height, newWidth, newHeight, DEFAULT_FOREGROUND)
        backgrounds = Rects.resize(backgrounds, width, height, newWidth, newHeight, DEFAULT_BACKGROUND)
        width = newWidth
        height = newHeight
    }

    /** 大きさとすべてのセルが [other] と同じか。カーソルは比べない。 */
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
        it.cursorX = cursorX
        it.cursorY = cursorY
        it.cursorVisible = cursorVisible
    }

    /** 画面全体（キーフレーム）を書く。大きさに続けて、各セルの文字・前景色・背景色を左上から並べ、最後にカーソルを書く。 */
    internal fun writeSnapshot(out: SyncWriter) {
        out.uint(width)
        out.uint(height)
        for (i in codePoints.indices) {
            out.uint(codePoints[i])
            out.rgb(foregrounds[i])
            out.rgb(backgrounds[i])
        }
        out.uint(cursorX)
        out.uint(cursorY)
        out.byte(if (cursorVisible) 1 else 0)
    }

    /** [writeSnapshot] で書いた画面全体を読み込む。大きさが違えば合わせる。 */
    internal fun readSnapshot(input: SyncReader) {
        val w = input.uint()
        val h = input.uint()
        val n = Rects.area(w, h)
        // 1セルは少なくとも 7 バイトなので、足りなければ大きさを変える前に壊れたデータとして扱う
        require(input.remaining.toLong() >= n.toLong() * 7) { "text snapshot is truncated" }
        val cps = IntArray(n)
        val fgs = IntArray(n)
        val bgs = IntArray(n)
        for (i in 0 until n) {
            val cp = input.uint()
            checkCodePoint(cp)
            cps[i] = cp
            fgs[i] = input.rgb()
            bgs[i] = input.rgb()
        }
        val cx = input.uint()
        val cy = input.uint()
        val visible = input.byte() == 1
        revision++
        width = w
        height = h
        codePoints = cps
        foregrounds = fgs
        backgrounds = bgs
        cursorX = cx
        cursorY = cy
        cursorVisible = visible
    }

    /** [writeSnapshot] が書く大きさの目安（バイト）。 */
    internal val snapshotSize: Int
        get() = codePoints.size * 8 + 21

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
