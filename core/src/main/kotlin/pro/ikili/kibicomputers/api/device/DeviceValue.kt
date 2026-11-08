package pro.ikili.kibicomputers.api.device

/**
 * デバイスの呼び出しの引数・結果、イベントの値（#65、ADR 0065）。入れ子のない6種のどれか（[Type]）。値は変わらない。
 *
 * WIT の `kibi:device/types` の `value`（`none`・`integer(s64)`・`float(f64)`・`boolean(bool)`・`text(string)`・
 * `bytes(list<u8>)`）と同じ。作るときは [of] か [none]、読むときは [type] を見てから `as*` で取り出す。
 *
 * ```java
 * switch (value.getType()) {
 *     case INTEGER: return value.asLong();
 *     case TEXT: return value.asText().length();
 *     default: return 0; // 種類は増えうるので、default を必ず書く
 * }
 * ```
 */
public sealed class DeviceValue {
    /** 値の種類。WIT の `value` の case と同じ名前。種類は増えうるので、`switch` には必ず `default` を書く */
    public enum class Type {
        /** 値なし（[none]）。イベントには載らない */
        NONE,

        /** 64 ビットの符号付き整数 */
        INTEGER,

        /** 64 ビットの浮動小数 */
        FLOAT,

        /** 真偽 */
        BOOLEAN,

        /** 文字列 */
        TEXT,

        /** バイト列 */
        BYTES,
    }

    /** 値の種類 */
    public abstract val type: Type

    /**
     * 整数の値
     *
     * @throws IllegalStateException [Type.INTEGER] でない
     */
    public fun asLong(): Long = (this as? IntegerValue)?.value ?: wrongType(Type.INTEGER)

    /**
     * 浮動小数の値（整数は受けない。整数も受けるときは [DeviceArguments.getDouble]）
     *
     * @throws IllegalStateException [Type.FLOAT] でない
     */
    public fun asDouble(): Double = (this as? FloatValue)?.value ?: wrongType(Type.FLOAT)

    /**
     * 真偽の値
     *
     * @throws IllegalStateException [Type.BOOLEAN] でない
     */
    public fun asBoolean(): Boolean = (this as? BooleanValue)?.value ?: wrongType(Type.BOOLEAN)

    /**
     * 文字列の値
     *
     * @throws IllegalStateException [Type.TEXT] でない
     */
    public fun asText(): String = (this as? TextValue)?.value ?: wrongType(Type.TEXT)

    /**
     * バイト列の値の写し（変えても値は変わらない）
     *
     * @throws IllegalStateException [Type.BYTES] でない
     */
    public fun asBytes(): ByteArray = (this as? BytesValue)?.data?.copyOf() ?: wrongType(Type.BYTES)

    /** バイト列の中身を写さずに渡す（内部用。変えない） */
    @JvmSynthetic
    internal fun bytesUnsafe(): ByteArray = (this as BytesValue).data

    private fun wrongType(expected: Type): Nothing = throw IllegalStateException("値の種類が $type で、$expected でない")

    private object NoneValue : DeviceValue() {
        override val type: Type get() = Type.NONE

        override fun toString(): String = "none"
    }

    private class IntegerValue(val value: Long) : DeviceValue() {
        override val type: Type get() = Type.INTEGER

        override fun equals(other: Any?): Boolean = other is IntegerValue && other.value == value

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = "integer($value)"
    }

    private class FloatValue(val value: Double) : DeviceValue() {
        override val type: Type get() = Type.FLOAT

        // Double の equals と同じ（NaN 同士は等しい、0.0 と -0.0 は違う）
        override fun equals(other: Any?): Boolean = other is FloatValue && other.value.equals(value)

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = "float($value)"
    }

    private class BooleanValue(val value: Boolean) : DeviceValue() {
        override val type: Type get() = Type.BOOLEAN

        override fun equals(other: Any?): Boolean = other is BooleanValue && other.value == value

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = "boolean($value)"
    }

    private class TextValue(val value: String) : DeviceValue() {
        override val type: Type get() = Type.TEXT

        override fun equals(other: Any?): Boolean = other is TextValue && other.value == value

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = "text(${value.length} chars)"
    }

    private class BytesValue(val data: ByteArray) : DeviceValue() {
        override val type: Type get() = Type.BYTES

        override fun equals(other: Any?): Boolean = other is BytesValue && other.data.contentEquals(data)

        override fun hashCode(): Int = data.contentHashCode()

        override fun toString(): String = "bytes(${data.size})"
    }

    public companion object {
        private val TRUE: DeviceValue = BooleanValue(true)
        private val FALSE: DeviceValue = BooleanValue(false)

        /** 値なし */
        @JvmStatic
        public fun none(): DeviceValue = NoneValue

        /** 整数の値 */
        @JvmStatic
        public fun of(value: Long): DeviceValue = IntegerValue(value)

        /** 浮動小数の値 */
        @JvmStatic
        public fun of(value: Double): DeviceValue = FloatValue(value)

        /** 真偽の値 */
        @JvmStatic
        public fun of(value: Boolean): DeviceValue = if (value) TRUE else FALSE

        /** 文字列の値 */
        @JvmStatic
        public fun of(value: String): DeviceValue = TextValue(value)

        /** バイト列の値。[value] は写して持つ（後で変えても値は変わらない） */
        @JvmStatic
        public fun of(value: ByteArray): DeviceValue = BytesValue(value.copyOf())
    }
}
