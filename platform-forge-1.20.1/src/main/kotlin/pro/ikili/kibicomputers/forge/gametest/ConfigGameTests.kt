package pro.ikili.kibicomputers.forge.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.config.ConfigTracker
import net.minecraftforge.fml.config.IConfigEvent
import net.minecraftforge.fml.config.ModConfig
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.config.ConfigSide
import pro.ikili.kibicomputers.core.config.KiBiConfig
import pro.ikili.kibicomputers.forge.config.KiBiConfigs

/**
 * コンフィグ（#202）の GameTest: SERVER の spec が登録され、ワールドを開くときに読まれて core の写しができている。
 * 読み込み・読み直しのイベントは、MOD のバスの購読（KFF のバスをリフレクションで取る）で受けていること。
 * 予備の購読（`ServerAboutToStartEvent`）でも読み込みは立つので、それとは分けて確かめる。
 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object ConfigGameTests {
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun serverConfigIsLoaded(helper: GameTestHelper) {
        helper.assertTrue(KiBiConfigs.isRegistered(ConfigSide.SERVER), "the server config should be registered")
        helper.assertTrue(KiBiConfigs.isLoaded(ConfigSide.SERVER), "the server config should be loaded when the server starts")
        // MOD のバスが取れていなければ、読み直しも効かない（予備の購読は読み込みだけ）
        helper.assertTrue(
            KiBiConfigs.isLoadedByEvent(ConfigSide.SERVER),
            "ModConfigEvent.Loading should reach KiBiConfigs through the mod event bus",
        )
        // 写しから core の設定を組み立てられる（範囲は spec が直している）
        val values = KiBiConfigs.server
        helper.assertTrue(values.rejected.isEmpty(), "no value should be rejected: ${values.rejected}")
        KiBiConfig.machineSaveConfig(values)
        KiBiConfig.screenSyncTraffic<Any>(values)
        helper.succeed()
    }

    /** 読み直しのイベント（FML がファイルの変更で出すのと同じもの）で、新しい値の写しができる */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, batch = "kibicomputers_config_reload")
    fun reloadedValuesReachTheState(helper: GameTestHelper) {
        val entry = KiBiConfig.RESTORES_PER_TICK
        val spec = KiBiConfigs.specValue(entry) ?: throw IllegalStateException("restoresPerTick should have a spec value")
        val original = spec.get() as Int
        val bumped = original + 1
        try {
            spec.set(bumped)
            val container = ModList.get().getModContainerById(KiBiComputers.MOD_ID).orElseThrow()
            val config = ConfigTracker.INSTANCE.configSets()[ModConfig.Type.SERVER]!!.first { it.modId == KiBiComputers.MOD_ID }
            container.dispatchConfigEvent(IConfigEvent.reloading(config))
            helper.assertTrue(
                KiBiConfigs.isReloadedByEvent(ConfigSide.SERVER),
                "ModConfigEvent.Reloading should reach KiBiConfigs through the mod event bus",
            )
            helper.assertTrue(
                KiBiConfigs.server[entry] == bumped,
                "the reloaded value should reach the state (was ${KiBiConfigs.server[entry]})",
            )
        } finally {
            spec.set(original)
            ModList.get().getModContainerById(KiBiComputers.MOD_ID).orElseThrow().dispatchConfigEvent(
                IConfigEvent.reloading(ConfigTracker.INSTANCE.configSets()[ModConfig.Type.SERVER]!!.first { it.modId == KiBiComputers.MOD_ID }),
            )
        }
        helper.assertTrue(KiBiConfigs.server[entry] == original, "restoring the value should be reflected")
        helper.succeed()
    }
}
