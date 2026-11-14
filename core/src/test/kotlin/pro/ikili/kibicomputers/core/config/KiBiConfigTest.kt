package pro.ikili.kibicomputers.core.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.display.ScreenSyncTraffic
import pro.ikili.kibicomputers.core.input.KeyboardInputBudget
import pro.ikili.kibicomputers.core.kernel.SchedulerConfig
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

    private fun mapSource(vararg pairs: Pair<ConfigEntry<*>, Any>): ConfigSource {
        val map = pairs.toMap()
        return ConfigSource { map[it] }
    }
}
