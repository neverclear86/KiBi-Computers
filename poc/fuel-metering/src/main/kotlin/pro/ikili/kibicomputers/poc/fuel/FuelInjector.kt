package pro.ikili.kibicomputers.poc.fuel

import run.endive.wasm.Parser
import run.endive.wasm.types.ExternalType
import run.endive.wasm.types.AnnotatedInstruction
import run.endive.wasm.types.OpCode
import java.io.ByteArrayOutputStream

/**
 * 方式 A: 読み込み時に WASM へ燃料の計測コードを挿入する。
 *
 * 入れるもの:
 * - 可変の i64 のグローバル（残りの燃料）。`kibi_fuel` という名前で export する
 * - import の関数 `kibi.fuel: (i64 残り) -> (i64 新しい残り)`。燃料が負になったときに呼ぶ。
 *   ホストはここでスレッドを止めて次の tick を待つ（#27）か、例外を投げて実行をやめさせる
 *
 * 数え方:
 * - 関数の本体を「途中で分岐しない命令の並び」（セグメント）に分ける。分岐の出入りがある命令
 *   （`block`・`loop`・`if`・`else`・`end`・`br`・`br_if`・`br_table`・`return`・`try_table`・`throw` など）で区切る
 * - 各セグメントの先頭で、そのセグメントの命令数（[CostModel]）を引く
 * - 残りが負かを確かめてホストを呼ぶのは、関数の入口と `loop` の先頭だけにする。
 *   終わらない実行は必ずどちらかを通るので、確かめる間に実行される命令は、関数1つの長さで抑えられる
 *
 * 残りの置き場所（[Storage]）:
 * - [Storage.GLOBAL]: 引くたびにグローバルを読み書きする（単純）
 * - [Storage.LOCAL]: 関数の中ではローカル変数に写して引き、呼び出し・戻る・例外を投げる前にだけグローバルに書き戻す。
 *   呼び出しから戻った後はグローバルから読み直す。
 *   ただし `try_table` を含む関数は、この方式にせず [Storage.GLOBAL] で数える。例外を捕まえた先は、通常の実行
 *   （`try_table` の本体が最後まで進んだとき、`loop` への `br` など）でも通るので、そこで読み直すと
 *   ローカルに引いた分を捨ててしまう（終わらないループを止められなくなる）。例外で着いたときだけ読み直す方法は
 *   WASM にないので、そういう関数はグローバルを正にする（呼ばれた先の分も、投げる前の書き戻しも、常に合う）
 *
 * import の関数を1つ足すので、定義済みの関数の番号が1つずれる。`call`・`return_call`・`ref.func`、
 * export、start、要素セグメント、グローバルの初期値の番号を付け替える。名前のカスタムセクションは捨てる。
 */
object FuelInjector {
    const val IMPORT_MODULE: String = "kibi"
    const val IMPORT_NAME: String = "fuel"
    const val EXPORT_GLOBAL: String = "kibi_fuel"

    /** 命令ごとの燃料。PoC ではどの命令も 1 */
    fun interface CostModel {
        fun cost(op: OpCode): Long
    }

    val UNIT: CostModel = CostModel { 1 }

    /** 関数の中で、残りをどこに置くか */
    enum class Storage { GLOBAL, LOCAL }

    /** 挿入の結果。[maxUnchecked] は、残りを確かめる間に実行されうる命令数の最大（燃料の行き過ぎの上限の目安） */
    class Result(val bytes: ByteArray, val checks: Int, val charges: Int, val maxUnchecked: Long)

    fun inject(wasm: ByteArray, storage: Storage = Storage.LOCAL, costModel: CostModel = UNIT): Result {
        val module = Parser.parse(wasm)
        val sections = scanSections(wasm)
        val importedFuncs = module.importSection().count(ExternalType.FUNCTION)
        val importedGlobals = module.importSection().count(ExternalType.GLOBAL)
        val definedGlobals = module.globalSection().globalCount()
        val newTypeIdx = module.typeSection().subTypeCount()
        val fuelFunc = importedFuncs
        val fuelGlobal = importedGlobals + definedGlobals
        val remap = { idx: Long -> if (idx >= fuelFunc) idx + 1 else idx }

        var checks = 0
        var charges = 0
        var maxUnchecked = 0L
        val out = Sink()
        out.bytes(wasm, 0, 8)

        fun has(id: Int) = sections.any { it.id == id }
        fun emit(id: Int, body: Sink) {
            out.u8(id)
            out.leb(body.size().toLong())
            out.append(body)
        }

        // セクションの並び順を保ったまま、無いセクション（型・import・グローバル・export）は決まった位置に作る
        val order = listOf(1, 2, 3, 4, 5, 6, 13, 7, 8, 9, 12, 10, 11)
        val created = HashSet<Int>()
        fun insertMissingBefore(id: Int) {
            val pos = if (id == Int.MAX_VALUE) Int.MAX_VALUE else order.indexOf(id)
            for (missing in listOf(SEC_TYPE, SEC_IMPORT, SEC_GLOBAL, SEC_EXPORT)) {
                if (has(missing) || order.indexOf(missing) >= pos || !created.add(missing)) continue
                val body = Sink()
                body.leb(1)
                when (missing) {
                    SEC_TYPE -> body.fuelType()
                    SEC_IMPORT -> body.fuelImport(newTypeIdx)
                    SEC_GLOBAL -> body.fuelGlobal()
                    SEC_EXPORT -> body.fuelExport(fuelGlobal)
                }
                emit(missing, body)
            }
        }

        for (s in sections) {
            if (s.id != SEC_CUSTOM) insertMissingBefore(s.id)
            val r = Reader(wasm, s.start, s.end)
            when (s.id) {
                SEC_CUSTOM -> {
                    // 名前のセクションは関数の番号がずれるので捨てる
                    val name = Reader(wasm, s.start, s.end).name()
                    if (name != "name") emit(s.id, Sink().also { it.bytes(wasm, s.start, s.end - s.start) })
                }
                SEC_TYPE -> {
                    val n = r.leb()
                    val body = Sink()
                    body.leb(n + 1)
                    body.bytes(wasm, r.pos, s.end - r.pos)
                    body.fuelType()
                    emit(s.id, body)
                }
                SEC_IMPORT -> {
                    val n = r.leb()
                    val body = Sink()
                    body.leb(n + 1)
                    body.bytes(wasm, r.pos, s.end - r.pos)
                    body.fuelImport(newTypeIdx)
                    emit(s.id, body)
                }
                SEC_GLOBAL -> {
                    val n = r.leb()
                    val body = Sink()
                    body.leb(n + 1)
                    repeat(n.toInt()) {
                        r.copyValType(body)
                        body.u8(r.u8()) // mut
                        r.copyConstExpr(body, remap)
                    }
                    body.fuelGlobal()
                    emit(s.id, body)
                }
                SEC_EXPORT -> {
                    val n = r.leb()
                    val body = Sink()
                    body.leb(n + 1)
                    repeat(n.toInt()) {
                        val nameStart = r.pos
                        r.name()
                        body.bytes(wasm, nameStart, r.pos - nameStart)
                        val kind = r.u8()
                        body.u8(kind)
                        val idx = r.leb()
                        body.leb(if (kind == 0) remap(idx) else idx)
                    }
                    body.fuelExport(fuelGlobal)
                    emit(s.id, body)
                }
                SEC_START -> emit(s.id, Sink().also { it.leb(remap(r.leb())) })
                SEC_ELEMENT -> emit(s.id, rewriteElements(r, remap))
                SEC_TABLE -> {
                    // 初期値の式を持つテーブル（GC の提案）は、PoC では扱わない
                    val n = r.leb()
                    repeat(n.toInt()) { check(wasm[r.pos].toInt() != 0x40) { "初期値の式を持つテーブルは未対応" }; r.skipTable() }
                    emit(s.id, Sink().also { it.bytes(wasm, s.start, s.end - s.start) })
                }
                SEC_CODE -> {
                    val n = r.leb().toInt()
                    val body = Sink()
                    body.leb(n.toLong())
                    for (i in 0 until n) {
                        val size = r.leb().toInt()
                        val bodyStart = r.pos
                        val bodyEnd = bodyStart + size
                        val fb = module.codeSection().getFunctionBody(i)
                        val params = module.functionSection().getFunctionType(i, module.typeSection()).params().size
                        val ctx = BodyContext(costModel, fuelFunc, fuelGlobal, remap, storage, params + fb.localTypes().size)
                        val fn = Sink()
                        val stats = rewriteBody(wasm, bodyStart, bodyEnd, fb.instructions(), ctx, fn)
                        checks += stats.checks
                        charges += stats.charges
                        maxUnchecked = maxOf(maxUnchecked, stats.maxUnchecked)
                        body.leb(fn.size().toLong())
                        body.append(fn)
                        r.pos = bodyEnd
                    }
                    emit(s.id, body)
                }
                else -> emit(s.id, Sink().also { it.bytes(wasm, s.start, s.end - s.start) })
            }
        }
        insertMissingBefore(Int.MAX_VALUE)
        return Result(out.toByteArray(), checks, charges, maxUnchecked)
    }

    private class BodyStats(val checks: Int, val charges: Int, val maxUnchecked: Long)

    /** 分岐の出入りがあり、セグメントを終わらせる命令 */
    private val SEGMENT_END = setOf(
        OpCode.BLOCK, OpCode.LOOP, OpCode.IF, OpCode.ELSE, OpCode.END, OpCode.TRY_TABLE,
        OpCode.BR, OpCode.BR_IF, OpCode.BR_TABLE, OpCode.RETURN, OpCode.UNREACHABLE,
        OpCode.THROW, OpCode.THROW_REF, OpCode.RETURN_CALL, OpCode.RETURN_CALL_INDIRECT, OpCode.RETURN_CALL_REF,
        OpCode.BR_ON_NULL, OpCode.BR_ON_NON_NULL, OpCode.BR_ON_CAST, OpCode.BR_ON_CAST_FAIL,
    )

    private class BodyContext(
        val costModel: CostModel,
        val fuelFunc: Int,
        val global: Int,
        val remap: (Long) -> Long,
        val storage: Storage,
        /** [Storage.LOCAL] で足すローカル変数の番号（引数とローカル変数の後ろ） */
        val local: Int,
    )

    private fun rewriteBody(
        wasm: ByteArray,
        bodyStart: Int,
        bodyEnd: Int,
        instructions: List<AnnotatedInstruction>,
        requested: BodyContext,
        out: Sink,
    ): BodyStats {
        val first = instructions.first().address()
        // try_table を含む関数は、ローカルに写さずグローバルで数える（理由は [FuelInjector] の KDoc）
        val ctx = if (requested.storage == Storage.LOCAL && instructions.any { it.opcode() == OpCode.TRY_TABLE }) {
            BodyContext(requested.costModel, requested.fuelFunc, requested.global, requested.remap, Storage.GLOBAL, requested.local)
        } else {
            requested
        }
        val cached = ctx.storage == Storage.LOCAL
        if (cached) {
            // ローカル変数の宣言に (1, i64) を足す
            val r = Reader(wasm, bodyStart, first)
            val groups = r.leb()
            out.leb(groups + 1)
            out.bytes(wasm, r.pos, first - r.pos)
            out.leb(1)
            out.u8(I64)
        } else {
            out.bytes(wasm, bodyStart, first - bodyStart)
        }

        val n = instructions.size
        // セグメントの区切り: [start, end]（end を含む）
        val segStart = BooleanArray(n)
        segStart[0] = true
        for (i in 0 until n - 1) if (instructions[i].opcode() in SEGMENT_END) segStart[i + 1] = true

        var checks = 0
        var charges = 0
        var maxUnchecked = 0L
        var sinceCheck = 0L
        var depth = 0 // 開いているブロックの数（関数そのものは数えない）
        for (i in 0 until n) {
            val ins = instructions[i]
            val op = ins.opcode()
            if (cached && i == 0) out.loadLocal(ctx)
            if (segStart[i]) {
                var j = i
                var cost = 0L
                while (true) {
                    cost += ctx.costModel.cost(instructions[j].opcode())
                    if (j == n - 1 || segStart[j + 1]) break
                    j++
                }
                out.charge(ctx, cost)
                charges++
                if (i == 0 || instructions[i - 1].opcode() == OpCode.LOOP) {
                    out.check(ctx)
                    checks++
                    sinceCheck = 0
                }
                sinceCheck += cost
                maxUnchecked = maxOf(maxUnchecked, sinceCheck)
            }

            // 呼び出し・戻る前に書き戻す
            val leaves = when (op) {
                OpCode.CALL, OpCode.CALL_INDIRECT, OpCode.CALL_REF,
                OpCode.RETURN, OpCode.RETURN_CALL, OpCode.RETURN_CALL_INDIRECT, OpCode.RETURN_CALL_REF,
                OpCode.THROW, OpCode.THROW_REF,
                -> true
                // 関数そのもののラベルへの分岐は、戻るのと同じ
                OpCode.BR, OpCode.BR_IF -> ins.operand(0).toInt() == depth
                // オペランドの並びに個数も入るが、余分に書き戻すだけなので害はない
                OpCode.BR_TABLE -> ins.operands().any { it.toInt() == depth }
                OpCode.END -> i == n - 1
                else -> false
            }
            if (cached && leaves) out.storeLocal(ctx)

            val start = ins.address()
            val end = if (i + 1 < n) instructions[i + 1].address() else bodyEnd
            when (op) {
                OpCode.CALL, OpCode.RETURN_CALL, OpCode.REF_FUNC -> {
                    out.u8(op.opcode())
                    out.leb(ctx.remap(ins.operand(0)))
                }
                else -> out.bytes(wasm, start, end - start)
            }

            // 呼び出しから戻ったら読み直す（呼んだ先が使った分がグローバルにある）
            if (cached && (op == OpCode.CALL || op == OpCode.CALL_INDIRECT || op == OpCode.CALL_REF)) out.loadLocal(ctx)

            when (op) {
                OpCode.BLOCK, OpCode.LOOP, OpCode.IF, OpCode.TRY_TABLE -> depth++
                OpCode.END -> depth--
                else -> Unit
            }
        }
        return BodyStats(checks, charges, maxUnchecked)
    }

    private fun rewriteElements(r: Reader, remap: (Long) -> Long): Sink {
        val body = Sink()
        val n = r.leb()
        body.leb(n)
        repeat(n.toInt()) {
            val flag = r.leb().toInt()
            body.leb(flag.toLong())
            fun funcIdxVec() {
                val m = r.leb()
                body.leb(m)
                repeat(m.toInt()) { body.leb(remap(r.leb())) }
            }
            fun exprVec() {
                val m = r.leb()
                body.leb(m)
                repeat(m.toInt()) { r.copyConstExpr(body, remap) }
            }
            when (flag) {
                0 -> { r.copyConstExpr(body, remap); funcIdxVec() }
                1, 3 -> { body.u8(r.u8()); funcIdxVec() }
                2 -> { body.leb(r.leb()); r.copyConstExpr(body, remap); body.u8(r.u8()); funcIdxVec() }
                4 -> { r.copyConstExpr(body, remap); exprVec() }
                5, 7 -> { r.copyValType(body); exprVec() }
                6 -> { body.leb(r.leb()); r.copyConstExpr(body, remap); r.copyValType(body); exprVec() }
                else -> error("要素セグメントの形式 $flag は未対応")
            }
        }
        return body
    }

    private const val SEC_CUSTOM = 0
    private const val SEC_TYPE = 1
    private const val SEC_IMPORT = 2
    private const val SEC_TABLE = 4
    private const val SEC_GLOBAL = 6
    private const val SEC_EXPORT = 7
    private const val SEC_START = 8
    private const val SEC_ELEMENT = 9
    private const val SEC_CODE = 10

    private class Section(val id: Int, val start: Int, val end: Int)

    private fun scanSections(wasm: ByteArray): List<Section> {
        val r = Reader(wasm, 8, wasm.size)
        val list = ArrayList<Section>()
        while (r.pos < wasm.size) {
            val id = r.u8()
            val size = r.leb().toInt()
            list += Section(id, r.pos, r.pos + size)
            r.pos += size
        }
        return list
    }

    // --- 挿入するコード ---

    private const val I64: Int = 0x7E

    /** 型 `(i64) -> (i64)` */
    private fun Sink.fuelType() {
        u8(0x60); leb(1); u8(I64); leb(1); u8(I64)
    }

    private fun Sink.fuelImport(typeIdx: Int) {
        name(IMPORT_MODULE); name(IMPORT_NAME); u8(0x00); leb(typeIdx.toLong())
    }

    /** `(global (mut i64) (i64.const 0))` */
    private fun Sink.fuelGlobal() {
        u8(I64); u8(0x01); u8(0x42); sleb(0); u8(0x0B)
    }

    private fun Sink.fuelExport(globalIdx: Int) {
        name(EXPORT_GLOBAL); u8(0x03); leb(globalIdx.toLong())
    }

    private fun Sink.get(ctx: BodyContext) {
        if (ctx.storage == Storage.LOCAL) {
            u8(0x20); leb(ctx.local.toLong()) // local.get
        } else {
            u8(0x23); leb(ctx.global.toLong()) // global.get
        }
    }

    private fun Sink.set(ctx: BodyContext) {
        if (ctx.storage == Storage.LOCAL) {
            u8(0x21); leb(ctx.local.toLong()) // local.set
        } else {
            u8(0x24); leb(ctx.global.toLong()) // global.set
        }
    }

    /** `local = global` */
    private fun Sink.loadLocal(ctx: BodyContext) {
        u8(0x23); leb(ctx.global.toLong())
        u8(0x21); leb(ctx.local.toLong())
    }

    /** `global = local` */
    private fun Sink.storeLocal(ctx: BodyContext) {
        u8(0x20); leb(ctx.local.toLong())
        u8(0x24); leb(ctx.global.toLong())
    }

    /** `fuel -= cost` */
    private fun Sink.charge(ctx: BodyContext, cost: Long) {
        get(ctx)
        u8(0x42); sleb(cost) // i64.const
        u8(0x7D) // i64.sub
        set(ctx)
    }

    /** `if (fuel < 0) fuel = kibi.fuel(fuel)` */
    private fun Sink.check(ctx: BodyContext) {
        get(ctx)
        u8(0x42); sleb(0)
        u8(0x53) // i64.lt_s
        u8(0x04); u8(0x40) // if (空のブロック型)
        get(ctx)
        u8(0x10); leb(ctx.fuelFunc.toLong()) // call
        set(ctx)
        u8(0x0B) // end
    }

    // --- バイト列の読み書き ---

    private class Sink : ByteArrayOutputStream() {
        fun u8(b: Int) = write(b and 0xFF)
        fun bytes(src: ByteArray, off: Int, len: Int) = write(src, off, len)
        fun append(other: Sink) = other.writeTo(this)

        fun leb(value: Long) {
            var v = value
            while (true) {
                val b = (v and 0x7F).toInt()
                v = v ushr 7
                if (v == 0L) {
                    u8(b)
                    return
                }
                u8(b or 0x80)
            }
        }

        fun sleb(value: Long) {
            var v = value
            while (true) {
                val b = (v and 0x7F).toInt()
                v = v shr 7
                val done = (v == 0L && b and 0x40 == 0) || (v == -1L && b and 0x40 != 0)
                if (done) {
                    u8(b)
                    return
                }
                u8(b or 0x80)
            }
        }

        fun name(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            leb(b.size.toLong())
            write(b)
        }
    }

    private class Reader(val buf: ByteArray, var pos: Int, val end: Int) {
        fun u8(): Int = buf[pos++].toInt() and 0xFF

        fun leb(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = u8()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun skipLeb() {
            while (u8() and 0x80 != 0) Unit
        }

        fun name(): String {
            val len = leb().toInt()
            val s = String(buf, pos, len, Charsets.UTF_8)
            pos += len
            return s
        }

        /** 値の型（参照型のヒープ型を含む）をそのまま写す */
        fun copyValType(out: Sink) {
            val start = pos
            val t = u8()
            if (t == 0x63 || t == 0x64) skipLeb()
            out.bytes(buf, start, pos - start)
        }

        fun skipTable() {
            val t = u8()
            if (t == 0x63 || t == 0x64) skipLeb()
            val flags = u8()
            skipLeb()
            if (flags and 1 != 0) skipLeb()
        }

        /** 定数式を写す。`ref.func` の番号だけ付け替える */
        fun copyConstExpr(out: Sink, remap: (Long) -> Long) {
            while (true) {
                val start = pos
                when (val op = u8()) {
                    0x0B -> { out.u8(op); return }
                    0x41, 0x42, 0x23 -> { skipLeb(); out.bytes(buf, start, pos - start) }
                    0x43 -> { pos += 4; out.bytes(buf, start, pos - start) }
                    0x44 -> { pos += 8; out.bytes(buf, start, pos - start) }
                    0xD0 -> { skipLeb(); out.bytes(buf, start, pos - start) } // ref.null
                    0xD2 -> { out.u8(op); out.leb(remap(leb())) } // ref.func
                    0x6A, 0x6B, 0x6C, 0x7C, 0x7D, 0x7E -> out.u8(op) // 拡張定数式
                    else -> error("定数式の命令 0x%02X は未対応".format(op))
                }
            }
        }
    }
}
