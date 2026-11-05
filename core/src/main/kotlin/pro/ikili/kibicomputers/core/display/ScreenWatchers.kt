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
 * サーバー側の、1つの画面を見ているプレイヤーの集合（#359）。誰に送るかは、サーバーがプレイヤーとの距離から決める。
 *
 * - 目からの距離が [startDistance] 以内に入ったら見はじめ、[stopDistance] より遠ざかったらやめる（ヒステリシス）。
 *   見はじめる人は [scanIntervalTicks] に1回だけ探す（待っている画面の tick ごとの負荷を抑えるため）。
 *   見ている人が遠ざかったかは毎 tick 調べる。
 * - 見はじめた人には、その tick の終わりに画面全体（キーフレーム）を送り、以後は描画命令を送る。
 * - クライアントからの「送り直してほしい」（[requestResync]）は、お願いとして扱う。
 *   見ている人のものだけ受け付け、画面全体の送り直しは、プレイヤーと画面の組ごとに [resyncIntervalTicks] に1回までにする。
 *   早すぎるお願いは、間隔が空いたときに送る。見はじめるときの画面全体もこの間隔に数える。
 * - 送信量の予算（[ScreenSyncTraffic]。#352）が足りずに、ある人へのメッセージを送れなかったら、その人は「止められた人」に
 *   移す。止められた人には描画命令を送らず、予算の残りに画面全体が収まり、前に画面全体を送って（試して）から
 *   [throttleRetryTicks] が過ぎていれば、画面全体を送ってみる（届いたら、見ている人に戻る）。
 *
 * スレッドセーフではない。サーバーのメインスレッドから使う。
 *
 * @param P プレイヤーの型（接続層の `ServerPlayer` など）。同じプレイヤーは同じオブジェクトで表す
 * @param startDistance 見はじめる距離（ブロックの数）。仮の値（確定は #251）
 * @param stopDistance 見るのをやめる距離（ブロックの数）。[startDistance] 以上
 * @param resyncIntervalTicks 画面全体を送り直す最短の間隔（tick）。仮の値（確定は #251）
 * @param scanIntervalTicks 見はじめる人を探す間隔（tick）
 * @param throttleRetryTicks 送信量の予算で止められた人に、画面全体を送ってみる最短の間隔（tick）。仮の値（確定は #251）
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
    private val throttleRetryTicks: Int = DEFAULT_THROTTLE_RETRY_TICKS,
) {
    init {
        require(startDistance >= 0.0) { "startDistance must not be negative: $startDistance" }
        require(stopDistance >= startDistance) { "stopDistance must not be less than startDistance: $stopDistance" }
        require(resyncIntervalTicks >= 0) { "resyncIntervalTicks must not be negative: $resyncIntervalTicks" }
        require(scanIntervalTicks > 0) { "scanIntervalTicks must be positive: $scanIntervalTicks" }
        require(throttleRetryTicks >= 0) { "throttleRetryTicks must not be negative: $throttleRetryTicks" }
    }

    private val startSquared = startDistance * startDistance
    private val stopSquared = stopDistance * stopDistance

    // 前の tick までに画面を送った人
    private val watchers = LinkedHashSet<P>()

    // この tick の終わりに画面全体を送る人（見はじめた人と、送り直しのお願いが通った人）。watchers とは重ならない
    private val fresh = LinkedHashSet<P>()

    // 送り直しのお願いが早すぎたので、間隔が空くのを待っている人。watchers の中の人だけ
    private val deferred = LinkedHashSet<P>()

    // 送信量の予算が足りずにメッセージを送れなかったので、画面全体を送れるのを待っている人。watchers・fresh とは重ならない
    private val throttled = LinkedHashSet<P>()

    // 最後に画面全体を送った tick。見るのをやめた人も、間隔が過ぎるまで覚えておく
    private val lastKeyframe = HashMap<P, Long>()

    private var scanned = false

    /** 見ているプレイヤーの数（この tick に見はじめて、まだ送っていない人と、送信量の予算で止められている人も含む）。 */
    public val count: Int
        get() = watchers.size + fresh.size + throttled.size

    /** [player] が見ているか（この tick に見はじめて、まだ送っていない人と、送信量の予算で止められている人も含む）。 */
    public fun isWatching(player: P): Boolean = player in watchers || player in fresh || player in throttled

    /** [player] が、送信量の予算が足りずに止められていて、画面全体を送れるのを待っているか（#352）。 */
    public fun isThrottled(player: P): Boolean = player in throttled

    /**
     * [player] からの「画面全体を送り直してほしい」というお願い（受け取ったデータが壊れていたときなど）。
     * 見ていない人のお願いは無視する。前に画面全体を送ってから [resyncIntervalTicks] が過ぎていなければ、過ぎてから送る。
     *
     * @param now いまの tick（[tick] に渡すものと同じ時計）
     * @return お願いを受け付けたか（この tick の終わりか、後で送る）。見ていない人なら false
     */
    public fun requestResync(player: P, now: Long): Boolean {
        // 止められている人には、どのみち画面全体を送る
        if (player in fresh || player in throttled) return true
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

    /**
     * [player] を見ている人から外し、最後に画面全体を送った tick も忘れる。
     * ログアウトしたとき、リスポーンでプレイヤーのオブジェクトが替わったとき、ディメンションを移ったとき
     * （クライアントの画面は作り直されるので、戻ってきたら間隔を待たずに画面全体を送る）に呼ぶ。
     * 外したオブジェクトへの参照は残らない。
     */
    public fun remove(player: P) {
        watchers.remove(player)
        fresh.remove(player)
        deferred.remove(player)
        throttled.remove(player)
        lastKeyframe.remove(player)
    }

    /** 見ている人をすべて外す（画面が消えたときなど）。 */
    public fun clear() {
        watchers.clear()
        fresh.clear()
        deferred.clear()
        throttled.clear()
        lastKeyframe.clear()
    }

    /**
     * この tick に [tick] がすることがあるか。false なら [tick] は何もしない（見ている人がおらず、見はじめる人を探す tick でもない）。
     *
     * 誰も見ていない画面は、ほとんどの tick で false になる。接続層は、これが false の tick には、
     * 画面のバッファ（プロセスのスレッドが書く表示デバイス。#355）のロックを取らずに飛ばしてよい（#351）。
     * [sender] の状態（[ScreenSyncSender.isRecording]）だけを見るので、画面のバッファのロックは要らない。
     *
     * @param now いまの tick（[tick] に渡すものと同じ時計）
     * @param sender この画面の同期の送り手（[tick] に渡すものと同じ）
     */
    public fun needsTick(now: Long, sender: ScreenSyncSender): Boolean =
        isScanTick(now) || watchers.isNotEmpty() || fresh.isNotEmpty() || throttled.isNotEmpty() || sender.isRecording

    private fun isScanTick(now: Long): Boolean = !scanned || Math.floorMod(now, scanIntervalTicks.toLong()) == 0L

    /**
     * tick の終わりに1回呼ぶ。見ている人を決め直し、[sender] の、その tick の分を送る。
     * [traffic] を渡したときは、すぐには送らず [traffic] に積む。すべての画面の後で [ScreenSyncTraffic.endTick] を呼ぶと送る。
     *
     * @param now いまの tick。単調に増える時計（ディメンションのゲーム時間など）
     * @param sender この画面の同期の送り手
     * @param host プレイヤーの位置を調べ、フラグメントを送る口
     * @param traffic 送信量の勘定と予算。null なら予算をかけずにすぐ送り、記録もしない
     */
    public fun tick(now: Long, sender: ScreenSyncSender, host: ScreenSyncHost<P>, traffic: ScreenSyncTraffic<P>?) {
        if (!needsTick(now, sender)) return
        val scan = isScanTick(now)
        scanned = true

        // 遠ざかった人・見られなくなった人を外す
        dropFarAway(watchers, host)
        dropFarAway(fresh, host)
        dropFarAway(throttled, host)
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

        // 送信量の予算で止められた人のうち、予算の残りに画面全体が収まり、間隔が空いた人に、画面全体を送ってみる
        if (throttled.isNotEmpty()) {
            val keyframeBytes = sender.keyframeBytes
            val it = throttled.iterator()
            while (it.hasNext()) {
                val p = it.next()
                val last = lastKeyframe[p]
                if ((last == null || now - last >= throttleRetryTicks) && (traffic == null || traffic.hasBudget(p, keyframeBytes))) {
                    it.remove()
                    fresh.add(p)
                }
            }
        }

        val flush = sender.flush(watchers.isNotEmpty(), fresh.isNotEmpty())
        if (flush.forWatchers.isNotEmpty()) {
            for (p in watchers) deliver(host, traffic, p, flush.forWatchers, keyframe = false)
        }
        if (flush.forNewWatchers.isNotEmpty()) {
            // 送れなかったときも、画面全体を試した tick として数える（止められた人が毎 tick 試さないように）
            for (p in fresh) {
                lastKeyframe[p] = now
                deliver(host, traffic, p, flush.forNewWatchers, keyframe = true)
            }
        }
        watchers.addAll(fresh)
        fresh.clear()
    }

    /**
     * [ScreenSyncTraffic.endTick] が、送信量の予算が足りずに、この画面から [player] へのメッセージを送らなかった。
     * [player] は前に送った画面の続きを持っていないので、止められた人に移し、次は画面全体を送る。
     */
    internal fun onThrottled(player: P) {
        if (watchers.remove(player)) {
            deferred.remove(player)
            throttled.add(player)
        }
    }

    private fun canSendKeyframe(player: P, now: Long): Boolean {
        val last = lastKeyframe[player] ?: return true
        return now - last >= resyncIntervalTicks
    }

    private fun dropFarAway(set: MutableSet<P>, host: ScreenSyncHost<P>) {
        if (set.isNotEmpty()) set.removeIf { host.distanceSquared(it) > stopSquared }
    }

    private fun deliver(
        host: ScreenSyncHost<P>,
        traffic: ScreenSyncTraffic<P>?,
        player: P,
        fragments: List<ByteArray>,
        keyframe: Boolean,
    ) {
        if (traffic == null) {
            for (f in fragments) host.send(player, f)
        } else {
            traffic.enqueue(this, host, player, fragments, keyframe, host.distanceSquared(player))
        }
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

        /** 送信量の予算で止められた人に、画面全体を送ってみる最短の間隔の既定値（tick。0.5秒）。仮の値（確定は #251）。 */
        public const val DEFAULT_THROTTLE_RETRY_TICKS: Int = 10
    }
}
