package pro.ikili.kibicomputers.core.runtime.asyncify

import pro.ikili.kibicomputers.core.runtime.WasmAsync
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import run.endive.wasm.Parser
import run.endive.wasm.WasmModule
import run.endive.wasm.types.AnnotatedInstruction
import run.endive.wasm.types.FunctionImport
import run.endive.wasm.types.OpCode
import run.endive.wasm.types.ValType
import java.io.ByteArrayOutputStream

/**
 * 変換できない作りのモジュール（#329）。`Program.loadMetered` は、これを原因にした `WasmLoadException` で読み込みを断る
 * （`exec` は `NOEXEC`。#367）。
 *
 * [message] は断った理由（どの命令・どの機能か。`exec` のエラーメッセージに入る）。
 */
internal class AsyncifyUnsupported(message: String) : RuntimeException(message)

/**
 * 読み込み時にかける、待機点への変換（asyncify 相当。#329、方式は ADR 0316、PoC は `poc/threadless-wait/`）。
 *
 * 止まりうる import（[transform] の `waitImports`。p1 では `fd_read`・`fd_write`・`poll_oneoff`）の中でホストが状態を
 * [WasmAsyncState.UNWINDING][pro.ikili.kibicomputers.core.runtime.WasmAsyncState.UNWINDING] にして戻ると、呼んだ関数は
 * ローカル変数と「どの呼び出しで止まったか」をホストへ退避して戻り、それを export の入口まで繰り返す。
 * WASM のスタックが空になる（＝待機点になる）ので、スレッドを返せる。
 * 再開するときは、状態を `REWINDING` にして同じ export を呼ぶ。各関数は入口でローカル変数を戻し、止まった呼び出しまで、
 * ほかのコードを飛ばして進む。止まりうる import に着いたら、ホストが状態を `NORMAL` に戻して、その呼び出しをやり直す。
 *
 * binaryen はコードを平らにして（Flatten）からこれを行う。ここでは平らにせず、次の形にする:
 * - 止まりうる呼び出し（止まりうる import・止まりうる関数・`call_indirect`・`call_ref`）と、それを含む構造（block・loop・if・try_table）を
 *   「待ち点」とする。待ち点の直前で、オペランドスタックに積まれている値をすべて一時ローカルに移し（スタックを空にする）、直後に戻す
 * - 待ち点の間の命令の並び（ラン）は `if (state == NORMAL)` で包む。巻き直し中は飛ばされる。ランの中の分岐は、包んだ分だけ深さを足す
 * - 呼び出しは `if (state == NORMAL || k == K)` で包む（K はその関数の中の呼び出しの番号）
 * - `if` の条件は一時ローカルに入れておく。巻き直し中は戻したローカルで同じ枝に入る
 * - ブロックの最後のランは `if (result T) … else <ダミーの値> end` にする（巻き直し中に結果の型をそろえるため）
 * - `call_indirect` は、型が同じ止まりうる関数があるときだけ止まりうる、と見る
 *
 * 足すもの（名前は [WasmAsync]。カーネルと変換したコードの間の取り決めで、プログラムには見せない）:
 * - import の関数 `kibi.async_push`（`(i64) -> ()`）・`kibi.async_pop`（`() -> (i64)`）。退避先はホスト（カーネル）で、
 *   線形メモリには書かない（モジュールのメモリの配置を変えない）。import の関数の最後に足すので、定義済みの関数の番号が2つずれる
 * - 可変の i32 のグローバル（状態。0 = NORMAL、1 = UNWINDING、2 = REWINDING）。`kibi_async_state` という名前で export する
 *
 * 1つの関数が巻き戻しで積む値の並び: 退避するローカル（i32・f32・f64 は 64 ビットに広げる。参照型は null だけで、
 * null でなければ trap）、最後に「どの呼び出しで止まったか」の番号。内側の関数から順に積み、巻き直しは外側から順に取り出す。
 *
 * 名前のカスタムセクションは捨てる（燃料の計測コードの挿入と同じ）。
 */
internal object Asyncify {
    /**
     * 変換の結果。
     *
     * @property bytes 変換後の WASM
     * @property functions 定義済みの関数の数
     * @property instrumented 変換した（止まりうる）関数の数
     * @property sites 止まりうる呼び出しの数
     * @property codeBefore コードセクションの大きさ（変換前）
     * @property codeAfter 同じく変換後
     * @property maxLocals 変換した関数の、退避するローカル変数の数の最大
     */
    class Result(
        val bytes: ByteArray,
        val functions: Int,
        val instrumented: Int,
        val sites: Int,
        val codeBefore: Int,
        val codeAfter: Int,
        val maxLocals: Int,
    )

    /**
     * [wasm] を変換する。[waitImports] は止まりうる import（`"<module>.<name>"`）。重いので（lua.wasm で約 100 ms）、メインスレッドでは呼ばない。
     *
     * [checkpoint] は関数の本体を1つ書き換えるごとに呼ぶ（検証の時間の上限。#40）。投げた [WasmLoadException] はそのまま外に出る。
     *
     * @throws AsyncifyUnsupported 変換できない作り（SIMD、GC・型付きの参照型、止まりうる関数の中の末尾呼び出し、
     *   止まりうる構造の中の、引数を持つブロックや複数の結果を持つブロック、関数でない import など）。変換せずに動かす
     * @throws WasmLoadException WASM として読めない、予約した名前（[WasmAsync]）をすでに使っている、時間の上限を超えた。読み込み自体を断る
     */
    @Throws(WasmLoadException::class)
    fun transform(wasm: ByteArray, waitImports: Set<String>, checkpoint: () -> Unit = {}): Result {
        val module = try {
            Parser.parse(wasm)
        } catch (e: RuntimeException) {
            throw WasmLoadException("WASM として読めない: ${e.message}", e)
        } catch (e: StackOverflowError) {
            throw WasmLoadException("WASM を読み込めない: 入れ子が深すぎる", e)
        }
        checkpoint()
        checkReserved(module)
        return try {
            Transformer(wasm, module, waitImports, checkpoint).run()
        } catch (e: WasmLoadException) {
            throw e
        } catch (e: AsyncifyUnsupported) {
            throw e
        } catch (e: RuntimeException) {
            throw AsyncifyUnsupported("変換できない: $e").also { it.initCause(e) }
        } catch (e: StackOverflowError) {
            throw AsyncifyUnsupported("変換できない: 入れ子が深すぎる").also { it.initCause(e) }
        }
    }

    /** 予約した名前をすでに使っていたら断る（プレイヤーが退避した値や状態を自分で書き換えられてしまう） */
    private fun checkReserved(module: WasmModule) {
        val imports = module.importSection()
        for (i in 0 until imports.importCount()) {
            val imp = imports.getImport(i)
            if (imp.module() == WasmAsync.IMPORT_MODULE && (imp.name() == WasmAsync.PUSH || imp.name() == WasmAsync.POP)) {
                throw WasmLoadException("${imp.module()}.${imp.name()} は予約された import")
            }
        }
        val exports = module.exportSection()
        for (i in 0 until exports.exportCount()) {
            if (exports.getExport(i).name() == WasmAsync.STATE_EXPORT) throw WasmLoadException("${WasmAsync.STATE_EXPORT} は予約された export")
        }
    }
}

private const val UNWINDING = 1
private const val REWINDING = 2

private const val I32 = 0x7F
private const val I64 = 0x7E
private const val F32 = 0x7D
private const val F64 = 0x7C
private const val V128 = 0x7B
private const val BOT = -1

private fun isRef(t: Int) = t != I32 && t != I64 && t != F32 && t != F64 && t != V128 && t != BOT

private fun code(v: ValType): Int {
    val op = v.opcode()
    // Endive は funcref などの略記を (ref null <抽象ヒープ型>) で持つ。抽象ヒープ型の番号は「略記のバイト - 0x80」
    if (op == 0x63 && v.typeIdx() < 0) return v.typeIdx() + 0x80
    if (op == 0x63 || op == 0x64) throw AsyncifyUnsupported("型付きの参照型には対応していない")
    return op
}

private class Sig(val params: List<Int>, val results: List<Int>)

/** 関数の本体の木。葉は命令、枝は block・loop・if・try_table */
private sealed class Node

private class Leaf(val ins: AnnotatedInstruction) : Node()

private class Struct(
    val open: AnnotatedInstruction,
    val body: List<Node>,
    val els: List<Node>?,
) : Node()

private class TypeStack(initial: List<Int> = emptyList()) {
    val types = ArrayList(initial)
    var unreachable = false

    fun push(t: Int) {
        types += t
    }

    fun pop(): Int = if (types.isEmpty()) {
        if (!unreachable) throw AsyncifyUnsupported("スタックが空なのに取り出した")
        BOT
    } else {
        types.removeAt(types.size - 1)
    }

    fun pop(n: Int) = repeat(n) { pop() }

    fun kill() {
        types.clear()
        unreachable = true
    }
}

private class Transformer(
    private val wasm: ByteArray,
    private val module: WasmModule,
    private val asyncImports: Set<String>,
    private val checkpoint: () -> Unit,
) {
    private val importedFuncs: Int
    private val funcSigs = ArrayList<Sig>()
    private val typeSigs = ArrayList<Sig>()
    private val globalTypes = ArrayList<Int>()
    private val tableTypes = ArrayList<Int>()
    private val tagSigs = ArrayList<Sig>()
    private val asyncFuncs = HashSet<Int>()

    /** 止まりうる関数（元の番号） */
    private val suspendable = HashSet<Int>()

    private val pushFunc: Int
    private val popFunc: Int
    private val stateGlobal: Int

    init {
        val types = module.typeSection()
        for (i in 0 until types.typeCount()) {
            val t = types.getType(i)
            typeSigs += Sig(t.params().map(::code), t.returns().map(::code))
        }
        val imports = module.importSection()
        var nf = 0
        for (i in 0 until imports.importCount()) {
            val imp = imports.getImport(i)
            if (imp !is FunctionImport) throw AsyncifyUnsupported("関数でない import には対応していない: ${imp.module()}.${imp.name()}")
            if ("${imp.module()}.${imp.name()}" in asyncImports) asyncFuncs += nf
            funcSigs += typeSigs[imp.typeIndex()]
            nf++
        }
        importedFuncs = nf
        val fs = module.functionSection()
        for (i in 0 until fs.functionCount()) funcSigs += typeSigs[fs.getFunctionType(i)]
        val gs = module.globalSection()
        for (i in 0 until gs.globalCount()) globalTypes += code(gs.getGlobal(i).valueType())
        val ts = module.tableSection()
        for (i in 0 until ts.tableCount()) tableTypes += code(ts.getTable(i).elementType())
        module.tagSection().ifPresent { tags -> tags.types().forEach { tagSigs += typeSigs[it.typeIdx()] } }
        pushFunc = importedFuncs
        popFunc = importedFuncs + 1
        stateGlobal = globalTypes.size
    }

    /** 止まりうる関数の型（引数と結果の並び）。間接呼び出しは、型が同じ関数しか呼べないので、この型のときだけ止まりうる */
    private val suspendTypes = HashSet<Pair<List<Int>, List<Int>>>()

    private fun indirectMaySuspend(typeIdx: Int): Boolean = typeSigs[typeIdx].let { it.params to it.results } in suspendTypes

    private fun remap(idx: Long): Long = if (idx >= importedFuncs) idx + 2 else idx

    private fun bodies(): List<List<AnnotatedInstruction>> =
        (0 until module.codeSection().functionBodyCount()).map { module.codeSection().getFunctionBody(it).instructions() }

    private fun analyze(bodies: List<List<AnnotatedInstruction>>) {
        if (asyncFuncs.isEmpty()) return
        suspendable += asyncFuncs
        var changed = true
        while (changed) {
            changed = false
            for (f in suspendable) suspendTypes += funcSigs[f].params to funcSigs[f].results
            for ((i, body) in bodies.withIndex()) {
                val f = importedFuncs + i
                if (f in suspendable) continue
                val calls = body.any { ins ->
                    when (ins.opcode()) {
                        OpCode.CALL, OpCode.RETURN_CALL -> ins.operand(0).toInt() in suspendable
                        OpCode.CALL_INDIRECT, OpCode.CALL_REF, OpCode.RETURN_CALL_INDIRECT, OpCode.RETURN_CALL_REF ->
                            indirectMaySuspend(ins.operand(0).toInt())
                        else -> false
                    }
                }
                if (calls) {
                    suspendable += f
                    changed = true
                }
            }
        }
    }

    fun run(): Asyncify.Result {
        val bodies = bodies()
        analyze(bodies)
        val sections = scanSections()
        for (id in listOf(1, 2, 6, 7)) if (sections.none { it.id == id }) throw AsyncifyUnsupported("セクション $id がない")
        val newTypePush = module.typeSection().subTypeCount()
        val out = Sink()
        out.bytes(wasm, 0, 8)
        var codeBefore = 0
        var codeAfter = 0
        var instrumented = 0
        var sites = 0
        var maxLocals = 0
        fun emit(id: Int, body: Sink) {
            out.u8(id)
            out.leb(body.size().toLong())
            out.append(body)
        }
        for (s in sections) {
            val r = Reader(wasm, s.start, s.end)
            when (s.id) {
                0 -> if (Reader(wasm, s.start, s.end).name() != "name") emit(0, Sink().also { it.bytes(wasm, s.start, s.end - s.start) })
                1 -> {
                    val n = r.leb()
                    val body = Sink()
                    body.leb(n + 2)
                    body.bytes(wasm, r.pos, s.end - r.pos)
                    // (i64) -> () と () -> (i64)
                    body.u8(0x60); body.leb(1); body.u8(I64); body.leb(0)
                    body.u8(0x60); body.leb(0); body.leb(1); body.u8(I64)
                    emit(1, body)
                }
                2 -> {
                    val n = r.leb()
                    val body = Sink()
                    body.leb(n + 2)
                    body.bytes(wasm, r.pos, s.end - r.pos)
                    body.name(WasmAsync.IMPORT_MODULE); body.name(WasmAsync.PUSH); body.u8(0); body.leb(newTypePush)
                    body.name(WasmAsync.IMPORT_MODULE); body.name(WasmAsync.POP); body.u8(0); body.leb(newTypePush + 1)
                    emit(2, body)
                }
                6 -> {
                    val n = r.leb()
                    val body = Sink()
                    body.leb(n + 1)
                    repeat(n.toInt()) {
                        r.copyValType(body)
                        body.u8(r.u8())
                        r.copyConstExpr(body, ::remap)
                    }
                    body.u8(I32); body.u8(1); body.u8(0x41); body.sleb(0); body.u8(0x0B)
                    emit(6, body)
                }
                7 -> {
                    val n = r.leb()
                    val body = Sink()
                    body.leb(n + 1)
                    repeat(n.toInt()) {
                        val ns = r.pos
                        r.name()
                        body.bytes(wasm, ns, r.pos - ns)
                        val kind = r.u8()
                        body.u8(kind)
                        val idx = r.leb()
                        body.leb(if (kind == 0) remap(idx) else idx)
                    }
                    body.name(WasmAsync.STATE_EXPORT); body.u8(3); body.leb(stateGlobal)
                    emit(7, body)
                }
                8 -> emit(8, Sink().also { it.leb(remap(r.leb())) })
                9 -> emit(9, rewriteElements(r))
                10 -> {
                    val n = r.leb().toInt()
                    val body = Sink()
                    body.leb(n)
                    for (i in 0 until n) {
                        val size = r.leb().toInt()
                        val start = r.pos
                        val end = start + size
                        val f = importedFuncs + i
                        checkpoint()
                        val fn = Sink()
                        if (f in suspendable) {
                            val stats = FunctionRewriter(f, bodies[i], start, end).rewrite(fn)
                            instrumented++
                            sites += stats.first
                            maxLocals = maxOf(maxLocals, stats.second)
                        } else {
                            copyPlainFunction(bodies[i], start, end, fn)
                        }
                        codeBefore += size
                        codeAfter += fn.size()
                        body.leb(fn.size())
                        body.append(fn)
                        r.pos = end
                    }
                    emit(10, body)
                }
                else -> emit(s.id, Sink().also { it.bytes(wasm, s.start, s.end - s.start) })
            }
        }
        return Asyncify.Result(out.toByteArray(), bodies.size, instrumented, sites, codeBefore, codeAfter, maxLocals)
    }

    private class Section(val id: Int, val start: Int, val end: Int)

    private fun scanSections(): List<Section> {
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

    private fun rewriteElements(r: Reader): Sink {
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
                repeat(m.toInt()) { r.copyConstExpr(body, ::remap) }
            }
            when (flag) {
                0 -> {
                    r.copyConstExpr(body, ::remap)
                    funcIdxVec()
                }
                1, 3 -> {
                    body.u8(r.u8())
                    funcIdxVec()
                }
                2 -> {
                    body.leb(r.leb())
                    r.copyConstExpr(body, ::remap)
                    body.u8(r.u8())
                    funcIdxVec()
                }
                4 -> {
                    r.copyConstExpr(body, ::remap)
                    exprVec()
                }
                5, 7 -> {
                    r.copyValType(body)
                    exprVec()
                }
                6 -> {
                    body.leb(r.leb())
                    r.copyConstExpr(body, ::remap)
                    r.copyValType(body)
                    exprVec()
                }
                else -> throw AsyncifyUnsupported("要素セグメントの形式 $flag には対応していない")
            }
        }
        return body
    }

    private fun insEnd(list: List<AnnotatedInstruction>, i: Int, bodyEnd: Int) = if (i + 1 < list.size) list[i + 1].address() else bodyEnd

    /** 変換しない関数: 関数の番号だけ付け替えて写す */
    private fun copyPlainFunction(list: List<AnnotatedInstruction>, start: Int, end: Int, out: Sink) {
        val first = list.first().address()
        out.bytes(wasm, start, first - start)
        for (i in list.indices) {
            val ins = list[i]
            when (ins.opcode()) {
                OpCode.CALL, OpCode.RETURN_CALL, OpCode.REF_FUNC -> {
                    out.u8(ins.opcode().opcode())
                    out.leb(remap(ins.operand(0)))
                }
                else -> out.bytes(wasm, ins.address(), insEnd(list, i, end) - ins.address())
            }
        }
    }

    // ---- 止まりうる関数の書き換え ----

    private inner class FunctionRewriter(
        private val func: Int,
        private val list: List<AnnotatedInstruction>,
        private val bodyStart: Int,
        private val bodyEnd: Int,
    ) {
        private val sig = funcSigs[func]
        private val localTypes = ArrayList<Int>()
        private val origLocals: Int
        private val newLocals = ArrayList<Int>()
        private val free = HashMap<Int, ArrayDeque<Int>>()
        private val endOf = java.util.IdentityHashMap<AnnotatedInstruction, Int>()
        private var siteCount = 0
        private val kLocal: Int

        init {
            localTypes += sig.params
            module.codeSection().getFunctionBody(func - importedFuncs).localTypes().forEach { localTypes += code(it) }
            origLocals = localTypes.size
            for (i in list.indices) endOf[list[i]] = insEnd(list, i, bodyEnd)
            if (localTypes.any { it == V128 }) throw AsyncifyUnsupported("v128 のローカルには対応していない")
            kLocal = newLocal(I32)
        }

        private fun newLocal(t: Int): Int {
            if (t == V128) throw AsyncifyUnsupported("v128 のローカルには対応していない")
            newLocals += t
            localTypes += t
            return localTypes.size - 1
        }

        private fun alloc(t: Int): Int = free[t]?.removeFirstOrNull() ?: newLocal(t)

        /** 共有しない一時ローカル（構造の結果） */
        private val dedicated = HashSet<Int>()

        private fun release(idx: Int) {
            if (idx in dedicated) return
            free.getOrPut(localTypes[idx]) { ArrayDeque() }.addLast(idx)
        }

        /** (呼び出しの数, 退避するローカルの数) */
        fun rewrite(out: Sink): Pair<Int, Int> {
            val (nodes, _) = buildTree(0)
            val body = Sink()
            emitSuspendBody(body, nodes, sig.results, 0)

            // ローカル変数の宣言
            val r = Reader(wasm, bodyStart, list.first().address())
            val groups = r.leb()
            val runs = ArrayList<Pair<Int, Int>>()
            for (t in newLocals) if (runs.isNotEmpty() && runs.last().second == t) runs[runs.size - 1] = runs.last().first + 1 to t else runs += 1 to t
            out.leb(groups + runs.size)
            out.bytes(wasm, r.pos, list.first().address() - r.pos)
            for ((n, t) in runs) {
                out.leb(n)
                out.u8(t)
            }

            // 入口: 巻き直し中ならローカルを戻す
            out.u8(0x23); out.leb(stateGlobal); out.u8(0x41); out.sleb(REWINDING.toLong()); out.u8(0x46)
            out.u8(0x04); out.u8(0x40)
            out.u8(0x10); out.leb(popFunc); out.u8(0xA7); out.u8(0x21); out.leb(kLocal)
            for (i in localTypes.indices.reversed()) {
                if (i == kLocal) continue
                val t = localTypes[i]
                if (isRef(t)) {
                    out.u8(0xD0); out.u8(t)
                } else {
                    out.u8(0x10); out.leb(popFunc)
                    when (t) {
                        I32 -> out.u8(0xA7)
                        F32 -> { out.u8(0xA7); out.u8(0xBE) }
                        F64 -> out.u8(0xBF)
                    }
                }
                out.u8(0x21); out.leb(i)
            }
            out.u8(0x0B)

            out.u8(0x02); out.u8(0x40) // $unwind
            out.u8(0x02); blockType(out, sig.results) // $body
            out.append(body)
            out.u8(0x0B)
            out.u8(0x0F) // return
            out.u8(0x0B)

            // 巻き戻し: ローカルと k を積んで、ダミーの値で戻る
            for (i in localTypes.indices) {
                if (i == kLocal) continue
                val t = localTypes[i]
                out.u8(0x20); out.leb(i)
                if (isRef(t)) {
                    out.u8(0xD1); out.u8(0x45); out.u8(0x04); out.u8(0x40); out.u8(0x00); out.u8(0x0B)
                    continue
                }
                when (t) {
                    I32 -> out.u8(0xAD)
                    F32 -> { out.u8(0xBC); out.u8(0xAD) }
                    F64 -> out.u8(0xBD)
                }
                out.u8(0x10); out.leb(pushFunc)
            }
            out.u8(0x20); out.leb(kLocal); out.u8(0xAD); out.u8(0x10); out.leb(pushFunc)
            dummies(out, sig.results)
            out.u8(0x0B)
            return siteCount to localTypes.size
        }

        private fun buildTree(from: Int): Pair<List<Node>, Int> {
            val nodes = ArrayList<Node>()
            var i = from
            while (true) {
                val ins = list[i]
                when (ins.opcode()) {
                    OpCode.END, OpCode.ELSE -> return nodes to i
                    OpCode.BLOCK, OpCode.LOOP, OpCode.IF, OpCode.TRY_TABLE -> {
                        val (body, stop) = buildTree(i + 1)
                        var els: List<Node>? = null
                        var j = stop
                        if (list[stop].opcode() == OpCode.ELSE) {
                            val (e, stop2) = buildTree(stop + 1)
                            els = e
                            j = stop2
                        }
                        nodes += Struct(ins, body, els)
                        i = j + 1
                    }
                    else -> {
                        nodes += Leaf(ins)
                        i++
                    }
                }
            }
        }

        // --- 判定 ---

        private fun isSuspendCall(ins: AnnotatedInstruction): Boolean = when (ins.opcode()) {
            OpCode.CALL -> ins.operand(0).toInt() in suspendable
            OpCode.CALL_INDIRECT, OpCode.CALL_REF -> indirectMaySuspend(ins.operand(0).toInt())
            OpCode.RETURN_CALL -> if (ins.operand(0).toInt() in suspendable) throw AsyncifyUnsupported("末尾呼び出しには対応していない") else false
            OpCode.RETURN_CALL_INDIRECT, OpCode.RETURN_CALL_REF ->
                if (indirectMaySuspend(ins.operand(0).toInt())) throw AsyncifyUnsupported("末尾呼び出しには対応していない") else false
            else -> false
        }

        private val suspendMemo = HashMap<Node, Boolean>()

        private fun hasSuspend(n: Node): Boolean = suspendMemo.getOrPut(n) {
            when (n) {
                is Leaf -> isSuspendCall(n.ins)
                is Struct -> n.body.any(::hasSuspend) || (n.els?.any(::hasSuspend) ?: false)
            }
        }

        // --- ブロックの型 ---

        /** (params, results, ブロック型のバイト列の終わり) */
        private fun blockSig(ins: AnnotatedInstruction): Triple<List<Int>, List<Int>, Int> {
            val r = Reader(wasm, ins.address() + 1, bodyEnd)
            val b = wasm[r.pos].toInt() and 0xFF
            return when {
                b == 0x40 -> {
                    r.pos++
                    Triple(emptyList(), emptyList(), r.pos)
                }
                b == 0x63 || b == 0x64 -> throw AsyncifyUnsupported("型付きの参照型には対応していない")
                b in 0x60..0x7F -> {
                    r.pos++
                    Triple(emptyList(), listOf(b), r.pos)
                }
                else -> {
                    val idx = r.sleb().toInt()
                    val s = typeSigs[idx]
                    Triple(s.params, s.results, r.pos)
                }
            }
        }

        private fun blockType(out: Sink, results: List<Int>) {
            when (results.size) {
                0 -> out.u8(0x40)
                1 -> out.u8(results[0])
                else -> throw AsyncifyUnsupported("複数の結果を持つブロックには対応していない")
            }
        }

        private fun dummies(out: Sink, types: List<Int>) {
            for (t in types) when (t) {
                I32 -> { out.u8(0x41); out.u8(0) }
                I64 -> { out.u8(0x42); out.u8(0) }
                F32 -> { out.u8(0x43); repeat(4) { out.u8(0) } }
                F64 -> { out.u8(0x44); repeat(8) { out.u8(0) } }
                else -> { out.u8(0xD0); out.u8(t) }
            }
        }

        // --- 型の追跡 ---

        private fun effect(ins: AnnotatedInstruction, st: TypeStack) {
            val op = ins.opcode().opcode()
            when (op) {
                0x00, 0x0C, 0x0E, 0x0F, 0x12, 0x13, 0x15 -> st.kill()
                0x01 -> Unit
                0x08 -> {
                    st.pop(tagSigs[ins.operand(0).toInt()].params.size)
                    st.kill()
                }
                0x0A -> {
                    st.pop()
                    st.kill()
                }
                0x0D -> st.pop()
                0x10 -> call(st, funcSigs[ins.operand(0).toInt()])
                0x11 -> {
                    st.pop()
                    call(st, typeSigs[ins.operand(0).toInt()])
                }
                0x14 -> {
                    st.pop()
                    call(st, typeSigs[ins.operand(0).toInt()])
                }
                0x1A -> st.pop()
                0x1B -> {
                    st.pop()
                    val a = st.pop()
                    val b = st.pop()
                    st.push(if (a == BOT) b else a)
                }
                0x1C -> {
                    st.pop(3)
                    st.push(wasm[ins.address() + 2].toInt() and 0xFF)
                }
                0x20 -> st.push(localTypes[ins.operand(0).toInt()])
                0x21 -> st.pop()
                0x22 -> {
                    st.pop()
                    st.push(localTypes[ins.operand(0).toInt()])
                }
                0x23 -> st.push(globalTypes[ins.operand(0).toInt()])
                0x24 -> st.pop()
                0x25 -> {
                    st.pop()
                    st.push(tableTypes[ins.operand(0).toInt()])
                }
                0x26 -> st.pop(2)
                in 0x28..0x35 -> {
                    st.pop()
                    st.push(
                        when (op) {
                            0x28, in 0x2C..0x2F -> I32
                            0x2A -> F32
                            0x2B -> F64
                            else -> I64
                        },
                    )
                }
                in 0x36..0x3E -> st.pop(2)
                0x3F -> st.push(I32)
                0x40 -> {
                    st.pop()
                    st.push(I32)
                }
                0x41 -> st.push(I32)
                0x42 -> st.push(I64)
                0x43 -> st.push(F32)
                0x44 -> st.push(F64)
                0x45, 0x50 -> un(st, I32)
                in 0x46..0x4F, in 0x51..0x66 -> bin(st, I32)
                in 0x67..0x69 -> un(st, I32)
                in 0x6A..0x78 -> bin(st, I32)
                in 0x79..0x7B -> un(st, I64)
                in 0x7C..0x8A -> bin(st, I64)
                in 0x8B..0x91 -> un(st, F32)
                in 0x92..0x98 -> bin(st, F32)
                in 0x99..0x9F -> un(st, F64)
                in 0xA0..0xA6 -> bin(st, F64)
                in 0xA7..0xAB, 0xBC -> un(st, I32)
                in 0xAC..0xB1, 0xBD -> un(st, I64)
                in 0xB2..0xB6, 0xBE -> un(st, F32)
                in 0xB7..0xBB, 0xBF -> un(st, F64)
                0xC0, 0xC1 -> un(st, I32)
                in 0xC2..0xC4 -> un(st, I64)
                0xD0 -> st.push(wasm[ins.address() + 1].toInt() and 0xFF)
                0xD1 -> un(st, I32)
                0xD2 -> st.push(0x70)
                0xD3 -> bin(st, I32)
                0xD4 -> {
                    val t = st.pop()
                    st.push(t)
                }
                in 0xFC00..0xFC03 -> un(st, I32)
                in 0xFC04..0xFC07 -> un(st, I64)
                0xFC08, 0xFC0A, 0xFC0B, 0xFC0C, 0xFC0E, 0xFC11 -> st.pop(3)
                0xFC09, 0xFC0D -> Unit
                0xFC0F -> {
                    st.pop(2)
                    st.push(I32)
                }
                0xFC10 -> st.push(I32)
                else -> throw AsyncifyUnsupported("止まりうる関数の中の命令 ${ins.opcode()} には対応していない")
            }
        }

        private fun call(st: TypeStack, s: Sig) {
            st.pop(s.params.size)
            s.results.forEach(st::push)
        }

        private fun un(st: TypeStack, r: Int) {
            st.pop()
            st.push(r)
        }

        private fun bin(st: TypeStack, r: Int) {
            st.pop(2)
            st.push(r)
        }

        private fun structEffect(n: Struct, st: TypeStack) {
            val (params, results, _) = blockSig(n.open)
            if (n.open.opcode() == OpCode.IF) st.pop()
            st.pop(params.size)
            results.forEach(st::push)
        }

        // --- 出力 ---

        /** 待ち点を含むブロックの中身。入るときスタックは空。[m] は $body の中で開いている待ち点の構造の数 */
        private fun emitSuspendBody(out: Sink, nodes: List<Node>, results: List<Int>, m: Int) {
            var st = TypeStack()
            var pending: List<Int> = emptyList()
            val run = ArrayList<Node>()
            for (node in nodes) {
                if (st.unreachable || !hasSuspend(node)) {
                    run += node
                    when (node) {
                        is Leaf -> effect(node.ins, st)
                        is Struct -> structEffect(node, st)
                    }
                    continue
                }
                val consumed: Int
                val callSig: Sig?
                when (node) {
                    is Leaf -> {
                        callSig = when (node.ins.opcode()) {
                            OpCode.CALL -> funcSigs[node.ins.operand(0).toInt()]
                            else -> typeSigs[node.ins.operand(0).toInt()]
                        }
                        consumed = callSig.params.size + if (node.ins.opcode() == OpCode.CALL) 0 else 1
                    }
                    is Struct -> {
                        callSig = null
                        val (params, _, _) = blockSig(node.open)
                        if (params.isNotEmpty()) throw AsyncifyUnsupported("引数を持つブロックには対応していない")
                        consumed = if (node.open.opcode() == OpCode.IF) 1 else 0
                    }
                }
                // 前のランを閉じる: スタックをすべて一時ローカルに移す
                val spill = st.types.map(::alloc)
                pending.forEach(::release)
                emitRun(out, pending, run, spill)
                run.clear()
                val keep = spill.subList(0, spill.size - consumed)
                val args = spill.subList(spill.size - consumed, spill.size)
                val resultTemps: List<Int>
                when (node) {
                    is Leaf -> {
                        resultTemps = emitCallSite(out, node.ins, args, callSig!!.results, m)
                    }
                    is Struct -> {
                        for (a in args) {
                            out.u8(0x20); out.leb(a)
                        }
                        val (_, res, btEnd) = blockSig(node.open)
                        // 見出し（try_table の catch のラベルは外側から数えるので、そのままでよい）
                        val headerEnd = if (node.open.opcode() == OpCode.TRY_TABLE) endOf[node.open]!! else btEnd
                        out.bytes(wasm, node.open.address(), headerEnd - node.open.address())
                        emitSuspendBody(out, node.body, res, m + 1)
                        if (node.els != null) {
                            out.u8(0x05)
                            emitSuspendBody(out, node.els, res, m + 1)
                        }
                        out.u8(0x0B)
                        args.forEach(::release)
                        // 結果は専用のローカルに（巻き直し中にも書かれるので、ほかと共有しない）
                        resultTemps = res.map { newLocal(it).also(dedicated::add) }
                        for (t in resultTemps.reversed()) {
                            out.u8(0x21); out.leb(t)
                        }
                    }
                }
                pending = keep + resultTemps
                st = TypeStack(pending.map { localTypes[it] })
            }
            // 最後のラン
            val hasCode = run.isNotEmpty()
            if (!hasCode) {
                for (p in pending) {
                    out.u8(0x20); out.leb(p)
                }
            } else {
                out.u8(0x23); out.leb(stateGlobal); out.u8(0x45)
                out.u8(0x04); blockType(out, results)
                for (p in pending) {
                    out.u8(0x20); out.leb(p)
                }
                for (n in run) emitPlain(out, n, 0)
                out.u8(0x05)
                dummies(out, results)
                out.u8(0x0B)
            }
            pending.forEach(::release)
        }

        /** ランを `if (state == NORMAL)` で包んで出す。[prefix] を積んでから始め、最後にスタックを [suffix] に移す */
        private fun emitRun(out: Sink, prefix: List<Int>, nodes: List<Node>, suffix: List<Int>) {
            if (prefix.isEmpty() && nodes.isEmpty() && suffix.isEmpty()) return
            out.u8(0x23); out.leb(stateGlobal); out.u8(0x45)
            out.u8(0x04); out.u8(0x40)
            for (p in prefix) {
                out.u8(0x20); out.leb(p)
            }
            for (n in nodes) emitPlain(out, n, 0)
            for (s in suffix.reversed()) {
                out.u8(0x21); out.leb(s)
            }
            out.u8(0x0B)
        }

        private fun emitCallSite(out: Sink, ins: AnnotatedInstruction, args: List<Int>, results: List<Int>, m: Int): List<Int> {
            val k = siteCount++
            // if (state == NORMAL || k == K)
            out.u8(0x23); out.leb(stateGlobal); out.u8(0x45)
            out.u8(0x20); out.leb(kLocal); out.u8(0x41); out.sleb(k.toLong()); out.u8(0x46)
            out.u8(0x72)
            out.u8(0x04); out.u8(0x40)
            for (a in args) {
                out.u8(0x20); out.leb(a)
            }
            if (ins.opcode() == OpCode.CALL) {
                out.u8(0x10); out.leb(remap(ins.operand(0)))
            } else {
                out.bytes(wasm, ins.address(), endOf[ins]!! - ins.address())
            }
            args.forEach(::release)
            // 巻き戻し中なら k = K として $unwind へ
            out.u8(0x23); out.leb(stateGlobal); out.u8(0x41); out.sleb(UNWINDING.toLong()); out.u8(0x46)
            out.u8(0x04); out.u8(0x40)
            out.u8(0x41); out.sleb(k.toLong()); out.u8(0x21); out.leb(kLocal)
            out.u8(0x0C); out.leb(m + 3)
            out.u8(0x0B)
            val temps = results.map(::alloc)
            for (t in temps.reversed()) {
                out.u8(0x21); out.leb(t)
            }
            out.u8(0x0B)
            return temps
        }

        /** 変えない命令を写す。[inner] はラン（`if` で包んだ）の中で開いている構造の数。それより外への分岐は 1 深くする */
        private fun emitPlain(out: Sink, node: Node, inner: Int) {
            fun label(l: Long) = if (l >= inner) l + 1 else l
            when (node) {
                is Leaf -> {
                    val ins = node.ins
                    val end = endOf[ins]!!
                    when (ins.opcode()) {
                        OpCode.BR, OpCode.BR_IF -> {
                            out.u8(ins.opcode().opcode())
                            out.leb(label(Reader(wasm, ins.address() + 1, end).leb()))
                        }
                        OpCode.BR_TABLE -> {
                            val r = Reader(wasm, ins.address() + 1, end)
                            val n = r.leb()
                            out.u8(0x0E)
                            out.leb(n)
                            repeat(n.toInt() + 1) { out.leb(label(r.leb())) }
                        }
                        OpCode.CALL, OpCode.RETURN_CALL, OpCode.REF_FUNC -> {
                            out.u8(ins.opcode().opcode())
                            out.leb(remap(ins.operand(0)))
                        }
                        OpCode.BR_ON_NULL, OpCode.BR_ON_NON_NULL, OpCode.BR_ON_CAST, OpCode.BR_ON_CAST_FAIL ->
                            throw AsyncifyUnsupported("${ins.opcode()} には対応していない")
                        else -> out.bytes(wasm, ins.address(), end - ins.address())
                    }
                }
                is Struct -> {
                    val (_, _, btEnd) = blockSig(node.open)
                    out.bytes(wasm, node.open.address(), btEnd - node.open.address())
                    if (node.open.opcode() == OpCode.TRY_TABLE) {
                        val r = Reader(wasm, btEnd, endOf[node.open]!!)
                        val n = r.leb()
                        out.leb(n)
                        repeat(n.toInt()) {
                            val kind = r.u8()
                            out.u8(kind)
                            if (kind == 0 || kind == 1) out.leb(r.leb())
                            out.leb(label(r.leb()))
                        }
                    }
                    for (c in node.body) emitPlain(out, c, inner + 1)
                    if (node.els != null) {
                        out.u8(0x05)
                        for (c in node.els) emitPlain(out, c, inner + 1)
                    }
                    out.u8(0x0B)
                }
            }
        }
    }
}

/** バイト列の書き出し（core の `FuelInjector` と同じもの。core のものは internal なので写した） */
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

    fun leb(value: Int) = leb(value.toLong())

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

/** バイト列の読み込み */
private class Reader(val buf: ByteArray, var pos: Int, val end: Int) {
    fun u8(): Int {
        if (pos >= end) throw AsyncifyUnsupported("セクションの終わりを超えて読んだ")
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
        }
    }

    fun sleb(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = u8()
            result = result or ((b and 0x7F).toLong() shl shift)
            shift += 7
            if (b and 0x80 == 0) {
                if (shift < 64 && b and 0x40 != 0) result = result or (-1L shl shift)
                return result
            }
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

    fun copyValType(out: Sink) {
        val start = pos
        val t = u8()
        if (t == 0x63 || t == 0x64) skipLeb()
        out.bytes(buf, start, pos - start)
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
                0xD0 -> {
                    skipLeb()
                    out.bytes(buf, start, pos - start)
                }
                0xD2 -> {
                    out.u8(op)
                    out.leb(remap(leb()))
                }
                0x6A, 0x6B, 0x6C, 0x7C, 0x7D, 0x7E -> out.u8(op)
                else -> throw AsyncifyUnsupported("定数式の命令 0x%02X には対応していない".format(op))
            }
        }
    }
}
