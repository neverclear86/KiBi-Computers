package pro.ikili.kibicomputers.neoforge.client

import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.common.NeoForge
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks

/** クライアントでだけ行う登録（描画など）。 */
object KiBiClient {
    /** MOD のイベントバスに登録する。クライアントの MOD の初期化で1回だけ呼ぶ。 */
    fun register(modBus: IEventBus) {
        modBus.addListener(::registerRenderers)
        NeoForge.EVENT_BUS.addListener<ClientTickEvent.Post>(ClientScreenWatcher::onClientTick)
    }

    private fun registerRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerBlockEntityRenderer(KiBiBlocks.SCREEN_BLOCK_ENTITY.get(), ::ScreenRenderer)
    }
}
