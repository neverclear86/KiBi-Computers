package pro.ikili.kibicomputers.core.machine;

import org.junit.jupiter.api.Test;
import pro.ikili.kibicomputers.core.kernel.Machine;
import pro.ikili.kibicomputers.core.kernel.MachineConfig;
import pro.ikili.kibicomputers.core.kernel.MachineEnvironment;
import pro.ikili.kibicomputers.core.kernel.MachineHost;
import pro.ikili.kibicomputers.core.kernel.MachineScheduler;
import pro.ikili.kibicomputers.core.kernel.SchedulerConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** サーバー側のマシン管理（#120）が Java から自然に使えることを確かめる。 */
class MachineManagerJavaTest {
    /** 接続層のキーの例: ディメンションと座標 */
    record BlockKey(String dimension, long pos) {
    }

    @Test
    void managerAndSlotFromJava() throws InterruptedException {
        List<String> errors = new ArrayList<>();
        MachineErrorListener listener = (message, error) -> errors.add(message);
        MachineManager<BlockKey> manager = new MachineManager<>(SchedulerConfig.builder().maxConcurrentMachines(1).build(), listener);
        MachineScheduler scheduler = manager.getScheduler();
        assertNotNull(scheduler);
        MachineHost host = (machine, state, cause) -> {
        };
        BlockKey key = new BlockKey("overworld", 42L);

        Machine machine = manager.attach(key, host, MachineConfig.DEFAULT);
        assertSame(machine, manager.get(key));
        assertEquals(1, manager.getSize());
        Set<BlockKey> keys = manager.keys();
        assertTrue(keys.contains(key));
        manager.tick();
        assertEquals(1L, manager.getTicks());
        assertTrue(manager.release(key, machine, MachineReleaseReason.UNLOADED));
        assertFalse(manager.release(key, MachineReleaseReason.REMOVED));

        MachineSlot<BlockKey> slot = new MachineSlot<>();
        Machine loaded = slot.load(manager, key, host, MachineConfig.DEFAULT, MachineEnvironment.DEFAULT);
        assertSame(loaded, slot.getMachine());
        assertTrue(slot.chunkUnloaded());
        assertFalse(slot.removed());
        assertNotNull(slot.load(manager, key, host, MachineConfig.DEFAULT));
        assertTrue(slot.removed());

        assertTrue(manager.shutdown(10_000));
        assertTrue(manager.isClosed());
        assertNull(slot.load(manager, key, host, MachineConfig.DEFAULT));
        assertTrue(errors.isEmpty());

        MachineManager<String> defaults = new MachineManager<>();
        assertFalse(defaults.isClosed());
        assertTrue(defaults.shutdown(10_000));
        MachineErrorListener.IGNORE.onError("ignored", new RuntimeException());
    }
}
