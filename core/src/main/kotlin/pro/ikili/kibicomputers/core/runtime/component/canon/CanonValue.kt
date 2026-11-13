package pro.ikili.kibicomputers.core.runtime.component.canon

/**
 * Canonical ABI で受け渡す値（#264）。型（[CanonType]）と組にして使う。
 *
 * - `s8`・`u8`・`s16`・`u16`・`s32`・`u32` は [I32]。lift した値は型の範囲に収めてある（`u8` は 0..255、`s8` は -128..127、
 *   `u32` は 32 ビットをそのまま `Int` のビット列として持つ）。lower するとき、範囲の外の値はホストの誤り（[IllegalArgumentException]）
 * - `s64`・`u64` は [I64]（`u64` はビット列のまま）
 * - `list<u8>` は [Bytes]（要素ごとにオブジェクトを作らない）、ほかの `list` は [Items]
 * - record・tuple は [Fields]（宣言の順）
 * - variant・enum・option・result は [Case]（場合の番号と中身。option は none が 0・some が 1、result は ok が 0・error が 1）
 * - flags は [Flags]（宣言の順に下位のビットから）
 * - own・borrow は [Handle]（[CanonResources] が lift・lower で変換した値）
 *
 * ホストの実装は、番号を手で数えずに型の名前で作る・読む: record は [CanonType.Record.value]・[CanonType.Record.field]、
 * variant・enum は `case`・`caseName`、flags は [CanonType.Flags.value]・[CanonType.Flags.has]・[CanonType.Flags.namesOf]。
 * option・result・文字列は下の [some]・[ok]・[err]・[str]
 */
internal sealed class CanonValue {
    class Bool(val value: Boolean) : CanonValue() {
        override fun equals(other: Any?): Boolean = other is Bool && other.value == value
        override fun hashCode(): Int = value.hashCode()
        override fun toString(): String = value.toString()
    }

    class I32(val value: Int) : CanonValue() {
        override fun equals(other: Any?): Boolean = other is I32 && other.value == value
        override fun hashCode(): Int = value
        override fun toString(): String = value.toString()
    }

    class I64(val value: Long) : CanonValue() {
        override fun equals(other: Any?): Boolean = other is I64 && other.value == value
        override fun hashCode(): Int = value.hashCode()
        override fun toString(): String = value.toString()
    }

    /** `f32`。比べるときはビット列で比べる（NaN どうしも同じビットなら等しい） */
    class F32(val value: Float) : CanonValue() {
        override fun equals(other: Any?): Boolean =
            other is F32 && java.lang.Float.floatToRawIntBits(other.value) == java.lang.Float.floatToRawIntBits(value)
        override fun hashCode(): Int = java.lang.Float.floatToRawIntBits(value)
        override fun toString(): String = value.toString()
    }

    /** `f64`。比べるときはビット列で比べる */
    class F64(val value: Double) : CanonValue() {
        override fun equals(other: Any?): Boolean =
            other is F64 && java.lang.Double.doubleToRawLongBits(other.value) == java.lang.Double.doubleToRawLongBits(value)
        override fun hashCode(): Int = java.lang.Double.doubleToRawLongBits(value).hashCode()
        override fun toString(): String = value.toString()
    }

    /** `char`（Unicode のスカラー値。サロゲートと 0x110000 以上は持たない） */
    class Char(val codePoint: Int) : CanonValue() {
        init {
            require(codePoint in 0..0x10ffff && codePoint !in 0xd800..0xdfff) { "Unicode のスカラー値ではない: $codePoint" }
        }

        override fun equals(other: Any?): Boolean = other is Char && other.codePoint == codePoint
        override fun hashCode(): Int = codePoint
        override fun toString(): String = "'${String(Character.toChars(codePoint))}'"
    }

    /**
     * `string`。[value] はスカラー値だけの文字列（対になっていないサロゲートは持たない）。
     *
     * [encoding] と [taggedCodeUnits] は、lift したときの元の符号化と長さ（Canonical ABI の `String` の組）。lower で
     * `cabi_realloc` を呼ぶ大きさは仕様どおりこれで決まる。ホストが作る文字列（[of]）は、UTF-8 で長さがその
     * バイト数のものとして扱う
     */
    class Str internal constructor(
        val value: String,
        val encoding: CanonStringEncoding,
        val taggedCodeUnits: Long,
    ) : CanonValue() {
        override fun equals(other: Any?): Boolean = other is Str && other.value == value
        override fun hashCode(): Int = value.hashCode()
        override fun toString(): String = "\"$value\""

        companion object {
            /** ホストの文字列。対になっていないサロゲートは U+FFFD にする（`WasiUtf8.encode` と同じ） */
            fun of(s: String): Str {
                val v = CanonStrings.toScalarValues(s)
                return Str(v, CanonStringEncoding.UTF8, CanonStrings.utf8Length(v))
            }
        }
    }

    /** `list<u8>`。中身は呼ぶ側が書き換えない */
    class Bytes(val bytes: ByteArray) : CanonValue() {
        override fun equals(other: Any?): Boolean = other is Bytes && other.bytes.contentEquals(bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
        override fun toString(): String = "bytes(${bytes.size})"
    }

    /** `list<T>`（`u8` 以外） */
    class Items(val items: List<CanonValue>) : CanonValue() {
        override fun equals(other: Any?): Boolean = other is Items && other.items == items
        override fun hashCode(): Int = items.hashCode()
        override fun toString(): String = items.toString()
    }

    /** record・tuple */
    class Fields(val fields: List<CanonValue>) : CanonValue() {
        override fun equals(other: Any?): Boolean = other is Fields && other.fields == fields
        override fun hashCode(): Int = fields.hashCode()
        override fun toString(): String = fields.joinToString(", ", "{", "}")
    }

    /** variant・enum・option・result */
    class Case(val index: Int, val payload: CanonValue?) : CanonValue() {
        override fun equals(other: Any?): Boolean = other is Case && other.index == index && other.payload == payload
        override fun hashCode(): Int = index * 31 + (payload?.hashCode() ?: 0)
        override fun toString(): String = if (payload == null) "#$index" else "#$index($payload)"
    }

    /** flags */
    class Flags(val bits: Int) : CanonValue() {
        override fun equals(other: Any?): Boolean = other is Flags && other.bits == bits
        override fun hashCode(): Int = bits
        override fun toString(): String = "flags(0x${Integer.toHexString(bits)})"
    }

    /** own・borrow */
    class Handle(val value: Int) : CanonValue() {
        override fun equals(other: Any?): Boolean = other is Handle && other.value == value
        override fun hashCode(): Int = value
        override fun toString(): String = "handle($value)"
    }

    companion object {
        val TRUE: Bool = Bool(true)
        val FALSE: Bool = Bool(false)
        val NONE: Case = Case(0, null)

        fun some(v: CanonValue): Case = Case(1, v)
        fun ok(v: CanonValue? = null): Case = Case(0, v)
        fun err(v: CanonValue? = null): Case = Case(1, v)
        fun str(s: String): Str = Str.of(s)
    }
}
