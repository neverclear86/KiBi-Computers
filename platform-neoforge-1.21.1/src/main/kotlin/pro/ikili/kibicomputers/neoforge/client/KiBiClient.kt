package pro.ikili.kibicomputers.neoforge.client

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent
import net.neoforged.neoforge.common.NeoForge
import pro.ikili.kibicomputers.neoforge.input.KeyboardInput
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import pro.ikili.kibicomputers.neoforge.registry.KiBiMenus

/** クライアントでだけ行う登録（描画など）。 */
object KiBiClient {
    /** MOD のイベントバスに登録する。クライアントの MOD の初期化で1回だけ呼ぶ。 */
    fun register(modBus: IEventBus) {
        modBus.addListener(::registerRenderers)
        modBus.addListener(::registerScreens)
        NeoForge.EVENT_BUS.addListener<ClientTickEvent.Post>(ClientScreenWatcher::onClientTick)
        KeyboardInput.clientHooks = object : KeyboardInput.ClientHooks {
            override fun open(session: Int, keyboard: BlockPos, screen: BlockPos?) {
                Minecraft.getInstance().setScreen(KeyboardInputScreen(session, keyboard, screen))
            }

            override fun closed(session: Int) {
                val open = Minecraft.getInstance().screen as? KeyboardInputScreen ?: return
                if (open.session == session) open.closedByServer()
            }
        }
    }

    private fun registerRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerBlockEntityRenderer(KiBiBlocks.SCREEN_BLOCK_ENTITY.get(), ::ScreenRenderer)
    }

    private fun registerScreens(event: RegisterMenuScreensEvent) {
        event.register(KiBiMenus.CASE.get(), ::CaseScreen)
    }
}
