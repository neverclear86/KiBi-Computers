package pro.ikili.kibicomputers.core.runtime.endive

import pro.ikili.kibicomputers.core.runtime.InterpreterReason
import java.util.concurrent.atomic.AtomicLongArray

/**
 * WASM をコンパイルしきれず、関数（またはモジュール全体）をインタプリタで動かすことのログ（#680、ADR 0088 の「追記（#680）」）。
 *
 * コンパイルは中身の SHA-256 ごとに1回（`ProgramCache`）だが、プレイヤーはバイト列を少し変えて exec し直せば、何度でもコンパイルさせられる。
 * 1回ごとに WARNING を出すとログが埋まるので（ADR 0088 の「ログと数え口」、ADR 0044・0065 と同じ方針）:
 *
 * - 理由（[InterpreterReason]）ごとに、最初の1回だけ WARNING。2回目からは DEBUG に出す（何回目かを添える）
 * - 理由は決まった数しかないので、覚える数の上限は要らない（[InterpreterReason] の数だけ）
 * - JVM 全体で1つ（[EndiveRuntime] の companion が持つ）。サーバーの起動ごとには作り直さない（開発クライアントで統合サーバーを
 *   立て直しても、2回目のサーバーでは WARNING が出ない。DEBUG には出る）
 *
 * どのスレッドから呼んでもよい（コンパイル用のスレッド・MOD の初期化・テスト）。
 */
internal class CompileFallbackLog(private val log: System.Logger) {
    private val counts = AtomicLongArray(InterpreterReason.entries.size)

    /** [reason] でインタプリタに回した。[message] は、出すときだけ作る */
    fun report(reason: InterpreterReason, message: () -> String) {
        val n = counts.incrementAndGet(reason.ordinal)
        if (n == 1L) {
            log.log(System.Logger.Level.WARNING, message() + "。同じ理由の2回目からは DEBUG に出す")
        } else if (log.isLoggable(System.Logger.Level.DEBUG)) {
            log.log(System.Logger.Level.DEBUG, message() + "（同じ理由で $n 回目）")
        }
    }

    /**
     * [reason] で報告した回数（#931。運用者の数え口 `InterpreterCounts` が読む）。JVM 全体の累計で、reset しない（読む側が差を取る）。
     * 数えるのはコンパイルした回数で、プログラムの数ではない（燃料の計測の有無・止まりうる import が違う読み込み、キャッシュから外れた後の
     * 読み込み直し、復元のための読み込みは、同じプログラムでも別に数える。コンポーネントは中の core モジュールごとに数える）
     */
    fun count(reason: InterpreterReason): Long = counts.get(reason.ordinal)
}
