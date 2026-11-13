package pro.ikili.kibicomputers.core.computer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.MachineConfig

/** 部品のティア（#124） */
class PartTierTest {
    private val formal = PartTier.entries.filter { it != PartTier.CREATIVE }

    @Test
    fun `正式なティアは1から4で、クリエイティブが最後`() {
        assertEquals(PartTier.CREATIVE, PartTier.entries.last())
        assertEquals(listOf(1, 2, 3, 4), formal.map { it.level })
        assertEquals(0, PartTier.CREATIVE.level)
    }

    @Test
    fun `ディスクの容量はティアの順に増え、クリエイティブがいちばん大きい`() {
        for ((a, b) in PartTier.entries.zipWithNext()) {
            assertTrue(a.diskBytes < b.diskBytes, "$a < $b")
            assertTrue(a.diskEntries < b.diskEntries, "$a < $b")
        }
    }

    @Test
    fun `正式なティアのCPUの燃料とRAMの容量は今は既定の値`() {
        for (tier in formal) {
            assertEquals(MachineConfig.DEFAULT.fuelPerTick, tier.cpuFuelPerTick)
            assertEquals(MachineConfig.DEFAULT.memoryBytes, tier.memoryBytes)
        }
    }

    @Test
    fun `CPUの接続数はティアの順に増え、3段目が既定の値で、クリエイティブがいちばん多い`() {
        val counts = formal.map { it.cpuMaxDevices }
        assertEquals(counts.sorted(), counts)
        assertEquals(counts.size, counts.toSet().size)
        assertEquals(MachineConfig.DEFAULT.maxDevices, PartTier.TIER_3.cpuMaxDevices)
        assertTrue(PartTier.CREATIVE.cpuMaxDevices > counts.max())
    }
}
