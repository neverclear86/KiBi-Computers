package pro.ikili.kibicomputers.neoforge.client

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.neoforge.client.gui.ConfigurationScreen
import net.neoforged.neoforge.client.gui.IConfigScreenFactory
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent
import pro.ikili.kibicomputers.neoforge.input.KeyboardInput
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import pro.ikili.kibicomputers.neoforge.registry.KiBiMenus

/** クライアントでだけ行う登録（描画など）。 */
object KiBiClient {
    /** MOD のイベントバスに登録する。クライアントの MOD の初期化で1回だけ呼ぶ。 */
    fun register(container: ModContainer, modBus: IEventBus) {
        // MOD の一覧の「設定」ボタンから開く、NeoForge の組み込みの設定画面（#202。項目の名前と説明は lang）
        container.registerExtensionPoint(IConfigScreenFactory::class.java, IConfigScreenFactory(::ConfigurationScreen))
        modBus.addListener(::registerRenderers)
        modBus.addListener(::registerScreens)
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
