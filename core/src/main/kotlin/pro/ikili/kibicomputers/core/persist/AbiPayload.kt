package pro.ikili.kibicomputers.core.persist

/**
 * 符号化を後でする ABI 層の写し（#84、ADR 0084）。取り出し（カーネルロックの中）では元と何も共有しない写しだけを取り、
 * バイト列にする（区画の圧縮を含む）のは書き込みのスレッドで [encode] を呼んだとき。
 */
internal interface AbiPayload {
    /** 書き込み待ちの間に持つ大きさの見積もり（バイト） */
    val estimatedBytes: Long

    /** `PROCESS_ABI` 節の層の中身（pid・層の ID・版の後ろ）にする。どのスレッドから呼んでもよい */
    fun encode(): ByteArray

    companion object {
        /** もうバイト列になっている中身 */
        fun encoded(bytes: ByteArray): AbiPayload = object : AbiPayload {
            override val estimatedBytes: Long get() = bytes.size.toLong()

            override fun encode(): ByteArray = bytes
        }
    }
}
