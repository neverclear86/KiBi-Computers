package pro.ikili.kibicomputers.core.runtime.validation

import pro.ikili.kibicomputers.core.runtime.WasmLoadException

/**
 * ランタイムに渡す前の、WASM のバイナリの軽い確認（#40）。
 *
 * ランタイムのパーサーは、宣言された数に合わせて確保したり繰り返したりするので、小さなバイト列でも
 * 大きな確保や長い処理をさせられる。ここではバイト列を1回なめて、次を確かめる（どれも [ModuleLimits]）。
 *
 * - ヘッダー（WASM のコアモジュールの version 1。Component Model のバイナリは対象外）
 * - セクションの大きさが残りに収まる
 * - 各セクションの要素の数が上限以下で、残りのバイト数以下（要素は 1 バイト以上なので、それより多いのは壊れている）
 * - 関数の本体の大きさと、宣言するローカル変数の数
 * - テーブルの最初の要素数
 *
 * 命令の中身や型の正しさは確かめない（ランタイムの検証に任せる）。
 */
internal object ModulePrescan {
    private val HEADER = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)

    private const val SEC_CUSTOM = 0
    private const val SEC_TYPE = 1
    private const val SEC_IMPORT = 2
    private const val SEC_FUNCTION = 3
    private const val SEC_TABLE = 4
    private const val SEC_MEMORY = 5
    private const val SEC_GLOBAL = 6
    private const val SEC_EXPORT = 7
    private const val SEC_START = 8
    private const val SEC_ELEMENT = 9
    private const val SEC_CODE = 10
    private const val SEC_DATA = 11
    private const val SEC_DATA_COUNT = 12
    private const val SEC_TAG = 13

    /** [bytes] を確かめる。だめなら [WasmLoadException] */
    @Throws(WasmLoadException::class)
    fun check(bytes: ByteArray, limits: ModuleLimits, deadline: LoadDeadline?) {
        if (bytes.size > limits.maxBytes) fail("大きすぎる（${bytes.size} バイト。上限 ${limits.maxBytes}）")
        if (bytes.size < HEADER.size) fail("WASM のヘッダーがない")
        for (i in 0 until 4) if (bytes[i] != HEADER[i]) fail("WASM のヘッダーがない")
        for (i in 4 until 8) if (bytes[i] != HEADER[i]) fail("WASM のコアモジュール（version 1）ではない")
        val r = Reader(bytes, HEADER.size, bytes.size)
        while (r.pos < r.end) {
            val id = r.u8()
            val size = r.u32()
            if (size > r.end - r.pos) fail("セクション $id の大きさ $size が残りを超える")
            val s = Reader(bytes, r.pos, r.pos + size.toInt())
            r.pos = s.end
            when (id) {
                SEC_CUSTOM, SEC_START, SEC_DATA_COUNT -> Unit
                SEC_TYPE -> s.count("型", limits.maxTypes)
                SEC_IMPORT -> s.count("import", limits.maxImports)
                SEC_FUNCTION -> s.count("関数", limits.maxFunctions)
                SEC_TABLE -> checkTables(s, limits)
                SEC_MEMORY -> s.count("メモリ", limits.maxMemories)
                SEC_GLOBAL -> s.count("グローバル", limits.maxGlobals)
                SEC_EXPORT -> s.count("export", limits.maxExports)
                SEC_ELEMENT -> s.count("要素のセグメント", limits.maxElementSegments)
                SEC_CODE -> checkCode(s, limits, deadline)
                SEC_DATA -> s.count("データのセグメント", limits.maxDataSegments)
                SEC_TAG -> s.count("タグ", limits.maxTags)
                else -> fail("知らないセクション $id")
            }
        }
        deadline?.check("構造の確認")
    }

    private fun checkTables(s: Reader, limits: ModuleLimits) {
        val n = s.count("テーブル", limits.maxTables)
        repeat(n) {
            // 初期値の式を持つ形（0x40 0x00 から始まる）は、燃料の計測コードの挿入が対応していないので断る
            if (s.peek() == 0x40) fail("初期値の式を持つテーブルには対応していない")
            s.refType()
            val flags = s.u8()
            if (flags and 0x04 != 0) fail("64 ビットのテーブルには対応していない")
            val min = s.u32()
            if (flags and 0x01 != 0) s.u32()
            if (min > limits.maxTableElements) fail("テーブルの最初の要素数 $min が上限 ${limits.maxTableElements} を超える")
        }
    }

    private fun checkCode(s: Reader, limits: ModuleLimits, deadline: LoadDeadline?) {
        val n = s.count("関数の本体", limits.maxFunctions)
        for (i in 0 until n) {
            val size = s.u32()
            if (size > s.end - s.pos) fail("関数 $i の本体の大きさ $size が残りを超える")
            if (size > limits.maxFunctionBodyBytes) fail("関数 $i の本体が大きすぎる（$size バイト。上限 ${limits.maxFunctionBodyBytes}）")
            val body = Reader(s.bytes, s.pos, s.pos + size.toInt())
            s.pos = body.end
            val groups = body.u32()
            if (groups > body.end - body.pos) fail("関数 $i のローカル変数の宣言が壊れている")
            var locals = 0L
            for (g in 0 until groups) {
                locals += body.u32()
                if (locals > limits.maxLocalsPerFunction) fail("関数 $i のローカル変数が多すぎる（上限 ${limits.maxLocalsPerFunction}）")
                body.valType()
            }
            if (deadline != null && i and 0xff == 0xff) deadline.check("構造の確認")
        }
    }

    private fun fail(message: String): Nothing = throw WasmLoadException("WASM の構造が上限を超える、または壊れている: $message")

    /** [bytes] の [pos]〜[end] を読む */
    private class Reader(val bytes: ByteArray, var pos: Int, val end: Int) {
        fun peek(): Int {
            if (pos >= end) fail("途中で終わっている")
            return bytes[pos].toInt() and 0xff
        }

        fun u8(): Int = peek().also { pos++ }

        /** 符号なし LEB128 の 32 ビット */
        fun u32(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = u8()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift >= 35) fail("整数が長すぎる")
            }
            if (result > 0xffff_ffffL) fail("整数が 32 ビットを超える")
            return result
        }

        /** 符号つき LEB128 の 33 ビット（ヒープの型）。値は使わないので読み飛ばす */
        fun skipS33() {
            var n = 0
            while (u8() and 0x80 != 0) {
                if (++n >= 5) fail("整数が長すぎる")
            }
        }

        /** ベクターの数を読み、上限と残りのバイト数で確かめる */
        fun count(what: String, max: Int): Int {
            val n = u32()
            if (n > max) fail("${what}の数 $n が上限 $max を超える")
            if (n > end - pos) fail("${what}の数 $n がセクションの大きさに合わない")
            return n.toInt()
        }

        /** 値の型（参照の型を含む）を読み飛ばす */
        fun valType() {
            when (u8()) {
                0x63, 0x64 -> skipS33() // (ref null? ヒープの型)
                else -> Unit // 数・ベクター・短い形の参照の型は 1 バイト
            }
        }

        fun refType() = valType()
    }
}
