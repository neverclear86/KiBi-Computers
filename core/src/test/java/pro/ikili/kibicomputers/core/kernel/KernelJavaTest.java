package pro.ikili.kibicomputers.core.kernel;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 接続層が使うカーネルの型（#43）が Java から自然に使えることを確かめる。 */
class KernelJavaTest {
    @Test
    void schedulerAndMachineFromJava() throws InterruptedException, TimeoutException {
        MachineScheduler scheduler = new MachineScheduler(
                SchedulerConfig.builder().maxConcurrentMachines(2).maxBlockingProcesses(64).build());
        try {
            Machine machine = scheduler.createMachine((m, state, cause) -> {
                if (state == MachineState.CRASHED) m.stop(); // 中から stop してよい
            }, MachineConfig.DEFAULT);
            assertEquals(MachineState.STOPPED, machine.getState());
            assertEquals(MachineConfig.DEFAULT, machine.getConfig());
            scheduler.tick();
            try (PauseHandle paused = scheduler.pauseAll(1000)) {
                boolean saveable = machine.isAtWaitPoint();
                assertTrue(saveable);
            }
            machine.stop();                                  // 待たずに返る
            boolean done = machine.awaitTermination(1000);
            assertTrue(done);
            machine.close();
        } finally {
            scheduler.close();                               // 待たずに返る
            assertTrue(scheduler.awaitTermination(1000));
        }
        assertThrows(IllegalStateException.class, () -> scheduler.createMachine((m, s, c) -> { }, MachineConfig.DEFAULT));
    }

    @Test
    void configBuilders() throws InterruptedException {
        SchedulerConfig config = SchedulerConfig.builder()
                .maxConcurrentMachines(3)
                .threadStackBytes(1024 * 1024)
                .maxBlockingProcesses(10)
                .build();
        assertEquals(3, config.getMaxConcurrentMachines());
        assertEquals(1024 * 1024, config.getThreadStackBytes());
        assertEquals(10, config.getMaxBlockingProcesses());
        assertTrue(SchedulerConfig.DEFAULT.getMaxConcurrentMachines() >= 1);

        // 実行ファイルの読み込みと共有（#37）
        SchedulerConfig programs = SchedulerConfig.builder()
                .compilerThreads(2)
                .programCacheEntries(8)
                .programCacheBytes(4L << 20)
                .build();
        assertEquals(2, programs.getCompilerThreads());
        assertEquals(8, programs.getProgramCacheEntries());
        assertEquals(4L << 20, programs.getProgramCacheBytes());
        assertEquals(1, SchedulerConfig.DEFAULT.getCompilerThreads());
        assertThrows(IllegalArgumentException.class, () -> SchedulerConfig.builder().compilerThreads(0));
        assertThrows(IllegalArgumentException.class, () -> SchedulerConfig.builder().programCacheEntries(0));

        // サーバー全体の RAM の上限（#362）
        SchedulerConfig total = SchedulerConfig.builder().totalMemoryBytes(64L << 20).build();
        assertEquals(64L << 20, total.getTotalMemoryBytes());
        assertEquals(Runtime.getRuntime().maxMemory() / 2, SchedulerConfig.DEFAULT.getTotalMemoryBytes());
        assertThrows(IllegalArgumentException.class, () -> SchedulerConfig.builder().totalMemoryBytes(-1));
        assertThrows(IllegalArgumentException.class, () -> SchedulerConfig.builder().programCacheBytes(0));

        MachineConfig machine = MachineConfig.builder().fuelPerTick(500).maxProcesses(8).build();
        assertEquals(500, machine.getFuelPerTick());
        assertEquals(8, machine.getMaxProcesses());
        MachineConfig memory = MachineConfig.builder().memoryBytes(8L << 20).maxProcessMemoryBytes(4L << 20).build();
        assertEquals(8L << 20, memory.getMemoryBytes());
        assertEquals(4L << 20, memory.getMaxProcessMemoryBytes());

        MachineScheduler defaults = new MachineScheduler();
        try {
            assertEquals(SchedulerConfig.DEFAULT, defaults.getConfig());
        } finally {
            defaults.close();
            assertTrue(defaults.awaitTermination(1000));
        }
        assertThrows(IllegalArgumentException.class, () -> SchedulerConfig.builder().maxConcurrentMachines(0));
    }
}
