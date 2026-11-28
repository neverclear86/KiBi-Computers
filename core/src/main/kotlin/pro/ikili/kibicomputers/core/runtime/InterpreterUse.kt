package pro.ikili.kibicomputers.core.runtime

import org.jetbrains.annotations.ApiStatus
import java.util.EnumSet

/**
 * WASM をコンパイルしきれず、関数（またはモジュール全体）をインタプリタで動かす理由（#680・#931、ADR 0088 の「追記（#680）」「追記（#931）」）。
 * 読み込みのログ（`CompileFallbackLog`）と、運用者の数え口（[InterpreterCounts]・`Machine.interpretedReasons`）で同じものを使う
 */
@ApiStatus.Internal
public enum class InterpreterReason {
    /** 関数の型（または struct のフィールド・array の要素。#891）に v128 があって、モジュール全体をインタプリタで動かす */
    V128_SIGNATURE,

    /** 例外処理を使うモジュールの関数が SIMD を使っていて、モジュール全体をインタプリタで動かす */
    SIMD_WITH_EXCEPTIONS,

    /** SIMD を使う関数だけをインタプリタで動かす（#424。SIMD を使うモジュールなら毎回起きる普通の経路） */
    SIMD_FUNCTIONS,

    /**
     * JVM のメソッドの上限を超える関数（とそこから末尾呼び出しで届く関数、SIMD を使う関数）をインタプリタで動かす（#446・#680）。
     * 性能の苦情を調べる手がかりなので、[SIMD_FUNCTIONS] とは分けて数える
     */
    TOO_LARGE_FUNCTIONS,

    /** コンパイルに失敗して、モジュール全体をインタプリタで動かす */
    WHOLE_MODULE,
    ;

    /** 理由の集合を1つの `Int` に詰めるときのビット */
    internal val bit: Int get() = 1 shl ordinal

    internal companion object {
        /** [bits]（[bit] の和）の理由の集合（宣言の順） */
        fun setOf(bits: Int): Set<InterpreterReason> {
            val out = EnumSet.noneOf(InterpreterReason::class.java)
            for (r in entries) if (bits and r.bit != 0) out += r
            return java.util.Collections.unmodifiableSet(out)
        }
    }
}

/**
 * 読み込んだモジュール（かコンポーネント）が、インタプリタで動くコードを持つこと（#931）。読み込みのとき（コンパイル用のスレッド）に1回だけ決め、
 * 後は変わらない。プロセスを作るたびに読む（`Machine` の数え口）ので、読むのは O(1)。
 *
 * コンパイルしない設定のランタイム（テスト用。`EndiveRuntime(compile = false)`）は、インタプリタに「回した」のではないので持たない（`null`）。
 *
 * @property wholeModules モジュール全体をインタプリタで動かす core モジュールの数（p1 のプログラムなら 0 か 1）
 * @property functions 一部の関数だけをインタプリタで動かす core モジュールの、インタプリタで動かす関数の数の合計。
 *   Endive が自動で回した分は、本体が残った関数（そこから末尾呼び出しで届く関数も含む）で数えるので、本当の数より多いことがある
 */
@ApiStatus.Internal
public class InterpreterUse internal constructor(
    /** 理由の集合（[InterpreterReason.bit] の和） */
    internal val reasonBits: Int,
    public val wholeModules: Int,
    public val functions: Int,
) {
    /** インタプリタに回した理由（宣言の順） */
    public val reasons: Set<InterpreterReason> get() = InterpreterReason.setOf(reasonBits)

    override fun toString(): String = "InterpreterUse(reasons=$reasons, wholeModules=$wholeModules, functions=$functions)"

    internal companion object {
        /** モジュール全体をインタプリタで動かす（[reason] で） */
        fun wholeModule(reason: InterpreterReason): InterpreterUse = InterpreterUse(reason.bit, 1, 0)

        /** [functions] 個の関数をインタプリタで動かす（[reason] で）。0 個なら `null` */
        fun functions(reason: InterpreterReason, functions: Int): InterpreterUse? =
            if (functions <= 0) null else InterpreterUse(reason.bit, 0, functions)

        /** 中の core モジュールの和（コンポーネント）。どれも持たなければ `null` */
        fun combine(uses: Iterable<InterpreterUse?>): InterpreterUse? {
            var bits = 0
            var whole = 0
            var functions = 0
            var any = false
            for (u in uses) {
                if (u == null) continue
                any = true
                bits = bits or u.reasonBits
                whole += u.wholeModules
                functions += u.functions
            }
            return if (any) InterpreterUse(bits, whole, functions) else null
        }
    }
}
