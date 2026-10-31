package pro.ikili.kibicomputers.core.kernel

/**
 * マシンの燃料の遅延補充と受け渡し（ADR 0028 をカーネルに載せたもの。ADR 0043）。カーネルロックの中で使う。
 *
 * 燃料の正は [KernelState.fuel]。規則は「tick ごとに `fuel = min(fuel, 0) + fuelPerTick`」だが、
 * メインスレッドで全マシンに配ることはせず、最後に補充した tick（[lastRefillTick]）から進んだ数だけを、
 * k 回くり返すのと同じ値になる閉じた式でまとめて当てはめる。
 *
 * マシンの燃料を読み書きするのは CPU を持つプロセスだけ（CPU は同時に1プロセス）。
 * CPU を持っている間の [KernelState.fuel] は 0 で、[lastRefillTick] は手持ちを渡した tick のまま。
 */
internal class FuelAccount(private val state: KernelState, val fuelPerTick: Long, initialTick: Long) {
    init {
        require(fuelPerTick > 0) { "fuelPerTick は正" }
    }

    /** 最後に補充した tick（実行時だけ。復元のときは今の tick にする） */
    var lastRefillTick: Long = initialTick
        private set

    /** 今の tick [now] まで補充する */
    fun refill(now: Long) {
        val k = now - lastRefillTick
        if (k <= 0) return
        state.fuel = refilled(state.fuel, k, fuelPerTick)
        lastRefillTick = now
    }

    /** [refill] したらいくつになるか（書き換えない。ディスパッチャーが燃料待ちを起こすかを決めるのに使う） */
    fun estimate(now: Long): Long = refilled(state.fuel, now - lastRefillTick, fuelPerTick)

    /** CPU を取る: 補充してから、燃料が正なら全部を渡して 0 にし、渡した量を返す。正でなければ 0 を返して何もしない */
    fun take(now: Long): Long {
        refill(now)
        val fuel = state.fuel
        if (fuel <= 0) return 0
        state.fuel = 0
        return fuel
    }

    /**
     * CPU を返す: まず今の tick まで補充し、残り [remaining] を足す。
     * 手持ちを渡してから tick が進んでいなければ（k = 0）そのまま、進んでいれば（k ≥ 1）`min(r, 0)` だけを足す。
     */
    fun giveBack(now: Long, remaining: Long) {
        val k = now - lastRefillTick
        refill(now)
        state.fuel += if (k <= 0) remaining else minOf(remaining, 0)
    }

    /** 例外で抜けたとき: CPU を持っていたときだけ補充する（何も書き戻さない） */
    fun onException(now: Long, holdsCpu: Boolean) {
        if (holdsCpu) refill(now)
    }

    companion object {
        /** [fuel] に「tick ごとに `min(fuel, 0) + perTick`」を [k] 回当てはめた値 */
        fun refilled(fuel: Long, k: Long, perTick: Long): Long {
            if (k <= 0) return fuel
            if (fuel >= 0) return perTick
            // 負の間は perTick ずつ増え、0 以上になった次の tick からは perTick
            val ticksToNonNegative = (-fuel + perTick - 1) / perTick // fuel + n·perTick >= 0 になる最小の n
            return if (k <= ticksToNonNegative) fuel + k * perTick else perTick
        }
    }
}
