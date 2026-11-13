package pro.ikili.kibicomputers.core.computer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.MachineConfig

/** 部品のティアと、部品から決まるマシンの設定（#347） */
class MachineSpecsTest {
    /** 指定したスロットにだけ [tier] の部品が挿さっている */
    private fun parts(tier: PartTier, vararg slots: Int) = InstalledPartTiers { slot -> if (slot in slots) tier else null }

    @Test
    fun `クリエイティブは最後のティアで、既定より速く大きい`() {
        assertEquals(PartTier.CREATIVE, PartTier.entries.last())
        assertTrue(PartTier.CREATIVE.cpuFuelPerTick > MachineConfig.DEFAULT.fuelPerTick)
        assertTrue(PartTier.CREATIVE.memoryBytes > MachineConfig.DEFAULT.memoryBytes)
    }

    @Test
    fun `何も挿さっていなければ既定の設定`() {
        val config = MachineSpecs.configOf(CaseTier.SUPERIOR) { null }
        assertEquals(MachineConfig.DEFAULT.fuelPerTick, config.fuelPerTick)
        assertEquals(MachineConfig.DEFAULT.memoryBytes, config.memoryBytes)
        assertEquals(MachineConfig.DEFAULT.maxDevices, config.maxDevices)
    }

    @Test
    fun `CPU のティアで接続数が決まり、仮の値は 8・12・16・32・256`() {
        val tier = CaseTier.SUPERIOR
        val cpu = tier.firstSlot(PartKind.CPU)
        val expected = mapOf(
            PartTier.TIER_1 to 8,
            PartTier.TIER_2 to 12,
            PartTier.TIER_3 to 16,
            PartTier.TIER_4 to 32,
            PartTier.CREATIVE to 256,
        )
        for ((part, devices) in expected) {
            assertEquals(devices, part.cpuMaxDevices)
            assertEquals(devices, MachineSpecs.configOf(tier, parts(part, cpu)).maxDevices, "$part")
        }
        // 最低のティアでも、ケースのスピーカー・画面・キーボードの3つは入る
        assertTrue(PartTier.entries.all { it.cpuMaxDevices >= 3 })
    }

    @Test
    fun `CPU で燃料、RAM の合計で容量が決まる`() {
        val tier = CaseTier.SUPERIOR
        val ram = tier.firstSlot(PartKind.MEMORY)
        val slots = intArrayOf(tier.firstSlot(PartKind.CPU), ram, ram + 1, ram + 2)
        val config = MachineSpecs.configOf(tier, parts(PartTier.CREATIVE, *slots))
        assertEquals(PartTier.CREATIVE.cpuFuelPerTick, config.fuelPerTick)
        assertEquals(PartTier.CREATIVE.memoryBytes * 3, config.memoryBytes)
        assertEquals(config.memoryBytes, config.maxProcessMemoryBytes)
    }

    @Test
    fun `CPU・RAM 以外のスロットは設定に効かない`() {
        val tier = CaseTier.BASIC
        val others = intArrayOf(tier.firstSlot(PartKind.STORAGE), tier.firstSlot(PartKind.BOOT), tier.firstSlot(PartKind.EXPANSION))
        val config = MachineSpecs.configOf(tier, parts(PartTier.CREATIVE, *others))
        assertEquals(MachineConfig.DEFAULT.fuelPerTick, config.fuelPerTick)
        assertEquals(MachineConfig.DEFAULT.maxDevices, config.maxDevices)
        assertEquals(MachineConfig.DEFAULT.memoryBytes, config.memoryBytes)
    }
}
