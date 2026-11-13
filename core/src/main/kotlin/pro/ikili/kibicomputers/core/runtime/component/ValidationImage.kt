package pro.ikili.kibicomputers.core.runtime.component

import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import java.io.ByteArrayOutputStream

/**
 * wasm-tools に渡す、検証用の写し（#262）。
 *
 * wasm-tools（WASM で動く）の検証の時間は、ほとんどが core モジュールの関数の本体の検証で、大きさにほぼ比例する
 * （300 KiB の lua.wasm で約 120 ms、16 MiB で約 5〜7 秒）。関数の本体は、p1 と同じく Endive が読み込むとき
 * （#263 の「パース → コンパイル」）に検証するので、ここでは二重に検証しない。写しでは:
 *
 * - core モジュールの関数の本体を、どの型にも合う `unreachable` だけの本体にする（関数の数と型は変えない）
 * - core モジュールのカスタム セクション（名前・デバッグ情報）を除く
 * - そのほか（型・import・export・テーブル・メモリ・グローバル・要素・データ、コンポーネントのすべてのセクション）はそのまま
 *
 * import・export と型が元と同じなので、コンポーネントとしての正しさ（インスタンス化の引数、canon の型、alias など）の
 * 検証の結果は、元のバイナリと変わらない。
 *
 * 見逃すのは関数の本体だけではない。写しが落とす・読まない core モジュールの部分の誤りも、wasm-tools には届かない:
 * core モジュールのカスタム セクションの壊れ（UTF-8 の誤り、途中で終わっている）と、コードセクションの本体の後ろの余り。
 * どれも実行されるバイトではないが、「wasm-tools の検証に通った」と言えるのはコンポーネントの層までで、
 * core モジュールの中身は、元のバイト列を Endive が読んで初めて確かめられる（#263）。
 *
 * 写しの中の位置は元と違う（core モジュールの本体とカスタム セクションを落とし、大きさの変わったセクションの長さを
 * 書き直す）ので、写しのどこが元のどこから来たかを持つ（[Image.originalOffset]）。wasm-tools のメッセージの位置
 * （`at offset 0x..`）を元のファイルの位置に直すのに使う。そのまま写した部分は元と同じ位置に、書き直した部分
 * （セクションの id と長さ、`unreachable` の本体）は、元のセクションの先頭に対応させる。
 */
internal object ValidationImage {
    private const val CORE_SEC_CUSTOM = 0
    private const val CORE_SEC_CODE = 10

    /** `unreachable` だけの関数の本体（ローカル変数なし、`unreachable`、`end`）と、その大きさ */
    private val STUB_BODY = byteArrayOf(0x03, 0x00, 0x00, 0x0b)

    /** 検証用の写し [bytes] と、その中の位置から元の位置への対応 */
    class Image internal constructor(
        val bytes: ByteArray,
        /** 区間ごとの、写しの中の先頭（昇順） */
        private val imageStarts: IntArray,
        /** 区間ごとの、対応する元の位置 */
        private val originStarts: IntArray,
        /** 区間が元をそのまま写したものか（`false` なら、区間のどこも [originStarts] の位置に対応させる） */
        private val exact: BooleanArray,
    ) {
        /** 写しの中の位置 [imageOffset] に対応する、元のバイト列の中の位置 */
        fun originalOffset(imageOffset: Long): Long {
            if (imageStarts.isEmpty() || imageOffset < 0) return imageOffset
            val key = if (imageOffset > Int.MAX_VALUE) Int.MAX_VALUE else imageOffset.toInt()
            var i = java.util.Arrays.binarySearch(imageStarts, key)
            if (i < 0) i = -i - 2
            if (i < 0) return imageOffset
            return if (exact[i]) originStarts[i] + (imageOffset - imageStarts[i]) else originStarts[i].toLong()
        }

        /** wasm-tools のメッセージの中の位置（`at offset 0x..`）を、元のバイト列の中の位置に直したもの */
        fun mapMessage(message: String): String = OFFSET.replace(message) { m ->
            val v = m.groupValues[1].toLongOrNull(16)
            if (v == null) m.value else "at offset 0x" + originalOffset(v).toString(16)
        }

        private companion object {
            val OFFSET = Regex("""at offset 0x([0-9a-fA-F]{1,15})""")
        }
    }

    /** [root]（[bytes] を読んだもの）の検証用の写し */
    @Throws(WasmLoadException::class)
    fun of(bytes: ByteArray, root: ParsedComponent): Image = writeComponent(bytes, root).toImage()

    /** 写しを書くもの。書いた区間ごとに、元のどこから来たかを覚える */
    private class Writer(sizeHint: Int) {
        val out = ByteArrayOutputStream(sizeHint)
        private var imageStarts = IntArray(16)
        private var originStarts = IntArray(16)
        private var exact = BooleanArray(16)
        private var n = 0

        val size: Int get() = out.size()

        private fun mark(image: Int, origin: Int, isExact: Boolean) {
            if (n > 0 && imageStarts[n - 1] == image) n--
            if (n == imageStarts.size) {
                imageStarts = imageStarts.copyOf(n * 2)
                originStarts = originStarts.copyOf(n * 2)
                exact = exact.copyOf(n * 2)
            }
            imageStarts[n] = image
            originStarts[n] = origin
            exact[n] = isExact
            n++
        }

        /** 元の [offset] から [length] バイトをそのまま写す */
        fun copy(bytes: ByteArray, offset: Int, length: Int) {
            if (length <= 0) return
            mark(size, offset, true)
            out.write(bytes, offset, length)
        }

        /** 新しく作ったバイト列を書く。どこも元の [anchor] に対応させる */
        fun emit(data: ByteArray, anchor: Int) {
            if (data.isEmpty()) return
            mark(size, anchor, false)
            out.write(data)
        }

        /** 別に書いた [child] を続けて書く（区間も写す） */
        fun append(child: Writer) {
            val base = size
            for (i in 0 until child.n) mark(base + child.imageStarts[i], child.originStarts[i], child.exact[i])
            child.out.writeTo(out)
        }

        fun toImage(): Image = Image(out.toByteArray(), imageStarts.copyOf(n), originStarts.copyOf(n), exact.copyOf(n))
    }

    private fun writeComponent(bytes: ByteArray, c: ParsedComponent): Writer {
        val w = Writer(c.range.length / 4)
        w.copy(bytes, c.range.offset, WasmBinaryHeader.SIZE)
        // セクションは隙間なく並ぶので、前のセクションの終わりが次のセクションの先頭（id と長さ）
        var begin = c.range.offset + WasmBinaryHeader.SIZE
        for (s in c.sections) {
            when (s) {
                is ComponentSection.CoreModule -> sectionOf(w, 1, stubModule(bytes, s.range), begin)
                is ComponentSection.Nested -> sectionOf(w, 4, writeComponent(bytes, s.component), begin)
                else -> {
                    if (begin < s.range.offset && s.range.offset - begin <= 6) {
                        // id と長さも含めて、元のとおりに写す
                        w.copy(bytes, begin, s.range.end - begin)
                    } else {
                        w.emit(header(idOf(s), s.range.length), begin)
                        w.copy(bytes, s.range.offset, s.range.length)
                    }
                }
            }
            begin = s.range.end
        }
        return w
    }

    private fun idOf(s: ComponentSection): Int = when (s) {
        is ComponentSection.Custom -> 0
        is ComponentSection.CoreModule -> 1
        is ComponentSection.CoreInstances -> 2
        is ComponentSection.CoreTypes -> 3
        is ComponentSection.Nested -> 4
        is ComponentSection.Instances -> 5
        is ComponentSection.Aliases -> 6
        is ComponentSection.Types -> 7
        is ComponentSection.Canons -> 8
        is ComponentSection.Imports -> 10
        is ComponentSection.Exports -> 11
    }

    /** core モジュールの写し（関数の本体を `unreachable` に、カスタム セクションを除く）。`ModulePrescan` を通ったものに使う */
    private fun stubModule(bytes: ByteArray, range: ByteRange): Writer {
        val w = Writer(range.length / 4)
        w.copy(bytes, range.offset, WasmBinaryHeader.SIZE)
        var pos = range.offset + WasmBinaryHeader.SIZE
        while (pos < range.end) {
            val begin = pos
            val id = bytes[pos++].toInt() and 0xff
            val (size, after) = readU32(bytes, pos, range.end)
            pos = after
            val start = pos
            pos += size
            if (pos > range.end) throw WasmLoadException("core モジュールのセクションが壊れている")
            when (id) {
                CORE_SEC_CUSTOM -> Unit
                CORE_SEC_CODE -> {
                    val (count, _) = readU32(bytes, start, pos)
                    val body = ByteArrayOutputStream(5 + count * STUB_BODY.size)
                    leb(body, count.toLong())
                    repeat(count) { body.write(STUB_BODY) }
                    val content = body.toByteArray()
                    w.emit(header(id, content.size), begin)
                    w.emit(content, begin)
                }
                // id と長さも含めて、元のとおりに写す
                else -> w.copy(bytes, begin, pos - begin)
            }
        }
        return w
    }

    /** [content] を id [id] のセクションとして書く（id と長さは、元のセクションの先頭 [anchor] に対応させる） */
    private fun sectionOf(w: Writer, id: Int, content: Writer, anchor: Int) {
        w.emit(header(id, content.size), anchor)
        w.append(content)
    }

    private fun header(id: Int, length: Int): ByteArray {
        val out = ByteArrayOutputStream(6)
        out.write(id)
        leb(out, length.toLong())
        return out.toByteArray()
    }

    private fun readU32(bytes: ByteArray, at: Int, end: Int): Pair<Int, Int> {
        var pos = at
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= end) throw WasmLoadException("core モジュールが途中で終わっている")
            val b = bytes[pos++].toInt() and 0xff
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift >= 35) throw WasmLoadException("core モジュールの整数が長すぎる")
        }
        if (result > Int.MAX_VALUE) throw WasmLoadException("core モジュールの整数が大きすぎる")
        return result.toInt() to pos
    }

    private fun leb(out: ByteArrayOutputStream, value: Long) {
        var v = value
        do {
            var b = (v and 0x7f).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            out.write(b)
        } while (v != 0L)
    }
}
