package pro.ikili.kibicomputers.core.device

import org.jetbrains.annotations.ApiStatus
import java.util.UUID

/**
 * デバイスの address（#65、ADR 0065）。小文字・ハイフンありの UUID の文字列（36 文字）。
 * デバイスを作ったときに決め、持ち主（アイテムのデータ・ブロックエンティティの NBT）が保存する。
 */
@ApiStatus.Internal
public object DeviceAddress {
    /** address の長さ */
    public const val LENGTH: Int = 36

    private val FORM = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /** [text] が address の形（小文字の UUID）なら `true`。大文字は受けない */
    public fun isValid(text: String): Boolean = text.length == LENGTH && FORM.matches(text)

    /**
     * [text] を address として確かめて返す
     *
     * @throws IllegalArgumentException 形が違う
     */
    public fun parse(text: String): String {
        require(isValid(text)) { "デバイスの address は小文字の UUID（36 文字）: $text" }
        return text
    }

    /** 新しい address（乱数の UUID） */
    public fun random(): String = UUID.randomUUID().toString()
}
