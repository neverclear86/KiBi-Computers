package pro.ikili.kibicomputers.core.computer;

import org.junit.jupiter.api.Test;
import pro.ikili.kibicomputers.core.kernel.MachineConfig;
import pro.ikili.kibicomputers.core.machine.MachineManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ケースのティアと電源（#121）が Java から自然に使えることを確かめる。 */
class ComputerCaseJavaTest {
    @Test
    void caseFromJava() throws InterruptedException {
        CaseTier tier = CaseTier.fromLevel(CaseTier.MIN_LEVEL);
        assertEquals(CaseTier.BASIC, tier);
        assertEquals(1, tier.level);
        assertEquals(5, tier.getSlotCount());
        assertEquals(1, tier.slotCount(PartKind.MEMORY));
        assertEquals(PartKind.CPU, tier.kindOf(0));
        List<PartKind> slots = tier.getSlots();
        assertEquals(PartKind.EXPANSION, slots.get(tier.firstSlot(PartKind.EXPANSION)));
        assertTrue(PartKind.CPU.required);
        assertThrows(IllegalArgumentException.class, () -> CaseTier.fromLevel(CaseTier.MAX_LEVEL + 1));

        InstalledParts none = slot -> false;
        assertEquals(4, tier.missingRequired(none).size());
        InstalledParts all = slot -> true;

        MachineManager<String> manager = new MachineManager<>();
        ComputerCase<String> computer = new ComputerCase<>(tier);
        assertEquals(ComputerStatus.ERROR, computer.turnOn(manager, "key", none, true, MachineConfig.DEFAULT));
        assertEquals(ComputerError.MISSING_PARTS, computer.getError());
        assertEquals(ComputerStatus.RUNNING, computer.pressPowerButton(manager, "key", all, true, MachineConfig.DEFAULT));
        assertNotNull(computer.getMachine());
        computer.partsChanged(all);
        computer.powerLost();
        assertEquals(ComputerStatus.NO_POWER, computer.getStatus());
        computer.turnOff();
        computer.chunkUnloaded();
        computer.removed();
        assertNull(computer.getMachine());
        assertTrue(computer.getMissingParts().isEmpty());
        assertTrue(manager.shutdown(10_000));
    }
}
