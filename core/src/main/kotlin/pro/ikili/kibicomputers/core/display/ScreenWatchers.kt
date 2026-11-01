package pro.ikili.kibicomputers.core.display

import org.jetbrains.annotations.ApiStatus

/**
 * [ScreenWatchers] が、プレイヤーの位置を調べたり、フラグメントを送ったりするときの口（#359）。接続層が実装する。
 *
 * @param P プレイヤーの型（接続層の `ServerPlayer` など）。同じプレイヤーは同じオブジェクトで表す
 */
@ApiStatus.Internal
public interface ScreenSyncHost<P : Any> {
    /**
     * 新しく見はじめるかもしれないプレイヤー（画面と同じディメンションにいる人など）。
     * 見はじめる人を探す tick（[ScreenWatchers.DEFAULT_SCAN_INTERVAL_TICKS] に1回）だけ呼ぶ。
     */
    public fun candidates(): Iterable<P>

    /**
     * [player] の目から画面までの距離の2乗（ブロックの数の2乗）。
     * 画面を見られない（切断した、別のディメンションにいる、画面のチャンクがまだ届いていない）ときは [Double.POSITIVE_INFINITY]。
     */
    public fun distanceSquared(player: P): Double

    /** [player] に、フラグメント [fragment] を送る。 */
    public fun send(player: P, fragment: ByteArray)
}

/**
 * プレイヤーごとの、画面の同期の送信量の勘定（#359）。サーバーに1つ置き、すべての画面の [ScreenWatchers] が書き込む。
 * プレイヤーごと・サーバー全体の送信量の予算（#352）は、この勘定の上に作る。
 *
 * スレッドセーフではない。サーバーのメインスレッドから使う。
 *
 * @param P プレイヤーの型
 */
@ApiStatus.Internal
public class ScreenSyncTraffic<P : Any> {
    private class Counter {
        var bytes = 0L
        var keyframes = 0L
    }

    private val counters = HashMap<P, Counter>()

    /** すべてのプレイヤーに送ったバイト数の合計。 */
    public var totalBytes: Long = 0L
        private set

    /** [player] に [bytes] バイト送ったことを記録する。 */
    public fun recordBytes(player: P, bytes: Int) {
        counters.getOrPut(player, ::Counter).bytes += bytes
        totalBytes += bytes
    }

    /** [player] に、見はじめ・送り直しの画面全体（キーフレーム）を1つ送ったことを記録する。 */
    public fun recordKeyframe(player: P) {
        counters.getOrPut(player, ::Counter).keyframes++
    }

    /** これまでに [player] に送ったバイト数。 */
    public fun bytesSent(player: P): Long = counters[player]?.bytes ?: 0L

    /**
     * これまでに [player] に送った、見はじめ・送り直しの画面全体（キーフレーム）の数。
     * 描画命令が多すぎて、見ている全員にキーフレームを送った tick の分は含まない。
     */
    public fun keyframesSent(player: P): Long = counters[player]?.keyframes ?: 0L

    /** [player] の勘定を消す（ログアウトしたときなど）。 */
    public fun forget(player: P) {
        counters.remove(player)
    }

    /** すべての勘定を消す。 */
    public fun clear() {
        counters.clear()
        totalBytes = 0L
    }
}

/**
 * サーバー側の、1つの画面を見ているプレイヤーの集合（#359）。誰に送るかは、サーバーがプレイヤーとの距離から決める。
 *
 * - 目からの距離が [startDistance] 以内に入ったら見はじめ、[stopDistance] より遠ざかったらやめる（ヒステリシス）。
 *   見はじめる人は [scanIntervalTicks] に1回だけ探す（待っている画面の tick ごとの負荷を抑えるため）。
 *   見ている人が遠ざかったかは毎 tick 調べる。
 * - 見はじめた人には、その tick の終わりに画面全体（キーフレーム）を送り、以後は描画命令を送る。
 * - クライアントからの「送り直してほしい」（[requestResync]）は、お願いとして扱う。
 *   見ている人のものだけ受け付け、画面全体の送り直しは、プレイヤーと画面の組ごとに [resyncIntervalTicks] に1回までにする。
 *   早すぎるお願いは、間隔が空いたときに送る。見はじめるときの画面全体もこの間隔に数える。
 *
 * スレッドセーフではない。サーバーのメインスレッドから使う。
 *
 * @param P プレイヤーの型（接続層の `ServerPlayer` など）。同じプレイヤーは同じオブジェクトで表す
 * @param startDistance 見はじめる距離（ブロックの数）。仮の値（確定は #251）
 * @param stopDistance 見るのをやめる距離（ブロックの数）。[startDistance] 以上
 * @param resyncIntervalTicks 画面全体を送り直す最短の間隔（tick）。仮の値（確定は #251）
 * @param scanIntervalTicks 見はじめる人を探す間隔（tick）
 */
@ApiStatus.Internal
public class ScreenWatchers<P : Any>
@JvmOverloads
@Throws(IllegalArgumentException::class)
public constructor(
    private val startDistance: Double = DEFAULT_START_DISTANCE,
    private val stopDistance: Double = startDistance + DEFAULT_HYSTERESIS,
    private val resyncIntervalTicks: Int = DEFAULT_RESYNC_INTERVAL_TICKS,
    private val scanIntervalTicks: Int = DEFAULT_SCAN_INTERVAL_TICKS,
) {
    init {
        require(startDistance >= 0.0) { "startDistance must not be negative: $startDistance" }
        require(stopDistance >= startDistance) { "stopDistance must not be less than startDistance: $stopDistance" }
        require(resyncIntervalTicks >= 0) { "resyncIntervalTicks must not be negative: $resyncIntervalTicks" }
        require(scanIntervalTicks > 0) { "scanIntervalTicks must be positive: $scanIntervalTicks" }
    }

    private val startSquared = startDistance * startDistance
    private val stopSquared = stopDistance * stopDistance

    // 前の tick までに画面を送った人
    private val watchers = LinkedHashSet<P>()

    // この tick の終わりに画面全体を送る人（見はじめた人と、送り直しのお願いが通った人）。watchers とは重ならない
    private val fresh = LinkedHashSet<P>()

    // 送り直しのお願いが早すぎたので、間隔が空くのを待っている人。watchers の中の人だけ
    private val deferred = LinkedHashSet<P>()

    // 最後に画面全体を送った tick。見るのをやめた人も、間隔が過ぎるまで覚えておく
    private val lastKeyframe = HashMap<P, Long>()

    private var scanned = false

    /** 見ているプレイヤーの数（この tick に見はじめて、まだ送っていない人も含む）。 */
    public val count: Int
        get() = watchers.size + fresh.size

    /** [player] が見ているか（この tick に見はじめて、まだ送っていない人も含む）。 */
    public fun isWatching(player: P): Boolean = player in watchers || player in fresh

    /**
     * [player] からの「画面全体を送り直してほしい」というお願い（受け取ったデータが壊れていたときなど）。
     * 見ていない人のお願いは無視する。前に画面全体を送ってから [resyncIntervalTicks] が過ぎていなければ、過ぎてから送る。
     *
     * @param now いまの tick（[tick] に渡すものと同じ時計）
     * @return お願いを受け付けたか（この tick の終わりか、後で送る）。見ていない人なら false
     */
    public fun requestResync(player: P, now: Long): Boolean {
        if (player in fresh) return true
        if (player !in watchers) return false
        if (canSendKeyframe(player, now)) {
            watchers.remove(player)
            deferred.remove(player)
            fresh.add(player)
        } else {
            deferred.add(player)
        }
        return true
    }

    /** [player] を見ている人から外す（ログアウトしたときなど）。 */
    public fun remove(player: P) {
        watchers.remove(player)
        fresh.remove(player)
        deferred.remove(player)
    }

    /** 見ている人をすべて外す（画面が消えたときなど）。 */
    public fun clear() {
        watchers.clear()
        fresh.clear()
        deferred.clear()
        lastKeyframe.clear()
    }

    /**
     * tick の終わりに1回呼ぶ。見ている人を決め直し、[sender] の、その tick の分を送る。
     *
     * @param now いまの tick。単調に増える時計（ディメンションのゲーム時間など）
     * @param sender この画面の同期の送り手
     * @param host プレイヤーの位置を調べ、フラグメントを送る口
     * @param traffic 送信量の勘定。null なら記録しない
     */
    public fun tick(now: Long, sender: ScreenSyncSender, host: ScreenSyncHost<P>, traffic: ScreenSyncTraffic<P>?) {
        val scan = !scanned || Math.floorMod(now, scanIntervalTicks.toLong()) == 0L
        if (!scan && watchers.isEmpty() && fresh.isEmpty() && !sender.isRecording) return
        scanned = true

        // 遠ざかった人・見られなくなった人を外す
        dropFarAway(watchers, host)
        dropFarAway(fresh, host)
        if (deferred.isNotEmpty()) deferred.retainAll(watchers)

        if (scan) {
            if (lastKeyframe.isNotEmpty()) {
                lastKeyframe.entries.removeIf { (p, t) -> now - t >= resyncIntervalTicks && !isWatching(p) }
            }
            for (p in host.candidates()) {
                if (isWatching(p) || !canSendKeyframe(p, now)) continue
                if (host.distanceSquared(p) <= startSquared) fresh.add(p)
            }
        }

        // 間隔が空いた、待っていた送り直し
        if (deferred.isNotEmpty()) {
            val it = deferred.iterator()
            while (it.hasNext()) {
                val p = it.next()
                if (canSendKeyframe(p, now)) {
                    it.remove()
                    watchers.remove(p)
                    fresh.add(p)
                }
            }
        }

        val flush = sender.flush(watchers.isNotEmpty(), fresh.isNotEmpty())
        if (!flush.isEmpty) {
            for (fragment in flush.forWatchers) for (p in watchers) send(host, traffic, p, fragment)
            for (fragment in flush.forNewWatchers) for (p in fresh) send(host, traffic, p, fragment)
        }
        if (flush.forNewWatchers.isNotEmpty()) {
            for (p in fresh) {
                lastKeyframe[p] = now
                traffic?.recordKeyframe(p)
            }
        }
        watchers.addAll(fresh)
        fresh.clear()
    }

    private fun canSendKeyframe(player: P, now: Long): Boolean {
        val last = lastKeyframe[player] ?: return true
        return now - last >= resyncIntervalTicks
    }

    private fun dropFarAway(set: MutableSet<P>, host: ScreenSyncHost<P>) {
        if (set.isNotEmpty()) set.removeIf { host.distanceSquared(it) > stopSquared }
    }

    private fun send(host: ScreenSyncHost<P>, traffic: ScreenSyncTraffic<P>?, player: P, fragment: ByteArray) {
        host.send(player, fragment)
        traffic?.recordBytes(player, fragment.size)
    }

    public companion object {
        /**
         * 見はじめる距離の既定値（ブロックの数）。描画が背景とピクセルを描く距離（[ScreenDetail.COLORS_DISTANCE]）と同じ。
         * 仮の値（確定は #251）。
         */
        public const val DEFAULT_START_DISTANCE: Double = ScreenDetail.COLORS_DISTANCE

        /** 見はじめる距離と、やめる距離の差の既定値（ブロックの数）。境目を行き来したときに画面全体を送り続けないようにする。 */
        public const val DEFAULT_HYSTERESIS: Double = 8.0

        /** 画面全体を送り直す最短の間隔の既定値（tick。2秒）。仮の値（確定は #251）。 */
        public const val DEFAULT_RESYNC_INTERVAL_TICKS: Int = 40

        /** 見はじめる人を探す間隔の既定値（tick）。 */
        public const val DEFAULT_SCAN_INTERVAL_TICKS: Int = 10
    }
}
