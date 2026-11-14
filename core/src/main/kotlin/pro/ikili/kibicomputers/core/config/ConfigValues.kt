package pro.ikili.kibicomputers.core.config

import org.jetbrains.annotations.ApiStatus
import java.util.IdentityHashMap

/**
 * ローダーの設定から、項目ごとの値を取り出す口（#202）。接続層が spec の値を読んで実装する。
 *
 * 値が書かれていない・読めないなら null を返す（既定を使う）。
 */
@ApiStatus.Internal
public fun interface ConfigSource {
    /** [entry] の値。なければ null */
    public fun raw(entry: ConfigEntry<*>): Any?
}

/**
 * コンフィグの値の、変わらない写し（#202、ADR 0202）。読み直しはこの写しを丸ごと差し替える（[ConfigState]）ので、
 * 読む側は項目の途中の組み合わせを見ない。
 *
 * 値の決め方は [resolve] の1か所にまとめる。プリセット（#203）は、そこに「明示した上書き ?: プリセット ?: 既定」を差し込む。
 */
@ApiStatus.Internal
public class ConfigValues private constructor(
    private val values: Map<ConfigEntry<*>, Any>,
    /** [resolve] で既定に戻した項目（範囲の外や型の違う値だった）。ログに出すため */
    public val rejected: List<ConfigEntry<*>>,
) {
    /** [entry] の値。定義にない項目なら既定 */
    @Suppress("UNCHECKED_CAST")
    public operator fun <T : Any> get(entry: ConfigEntry<T>): T = (values[entry] as T?) ?: entry.default

    /** [other] と値が違う項目（[entries] の順） */
    public fun changedFrom(other: ConfigValues, entries: List<ConfigEntry<*>> = KiBiConfig.ENTRIES): List<ConfigEntry<*>> =
        entries.filter { get(it) != other.get(it) }

    override fun equals(other: Any?): Boolean =
        other is ConfigValues && KiBiConfig.ENTRIES.all { get(it) == other.get(it) }

    override fun hashCode(): Int = KiBiConfig.ENTRIES.fold(0) { h, e -> 31 * h + get(e).hashCode() }

    public companion object {
        /** すべて既定の値 */
        @JvmField
        public val DEFAULTS: ConfigValues = ConfigValues(emptyMap(), emptyList())

        /**
         * [source] から [entries] の値を決める。値がなければ既定、型か範囲が合わなければ既定にして [rejected] に入れる。
         */
        @JvmStatic
        public fun resolve(source: ConfigSource, entries: List<ConfigEntry<*>> = KiBiConfig.ENTRIES): ConfigValues {
            val map = IdentityHashMap<ConfigEntry<*>, Any>()
            val rejected = ArrayList<ConfigEntry<*>>()
            for (entry in entries) {
                val raw = source.raw(entry) ?: continue
                val value = entry.coerce(raw)
                if (value == null) rejected += entry else map[entry] = value
            }
            return ConfigValues(map, rejected)
        }
    }
}

/**
 * 1つの分類の、今のコンフィグの値（#202）。読み直しはファイルを見張るスレッドから来るので、[values] は
 * `@Volatile` の参照1つを差し替える。読む側（メインスレッド）は1回読んだ写しを使う。
 */
@ApiStatus.Internal
public class ConfigState(initial: ConfigValues = ConfigValues.DEFAULTS) {
    /** 今の値 */
    @Volatile
    public var values: ConfigValues = initial
        private set

    /**
     * 今の値を [next] にする。値が変わった項目（定義の順）を返す。何も変わらなければ参照を差し替えない
     * （読む側は参照の違いで変化を見つける）
     */
    public fun update(next: ConfigValues): List<ConfigEntry<*>> {
        val changed = next.changedFrom(values)
        if (changed.isNotEmpty()) values = next
        return changed
    }
}
