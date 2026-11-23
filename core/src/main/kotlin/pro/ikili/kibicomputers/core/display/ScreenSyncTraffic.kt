package pro.ikili.kibicomputers.core.display

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.core.kernel.sched.FairShareGroup

/**
 * 画面の同期の、プレイヤーごとの送信量の勘定と予算（#359、#352）。サーバーに1つ置き、すべての画面の [ScreenWatchers] が使う。
 *
 * 予算は3段にする。どれも、1秒分を上限に tick ごとに増える（トークンバケット）。
 *
 * - プレイヤーごとの予算（[playerBytesPerSecond]）: そのプレイヤーが見ている全画面の合計
 * - 公平の組ごとの予算（[groupBytesPerSecond]。#738）: 映している側（画面がつながったケースの持ち主。[ScreenSyncHost.fairShareGroup]）の
 *   全画面から、全プレイヤーへの合計。0 なら置かない。満タンの組は勘定から消す
 * - サーバー全体の予算（[serverBytesPerSecond]）: すべてのプレイヤーの合計
 *
 * [ScreenWatchers.tick] は、送るもの（画面とプレイヤーの組ごとの、その tick のメッセージ）をすぐには送らず、ここに積む。
 * すべての画面の tick の後に [endTick] を1回呼ぶと、積んだものを送る。まず公平の組の間で1つずつ順番に回し（#738）、組の中は
 * 優先度の順にする（組が1つだけなら、優先度の順そのもの）。組の巡回は tick をまたいで続ける（#896）: 組の間は、最後に送れたのが
 * 古い組から回すので、1 tick の予算が組の数より少ないメッセージしか通さない状態が続いても、どの組にも順番が来る。
 * 送れなかった組は、キーフレームを試し直すまで何 tick か積まないことがあるので、巡回の位置は積まなくなってからも
 * [ROTATION_RETAIN_TICKS] の間は残す。優先度は、画面までの距離と
 * メッセージの大きさの積で、近い画面・変化の少ない画面ほど先に送る。プレイヤー（または組・サーバー全体）の予算の残りに
 * 収まらないメッセージは送らず、その画面とプレイヤーの組は、次の送信をキーフレームにする（ADR 0031）。
 * キーフレームを試すのは、予算の残りにそのキーフレームが収まり、前に試してから一定の間隔
 * （[ScreenWatchers.DEFAULT_THROTTLE_RETRY_TICKS]）が過ぎたときだけ。
 * 遠い画面・変化の多い画面から、送る頻度が落ちる。
 *
 * 1秒分の予算より大きなメッセージ（大きな画面のキーフレームなど）は、予算が満タンのときだけ送る（残りは負になり、
 * 戻るまで送らない）。いつかは送れるようにするため。
 *
 * スレッドセーフではない。サーバーのメインスレッドから使う。
 *
 * @param P プレイヤーの型
 * @param playerBytesPerSecond プレイヤーごとの送信量の上限（バイト/秒）。仮の値（確定は #251）
 * @param serverBytesPerSecond サーバー全体の送信量の上限（バイト/秒）。仮の値（確定は #251）
 * @param groupBytesPerSecond 公平の組（持ち主）ごとの送信量の上限（バイト/秒。0 は上限なし。#738）。仮の値（確定は #251）
 */
@ApiStatus.Internal
public class ScreenSyncTraffic<P : Any>
@JvmOverloads
@Throws(IllegalArgumentException::class)
public constructor(
    public val playerBytesPerSecond: Int = DEFAULT_PLAYER_BYTES_PER_SECOND,
    public val serverBytesPerSecond: Int = DEFAULT_SERVER_BYTES_PER_SECOND,
    public val groupBytesPerSecond: Int = DEFAULT_GROUP_BYTES_PER_SECOND,
) {
    init {
        require(playerBytesPerSecond > 0) { "playerBytesPerSecond must be positive: $playerBytesPerSecond" }
        require(serverBytesPerSecond > 0) { "serverBytesPerSecond must be positive: $serverBytesPerSecond" }
        require(groupBytesPerSecond >= 0) { "groupBytesPerSecond must not be negative: $groupBytesPerSecond" }
    }

    private inner class Counter {
        var bytes = 0L
        var keyframes = 0L
        var throttled = 0L

        // 送ってよいバイト数の残り。1秒分より大きなメッセージを送ったときだけ負になる
        var budget = playerBytesPerSecond.toLong()
    }

    /** 積んだ、まだ送っていないメッセージ（画面とプレイヤーの組ごとに、1 tick に1つ）。 */
    private class Delivery<P : Any>(
        val watchers: ScreenWatchers<P>,
        val host: ScreenSyncHost<P>,
        val player: P,
        val group: FairShareGroup,
        val fragments: List<ByteArray>,
        val bytes: Int,
        val keyframe: Boolean,
        val priority: Double,
    )

    private val counters = HashMap<P, Counter>()
    private val pending = ArrayList<Delivery<P>>()
    private var serverBudget = serverBytesPerSecond.toLong()

    /** 公平の組ごとの予算の残り（満タンでない組だけ。満タンに戻ったら消す。#738） */
    private val groupBudgets = HashMap<FairShareGroup, Long>()

    /** 組の巡回の位置（#896）。[stamp] は最後に送れた番号（[serveCount]）、[lastSeen] は最後に積んだ tick（[tickCount]） */
    private class Rotation(var stamp: Long, var lastSeen: Long)

    /**
     * 組の巡回の位置（#896）。[ROTATION_RETAIN_TICKS] 以内に積んだ組だけを持つ（今までに映したすべての持ち主は溜めない）。
     * 番号の小さい組ほど長く送れていないので、先に回す。初めて積んだ組（または消えた後で戻った組）は、その時点の
     * [serveCount] を番号にする（それより前に送れた組の後ろ、後に送れた組の前に並ぶ）
     */
    private val rotation = HashMap<FairShareGroup, Rotation>()

    /** 送れたメッセージの通し番号（巡回の位置に使う。#896） */
    private var serveCount = 0L

    /** [endTick] を呼んだ回数（巡回の位置を消す判断に使う。#896） */
    private var tickCount = 0L

    /** すべてのプレイヤーに送ったバイト数の合計。 */
    public var totalBytes: Long = 0L
        private set

    /** 予算が足りずに送らなかったメッセージの数の合計。 */
    public var totalThrottled: Long = 0L
        private set

    /** [player] に [bytes] バイト送ったことを記録する。予算からも引く。 */
    public fun recordBytes(player: P, bytes: Int) {
        val c = counter(player)
        c.bytes += bytes
        c.budget -= bytes
        totalBytes += bytes
        serverBudget -= bytes
    }

    /** [player] に、見はじめ・送り直しの画面全体（キーフレーム）を1つ送ったことを記録する。 */
    public fun recordKeyframe(player: P) {
        counter(player).keyframes++
    }

    /** これまでに [player] に送ったバイト数。 */
    public fun bytesSent(player: P): Long = counters[player]?.bytes ?: 0L

    /**
     * これまでに [player] に送った、見はじめ・送り直しの画面全体（キーフレーム）の数。
     * 描画命令が多すぎて、見ている全員にキーフレームを送った tick の分は含まない。
     */
    public fun keyframesSent(player: P): Long = counters[player]?.keyframes ?: 0L

    /** これまでに、予算が足りずに [player] に送らなかったメッセージの数。 */
    public fun throttled(player: P): Long = counters[player]?.throttled ?: 0L

    /** [player] の予算の残り（バイト）。 */
    public fun remainingBudget(player: P): Long = counters[player]?.budget ?: playerBytesPerSecond.toLong()

    /** サーバー全体の予算の残り（バイト）。 */
    public val remainingServerBudget: Long
        get() = serverBudget

    /** 公平の組 [group] の予算の残り（バイト。組ごとの上限がなければ [Long.MAX_VALUE]。#738）。 */
    public fun remainingGroupBudget(group: FairShareGroup): Long =
        if (groupBytesPerSecond == 0) Long.MAX_VALUE else groupBudgets[group] ?: groupBytesPerSecond.toLong()

    /** 予算の残りを記録している公平の組の数（テスト用。満タンの組は数えない。#738） */
    internal val trackedGroups: Int get() = groupBudgets.size

    /** 巡回の位置を持っている公平の組の数（テスト用。#896） */
    internal val rotationGroups: Int get() = rotation.size

    /**
     * tick の終わりに、すべての画面の [ScreenWatchers.tick] の後で1回呼ぶ。積んだメッセージを優先度の順に、
     * 予算の残りに収まるものだけ送り、予算を1 tick 分増やす。組の間は、tick をまたいで続く巡回の順にする（#738、#896）
     */
    public fun endTick() {
        if (pending.isNotEmpty()) {
            if (pending.size > 1) pending.sortWith(BY_PRIORITY)
            for (d in fairOrder()) {
                val c = counter(d.player)
                if (fits(c.budget, playerBytesPerSecond, d.bytes) && fitsGroup(d.group, d.bytes) &&
                    fits(serverBudget, serverBytesPerSecond, d.bytes)
                ) {
                    for (f in d.fragments) d.host.send(d.player, f)
                    recordBytes(d.player, d.bytes)
                    chargeGroup(d.group, d.bytes)
                    rotation[d.group]?.stamp = ++serveCount
                    if (d.keyframe) c.keyframes++
                } else {
                    c.throttled++
                    totalThrottled++
                    d.watchers.onThrottled(d.player)
                }
            }
            pending.clear()
        }
        tickCount++
        if (rotation.isNotEmpty()) {
            // しばらく積まなかった組の巡回の位置は消す（今までに映したすべての持ち主を溜めない）
            val oldest = tickCount - ROTATION_RETAIN_TICKS
            rotation.values.removeIf { it.lastSeen < oldest }
        }
        val playerCap = playerBytesPerSecond.toLong()
        val playerRefill = maxOf(1L, playerCap / ScreenSyncSender.TICKS_PER_SECOND)
        for (c in counters.values) {
            if (c.budget < playerCap) c.budget = minOf(playerCap, c.budget + playerRefill)
        }
        val serverCap = serverBytesPerSecond.toLong()
        serverBudget = minOf(serverCap, serverBudget + maxOf(1L, serverCap / ScreenSyncSender.TICKS_PER_SECOND))
        if (groupBudgets.isNotEmpty()) {
            // 満タンに戻った組は消す（今までに映したすべての持ち主を回さない）
            val groupCap = groupBytesPerSecond.toLong()
            val groupRefill = maxOf(1L, groupCap / ScreenSyncSender.TICKS_PER_SECOND)
            val it = groupBudgets.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                val next = e.value + groupRefill
                if (next >= groupCap) it.remove() else e.setValue(next)
            }
        }
    }

    /**
     * 優先度の順に並べた [pending] を、公平の組の間で1つずつ順番に取り出す順にする（#738）。組の中は優先度の順のまま。
     * 組の順は、最後に送れた番号（[rotation]）の古い順で、同じ番号の組の間は、その組の最も優先度の高いメッセージの順（#896）。
     * 組が1つだけなら [pending] のまま（巡回の位置は更新する）
     */
    private fun fairOrder(): List<Delivery<P>> {
        val first = pending[0].group
        if (pending.all { it.group == first }) {
            seen(first)
            return pending
        }
        val byGroup = LinkedHashMap<FairShareGroup, ArrayDeque<Delivery<P>>>()
        for (d in pending) byGroup.getOrPut(d.group) { ArrayDeque() }.addLast(d)
        // 番号は先に1回ずつ引く（比べるたびに表を引かない）。安定なソートなので、同じ番号の組の間は優先度の順のまま
        val queues = byGroup.entries.map { seen(it.key).stamp to it.value }.sortedBy { it.first }.map { it.second }
        val order = ArrayList<Delivery<P>>(pending.size)
        while (order.size < pending.size) {
            for (q in queues) q.removeFirstOrNull()?.let { order += it }
        }
        return order
    }

    // 今の tick に [group] を積んだことを記録し、その巡回の位置を返す
    private fun seen(group: FairShareGroup): Rotation {
        val r = rotation.getOrPut(group) { Rotation(serveCount, tickCount) }
        r.lastSeen = tickCount
        return r
    }

    // 組の予算の残りに [bytes] が収まるか（組ごとの上限がなければ、いつも収まる）
    private fun fitsGroup(group: FairShareGroup, bytes: Int): Boolean =
        groupBytesPerSecond == 0 || fits(groupBudgets[group] ?: groupBytesPerSecond.toLong(), groupBytesPerSecond, bytes)

    private fun chargeGroup(group: FairShareGroup, bytes: Int) {
        if (groupBytesPerSecond == 0) return
        groupBudgets[group] = (groupBudgets[group] ?: groupBytesPerSecond.toLong()) - bytes
    }

    /** [player] の勘定を消す（ログアウトしたときなど）。積んだ、まだ送っていないメッセージも捨てる。 */
    public fun forget(player: P) {
        counters.remove(player)
        if (pending.isNotEmpty()) pending.removeIf { it.player == player }
    }

    /** すべての勘定と、積んだメッセージを消す。予算は満タンに戻す。 */
    public fun clear() {
        counters.clear()
        pending.clear()
        groupBudgets.clear()
        rotation.clear()
        totalBytes = 0L
        totalThrottled = 0L
        serverBudget = serverBytesPerSecond.toLong()
    }

    /**
     * いま [player] に、公平の組 [group] の画面から [bytes] バイトのキーフレームを送ってみる余裕があるか（[endTick] で送れる見込みがあるか）。
     * 組の予算も見る（見ないと、組の予算で断られるキーフレームを毎回試して空振りし続ける。#738）
     */
    internal fun hasBudget(player: P, bytes: Int, group: FairShareGroup = FairShareGroup.NONE): Boolean =
        fits(remainingBudget(player), playerBytesPerSecond, bytes) && fitsGroup(group, bytes) &&
            fits(serverBudget, serverBytesPerSecond, bytes)

    // 残り [remaining] に [bytes] が収まるか。1秒分 [perSecond] より大きいものは、満タンなら収まるものとする
    private fun fits(remaining: Long, perSecond: Int, bytes: Int): Boolean =
        remaining >= bytes || remaining >= perSecond

    /** [watchers] の画面から [player] に送る、この tick のメッセージ [fragments] を積む。 */
    internal fun enqueue(
        watchers: ScreenWatchers<P>,
        host: ScreenSyncHost<P>,
        player: P,
        fragments: List<ByteArray>,
        keyframe: Boolean,
        distanceSquared: Double,
    ) {
        var bytes = 0
        for (f in fragments) bytes += f.size
        val distance = if (distanceSquared.isFinite()) Math.sqrt(maxOf(0.0, distanceSquared)) else Double.MAX_VALUE
        pending += Delivery(watchers, host, player, host.fairShareGroup(), fragments, bytes, keyframe, (distance + 1.0) * bytes)
    }

    private fun counter(player: P): Counter = counters.getOrPut(player) { Counter() }

    public companion object {
        /** プレイヤーごとの送信量の上限の既定値（1 MiB/秒。画面1つの上限と同じ）。仮の値（確定は #251）。 */
        public const val DEFAULT_PLAYER_BYTES_PER_SECOND: Int = 1 shl 20

        /** サーバー全体の送信量の上限の既定値（8 MiB/秒）。仮の値（確定は #251）。 */
        public const val DEFAULT_SERVER_BYTES_PER_SECOND: Int = 8 shl 20

        /**
         * 公平の組（持ち主）ごとの送信量の上限の既定値（0 = 上限なし。#738）。仮の値（確定は #251）。
         * 組の間の公平は [endTick] の巡回で取るので、既定では上限を置かない（持ち主が1人のサーバーで、見る人が多いときに
         * サーバー全体の予算が空いたまま遅くならないように）
         */
        public const val DEFAULT_GROUP_BYTES_PER_SECOND: Int = 0

        /**
         * 積まなくなった組の巡回の位置を残す tick 数（#896）。送れなかった画面がキーフレームを試し直す間隔
         * （[ScreenWatchers.DEFAULT_THROTTLE_RETRY_TICKS]）より十分長くする。短いと、試し直しまで積まなかった組が戻ったときに
         * 位置を失い、間を置いて積むほかの組と同じ扱いになって、優先度で負け続ける。仮の値
         */
        internal const val ROTATION_RETAIN_TICKS: Int = ScreenWatchers.DEFAULT_RESYNC_INTERVAL_TICKS * 2

        private val BY_PRIORITY: Comparator<Delivery<*>> = Comparator { a, b -> a.priority.compareTo(b.priority) }
    }
}
