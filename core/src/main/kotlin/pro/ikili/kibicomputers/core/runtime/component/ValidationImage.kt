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
 */
internal object ValidationImage {
    private const val CORE_SEC_CUSTOM = 0
    private const val CORE_SEC_CODE = 10

    /** `unreachable` だけの関数の本体（ローカル変数なし、`unreachable`、`end`）と、その大きさ */
    private val STUB_BODY = byteArrayOf(0x03, 0x00, 0x00, 0x0b)

    /** [root]（[bytes] を読んだもの）の検証用の写し */
    @Throws(WasmLoadException::class)
    fun of(bytes: ByteArray, root: ParsedComponent): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 4)
        writeComponent(bytes, root, out)
        return out.toByteArray()
    }

    private fun writeComponent(bytes: ByteArray, c: ParsedComponent, out: ByteArrayOutputStream) {
        out.write(bytes, c.range.offset, WasmBinaryHeader.SIZE)
        for (s in c.sections) {
            when (s) {
                is ComponentSection.CoreModule -> section(out, 1, stubModule(bytes, s.range))
                is ComponentSection.Nested -> section(out, 4, ByteArrayOutputStream().also { writeComponent(bytes, s.component, it) }.toByteArray())
                else -> {
                    out.write(idOf(s))
                    leb(out, s.range.length.toLong())
                    out.write(bytes, s.range.offset, s.range.length)
                }
            }
        }
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
    private fun stubModule(bytes: ByteArray, range: ByteRange): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(bytes, range.offset, WasmBinaryHeader.SIZE)
        var pos = range.offset + WasmBinaryHeader.SIZE
        while (pos < range.end) {
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
                    section(out, id, body.toByteArray())
                }
                else -> {
                    out.write(id)
                    leb(out, size.toLong())
                    out.write(bytes, start, size)
                }
            }
        }
        return out.toByteArray()
    }

    private fun section(out: ByteArrayOutputStream, id: Int, content: ByteArray) {
        out.write(id)
        leb(out, content.size.toLong())
        out.write(content)
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
