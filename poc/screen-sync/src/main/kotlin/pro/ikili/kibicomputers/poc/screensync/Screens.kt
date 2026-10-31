package pro.ikili.kibicomputers.poc.screensync

/**
 * 測定用の画面バッファ。本番のモデルは #135 で作る。ここでは方式の比較に要る最小限だけを持つ。
 *
 * 描画命令は [log] があればそこにも書く（方式 B の送信内容）。サーバーとクライアントで同じ
 * 描画コードを使うので、命令を再生すれば同じ内容になる。
 */
sealed interface Screen {
    val width: Int
    val height: Int
    var log: Buf?

    /** 画面全体の生のバイト数（方式 C の入力の大きさ）。 */
    val rawSize: Int

    /** 画面全体をバイト列にする（方式 C・キーフレーム用）。 */
    fun snapshot(out: ByteArray)
    fun loadSnapshot(src: ByteArray)

    /** 方式 A: [prev] との差分を [out] に書き、[prev] を今の内容にそろえる。 */
    fun encodeDiff(prev: Screen, out: Buf)
    fun applyDiff(r: Reader)

    /** 方式 B: 描画命令を再生する。 */
    fun applyCommands(r: Reader)

    fun duplicate(): Screen
    fun sameAs(other: Screen): Boolean
}

private object Op {
    const val T_SET = 1
    const val T_FILL = 2
    const val T_COPY = 3
    const val P_SET = 10
    const val P_RECT = 11
    const val P_LINE = 12
    const val P_BLIT = 13
    const val P_COPY = 14
}

/** 文字セルの画面。1セルは文字（コードポイント）・前景色・背景色（RGB）。 */
class TextScreen(override val width: Int, override val height: Int) : Screen {
    val cp = IntArray(width * height) { ' '.code }
    val fg = IntArray(width * height) { 0xFFFFFF }
    val bg = IntArray(width * height)
    override var log: Buf? = null

    override val rawSize: Int get() = width * height * 10

    fun set(x: Int, y: Int, s: String, f: Int, b: Int) {
        log?.let {
            it.byte(Op.T_SET); it.varint(x); it.varint(y); it.rgb(f); it.rgb(b)
            val u = s.toByteArray(Charsets.UTF_8)
            it.varint(u.size); it.put(u)
        }
        if (y !in 0 until height) return
        var cx = x
        var i = 0
        while (i < s.length && cx < width) {
            val c = s.codePointAt(i)
            if (cx >= 0) {
                val k = y * width + cx
                cp[k] = c; fg[k] = f; bg[k] = b
            }
            cx++
            i += Character.charCount(c)
        }
    }

    fun fill(x: Int, y: Int, w: Int, h: Int, c: Int, f: Int, b: Int) {
        log?.let {
            it.byte(Op.T_FILL); it.varint(x); it.varint(y); it.varint(w); it.varint(h)
            it.varint(c); it.rgb(f); it.rgb(b)
        }
        for (yy in maxOf(0, y) until minOf(height, y + h)) {
            for (xx in maxOf(0, x) until minOf(width, x + w)) {
                val k = yy * width + xx
                cp[k] = c; fg[k] = f; bg[k] = b
            }
        }
    }

    /** 範囲を (dx, dy) だけずらして写す（スクロール）。 */
    fun copy(x: Int, y: Int, w: Int, h: Int, dx: Int, dy: Int) {
        log?.let {
            it.byte(Op.T_COPY); it.varint(x); it.varint(y); it.varint(w); it.varint(h)
            it.svarint(dx); it.svarint(dy)
        }
        copyRegion(width, height, x, y, w, h, dx, dy, cp, fg, bg)
    }

    override fun snapshot(out: ByteArray) {
        // 面ごとに並べる（圧縮が効きやすい）
        val n = width * height
        for (i in 0 until n) {
            val c = cp[i]
            out[i * 4] = (c shr 24).toByte(); out[i * 4 + 1] = (c shr 16).toByte()
            out[i * 4 + 2] = (c shr 8).toByte(); out[i * 4 + 3] = c.toByte()
        }
        var o = n * 4
        for (plane in arrayOf(fg, bg)) {
            for (i in 0 until n) {
                val c = plane[i]
                out[o++] = (c shr 16).toByte(); out[o++] = (c shr 8).toByte(); out[o++] = c.toByte()
            }
        }
    }

    override fun loadSnapshot(src: ByteArray) {
        val n = width * height
        for (i in 0 until n) {
            cp[i] = ((src[i * 4].toInt() and 0xFF) shl 24) or ((src[i * 4 + 1].toInt() and 0xFF) shl 16) or
                ((src[i * 4 + 2].toInt() and 0xFF) shl 8) or (src[i * 4 + 3].toInt() and 0xFF)
        }
        var o = n * 4
        for (plane in arrayOf(fg, bg)) {
            for (i in 0 until n) {
                plane[i] = ((src[o].toInt() and 0xFF) shl 16) or ((src[o + 1].toInt() and 0xFF) shl 8) or
                    (src[o + 2].toInt() and 0xFF)
                o += 3
            }
        }
    }

    /**
     * 変わったセルの連続（run）ごとに「前の run からの距離・長さ・セル」を書く。
     * セルは `(コードポイント << 1) | 色が変わったか` と、変わったときだけ前景色・背景色。
     */
    override fun encodeDiff(prev: Screen, out: Buf) {
        prev as TextScreen
        val n = width * height
        var i = 0
        var last = 0
        while (i < n) {
            if (cp[i] == prev.cp[i] && fg[i] == prev.fg[i] && bg[i] == prev.bg[i]) {
                i++
                continue
            }
            var j = i
            while (j < n && !(cp[j] == prev.cp[j] && fg[j] == prev.fg[j] && bg[j] == prev.bg[j])) j++
            out.varint(i - last); out.varint(j - i)
            var pf = -1
            var pb = -1
            for (k in i until j) {
                val attr = fg[k] != pf || bg[k] != pb
                out.varint((cp[k] shl 1) or (if (attr) 1 else 0))
                if (attr) {
                    out.rgb(fg[k]); out.rgb(bg[k]); pf = fg[k]; pb = bg[k]
                }
                prev.cp[k] = cp[k]; prev.fg[k] = fg[k]; prev.bg[k] = bg[k]
            }
            last = j
            i = j
        }
    }

    override fun applyDiff(r: Reader) {
        var pos = 0
        while (r.hasMore) {
            pos += r.varint()
            val len = r.varint()
            var f = 0
            var b = 0
            for (k in pos until pos + len) {
                val v = r.varint()
                if (v and 1 != 0) {
                    f = r.rgb(); b = r.rgb()
                }
                cp[k] = v ushr 1; fg[k] = f; bg[k] = b
            }
            pos += len
        }
    }

    override fun applyCommands(r: Reader) {
        while (r.hasMore) {
            when (r.byte()) {
                Op.T_SET -> {
                    val x = r.varint(); val y = r.varint(); val f = r.rgb(); val b = r.rgb()
                    val u = ByteArray(r.varint()); r.get(u, 0, u.size)
                    set(x, y, String(u, Charsets.UTF_8), f, b)
                }
                Op.T_FILL -> fill(r.varint(), r.varint(), r.varint(), r.varint(), r.varint(), r.rgb(), r.rgb())
                Op.T_COPY -> copy(r.varint(), r.varint(), r.varint(), r.varint(), r.svarint(), r.svarint())
                else -> error("unknown op")
            }
        }
    }

    override fun duplicate(): TextScreen = TextScreen(width, height).also {
        cp.copyInto(it.cp); fg.copyInto(it.fg); bg.copyInto(it.bg)
    }

    override fun sameAs(other: Screen): Boolean = other is TextScreen &&
        cp.contentEquals(other.cp) && fg.contentEquals(other.fg) && bg.contentEquals(other.bg)
}

/** ピクセルの画面（RGB）。 */
class PixelScreen(override val width: Int, override val height: Int) : Screen {
    val px = IntArray(width * height)
    override var log: Buf? = null

    override val rawSize: Int get() = width * height * 3

    fun setPixel(x: Int, y: Int, c: Int) {
        log?.let { it.byte(Op.P_SET); it.varint(x); it.varint(y); it.rgb(c) }
        if (x in 0 until width && y in 0 until height) px[y * width + x] = c
    }

    fun fillRect(x: Int, y: Int, w: Int, h: Int, c: Int) {
        log?.let { it.byte(Op.P_RECT); it.varint(x); it.varint(y); it.varint(w); it.varint(h); it.rgb(c) }
        for (yy in maxOf(0, y) until minOf(height, y + h)) {
            px.fill(c, yy * width + maxOf(0, x), yy * width + minOf(width, x + w))
        }
    }

    fun line(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
        log?.let { it.byte(Op.P_LINE); it.varint(x0); it.varint(y0); it.varint(x1); it.varint(y1); it.rgb(c) }
        // Bresenham
        var x = x0
        var y = y0
        val dx = kotlin.math.abs(x1 - x0)
        val dy = -kotlin.math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            if (x in 0 until width && y in 0 until height) px[y * width + x] = c
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    /** 画像の転送。[src] は w*h の RGB。 */
    fun blit(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
        log?.let {
            it.byte(Op.P_BLIT); it.varint(x); it.varint(y); it.varint(w); it.varint(h)
            for (c in src) it.rgb(c)
        }
        for (yy in 0 until h) {
            val ty = y + yy
            if (ty !in 0 until height) continue
            for (xx in 0 until w) {
                val tx = x + xx
                if (tx in 0 until width) px[ty * width + tx] = src[yy * w + xx]
            }
        }
    }

    fun copy(x: Int, y: Int, w: Int, h: Int, dx: Int, dy: Int) {
        log?.let {
            it.byte(Op.P_COPY); it.varint(x); it.varint(y); it.varint(w); it.varint(h); it.svarint(dx); it.svarint(dy)
        }
        copyRegion(width, height, x, y, w, h, dx, dy, px)
    }

    override fun snapshot(out: ByteArray) {
        var o = 0
        for (c in px) {
            out[o++] = (c shr 16).toByte(); out[o++] = (c shr 8).toByte(); out[o++] = c.toByte()
        }
    }

    override fun loadSnapshot(src: ByteArray) {
        var o = 0
        for (i in px.indices) {
            px[i] = ((src[o].toInt() and 0xFF) shl 16) or ((src[o + 1].toInt() and 0xFF) shl 8) or
                (src[o + 2].toInt() and 0xFF)
            o += 3
        }
    }

    /** 変わったピクセルの run ごとに「距離・長さ・RGB」を書く。 */
    override fun encodeDiff(prev: Screen, out: Buf) {
        prev as PixelScreen
        val n = px.size
        var i = 0
        var last = 0
        while (i < n) {
            if (px[i] == prev.px[i]) {
                i++
                continue
            }
            var j = i
            while (j < n && px[j] != prev.px[j]) j++
            out.varint(i - last); out.varint(j - i)
            for (k in i until j) {
                out.rgb(px[k]); prev.px[k] = px[k]
            }
            last = j
            i = j
        }
    }

    override fun applyDiff(r: Reader) {
        var pos = 0
        while (r.hasMore) {
            pos += r.varint()
            val len = r.varint()
            for (k in pos until pos + len) px[k] = r.rgb()
            pos += len
        }
    }

    override fun applyCommands(r: Reader) {
        while (r.hasMore) {
            when (r.byte()) {
                Op.P_SET -> setPixel(r.varint(), r.varint(), r.rgb())
                Op.P_RECT -> fillRect(r.varint(), r.varint(), r.varint(), r.varint(), r.rgb())
                Op.P_LINE -> line(r.varint(), r.varint(), r.varint(), r.varint(), r.rgb())
                Op.P_BLIT -> {
                    val x = r.varint(); val y = r.varint(); val w = r.varint(); val h = r.varint()
                    blit(x, y, w, h, IntArray(w * h) { r.rgb() })
                }
                Op.P_COPY -> copy(r.varint(), r.varint(), r.varint(), r.varint(), r.svarint(), r.svarint())
                else -> error("unknown op")
            }
        }
    }

    override fun duplicate(): PixelScreen = PixelScreen(width, height).also { px.copyInto(it.px) }

    override fun sameAs(other: Screen): Boolean = other is PixelScreen && px.contentEquals(other.px)
}

/** 重なりを考えて範囲を写す。はみ出す部分は切り捨てる。 */
private fun copyRegion(
    width: Int, height: Int, x: Int, y: Int, w: Int, h: Int, dx: Int, dy: Int, vararg planes: IntArray,
) {
    val x0 = maxOf(0, x); val y0 = maxOf(0, y)
    val x1 = minOf(width, x + w); val y1 = minOf(height, y + h)
    if (x0 >= x1 || y0 >= y1) return
    val rows = if (dy > 0) (y1 - 1 downTo y0) else (y0 until y1)
    for (plane in planes) {
        for (yy in rows) {
            val ty = yy + dy
            if (ty !in 0 until height) continue
            val sx0 = maxOf(x0, -dx); val sx1 = minOf(x1, width - dx)
            if (sx0 >= sx1) continue
            System.arraycopy(plane, yy * width + sx0, plane, ty * width + sx0 + dx, sx1 - sx0)
        }
    }
}
