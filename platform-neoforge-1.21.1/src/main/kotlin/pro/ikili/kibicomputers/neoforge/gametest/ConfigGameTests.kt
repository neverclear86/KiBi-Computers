package pro.ikili.kibicomputers.neoforge.gametest

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.config.ConfigSide
import pro.ikili.kibicomputers.core.config.ConfigSource
import pro.ikili.kibicomputers.core.config.ConfigValues
import pro.ikili.kibicomputers.core.config.KiBiConfig
import pro.ikili.kibicomputers.neoforge.config.KiBiConfigs
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines

/** コンフィグ（#202）の GameTest: spec の登録、サーバーの起動での値の受け渡し、読み直しの反映、lang のキー。 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object ConfigGameTests {
    /** SERVER のコンフィグが、MachineManager を作る前に読まれ、その値でスケジューラと保存の設定が作られている */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun serverConfigReachesTheScheduler(helper: GameTestHelper) {
        helper.assertTrue(KiBiConfigs.isRegistered(ConfigSide.SERVER), "the server config should be registered")
        val values = KiBiConfigs.server
        val scheduler = ServerMachines.schedulerConfig
        helper.assertTrue(scheduler != null, "the scheduler config should be recorded")
        val expected = KiBiConfig.schedulerConfig(values, scheduler!!.moduleStore)
        helper.assertTrue(scheduler.compilerThreads == expected.compilerThreads, "compilerThreads should come from the config")
        helper.assertTrue(scheduler.maxConcurrentMachines == expected.maxConcurrentMachines, "maxConcurrentMachines should come from the config")
        helper.assertTrue(scheduler.totalMemoryBytes == expected.totalMemoryBytes, "totalMemoryBytes should come from the config")
        helper.assertTrue(scheduler.programCacheBytes == expected.programCacheBytes, "programCacheBytes should come from the config")
        helper.succeed()
    }

    /** 読み直しで差し替えた写しが、次の tick に保存の設定に当たり、戻すと元に戻る */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun reloadedValuesApplyOnTheNextTick(helper: GameTestHelper) {
        val state = KiBiConfigs.state(ConfigSide.SERVER)
        val original = state.values
        val bumped = original[KiBiConfig.RESTORES_PER_TICK] + 1
        val next = ConfigValues.resolve(
            ConfigSource { if (it === KiBiConfig.RESTORES_PER_TICK) bumped else original[it] },
            KiBiConfig.entries(ConfigSide.SERVER),
        )
        val changed = state.update(next)
        helper.assertTrue(changed == listOf(KiBiConfig.RESTORES_PER_TICK), "only restoresPerTick should change: $changed")
        helper.runAfterDelay(2) {
            try {
                // ほかのテストが保存の設定を差し替えている間は、そちらが優先される
                val save = ServerMachines.saveConfig
                helper.assertTrue(
                    save.restoresPerTick == bumped || save != KiBiConfig.machineSaveConfig(original),
                    "the reloaded restoresPerTick should apply (was ${save.restoresPerTick})",
                )
            } finally {
                state.update(original)
            }
            helper.succeed()
        }
    }

    /** すべての項目と区画に、en_us・ja_jp の名前と説明（`.tooltip`）がある */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun everyEntryIsTranslated(helper: GameTestHelper) {
        val keys = buildList {
            add(KiBiComputers.MOD_ID + ".configuration.title")
            for (section in KiBiConfig.SECTION_COMMENTS.keys) {
                add(KiBiConfig.sectionTranslationKey(section))
                add(KiBiConfig.sectionTranslationKey(section) + ".tooltip")
            }
            for (entry in KiBiConfig.ENTRIES) {
                add(entry.translationKey)
                add(entry.translationKey + ".tooltip")
            }
        }
        for (lang in listOf("en_us", "ja_jp")) {
            val json = lang(lang)
            val missing = keys.filter { !json.has(it) || json.get(it).asString.isBlank() }
            helper.assertTrue(missing.isEmpty(), "$lang is missing config translations: $missing")
        }
        helper.succeed()
    }

    private fun lang(name: String): JsonObject {
        val stream = ConfigGameTests::class.java.getResourceAsStream("/assets/${KiBiComputers.MOD_ID}/lang/$name.json")
            ?: error("lang file $name.json not found")
        return stream.reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
    }
}
