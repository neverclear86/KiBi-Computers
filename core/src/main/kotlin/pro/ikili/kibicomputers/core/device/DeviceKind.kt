package pro.ikili.kibicomputers.core.device

import org.jetbrains.annotations.ApiStatus

/**
 * デバイスの種類（#65、ADR 0065）。`<名前空間>:<名前>`。文字の規則は Minecraft の ResourceLocation と同じ
 * （名前空間は `[a-z0-9._-]+`、名前は `[a-z0-9/._-]+`）で、合わせて [MAX_BYTES] バイトまで。
 */
@ApiStatus.Internal
public object DeviceKind {
    /** 種類の最大のバイト数（ASCII だけなので文字数と同じ） */
    public const val MAX_BYTES: Int = 64

    private val FORM = Regex("[a-z0-9._-]+:[a-z0-9/._-]+")

    /** 画面（#355） */
    public const val SCREEN: String = "kibicomputers:screen"

    /** キーボード（#143） */
    public const val KEYBOARD: String = "kibicomputers:keyboard"

    /** スピーカー（ケースのビープ） */
    public const val SPEAKER: String = "kibicomputers:speaker"

    /** [text] が種類の形なら `true` */
    public fun isValid(text: String): Boolean = text.length <= MAX_BYTES && FORM.matches(text)
}
