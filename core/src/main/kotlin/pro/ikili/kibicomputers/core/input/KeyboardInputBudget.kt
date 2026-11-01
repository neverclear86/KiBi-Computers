package pro.ikili.kibicomputers.core.input

/**
 * キーボードが受け取る入力の量の上限（トークンバケット、#142）。クライアントから大量のパケットを送られても、
 * サーバーの処理が増えすぎないようにする。キーボードごとに1つ持つ。
 *
 * 量は受け取ったパケットのバイト数で数える。tick ごとに [refillPerTick] バイトずつ、[capacity] まで戻る。
 * 戻す計算は使うときにまとめて行うので、tick ごとの処理はない。
 *
 * @param capacity ためておける量（一度に受け取れる最大）。1回の貼り付けのパケットより大きくする
 * @param refillPerTick 1 tick ごとに戻る量
 * @throws IllegalArgumentException [capacity] が正でないか、[refillPerTick] が負のとき
 */
public class KeyboardInputBudget @JvmOverloads @Throws(IllegalArgumentException::class) constructor(
    public val capacity: Int = DEFAULT_CAPACITY,
    public val refillPerTick: Int = DEFAULT_REFILL_PER_TICK,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
        require(refillPerTick >= 0) { "refillPerTick must not be negative" }
    }

    private var available: Long = capacity.toLong()
    private var lastTick: Long = Long.MIN_VALUE

    /** 今の残り（[tick] の時点）。 */
    public fun available(tick: Long): Int {
        refill(tick)
        return available.toInt()
    }

    /**
     * [tick] の時点で [bytes] を使う。足りなければ使わずに false を返す（そのパケットは捨てる）。
     *
     * @throws IllegalArgumentException [bytes] が負のとき
     */
    @Throws(IllegalArgumentException::class)
    public fun tryConsume(bytes: Int, tick: Long): Boolean {
        require(bytes >= 0) { "bytes must not be negative" }
        refill(tick)
        if (bytes > available) return false
        available -= bytes
        return true
    }

    private fun refill(tick: Long) {
        if (lastTick == Long.MIN_VALUE) {
            lastTick = tick
            return
        }
        val elapsed = tick - lastTick
        if (elapsed <= 0) return
        lastTick = tick
        if (refillPerTick == 0) return
        // 長く空いても capacity で頭打ち（掛け算のあふれを避ける）
        available = if (elapsed >= capacity) capacity.toLong() else minOf(capacity.toLong(), available + elapsed * refillPerTick)
    }

    public companion object {
        /** ためておける量の既定（仮の値。#251 で確定）。貼り付け1回分のパケットが入る。 */
        public const val DEFAULT_CAPACITY: Int = 2 * KeyboardEventCodec.MAX_PACKET_BYTES

        /** 1 tick ごとに戻る量の既定（仮の値。#251 で確定）。1秒で 20 KiB。 */
        public const val DEFAULT_REFILL_PER_TICK: Int = 1024
    }
}
