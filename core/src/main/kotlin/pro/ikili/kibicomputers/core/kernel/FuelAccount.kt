package pro.ikili.kibicomputers.core.kernel

/**
 * マシンの燃料の遅延補充と受け渡し（ADR 0028 をカーネルに載せたもの。ADR 0043）。カーネルロックの中で使う。
 *
 * 燃料の正は [KernelState.fuel]。規則は「tick ごとに `fuel = min(fuel, 0) + fuelPerTick`」だが、
 * メインスレッドで全マシンに配ることはせず、最後に補充した tick（[lastRefillTick]）から進んだ数だけを、
 * k 回くり返すのと同じ値になる閉じた式でまとめて当てはめる。
 *
 * マシンの燃料を読み書きするのは CPU を持つプロセスだけ（CPU は同時に1プロセス）。
 *
 * 1回に渡すのは、その tick の残りのうちスライス（[MachineConfig.fuelSlice]。#47、ADR 0047）まで。
 * CPU を持っている間の [KernelState.fuel] は、その tick の残り（0 以上）で、[lastRefillTick] は手持ちを渡した tick のまま。
 * 返すとき（[giveBack]）に tick が進んでいれば、残りは 0 以上なので `refilled` は `fuelPerTick` になり、そこへ手持ちの `min(r, 0)` を足す。
 * 結果は「tick ごとに `min(fuel, 0) + fuelPerTick`」と同じ（使わなかった分は、手持ちもマシンの残りも持ち越さない）
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

    /**
     * CPU を取る: 補充してから、燃料が正なら [limit]（スライス）までを渡して残りをマシンに置き、渡した量を返す。
     * 正でなければ 0 を返して何もしない
     */
    fun take(now: Long, limit: Long): Long {
        require(limit > 0) { "limit は正" }
        refill(now)
        val fuel = state.fuel
        if (fuel <= 0) return 0
        val grant = minOf(fuel, limit)
        state.fuel = fuel - grant
        return grant
    }

    /** [giveBack] したらいくつになるか（書き換えない。スライスを使い切ったときに、その場で次を足すか・譲るかを決めるのに使う） */
    fun afterGiveBack(now: Long, remaining: Long): Long {
        val k = now - lastRefillTick
        return refilled(state.fuel, k, fuelPerTick) + if (k <= 0) remaining else minOf(remaining, 0)
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
