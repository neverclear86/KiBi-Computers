package pro.ikili.kibicomputers.core.runtime.validation

import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import run.endive.wasm.Parser
import run.endive.wasm.types.CodeSection
import run.endive.wasm.types.ElementSection
import run.endive.wasm.types.GlobalSection
import run.endive.wasm.types.Instruction
import run.endive.wasm.types.OpCode
import run.endive.wasm.types.SectionId
import java.io.ByteArrayInputStream

/**
 * ランタイムに渡す前の、WASM のバイナリの軽い確認（#40）。
 *
 * ランタイムのパーサーは、宣言された数に合わせて確保したり繰り返したりするので、小さなバイト列でも
 * 大きな確保や長い処理をさせられる。ここではバイト列を1回なめて、次を確かめる（どれも [ModuleLimits]）。
 *
 * - ヘッダー（WASM のコアモジュールの version 1）。Component Model のコンポーネント（layer 1）は「version 1 ではない」で断る
 *   （コンポーネントは `Program.loadExecutable` がヘッダーで見分けて `ComponentLoader` で読むので、ここには来ない。#687。
 *   `ComponentLoader` は中の core モジュールの1つずつにこれを当てる）
 * - セクションの大きさが残りに収まる
 * - 各セクションの要素の数が上限以下で、残りのバイト数以下（要素は 1 バイト以上なので、それより多いのは壊れている）
 * - 関数の本体の大きさと、宣言するローカル変数の数
 * - テーブルの最初の要素数
 * - 型の数（rec グループの中の型も 1 つずつ数える）と、struct の型のフィールドの数（型 1 つと、全部の合計。#1009）
 * - GC の型（struct・array）があれば、ランタイムの検証の手間が型の大きさ・即値に比例する命令（`struct.new`・`struct.new_default`・
 *   `array.new_fixed`）の手間の合計（#1009。[checkGcWork]）
 *
 * ほかの命令の中身や型の正しさは確かめない（ランタイムの検証に任せる）。
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
        var structFields: IntArray? = null
        while (r.pos < r.end) {
            val id = r.u8()
            val size = r.u32()
            if (size > r.end - r.pos) fail("セクション $id の大きさ $size が残りを超える")
            val s = Reader(bytes, r.pos, r.pos + size.toInt())
            r.pos = s.end
            when (id) {
                SEC_CUSTOM, SEC_START, SEC_DATA_COUNT -> Unit
                SEC_TYPE -> structFields = checkTypes(s, limits)
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
        if (structFields != null) {
            checkGcWork(bytes, structFields, limits, deadline)
            deadline?.check("構造の確認")
        }
    }

    /**
     * 型のセクションを読み、型の数とフィールドの数を確かめる。GC の型（struct か array）があれば、平らな型の番号（rec グループの中の型を
     * 1 つずつ数えた番号）ごとの struct のフィールドの数（struct でない型は 0）を返す。GC の型がなければ `null`
     *
     * 読み方は Endive 1.2.0 の `Parser.parseRecType` と同じ: `0x4E` は subtype のベクター、`0x50`・`0x4F` は親の型の番号のベクターと comptype、
     * ほかは comptype だけ。comptype は `0x5E`（array）・`0x5F`（struct）・`0x60`（関数）で、ほかは断る（Endive も断る）
     */
    private fun checkTypes(s: Reader, limits: ModuleLimits): IntArray? {
        val groups = s.count("型", limits.maxTypes)
        var fields = IntArray(minOf(groups, 16))
        var types = 0
        var totalFields = 0L
        var gc = false
        fun subType() {
            var form = s.u8()
            if (form == 0x50 || form == 0x4F) {
                repeat(s.count("親の型", limits.maxTypes)) { s.u32() }
                form = s.u8()
            }
            var n = 0
            when (form) {
                0x5E -> {
                    s.fieldType()
                    gc = true
                }
                0x5F -> {
                    val count = s.u32()
                    if (count > limits.maxStructFields) fail("struct の型 $types のフィールドの数 $count が上限 ${limits.maxStructFields} を超える")
                    if (count > s.end - s.pos) fail("struct の型 $types のフィールドの数 $count が型のセクションの大きさに合わない")
                    totalFields += count
                    if (totalFields > limits.maxTotalStructFields) fail("struct の型のフィールドの数の合計が上限 ${limits.maxTotalStructFields} を超える")
                    repeat(count.toInt()) { s.fieldType() }
                    n = count.toInt()
                    gc = true
                }
                0x60 -> {
                    repeat(s.count("関数の型の引数", Int.MAX_VALUE)) { s.valType() }
                    repeat(s.count("関数の型の戻り値", Int.MAX_VALUE)) { s.valType() }
                }
                else -> fail("知らない型の形 0x${form.toString(16)}")
            }
            if (types >= limits.maxTypes) fail("型の数（rec グループの中の型も数える）が上限 ${limits.maxTypes} を超える")
            if (types == fields.size) fields = fields.copyOf(maxOf(16, fields.size * 2))
            fields[types++] = n
        }
        repeat(groups) {
            if (s.peek() == 0x4E) {
                s.u8()
                repeat(s.count("rec グループの型", limits.maxTypes)) { subType() }
            } else {
                subType()
            }
        }
        return if (gc) fields.copyOf(types) else null
    }

    /**
     * ランタイムの検証の手間のうち、型の大きさ・即値に比例する分を数え、上限（[ModuleLimits.maxGcValidationWork]）を超えれば断る（#1009）。
     *
     * Endive 1.2.0 の検証は、`struct.new`・`struct.new_default` 1 つごとに型の全フィールドを、`array.new_fixed` 1 つごとに即値の数だけ
     * オペランドを確かめる（`Validator.java`）。どれも型・即値の大きさに上限がないので、小さなモジュールで検証を何時間にも延ばせ、
     * 検証は途中で止められない。そこで、関数の本体と定数式（グローバルの初期値・要素の式）の全部の命令について、
     * `struct.new`・`struct.new_default` は型のフィールドの数、`array.new_fixed` は要素の数を足し上げる。届かない場所（`unreachable` の後）も数える
     * （検証はそこも確かめる）。`array.new_fixed` は 1 つで重くできるので、1 つの要素の数にも上限（[ModuleLimits.maxArrayNewFixed]）を置く。
     *
     * 命令は Endive の検証なしの parse（型・グローバル・要素・コードのセクションだけ）で読む（後の検証が見るものと同じ並び）。
     * parse が失敗するモジュールは、ここでは断らずに通す（後のランタイムの parse が同じところで失敗して、いつもの理由で断る。
     * 重い検証は parse の後なので、そこには届かない）。struct でない型の番号は 0 と数える（検証は最初の命令で断る）
     */
    private fun checkGcWork(bytes: ByteArray, structFields: IntArray, limits: ModuleLimits, deadline: LoadDeadline?) {
        val code = ArrayList<CodeSection>(1)
        val globals = ArrayList<GlobalSection>(1)
        val elements = ArrayList<ElementSection>(1)
        try {
            Parser.builder()
                .withValidation(false)
                .includeSectionId(SectionId.TYPE)
                .includeSectionId(SectionId.GLOBAL)
                .includeSectionId(SectionId.ELEMENT)
                .includeSectionId(SectionId.CODE)
                .build()
                .parse(ByteArrayInputStream(bytes)) { section ->
                    when (section) {
                        is CodeSection -> code += section
                        is GlobalSection -> globals += section
                        is ElementSection -> elements += section
                        else -> Unit
                    }
                }
        } catch (e: RuntimeException) {
            return
        } catch (e: StackOverflowError) {
            return
        }
        deadline?.check("構造の確認")
        var work = 0L
        var seen = 0
        fun add(ins: Instruction) {
            if (deadline != null && ++seen and 0xffff == 0) deadline.check("構造の確認")
            when (ins.opcode()) {
                OpCode.STRUCT_NEW, OpCode.STRUCT_NEW_DEFAULT -> {
                    val idx = ins.operand(0)
                    if (idx in structFields.indices) work += structFields[idx.toInt()]
                }
                OpCode.ARRAY_NEW_FIXED -> {
                    val n = ins.operand(1) and 0xFFFF_FFFFL
                    if (n > limits.maxArrayNewFixed) fail("array.new_fixed の要素の数 $n が上限 ${limits.maxArrayNewFixed} を超える")
                    work += n
                }
                else -> return
            }
            // 1 つ足す分は 2^32 未満なので、上限（Long）を超えたところで止めれば桁あふれしない
            if (work > limits.maxGcValidationWork) {
                fail("struct.new・array.new_fixed の検証の手間（フィールド・要素の数の合計）が上限 ${limits.maxGcValidationWork} を超える")
            }
        }
        for (section in globals) for (g in section.globals()) g.initInstructions().forEach(::add)
        for (section in elements) for (el in section.elements()) for (init in el.initializers()) init.forEach(::add)
        for (section in code) {
            for ((i, body) in section.functionBodies().withIndex()) {
                body.instructions().forEach(::add)
                if (deadline != null && i and 0xff == 0xff) deadline.check("構造の確認")
            }
        }
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

        /** struct・array のフィールドの型（i8・i16 か値の型と、可変かの 1 バイト）を読み飛ばす */
        fun fieldType() {
            if (peek() == 0x78 || peek() == 0x77) u8() else valType()
            u8()
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
