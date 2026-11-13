package pro.ikili.kibicomputers.core.runtime.component

import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import java.nio.charset.StandardCharsets

/**
 * core モジュールの import のモジュール名を読む（#262）。コンポーネントの中の core モジュールが、カーネルの予約した
 * モジュール名（[RESERVED_MODULE]）を import していないかを確かめるのに使う。
 *
 * 検証（[ComponentValidator]）に通ったモジュールに使う（壊れていたら [WasmLoadException]）。
 * import の記述は、検証で有効にした機能（[ComponentValidator.FEATURES]）の範囲で読み飛ばす。
 */
internal object CoreModuleImports {
    /**
     * カーネルが予約したモジュール名。燃料（`kibi.fuel`）と待機点（`kibi.async_*`）の import に使い（#28・#329）、
     * コンポーネントでは #270 でリンカーがコアの import に足す。プレイヤーのモジュールが自分で import すると、
     * 燃料の計測をすり抜けたり、カーネルの状態を壊したりできるので断る
     */
    const val RESERVED_MODULE: String = "kibi"

    private const val SEC_IMPORT = 2

    /** [range] の core モジュールが import するモジュール名（重複あり、宣言の順） */
    @Throws(WasmLoadException::class)
    fun moduleNames(bytes: ByteArray, range: ByteRange): List<String> {
        val r = R(bytes, range.offset + WasmBinaryHeader.SIZE, range.end)
        val out = ArrayList<String>()
        while (r.pos < r.end) {
            val id = r.u8()
            val size = r.u32().toInt()
            val next = r.pos + size
            if (size < 0 || next > r.end) bad()
            if (id == SEC_IMPORT) {
                val n = r.u32()
                for (i in 0 until n) {
                    out += r.name()
                    r.name()
                    r.skipImportDesc()
                }
            }
            r.pos = next
        }
        return out
    }

    private fun bad(): Nothing = throw WasmLoadException("core モジュールの import を読めない")

    private class R(val bytes: ByteArray, var pos: Int, val end: Int) {
        fun u8(): Int {
            if (pos >= end) bad()
            return bytes[pos++].toInt() and 0xff
        }

        fun u32(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = u8()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift >= 35) bad()
            }
        }

        fun skipLeb() {
            var n = 0
            while (u8() and 0x80 != 0) if (++n >= 10) bad()
        }

        fun name(): String {
            val n = u32().toInt()
            if (n < 0 || n > end - pos) bad()
            return String(bytes, pos, n, StandardCharsets.UTF_8).also { pos += n }
        }

        fun valType() {
            when (u8()) {
                0x63, 0x64 -> skipLeb()
                else -> Unit
            }
        }

        fun limits() {
            val flags = u8()
            skipLeb()
            if (flags and 0x01 != 0) skipLeb()
            if (flags and 0x08 != 0) skipLeb()
        }

        fun skipImportDesc() {
            when (u8()) {
                0x00 -> skipLeb()
                0x01 -> {
                    valType()
                    limits()
                }
                0x02 -> limits()
                0x03 -> {
                    valType()
                    u8()
                }
                0x04 -> {
                    u8()
                    skipLeb()
                }
                else -> bad()
            }
        }
    }
}
