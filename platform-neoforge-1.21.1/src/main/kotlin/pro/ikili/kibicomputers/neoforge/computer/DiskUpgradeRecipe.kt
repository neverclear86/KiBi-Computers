package pro.ikili.kibicomputers.neoforge.computer

import com.mojang.serialization.MapCodec
import net.minecraft.core.HolderLookup
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingBookCategory
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.ShapedRecipe
import net.minecraft.world.item.crafting.ShapedRecipePattern
import pro.ikili.kibicomputers.neoforge.registry.KiBiRecipes

/**
 * 内蔵ディスクを上のティアにするクラフトのレシピ（#437、ADR 0124）。材料・形・結果は `crafting_shaped` と同じで、
 * 結果に材料のディスクのデータコンポーネント（`kibicomputers:disk_id`、金床で付けた名前など）をそのまま写す。
 * 識別子が写るので、中身（`<world>/kibicomputers/disks/<id>/`）はそのままで、容量だけ結果のティアのものになる。
 *
 * - 識別子のないディスクからは、識別子のないディスクができる（クラフトしたときには付けない。ADR 0124）
 * - 形の正しくない識別子も、検査せずにそのまま写す（判断は使う側の [DiskItem.diskIdOf] だけが行う）
 * - レシピ本の配置（`PlaceRecipe`）や JEI・EMI が shaped として扱うよう、包まずに [ShapedRecipe] を継承する
 */
class DiskUpgradeRecipe(
    group: String,
    category: CraftingBookCategory,
    pattern: ShapedRecipePattern,
    result: ItemStack,
    showNotification: Boolean,
) : ShapedRecipe(group, category, pattern, result, showNotification) {
    /** 材料のディスク（[CraftingInput] は空きを詰めた形なので、位置ではなく種類で探す）を、結果のアイテムに替えて返す */
    override fun assemble(input: CraftingInput, registries: HolderLookup.Provider): ItemStack {
        val disk = input.items().firstOrNull { it.item is DiskItem } ?: return super.assemble(input, registries)
        val result = getResultItem(registries)
        // SmithingTransformRecipe と同じ形: 材料の差分を写し、レシピの結果の差分を上に重ねる
        val upgraded = disk.transmuteCopy(result.item, result.count)
        upgraded.applyComponents(result.componentsPatch)
        return upgraded
    }

    override fun getSerializer(): RecipeSerializer<*> = KiBiRecipes.DISK_UPGRADE.get()

    /** `kibicomputers:disk_upgrade` のシリアライザー。JSON とネットワークの形は `crafting_shaped` と同じ */
    class Serializer : RecipeSerializer<DiskUpgradeRecipe> {
        override fun codec(): MapCodec<DiskUpgradeRecipe> = CODEC

        override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, DiskUpgradeRecipe> = STREAM_CODEC

        private companion object {
            // ShapedRecipe の result などはパッケージ private なので、公開されたアクセサーから読む（provider は使われない）
            fun wrap(recipe: ShapedRecipe): DiskUpgradeRecipe = recipe as? DiskUpgradeRecipe ?: DiskUpgradeRecipe(
                recipe.group,
                recipe.category(),
                recipe.pattern,
                recipe.getResultItem(RegistryAccess.EMPTY),
                recipe.showNotification(),
            )

            val CODEC: MapCodec<DiskUpgradeRecipe> = ShapedRecipe.Serializer.CODEC.xmap(::wrap) { it }

            val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, DiskUpgradeRecipe> =
                ShapedRecipe.Serializer.STREAM_CODEC.map(::wrap) { it }
        }
    }
}
