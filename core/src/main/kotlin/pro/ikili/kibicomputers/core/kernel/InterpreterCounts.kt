package pro.ikili.kibicomputers.core.kernel

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.core.runtime.InterpreterReason
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime

/**
 * インタプリタに回したことの数え口の写し（#931、ADR 0088 の「追記（#931）」）。理由（[InterpreterReason]）ごとに2つ持つ。
 *
 * - [compiles]: 読み込みでインタプリタに回した回数（`CompileFallbackLog`。JVM 全体の累計）。コンパイルした回数で、プログラムの数ではない
 *   （燃料の計測の有無・止まりうる import が違う読み込み、`ProgramCache` から外れた後の読み込み直し、復元のための読み込みは、同じプログラムでも
 *   別に数える。コンポーネントは中の core モジュールごとに数える）
 * - [processes]: インタプリタで動くコードを持つプログラムでプロセスを作った回数（[MachineScheduler.interpretedProcessCount]。そのスケジューラの累計。
 *   exec と保存からの復元。コンパイルは台をまたいで共有されるので、どの台が動かしたかはこちらで数える）
 *
 * どちらも reset しない累計なので、区間の増分は [minus] で取る（負荷試験の段階ごとの出力）。値は1つずつ読むので、動いている間に読むと互いに少しずれる。
 */
@ApiStatus.Internal
public class InterpreterCounts private constructor(
    private val compileCounts: LongArray,
    private val processCounts: LongArray,
) {
    /** [reason] でインタプリタに回したコンパイルの回数 */
    public fun compiles(reason: InterpreterReason): Long = compileCounts[reason.ordinal]

    /** [reason] でインタプリタに回したコードを持つプログラムで、プロセスを作った回数 */
    public fun processes(reason: InterpreterReason): Long = processCounts[reason.ordinal]

    /** どの理由も 0 */
    public val isZero: Boolean get() = compileCounts.all { it == 0L } && processCounts.all { it == 0L }

    /** 区間の増分（`this` が後、[other] が前） */
    public operator fun minus(other: InterpreterCounts): InterpreterCounts = InterpreterCounts(
        LongArray(compileCounts.size) { compileCounts[it] - other.compileCounts[it] },
        LongArray(processCounts.size) { processCounts[it] - other.processCounts[it] },
    )

    /** コンパイルの回数の要約（0 でない理由だけ。どれも 0 なら `なし`）。例: `TOO_LARGE_FUNCTIONS 2, SIMD_FUNCTIONS 1` */
    public fun compileSummary(): String = summary(compileCounts)

    /** プロセスの数の要約（[compileSummary] と同じ書き方） */
    public fun processSummary(): String = summary(processCounts)

    override fun toString(): String = "インタプリタに回したコンパイル: ${compileSummary()} / インタプリタのコードを持つプロセス: ${processSummary()}"

    override fun equals(other: Any?): Boolean =
        other is InterpreterCounts && compileCounts.contentEquals(other.compileCounts) && processCounts.contentEquals(other.processCounts)

    override fun hashCode(): Int = compileCounts.contentHashCode() * 31 + processCounts.contentHashCode()

    public companion object {
        /** どの理由も 0 */
        @JvmField
        public val ZERO: InterpreterCounts = InterpreterCounts(LongArray(InterpreterReason.entries.size), LongArray(InterpreterReason.entries.size))

        /** 今の値。[scheduler] が `null`（サーバーが動いていない）なら、プロセスの数は 0 */
        @JvmStatic
        public fun current(scheduler: MachineScheduler?): InterpreterCounts {
            val reasons = InterpreterReason.entries
            return InterpreterCounts(
                LongArray(reasons.size) { EndiveRuntime.FALLBACK_LOG.count(reasons[it]) },
                LongArray(reasons.size) { scheduler?.interpretedProcessCount(reasons[it]) ?: 0L },
            )
        }

        /** 理由の並び（宣言の順。空なら `なし`）。運用者の口で、台ごとの理由を書くのに使う */
        @JvmStatic
        public fun formatReasons(reasons: Set<InterpreterReason>): String =
            if (reasons.isEmpty()) "なし" else reasons.sortedBy { it.ordinal }.joinToString(", ")

        private fun summary(counts: LongArray): String {
            val parts = InterpreterReason.entries.filter { counts[it.ordinal] != 0L }.map { "${it.name} ${counts[it.ordinal]}" }
            return if (parts.isEmpty()) "なし" else parts.joinToString(", ")
        }
    }
}

/**
 * 運用者の口（接続層のコマンド。#931）で、インタプリタで動くコードを持つプログラムを多く動かした台を選ぶ。
 * コマンドを打ったときに1回だけ全台をなめる（tick ごとには呼ばない）。出力を台数で埋めないよう、上位 [limit] 台と、残りの台数だけにする
 */
@ApiStatus.Internal
public object InterpreterRanking {
    /** 上位に並べる台の数の既定（仮の値） */
    public const val DEFAULT_LIMIT: Int = 10

    /**
     * 選んだ結果。[top] は回数（[Ranked.count]）の多い順（同じなら [items] の順）、[others] は [top] に入らなかった、1回以上数えた台の数。
     * [machines] は1回以上数えた台の数
     */
    public class Result<T> internal constructor(public val top: List<Ranked<T>>, public val others: Int) {
        public val machines: Int get() = top.size + others
    }

    /** 選んだ台 [item] と、その回数 [count] */
    public class Ranked<T> internal constructor(public val item: T, public val count: Long)

    /** [items] から、[countOf] が 1 以上のものを、多い順に [limit] 個選ぶ */
    public fun <T> top(items: Iterable<T>, limit: Int = DEFAULT_LIMIT, countOf: (T) -> Long): Result<T> {
        require(limit >= 0) { "limit は 0 以上" }
        val counted = ArrayList<Ranked<T>>()
        for (item in items) {
            val n = countOf(item)
            if (n > 0) counted += Ranked(item, n)
        }
        // 安定なソートなので、同じ回数は items の順のまま
        counted.sortByDescending { it.count }
        val top = counted.take(limit)
        return Result(top, counted.size - top.size)
    }
}
