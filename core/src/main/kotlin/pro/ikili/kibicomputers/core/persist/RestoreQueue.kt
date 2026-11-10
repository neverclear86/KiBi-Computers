package pro.ikili.kibicomputers.core.persist

import org.jetbrains.annotations.ApiStatus
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 復元を始める順番待ち（#85、ADR 0085）。チャンクの読み込みで電源が入っていたケースを積み、tick ごとに
 * [MachineSaveConfig.restoresPerTick] 台まで始める。その ID の書き込みが終わっていない台は、終わるまで（上限
 * [MachineSaveConfig.restoreWriteWaitMillis]）後回しにし、超えたら冷えた起動として始める（ADR 0084 の規則 9）。
 * 書き込みの終わりは待たずに確かめる（[MachineSaveStore.awaitWrites] に 0 を渡す）ので、メインスレッドを止めない。
 *
 * スレッド: メインスレッドだけで使う。
 *
 * @param T 積むもの（接続層のブロックエンティティ）
 */
@ApiStatus.Internal
public class RestoreQueue<T : Any> {
    /** 始める（[tick] から） */
    public fun interface Starter<T> {
        /** [target] の復元を始める。[writesTimedOut] なら、書き込みが期限までに終わらなかった（冷えた起動にする） */
        public fun start(target: T, writesTimedOut: Boolean)
    }

    private class Entry(val id: UUID?, val since: Long)

    private val entries = LinkedHashMap<T, Entry>()

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

    /** すべて外す */
    public fun clear() {
        entries.clear()
    }

    /**
     * 積んだ順に、[config] の台数まで [starter] で始める。[store] があれば、その ID の書き込みが終わっていない台は後回しにする
     * （期限を過ぎたら `writesTimedOut` で始める）。
     *
     * @return 始めた台数
     */
    public fun tick(nowNanos: Long, store: MachineSaveStore?, config: MachineSaveConfig, starter: Starter<T>): Int {
        if (entries.isEmpty()) return 0
        val waitNanos = TimeUnit.MILLISECONDS.toNanos(config.restoreWriteWaitMillis)
        var started = 0
        val it = entries.entries.iterator()
        val ready = ArrayList<Pair<T, Boolean>>()
        while (it.hasNext() && started < config.restoresPerTick) {
            val (target, entry) = it.next()
            val id = entry.id
            var timedOut = false
            if (id != null && store != null && !store.awaitWrites(id, 0)) {
                if (nowNanos - entry.since < waitNanos) continue
                timedOut = true
            }
            it.remove()
            ready += target to timedOut
            started++
        }
        // 始める処理が積み直しても（同じ tick の中）、回っている列を壊さないように、外してから呼ぶ
        for ((target, timedOut) in ready) starter.start(target, timedOut)
        return started
    }
}
