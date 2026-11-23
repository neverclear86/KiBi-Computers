package pro.ikili.kibicomputers.core.kernel

import org.jetbrains.annotations.ApiStatus

/**
 * ゲーム内時間を、接続層が写した値で返す時計（#439）。実時間・単調時計は [MachineClock.SYSTEM] に委ねる。
 *
 * 接続層は、ワールド（次元）ごとに1つ作り、メインスレッドの tick ごとに [publish] でその時点のゲーム内時間
 * （`Level.getGameTime()`）を写す。[worldTicks] は写した値（`volatile`）を読むだけなので、どのスレッドから呼んでもよい
 * （[MachineClock] の約束）。台数が増えても、tick ごとの処理はワールドの数だけで済む。
 *
 * 作ったときの値を [initialWorldTicks] で入れる。最初の [publish] までの間に呼ばれても（作った直後に復元・保存するとき）、
 * 0 を返さない（0 を返すと、保存する時計の値や、復元のときの飛びが狂う）。
 *
 * @param initialWorldTicks 作ったときのゲーム内時間
 */
@ApiStatus.Internal
public class PublishedWorldClock(initialWorldTicks: Long) : MachineClock {
    @Volatile
    private var ticks: Long = initialWorldTicks

    /** ゲーム内時間を写す。接続層がメインスレッドから tick ごとに呼ぶ（書くのは1つのスレッドだけにする） */
    public fun publish(worldTicks: Long) {
        ticks = worldTicks
    }

    override fun realtimeNanos(): Long = MachineClock.SYSTEM.realtimeNanos()

    override fun monotonicNanos(): Long = MachineClock.SYSTEM.monotonicNanos()

    /** 最後に [publish] した値（まだなら作ったときの値）。負の値もそのまま返す（0 に丸めるのはカーネル） */
    override fun worldTicks(): Long = ticks

    override fun toString(): String = "PublishedWorldClock(worldTicks=$ticks)"
}
