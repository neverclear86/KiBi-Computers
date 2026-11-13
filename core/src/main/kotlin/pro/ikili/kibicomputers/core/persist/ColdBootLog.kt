package pro.ikili.kibicomputers.core.persist

import org.jetbrains.annotations.ApiStatus

/**
 * 冷えた起動のログをどう出すかを、理由ごとに決める（#88、ADR 0088）。
 *
 * MOD を更新した直後は、電源の入っていた全台が [ColdBootCause.MOD_VERSION] で冷えた起動になる（1,000 台規模なら 1,000 回）。
 * そのため [ColdBootCause.MOD_VERSION] は、最初の1回だけ要約（[Kind.SUMMARY]）にし、2回目からは台の位置を DEBUG だけにする
 * （[Kind.QUIET]）。それ以外の理由は、今までどおり1台ごとに出す（[Kind.EACH]）。
 *
 * 状態は「要約をもう出したか」だけで、数え口もロガーも持たない。接続層がサーバーの起動ごとに作り直す（要約はサーバーの起動ごとに1回）。
 * メインスレッドだけで使う前提なので、同期しない
 */
@ApiStatus.Internal
public class ColdBootLog {
    /** ログの出し方 */
    public enum class Kind {
        /** 1台ごとに INFO */
        EACH,

        /** 要約を INFO で1回出し、台の位置は DEBUG */
        SUMMARY,

        /** 台の位置を DEBUG だけ */
        QUIET,
    }

    private var summarized = false

    /** 冷えた起動の理由 [cause]（分からなければ `null`）のログの出し方を決める */
    public fun classify(cause: ColdBootCause?): Kind {
        if (cause != ColdBootCause.MOD_VERSION) return Kind.EACH
        if (summarized) return Kind.QUIET
        summarized = true
        return Kind.SUMMARY
    }
}
