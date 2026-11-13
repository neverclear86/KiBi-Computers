package pro.ikili.kibicomputers.neoforge.registry

import net.minecraft.core.registries.Registries
import net.minecraft.world.item.crafting.RecipeSerializer
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.neoforge.computer.DiskUpgradeRecipe

/** レシピのシリアライザーの登録。 */
object KiBiRecipes {
    private val SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, KiBiComputers.MOD_ID)

    /** 内蔵ディスクを上のティアにするクラフト（`kibicomputers:disk_upgrade`。#437）。レシピの種類は vanilla のクラフトのまま */
    val DISK_UPGRADE: DeferredHolder<RecipeSerializer<*>, DiskUpgradeRecipe.Serializer> =
        SERIALIZERS.register("disk_upgrade") { -> DiskUpgradeRecipe.Serializer() }

    /** MOD のイベントバスに登録する。MOD の初期化で1回だけ呼ぶ。 */
    fun register(modBus: IEventBus) {
        SERIALIZERS.register(modBus)
    }
}
