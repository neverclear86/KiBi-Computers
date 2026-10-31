package pro.ikili.kibicomputers.core.computer

/**
 * ケースの部品スロットの種類（#121）。スロットには、その種類の部品だけを挿せる。
 *
 * @property required コンピューターを起動するのに、この種類の部品が1つ以上いるか
 */
public enum class PartKind(@JvmField public val required: Boolean) {
    /** CPU（#122） */
    CPU(true),

    /** RAM（#123） */
    MEMORY(true),

    /** 内蔵ディスク（#124） */
    STORAGE(true),

    /** 起動部品（EEPROM、#125） */
    BOOT(true),

    /** 拡張カード（GPU カード、ネットワークカードなど）。なくても起動できる */
    EXPANSION(false),
}

/**
 * スロットの番号ごとに、部品が挿さっているか（[CaseTier.missingRequired] に渡す）。
 * 接続層はケースの持ち物から答える。
 */
public fun interface InstalledParts {
    /** スロット [slot]（0 から [CaseTier.slotCount] 未満）に部品が挿さっていれば `true` */
    public fun isInstalled(slot: Int): Boolean
}

/**
 * ケースブロックのティア（#121）。ティアで、部品スロットの種類ごとの数が決まる。
 *
 * スロットの番号は、[PartKind] の順に種類ごとにまとめて振る（[slots]）。
 * 数は仮の値で、正式な値は #251 で確定する。
 *
 * @property level ティアの番号（1〜3）
 */
public enum class CaseTier(
    @JvmField public val level: Int,
    cpu: Int,
    memory: Int,
    storage: Int,
    boot: Int,
    expansion: Int,
) {
    /** 1段目: どの種類も1つずつ。 */
    BASIC(1, cpu = 1, memory = 1, storage = 1, boot = 1, expansion = 1),

    /** 2段目: RAM・ディスク・拡張カードが2つずつ。 */
    ADVANCED(2, cpu = 1, memory = 2, storage = 2, boot = 1, expansion = 2),

    /** 3段目: RAM・ディスク・拡張カードが3つずつ。 */
    SUPERIOR(3, cpu = 1, memory = 3, storage = 3, boot = 1, expansion = 3),
    ;

    private val counts: IntArray = intArrayOf(cpu, memory, storage, boot, expansion)

    /** スロットの番号ごとの種類。[PartKind] の順に並ぶ。 */
    public val slots: List<PartKind> = PartKind.entries.flatMap { kind -> List(counts[kind.ordinal]) { kind } }

    /** スロットの数 */
    public val slotCount: Int get() = slots.size

    /** [kind] のスロットの数 */
    public fun slotCount(kind: PartKind): Int = counts[kind.ordinal]

    /**
     * スロット [slot] の種類。
     *
     * @throws IndexOutOfBoundsException [slot] が範囲外
     */
    @Throws(IndexOutOfBoundsException::class)
    public fun kindOf(slot: Int): PartKind = slots[slot]

    /** [kind] の最初のスロットの番号 */
    public fun firstSlot(kind: PartKind): Int = slots.indexOf(kind)

    /** 起動に要る種類（[PartKind.required]）のうち、1つも挿さっていないもの。[PartKind] の順 */
    public fun missingRequired(parts: InstalledParts): List<PartKind> =
        PartKind.entries.filter { kind ->
            kind.required && slots.indices.none { slots[it] == kind && parts.isInstalled(it) }
        }

    public companion object {
        /** いちばん低いティアの番号。 */
        public const val MIN_LEVEL: Int = 1

        /** いちばん高いティアの番号。 */
        public const val MAX_LEVEL: Int = 3

        /**
         * 番号 [level]（1〜3）のティア。
         *
         * @throws IllegalArgumentException [level] が 1〜3 でないとき
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class)
        public fun fromLevel(level: Int): CaseTier =
            entries.firstOrNull { it.level == level }
                ?: throw IllegalArgumentException("Unknown case tier: $level")
    }
}
