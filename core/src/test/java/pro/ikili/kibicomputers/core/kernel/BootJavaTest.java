package pro.ikili.kibicomputers.core.kernel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 起動と再起動（#49）が Java から自然に使えることを確かめる。 */
class BootJavaTest {
    /** 起動の失敗と再起動の求めを上書きするクラス */
    static final class BootHost implements MachineHost {
        final List<BootFailure> failures = Collections.synchronizedList(new ArrayList<>());
        final List<String> details = Collections.synchronizedList(new ArrayList<>());
        volatile int reboots;

        @Override
        public void onStateChanged(Machine machine, MachineState state, Throwable cause) {
        }

        @Override
        public void onBootFailed(Machine machine, BootFailure failure, String detail) {
            failures.add(failure);
            details.add(detail);
        }

        @Override
        public void onRebootRequested(Machine machine) {
            reboots++;
            // その時点の設定で作り直して起動する
            machine.boot(BootConfig.builder().firmwarePresent(false).build());
        }
    }

    @Test
    void bootConfigFromJava() {
        BootConfig config = BootConfig.builder()
                .firmwarePresent(true)
                .addBootDisk("disk-a")
                .addBootDisk("disk-b")
                .build();
        assertTrue(config.isFirmwarePresent());
        assertEquals(List.of("disk-a", "disk-b"), config.getBootDisks());
        assertThrows(UnsupportedOperationException.class, () -> config.getBootDisks().add("x"));
        BootConfig replaced = BootConfig.builder().addBootDisk("x").bootDisks(List.of("y")).firmwarePresent(false).build();
        assertEquals(List.of("y"), replaced.getBootDisks());
        assertFalse(replaced.isFirmwarePresent());
        assertEquals("/sbin/init", BootConfig.INIT_PATH);
        assertEquals(5, BootFailure.values().length);
        assertSame(BootFailure.NO_FIRMWARE, BootFailure.valueOf("NO_FIRMWARE"));
    }

    @Test
    void bootAndRebootFromJava() throws InterruptedException {
        MachineScheduler scheduler = new MachineScheduler();
        try {
            BootHost host = new BootHost();
            Machine machine = scheduler.createMachine(host, MachineConfig.DEFAULT);
            assertNull(machine.getLastBootConfig());
            assertFalse(machine.reboot());
            assertFalse(machine.isBooting());

            BootConfig config = BootConfig.builder().firmwarePresent(false).build();
            assertTrue(machine.boot(config));
            assertSame(config, machine.getLastBootConfig());
            tickUntil(scheduler, () -> host.failures.size() == 1);
            assertEquals(BootFailure.NO_FIRMWARE, host.failures.get(0));
            assertEquals(MachineState.STOPPED, machine.getState());

            // 止まっているときの再起動は、すぐ onRebootRequested を呼ぶ
            assertTrue(machine.reboot());
            tickUntil(scheduler, () -> host.reboots == 1 && host.failures.size() == 2);

            // ラムダの host は既定の onRebootRequested（前回の設定で起動）を使う
            List<MachineState> states = Collections.synchronizedList(new ArrayList<>());
            Machine lambda = scheduler.createMachine((m, s, c) -> states.add(s), MachineConfig.DEFAULT);
            assertTrue(lambda.boot(config));
            tickUntil(scheduler, () -> !lambda.isBooting());
            assertTrue(lambda.reboot());
            tickUntil(scheduler, () -> !lambda.isBooting());
            assertTrue(states.isEmpty());
        } finally {
            scheduler.close();
            assertTrue(scheduler.awaitTermination(5000));
        }
    }

    private static void tickUntil(MachineScheduler scheduler, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "条件がそろわない");
            scheduler.tick();
            Thread.sleep(1);
        }
    }
}
