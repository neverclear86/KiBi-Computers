package pro.ikili.kibicomputers.core.runtime.validation

import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import run.endive.wasm.Parser
import run.endive.wasm.types.ActiveDataSegment
import run.endive.wasm.types.ActiveElement
import run.endive.wasm.types.CodeSection
import run.endive.wasm.types.DataSection
import run.endive.wasm.types.ElementSection
import run.endive.wasm.types.GlobalSection
import run.endive.wasm.types.Instruction
import run.endive.wasm.types.OpCode
import run.endive.wasm.types.SectionId
import run.endive.wasm.types.TableSection
import run.endive.wasm.types.TypeSection
import run.endive.wasm.types.ValType
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
 * - GC の型（struct・array）があれば、ランタイムの検証の手間が struct の型の大きさ・即値に比例する命令（具体的な型を名指しする命令と
 *   `array.new_fixed` など）の手間の合計（#1009。[checkGcWork]）
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
        var hasGcTypes = false
        var seenTypes = false
        while (r.pos < r.end) {
            val id = r.u8()
            val size = r.u32()
            if (size > r.end - r.pos) fail("セクション $id の大きさ $size が残りを超える")
            val s = Reader(bytes, r.pos, r.pos + size.toInt())
            r.pos = s.end
            when (id) {
                SEC_CUSTOM, SEC_START, SEC_DATA_COUNT -> Unit
                SEC_TYPE -> {
                    // Endive も型のセクションが 2 つあれば断る。ここで見る型の表と Endive が読む表を、1 つに決めておく
                    if (seenTypes) fail("型のセクションが 2 つある")
                    seenTypes = true
                    hasGcTypes = checkTypes(s, limits)
                }
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
        if (hasGcTypes) {
            checkGcWork(bytes, limits, deadline)
            deadline?.check("構造の確認")
        }
    }

    /**
     * 型のセクションを読み、型の数とフィールドの数を確かめる。GC の型（struct か array）があれば `true`
     *
     * 読み方は Endive 1.2.0 の `Parser.parseTypeSection` と合わせる（ずれると、ここで見た型の並びと Endive が検証する並びが違い、上限が効かなくなる）。
     * 値の型のコードと型の形の識別子は、`readVarUInt32`（最短でない符号化も受け付ける）で読む。
     * `0x4E` は subtype のベクター、`0x50`・`0x4F` は親の型の番号のベクターと comptype、ほかは comptype だけ。comptype は
     * `0x5E`（array）・`0x5F`（struct）・`0x60`（関数）で、ほかは断る（Endive も断る）。読み終わったあとにセクションの読み残しがあれば断る
     * （Endive は section size mismatch で断る）
     */
    private fun checkTypes(s: Reader, limits: ModuleLimits): Boolean {
        val groups = s.count("型", limits.maxTypes)
        var types = 0
        var totalFields = 0L
        var gc = false
        fun subType(head: Long) {
            var form = head
            if (form == 0x50L || form == 0x4FL) {
                repeat(s.count("親の型", limits.maxTypes)) { s.u32() }
                form = s.u32()
            }
            when (form) {
                0x5EL -> {
                    s.fieldType()
                    gc = true
                }
                0x5FL -> {
                    val count = s.u32()
                    if (count > limits.maxStructFields) fail("struct の型 $types のフィールドの数 $count が上限 ${limits.maxStructFields} を超える")
                    if (count > s.end - s.pos) fail("struct の型 $types のフィールドの数 $count が型のセクションの大きさに合わない")
                    totalFields += count
                    if (totalFields > limits.maxTotalStructFields) fail("struct の型のフィールドの数の合計が上限 ${limits.maxTotalStructFields} を超える")
                    repeat(count.toInt()) { s.fieldType() }
                    gc = true
                }
                0x60L -> {
                    repeat(s.count("関数の型の引数", Int.MAX_VALUE)) { s.valType() }
                    repeat(s.count("関数の型の戻り値", Int.MAX_VALUE)) { s.valType() }
                }
                else -> fail("知らない型の形 0x${form.toString(16)}")
            }
            if (++types > limits.maxTypes) fail("型の数（rec グループの中の型も数える）が上限 ${limits.maxTypes} を超える")
        }
        repeat(groups) {
            val head = s.u32()
            if (head == 0x4EL) {
                repeat(s.count("rec グループの型", limits.maxTypes)) { subType(s.u32()) }
            } else {
                subType(head)
            }
        }
        if (s.pos != s.end) fail("型のセクションの大きさに合わない（${s.end - s.pos} バイトが読み残される）")
        return gc
    }

    /**
     * Endive の型の表から、平らな型の番号（rec グループの中の型を 1 つずつ数えた番号）ごとの struct のフィールドの数（struct でない型は 0）を作る。
     * [checkTypes] と同じ上限を、Endive が読んだ表にも当てる（2 つの読み方が食い違っても、上限が効くように）
     */
    private fun structFieldCounts(types: TypeSection, limits: ModuleLimits): IntArray {
        val n = types.subTypeCount()
        if (n > limits.maxTypes) fail("型の数（rec グループの中の型も数える）が上限 ${limits.maxTypes} を超える")
        val counts = IntArray(n)
        var total = 0L
        for (i in 0 until n) {
            val fields = types.getSubType(i).compType().structType()?.fieldTypes()?.size ?: continue
            if (fields > limits.maxStructFields) fail("struct の型 $i のフィールドの数 $fields が上限 ${limits.maxStructFields} を超える")
            total += fields
            if (total > limits.maxTotalStructFields) fail("struct の型のフィールドの数の合計が上限 ${limits.maxTotalStructFields} を超える")
            counts[i] = fields
        }
        return counts
    }

    /**
     * ランタイムの検証の手間のうち、struct の型の大きさ・即値に比例する分を数え、上限（[ModuleLimits.maxGcValidationWork]）を超えれば断る（#1009）。
     *
     * Endive 1.2.0 の検証（`Validator.java`）は、`struct.new` 1 つごとに型の全フィールドを、`array.new_fixed` 1 つごとに即値の数だけ
     * オペランドを確かめる。また、具体的な型を指す参照の型を作るたび（`valType(opcode, typeIdx)` → `ValType.resolve`）に、参照先の
     * `SubType.hashCode()` を取り直す（覚えていないので、struct なら全フィールドを走査する）。どれも型・即値の大きさに上限がないので、
     * 小さなモジュールで検証を何時間にも延ばせ、検証は途中で止められない。そこで、関数の本体と全部の定数式（グローバルの初期値、
     * 要素の式と offset、データの offset、テーブルの初期値）の全部の命令について、次を足し上げる（struct でない型は 0 と数える）。
     *
     * - 型を名指しする命令（`struct.new`・`struct.new_default`・`struct.get`・`struct.get_s`・`struct.get_u`・`struct.set`・`ref.null`・
     *   `ref.test`・`ref.cast`（null つきも）・型つきの `select`）: 名指しした型のフィールドの数。`br_on_cast`・`br_on_cast_fail` は、
     *   1 つ目の型を 2 回、2 つ目の型を 1 回作るので、その分
     * - スタックの参照の型を作り直す命令（`ref.as_non_null`・`br_on_null`・`br_on_non_null`）: 即値がなく、どの型かは型の推論をしないと
     *   分からないので、いちばん大きな struct の型のフィールドの数
     * - `array.new_fixed`: 要素の数（1 つで重くできるので、1 つの要素の数にも上限（[ModuleLimits.maxArrayNewFixed]）を置く）
     *
     * array の型は大きさが決まっている（フィールド 1 つ）ので数えない。関数の型の大きさ（引数・戻り値）とサブタイプの鎖は #1038 で扱う。
     * 届かない場所（`unreachable` の後）も数える（検証はそこも確かめる）。テーブルの初期値は、式を書かないテーブルにも Endive が
     * `ref.null <要素の型>` を補うので、その分も数える。
     *
     * 型の表と命令は Endive の検証なしの parse（型・テーブル・グローバル・要素・コード・データのセクションだけ）で読む（後の検証が見るものと
     * 同じ並び。自前の読み手の表は使わない）。parse が失敗するモジュールは、ここでは断らずに通す（後のランタイムの parse が同じところで
     * 失敗して、いつもの理由で断る。重い検証は parse の後なので、そこには届かない）。
     *
     * この parse 自体にも、型の大きさ × 具体的な型への参照の数の手間が残る（`ValType.resolve`。#1042）
     */
    private fun checkGcWork(bytes: ByteArray, limits: ModuleLimits, deadline: LoadDeadline?) {
        var types: TypeSection? = null
        val code = ArrayList<CodeSection>(1)
        val globals = ArrayList<GlobalSection>(1)
        val elements = ArrayList<ElementSection>(1)
        val tables = ArrayList<TableSection>(1)
        val data = ArrayList<DataSection>(1)
        try {
            Parser.builder()
                .withValidation(false)
                .includeSectionId(SectionId.TYPE)
                .includeSectionId(SectionId.TABLE)
                .includeSectionId(SectionId.GLOBAL)
                .includeSectionId(SectionId.ELEMENT)
                .includeSectionId(SectionId.CODE)
                .includeSectionId(SectionId.DATA)
                .build()
                .parse(ByteArrayInputStream(bytes)) { section ->
                    when (section) {
                        is TypeSection -> types = section
                        is CodeSection -> code += section
                        is GlobalSection -> globals += section
                        is ElementSection -> elements += section
                        is TableSection -> tables += section
                        is DataSection -> data += section
                        else -> Unit
                    }
                }
        } catch (e: RuntimeException) {
            return
        } catch (e: StackOverflowError) {
            return
        }
        deadline?.check("構造の確認")
        // フィールドの数は、Endive が読んだ型の表（後の検証が見るもの）から取る。自前の読み手の表には頼らない
        val structFields = structFieldCounts(types ?: return, limits)
        val maxFields = structFields.maxOrNull()?.toLong() ?: 0L

        /** 型の番号（ヒープの型。抽象の型は負）[heapType] が struct の型なら、そのフィールドの数。ほかは 0 */
        fun fields(heapType: Long): Long = if (heapType in structFields.indices) structFields[heapType.toInt()].toLong() else 0L
        var work = 0L
        var seen = 0
        fun add(ins: Instruction) {
            if (deadline != null && ++seen and 0xffff == 0) deadline.check("構造の確認")
            when (ins.opcode()) {
                OpCode.STRUCT_NEW, OpCode.STRUCT_NEW_DEFAULT, OpCode.STRUCT_GET, OpCode.STRUCT_GET_S, OpCode.STRUCT_GET_U,
                OpCode.STRUCT_SET, OpCode.REF_NULL, OpCode.REF_TEST, OpCode.REF_TEST_NULL, OpCode.CAST_TEST, OpCode.CAST_TEST_NULL,
                -> work += fields(ins.operand(0))
                OpCode.BR_ON_CAST, OpCode.BR_ON_CAST_FAIL -> work += 2 * fields(ins.operand(2)) + fields(ins.operand(3))
                // 即値は値の型の id（参照の型なら、上位 32 ビットが型の番号）。Endive は 2 つ以上なら resolve の前に断るが、全部数える
                OpCode.SELECT_T -> for (id in ins.operands()) {
                    val t = ValType.builder().fromId(id)
                    if (t.isReference) work += fields(t.typeIdx().toLong())
                }
                OpCode.REF_AS_NON_NULL, OpCode.BR_ON_NULL, OpCode.BR_ON_NON_NULL -> work += maxFields
                OpCode.ARRAY_NEW_FIXED -> {
                    val n = ins.operand(1) and 0xFFFF_FFFFL
                    if (n > limits.maxArrayNewFixed) fail("array.new_fixed の要素の数 $n が上限 ${limits.maxArrayNewFixed} を超える")
                    work += n
                }
                else -> return
            }
            // 1 つ足す分は 2^32 未満なので、上限（Long）を超えたところで止めれば桁あふれしない
            if (work > limits.maxGcValidationWork) {
                fail("GC の型を使う命令の検証の手間（struct のフィールド・array.new_fixed の要素の数の合計）が上限 ${limits.maxGcValidationWork} を超える")
            }
        }
        // 定数式（Endive の validateTables・validateGlobals・validateElements・validateData が確かめるもの）
        for (section in tables) for (i in 0 until section.tableCount()) section.getTable(i).initialize()?.forEach(::add)
        for (section in globals) for (g in section.globals()) g.initInstructions().forEach(::add)
        for (section in elements) {
            for (el in section.elements()) {
                if (el is ActiveElement) el.offset().forEach(::add)
                for (init in el.initializers()) init.forEach(::add)
            }
        }
        for (section in data) {
            for (segment in section.dataSegments()) if (segment is ActiveDataSegment) segment.offsetInstructions().forEach(::add)
        }
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
            // 先頭は Endive の parseTableSection と同じく readVarUInt32 で読む（最短でない符号化の 0xC0 0x00 も 0x40 になる）。
            // 初期値の式を持つ形（0x40 0x00 から始まる）は、燃料の計測コードの挿入が対応していないので断る
            val head = s.u32()
            if (head == 0x40L) fail("初期値の式を持つテーブルには対応していない")
            s.valTypeFrom(head)
            val flags = s.u8()
            if (flags and 0x04 != 0) fail("64 ビットのテーブルには対応していない")
            val min = s.u32()
            if (flags and 0x01 != 0) s.u32()
            if (min > limits.maxTableElements) fail("テーブルの最初の要素数 $min が上限 ${limits.maxTableElements} を超える")
        }
        // Endive も読み残しを section size mismatch で断る。読み方がずれたまま通さない
        if (s.pos != s.end) fail("テーブルのセクションの大きさに合わない（${s.end - s.pos} バイトが読み残される）")
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
            valType() // i8・i16（0x78・0x77）も、後ろに何も付かない 1 つの型のコードとして同じに読める
            u8()
        }

        /**
         * 値の型（参照の型を含む）を読み飛ばす。型のコードは Endive と同じ `readVarUInt32`（最大 5 バイトで、最短でない符号化も受ける）で読み、
         * 0x63・0x64 のときだけヒープの型が付く。ほかのコードは、どんなバイトでも 1 つの型として読む（検査は Endive に任せる）
         */
        fun valType() = valTypeFrom(u32())

        /** 型のコード [code] を読んだあとの、値の型の残り（参照の型のヒープの型）を読み飛ばす */
        fun valTypeFrom(code: Long) {
            if (code == 0x63L || code == 0x64L) skipS33() // (ref null? ヒープの型)
        }
    }
}
