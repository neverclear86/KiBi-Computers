package pro.ikili.kibicomputers.neoforge

import com.mojang.logging.LogUtils
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.runtime.RuntimeSelfCheck
import pro.ikili.kibicomputers.neoforge.client.KiBiClient
import pro.ikili.kibicomputers.neoforge.display.ScreenSync
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import pro.ikili.kibicomputers.neoforge.registry.KiBiMenus
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
        runCatching { RuntimeSelfCheck.add(1, 2) }
            .onSuccess { LOGGER.info("WASM runtime self-check passed (1 + 2 = {})", it) }
            .onFailure { LOGGER.error("WASM runtime self-check failed", it) }
        KiBiBlocks.register(MOD_BUS)
        KiBiMenus.register(MOD_BUS)
        ScreenSync.register(MOD_BUS)
        ServerMachines.register()
        // クライアントのクラスは、クライアントでだけ読み込む
        if (FMLEnvironment.dist.isClient) KiBiClient.register(MOD_BUS)
        startDevDriver()
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
