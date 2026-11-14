package pro.ikili.kibicomputers.neoforge

import com.mojang.logging.LogUtils
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.runtime.RuntimeSelfCheck
import pro.ikili.kibicomputers.neoforge.client.KiBiClient
import pro.ikili.kibicomputers.neoforge.computer.DeviceAttachments
import pro.ikili.kibicomputers.neoforge.computer.DeviceWiring
import pro.ikili.kibicomputers.neoforge.config.KiBiConfigs
import pro.ikili.kibicomputers.neoforge.computer.WorldDisks
import pro.ikili.kibicomputers.neoforge.display.ScreenSync
import pro.ikili.kibicomputers.neoforge.input.KeyboardInput
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines
import pro.ikili.kibicomputers.neoforge.machine.WorldMachineSaves
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import pro.ikili.kibicomputers.neoforge.registry.KiBiDataComponents
import pro.ikili.kibicomputers.neoforge.registry.KiBiMenus
import pro.ikili.kibicomputers.neoforge.registry.KiBiRecipes
import net.neoforged.fml.ModLoadingContext
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLEnvironment
import org.slf4j.Logger
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

/** NeoForge 1.21.1 向けのエントリポイント。 */
@Mod(KiBiComputers.MOD_ID)
object KiBiComputersNeoForge {
    private val LOGGER: Logger = LogUtils.getLogger()

    init {
        LOGGER.info("Loading {}", KiBiComputers.MOD_NAME)
        // core のログ（EndiveRuntime の WARNING など）を取りこぼさないよう、ほかより先に取り付ける
        CoreLogBridge.install(LOGGER)
        runCatching { RuntimeSelfCheck.add(1, 2) }
            .onSuccess { LOGGER.info("WASM runtime self-check passed (1 + 2 = {})", it) }
            .onFailure { LOGGER.error("WASM runtime self-check failed", it) }
        val container = ModLoadingContext.get().activeContainer
        // コンフィグ（#202）。SERVER のファイルはワールドを開くときに読まれる
        KiBiConfigs.register(container, MOD_BUS)
        KiBiDataComponents.register(MOD_BUS)
        DeviceAttachments.register(MOD_BUS)
        KiBiBlocks.register(MOD_BUS)
        KiBiMenus.register(MOD_BUS)
        KiBiRecipes.register(MOD_BUS)
        ScreenSync.register(MOD_BUS)
        KeyboardInput.register(MOD_BUS)
        ServerMachines.register()
        DeviceWiring.register()
        WorldDisks.register()
        WorldMachineSaves.register()
        // クライアントのクラスは、クライアントでだけ読み込む
        if (FMLEnvironment.dist.isClient) KiBiClient.register(container, MOD_BUS)
        startDevDriver()
        registerTestDevices()
    }

    // 開発環境の GameTest・負荷試験でだけ、他 MOD 役の試験用デバイス（dev ソースセット、#153）を登録する
    private fun registerTestDevices() {
        if (System.getProperty("kibicomputers.dev.testDevices") != "true") return
        runCatching {
            Class.forName("pro.ikili.kibicomputers.neoforge.dev.TestDevices", true, javaClass.classLoader)
                .getMethod("register", net.neoforged.bus.api.IEventBus::class.java)
                .invoke(null, MOD_BUS)
        }.onFailure { LOGGER.warn("Test devices could not be registered", it) }
    }

    // 開発環境の runClient でだけ、クライアントの自動操作の口（dev ソースセット、#252）を起動する
    private fun startDevDriver() {
        val port = System.getProperty("kibicomputers.devDriver.port") ?: return
        runCatching {
            Class.forName("pro.ikili.kibicomputers.neoforge.dev.DevDriver", true, javaClass.classLoader)
                .getMethod("start", Int::class.javaPrimitiveType, String::class.java)
                .invoke(null, port.toInt(), System.getProperty("kibicomputers.devDriver.portFile"))
        }.onFailure { LOGGER.warn("Dev driver could not start", it) }
    }
}
