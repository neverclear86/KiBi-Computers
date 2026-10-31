package pro.ikili.kibicomputers.core.display

/**
 * ピクセル画面のバッファ。幅 [width]・高さ [height] のピクセルを持つ。
 *
 * 1ピクセルは `0xRRGGBB` の 24 ビット RGB で、上位 8 ビットは捨てる（透明度は持たない）。
 * 画面のティアに応じた色の丸めはこのバッファではしない。
 *
 * 座標は左上が `(0, 0)`。1ピクセルを読む関数は画面の外を指すと例外を投げ、書き込む関数は
 * 画面の内側だけに切り取って書く。描画は整数演算だけで行う。線などの図形の描画は #141 で足す。
 *
 * スレッドセーフではない。
 */
public class PixelBuffer
@Throws(IllegalArgumentException::class)
public constructor(width: Int, height: Int) {
    /** 横のピクセル数。 */
    public var width: Int = width
        private set

    /** 縦のピクセル数。 */
    public var height: Int = height
        private set

    /**
     * 書き込みのたびに増える番号。内容を変えうる操作（画面の外への書き込みも含む）を呼ぶと必ず増える。
     * 描画側（#139）が、前に描いたときから変わったかを安く調べるのに使う。
     */
    public var revision: Long = 0
        private set

    private var pixels: IntArray = IntArray(Rects.area(width, height))

    /** `(x, y)` のピクセルの色（`0xRRGGBB`）。 */
    @Throws(IndexOutOfBoundsException::class)
    public fun get(x: Int, y: Int): Int {
        Rects.checkIndex(x, y, width, height)
        return pixels[y * width + x]
    }

    /** `(x, y)` のピクセルを [rgb] にする。画面の外なら何もしない。 */
    public fun set(x: Int, y: Int, rgb: Int) {
        revision++
        if (x !in 0 until width || y !in 0 until height) return
        pixels[y * width + x] = rgb and RGB_MASK
    }

    /** 矩形 `(x, y, w, h)` を [rgb] で塗る。画面の外の部分は無視する。 */
    public fun fill(x: Int, y: Int, w: Int, h: Int, rgb: Int) {
        revision++
        val c = rgb and RGB_MASK
        val x0 = Rects.start(x, width)
        val x1 = Rects.end(x, w, width)
        if (x0 >= x1) return
        for (row in Rects.start(y, height) until Rects.end(y, h, height)) {
            pixels.fill(c, row * width + x0, row * width + x1)
        }
    }

    /**
     * 矩形 `(x, y, w, h)` の内容を `(dx, dy)` だけずらした位置に写す。
     *
     * 写し元・写し先とも画面の内側に切り取る。重なっていても写す前の内容が写る。
     * 写し先にならなかったピクセルは元のまま残る。
     */
    public fun copy(x: Int, y: Int, w: Int, h: Int, dx: Int, dy: Int) {
        revision++
        Rects.copy(arrayOf(pixels), width, height, x, y, w, h, dx, dy)
    }

    /**
     * [src] の画像（幅 [w]・高さ [h]）を `(x, y)` に書く（画像の転送）。
     * 画像の `(i, j)` のピクセルは `src[offset + j * stride + i]`。画面の外の部分は捨てる。
     *
     * @throws IllegalArgumentException [w] か [h] が負、または [stride] が [w] より小さいとき
     * @throws IndexOutOfBoundsException [src] に画像の全体が入っていないとき
     */
    @Throws(IllegalArgumentException::class, IndexOutOfBoundsException::class)
    public fun setPixels(x: Int, y: Int, w: Int, h: Int, src: IntArray, offset: Int, stride: Int) {
        checkImage(w, h, src.size, offset, stride)
        revision++
        val x0 = Rects.start(x, width)
        val x1 = Rects.end(x, w, width)
        if (x0 >= x1) return
        for (row in Rects.start(y, height) until Rects.end(y, h, height)) {
            val from = offset + (row - y) * stride + (x0 - x)
            val to = row * width
            for (col in x0 until x1) pixels[to + col] = src[from + col - x0] and RGB_MASK
        }
    }

    /**
     * 矩形 `(x, y, w, h)` の内容を [dst] に読み出す。`(i, j)` のピクセルを `dst[offset + j * stride + i]` に書く。
     * 画面の外の部分には書かない（[dst] の元の値が残る）。
     *
     * @throws IllegalArgumentException [w] か [h] が負、または [stride] が [w] より小さいとき
     * @throws IndexOutOfBoundsException [dst] に矩形の全体が入らないとき
     */
    @Throws(IllegalArgumentException::class, IndexOutOfBoundsException::class)
    public fun getPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray, offset: Int, stride: Int) {
        checkImage(w, h, dst.size, offset, stride)
        val x0 = Rects.start(x, width)
        val x1 = Rects.end(x, w, width)
        if (x0 >= x1) return
        for (row in Rects.start(y, height) until Rects.end(y, h, height)) {
            System.arraycopy(pixels, row * width + x0, dst, offset + (row - y) * stride + (x0 - x), x1 - x0)
        }
    }

    /** 画面全体を [rgb] で塗る。 */
    @JvmOverloads
    public fun clear(rgb: Int = DEFAULT_COLOR) {
        revision++
        pixels.fill(rgb and RGB_MASK)
    }

    /**
     * 大きさを変える。左上の重なる部分の内容は残し、増えた部分は [DEFAULT_COLOR] にする。
     *
     * @throws IllegalArgumentException 大きさが正でないとき、または大きすぎるとき
     */
    @Throws(IllegalArgumentException::class)
    public fun resize(newWidth: Int, newHeight: Int) {
        Rects.area(newWidth, newHeight)
        revision++
        pixels = Rects.resize(pixels, width, height, newWidth, newHeight, DEFAULT_COLOR)
        width = newWidth
        height = newHeight
    }

    /** 大きさとすべてのピクセルが [other] と同じか。 */
    public fun contentEquals(other: PixelBuffer): Boolean =
        width == other.width && height == other.height && pixels.contentEquals(other.pixels)

    /** 同じ内容の、独立したバッファを作る。 */
    public fun copyOf(): PixelBuffer = PixelBuffer(width, height).also { pixels.copyInto(it.pixels) }

    public companion object {
        /** 既定の色（黒）。 */
        public const val DEFAULT_COLOR: Int = 0x000000

        private const val RGB_MASK = 0xFFFFFF

        private fun checkImage(w: Int, h: Int, size: Int, offset: Int, stride: Int) {
            require(w >= 0 && h >= 0) { "negative size: ${w}x$h" }
            require(stride >= w) { "stride $stride is smaller than width $w" }
            if (w == 0 || h == 0) return
            val last = offset.toLong() + (h - 1).toLong() * stride + w
            if (offset < 0 || last > size) {
                throw IndexOutOfBoundsException("image ${w}x$h at $offset (stride $stride) does not fit in $size")
            }
        }
    }
}
