package pro.ikili.kibicomputers.core.runtime.component.canon

import pro.ikili.kibicomputers.core.runtime.WasmMemoryAccessException
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonContext.Companion.u32

/**
 * 同期の関数 1 つの、lift・lower の計画（#264。ADR 0264）。関数の型（引数・結果）から、平らにした形と、ポインタで
 * 渡すかどうかを 1 回だけ決めて持つ。不変なので、コンパイル用のスレッドで作り、実行のスレッドで共有してよい。
 *
 * 向きは 2 つある（仕様の `flatten_functype`）:
 * - canon lower（ゲストが import を呼ぶ）: [liftParams] で引数を読み、ホストの処理の後に [lowerResult] で結果を返す。
 *   引数が [MAX_FLAT_PARAMS] 個を超えるならゲストが引数の場所を 1 つ渡し、結果が [MAX_FLAT_RESULTS] 個を超えるなら
 *   ゲストが最後の引数に書き先を渡す（[lowerSignature]）
 * - canon lift（ホストがゲストの export を呼ぶ）: [lowerParams] で引数を渡し、[liftResult] で結果を読む。
 *   引数が多いならホストが realloc で場所を取って書き、結果が多いならゲストが結果の場所を返す（[liftSignature]）。
 *   結果は post-return を呼ぶ前に読みきる（[liftResult] はメモリへの参照を残さない）
 *
 * 呼び出しの順（全引数を lift してからホストの検査をする。#616）、書き先の確かめはホストの処理の後（ADR 0306）、
 * post-return と may_leave は、呼ぶ側（#263 のリンカー）が守る
 */
internal class CanonFunction(val params: List<CanonType>, val result: CanonType?) {
    /** 引数を並べた tuple（引数をポインタで渡すときの配置） */
    private val paramTuple = CanonType.Tuple(params)

    /** 引数の平らにした形。[MAX_FLAT_PARAMS] 個を超えるなら `null`（ポインタで渡す） */
    val flatParams: List<CoreFlat>? = paramTuple.flat

    /** 引数をポインタで渡すか */
    val paramsByPointer: Boolean get() = flatParams == null

    /** 結果をポインタで渡すか（平らにして 1 個を超える） */
    val resultByPointer: Boolean = result != null && (result.flat?.size ?: Int.MAX_VALUE) > MAX_FLAT_RESULTS

    init {
        val depth = maxOf(paramTuple.depth, result?.depth ?: 0)
        require(depth <= CanonLimits.MAX_TYPE_DEPTH + 1) { "型が深すぎる（$depth）" }
    }

    /** core の関数の型（引数と結果の平らにした型） */
    class CoreSignature(val params: List<CoreFlat>, val results: List<CoreFlat>) {
        override fun equals(other: Any?): Boolean = other is CoreSignature && other.params == params && other.results == results
        override fun hashCode(): Int = params.hashCode() * 31 + results.hashCode()
        override fun toString(): String = "$params -> $results"
    }

    /** canon lower で作る core の関数の型 */
    val lowerSignature: CoreSignature = run {
        val ps = ArrayList(flatParams ?: POINTER)
        val rs: List<CoreFlat>
        if (resultByPointer) {
            ps += CoreFlat.I32
            rs = emptyList()
        } else {
            rs = result?.flat ?: emptyList()
        }
        CoreSignature(ps, rs)
    }

    /** canon lift で呼ぶ core の関数の型 */
    val liftSignature: CoreSignature =
        CoreSignature(flatParams ?: POINTER, if (resultByPointer) POINTER else result?.flat ?: emptyList())

    // ---- canon lower（ゲスト → ホスト） ----

    /** ゲストが import を呼んだ core の引数 [args]（[lowerSignature] の形）から、引数をすべて lift する */
    @Throws(WasmTrapException::class)
    fun liftParams(cx: CanonContext, args: LongArray): List<CanonValue> = guarded {
        require(args.size == lowerSignature.params.size) { "core の引数の数が違う（${args.size}。$lowerSignature）" }
        if (flatParams == null) {
            val ptr = u32(args[0].toInt())
            cx.checkAligned(ptr, paramTuple.align, "引数")
            cx.checkInMemory(ptr, paramTuple.size.toLong(), "引数")
            List(params.size) { i -> CanonAbi.load(cx, ptr + paramTuple.offsets[i], params[i]) }
        } else {
            val r = FlatReader(args)
            List(params.size) { i -> CanonAbi.liftFlat(cx, r, params[i]) }
        }
    }

    /**
     * ホストの結果 [value] を lower し、core の結果を返す。結果をポインタで渡す形なら、[args]（ゲストの core の引数）の
     * 最後の書き先に書く（書き先の align と範囲は、ここで確かめる。ホストの処理が済んだ後）
     */
    @Throws(WasmTrapException::class)
    fun lowerResult(cx: CanonContext, value: CanonValue?, args: LongArray): LongArray = guarded {
        require(args.size == lowerSignature.params.size) { "core の引数の数が違う（${args.size}。$lowerSignature）" }
        if (result == null) {
            require(value == null) { "結果のない関数に値を返した" }
            return@guarded EMPTY
        }
        requireNotNull(value) { "結果の値がない（$result）" }
        if (resultByPointer) {
            val ptr = u32(args[args.size - 1].toInt())
            cx.checkAligned(ptr, result.align, "結果の書き先")
            cx.checkInMemory(ptr, result.size.toLong(), "結果の書き先")
            CanonAbi.store(cx, value, result, ptr)
            EMPTY
        } else {
            FlatWriter(1).also { CanonAbi.lowerFlat(cx, value, result, it) }.toArray()
        }
    }

    // ---- canon lift（ホスト → ゲスト） ----

    /** ゲストの export に渡す core の引数（[liftSignature] の形）を作る */
    @Throws(WasmTrapException::class)
    fun lowerParams(cx: CanonContext, values: List<CanonValue>): LongArray = guarded {
        require(values.size == params.size) { "引数の数が違う（${values.size} / ${params.size}）" }
        if (flatParams == null) {
            val ptr = cx.allocate(paramTuple.align, paramTuple.size.toLong())
            for (i in params.indices) CanonAbi.store(cx, values[i], params[i], ptr + paramTuple.offsets[i])
            longArrayOf(ptr)
        } else {
            val w = FlatWriter(flatParams.size)
            for (i in params.indices) CanonAbi.lowerFlat(cx, values[i], params[i], w)
            w.toArray()
        }
    }

    /** ゲストの export が返した core の結果 [results] から、結果を lift する（post-return の前に呼ぶ） */
    @Throws(WasmTrapException::class)
    fun liftResult(cx: CanonContext, results: LongArray): CanonValue? = guarded {
        require(results.size == liftSignature.results.size) { "core の結果の数が違う（${results.size}。$liftSignature）" }
        when {
            result == null -> null
            resultByPointer -> {
                val ptr = u32(results[0].toInt())
                cx.checkAligned(ptr, result.align, "結果")
                cx.checkInMemory(ptr, result.size.toLong(), "結果")
                CanonAbi.load(cx, ptr, result)
            }
            else -> CanonAbi.liftFlat(cx, FlatReader(results), result)
        }
    }

    /**
     * 範囲を確かめた後のメモリの読み書きは落ちないはずだが、落ちたら（下のメモリの実装の誤りなど）トラップにする
     * （ゲストの入力で、トラップ以外の例外を外に出さない）
     */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: WasmMemoryAccessException) {
        CanonTraps.outOfBounds("線形メモリの範囲の外: ${e.message}")
    }

    companion object {
        /** 同期の関数で、平らにして渡す引数の数の上限 */
        const val MAX_FLAT_PARAMS: Int = CanonType.MAX_FLAT

        /** 同期の関数で、平らにして返す結果の数の上限 */
        const val MAX_FLAT_RESULTS: Int = 1

        private val POINTER = listOf(CoreFlat.I32)
        private val EMPTY = LongArray(0)
    }
}
