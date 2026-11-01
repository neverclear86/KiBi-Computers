package pro.ikili.kibicomputers.core.kernel.event

/**
 * イベントキューの上限（仮の値。確定は #251）。
 *
 * メモリの見積もり（[KernelEvent.footprint]）は、JVM の上での実際の大きさを下回らないように、オブジェクトの固定のコストを含めて数える
 * （ADR 0063 の「メモリの数え方」）。
 */
internal object EventLimits {
    /** キューの件数の既定値 */
    const val DEFAULT_CAPACITY: Int = 64

    /** キューの件数の最大値。`openEventQueue` の capacity は `1..MAX_CAPACITY` */
    const val MAX_CAPACITY: Int = 256

    /** マシンあたりのキューの数 */
    const val MAX_QUEUES: Int = 64

    /** マシン全体で溜めるイベントの見積もり（[KernelEvent.footprint]）の合計（バイト） */
    const val MAX_PENDING_BYTES: Int = 256 * 1024

    /** 1イベントの固定の見積もり（`KernelEvent` 自体、値の並び、キューの中の場所。バイト） */
    const val EVENT_OVERHEAD_BYTES: Int = 96

    /** 値1個ごとの固定の見積もり（値のオブジェクトと、並びの中の場所。バイト） */
    const val EVENT_VALUE_OVERHEAD_BYTES: Int = 32

    /** 文字列・バイト列1個ごとのオブジェクトの見積もり（`String`・`ByteArray` の本体。中身のバイト数は別に足す） */
    const val OBJECT_OVERHEAD_BYTES: Int = 40

    /** 種類の名前の最大（UTF-8 のバイト。1 以上） */
    const val MAX_TYPE_BYTES: Int = 64

    /** 出したもの（source）の最大（UTF-8 のバイト） */
    const val MAX_SOURCE_BYTES: Int = 128

    /** 値の数の最大 */
    const val MAX_VALUES: Int = 32

    /** `Text`（UTF-8）と `Bytes` の中身の合計の最大（バイト） */
    const val MAX_PAYLOAD_BYTES: Int = 4 * 1024

    /** フィルターに書ける種類の数の最大 */
    const val MAX_FILTER_TYPES: Int = 16
}
