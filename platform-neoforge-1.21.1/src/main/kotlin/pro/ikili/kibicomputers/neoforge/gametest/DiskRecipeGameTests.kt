package pro.ikili.kibicomputers.neoforge.gametest

import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.item.crafting.ShapedRecipe
import net.minecraft.world.level.block.Blocks
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.computer.PartTier
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.computer.DiskItem
import pro.ikili.kibicomputers.neoforge.computer.DiskUpgradeRecipe
import pro.ikili.kibicomputers.neoforge.computer.WorldDisks
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import pro.ikili.kibicomputers.neoforge.registry.KiBiDataComponents
import pro.ikili.kibicomputers.neoforge.registry.KiBiRecipes
import java.nio.file.Files
import java.util.UUID

/** 内蔵ディスクを上のティアにするクラフト（#437、ADR 0124）の GameTest。 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object DiskRecipeGameTests {
    /** 起動を待つ tick 数の上限（[DiskItemGameTests] と同じ） */
    private const val BOOT_TIMEOUT_TICKS = 12_000

    private val CASE = BlockPos(1, 1, 1)

    private fun disk(tier: PartTier): ItemStack = ItemStack(KiBiBlocks.part(tier, PartKind.STORAGE))

    private fun tierOf(stack: ItemStack): PartTier? = (stack.item as? DiskItem)?.tier

    /** [tier] のディスクを作るレシピの ID（`kibicomputers:storage_<n>`） */
    private fun recipeId(tier: PartTier): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(KiBiComputers.MOD_ID, "storage_${tier.ordinal + 1}")

    /** 識別子の形（UUID の小文字）の値。ワールドのフォルダは作らない */
    private fun newId(): String = UUID.randomUUID().toString()

    /** [recipe] の形どおりに材料を並べ、ディスクの位置に [disk] を置いた入力 */
    private fun inputFor(recipe: ShapedRecipe, disk: ItemStack): CraftingInput {
        val pattern = recipe.pattern
        val items = pattern.ingredients().map { ingredient ->
            val choices = ingredient.items
            when {
                choices.isEmpty() -> ItemStack.EMPTY
                choices.any { it.item is DiskItem } -> disk
                else -> choices[0].copy()
            }
        }
        return CraftingInput.of(pattern.width(), pattern.height(), items)
    }

    /**
     * [disk] を材料に、`getRecipeFor`（作業台・クラフターと同じ探し方）で見つかるレシピで [to] のティアにする。
     * 読み込まれたレシピが [DiskUpgradeRecipe] で、結果が [to] のティアの1個であることも確かめる
     */
    private fun upgrade(helper: GameTestHelper, disk: ItemStack, to: PartTier): ItemStack {
        val level = helper.level
        val byId = level.recipeManager.byKey(recipeId(to)).orElse(null)
        helper.assertTrue(byId != null, "the recipe ${recipeId(to)} should be loaded")
        val recipe = byId!!.value()
        helper.assertTrue(recipe is DiskUpgradeRecipe, "${recipeId(to)} should be a DiskUpgradeRecipe but was ${recipe.javaClass}")
        val input = inputFor(recipe as ShapedRecipe, disk)
        val found = level.recipeManager.getRecipeFor(RecipeType.CRAFTING, input, level).orElse(null)
        helper.assertTrue(found != null && found.id() == recipeId(to), "the crafting grid should match ${recipeId(to)} but matched ${found?.id()}")
        val result = found!!.value().assemble(input, level.registryAccess())
        helper.assertTrue(tierOf(result) == to, "the result should be a $to disk but was ${result.item}")
        helper.assertTrue(result.count == 1, "the result should be one disk but was ${result.count}")
        return result
    }

    /** 識別子と金床で付けた名前は、上のティアにしても写る。材料は変えない。2→3→4 と続けても同じ識別子のまま */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun upgradeKeepsDiskIdAndName(helper: GameTestHelper) {
        val id = newId()
        val name = Component.literal("my disk")
        val first = disk(PartTier.TIER_1)
        first.set(KiBiDataComponents.DISK_ID.get(), id)
        first.set(DataComponents.CUSTOM_NAME, name)
        val firstCopy = first.copy()
        var stack = first
        for (tier in listOf(PartTier.TIER_2, PartTier.TIER_3, PartTier.TIER_4)) {
            val before = stack
            stack = upgrade(helper, before, tier)
            helper.assertTrue(before !== stack, "assemble should return a new stack")
            helper.assertTrue(DiskItem.diskIdOf(stack) == id, "the $tier disk should keep the ID $id but had ${DiskItem.diskIdOf(stack)}")
            helper.assertTrue(stack.get(DataComponents.CUSTOM_NAME) == name, "the $tier disk should keep its name but had ${stack.get(DataComponents.CUSTOM_NAME)}")
        }
        helper.assertTrue(ItemStack.isSameItemSameComponents(first, firstCopy), "the ingredient should not be changed")
        // レシピの結果の見本（レシピ本・クラフターのパターンの表示）は識別子を持たないまま
        val shown = helper.level.recipeManager.byKey(recipeId(PartTier.TIER_2)).get().value().getResultItem(helper.level.registryAccess())
        helper.assertTrue(!shown.has(KiBiDataComponents.DISK_ID.get()), "the recipe's result template should stay without an ID")
        helper.succeed()
    }

    /** 識別子のないディスクからは、識別子のないディスクができる（クラフトしたときには付けない） */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun upgradeWithoutIdStaysWithoutId(helper: GameTestHelper) {
        val result = upgrade(helper, disk(PartTier.TIER_1), PartTier.TIER_2)
        helper.assertTrue(!result.has(KiBiDataComponents.DISK_ID.get()), "a disk without an ID should stay without one")
        helper.assertTrue(result.componentsPatch.isEmpty, "the result should have no extra components but had ${result.componentsPatch}")
        helper.succeed()
    }

    /** 形の正しくない識別子は、検査も書き換えもせずにそのまま写る（使う側の [DiskItem.diskIdOf] が無視する） */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun upgradeCopiesInvalidIdAsIs(helper: GameTestHelper) {
        val stack = disk(PartTier.TIER_1)
        stack.set(KiBiDataComponents.DISK_ID.get(), "../evil")
        val result = upgrade(helper, stack, PartTier.TIER_2)
        helper.assertTrue(result.get(KiBiDataComponents.DISK_ID.get()) == "../evil", "an invalid ID should be copied as is")
        helper.assertTrue(DiskItem.diskIdOf(result) == null, "an invalid ID should still not be used")
        helper.succeed()
    }

    /** ネットワークの形（クライアントへの同期）で往復しても [DiskUpgradeRecipe] のままで、識別子を写す */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun upgradeRecipeSurvivesNetworkRoundTrip(helper: GameTestHelper) {
        val level = helper.level
        val holder = level.recipeManager.byKey(recipeId(PartTier.TIER_3)).get()
        val serializer = holder.value().serializer
        helper.assertTrue(serializer === KiBiRecipes.DISK_UPGRADE.get(), "the serializer should be disk_upgrade but was $serializer")
        val codec = KiBiRecipes.DISK_UPGRADE.get().streamCodec()
        val buf = RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess())
        codec.encode(buf, holder.value() as DiskUpgradeRecipe)
        val decoded: Any = codec.decode(buf)
        helper.assertTrue(buf.readableBytes() == 0, "the whole recipe should be read but ${buf.readableBytes()} bytes were left")
        helper.assertTrue(decoded is DiskUpgradeRecipe, "the decoded recipe should be a DiskUpgradeRecipe but was ${decoded.javaClass}")
        val original = holder.value() as ShapedRecipe
        val copy = decoded as ShapedRecipe
        helper.assertTrue(copy.width == original.width && copy.height == original.height, "the pattern size should be kept")
        helper.assertTrue(
            ItemStack.matches(copy.getResultItem(level.registryAccess()), original.getResultItem(level.registryAccess())),
            "the result should be kept",
        )
        val id = newId()
        val input = inputFor(copy, disk(PartTier.TIER_2).also { it.set(KiBiDataComponents.DISK_ID.get(), id) })
        helper.assertTrue(copy.matches(input, level), "the decoded recipe should match the same grid")
        val result = copy.assemble(input, level.registryAccess())
        helper.assertTrue(tierOf(result) == PartTier.TIER_3 && DiskItem.diskIdOf(result) == id, "the decoded recipe should keep the ID but made $result")
        helper.assertTrue(!copy.matches(inputFor(copy, ItemStack(Items.IRON_INGOT)), level), "a non-disk ingredient should not match")
        helper.succeed()
    }

    /** 上のティアにしたディスクで電源を入れると、同じ識別子のディスクとして起動に使われ、前の中身が残っている */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun upgradedDiskBootsWithSameData(helper: GameTestHelper) {
        val store = WorldDisks.current
        helper.assertTrue(store != null, "the server should have a disk store")
        val id = store!!.newId()
        val dir = store.directoryOf(id)!!
        Files.createDirectories(dir)
        val file = dir.resolve("keep.txt")
        Files.writeString(file, "kept")
        val upgraded = upgrade(helper, disk(PartTier.TIER_1).also { it.set(KiBiDataComponents.DISK_ID.get(), id) }, PartTier.TIER_2)
        helper.setBlock(CASE, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        val case = helper.getBlockEntity<CaseBlockEntity>(CASE)
        for (kind in PartKind.entries.filter { it.required && it != PartKind.BOOT && it != PartKind.STORAGE }) {
            case.parts.setStackInSlot(case.tier.firstSlot(kind), ItemStack(Items.IRON_INGOT))
        }
        val diskSlot = case.tier.firstSlot(PartKind.STORAGE)
        case.parts.setStackInSlot(diskSlot, upgraded)
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${case.computer}")
        helper.assertTrue(DiskItem.diskIdOf(case.parts.getStackInSlot(diskSlot)) == id, "the upgraded disk should keep its ID on power-on")
        val machine = case.computer.machine!!
        helper.assertTrue(machine.insertedDiskIds == setOf(id), "the machine should see the same disk but had ${machine.insertedDiskIds}")
        helper.assertTrue(machine.lastBootConfig!!.homeDisk == id, "the upgraded disk should be the home disk but was ${machine.lastBootConfig!!.homeDisk}")
        helper.assertTrue(Files.readString(file) == "kept", "the disk's data should be kept")
        case.pressPowerButton()
        helper.setBlock(CASE, Blocks.AIR)
        helper.succeed()
    }
}
