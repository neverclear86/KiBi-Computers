package pro.ikili.kibicomputers.neoforge

import com.mojang.logging.LogUtils
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.runtime.RuntimeSelfCheck
import net.neoforged.fml.common.Mod
import org.slf4j.Logger

/** NeoForge 1.21.1 向けのエントリポイント。 */
@Mod(KiBiComputers.MOD_ID)
object KiBiComputersNeoForge {
    private val LOGGER: Logger = LogUtils.getLogger()

    init {
        LOGGER.info("Loading {}", KiBiComputers.MOD_NAME)
        runCatching { RuntimeSelfCheck.add(1, 2) }
            .onSuccess { LOGGER.info("WASM runtime self-check passed (1 + 2 = {})", it) }
            .onFailure { LOGGER.error("WASM runtime self-check failed", it) }
    }
}
