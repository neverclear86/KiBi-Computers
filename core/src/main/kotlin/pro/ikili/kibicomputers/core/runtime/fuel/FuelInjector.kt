package pro.ikili.kibicomputers.core.runtime.fuel

import pro.ikili.kibicomputers.core.runtime.WasmFuel
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import run.endive.wasm.Parser
import run.endive.wasm.types.AnnotatedInstruction
import run.endive.wasm.types.ExternalType
import run.endive.wasm.types.FunctionImport
import run.endive.wasm.types.OpCode
import java.io.ByteArrayOutputStream

/**
 * 燃料の仮の重み（ADR 0028。確定は #251）。燃料 1 は、おおよそ WASM の命令 1 つ。
 *
 * 長さに比例する命令は、命令そのものの 1 に加えて、長さに応じた分を課す（1 命令で大量のメモリを動かせるため）。
 */
internal object FuelCosts {
    /** 命令 1 つ */
    const val INSTRUCTION: Long = 1

    /** `memory.copy`・`memory.fill`・`memory.init`: 長さ（バイト）を右にこれだけずらした分（8 バイトで 1） */
    const val MEMORY_BYTES_SHIFT: Int = 3

    /** `table.copy`・`table.fill`・`table.init`・`table.grow`: 要素の数を右にこれだけずらした分（1 要素で 1） */
    const val TABLE_ELEMENTS_SHIFT: Int = 0

    /** `memory.grow`: 増やすページ数を左にこれだけずらした分（1 ページ = 64 KiB を `memory.fill` したのと同じ 8,192） */
    const val MEMORY_GROW_PAGE_SHIFT: Int = 13
}

/**
 * 読み込み時に WASM へ燃料の計測コードを挿入する（#38。方式は ADR 0028、PoC は `poc/fuel-metering/`）。
 *
 * 入れるもの:
 * - 可変の i64 のグローバル（残りの燃料）。[WasmFuel.EXPORT_GLOBAL] という名前で export する
 * - import の関数 [WasmFuel.IMPORT_MODULE].[WasmFuel.IMPORT_NAME]（`(i64 残り) -> (i64 新しい残り)`）。
 *   燃料が負になったときに呼ぶ。カーネルはここでスレッドを止めて次の tick を待つ（#27）か、例外を投げて実行をやめさせる
 *
 * 数え方:
 * - 関数の本体を「途中で分岐の出入りがない命令の並び」（セグメント）に分け、各セグメントの先頭でその命令数を引く。
 *   `block`・`try_table` に入るところは分岐の行き先にならないので、前のセグメントとまとめる（挿入するコードを小さくする）
 * - 残りが負かを確かめて [WasmFuel.IMPORT_NAME] を呼ぶのは、関数の入口と `loop` の先頭だけ。
 *   終わらない実行は必ずどちらかを通るので、確かめる間に実行される命令は関数 1 つの長さで抑えられる
 * - 長さに比例する命令（[FuelCosts]）は、その命令の直前で、スタックの一番上にある長さを読んで引く
 *
 * 残りの置き場所: 関数の中ではローカル変数に写し、ほかの関数を呼ぶ前・戻る前・例外を投げる前にグローバルへ書き戻し、
 * 呼び出しから戻った後にグローバルから読み直す。ホストの関数（システムコール）は、呼ばれている間にグローバルを読み書きして燃料を課せる。
 * ただし `try_table` を含む関数は、ローカルに写さずグローバルを直接読み書きする（理由は ADR 0028）。
 *
 * import の関数を1つ足すので、定義済みの関数の番号が1つずれる。`call`・`return_call`・`ref.func`、
 * export、start、要素セグメント、グローバルの初期値の番号を付け替える。名前のカスタムセクションは捨てる。
 *
 * 断るもの（[WasmLoadException]）: WASM として読めない、すでに [WasmFuel] の名前を import・export している
 * （プレイヤーが燃料を自分で書き換えられてしまう）、グローバルを import している、初期値の式を持つテーブル。
 */
internal object FuelInjector {
    /**
     * 挿入の結果。
     *
     * @property bytes 挿入した後の WASM
     * @property checks 残りを確かめるところの数
     * @property charges 燃料を引くところの数
     * @property maxUnchecked 残りを確かめる間に実行されうる命令数の最大（燃料の行き過ぎの上限の目安。長さに比例する分は含まない）
     * @property sizesBefore 定義済みの関数ごとの、挿入の前の本体の大きさ（バイト）
     * @property sizesAfter 同じく挿入の後
     */
    class Result(
        val bytes: ByteArray,
        val checks: Int,
        val charges: Int,
        val maxUnchecked: Long,
        val sizesBefore: IntArray,
        val sizesAfter: IntArray,
    )

    /** [wasm] に計測コードを挿入する。重いので（lua.wasm で数十 ms）、メインスレッドでは呼ばない */
    @Throws(WasmLoadException::class)
    fun inject(wasm: ByteArray): Result = try {
        injectUnchecked(wasm)
    } catch (e: WasmLoadException) {
        throw e
    } catch (e: Exception) {
        throw WasmLoadException("燃料の計測コードを挿入できない: ${e.message}", e)
    }

    private fun injectUnchecked(wasm: ByteArray): Result {
        val module = Parser.parse(wasm)
        val importSection = module.importSection()
        for (i in 0 until importSection.importCount()) {
            val imp = importSection.getImport(i)
            if (imp is FunctionImport && imp.module() == WasmFuel.IMPORT_MODULE && imp.name() == WasmFuel.IMPORT_NAME) {
                throw WasmLoadException("${WasmFuel.IMPORT_MODULE}.${WasmFuel.IMPORT_NAME} は予約された import")
            }
        }
        val exportSection = module.exportSection()
        for (i in 0 until exportSection.exportCount()) {
            if (exportSection.getExport(i).name() == WasmFuel.EXPORT_GLOBAL) throw WasmLoadException("${WasmFuel.EXPORT_GLOBAL} は予約された export")
        }
        val importedGlobals = importSection.count(ExternalType.GLOBAL)
        if (importedGlobals > 0) throw WasmLoadException("グローバルの import には対応していない")

        val sections = scanSections(wasm)
        val importedFuncs = importSection.count(ExternalType.FUNCTION)
        val definedGlobals = module.globalSection().globalCount()
        val newTypeIdx = module.typeSection().subTypeCount()
        val fuelFunc = importedFuncs
        val fuelGlobal = importedGlobals + definedGlobals
        val remap = { idx: Long -> if (idx >= fuelFunc) idx + 1 else idx }

        var checks = 0
        var charges = 0
        var maxUnchecked = 0L
        var sizesBefore = IntArray(0)
        var sizesAfter = IntArray(0)
        val out = Sink()
        out.bytes(wasm, 0, 8)

        fun has(id: Int) = sections.any { it.id == id }
        fun emit(id: Int, body: Sink) {
            out.u8(id)
            out.leb(body.size().toLong())
            out.append(body)
        }

        // セクションの並び順を保ったまま、無いセクション（型・import・グローバル・export）は決まった位置に作る
        val order = listOf(SEC_TYPE, SEC_IMPORT, 3, SEC_TABLE, 5, SEC_GLOBAL, 13, SEC_EXPORT, SEC_START, SEC_ELEMENT, 12, SEC_CODE, 11)
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
                    if (Reader(wasm, s.start, s.end).name() != "name") emit(s.id, Sink().also { it.bytes(wasm, s.start, s.end - s.start) })
                }
                SEC_TYPE, SEC_IMPORT -> {
                    val n = r.leb()
                    val body = Sink()
                    body.leb(n + 1)
                    body.bytes(wasm, r.pos, s.end - r.pos)
                    if (s.id == SEC_TYPE) body.fuelType() else body.fuelImport(newTypeIdx)
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
                    val n = r.leb()
                    repeat(n.toInt()) {
                        if (wasm[r.pos].toInt() == 0x40) throw WasmLoadException("初期値の式を持つテーブルには対応していない")
                        r.skipTable()
                    }
                    emit(s.id, Sink().also { it.bytes(wasm, s.start, s.end - s.start) })
                }
                SEC_CODE -> {
                    val n = r.leb().toInt()
                    sizesBefore = IntArray(n)
                    sizesAfter = IntArray(n)
                    val body = Sink()
                    body.leb(n.toLong())
                    for (i in 0 until n) {
                        val size = r.leb().toInt()
                        val bodyStart = r.pos
                        val bodyEnd = bodyStart + size
                        val fb = module.codeSection().getFunctionBody(i)
                        val params = module.functionSection().getFunctionType(i, module.typeSection()).params().size
                        val ctx = BodyContext(fuelFunc, fuelGlobal, remap, params + fb.localTypes().size)
                        val fn = Sink()
                        val stats = rewriteBody(wasm, bodyStart, bodyEnd, fb.instructions(), ctx, fn)
                        checks += stats.checks
                        charges += stats.charges
                        maxUnchecked = maxOf(maxUnchecked, stats.maxUnchecked)
                        sizesBefore[i] = size
                        sizesAfter[i] = fn.size()
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
        return Result(out.toByteArray(), checks, charges, maxUnchecked, sizesBefore, sizesAfter)
    }

    private class BodyStats(val checks: Int, val charges: Int, val maxUnchecked: Long)

    /** 分岐の出入りがあり、次の命令から新しいセグメントにする命令 */
    private val SEGMENT_END = setOf(
        OpCode.LOOP, OpCode.IF, OpCode.ELSE, OpCode.END,
        OpCode.BR, OpCode.BR_IF, OpCode.BR_TABLE, OpCode.RETURN, OpCode.UNREACHABLE,
        OpCode.THROW, OpCode.THROW_REF, OpCode.RETURN_CALL, OpCode.RETURN_CALL_INDIRECT, OpCode.RETURN_CALL_REF,
        OpCode.BR_ON_NULL, OpCode.BR_ON_NON_NULL, OpCode.BR_ON_CAST, OpCode.BR_ON_CAST_FAIL,
    )

    /** ほかの関数を呼ぶ命令（前に書き戻し、戻ったら読み直す） */
    private val CALLS = setOf(OpCode.CALL, OpCode.CALL_INDIRECT, OpCode.CALL_REF)

    /** 関数から抜ける命令（前に書き戻す） */
    private val EXITS = setOf(
        OpCode.RETURN, OpCode.RETURN_CALL, OpCode.RETURN_CALL_INDIRECT, OpCode.RETURN_CALL_REF,
        OpCode.THROW, OpCode.THROW_REF,
    )

    /**
     * 長さに比例する命令と、長さを燃料にする変換（正なら右シフト、負なら左シフトの量）。
     * どれもスタックの一番上が長さ（i32）。Endive は memory64・table64 に対応していないので、i32 だけを扱う
     */
    private val BULK: Map<OpCode, Int> = mapOf(
        OpCode.MEMORY_COPY to FuelCosts.MEMORY_BYTES_SHIFT,
        OpCode.MEMORY_FILL to FuelCosts.MEMORY_BYTES_SHIFT,
        OpCode.MEMORY_INIT to FuelCosts.MEMORY_BYTES_SHIFT,
        OpCode.TABLE_COPY to FuelCosts.TABLE_ELEMENTS_SHIFT,
        OpCode.TABLE_FILL to FuelCosts.TABLE_ELEMENTS_SHIFT,
        OpCode.TABLE_INIT to FuelCosts.TABLE_ELEMENTS_SHIFT,
        OpCode.TABLE_GROW to FuelCosts.TABLE_ELEMENTS_SHIFT,
        OpCode.MEMORY_GROW to -FuelCosts.MEMORY_GROW_PAGE_SHIFT,
    )

    private class BodyContext(
        val fuelFunc: Int,
        val global: Int,
        val remap: (Long) -> Long,
        /** 足すローカル変数の最初の番号（引数とローカル変数の後ろ） */
        val firstNewLocal: Int,
    ) {
        /** 残りをローカルに写すか（`try_table` を含まない関数） */
        var cached: Boolean = false

        /** 残りを写すローカル（i64）の番号 */
        var fuelLocal: Int = -1

        /** 長さを控えるローカル（i32）の番号 */
        var lengthLocal: Int = -1
    }

    private fun rewriteBody(
        wasm: ByteArray,
        bodyStart: Int,
        bodyEnd: Int,
        instructions: List<AnnotatedInstruction>,
        ctx: BodyContext,
        out: Sink,
    ): BodyStats {
        val first = instructions.first().address()
        // try_table を含む関数は、ローカルに写さずグローバルで数える（理由は ADR 0028）
        ctx.cached = instructions.none { it.opcode() == OpCode.TRY_TABLE }
        val hasBulk = instructions.any { it.opcode() in BULK }
        var next = ctx.firstNewLocal
        if (ctx.cached) ctx.fuelLocal = next++
        if (hasBulk) ctx.lengthLocal = next

        // ローカル変数の宣言に (1, i64)・(1, i32) を足す
        val r = Reader(wasm, bodyStart, first)
        val groups = r.leb()
        val added = (if (ctx.cached) 1 else 0) + (if (hasBulk) 1 else 0)
        out.leb(groups + added)
        out.bytes(wasm, r.pos, first - r.pos)
        if (ctx.cached) {
            out.leb(1)
            out.u8(I64)
        }
        if (hasBulk) {
            out.leb(1)
            out.u8(I32)
        }

        val n = instructions.size
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
            if (ctx.cached && i == 0) out.loadLocal(ctx)
            if (segStart[i]) {
                var j = i
                var cost = 0L
                while (true) {
                    cost += FuelCosts.INSTRUCTION
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

            BULK[op]?.let { shift -> out.chargeLength(ctx, shift) }

            val leaves = when (op) {
                in CALLS, in EXITS -> true
                // 関数そのもののラベルへの分岐は、戻るのと同じ
                OpCode.BR, OpCode.BR_IF -> ins.operand(0).toInt() == depth
                // オペランドの並びに個数も入るが、余分に書き戻すだけなので害はない
                OpCode.BR_TABLE -> ins.operands().any { it.toInt() == depth }
                OpCode.END -> i == n - 1
                else -> false
            }
            if (ctx.cached && leaves) out.storeLocal(ctx)

            val start = ins.address()
            val end = if (i + 1 < n) instructions[i + 1].address() else bodyEnd
            when (op) {
                OpCode.CALL, OpCode.RETURN_CALL, OpCode.REF_FUNC -> {
                    out.u8(op.opcode())
                    out.leb(ctx.remap(ins.operand(0)))
                }
                else -> out.bytes(wasm, start, end - start)
            }

            // 呼び出しから戻ったら読み直す（呼んだ先・ホストの関数が使った分がグローバルにある）
            if (ctx.cached && op in CALLS) out.loadLocal(ctx)

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
                0 -> {
                    r.copyConstExpr(body, remap)
                    funcIdxVec()
                }
                1, 3 -> {
                    body.u8(r.u8())
                    funcIdxVec()
                }
                2 -> {
                    body.leb(r.leb())
                    r.copyConstExpr(body, remap)
                    body.u8(r.u8())
                    funcIdxVec()
                }
                4 -> {
                    r.copyConstExpr(body, remap)
                    exprVec()
                }
                5, 7 -> {
                    r.copyValType(body)
                    exprVec()
                }
                6 -> {
                    body.leb(r.leb())
                    r.copyConstExpr(body, remap)
                    r.copyValType(body)
                    exprVec()
                }
                else -> throw WasmLoadException("要素セグメントの形式 $flag には対応していない")
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

    private const val I32: Int = 0x7F
    private const val I64: Int = 0x7E

    /** 型 `(i64) -> (i64)` */
    private fun Sink.fuelType() {
        u8(0x60)
        leb(1)
        u8(I64)
        leb(1)
        u8(I64)
    }

    private fun Sink.fuelImport(typeIdx: Int) {
        name(WasmFuel.IMPORT_MODULE)
        name(WasmFuel.IMPORT_NAME)
        u8(0x00)
        leb(typeIdx.toLong())
    }

    /** `(global (mut i64) (i64.const 0))` */
    private fun Sink.fuelGlobal() {
        u8(I64)
        u8(0x01)
        u8(0x42)
        sleb(0)
        u8(0x0B)
    }

    private fun Sink.fuelExport(globalIdx: Int) {
        name(WasmFuel.EXPORT_GLOBAL)
        u8(0x03)
        leb(globalIdx.toLong())
    }

    private fun Sink.get(ctx: BodyContext) {
        if (ctx.cached) {
            u8(0x20) // local.get
            leb(ctx.fuelLocal.toLong())
        } else {
            u8(0x23) // global.get
            leb(ctx.global.toLong())
        }
    }

    private fun Sink.set(ctx: BodyContext) {
        if (ctx.cached) {
            u8(0x21) // local.set
            leb(ctx.fuelLocal.toLong())
        } else {
            u8(0x24) // global.set
            leb(ctx.global.toLong())
        }
    }

    /** `local = global` */
    private fun Sink.loadLocal(ctx: BodyContext) {
        u8(0x23)
        leb(ctx.global.toLong())
        u8(0x21)
        leb(ctx.fuelLocal.toLong())
    }

    /** `global = local` */
    private fun Sink.storeLocal(ctx: BodyContext) {
        u8(0x20)
        leb(ctx.fuelLocal.toLong())
        u8(0x24)
        leb(ctx.global.toLong())
    }

    /** `fuel -= cost` */
    private fun Sink.charge(ctx: BodyContext, cost: Long) {
        get(ctx)
        u8(0x42) // i64.const
        sleb(cost)
        u8(0x7D) // i64.sub
        set(ctx)
    }

    /** スタックの一番上の長さ（i32。そのまま残す）を [shift] で燃料にして引く: `fuel -= u64(len) >> shift`（負なら `<< -shift`） */
    private fun Sink.chargeLength(ctx: BodyContext, shift: Int) {
        u8(0x22) // local.tee
        leb(ctx.lengthLocal.toLong())
        get(ctx)
        u8(0x20) // local.get
        leb(ctx.lengthLocal.toLong())
        u8(0xAD) // i64.extend_i32_u
        if (shift != 0) {
            u8(0x42)
            sleb(if (shift > 0) shift.toLong() else (-shift).toLong())
            u8(if (shift > 0) 0x88 else 0x86) // i64.shr_u / i64.shl
        }
        u8(0x7D) // i64.sub
        set(ctx)
    }

    /** `if (fuel < 0) fuel = kibi.fuel(fuel)` */
    private fun Sink.check(ctx: BodyContext) {
        get(ctx)
        u8(0x42)
        sleb(0)
        u8(0x53) // i64.lt_s
        u8(0x04) // if (空のブロック型)
        u8(0x40)
        get(ctx)
        u8(0x10) // call
        leb(ctx.fuelFunc.toLong())
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
        fun u8(): Int {
            if (pos >= end) throw WasmLoadException("セクションの終わりを超えて読んだ")
            return buf[pos++].toInt() and 0xFF
        }

        fun leb(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = u8()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw WasmLoadException("LEB128 が長すぎる")
            }
        }

        fun skipLeb() {
            while (u8() and 0x80 != 0) Unit
        }

        fun name(): String {
            val len = leb().toInt()
            if (len < 0 || pos + len > end) throw WasmLoadException("名前が範囲を超える")
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
                    0x0B -> {
                        out.u8(op)
                        return
                    }
                    0x41, 0x42, 0x23 -> {
                        skipLeb()
                        out.bytes(buf, start, pos - start)
                    }
                    0x43 -> {
                        pos += 4
                        out.bytes(buf, start, pos - start)
                    }
                    0x44 -> {
                        pos += 8
                        out.bytes(buf, start, pos - start)
                    }
                    0xD0 -> { // ref.null
                        skipLeb()
                        out.bytes(buf, start, pos - start)
                    }
                    0xD2 -> { // ref.func
                        out.u8(op)
                        out.leb(remap(leb()))
                    }
                    0x6A, 0x6B, 0x6C, 0x7C, 0x7D, 0x7E -> out.u8(op) // 拡張定数式
                    else -> throw WasmLoadException("定数式の命令 0x%02X には対応していない".format(op))
                }
            }
        }
    }
}
