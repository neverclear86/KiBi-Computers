package pro.ikili.kibicomputers.core.runtime.fuel

import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import run.endive.wasm.types.ActiveElement
import run.endive.wasm.types.ExternalType
import run.endive.wasm.types.Instruction
import run.endive.wasm.types.OpCode
import run.endive.wasm.types.TypeSection
import run.endive.wasm.types.ValType
import run.endive.wasm.WasmModule as EndiveModule

/**
 * GC のオブジェクト（struct・array）が使う JVM のメモリの見積もり（#939。ADR 0048 の「GC のオブジェクト」）。
 *
 * Endive 1.2.0 は、struct・array を `WasmStruct`・`WasmArray` に入れ、値を `long[]`（参照の値なら、さらに `Object[]`）で持つ。
 * WASM の大きさ（i8 で 1 バイト）ではなく、この JVM の大きさで数える（圧縮しない参照の前提。テーブルの `tableElementBytes` と同じ考え方）:
 * - オブジェクト 1 つの固定費 [OBJECT_BYTES]（包みのオブジェクトと、配列 2 本のヘッダー）。フィールドのない struct・長さ 0 の配列も 0 にしない
 * - struct のフィールドと配列の要素: 型に関係なく [REF_ELEMENT_BYTES]。Endive は、struct と、定数式（グローバル・要素の初期値）・`array.new_elem` で
 *   作る配列には、いつも `long[]` と `Object[]` の両方を作る。関数の本体で作る参照でない要素の配列は `long[]` だけだが、コンパイルありの経路の
 *   `array.copy` は、コピー元が `Object[]` を持つと、コピー先にも全長の `Object[]` を後から作る（`WasmArray.setRef`）。後から作られる分は
 *   その場では数えられない（コピー先の全長が命令から分からない）ので、作るときに両方の分を数えておく
 * - i31ref を入れられる場所（[mayHoldI31] の型のフィールド・要素）: さらに [I31_BYTES]。Endive は `ref.i31` のたびに `WasmI31Ref` を作るので、
 *   入れる先 1 つにつきオブジェクト 1 つ分を見込む（作るたびには数えない。数えると、i31ref を作っては捨てるプログラムが累計で RAM の上限に届く）。
 *   定数式の `ref.i31` は、その場で 1 つ数える。ローカル変数・スタック・グローバルに置いた i31ref は数えない（数に上限がある）
 *
 * 見積もりは仮の値（確定は #251）。
 */
internal object GcCosts {
    /** オブジェクト 1 つの固定費（バイト） */
    const val OBJECT_BYTES: Long = 64

    /** struct のフィールド・配列の要素 1 つ（`long` と参照） */
    const val REF_ELEMENT_BYTES: Long = 16

    /** i31ref の値 1 つ（`WasmI31Ref`。ヘッダーと `int`） */
    const val I31_BYTES: Long = 16

    /**
     * [type] の場所に i31ref を入れられるか（抽象のヒープ型 any・eq・i31 と、`extern.convert_any` が i31ref をそのまま渡す extern）。
     * 具体的な型（`(ref $t)`）・struct・array・none・func・exn と、参照でない型（[type] が `null` の詰めた型も）は入れられない
     */
    fun mayHoldI31(type: ValType?): Boolean {
        if (type == null) return false
        return when (type.opcode()) {
            ValType.ID.AnyRef, ValType.ID.EqRef, ValType.ID.i31, ValType.ID.ExternRef -> true
            ValType.ID.Ref, ValType.ID.RefNull -> when (type.typeIdx()) {
                ValType.TypeIdxCode.ANY.code(), ValType.TypeIdxCode.EQ.code(),
                ValType.TypeIdxCode.I31.code(), ValType.TypeIdxCode.EXTERN.code(),
                -> true
                else -> false
            }
            else -> false
        }
    }

    /** 型 [type] のフィールド・要素 1 つのバイト数（[REF_ELEMENT_BYTES] と、i31ref を入れられるなら [I31_BYTES]） */
    private fun slotBytes(type: ValType?): Long = REF_ELEMENT_BYTES + if (mayHoldI31(type)) I31_BYTES else 0L

    /** struct の型 [typeIdx] のオブジェクト 1 つのバイト数。struct の型でなければ [WasmLoadException] */
    fun structBytes(types: TypeSection, typeIdx: Long): Long {
        val st = subType(types, typeIdx)?.compType()?.structType() ?: throw WasmLoadException("型 $typeIdx が struct でない")
        return st.fieldTypes().fold(OBJECT_BYTES) { sum, f -> sum + slotBytes(f.storageType().valType()) }
    }

    /**
     * array の型 [typeIdx] の要素 1 つのバイト数（[REF_ELEMENT_BYTES] と、i31ref を入れられるなら [I31_BYTES]）。array の型でなければ [WasmLoadException]。
     * `array.new_elem` には使わない（i31ref は項目の式が作る分として数える。[GcPlan.dynamicBytes]）
     */
    fun arrayElementBytes(types: TypeSection, typeIdx: Long): Long {
        val at = subType(types, typeIdx)?.compType()?.arrayType() ?: throw WasmLoadException("型 $typeIdx が array でない")
        return slotBytes(at.fieldType().storageType().valType())
    }

    /** struct の型 [typeIdx] のフィールドの数（燃料に使う） */
    fun structFields(types: TypeSection, typeIdx: Long): Int =
        subType(types, typeIdx)?.compType()?.structType()?.fieldTypes()?.size ?: throw WasmLoadException("型 $typeIdx が struct でない")

    private fun subType(types: TypeSection, idx: Long) = if (idx in 0 until types.subTypeCount()) types.getSubType(idx.toInt()) else null

    /** 定数式で GC のオブジェクトを作る命令か */
    fun isConstAllocation(op: OpCode): Boolean = when (op) {
        OpCode.STRUCT_NEW, OpCode.STRUCT_NEW_DEFAULT, OpCode.ARRAY_NEW, OpCode.ARRAY_NEW_DEFAULT, OpCode.ARRAY_NEW_FIXED -> true
        else -> false
    }

    /**
     * モジュールの定数式の見積もり。
     *
     * @property instantiateBytes インスタンスを作るときに 1 回評価する式（グローバルの初期値と、アクティブの要素の式）が作るオブジェクトの合計
     * @property elementItemBytes 要素セグメントごとの、項目 1 つの式が作るオブジェクトの最大（パッシブの要素は `table.init`・`array.new_elem`・
     *   `array.init_elem` で使うたびに評価し直すので、そこで項目の数 × これを数える）
     * @property elementItemLength 要素セグメントごとの、項目 1 つの式の命令数（`end` を除く）の最大。Endive は要素 1 つごとに式を先頭から全部評価し直すので、
     *   `table.init`・`array.new_elem`・`array.init_elem` の燃料は、この長さに比例して増やす（[GcPlan.elementFuel]）
     */
    class ModuleEstimate(val instantiateBytes: Long, val elementItemBytes: LongArray, val elementItemLength: LongArray)

    /**
     * [module] の定数式が作る GC のオブジェクトを見積もる。配列の長さが読み込みのときに決まらない（import したグローバルから読むなど）
     * 定数式があれば、その場所（「グローバル 3」など）を理由にして [WasmLoadException]
     */
    @Throws(WasmLoadException::class)
    fun estimate(module: EndiveModule): ModuleEstimate {
        val types = module.typeSection()
        // 定数式の global.get で読める i32・i64 の値（import したグローバルは分からない）
        val known = ArrayList<Long?>()
        repeat(module.importSection().count(ExternalType.GLOBAL)) { known += null }
        var total = 0L
        module.globalSection().globals().forEachIndexed { i, g ->
            val r = evaluate(g.initInstructions(), types, known) ?: throw WasmLoadException("グローバル $i の初期値が作る GC のオブジェクトの大きさを、読み込みのときに決められない")
            total = saturatedAdd(total, r.bytes)
            known += if (g.valueType() == ValType.I32 || g.valueType() == ValType.I64) r.value else null
        }
        val elements = module.elementSection()
        val items = LongArray(elements.elementCount())
        val lengths = LongArray(elements.elementCount())
        for (i in 0 until elements.elementCount()) {
            val el = elements.getElement(i)
            var max = 0L
            var sum = 0L
            var longest = 0L
            for (init in el.initializers()) {
                longest = maxOf(longest, init.count { it.opcode() != OpCode.END }.toLong())
                val r = evaluate(init, types, known) ?: throw WasmLoadException("要素セグメント $i の式が作る GC のオブジェクトの大きさを、読み込みのときに決められない")
                max = maxOf(max, r.bytes)
                sum = saturatedAdd(sum, r.bytes)
            }
            items[i] = max
            lengths[i] = longest
            if (el is ActiveElement) total = saturatedAdd(total, sum)
        }
        return ModuleEstimate(total, items, lengths)
    }

    private class Evaluated(val bytes: Long, val value: Long?)

    /**
     * 定数式 [expr] を評価して、作るオブジェクトのバイト数と、結果の整数の値（分かれば）を返す。配列の長さが分からなければ `null`。
     * 式そのものの正しさは Endive の検証と `invalidPassiveElementInitializer`（#893）が確かめるので、ここでは値の数と長さだけを追う
     */
    private fun evaluate(expr: List<Instruction>, types: TypeSection, globals: List<Long?>): Evaluated? {
        if (expr.none { isConstAllocation(it.opcode()) || it.opcode() == OpCode.REF_I31 }) {
            // オブジェクトを作らない式は、整数の値だけを追う（ほかのグローバルの配列の長さに使われうる）
            return Evaluated(0, simpleValue(expr, globals))
        }
        val stack = ArrayList<Long?>()
        fun pop(): Long? = if (stack.isEmpty()) throw UnknownValue else stack.removeAt(stack.size - 1)
        var bytes = 0L
        try {
            for (ins in expr) {
                when (ins.opcode()) {
                    OpCode.END -> Unit
                    OpCode.I32_CONST -> stack += ins.operand(0).toInt().toLong()
                    OpCode.I64_CONST -> stack += ins.operand(0)
                    OpCode.GLOBAL_GET -> stack += globals.getOrNull(ins.operand(0).toInt())
                    OpCode.I32_ADD, OpCode.I32_SUB, OpCode.I32_MUL, OpCode.I64_ADD, OpCode.I64_SUB, OpCode.I64_MUL -> {
                        val b = pop()
                        val a = pop()
                        stack += if (a == null || b == null) null else arith(ins.opcode(), a, b)
                    }
                    // ref.i31 は `WasmI31Ref` を 1 つ作る
                    OpCode.REF_I31 -> {
                        pop()
                        bytes = saturatedAdd(bytes, I31_BYTES)
                        stack += null
                    }
                    OpCode.ANY_CONVERT_EXTERN, OpCode.EXTERN_CONVERT_ANY -> {
                        pop()
                        stack += null
                    }
                    OpCode.STRUCT_NEW -> {
                        repeat(structFields(types, ins.operand(0))) { pop() }
                        bytes = saturatedAdd(bytes, structBytes(types, ins.operand(0)))
                        stack += null
                    }
                    OpCode.STRUCT_NEW_DEFAULT -> {
                        bytes = saturatedAdd(bytes, structBytes(types, ins.operand(0)))
                        stack += null
                    }
                    OpCode.ARRAY_NEW, OpCode.ARRAY_NEW_DEFAULT -> {
                        val len = pop() ?: return null
                        if (ins.opcode() == OpCode.ARRAY_NEW) pop()
                        bytes = saturatedAdd(bytes, OBJECT_BYTES + arrayElementBytes(types, ins.operand(0)) * (len and 0xFFFF_FFFFL))
                        stack += null
                    }
                    OpCode.ARRAY_NEW_FIXED -> {
                        val n = ins.operand(1)
                        repeat(n.toInt()) { pop() }
                        bytes = saturatedAdd(bytes, OBJECT_BYTES + arrayElementBytes(types, ins.operand(0)) * n)
                        stack += null
                    }
                    // ほかの定数式の命令（f32.const・ref.null・ref.func・v128.const）は値を 1 つ積む
                    else -> stack += null
                }
            }
        } catch (e: UnknownValue) {
            return null
        }
        return Evaluated(bytes, stack.lastOrNull())
    }

    /** オブジェクトを作らない式の整数の値（`i32.const`・`i64.const`・`global.get`・足し算などだけの式）。分からなければ `null` */
    private fun simpleValue(expr: List<Instruction>, globals: List<Long?>): Long? {
        val stack = ArrayList<Long?>()
        for (ins in expr) {
            when (ins.opcode()) {
                OpCode.END -> Unit
                OpCode.I32_CONST -> stack += ins.operand(0).toInt().toLong()
                OpCode.I64_CONST -> stack += ins.operand(0)
                OpCode.GLOBAL_GET -> stack += globals.getOrNull(ins.operand(0).toInt())
                OpCode.I32_ADD, OpCode.I32_SUB, OpCode.I32_MUL, OpCode.I64_ADD, OpCode.I64_SUB, OpCode.I64_MUL -> {
                    if (stack.size < 2) return null
                    val b = stack.removeAt(stack.size - 1)
                    val a = stack.removeAt(stack.size - 1)
                    stack += if (a == null || b == null) null else arith(ins.opcode(), a, b)
                }
                else -> return null
            }
        }
        return if (stack.size == 1) stack[0] else null
    }

    private fun arith(op: OpCode, a: Long, b: Long): Long = when (op) {
        OpCode.I32_ADD -> (a.toInt() + b.toInt()).toLong()
        OpCode.I32_SUB -> (a.toInt() - b.toInt()).toLong()
        OpCode.I32_MUL -> (a.toInt() * b.toInt()).toLong()
        OpCode.I64_ADD -> a + b
        OpCode.I64_SUB -> a - b
        else -> a * b
    }

    /** [evaluate] の途中で、スタックの値が足りない（正しくない式。大きさを決められないものとして扱う） */
    private object UnknownValue : RuntimeException(null, null, false, false) {
        private fun readResolve(): Any = UnknownValue
    }

    /** 足して、[Long.MAX_VALUE] で止める（どちらも 0 以上） */
    fun saturatedAdd(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
}

/**
 * 関数の本体の GC のオブジェクトを作る命令の、RAM と燃料の課金（#939。[FuelInjector] が使う）。
 *
 * - 大きさが命令だけで決まるもの（`struct.new`・`struct.new_default`・`array.new_fixed`）: [staticBytes] の定数で数える
 * - 長さ（スタックの一番上の i32）で決まるもの（`array.new`・`array.new_default`・`array.new_data`・`array.new_elem`）と、
 *   GC のオブジェクトを作る式の要素セグメントを評価し直すもの（`array.init_elem`・`table.init`）: [dynamicBytes] の
 *   「要素 1 つのバイト数 × 長さ + 固定費」で数える
 *
 * [needed] は、モジュールにどちらかがあるか（あれば import を 1 つ足す）。
 */
internal class GcPlan(module: EndiveModule) {
    private val types: TypeSection = module.typeSection()

    /** 要素セグメントごとの、項目 1 つの式が作るオブジェクトの最大（[GcCosts.ModuleEstimate.elementItemBytes]） */
    private val estimate = GcCosts.estimate(module)
    private val itemBytes: LongArray = estimate.elementItemBytes

    /** 要素セグメントごとの、項目 1 つの式の命令数（`end` を除く）の最大 */
    private val itemLength: LongArray = estimate.elementItemLength

    /** GC のオブジェクトを数える命令があるか */
    val needed: Boolean = module.codeSection().functionBodies().any { body ->
        body.instructions().any { staticBytes(it) != null || dynamicBytes(it) != null }
    }

    /** 命令そのものの 1 に足す燃料（[FuelCosts.GC_ALLOCATION] と、フィールド・要素の数） */
    fun staticFuel(ins: Instruction): Long = when (ins.opcode()) {
        OpCode.STRUCT_NEW, OpCode.STRUCT_NEW_DEFAULT -> FuelCosts.GC_ALLOCATION + GcCosts.structFields(types, ins.operand(0))
        OpCode.ARRAY_NEW_FIXED -> FuelCosts.GC_ALLOCATION + ins.operand(1)
        OpCode.ARRAY_NEW, OpCode.ARRAY_NEW_DEFAULT, OpCode.ARRAY_NEW_DATA, OpCode.ARRAY_NEW_ELEM -> FuelCosts.GC_ALLOCATION
        else -> 0
    }

    /** 大きさが命令だけで決まるオブジェクトのバイト数。当たらない命令なら `null` */
    fun staticBytes(ins: Instruction): Long? = when (ins.opcode()) {
        OpCode.STRUCT_NEW, OpCode.STRUCT_NEW_DEFAULT -> GcCosts.structBytes(types, ins.operand(0))
        OpCode.ARRAY_NEW_FIXED -> GcCosts.saturatedAdd(GcCosts.OBJECT_BYTES, GcCosts.arrayElementBytes(types, ins.operand(0)) * ins.operand(1))
        else -> null
    }

    /** 長さで決まるオブジェクトの、要素 1 つのバイト数（[MAX_PER_ELEMENT] で止める）と固定費。当たらない（数える分がない）命令なら `null` */
    fun dynamicBytes(ins: Instruction): Pair<Long, Long>? {
        val (perElement, fixed) = when (ins.opcode()) {
            OpCode.ARRAY_NEW, OpCode.ARRAY_NEW_DEFAULT, OpCode.ARRAY_NEW_DATA ->
                GcCosts.arrayElementBytes(types, ins.operand(0)) to GcCosts.OBJECT_BYTES
            // 配列の要素（Endive は要素の型に関係なく `long[]` と `Object[]` の両方を作るので、いつも 16）に加えて、要素ごとに評価し直す式が作るオブジェクト
            // （項目の `ref.i31` もここに入るので、i31ref の分は足さない）
            OpCode.ARRAY_NEW_ELEM -> {
                GcCosts.arrayElementBytes(types, ins.operand(0)) // array の型でなければ断る
                GcCosts.saturatedAdd(GcCosts.REF_ELEMENT_BYTES, item(ins.operand(1))) to GcCosts.OBJECT_BYTES
            }
            OpCode.ARRAY_INIT_ELEM -> item(ins.operand(1)) to 0L
            OpCode.TABLE_INIT -> item(ins.operand(0)) to 0L
            else -> return null
        }
        if (perElement == 0L && fixed == 0L) return null
        return minOf(perElement, MAX_PER_ELEMENT) to fixed
    }

    /**
     * `table.init`・`array.new_elem`・`array.init_elem` の、要素 1 つごとに足す燃料（項目の式の命令数のうち、長さ 1 を超える分）。
     * 基本の重み（[FuelCosts.TABLE_ELEMENTS_SHIFT]・[FuelCosts.ARRAY_ELEM_ITEMS_SHIFT] のシフト）とは別に、要素の数 × これを引く
     * （基本の重みはシフトの向き・大きさに関係なく同じ式で引けるよう、ここには混ぜない）。当たらない命令、または式が長さ 1 以下なら `null`。
     * Endive は要素 1 つごとに項目の式を先頭から全部評価し直すので、仕事の量は「要素の数 × 式の命令数」になる（長い式は燃料も増やす）
     */
    fun elementFuel(ins: Instruction): Long? {
        val segment = when (ins.opcode()) {
            OpCode.TABLE_INIT -> ins.operand(0)
            OpCode.ARRAY_NEW_ELEM, OpCode.ARRAY_INIT_ELEM -> ins.operand(1)
            else -> return null
        }
        val extra = if (segment in itemLength.indices) minOf(itemLength[segment.toInt()] - 1, MAX_ITEM_EXTRA) else 0L
        return if (extra <= 0) null else extra
    }

    private fun item(segment: Long): Long = if (segment in itemBytes.indices) itemBytes[segment.toInt()] else 0L

    companion object {
        /**
         * 要素 1 つのバイト数の上限（1 GiB）。これより大きい要素は、長さ 1 でもどの RAM の上限も超えるので、ここで止めても断ることは変わらない。
         * 長さ（2^32 未満）を掛けても i64 で桁あふれしない
         */
        const val MAX_PER_ELEMENT: Long = 1L shl 30

        /** 項目の式が長いときの、要素 1 つごとの燃料に足す分の上限。長さ（2^32 未満）を掛けても i64 で桁あふれしない */
        const val MAX_ITEM_EXTRA: Long = 1L shl 24
    }
}
