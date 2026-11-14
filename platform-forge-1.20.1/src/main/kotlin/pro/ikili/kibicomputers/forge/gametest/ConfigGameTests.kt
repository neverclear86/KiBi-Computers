package pro.ikili.kibicomputers.forge.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.config.ConfigSide
import pro.ikili.kibicomputers.core.config.KiBiConfig
import pro.ikili.kibicomputers.forge.config.KiBiConfigs

/** コンフィグ（#202）の GameTest: SERVER の spec が登録され、ワールドを開くときに読まれて core の写しができている。 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object ConfigGameTests {
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun serverConfigIsLoaded(helper: GameTestHelper) {
        helper.assertTrue(KiBiConfigs.isRegistered(ConfigSide.SERVER), "the server config should be registered")
        helper.assertTrue(KiBiConfigs.isLoaded(ConfigSide.SERVER), "the server config should be loaded when the server starts")
        // 写しから core の設定を組み立てられる（範囲は spec が直している）
        val values = KiBiConfigs.server
        helper.assertTrue(values.rejected.isEmpty(), "no value should be rejected: ${values.rejected}")
        KiBiConfig.machineSaveConfig(values)
        KiBiConfig.screenSyncTraffic<Any>(values)
        helper.succeed()
    }
}
