package pro.ikili.kibicomputers.core.display

/**
 * 画面バッファで共通に使う、矩形の切り取りと範囲のコピー。
 *
 * 描画はサーバーとクライアントで同じ結果になるように、整数演算だけで行う（ADR 0031）。
 * 座標と大きさは `Int` の範囲なら何を渡しても溢れないよう、途中は `Long` で計算する。
 */
internal object Rects {
    /** `[start, start + length)` を `[0, limit)` に切り取った開始位置。切り取った結果が空なら [end] 以上になる。 */
    fun start(start: Int, limit: Int): Int = start.coerceIn(0, limit)

    /** `[start, start + length)` を `[0, limit)` に切り取った終了位置（含まない）。 */
    fun end(start: Int, length: Int, limit: Int): Int {
        if (length <= 0) return 0
        return (start.toLong() + length).coerceIn(0L, limit.toLong()).toInt()
    }

    /** 1次元の領域の [width]x[height] の要素数。大きすぎるときは例外。 */
    fun area(width: Int, height: Int): Int {
        require(width > 0 && height > 0) { "size must be positive: ${width}x$height" }
        val n = width.toLong() * height
        require(n <= MAX_CELLS) { "size too large: ${width}x$height" }
        return n.toInt()
    }

    /**
     * 行優先で並んだ [planes]（すべて幅 [width]・高さ [height]）の矩形 `(x, y, w, h)` を、
     * `(dx, dy)` だけずらした位置に写す。
     *
     * 写し元と写し先は画面の内側に切り取る。重なっていても、写す前の内容が写る（memmove と同じ）。
     * 写し先にならなかった部分は元の内容のまま残る。
     */
    fun copy(
        planes: Array<IntArray>,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        dx: Int,
        dy: Int,
    ) {
        // 写し元を画面に切り取る
        val sx0 = start(x, width)
        val sx1 = end(x, w, width)
        val sy0 = start(y, height)
        val sy1 = end(y, h, height)
        if (sx0 >= sx1 || sy0 >= sy1) return
        // 写し先を画面に切り取り、写し元もそれに合わせる
        val tx0 = (sx0.toLong() + dx).coerceIn(0L, width.toLong()).toInt()
        val tx1 = (sx1.toLong() + dx).coerceIn(0L, width.toLong()).toInt()
        val ty0 = (sy0.toLong() + dy).coerceIn(0L, height.toLong()).toInt()
        val ty1 = (sy1.toLong() + dy).coerceIn(0L, height.toLong()).toInt()
        if (tx0 >= tx1 || ty0 >= ty1) return
        val len = tx1 - tx0
        val srcX = (tx0.toLong() - dx).toInt()
        // 下へずらすときは下の行から写して、まだ写していない行を上書きしない
        if (dy > 0) {
            for (ty in ty1 - 1 downTo ty0) copyRow(planes, width, srcX, ty - dy, tx0, ty, len)
        } else {
            for (ty in ty0 until ty1) copyRow(planes, width, srcX, ty - dy, tx0, ty, len)
        }
    }

    private fun copyRow(planes: Array<IntArray>, width: Int, sx: Int, sy: Int, tx: Int, ty: Int, len: Int) {
        val from = sy * width + sx
        val to = ty * width + tx
        // 同じ配列の中の重なりは System.arraycopy が正しく扱う
        for (plane in planes) System.arraycopy(plane, from, plane, to, len)
    }

    /**
     * [old]（幅 [oldWidth]・高さ [oldHeight]）の左上の重なる部分を、新しい大きさの配列に写す。
     * 新しく増えた部分は [fill] で埋める。
     */
    fun resize(old: IntArray, oldWidth: Int, oldHeight: Int, newWidth: Int, newHeight: Int, fill: Int): IntArray {
        val out = IntArray(newWidth * newHeight)
        if (fill != 0) out.fill(fill)
        val w = minOf(oldWidth, newWidth)
        for (row in 0 until minOf(oldHeight, newHeight)) {
            System.arraycopy(old, row * oldWidth, out, row * newWidth, w)
        }
        return out
    }

    fun checkIndex(x: Int, y: Int, width: Int, height: Int) {
        if (x !in 0 until width || y !in 0 until height) {
            throw IndexOutOfBoundsException("($x, $y) is outside ${width}x$height")
        }
    }

    /** 1つの画面バッファが持てる要素（セル・ピクセル）の最大数。配列の上限より十分小さくする。 */
    const val MAX_CELLS: Long = 1L shl 26
}
