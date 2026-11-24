package pro.ikili.kibicomputers.core.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.display.ScreenSyncTraffic
import pro.ikili.kibicomputers.core.input.KeyboardInputBudget
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.MachineSpecs
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.computer.PartSpec
import pro.ikili.kibicomputers.core.computer.PartTier
import pro.ikili.kibicomputers.core.kernel.MachineConfig
import pro.ikili.kibicomputers.core.kernel.RebootBackoff
import pro.ikili.kibicomputers.core.kernel.SchedulerConfig
import pro.ikili.kibicomputers.core.persist.FullSaveRule
import pro.ikili.kibicomputers.core.persist.MachineSaveConfig
import pro.ikili.kibicomputers.core.persist.ModuleStore

class KiBiConfigTest {
    @Test
    fun defaultsMatchTheCurrentBuiltInDefaults() {
        val values = ConfigValues.DEFAULTS
        assertEquals(MachineSaveConfig.DEFAULT, KiBiConfig.machineSaveConfig(values))

        val scheduler = KiBiConfig.schedulerConfig(values, ModuleStore.NONE)
        val builtIn = SchedulerConfig.DEFAULT
        assertEquals(builtIn.maxConcurrentMachines, scheduler.maxConcurrentMachines)
        assertEquals(builtIn.threadStackBytes, scheduler.threadStackBytes)
        assertEquals(builtIn.maxBlockingProcesses, scheduler.maxBlockingProcesses)
        assertEquals(builtIn.compilerThreads, scheduler.compilerThreads)
        assertEquals(builtIn.programCacheEntries, scheduler.programCacheEntries)
        assertEquals(builtIn.programCacheBytes, scheduler.programCacheBytes)
        assertEquals(builtIn.totalMemoryBytes, scheduler.totalMemoryBytes)

        val traffic = KiBiConfig.screenSyncTraffic<Any>(values)
        assertEquals(ScreenSyncTraffic.DEFAULT_PLAYER_BYTES_PER_SECOND, traffic.playerBytesPerSecond)
        assertEquals(ScreenSyncTraffic.DEFAULT_SERVER_BYTES_PER_SECOND, traffic.serverBytesPerSecond)

        val budget = KiBiConfig.keyboardInputBudget(values)
        assertEquals(KeyboardInputBudget.DEFAULT_CAPACITY, budget.capacity)
        assertEquals(KeyboardInputBudget.DEFAULT_REFILL_PER_TICK, budget.refillPerTick)
    }

    @Test
    fun ownerLimitsReachTheSchedulerAndTheScreenTraffic() {
        // 既定（#738）: 組ごとの値は SchedulerConfig・ScreenSyncTraffic の既定と同じ
        val defaults = KiBiConfig.schedulerConfig(ConfigValues.DEFAULTS, ModuleStore.NONE)
        assertEquals(SchedulerConfig.DEFAULT.maxConcurrentMachinesPerGroup, defaults.maxConcurrentMachinesPerGroup)
        assertEquals(SchedulerConfig.DEFAULT.maxBlockingProcessesPerGroup, defaults.maxBlockingProcessesPerGroup)
        assertEquals(SchedulerConfig.DEFAULT.maxPendingLoadsPerGroup, defaults.maxPendingLoadsPerGroup)
        assertEquals(ScreenSyncTraffic.DEFAULT_GROUP_BYTES_PER_SECOND, KiBiConfig.screenSyncTraffic<Any>(ConfigValues.DEFAULTS).groupBytesPerSecond)

        val values = ConfigValues.resolve(
            mapSource(
                KiBiConfig.MAX_CONCURRENT_MACHINES_PER_OWNER to 3,
                KiBiConfig.MAX_BLOCKING_PROCESSES_PER_OWNER to 5,
                KiBiConfig.MAX_PENDING_LOADS_PER_OWNER to 7,
                KiBiConfig.SCREEN_OWNER_BYTES_PER_SECOND to 11_000,
            ),
        )
        assertEquals(emptyList<ConfigEntry<*>>(), values.rejected)
        val scheduler = KiBiConfig.schedulerConfig(values, ModuleStore.NONE)
        assertEquals(3, scheduler.maxConcurrentMachinesPerGroup)
        assertEquals(5, scheduler.maxBlockingProcessesPerGroup)
        assertEquals(7, scheduler.maxPendingLoadsPerGroup)
        assertEquals(11_000, KiBiConfig.screenSyncTraffic<Any>(values).groupBytesPerSecond)
    }

    @Test
    fun autoSentinelLeavesMachineDependentDefaults() {
        val explicit = ConfigValues.resolve(
            mapSource(KiBiConfig.MAX_CONCURRENT_MACHINES to 3, KiBiConfig.TOTAL_MEMORY_MIB to 5),
        )
        val scheduler = KiBiConfig.schedulerConfig(explicit, ModuleStore.NONE)
        assertEquals(3, scheduler.maxConcurrentMachines)
        assertEquals(5L * 1024 * 1024, scheduler.totalMemoryBytes)

        val auto = KiBiConfig.schedulerConfig(ConfigValues.DEFAULTS, ModuleStore.NONE)
        assertEquals((Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1), auto.maxConcurrentMachines)
        assertEquals(Runtime.getRuntime().maxMemory() / 2, auto.totalMemoryBytes)
    }

    @Test
    fun largeMebibyteValuesDoNotOverflow() {
        val values = ConfigValues.resolve(
            mapSource(KiBiConfig.TOTAL_MEMORY_MIB to Int.MAX_VALUE, KiBiConfig.WRITER_MAX_PENDING_MIB to 65536),
        )
        assertEquals(Int.MAX_VALUE.toLong() * 1024 * 1024, KiBiConfig.schedulerConfig(values, ModuleStore.NONE).totalMemoryBytes)
        assertEquals(64L * 1024 * 1024 * 1024, KiBiConfig.machineSaveConfig(values).writerMaxPendingBytes)
    }

    @Test
    fun everyEntryBuildsAtItsMinimumAndMaximum() {
        for (bound in listOf(true, false)) {
            val source = ConfigSource { entry ->
                when (entry) {
                    is IntEntry -> if (bound) entry.min else entry.max
                    is LongEntry -> if (bound) entry.min else entry.max
                    is DoubleEntry -> if (bound) entry.min else entry.max
                    is BooleanEntry -> bound
                }
            }
            val values = ConfigValues.resolve(source)
            assertEquals(emptyList<ConfigEntry<*>>(), values.rejected)
            KiBiConfig.schedulerConfig(values, ModuleStore.NONE)
            KiBiConfig.machineSaveConfig(values)
            KiBiConfig.screenSyncTraffic<Any>(values)
            KiBiConfig.keyboardInputBudget(values)
            KiBiConfig.machineProfile(values)
            KiBiConfig.rebootBackoff(values)
            // 自動保存の周期は、止めた台が書かれる前に替わらない長さになる
            val save = KiBiConfig.machineSaveConfig(values)
            assertTrue(
                save.autosavePeriodTicks >= save.autosaveQuiesceTicks + 2,
                "周期 ${save.autosavePeriodTicks} が待つ ${save.autosaveQuiesceTicks} tick より短い",
            )
        }
    }

    @Test
    fun autosavePeriodIsRaisedAboveTheQuiesceWait() {
        val short = ConfigValues.resolve(
            mapSource(KiBiConfig.AUTOSAVE_PERIOD_TICKS to 20, KiBiConfig.AUTOSAVE_QUIESCE_TICKS to 100),
        )
        assertEquals(20, short[KiBiConfig.AUTOSAVE_PERIOD_TICKS])
        assertEquals(102L, KiBiConfig.machineSaveConfig(short).autosavePeriodTicks)
        // 十分長ければそのまま
        val long = ConfigValues.resolve(mapSource(KiBiConfig.AUTOSAVE_PERIOD_TICKS to 600))
        assertEquals(600L, KiBiConfig.machineSaveConfig(long).autosavePeriodTicks)
        // 周期の下限は 1 秒（1 tick ごとに止めて戻す周期は設定できない）
        val tooShort = ConfigValues.resolve(mapSource(KiBiConfig.AUTOSAVE_PERIOD_TICKS to 1))
        assertEquals(KiBiConfig.AUTOSAVE_PERIOD_TICKS.default, tooShort[KiBiConfig.AUTOSAVE_PERIOD_TICKS])
        assertEquals(listOf<ConfigEntry<*>>(KiBiConfig.AUTOSAVE_PERIOD_TICKS), tooShort.rejected)
    }

    @Test
    fun keyboardBudgetIsRebuiltWhenTheConfiguredValuesChange() {
        val values = ConfigValues.DEFAULTS
        val used = KiBiConfig.keyboardInputBudget(values)
        used.tryConsume(10, 0)
        assertSame(used, KiBiConfig.keyboardInputBudget(values, used), "同じ値なら、使いかけの予算を残す")
        assertNotSame(used, KiBiConfig.keyboardInputBudget(values, null))

        val changed = ConfigValues.resolve(mapSource(KiBiConfig.KEYBOARD_INPUT_REFILL_PER_TICK to 0))
        val rebuilt = KiBiConfig.keyboardInputBudget(changed, used)
        assertNotSame(used, rebuilt)
        assertEquals(0, rebuilt.refillPerTick)
        assertEquals(used.capacity, rebuilt.capacity)

        val bigger = ConfigValues.resolve(mapSource(KiBiConfig.KEYBOARD_INPUT_CAPACITY to used.capacity + 1))
        assertEquals(used.capacity + 1, KiBiConfig.keyboardInputBudget(bigger, used).capacity)
    }

    @Test
    fun outOfRangeAndWrongTypeFallBackToDefault() {
        val values = ConfigValues.resolve(
            mapSource(
                KiBiConfig.COMPILER_THREADS to 0,
                KiBiConfig.FULL_SAVE_CHANGED_RATIO to 2.0,
                KiBiConfig.RESTORES_PER_TICK to "many",
                KiBiConfig.AUTOSAVE_QUIESCE_TICKS to 7L,
            ),
        )
        assertEquals(KiBiConfig.COMPILER_THREADS.default, values[KiBiConfig.COMPILER_THREADS])
        assertEquals(KiBiConfig.FULL_SAVE_CHANGED_RATIO.default, values[KiBiConfig.FULL_SAVE_CHANGED_RATIO])
        assertEquals(KiBiConfig.RESTORES_PER_TICK.default, values[KiBiConfig.RESTORES_PER_TICK])
        // ローダーが Long で返しても、範囲の中なら Int に直す
        assertEquals(7, values[KiBiConfig.AUTOSAVE_QUIESCE_TICKS])
        assertEquals(
            setOf(KiBiConfig.COMPILER_THREADS, KiBiConfig.FULL_SAVE_CHANGED_RATIO, KiBiConfig.RESTORES_PER_TICK),
            values.rejected.toSet(),
        )
    }

    @Test
    fun fullSaveRuleComesFromTheRatioAndTheMinimumChunks() {
        val values = ConfigValues.resolve(
            mapSource(KiBiConfig.FULL_SAVE_CHANGED_RATIO to 0.25, KiBiConfig.FULL_SAVE_MIN_CHANGED_CHUNKS to 0),
        )
        assertEquals(FullSaveRule(0.25, 0), KiBiConfig.machineSaveConfig(values).fullSaveRule)
        val negative = ConfigValues.resolve(mapSource(KiBiConfig.FULL_SAVE_MIN_CHANGED_CHUNKS to -1))
        assertEquals(MachineSaveConfig.DEFAULT_FULL_SAVE_MIN_CHANGED_CHUNKS, KiBiConfig.machineSaveConfig(negative).fullSaveMinChangedChunks)
        assertEquals(listOf<ConfigEntry<*>>(KiBiConfig.FULL_SAVE_MIN_CHANGED_CHUNKS), negative.rejected)
    }

    @Test
    fun integerEntriesRejectFractionalNumbers() {
        val values = ConfigValues.resolve(
            mapSource(
                // 切り捨てて 2 にしない
                KiBiConfig.COMPILER_THREADS to 2.9,
                // NaN を 0（「自動」の番兵）にしない
                KiBiConfig.MAX_CONCURRENT_MACHINES to Double.NaN,
                KiBiConfig.TOTAL_MEMORY_MIB to 1.0f,
                // 小数の項目は整数の値も受ける
                KiBiConfig.FULL_SAVE_CHANGED_RATIO to 1,
                // 整数の型なら Int の項目にも受ける
                KiBiConfig.RESTORES_PER_TICK to 3.toShort(),
            ),
        )
        assertEquals(KiBiConfig.COMPILER_THREADS.default, values[KiBiConfig.COMPILER_THREADS])
        assertEquals(KiBiConfig.MAX_CONCURRENT_MACHINES.default, values[KiBiConfig.MAX_CONCURRENT_MACHINES])
        assertEquals(KiBiConfig.TOTAL_MEMORY_MIB.default, values[KiBiConfig.TOTAL_MEMORY_MIB])
        assertEquals(1.0, values[KiBiConfig.FULL_SAVE_CHANGED_RATIO])
        assertEquals(3, values[KiBiConfig.RESTORES_PER_TICK])
        assertEquals(
            setOf(KiBiConfig.COMPILER_THREADS, KiBiConfig.MAX_CONCURRENT_MACHINES, KiBiConfig.TOTAL_MEMORY_MIB),
            values.rejected.toSet(),
        )
    }

    @Test
    fun longEntryAcceptsIntegralTypesOnly() {
        val entry = LongEntry(ConfigSide.SERVER, "t", "n", 5L, 0L, 10L, ConfigApply.RELOAD, "en", "ja")
        assertEquals(7L, entry.coerce(7))
        assertEquals(7L, entry.coerce(7L))
        assertEquals(null, entry.coerce(7.0))
        assertEquals(null, entry.coerce(11L))
        assertEquals(null, entry.coerce("7"))
    }

    @Test
    fun pathsAreUniqueAndDescriptionsAreFilled() {
        val paths = KiBiConfig.ENTRIES.map { "${it.side}:${it.path}" }
        assertEquals(paths.size, paths.toSet().size, "重なったキー: $paths")
        for (entry in KiBiConfig.ENTRIES) {
            assertTrue(entry.commentEn.isNotBlank(), "英語の説明がない: $entry")
            assertTrue(entry.commentJa.isNotBlank(), "日本語の説明がない: $entry")
            assertTrue(entry.commentEn.all { it.code < 128 }, "英語の説明に ASCII 以外: $entry")
            assertTrue(entry.translationKey.startsWith(ConfigEntry.TRANSLATION_PREFIX))
        }
        assertTrue(KiBiConfig.entries(ConfigSide.SERVER).isNotEmpty())
        assertEquals(KiBiConfig.ENTRIES.map { it.section }.toSet(), KiBiConfig.SECTION_COMMENTS.keys, "区画の説明")
    }

    @Test
    fun stateSwapsSnapshotOnlyWhenSomethingChanged() {
        val state = ConfigState()
        val before = state.values
        assertEquals(emptyList<ConfigEntry<*>>(), state.update(ConfigValues.resolve(ConfigSource { null })))
        assertSame(before, state.values)

        val next = ConfigValues.resolve(mapSource(KiBiConfig.RESTORES_PER_TICK to 3, KiBiConfig.COMPILER_THREADS to 2))
        assertEquals(listOf(KiBiConfig.COMPILER_THREADS, KiBiConfig.RESTORES_PER_TICK), state.update(next))
        assertNotSame(before, state.values)
        assertEquals(3, state.values[KiBiConfig.RESTORES_PER_TICK])
        assertEquals(ConfigApply.WORLD_RESTART, KiBiConfig.COMPILER_THREADS.apply)
    }

    @Test
    fun fileNamesAreSharedAcrossVersions() {
        assertEquals("kibicomputers-server.toml", ConfigSide.SERVER.fileName)
        assertEquals("kibicomputers-client.toml", ConfigSide.CLIENT.fileName)
        assertEquals("kibicomputers-common.toml", ConfigSide.COMMON.fileName)
    }

    @Test
    fun machineAndPartDefaultsMatchTheBuiltInValues() {
        val profile = KiBiConfig.machineProfile(ConfigValues.DEFAULTS)
        assertTrue(profile.base.sameValues(MachineConfig.DEFAULT), "既定のコンフィグの上限は MachineConfig.DEFAULT と同じ")
        for (tier in PartTier.entries) assertEquals(tier.defaultSpec, profile.spec(tier), "$tier")
        val scheduler = KiBiConfig.schedulerConfig(ConfigValues.DEFAULTS, ModuleStore.NONE)
        assertEquals(SchedulerConfig.DEFAULT.mainThreadBudgetNanos, scheduler.mainThreadBudgetNanos)
        assertEquals(SchedulerConfig.DEFAULT.hostFilesTotal, scheduler.hostFilesTotal)
        val backoff = scheduler.rebootBackoff
        val d = RebootBackoff.DEFAULT
        assertEquals(
            listOf(d.freeReboots.toLong(), d.baseDelayTicks, d.maxDelayTicks, d.decayTicks, d.capLevels.toLong()),
            listOf(backoff.freeReboots.toLong(), backoff.baseDelayTicks, backoff.maxDelayTicks, backoff.decayTicks, backoff.capLevels.toLong()),
        )
    }

    @Test
    fun machineAndPartValuesReachTheMachineConfig() {
        val values = ConfigValues.resolve(
            mapSource(
                KiBiConfig.MACHINE_MAX_PROCESSES to 9,
                KiBiConfig.MACHINE_MAX_BLOCKING_PROCESSES to 2,
                KiBiConfig.MACHINE_MAX_HOST_CALLS_PER_TICK to 7,
                KiBiConfig.MACHINE_MAX_EVENT_QUEUES to 5,
                KiBiConfig.MACHINE_MAX_PENDING_EVENT_KIB to 32,
                KiBiConfig.MACHINE_MAX_ARMED_TIMERS to 3,
                KiBiConfig.MACHINE_HOST_FILES to 4,
                KiBiConfig.MACHINE_CONSOLE_INPUT_KIB to 2,
                KiBiConfig.MACHINE_CONSOLE_OUTPUT_KIB to 8,
                KiBiConfig.MACHINE_MAX_PENDING_CONSOLE_KIB to 16,
                KiBiConfig.CPU_FUEL_PER_TICK.getValue(PartTier.CREATIVE) to 2_000_000,
                KiBiConfig.CPU_MAX_DEVICES.getValue(PartTier.CREATIVE) to 40,
                KiBiConfig.MEMORY_MIB.getValue(PartTier.CREATIVE) to 48,
                KiBiConfig.STORAGE_MIB.getValue(PartTier.TIER_2) to 3,
                KiBiConfig.STORAGE_MAX_ENTRIES.getValue(PartTier.TIER_2) to 100,
                KiBiConfig.MAIN_THREAD_BUDGET_MICROS to 2500,
                KiBiConfig.HOST_FILES_TOTAL to 99,
            ),
        )
        assertEquals(emptyList<ConfigEntry<*>>(), values.rejected)
        val profile = KiBiConfig.machineProfile(values)
        assertSame(profile, KiBiConfig.machineProfile(values), "同じ写しには同じものを返す（台ごとに作り直さない）")
        // CREATIVE の CPU と RAM を挿したケース
        val case = CaseTier.entries.last()
        val config = MachineSpecs.configOf(
            case,
            { slot ->
                when (case.kindOf(slot)) {
                    PartKind.CPU, PartKind.MEMORY -> PartTier.CREATIVE
                    else -> null
                }
            },
            profile,
        )
        val memorySlots = (0 until case.slotCount).count { case.kindOf(it) == PartKind.MEMORY }
        assertEquals(2_000_000L, config.fuelPerTick)
        assertEquals(40, config.maxDevices)
        assertEquals(minOf(memorySlots * 48L * 1024 * 1024, MachineSpecs.MAX_MEMORY_BYTES), config.memoryBytes)
        assertEquals(9, config.maxProcesses)
        assertEquals(2, config.maxBlockingProcesses)
        assertEquals(7, config.maxHostCallsPerTick)
        assertEquals(5, config.maxEventQueues)
        assertEquals(32 * 1024, config.maxPendingEventBytes)
        assertEquals(3, config.maxArmedTimers)
        assertEquals(4, config.hostFilesPerMachine)
        assertEquals(2 * 1024, config.consoleInputBytes)
        assertEquals(8 * 1024, config.consoleOutputBytes)
        assertEquals(16L * 1024, config.maxPendingConsoleBytes)
        assertEquals(PartSpec(1_000_000, 32L shl 20, 3L shl 20, 100, 12), profile.spec(PartTier.TIER_2))
        val scheduler = KiBiConfig.schedulerConfig(values, ModuleStore.NONE)
        assertEquals(2_500_000L, scheduler.mainThreadBudgetNanos)
        assertEquals(99, scheduler.hostFilesTotal)
    }

    @Test
    fun rebootBackoffIsRaisedSoTheRateBoundHolds() {
        // maxDelay < decay で、capLevels が小さすぎる組み合わせ
        val values = ConfigValues.resolve(
            mapSource(
                KiBiConfig.REBOOT_FREE to 5,
                KiBiConfig.REBOOT_BASE_DELAY_TICKS to 1,
                KiBiConfig.REBOOT_MAX_DELAY_TICKS to 10,
                KiBiConfig.REBOOT_DECAY_TICKS to 1000,
                KiBiConfig.REBOOT_CAP_LEVELS to 1,
            ),
        )
        val b = KiBiConfig.rebootBackoff(values)
        assertEquals(1000L, b.maxDelayTicks)
        assertEquals(5 + 1 + 10, b.capLevels, "1 から倍々で 1000 に届くのは 10 回目（1024）")
        assertRateBound(b)
        // 全項目の最小・最大でも、見積もりが成り立つ
        for (bound in listOf(true, false)) {
            val extreme = ConfigValues.resolve(ConfigSource { entry -> (entry as? IntEntry)?.let { if (bound) it.min else it.max } })
            assertRateBound(KiBiConfig.rebootBackoff(extreme))
        }
    }

    /** N 回の再起動にかかる tick は「(N - capLevels) * decayTicks」以上（ADR 0635） */
    private fun assertRateBound(b: RebootBackoff) {
        for (ran in listOf(0L, 1L, b.decayTicks - 1, b.decayTicks, b.decayTicks + 1)) {
            val n = b.capLevels + 200
            var debt = 0L
            var total = 0L
            repeat(n) {
                val step = b.next(debt, ran)
                debt = step.debtTicks
                total += step.delayTicks + ran
            }
            val bound = (n - b.capLevels).toLong() * b.decayTicks
            assertTrue(total >= bound, "ran=$ran: $total < $bound")
        }
    }

    @Test
    fun machineConfigToBuilderCopiesEveryValue() {
        val c = MachineConfig.builder()
            .fuelPerTick(3_000_000).fuelSlice(400_000).maxProcesses(11).maxBlockingProcesses(3).maxHostCallsPerTick(5)
            .memoryBytes(8L shl 20).maxProcessMemoryBytes(4L shl 20).maxDevices(6).maxEventQueues(7).maxPendingEventBytes(9000)
            .maxArmedTimers(13).hostFilesPerMachine(17).consoleInputBytes(1000).consoleOutputBytes(3000).maxPendingConsoleBytes(5000)
            .build()
        assertTrue(c.toBuilder().build().sameValues(c))
        assertTrue(c.withFuelSlice(400_000).sameValues(c))
        // 明示していないスライスは、燃料を変えると決まり直す
        val d = MachineConfig.DEFAULT.toBuilder().fuelPerTick(4_000_000).build()
        assertEquals(1_000_000L, d.fuelSliceValue)
    }

    private fun mapSource(vararg pairs: Pair<ConfigEntry<*>, Any>): ConfigSource {
        val map = pairs.toMap()
        return ConfigSource { map[it] }
    }
}
