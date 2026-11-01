package pro.ikili.kibicomputers.core.computer

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.core.kernel.MachineConfig

/**
 * 部品（CPU・RAM・ディスク・起動部品）のティア（#347）。部品の種類（[PartKind]）とティアで、性能が決まる。
 *
 * 今は検証用の [CREATIVE] だけ。正式なティア（#122〜#125）は [CREATIVE] の前に足す（[CREATIVE] はいつも最後）。
 * 性能値はどれも仮の値で、正式な値は #20・#251 で確定する。
 *
 * @property cpuFuelPerTick この CPU の 1 tick の燃料（[MachineConfig.fuelPerTick]）
 * @property memoryBytes この RAM 1 つの容量（バイト。[MachineConfig.memoryBytes] に足し込む）
 */
@ApiStatus.Internal
public enum class PartTier(
    @JvmField public val cpuFuelPerTick: Long,
    @JvmField public val memoryBytes: Long,
) {
    /**
     * 検証用（クリエイティブタブにだけ出す。サバイバルのレシピはない）。性能は「上限いっぱい」相当の仮の値:
     * CPU は既定の 4 倍の燃料（1 tick 約 8 ms 相当）、RAM は 1 つで 128 MiB（既定の 32 MiB より大きい）。
     */
    CREATIVE(cpuFuelPerTick = 4_000_000, memoryBytes = 128L * 1024 * 1024),
}

/**
 * スロットの番号ごとに、挿さっている部品のティア（[MachineSpecs.configOf] に渡す）。
 * 接続層はケースの持ち物から答える。
 */
@ApiStatus.Internal
public fun interface InstalledPartTiers {
    /**
     * スロット [slot]（0 から [CaseTier.slotCount] 未満）に挿さっている部品のティア。
     * 挿さっていない、またはティアのない部品（拡張カードなど）なら `null`
     */
    public fun tierAt(slot: Int): PartTier?
}

/** 挿さっている部品から、マシンの設定（[MachineConfig]）を決める（#347。正式な式は #122・#123）。 */
@ApiStatus.Internal
public object MachineSpecs {
    /**
     * ケース [caseTier] に挿さっている部品 [parts] から、マシンの設定を作る。
     *
     * - 燃料（[MachineConfig.fuelPerTick]）: CPU のスロットのうち、いちばん速い CPU の [PartTier.cpuFuelPerTick]
     * - RAM（[MachineConfig.memoryBytes]）: RAM のスロットの [PartTier.memoryBytes] の合計（WASM の 32 ビットの上限で頭打ち）
     *
     * CPU・RAM が挿さっていなければ、その値は [MachineConfig.DEFAULT] のまま（起動できるかは [CaseTier.missingRequired] で見る）。
     */
    @JvmStatic
    public fun configOf(caseTier: CaseTier, parts: InstalledPartTiers): MachineConfig {
        var fuel = 0L
        var memory = 0L
        for (slot in 0 until caseTier.slotCount) {
            val tier = parts.tierAt(slot) ?: continue
            when (caseTier.kindOf(slot)) {
                PartKind.CPU -> fuel = maxOf(fuel, tier.cpuFuelPerTick)
                PartKind.MEMORY -> memory = minOf(memory + tier.memoryBytes, MAX_MEMORY_BYTES)
                else -> Unit
            }
        }
        val builder = MachineConfig.builder()
        if (fuel > 0) builder.fuelPerTick(fuel)
        if (memory > 0) builder.memoryBytes(memory)
        return builder.build()
    }

    /** RAM の合計の上限（WASM の 32 ビットの線形メモリの上限、4 GiB） */
    public const val MAX_MEMORY_BYTES: Long = 4L * 1024 * 1024 * 1024
}
