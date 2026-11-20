package pro.ikili.kibicomputers.core.persist

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.core.kernel.MachineScheduler
import pro.ikili.kibicomputers.core.kernel.hostFailureSite
import pro.ikili.kibicomputers.core.machine.isFatal
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 復元を始める順番待ち（#85、ADR 0085）。チャンクの読み込みで電源が入っていたケースを積み、tick ごとに
 * [MachineSaveConfig.restoresPerTick] 台まで始める。その ID の書き込みが終わっていない台は、終わるまで（上限
 * [MachineSaveConfig.restoreWriteWaitMillis]）後回しにし、超えたら冷えた起動として始める（ADR 0084 の規則 9）。
 * 書き込みの終わりは待たずに確かめる（[MachineSaveStore.awaitRestorable] に 0 を渡す。`owner.bin` だけの書き込みは待たない。#585）ので、メインスレッドを止めない。
 *
 * 始める処理（[Starter]。接続層）が投げても、ほかの台の開始は続ける（#785。ADR 0044 と同じ考え方）。投げた台は積み直さず、
 * 例外はログに出して捨てる。ログは列全体で、投げた場所ごとに最初の1回だけ WARNING とスタックトレースにし、同じ場所の繰り返しは
 * DEBUG にする（1,000 台が同じ不具合で投げてもログを埋めない）。台を書いた WARNING を省いた台があれば、投げた台数と省いた内訳を数えた
 * WARNING を足す（既定のログレベルでも、何台が残ったか分かる）。数の WARNING は時間で間引く（最初の1本はすぐ、以後は
 * [SUMMARY_INTERVAL_NANOS] に1本まで。間の分は足し合わせて次の1本に書く）ので、本数は台数にも tick ごとの台数の上限にも比例しない。
 * どの台かは [describe] で書く（位置・ディメンション・ID）。JVM の致命的なエラー（[isFatal]）は捨てずに投げ、まだ始めていない台は
 * 列に戻して次の tick に回す。
 *
 * スレッド: メインスレッドだけで使う。
 *
 * @param T 積むもの（接続層のブロックエンティティ）
 * @param describe ログに出す台の書き方（位置・ディメンション・ID など。既定は `toString`）。投げたら `toString` に戻す
 */
@ApiStatus.Internal
public class RestoreQueue<T : Any>(private val describe: (T) -> String = { it.toString() }) {
    /** 始める（[tick] から） */
    public fun interface Starter<T> {
        /** [target] の復元を始める。[writesTimedOut] なら、書き込みが期限までに終わらなかった（冷えた起動にする） */
        public fun start(target: T, writesTimedOut: Boolean)
    }

    private class Entry(val id: UUID?, val since: Long)

    private class Ready<T>(val target: T, val entry: Entry, val timedOut: Boolean)

    private val entries = LinkedHashMap<T, Entry>()

    /** スタックトレースを出した、開始の例外の投げた場所（[hostFailureSite]。[clear] で消す。#785） */
    private val tracedSites = HashSet<List<Any>>()

    /** 場所の数が上限に達したことを WARNING で知らせたか（[clear] で戻す） */
    private var traceLimitReported = false

    /** まだ数の WARNING に書いていない、開始が投げた台の数（[reportFailureSummary]） */
    private val pending = Failures()

    /** 最後に数の WARNING を出した時刻（[tick] の `nowNanos`）。まだ出していなければ `null`（[clear] で戻す） */
    private var lastSummaryNanos: Long? = null

    /** 待っている台数 */
    public val size: Int get() = entries.size

    /** [target] を積む（もう積んであれば何もしない）。[id] はその ID（なければ `null`。書き込みを待たない） */
    public fun offer(target: T, id: UUID?, nowNanos: Long) {
        entries.putIfAbsent(target, Entry(id, nowNanos))
    }

    /** [target] を外す（アンロード・電源を切った・壊したなど）。積んであれば `true` */
    public fun remove(target: T): Boolean = entries.remove(target) != null

    /** [target] を積んでいるか */
    public operator fun contains(target: T): Boolean = target in entries

    /**
     * すべて外す。ログの間引きの記録も消す（サーバーの起動ごとに、最初の WARNING がまた出る）。まだ書いていない数の WARNING は、
     * 間を待たずにここで出す
     */
    public fun clear() {
        flushSummary()
        lastSummaryNanos = null
        entries.clear()
        tracedSites.clear()
        traceLimitReported = false
    }

    /**
     * 積んだ順に、[config] の台数まで [starter] で始める。[store] があれば、その ID の書き込みが終わっていない台は後回しにする
     * （期限を過ぎたら `writesTimedOut` で始める）。
     *
     * [starter] が投げても、残りの台は始める。投げた台は列から外したまま積み直さない（例外はログに出して捨てる。#785）。
     * ただし JVM の致命的なエラー（[isFatal]）は、まだ始めていない台を列に戻してから投げる（積んだ時刻は元のまま）。
     *
     * @return 始めた台数（[starter] が投げた台も含む。その tick の上限の枠は使ったため）
     */
    public fun tick(nowNanos: Long, store: MachineSaveStore?, config: MachineSaveConfig, starter: Starter<T>): Int {
        if (entries.isEmpty()) {
            // 列が空でも、間引いて残した数の WARNING は間が過ぎたら出す
            if (pending.omitted > 0) reportFailureSummary(nowNanos)
            return 0
        }
        val waitNanos = TimeUnit.MILLISECONDS.toNanos(config.restoreWriteWaitMillis)
        var started = 0
        val it = entries.entries.iterator()
        val ready = ArrayList<Ready<T>>()
        while (it.hasNext() && started < config.restoresPerTick) {
            val (target, entry) = it.next()
            val id = entry.id
            var timedOut = false
            if (id != null && store != null && !store.awaitRestorable(id, 0)) {
                if (nowNanos - entry.since < waitNanos) continue
                timedOut = true
            }
            it.remove()
            ready += Ready(target, entry, timedOut)
            started++
        }
        // 始める処理が積み直しても（同じ tick の中）、回っている列を壊さないように、外してから呼ぶ
        var next = 0
        try {
            while (next < ready.size) {
                val r = ready[next++]
                try {
                    starter.start(r.target, r.timedOut)
                } catch (t: Throwable) {
                    if (isFatal(t)) throw t
                    pending.add(reportStartFailure(r.target, t))
                }
            }
            reportFailureSummary(nowNanos)
        } catch (t: Throwable) {
            // ここまで来るのは致命的なエラーだけ: まだ始めていない台を次の tick に回す（投げた台は途中まで始めたので戻さない。
            // ループの中で積み直された台は上書きしない）
            for (j in next until ready.size) entries.putIfAbsent(ready[j].target, ready[j].entry)
            reportFailureSummary(nowNanos)
            throw t
        }
        return started
    }

    /** 開始が投げた台をログにどう出したか（[reportStartFailure]） */
    private enum class Logged {
        /** 台と例外を書いた WARNING を出した */
        DETAILED,

        /** 既に WARNING を出した場所の繰り返し（台は DEBUG） */
        REPEATED,

        /** 場所の数が上限に達した後の新しい場所（台は DEBUG） */
        OVER_LIMIT,

        /** ログに出す処理が投げた（台はどこにも出ていない） */
        UNLOGGED,
    }

    /** 開始が投げた台数と、ログへの出し方ごとの内訳（前の数の WARNING から足し合わせる） */
    private class Failures {
        var count = 0
        var detailed = 0
        var repeated = 0
        var overLimit = 0
        var unlogged = 0

        /** 台を書いた WARNING を省いた台数 */
        val omitted: Int get() = count - detailed

        fun add(logged: Logged) {
            count++
            when (logged) {
                Logged.DETAILED -> detailed++
                Logged.REPEATED -> repeated++
                Logged.OVER_LIMIT -> overLimit++
                Logged.UNLOGGED -> unlogged++
            }
        }

        fun reset() {
            count = 0
            detailed = 0
            repeated = 0
            overLimit = 0
            unlogged = 0
        }
    }

    /** [target] の書き方（[describe]。投げたら `toString`、それも投げたらクラス名） */
    private fun nameOf(target: T): String {
        try {
            return describe(target)
        } catch (e: Throwable) {
            if (isFatal(e)) throw e
        }
        return try {
            target.toString()
        } catch (e: Throwable) {
            if (isFatal(e)) throw e
            target.javaClass.name
        }
    }

    /**
     * 台を書いた WARNING を省いた台があれば、投げた台数とその内訳の WARNING を出す（既定のログレベルでも、何台が残ったか分かるように。
     * #785）。時間で間引く: まだ1本も出していなければすぐ出し、以後は前の1本から [SUMMARY_INTERVAL_NANOS] が過ぎるまで足し合わせて待つ。
     * 台の特定は、場所ごとの最初の WARNING と DEBUG が受け持つ。
     */
    private fun reportFailureSummary(nowNanos: Long) {
        if (pending.omitted <= 0) return
        val last = lastSummaryNanos
        if (last != null && nowNanos - last < SUMMARY_INTERVAL_NANOS) return
        lastSummaryNanos = nowNanos
        flushSummary()
    }

    /** まだ書いていない数を WARNING に出して、数え直す（省いた台がなければ何もしない） */
    private fun flushSummary() {
        val f = pending
        if (f.omitted <= 0) {
            f.reset()
            return
        }
        try {
            val parts = ArrayList<String>(3)
            if (f.repeated > 0) parts += "既に WARNING を出した場所の繰り返し ${f.repeated} 台"
            if (f.overLimit > 0) parts += "場所の数が上限に達した後の新しい場所 ${f.overLimit} 台"
            val debug = if (parts.isEmpty()) "" else parts.joinToString("、") + "（台は DEBUG に出る）"
            val unlogged = if (f.unlogged > 0) "ログに出す処理が投げて台を書けなかったもの ${f.unlogged} 台" else ""
            val detail = listOf(debug, unlogged).filter { it.isNotEmpty() }.joinToString("、")
            LOG.log(
                System.Logger.Level.WARNING,
                "順番待ちの復元: 前の数の報告から（初回はサーバーの開始から）${f.count} 台の開始が例外を投げた（積み直さない）。" +
                    "台と例外を書いた WARNING は ${f.detailed} 件。省いた ${f.omitted} 台の内訳: $detail",
            )
        } catch (e: Throwable) {
            if (isFatal(e)) throw e
        } finally {
            f.reset()
        }
    }

    /**
     * [starter][Starter] が [target] で投げた [t] をログに出す（投げた場所ごとに最初の1回だけ WARNING。#785）。
     *
     * @return ログへの出し方
     */
    private fun reportStartFailure(target: T, t: Throwable): Logged {
        try {
            val name = nameOf(target)
            val site = hostFailureSite(SITE_KIND, t, MachineScheduler.HOST_FAILURE_FRAMES)
            if (site in tracedSites) {
                if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
                    LOG.log(System.Logger.Level.DEBUG, "順番待ちの復元の開始がまた例外を投げた（捨てて続ける）: $name: $t")
                }
                return Logged.REPEATED
            }
            if (tracedSites.size < MachineScheduler.HOST_FAILURE_TRACE_LIMIT) {
                tracedSites += site
                val message = "順番待ちの復元の開始が例外を投げた（その台は積み直さず、ほかの台は続ける。同じ場所の繰り返しは DEBUG）: $name: $t"
                LOG.log(System.Logger.Level.WARNING, message, t)
                return Logged.DETAILED
            } else if (!traceLimitReported) {
                traceLimitReported = true
                val message = "順番待ちの復元の開始が例外を投げた（その台は積み直さず、ほかの台は続ける。スタックトレースを出した場所の数が" +
                    "上限に達したので、スタックトレースは省き、以後の新しい場所は DEBUG）: $name: $t"
                LOG.log(System.Logger.Level.WARNING, message)
                return Logged.DETAILED
            } else if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
                LOG.log(System.Logger.Level.DEBUG, "順番待ちの復元の開始が別の場所で例外を投げた（捨てて続ける）: $name: $t")
            }
            return Logged.OVER_LIMIT
        } catch (e: Throwable) {
            // ログに出せない（例外の toString が投げたなど）: 捨てる。致命的なエラーは投げる
            if (isFatal(e)) throw e
            return Logged.UNLOGGED
        }
    }

    private companion object {
        private val LOG: System.Logger = System.getLogger("kibicomputers.persist")

        /** 投げた場所のキーの種類（[hostFailureSite]） */
        private const val SITE_KIND = "restore-start"

        /** 数の WARNING の間（[reportFailureSummary]）。1分に1本まで */
        private val SUMMARY_INTERVAL_NANOS: Long = TimeUnit.MINUTES.toNanos(1)
    }
}
