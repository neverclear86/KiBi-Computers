package pro.ikili.kibicomputers.core.computer

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.core.kernel.MachineConfig

/**
 * 部品（CPU・RAM・ディスク・起動部品）のティア（#347）。部品の種類（[PartKind]）とティアで、性能が決まる。
 *
 * 正式なティアは [TIER_1]〜[TIER_4]（#122〜#125）。検証用の [CREATIVE] はいつも最後。
 * 今アイテムとして登録しているのは、正式なティアではディスク（#124）だけ。CPU・RAM の正式な値は #122・#123 で決めるので、
 * 正式なティアの CPU・RAM には今は [MachineConfig.DEFAULT] と同じ値を入れている。
 * 性能値はどれも仮の値で、正式な値は #20・#251 で確定する。
 *
 * @property level ティアの番号（正式なティアは 1〜4、[CREATIVE] は 0）
 * @property cpuFuelPerTick この CPU の 1 tick の燃料（[MachineConfig.fuelPerTick]）
 * @property memoryBytes この RAM 1 つの容量（バイト。[MachineConfig.memoryBytes] に足し込む）
 * @property diskBytes このディスク 1 つの容量（バイト。中身の大きさ＋項目ごとの管理の分。#52・#124）
 * @property diskEntries このディスク 1 つのファイル・ディレクトリの数の上限（根を除く）
 * @property cpuMaxDevices この CPU でつなげるデバイスの数（[MachineConfig.maxDevices]。ケースのスピーカーも1つと数える。#66。確定は #122・#251）
 */
@ApiStatus.Internal
public enum class PartTier(
    @JvmField public val level: Int,
    @JvmField public val cpuFuelPerTick: Long,
    @JvmField public val memoryBytes: Long,
    @JvmField public val diskBytes: Long,
    @JvmField public val diskEntries: Int,
    @JvmField public val cpuMaxDevices: Int,
) {
    /** 1段目。ディスクは 1 MiB・1,024 項目、CPU の接続数は 8（仮） */
    TIER_1(1, DEFAULT_FUEL, DEFAULT_MEMORY, diskBytes = 1L * MIB, diskEntries = 1024, cpuMaxDevices = 8),

    /** 2段目。ディスクは 2 MiB・2,048 項目、CPU の接続数は 12（仮） */
    TIER_2(2, DEFAULT_FUEL, DEFAULT_MEMORY, diskBytes = 2L * MIB, diskEntries = 2048, cpuMaxDevices = 12),

    /** 3段目。ディスクは 4 MiB・4,096 項目、CPU の接続数は 16（仮） */
    TIER_3(3, DEFAULT_FUEL, DEFAULT_MEMORY, diskBytes = 4L * MIB, diskEntries = 4096, cpuMaxDevices = 16),

    /** 4段目（大型サーバー向け）。ディスクは 8 MiB・8,192 項目、CPU の接続数は 32（仮） */
    TIER_4(4, DEFAULT_FUEL, DEFAULT_MEMORY, diskBytes = 8L * MIB, diskEntries = 8192, cpuMaxDevices = 32),

    /**
     * 検証用（クリエイティブタブにだけ出す。サバイバルのレシピはない）。性能は「上限いっぱい」相当の仮の値:
     * CPU は既定の 4 倍の燃料（1 tick 約 8 ms 相当）、RAM は 1 つで 128 MiB（既定の 32 MiB より大きい）、
     * ディスクは 64 MiB・65,536 項目、CPU の接続数は 256。
     */
    CREATIVE(
        0,
        cpuFuelPerTick = 4_000_000,
        memoryBytes = 128L * MIB,
        diskBytes = 64L * MIB,
        diskEntries = 65536,
        cpuMaxDevices = 256,
    ),
}

private const val MIB: Long = 1024L * 1024

// enum のコンストラクターの引数から MachineConfig.DEFAULT は参照できない（初期化の順）ので、同じ値を置く
private const val DEFAULT_FUEL: Long = 1_000_000
private const val DEFAULT_MEMORY: Long = 32L * MIB

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
     * - 接続数（[MachineConfig.maxDevices]）: CPU のスロットのうち、いちばん大きい [PartTier.cpuMaxDevices]（#66）
     *
     * CPU・RAM が挿さっていなければ、その値は [MachineConfig.DEFAULT] のまま（起動できるかは [CaseTier.missingRequired] で見る）。
     */
    @JvmStatic
    public fun configOf(caseTier: CaseTier, parts: InstalledPartTiers): MachineConfig {
        var fuel = 0L
        var memory = 0L
        var maxDevices = 0
        for (slot in 0 until caseTier.slotCount) {
            val tier = parts.tierAt(slot) ?: continue
            when (caseTier.kindOf(slot)) {
                PartKind.CPU -> {
                    fuel = maxOf(fuel, tier.cpuFuelPerTick)
                    maxDevices = maxOf(maxDevices, tier.cpuMaxDevices)
                }
                PartKind.MEMORY -> memory = minOf(memory + tier.memoryBytes, MAX_MEMORY_BYTES)
                else -> Unit
            }
        }
        val builder = MachineConfig.builder()
        if (fuel > 0) builder.fuelPerTick(fuel)
        if (memory > 0) builder.memoryBytes(memory)
        if (maxDevices > 0) builder.maxDevices(maxDevices)
        return builder.build()
    }

    /** RAM の合計の上限（WASM の 32 ビットの線形メモリの上限、4 GiB） */
    public const val MAX_MEMORY_BYTES: Long = 4L * 1024 * 1024 * 1024
}
