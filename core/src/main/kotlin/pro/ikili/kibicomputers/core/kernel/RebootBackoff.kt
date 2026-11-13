package pro.ikili.kibicomputers.core.kernel

/**
 * ゲストの `reboot` で起動し直すまでの待ちを決める（#635、ADR 0635）。純粋な計算で、時計もロックも持たない。
 *
 * マシンごとに実行時だけの「借り」（tick）を持つ。再起動のたびに借りが [decayTicks] 増え、その起動で init が動いた tick 数だけ減る
 * （0 より下にも、[capLevels] 段より上にもならない）。段（`借り / decayTicks` の切り上げ）が [freeReboots] 以下なら待たない。
 * それより上は [baseDelayTicks] から倍々に増やし、[maxDelayTicks] で頭打ちにする。
 *
 * どれだけ動いてから再起動しても、N 回の再起動にかかる tick（動いた時間と待ちの和）は `(N - capLevels) * decayTicks` 以上になる
 * （長い目で見た起動の率は、1台あたり [decayTicks] に1回以下）。ただし [maxDelayTicks] が [decayTicks] 以上で、
 * [capLevels] 段の待ちが [maxDelayTicks] に届くときに限る（[DEFAULT] はそうしてある）。
 */
internal class RebootBackoff(
    /** 待たずに起動し直す段の数 */
    val freeReboots: Int,
    /** 最初に待つ段の待ち（tick） */
    val baseDelayTicks: Long,
    /** 待ちの上限（tick） */
    val maxDelayTicks: Long,
    /** 再起動1回で増える借り。動いた tick 数だけ減るので、この長さ動くと1段戻る */
    val decayTicks: Long,
    /** 借りの上限（段） */
    val capLevels: Int,
) {
    init {
        require(freeReboots >= 0) { "freeReboots は 0 以上" }
        require(baseDelayTicks >= 0) { "baseDelayTicks は 0 以上" }
        require(maxDelayTicks >= 0) { "maxDelayTicks は 0 以上" }
        require(decayTicks >= 1) { "decayTicks は 1 以上" }
        require(capLevels >= 1) { "capLevels は 1 以上" }
    }

    /** 1回の再起動の結果。[debtTicks] は新しい借り、[delayTicks] は起動し直すまでの待ち（0 なら待たない） */
    class Step(val debtTicks: Long, val delayTicks: Long)

    /** 借りが [debtTicks] のマシンが、init を [ranTicks] tick 動かしてから再起動するときの、新しい借りと待ち */
    fun next(debtTicks: Long, ranTicks: Long): Step {
        val cap = capLevels.toLong() * decayTicks
        val paid = (debtTicks - ranTicks.coerceAtLeast(0)).coerceAtLeast(0)
        val debt = (paid + decayTicks).coerceAtMost(cap)
        // 段は切り上げ（少しでも動けば段が1つ下がる、とはしない。数 tick だけ動いて再起動する init も、すぐ再起動するものと同じ段にする）
        return Step(debt, delayFor((debt + decayTicks - 1) / decayTicks))
    }

    /** 段 [level] の待ち */
    private fun delayFor(level: Long): Long {
        if (level <= freeReboots) return 0
        val shift = level - freeReboots - 1
        // 桁あふれしないように、上限に届いたら打ち切る
        var delay = baseDelayTicks
        var i = 0L
        while (i < shift && delay < maxDelayTicks) {
            delay = delay shl 1
            i++
        }
        return delay.coerceAtMost(maxDelayTicks)
    }

    companion object {
        /**
         * 既定の値（どれも仮の値。確定は #251）: 3回までは待たず、そこから 1 秒（20 tick）、2 秒…と倍々にして 60 秒（1,200 tick）で頭打ち。
         * 1 分動くと1段戻る。1台あたりの長い目で見た起動の率は 60 秒に1回以下（1,000 台で毎秒およそ 17 回以下）
         */
        val DEFAULT: RebootBackoff = RebootBackoff(
            freeReboots = 3,
            baseDelayTicks = 20,
            maxDelayTicks = 1200,
            decayTicks = 1200,
            capLevels = 11,
        )

        /** 待たない（テスト用） */
        val NONE: RebootBackoff = RebootBackoff(freeReboots = Int.MAX_VALUE, baseDelayTicks = 0, maxDelayTicks = 0, decayTicks = 1, capLevels = 1)
    }
}
