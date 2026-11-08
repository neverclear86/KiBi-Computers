package pro.ikili.kibicomputers.core.device

import pro.ikili.kibicomputers.api.device.DeviceValue

/** デバイスの呼び出しの上限と費用（#65。どれも仮の値。確定は #251） */
internal object DeviceLimits {
    /** 1回の呼び出しの引数の数・結果の数の最大 */
    const val MAX_VALUES: Int = 32

    /** 1回の呼び出しの text・bytes の中身の合計の最大（バイト。引数と結果それぞれ） */
    const val MAX_PAYLOAD_BYTES: Int = 64 * 1024

    /** メソッドの名前の最大（バイト） */
    const val MAX_METHOD_BYTES: Int = 64

    /** イベントの名前の最大（バイト） */
    const val MAX_EVENT_BYTES: Int = 64

    /** 1プロセスが同時に持てる、待たずに積んだ呼び出し（`pending-call`）の数 */
    const val MAX_PENDING_CALLS: Int = 16

    /** 呼び出し1回の基本の燃料 */
    const val CALL_FUEL: Long = 1000

    /** 引数・結果の text・bytes 1 バイトあたりの燃料 */
    const val FUEL_PER_BYTE: Long = 1

    /** [values] の text（UTF-8）と bytes の中身の合計のバイト数 */
    fun payloadBytes(values: List<DeviceValue>): Long {
        var n = 0L
        for (v in values) {
            n += when (v.type) {
                DeviceValue.Type.TEXT -> utf8Length(v.asText())
                DeviceValue.Type.BYTES -> v.bytesUnsafe().size.toLong()
                else -> 0L
            }
        }
        return n
    }

    /** [values] が数とバイト数の上限に収まるか */
    fun fits(values: List<DeviceValue>): Boolean = values.size <= MAX_VALUES && payloadBytes(values) <= MAX_PAYLOAD_BYTES

    /** UTF-8 にしたときのバイト数（対になっていないサロゲートは 1 バイト） */
    fun utf8Length(s: String): Long {
        var n = 0L
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
