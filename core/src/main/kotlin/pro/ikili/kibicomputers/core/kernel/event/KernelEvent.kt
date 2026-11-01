package pro.ikili.kibicomputers.core.kernel.event

/**
 * イベントの値。保存できる値（数値・真偽・文字列・バイト列）だけ。WASM・WIT への写し方は #73。
 */
internal sealed interface EventValue {
    /** 64 ビット整数 */
    data class I64(val value: Long) : EventValue

    /** 64 ビット浮動小数 */
    data class F64(val value: Double) : EventValue

    /** 真偽 */
    data class Bool(val value: Boolean) : EventValue

    /** 文字列 */
    data class Text(val value: String) : EventValue

    /** バイト列。作るときに写し、外へ渡すときも写す（変更できない値として扱う）。等しさは中身で比べる */
    class Bytes(bytes: ByteArray) : EventValue {
        private val data: ByteArray = bytes.copyOf()

        /** バイト数 */
        val size: Int get() = data.size

        /** 中身の写し */
        fun toByteArray(): ByteArray = data.copyOf()

        override fun equals(other: Any?): Boolean = other is Bytes && data.contentEquals(other.data)

        override fun hashCode(): Int = data.contentHashCode()

        override fun toString(): String = "Bytes(size=$size)"
    }
}

/**
 * カーネルのイベント（変更できない値。保存の対象）。キューへの同報では、同じものを共有する。
 *
 * [type] は種類（例: タイマーなら #64 が決める名前）。[source] は出したもの（デバイスの識別子など。なければ ""）。
 * 大きさは [EventLimits] の範囲まで（種類は 1..`MAX_TYPE_BYTES`、出したものは `MAX_SOURCE_BYTES` まで、値は `MAX_VALUES` 個まで、
 * `Text` と `Bytes` の中身の合計は `MAX_PAYLOAD_BYTES` まで）。超えたら [IllegalArgumentException]。
 */
internal class KernelEvent(
    val type: String,
    val source: String = "",
    values: List<EventValue> = emptyList(),
) {
    /** 値の並び（作るときに写す） */
    val values: List<EventValue> = if (values.isEmpty()) emptyList() else values.toList()

    /**
     * マシン全体で溜める量に数える見積もり（バイト）。作るときに求めて持つ。
     *
     * `EVENT_OVERHEAD_BYTES` + 種類（`OBJECT_OVERHEAD_BYTES` + UTF-8 のバイト数）+ 出したもの（空でなければ同じく）
     * + 値ごとに `EVENT_VALUE_OVERHEAD_BYTES`（`Text`・`Bytes` はさらに `OBJECT_OVERHEAD_BYTES` + 中身のバイト数）
     */
    val footprint: Int

    init {
        val typeBytes = utf8Length(type)
        require(typeBytes in 1..EventLimits.MAX_TYPE_BYTES) { "イベントの種類は 1..${EventLimits.MAX_TYPE_BYTES} バイト（$typeBytes）" }
        val sourceBytes = utf8Length(source)
        require(sourceBytes <= EventLimits.MAX_SOURCE_BYTES) { "出したものは ${EventLimits.MAX_SOURCE_BYTES} バイトまで（$sourceBytes）" }
        require(this.values.size <= EventLimits.MAX_VALUES) { "値は ${EventLimits.MAX_VALUES} 個まで（${this.values.size}）" }
        var payload = 0
        var size = EventLimits.EVENT_OVERHEAD_BYTES + EventLimits.OBJECT_OVERHEAD_BYTES + typeBytes
        if (sourceBytes > 0) size += EventLimits.OBJECT_OVERHEAD_BYTES + sourceBytes
        for (v in this.values) {
            size += EventLimits.EVENT_VALUE_OVERHEAD_BYTES
            val n = when (v) {
                is EventValue.Text -> utf8Length(v.value)
                is EventValue.Bytes -> v.size
                else -> -1
            }
            if (n >= 0) {
                payload += n
                require(payload <= EventLimits.MAX_PAYLOAD_BYTES) { "文字列とバイト列の合計は ${EventLimits.MAX_PAYLOAD_BYTES} バイトまで" }
                size += EventLimits.OBJECT_OVERHEAD_BYTES + n
            }
        }
        footprint = size
    }

    override fun equals(other: Any?): Boolean =
        other is KernelEvent && type == other.type && source == other.source && values == other.values

    override fun hashCode(): Int = (type.hashCode() * 31 + source.hashCode()) * 31 + values.hashCode()

    override fun toString(): String = "KernelEvent(type=$type, source=$source, values=$values)"

    private companion object {
        /** UTF-8 にしたときのバイト数（文字列を写さずに数える。対になっていないサロゲートは `?` の 1 バイト） */
        fun utf8Length(s: String): Int {
            var n = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c.code < 0x80 -> n += 1
                    c.code < 0x800 -> n += 2
                    Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                        n += 4
                        i++
                    }
                    Character.isSurrogate(c) -> n += 1
                    else -> n += 3
                }
                i++
            }
            return n
        }
    }
}
