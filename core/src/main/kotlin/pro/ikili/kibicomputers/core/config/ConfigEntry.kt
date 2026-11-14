package pro.ikili.kibicomputers.core.config

import org.jetbrains.annotations.ApiStatus

/**
 * コンフィグのファイルの分類（#202、ADR 0202）。接続層はこれを各ローダーの `ModConfig.Type` に写す。
 *
 * NeoForge にしかない `STARTUP` は使わない（1.20.1 の Forge と食い違うため）。
 */
@ApiStatus.Internal
public enum class ConfigSide(
    /** ファイル名の分類の部分（`kibicomputers-<suffix>.toml`） */
    public val suffix: String,
) {
    /**
     * サーバーの設定。つないだクライアントにも送られる。置き場はローダーに従う: NeoForge 21.1 は `config/`
     * （ワールドの `serverconfig/` に同じ名前のファイルがあればそちらが優先）、Forge 1.20.1 はワールドの `serverconfig/`
     */
    SERVER("server"),

    /** クライアントだけの設定（見た目など）。`config/` に置かれる */
    CLIENT("client"),

    /** サーバー・クライアントの両方で読む設定。`config/` に置かれる */
    COMMON("common"),
    ;

    /** この分類のファイル名（両バージョンで同じ） */
    public val fileName: String get() = "kibicomputers-$suffix.toml"
}

/** 値を変えたときに、いつ効くか（ADR 0202）。 */
@ApiStatus.Internal
public enum class ConfigApply {
    /** ファイルを読み直したら、動いているサーバーにも効く */
    RELOAD,

    /** 次にワールド（サーバー）を開いたときに効く。スレッドの数など、作るときに決まる値 */
    WORLD_RESTART,
}

/**
 * コンフィグの1項目の定義（#202）。Minecraft に依存しない。接続層は [KiBiConfig.ENTRIES] を走査して、
 * 各ローダーの spec（`ModConfigSpec`・`ForgeConfigSpec`）に写す。項目ごとのコードを接続層に書かない。
 *
 * 値の型は [IntEntry]・[LongEntry]・[DoubleEntry]・[BooleanEntry] の4つ。
 *
 * @property side ファイルの分類
 * @property section 区画（TOML のテーブル。例: `scheduler`）
 * @property name 区画の中のキー（例: `maxConcurrentMachines`）
 * @property default 既定の値（仮の値。確定は #251）
 * @property apply いつ効くか
 * @property commentEn 説明（英語）。TOML のコメントと設定画面のツールチップに出す
 * @property commentJa 説明（日本語）
 */
@ApiStatus.Internal
public sealed class ConfigEntry<T : Any>(
    public val side: ConfigSide,
    public val section: String,
    public val name: String,
    public val default: T,
    public val apply: ConfigApply,
    public val commentEn: String,
    public val commentJa: String,
) {
    /** 区画とキーをつないだ名前（例: `scheduler.maxConcurrentMachines`）。分類の中で重ならない */
    public val path: String get() = "$section.$name"

    /** 設定画面の名前の lang のキー。説明は末尾に `.tooltip` を付けたキー */
    public val translationKey: String get() = "$TRANSLATION_PREFIX$section.$name"

    /**
     * ローダーから受け取った値を、この項目の型に直す。型が違うか範囲の外なら null（呼ぶ側は既定にして `rejected` に入れる）。
     *
     * 整数の項目は整数の型（`Byte`・`Short`・`Int`・`Long`）だけを受ける。小数（`2.9`・`NaN`）は切り捨てずに断る
     * （`NaN` を 0 にすると「自動」の番兵になってしまう）。小数の項目は数なら何でも受けるが、`NaN` は断る
     */
    public abstract fun coerce(value: Any): T?

    override fun toString(): String = "${side.suffix}:$path"

    public companion object {
        /** lang のキーの頭。区画の名前は `kibicomputers.configuration.<区画>` */
        public const val TRANSLATION_PREFIX: String = "kibicomputers.configuration."
    }
}

/**
 * 整数の項目。[auto] があれば、その値は「自動」（機械によって決まる既定。コア数やヒープの大きさ）を表す番兵で、
 * 解釈は core の組み立て（[KiBiConfig]）で行う。計算した数をファイルに書かないため（ADR 0202）。
 */
@ApiStatus.Internal
public class IntEntry internal constructor(
    side: ConfigSide,
    section: String,
    name: String,
    default: Int,
    public val min: Int,
    public val max: Int,
    apply: ConfigApply,
    commentEn: String,
    commentJa: String,
    public val auto: Int? = null,
) : ConfigEntry<Int>(side, section, name, default, apply, commentEn, commentJa) {
    init {
        require(min <= max) { "$name: min > max" }
        require(default in min..max) { "$name: default out of range" }
        require(auto == null || auto in min..max) { "$name: auto out of range" }
    }

    override fun coerce(value: Any): Int? {
        val n = integralOrNull(value) ?: return null
        return if (n in min.toLong()..max.toLong()) n.toInt() else null
    }
}

/** 64 ビットの整数の項目。 */
@ApiStatus.Internal
public class LongEntry internal constructor(
    side: ConfigSide,
    section: String,
    name: String,
    default: Long,
    public val min: Long,
    public val max: Long,
    apply: ConfigApply,
    commentEn: String,
    commentJa: String,
) : ConfigEntry<Long>(side, section, name, default, apply, commentEn, commentJa) {
    init {
        require(min <= max) { "$name: min > max" }
        require(default in min..max) { "$name: default out of range" }
    }

    override fun coerce(value: Any): Long? {
        val n = integralOrNull(value) ?: return null
        return if (n in min..max) n else null
    }
}

/** 小数の項目。 */
@ApiStatus.Internal
public class DoubleEntry internal constructor(
    side: ConfigSide,
    section: String,
    name: String,
    default: Double,
    public val min: Double,
    public val max: Double,
    apply: ConfigApply,
    commentEn: String,
    commentJa: String,
) : ConfigEntry<Double>(side, section, name, default, apply, commentEn, commentJa) {
    init {
        require(min <= max) { "$name: min > max" }
        require(default in min..max) { "$name: default out of range" }
    }

    override fun coerce(value: Any): Double? {
        val n = (value as? Number)?.toDouble() ?: return null
        return if (!n.isNaN() && n in min..max) n else null
    }
}

/** 真偽の項目。 */
@ApiStatus.Internal
public class BooleanEntry internal constructor(
    side: ConfigSide,
    section: String,
    name: String,
    default: Boolean,
    apply: ConfigApply,
    commentEn: String,
    commentJa: String,
) : ConfigEntry<Boolean>(side, section, name, default, apply, commentEn, commentJa) {
    override fun coerce(value: Any): Boolean? = value as? Boolean
}

/** 整数の型（`Byte`・`Short`・`Int`・`Long`）なら `Long` に直す。それ以外（小数・文字列など）は null */
private fun integralOrNull(value: Any): Long? = when (value) {
    is Int -> value.toLong()
    is Long -> value
    is Short -> value.toLong()
    is Byte -> value.toLong()
    else -> null
}
