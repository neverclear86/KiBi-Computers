package pro.ikili.kibicomputers.core.config

import org.jetbrains.annotations.ApiStatus

/**
 * コンフィグの値（[configured]）と、その上にテスト（GameTest・負荷試験）が一時的に載せる上書きの2層を持つ値（#202、ADR 0202）。
 * メインスレッドだけで使う。
 *
 * - 読む値（[value]）は、上書きがあれば上書き、なければコンフィグの値。
 * - 上書きは [set] で載せる。コンフィグの値と同じ値を [set] したら、上書きは外れる。
 * - 上書きを載せたときのコンフィグの値（[base]）と同じ値を [set] しても外れる。テストの定型
 *   （`val c = value; set(c.copy(..)); …; set(c)`）の間にコンフィグが読み直されても、`c` に戻した時点で上書きが外れ、
 *   新しいコンフィグの値が効く。
 * - コンフィグが読み直されて [configure] で新しい値が来たとき、それが上書きと同じ値なら上書きは外れる。
 *
 * @param T 値の型。`equals` で同じ値を見分ける
 */
@ApiStatus.Internal
public class OverridableValue<T : Any>(initial: T) {
    /** コンフィグの値 */
    public var configured: T = initial
        private set

    private var override: T? = null

    /** 上書きを載せたときの [configured]。上書きがなければ null */
    private var base: T? = null

    /** 今の値。上書きがあれば上書き、なければ [configured] */
    public val value: T get() = override ?: configured

    /** 上書きが載っているか */
    public val isOverridden: Boolean get() = override != null

    /** 今の値を [next] にする（上書きとして。コンフィグの値か [base] と同じなら上書きを外す） */
    public fun set(next: T) {
        if (next == configured || next == base) {
            clearOverride()
            return
        }
        if (override == null) base = configured
        override = next
    }

    /** コンフィグの値を [next] にする。上書きと同じ値なら上書きを外す */
    public fun configure(next: T) {
        configured = next
        if (override == next) clearOverride()
    }

    /** 上書きを外す（新しいサーバーを開くとき） */
    public fun clearOverride() {
        override = null
        base = null
    }
}
