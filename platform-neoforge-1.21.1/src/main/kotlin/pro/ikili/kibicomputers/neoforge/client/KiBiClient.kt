package pro.ikili.kibicomputers.neoforge.client

import net.minecraft.client.Minecraft
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent
import pro.ikili.kibicomputers.neoforge.input.KeyboardInput
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import pro.ikili.kibicomputers.neoforge.registry.KiBiMenus

/** クライアントでだけ行う登録（描画など）。 */
object KiBiClient {
    /** MOD のイベントバスに登録する。クライアントの MOD の初期化で1回だけ呼ぶ。 */
    fun register(modBus: IEventBus) {
        modBus.addListener(::registerRenderers)
        modBus.addListener(::registerScreens)
        KeyboardInput.clientOpener = KeyboardInput.ClientOpener { keyboard, screen ->
            Minecraft.getInstance().setScreen(KeyboardInputScreen(keyboard, screen))
        }
    }

    private fun registerRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerBlockEntityRenderer(KiBiBlocks.SCREEN_BLOCK_ENTITY.get(), ::ScreenRenderer)
    }

    private fun registerScreens(event: RegisterMenuScreensEvent) {
        event.register(KiBiMenus.CASE.get(), ::CaseScreen)
    }
}
