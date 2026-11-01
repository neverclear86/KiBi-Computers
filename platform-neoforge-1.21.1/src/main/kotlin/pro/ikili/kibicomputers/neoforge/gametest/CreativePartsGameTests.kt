package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.GameType
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.computer.PartTier
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.computer.CaseParts
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks

/** 検証用のクリエイティブのティアの部品（#347）の GameTest。 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object CreativePartsGameTests {
    private val POS = BlockPos(1, 1, 1)
    private val KINDS = listOf(PartKind.CPU, PartKind.MEMORY, PartKind.STORAGE, PartKind.BOOT)

    /** 部品は自分の種類のタグに入っていて、その種類のスロットにだけ挿せる。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun creativePartsFitOnlyTheirSlots(helper: GameTestHelper) {
        helper.setBlock(POS, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        val be = helper.getBlockEntity<CaseBlockEntity>(POS)
        for (kind in KINDS) {
            val stack = ItemStack(KiBiBlocks.part(PartTier.CREATIVE, kind))
            helper.assertTrue(stack.`is`(CaseParts.tagOf(kind)), "creative $kind should be in its tag")
            for (slot in 0 until be.tier.slotCount) {
                val fits = be.tier.kindOf(slot) == kind
                helper.assertTrue(be.parts.isItemValid(slot, stack) == fits, "creative $kind in slot $slot should fit: $fits")
            }
        }
        helper.succeed()
    }

    /** タグの検査を通して（insertItem で）部品を挿すと起動でき、マシンの設定は部品から決まる。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun insertedCreativePartsBootMachine(helper: GameTestHelper) {
        helper.setBlock(POS, KiBiBlocks.CASES.getValue(CaseTier.SUPERIOR).get())
        val be = helper.getBlockEntity<CaseBlockEntity>(POS)
        for (kind in KINDS) {
            val slot = be.tier.firstSlot(kind)
            val rest = be.parts.insertItem(slot, ItemStack(KiBiBlocks.part(PartTier.CREATIVE, kind)), false)
            helper.assertTrue(rest.isEmpty, "creative $kind should be accepted by slot $slot")
        }
        // 2 本目の RAM
        val ram2 = be.tier.firstSlot(PartKind.MEMORY) + 1
        helper.assertTrue(be.parts.insertItem(ram2, ItemStack(KiBiBlocks.part(PartTier.CREATIVE, PartKind.MEMORY)), false).isEmpty, "second RAM should fit")

        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case with creative parts should run but was ${be.computer}")
        val config = be.computer.machine!!.config
        helper.assertTrue(config.fuelPerTick == PartTier.CREATIVE.cpuFuelPerTick, "fuel should come from the CPU but was ${config.fuelPerTick}")
        helper.assertTrue(config.memoryBytes == PartTier.CREATIVE.memoryBytes * 2, "RAM should be the sum of two sticks but was ${config.memoryBytes}")
        be.pressPowerButton()
        helper.succeed()
    }

    /** クリエイティブのケースを置くと、起動に要る部品がすべて挿さっていて、すぐ起動できる。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun creativeCaseIsPrefilled(helper: GameTestHelper) {
        val stack = KiBiBlocks.creativeCase(helper.level.registryAccess())
        val player = helper.makeMockPlayer(GameType.CREATIVE)
        player.setItemInHand(InteractionHand.MAIN_HAND, stack.copy())
        helper.placeAt(player, stack, POS.below(), Direction.UP)
        val be = helper.getBlockEntity<CaseBlockEntity>(POS)
        helper.assertTrue(be.tier == CaseTier.entries.last(), "creative case should be the top tier but was ${be.tier}")
        for (slot in 0 until be.tier.slotCount) {
            val kind = be.tier.kindOf(slot)
            val item = be.parts.getStackInSlot(slot)
            if (kind in KINDS) {
                helper.assertTrue(item.`is`(KiBiBlocks.part(PartTier.CREATIVE, kind)), "slot $slot ($kind) should hold a creative part but held $item")
            } else {
                helper.assertTrue(item.isEmpty, "slot $slot ($kind) should be empty")
            }
        }
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "creative case should run but was ${be.computer}")
        val ramSlots = be.tier.slotCount(PartKind.MEMORY)
        helper.assertTrue(be.computer.machine!!.config.memoryBytes == PartTier.CREATIVE.memoryBytes * ramSlots, "RAM should fill every slot")
        be.pressPowerButton()
        helper.succeed()
    }
}
