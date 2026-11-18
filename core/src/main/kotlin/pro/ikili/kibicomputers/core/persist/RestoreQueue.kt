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
 * 書き込みの終わりは待たずに確かめる（[MachineSaveStore.awaitWrites] に 0 を渡す）ので、メインスレッドを止めない。
 *
 * 始める処理（[Starter]。接続層）が投げても、ほかの台の開始は続ける（#785。ADR 0044 と同じ考え方）。投げた台は積み直さず、
 * 例外はログに出して捨てる。ログは列全体で、投げた場所ごとに最初の1回だけ WARNING とスタックトレースにし、同じ場所の繰り返しは
 * DEBUG にする（1,000 台が同じ不具合で投げてもログを埋めない）。繰り返しを省いた tick には、投げた台数を数えた WARNING を1本足す
 * （既定のログレベルでも、何台が残ったか分かる）。どの台かは [describe] で書く（位置・ディメンション・ID）。JVM の致命的なエラー（[isFatal]）は捨てずに投げ、まだ始めていない台は
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

    /** すべて外す。ログの間引きの記録も消す（サーバーの起動ごとに、最初の WARNING がまた出る） */
    public fun clear() {
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
        if (entries.isEmpty()) return 0
        val waitNanos = TimeUnit.MILLISECONDS.toNanos(config.restoreWriteWaitMillis)
        var started = 0
        val it = entries.entries.iterator()
        val ready = ArrayList<Ready<T>>()
        val failures = Failures()
        while (it.hasNext() && started < config.restoresPerTick) {
            val (target, entry) = it.next()
            val id = entry.id
            var timedOut = false
            if (id != null && store != null && !store.awaitWrites(id, 0)) {
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
                    failures.count++
                    if (reportStartFailure(r.target, t)) failures.detailed++
                }
            }
            reportFailureSummary(failures)
        } catch (t: Throwable) {
            // ここまで来るのは致命的なエラーだけ: まだ始めていない台を次の tick に回す（投げた台は途中まで始めたので戻さない。
            // ループの中で積み直された台は上書きしない）
            for (j in next until ready.size) entries.putIfAbsent(ready[j].target, ready[j].entry)
            reportFailureSummary(failures)
            throw t
        }
        return started
    }

    /** 1回の [tick] で、開始が投げた台数と、そのうち台を書いた WARNING を出した数 */
    private class Failures {
        var count = 0
        var detailed = 0
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
     * 繰り返しを省いた分がある tick に、投げた台数の WARNING を1本出す（既定のログレベルでも、何台が残ったか分かるように。#785）。
     * 台の特定は、場所ごとの最初の WARNING と DEBUG が受け持つ。
     */
    private fun reportFailureSummary(failures: Failures) {
        if (failures.count <= failures.detailed) return
        try {
            LOG.log(
                System.Logger.Level.WARNING,
                "順番待ちの復元: この tick で ${failures.count} 台の開始が例外を投げた（積み直さない）。" +
                    "台と例外を書いた WARNING は ${failures.detailed} 件で、残りは同じ場所の繰り返しなので省いた（台は DEBUG に出る）",
            )
        } catch (e: Throwable) {
            if (isFatal(e)) throw e
        }
    }

    /**
     * [starter][Starter] が [target] で投げた [t] をログに出す（投げた場所ごとに最初の1回だけ WARNING。#785）。
     *
     * @return 台と例外を書いた WARNING を出したか
     */
    private fun reportStartFailure(target: T, t: Throwable): Boolean {
        try {
            val name = nameOf(target)
            val site = hostFailureSite(SITE_KIND, t, MachineScheduler.HOST_FAILURE_FRAMES)
            if (site in tracedSites) {
                if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
                    LOG.log(System.Logger.Level.DEBUG, "順番待ちの復元の開始がまた例外を投げた（捨てて続ける）: $name: $t")
                }
                return false
            }
            if (tracedSites.size < MachineScheduler.HOST_FAILURE_TRACE_LIMIT) {
                tracedSites += site
                val message = "順番待ちの復元の開始が例外を投げた（その台は積み直さず、ほかの台は続ける。同じ場所の繰り返しは DEBUG）: $name: $t"
                LOG.log(System.Logger.Level.WARNING, message, t)
                return true
            } else if (!traceLimitReported) {
                traceLimitReported = true
                val message = "順番待ちの復元の開始が例外を投げた（その台は積み直さず、ほかの台は続ける。スタックトレースを出した場所の数が" +
                    "上限に達したので、スタックトレースは省き、以後の新しい場所は DEBUG）: $name: $t"
                LOG.log(System.Logger.Level.WARNING, message)
                return true
            } else if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
                LOG.log(System.Logger.Level.DEBUG, "順番待ちの復元の開始が別の場所で例外を投げた（捨てて続ける）: $name: $t")
            }
            return false
        } catch (e: Throwable) {
            // ログに出せない（例外の toString が投げたなど）: 捨てる。致命的なエラーは投げる
            if (isFatal(e)) throw e
            return false
        }
    }

    private companion object {
        private val LOG: System.Logger = System.getLogger("kibicomputers.persist")

        /** 投げた場所のキーの種類（[hostFailureSite]） */
        private const val SITE_KIND = "restore-start"
    }
}
