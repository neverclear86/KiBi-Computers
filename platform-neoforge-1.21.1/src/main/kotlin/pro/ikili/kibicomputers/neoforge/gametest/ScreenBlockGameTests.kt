package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.display.ScreenTier
import pro.ikili.kibicomputers.neoforge.display.ScreenBlock
import pro.ikili.kibicomputers.neoforge.display.ScreenBlockEntity
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks

/** 画面ブロック（#136）の GameTest。 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object ScreenBlockGameTests {
    private val POS = BlockPos(1, 1, 1)

    /** どのティアの画面も置けて、ティアに合った大きさの画面バッファを持つ。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun screenOfEachTierHasBufferOfTierSize(helper: GameTestHelper) {
        for (tier in ScreenTier.entries) {
            helper.setBlock(POS, KiBiBlocks.SCREENS.getValue(tier).get())
            val be = helper.getBlockEntity<ScreenBlockEntity>(POS)
            helper.assertTrue(be.tier == tier, "block entity tier should be $tier but was ${be.tier}")
            helper.assertTrue(
                be.text.width == tier.maxColumns && be.text.height == tier.maxRows,
                "$tier text buffer should be ${tier.maxColumns}x${tier.maxRows} but was ${be.text.width}x${be.text.height}",
            )
            helper.assertTrue((be.pixels != null) == tier.supportsPixels, "$tier pixel buffer presence")
        }
        helper.succeed()
    }

    /** 置いたプレイヤーの方を向き、構造物の回転にも追従する。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun screenFacesPlacerAndRotates(helper: GameTestHelper) {
        val block = KiBiBlocks.SCREENS.getValue(ScreenTier.MONOCHROME).get()
        val player = helper.makeMockPlayer(GameType.CREATIVE)
        player.setYRot(0f) // 南（+Z）を向いている
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(block))
        helper.placeAt(player, ItemStack(block), POS.below(), Direction.UP)
        val placed = helper.getBlockState(POS)
        helper.assertTrue(placed.block == block, "screen should be placed but was ${placed.block}")
        helper.assertTrue(
            placed.getValue(ScreenBlock.FACING) == Direction.NORTH,
            "screen should face the player (north) but faced ${placed.getValue(ScreenBlock.FACING)}",
        )
        val rotated = placed.rotate(Rotation.CLOCKWISE_90)
        helper.assertTrue(rotated.getValue(ScreenBlock.FACING) == Direction.EAST, "rotated screen should face east")
        helper.succeed()
    }

    /** ツルハシで壊すと、そのティアの画面ブロック自身を落とす。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun screenDropsItself(helper: GameTestHelper) {
        for (tier in ScreenTier.entries) {
            val block = KiBiBlocks.SCREENS.getValue(tier).get()
            helper.setBlock(POS, block)
            val state = helper.getBlockState(POS)
            val params = LootParams.Builder(helper.level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(helper.absolutePos(POS)))
                .withParameter(LootContextParams.TOOL, ItemStack(Items.IRON_PICKAXE))
                .withOptionalParameter(LootContextParams.BLOCK_ENTITY, helper.getBlockEntity<ScreenBlockEntity>(POS))
            val drops = state.getDrops(params)
            helper.assertTrue(
                drops.size == 1 && drops[0].`is`(block.asItem()) && drops[0].count == 1,
                "$tier screen should drop itself but dropped $drops",
            )
        }
        helper.succeed()
    }
}
