package pro.ikili.kibicomputers.core.device

import pro.ikili.kibicomputers.core.machine.isFatal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * デバイスのメソッドが投げた・`null` の値を返した（internal error にした）ことのログ（#679、ADR 0065 の「追記（#679）」）。
 * スケジューラに1つ（マシンごとではない。同じ種類の壊れたデバイスが多数の台につながっても、WARNING は増えない）。
 *
 * - 失敗は「デバイスの種類・メソッド・失敗の種類（例外のクラス名か、`null` の値）」をキーにまとめる（スタックもメッセージも読まない）
 * - キーごとに最初の1回だけ WARNING（例外ならスタックトレースつき）。2回目からは数えるだけで、台は DEBUG に出す
 * - 覚えるキーは [keyLimit] 個まで。超えたら、そのことを書いた WARNING を1回だけ出し、後は「上限の後」としてまとめて数える
 * - 数えた繰り返しは、最初の繰り返しから [summaryIntervalTicks] tick が過ぎたら、要約の WARNING 1本に出して数え直す
 *   （失敗したときと [tick] の頭で確かめる。どちらも O(1)）
 *
 * どのスレッドから呼んでもよい（直接のメソッドは呼んだスレッドで、メインスレッドのメソッドは `tick()` の中で失敗する）。
 * ログに出す処理は投げない（JVM の致命的なエラーだけ投げ直す）。他 MOD のコード（例外の `toString` など）は、ログに出すときだけ try の中で呼ぶ
 */
internal class DeviceFailureLog(
    private val summaryIntervalTicks: Long = SUMMARY_INTERVAL_TICKS,
    private val keyLimit: Int = KEY_LIMIT,
    private val log: System.Logger = LOG,
) {
    private data class Key(val kind: String, val method: String, val failure: String)

    /** キーごとの、前の要約からの繰り返しの数 */
    private val repeats = ConcurrentHashMap<Key, AtomicLong>()
    private val keyCount = AtomicInteger()

    /** キーが上限に達した後の失敗の数（前の要約から） */
    private val overLimit = AtomicLong()
    private val limitWarned = AtomicBoolean()

    /** まだ要約に出していない繰り返しの、最初の tick（[NONE] = ない） */
    private val pendingSince = AtomicLong(NONE)

    /**
     * マシン [machineId] のデバイス [address]（種類 [kind]）のメソッド [method] が [t] を投げた（`null` = `null` の値を返した）。
     * [now] は今の tick の番号
     */
    fun report(machineId: Long, address: String, kind: String, method: String, t: Throwable?, now: Long) {
        try {
            val key = Key(kind, method, t?.javaClass?.name ?: NULL_VALUE)
            val counter = repeats[key]
            when {
                counter != null -> {
                    counter.incrementAndGet()
                    repeated(now)
                    debug(machineId, address, kind, method, t)
                }
                keyCount.get() >= keyLimit -> {
                    overLimit.incrementAndGet()
                    repeated(now)
                    if (limitWarned.compareAndSet(false, true)) {
                        log.log(
                            System.Logger.Level.WARNING,
                            "デバイスのメソッドの失敗: デバイスの種類・メソッド・失敗の種類の組が上限（$keyLimit 個）に達した。" +
                                "新しい組の失敗は WARNING に出さず、数だけを要約に出す（台は DEBUG に出る）",
                        )
                    }
                    debug(machineId, address, kind, method, t)
                }
                repeats.putIfAbsent(key, AtomicLong()) == null -> {
                    keyCount.incrementAndGet()
                    first(machineId, address, kind, method, t)
                }
                else -> {
                    // 同時に同じキーで初めて失敗した。もう一方が WARNING を出す
                    repeats[key]?.incrementAndGet()
                    repeated(now)
                    debug(machineId, address, kind, method, t)
                }
            }
            tick(now)
        } catch (e: Throwable) {
            if (isFatal(e)) throw e
        }
    }

    /** 要約を出す時が来ていたら出す（`MachineScheduler.tick()` の頭で呼ぶ。O(1)）。投げない（JVM の致命的なエラーを除く） */
    fun tick(now: Long) {
        val since = pendingSince.get()
        if (since == NONE || now - since < summaryIntervalTicks) return
        if (!pendingSince.compareAndSet(since, NONE)) return
        try {
            flush()
        } catch (e: Throwable) {
            if (isFatal(e)) throw e
        }
    }

    private fun repeated(now: Long) {
        pendingSince.compareAndSet(NONE, now)
    }

    private fun first(machineId: Long, address: String, kind: String, method: String, t: Throwable?) {
        val what = if (t == null) "null の値を返した" else "例外を投げた"
        val msg = "マシン $machineId のデバイス $address（種類 $kind）のメソッド $method が${what}（internal error を返した）。" +
            "同じデバイスの種類・メソッド・失敗の種類の繰り返しは DEBUG に出し、数は要約に出す"
        if (t == null) log.log(System.Logger.Level.WARNING, msg) else log.log(System.Logger.Level.WARNING, msg, t)
    }

    private fun debug(machineId: Long, address: String, kind: String, method: String, t: Throwable?) {
        if (!log.isLoggable(System.Logger.Level.DEBUG)) return
        val what = if (t == null) "null の値を返した" else "例外を投げた: $t"
        log.log(System.Logger.Level.DEBUG, "マシン $machineId のデバイス $address（種類 $kind）のメソッド $method が$what")
    }

    /** 数えた繰り返しを WARNING 1本に出して数え直す（なければ何も出さない） */
    private fun flush() {
        val parts = ArrayList<String>()
        var total = 0L
        for ((key, counter) in repeats) {
            val n = counter.getAndSet(0)
            if (n <= 0) continue
            total += n
            val failure = if (key.failure == NULL_VALUE) "null の値" else key.failure
            parts += "種類 ${key.kind} のメソッド ${key.method}（$failure）$n 回"
        }
        val over = overLimit.getAndSet(0)
        if (over > 0) {
            total += over
            parts += "組の数が上限に達した後の失敗 $over 回"
        }
        if (total <= 0) return
        log.log(
            System.Logger.Level.WARNING,
            "デバイスのメソッドの失敗: 前の数の報告から、WARNING を省いた失敗（internal error）が $total 回あった" +
                "（台は DEBUG に出る）。内訳: ${parts.joinToString("、")}",
        )
    }

    companion object {
        private val LOG: System.Logger = System.getLogger("kibicomputers.device")

        /** 要約の間（1分 = 1,200 tick。運用の値。`RestoreQueue` の要約と同じ間） */
        const val SUMMARY_INTERVAL_TICKS: Long = 1200

        /** 覚えるキーの数（`MachineScheduler.HOST_FAILURE_TRACE_LIMIT` と同じ） */
        const val KEY_LIMIT: Int = 64

        private const val NONE = Long.MIN_VALUE

        /** `null` の値を返した失敗のキー（クラス名にならない文字列） */
        private const val NULL_VALUE = "<null value>"
    }
}
