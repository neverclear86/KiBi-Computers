package pro.ikili.kibicomputers.core.kernel;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** カーネルと接続層の境界（#44）が Java から自然に使えることを確かめる。 */
class HostBoundaryJavaTest {
    /** 接続層の時計: ゲーム内時間は tick ごとに写しておいたものを返す */
    static final class WorldClock implements MachineClock {
        volatile long worldTicks;

        @Override
        public long realtimeNanos() {
            return MachineClock.SYSTEM.realtimeNanos();
        }

        @Override
        public long monotonicNanos() {
            return System.nanoTime();
        }

        @Override
        public long worldTicks() {
            return worldTicks;
        }
    }

    /** onStateChanged だけ実装し、ほかは既定の実装に任せるクラス */
    static final class StateOnlyHost implements MachineHost {
        final List<MachineState> states = new ArrayList<>();

        @Override
        public void onStateChanged(Machine machine, MachineState state, Throwable cause) {
            states.add(state);
        }
    }

    @Test
    void environmentAndHostFromJava() throws InterruptedException {
        WorldClock clock = new WorldClock();
        clock.worldTicks = 1234;
        Path disks = Paths.get("disks");
        MachineEnvironment env = MachineEnvironment.builder()
                .clock(clock)
                .entropy(bytes -> java.util.Arrays.fill(bytes, (byte) 1))
                .disks(id -> disks.resolve(id))
                .storageDirectory(Paths.get("save", "m1"))
                .build();
        assertEquals(1234, env.getClock().worldTicks());
        assertEquals(disks.resolve("abc"), env.getDisks().resolve("abc"));
        assertEquals(Paths.get("save", "m1"), env.getStorageDirectory());
        assertSame(EntropySource.SECURE, MachineEnvironment.DEFAULT.getEntropy());
        assertSame(MachineClock.SYSTEM, MachineEnvironment.DEFAULT.getClock());
        assertNull(DiskResolver.NONE.resolve("x"));
        assertNull(MachineEnvironment.DEFAULT.getStorageDirectory());

        MachineConfig config = MachineConfig.builder().maxHostCallsPerTick(8).build();
        assertEquals(8, config.getMaxHostCallsPerTick());
        assertTrue(Machine.MIN_BEEP_FREQUENCY_HZ < Machine.MAX_BEEP_FREQUENCY_HZ);
        assertTrue(Machine.MAX_BEEP_DURATION_MILLIS > 0);

        MachineScheduler scheduler = new MachineScheduler();
        try {
            StateOnlyHost host = new StateOnlyHost();
            Machine machine = scheduler.createMachine(host, config, env);
            assertSame(env, machine.getEnvironment());
            Machine withDefaults = scheduler.createMachine((m, s, c) -> { }, MachineConfig.DEFAULT);
            assertSame(MachineEnvironment.DEFAULT, withDefaults.getEnvironment());

            // 既定の実装は何もしない（呼べる）
            host.onConsoleOutput(machine, new byte[] {1});
            host.onBeep(machine, 440, 100);
            machine.notifyDevicesChanged();
            scheduler.tick();
        } finally {
            scheduler.close();
            assertTrue(scheduler.awaitTermination(1000));
        }
    }
}
