package pro.ikili.kibicomputers.core.computer;

import org.junit.jupiter.api.Test;
import pro.ikili.kibicomputers.core.kernel.MachineConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 部品のティアと部品から決まる設定（#347）が Java から自然に使えることを確かめる。 */
class MachineSpecsJavaTest {
    @Test
    void specsFromJava() {
        PartTier tier = PartTier.CREATIVE;
        long fuel = tier.cpuFuelPerTick;
        long memory = tier.memoryBytes;
        CaseTier caseTier = CaseTier.BASIC;
        InstalledPartTiers parts = slot -> slot == caseTier.firstSlot(PartKind.CPU) || slot == caseTier.firstSlot(PartKind.MEMORY) ? tier : null;
        MachineConfig config = MachineSpecs.configOf(caseTier, parts);
        assertEquals(fuel, config.getFuelPerTick());
        assertEquals(memory, config.getMemoryBytes());
        assertTrue(memory <= MachineSpecs.MAX_MEMORY_BYTES);
    }
}
