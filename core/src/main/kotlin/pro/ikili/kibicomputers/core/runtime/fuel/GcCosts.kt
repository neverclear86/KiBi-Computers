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
 * - 関数の本体で作る配列の要素: 参照（Endive が `Object[]` に置く型）なら [REF_ELEMENT_BYTES]、ほかは [VALUE_ELEMENT_BYTES]
 * - struct のフィールドと、定数式（グローバル・要素の初期値）で作る配列の要素: [REF_ELEMENT_BYTES]（Endive が `long[]` と `Object[]` の両方を作る）
 *
 * 見積もりは仮の値（確定は #251）。
 */
internal object GcCosts {
    /** オブジェクト 1 つの固定費（バイト） */
    const val OBJECT_BYTES: Long = 64

    /** 参照の要素・struct のフィールド・定数式の配列の要素 1 つ（`long` と参照） */
    const val REF_ELEMENT_BYTES: Long = 16

    /** 参照でない要素 1 つ（`long`） */
    const val VALUE_ELEMENT_BYTES: Long = 8

    /** struct の型 [typeIdx] のオブジェクト 1 つのバイト数。struct の型でなければ [WasmLoadException] */
    fun structBytes(types: TypeSection, typeIdx: Long): Long {
        val st = subType(types, typeIdx)?.compType()?.structType() ?: throw WasmLoadException("型 $typeIdx が struct でない")
        return OBJECT_BYTES + REF_ELEMENT_BYTES * st.fieldTypes().size
    }

    /** 関数の本体で作る、array の型 [typeIdx] の要素 1 つのバイト数。array の型でなければ [WasmLoadException] */
    fun arrayElementBytes(types: TypeSection, typeIdx: Long): Long {
        val at = subType(types, typeIdx)?.compType()?.arrayType() ?: throw WasmLoadException("型 $typeIdx が array でない")
        return if (at.fieldType().storageType().isObjectRef()) REF_ELEMENT_BYTES else VALUE_ELEMENT_BYTES
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
     */
    class ModuleEstimate(val instantiateBytes: Long, val elementItemBytes: LongArray)

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
        for (i in 0 until elements.elementCount()) {
            val el = elements.getElement(i)
            var max = 0L
            var sum = 0L
            for (init in el.initializers()) {
                val r = evaluate(init, types, known) ?: throw WasmLoadException("要素セグメント $i の式が作る GC のオブジェクトの大きさを、読み込みのときに決められない")
                max = maxOf(max, r.bytes)
                sum = saturatedAdd(sum, r.bytes)
            }
            items[i] = max
            if (el is ActiveElement) total = saturatedAdd(total, sum)
        }
        return ModuleEstimate(total, items)
    }

    private class Evaluated(val bytes: Long, val value: Long?)

    /**
     * 定数式 [expr] を評価して、作るオブジェクトのバイト数と、結果の整数の値（分かれば）を返す。配列の長さが分からなければ `null`。
     * 式そのものの正しさは Endive の検証と `invalidPassiveElementInitializer`（#893）が確かめるので、ここでは値の数と長さだけを追う
     */
    private fun evaluate(expr: List<Instruction>, types: TypeSection, globals: List<Long?>): Evaluated? {
        if (expr.none { isConstAllocation(it.opcode()) }) {
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
                    OpCode.REF_I31, OpCode.ANY_CONVERT_EXTERN, OpCode.EXTERN_CONVERT_ANY -> {
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
                        bytes = saturatedAdd(bytes, OBJECT_BYTES + REF_ELEMENT_BYTES * (len and 0xFFFF_FFFFL))
                        stack += null
                    }
                    OpCode.ARRAY_NEW_FIXED -> {
                        val n = ins.operand(1)
                        repeat(n.toInt()) { pop() }
                        bytes = saturatedAdd(bytes, OBJECT_BYTES + REF_ELEMENT_BYTES * n)
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
    private val itemBytes: LongArray = GcCosts.estimate(module).elementItemBytes

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
            // 配列の要素に加えて、要素ごとに評価し直す式が作るオブジェクト
            OpCode.ARRAY_NEW_ELEM ->
                GcCosts.saturatedAdd(GcCosts.arrayElementBytes(types, ins.operand(0)), item(ins.operand(1))) to GcCosts.OBJECT_BYTES
            OpCode.ARRAY_INIT_ELEM -> item(ins.operand(1)) to 0L
            OpCode.TABLE_INIT -> item(ins.operand(0)) to 0L
            else -> return null
        }
        if (perElement == 0L && fixed == 0L) return null
        return minOf(perElement, MAX_PER_ELEMENT) to fixed
    }

    private fun item(segment: Long): Long = if (segment in itemBytes.indices) itemBytes[segment.toInt()] else 0L

    companion object {
        /**
         * 要素 1 つのバイト数の上限（1 GiB）。これより大きい要素は、長さ 1 でもどの RAM の上限も超えるので、ここで止めても断ることは変わらない。
         * 長さ（2^32 未満）を掛けても i64 で桁あふれしない
         */
        const val MAX_PER_ELEMENT: Long = 1L shl 30
    }
}
