package pro.ikili.kibicomputers.core.kernel

import org.jetbrains.annotations.ApiStatus

/**
 * OS の既定の言語（#661、ADR 0661）。電源を入れたプレイヤーの言語を、init に渡す環境変数 `LANG` の値（POSIX の形）に直す。
 *
 * 値はクライアントが決める（改造したクライアントなら何でも送れる）うえ、ケースの NBT にも何でも書けるので、
 * 受け取るときは必ずここを通す。形の合わないものは捨てる（英語＝`LANG` を渡さない）。
 *
 * - 正規化（[fromMinecraft]）: 小文字にしてから、`xx_yy`（言語 2〜3 文字・地域 2〜3 文字の英数字）を `xx_YY.UTF-8`、
 *   `xx`（地域なし）を `xx.UTF-8` にする。それ以外は `null`
 * - 検査（[isValid]）: `^[a-z]{2,3}(_[A-Z0-9]{2,3})?\.UTF-8$` で、[MAX_LENGTH] 文字以下
 */
@ApiStatus.Internal
public object BootLanguage {
    /** 長さの上限（POSIX の形で） */
    public const val MAX_LENGTH: Int = 16

    /** 正規化の前に見る長さの上限（Minecraft の言語コード） */
    private const val MAX_RAW_LENGTH: Int = 8

    private val WITH_REGION = Regex("^([a-z]{2,3})_([a-z0-9]{2,3})$")
    private val LANGUAGE_ONLY = Regex("^[a-z]{2,3}$")
    private val POSIX = Regex("^[a-z]{2,3}(_[A-Z0-9]{2,3})?\\.UTF-8$")

    /**
     * Minecraft の言語コード（例: `ja_jp`）を POSIX の形（`ja_JP.UTF-8`）に直す。形が合わなければ `null`。
     * 大文字・小文字は区別しない（Locale に左右されない Kotlin の [lowercase]・[uppercase] を使う）
     */
    @JvmStatic
    public fun fromMinecraft(raw: String?): String? {
        if (raw == null || raw.isEmpty() || raw.length > MAX_RAW_LENGTH) return null
        val lower = raw.lowercase()
        val result = WITH_REGION.matchEntire(lower)?.let { m ->
            "${m.groupValues[1]}_${m.groupValues[2].uppercase()}.UTF-8"
        } ?: if (LANGUAGE_ONLY.matches(lower)) "$lower.UTF-8" else null
        return result?.takeIf { isValid(it) }
    }

    /** POSIX の形（[fromMinecraft] が返す形）として受け付けるか。`null` は受け付けない */
    @JvmStatic
    public fun isValid(posix: String?): Boolean =
        posix != null && posix.length <= MAX_LENGTH && POSIX.matches(posix)
}
