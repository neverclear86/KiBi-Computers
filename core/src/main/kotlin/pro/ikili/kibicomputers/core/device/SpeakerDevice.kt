package pro.ikili.kibicomputers.core.device

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceContext
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.kernel.Machine
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** スピーカーの音を鳴らす先（接続層が [SpeakerDevice] に渡す）。 */
@ApiStatus.Internal
public fun interface SpeakerListener {
    /**
     * ビープを鳴らす。つないだマシンの `tick()` の中で、メインスレッドから呼ばれる。中で投げた例外はログに出して捨てる（JVM の致命的なエラーは投げる。#674）。
     *
     * @param frequencyHz 周波数（[SpeakerDevice.MIN_FREQUENCY_HZ]〜[SpeakerDevice.MAX_FREQUENCY_HZ] に丸めてある）
     * @param durationMillis 長さ（1〜[SpeakerDevice.MAX_DURATION_MILLIS] に丸めてある）
     */
    public fun play(frequencyHz: Int, durationMillis: Int)
}

/**
 * スピーカーのデバイス（`kibicomputers:speaker`。#65、ADR 0065）。ケースに付いたビープ（ADR 0044 の接続層へのビープの通知を置き換えた）。
 *
 * メソッド: `beep(frequency: integer, duration-ms: integer) -> boolean`（直接）。値は範囲に丸め、つないだマシンの通知の列に積む
 * だけで待たない。鳴らすのは次の `tick()` の中で、メインスレッドから [listener] を呼ぶ（デバイスは tick を持たない）。
 * まだ鳴らしていないビープが [MAX_PENDING] 個あるなら捨てて `false`。
 *
 * スレッド: つなぐ・外すのはメインスレッド。`beep` はどのスレッドから呼んでもよい。
 */
@ApiStatus.Internal
public class SpeakerDevice(private val listener: SpeakerListener) : Device {
    override val kind: String get() = DeviceKind.SPEAKER

    override val methods: DeviceMethods = DeviceMethods.builder()
        .direct("beep") { _, args ->
            val frequency = args.getLong(0).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            val duration = args.getLong(1).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            arrayOf(DeviceValue.of(beep(frequency, duration)))
        }
        .build()

    @Volatile
    private var machine: Machine? = null

    private val pending = AtomicInteger()

    /** 捨てたビープの数（数え口） */
    public val droppedBeeps: AtomicLong = AtomicLong()

    override fun onAttached(context: DeviceContext) {
        machine = (context as AttachedDevice).machine
    }

    override fun onDetached(context: DeviceContext) {
        if (machine === (context as AttachedDevice).machine) machine = null
    }

    /**
     * ビープを鳴らす（次の `tick()` で [listener] に渡す）。値は範囲に丸める。どのスレッドから呼んでもよい。
     * つないでいない・まだ鳴らしていないビープが [MAX_PENDING] 個あるなら捨てて `false`
     */
    public fun beep(frequencyHz: Int, durationMillis: Int): Boolean {
        val m = machine ?: return false
        while (true) {
            val n = pending.get()
            if (n >= MAX_PENDING) {
                droppedBeeps.incrementAndGet()
                return false
            }
            if (pending.compareAndSet(n, n + 1)) break
        }
        val f = frequencyHz.coerceIn(MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ)
        val d = durationMillis.coerceIn(1, MAX_DURATION_MILLIS)
        m.postHostCallback {
            pending.decrementAndGet()
            listener.play(f, d)
        }
        return true
    }

    override fun toString(): String = "SpeakerDevice($machine)"

    public companion object {
        /** ビープの周波数の下限（Hz） */
        public const val MIN_FREQUENCY_HZ: Int = 20

        /** ビープの周波数の上限（Hz） */
        public const val MAX_FREQUENCY_HZ: Int = 20_000

        /** ビープの長さの上限（ミリ秒。仮の値。確定は #251） */
        public const val MAX_DURATION_MILLIS: Int = 5_000

        /** まだ鳴らしていないビープの上限（仮の値。確定は #251） */
        public const val MAX_PENDING: Int = 8
    }
}
