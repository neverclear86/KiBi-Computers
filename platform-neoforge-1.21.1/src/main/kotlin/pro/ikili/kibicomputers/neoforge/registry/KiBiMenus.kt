package pro.ikili.kibicomputers.neoforge.registry

import net.minecraft.core.registries.Registries
import net.minecraft.world.inventory.MenuType
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.neoforge.computer.CaseMenu

/** GUI（メニュー）の種類の登録。 */
object KiBiMenus {
    private val MENUS = DeferredRegister.create(Registries.MENU, KiBiComputers.MOD_ID)

    /** ケースの GUI（#121）。すべてのティアで共通。ティアは開くときの追加データで送る */
    val CASE: DeferredHolder<MenuType<*>, MenuType<CaseMenu>> =
        MENUS.register("case") { -> IMenuTypeExtension.create(CaseMenu::client) }

    /** MOD のイベントバスに登録する。MOD の初期化で1回だけ呼ぶ。 */
    fun register(modBus: IEventBus) {
        MENUS.register(modBus)
    }
}
