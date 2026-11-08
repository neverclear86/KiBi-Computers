package pro.ikili.kibicomputers.api.device

import pro.ikili.kibicomputers.core.device.BadArgumentsException

/**
 * メソッドの呼び出しの引数の並び（#65）。[DeviceMethodHandler.call] に渡す。値は変わらない。
 *
 * `get*` は、引数がない・種類が違うと [DeviceException] を投げる。そのまま投げ返すと、プロセスには
 * `bad-arguments(message)` として届く。
 */
public class DeviceArguments internal constructor(private val values: List<DeviceValue>) {
    /** 引数の数 */
    public fun size(): Int = values.size

    /** [index] 番目の引数。範囲外なら [DeviceValue.none] */
    public fun get(index: Int): DeviceValue = values.getOrNull(index) ?: DeviceValue.none()

    /** [index] 番目の引数が [DeviceValue.Type.NONE] か、範囲外なら `true` */
    public fun isNone(index: Int): Boolean = get(index).type == DeviceValue.Type.NONE

    /**
     * [index] 番目の整数
     *
     * @throws DeviceException ない・整数でない
     */
    @Throws(DeviceException::class)
    public fun getLong(index: Int): Long = expect(index, DeviceValue.Type.INTEGER).asLong()

    /**
     * [index] 番目の数。浮動小数と整数を受ける（整数は浮動小数にする）
     *
     * @throws DeviceException ない・数でない
     */
    @Throws(DeviceException::class)
    public fun getDouble(index: Int): Double {
        val v = get(index)
        return when (v.type) {
            DeviceValue.Type.FLOAT -> v.asDouble()
            DeviceValue.Type.INTEGER -> v.asLong().toDouble()
            else -> throw mismatch(index, "float", v)
        }
    }

    /**
     * [index] 番目の真偽
     *
     * @throws DeviceException ない・真偽でない
     */
    @Throws(DeviceException::class)
    public fun getBoolean(index: Int): Boolean = expect(index, DeviceValue.Type.BOOLEAN).asBoolean()

    /**
     * [index] 番目の文字列
     *
     * @throws DeviceException ない・文字列でない
     */
    @Throws(DeviceException::class)
    public fun getText(index: Int): String = expect(index, DeviceValue.Type.TEXT).asText()

    /**
     * [index] 番目のバイト列の写し
     *
     * @throws DeviceException ない・バイト列でない
     */
    @Throws(DeviceException::class)
    public fun getBytes(index: Int): ByteArray = expect(index, DeviceValue.Type.BYTES).asBytes()

    /** 引数の並び（内部用） */
    @JvmSynthetic
    internal fun toList(): List<DeviceValue> = values

    private fun expect(index: Int, type: DeviceValue.Type): DeviceValue {
        val v = get(index)
        if (v.type != type) throw mismatch(index, type.name.lowercase(), v)
        return v
    }

    private fun mismatch(index: Int, expected: String, actual: DeviceValue): DeviceException {
        val got = if (index in values.indices) actual.type.name.lowercase() else "nothing"
        return BadArgumentsException("argument #${index + 1}: $expected expected, got $got")
    }

    override fun toString(): String = "DeviceArguments($values)"
}
