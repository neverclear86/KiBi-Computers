package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.ComputerError
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.neoforge.computer.CaseBlock
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.computer.CaseMenu
import pro.ikili.kibicomputers.neoforge.machine.MachineKey
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks

/** ケースブロック（#121）の GameTest。 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object CaseBlockGameTests {
    private val POS = BlockPos(1, 1, 1)

    private fun place(helper: GameTestHelper, tier: CaseTier): CaseBlockEntity {
        helper.setBlock(POS, KiBiBlocks.CASES.getValue(tier).get())
        return helper.getBlockEntity(POS)
    }

    /**
     * 起動に要る種類のスロットに、1つずつアイテムを入れる。部品のアイテム（#122〜#125）はまだないので、
     * 種類を確かめない [net.neoforged.neoforge.items.ItemStackHandler.setStackInSlot] で入れる。
     */
    private fun installRequired(be: CaseBlockEntity) {
        for (kind in PartKind.entries.filter { it.required }) {
            be.parts.setStackInSlot(be.tier.firstSlot(kind), ItemStack(Items.IRON_INGOT))
        }
    }

    /** どのティアのケースも置けて、ティアの数の部品スロットを持つ。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun caseOfEachTierHasSlotsOfTier(helper: GameTestHelper) {
        for (tier in CaseTier.entries) {
            val be = place(helper, tier)
            helper.assertTrue(be.tier == tier, "block entity tier should be $tier but was ${be.tier}")
            helper.assertTrue(be.parts.slots == tier.slotCount, "$tier should have ${tier.slotCount} slots but had ${be.parts.slots}")
            helper.assertTrue(be.computer.status == ComputerStatus.OFF, "new case should be off")
            // 部品でないアイテムは挿せない。挿せても1つまで
            helper.assertFalse(be.parts.isItemValid(0, ItemStack(Items.DIRT)), "dirt should not fit a part slot")
            helper.assertTrue(be.parts.getSlotLimit(0) == 1, "part slots should hold one item")
            helper.assertTrue(
                be.parts.insertItem(0, ItemStack(Items.DIRT), false).count == 1,
                "inserting a non-part should be refused",
            )
            val menu = CaseMenu.server(0, helper.makeMockPlayer(GameType.SURVIVAL).inventory, be)
            helper.assertTrue(menu.partSlotCount == tier.slotCount, "menu should show ${tier.slotCount} part slots")
            helper.assertTrue(menu.slots.size == tier.slotCount + 36, "menu should also show the player inventory")
        }
        helper.succeed()
    }

    /** 置いたプレイヤーの方を向く。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun caseFacesPlacer(helper: GameTestHelper) {
        val block = KiBiBlocks.CASES.getValue(CaseTier.BASIC).get()
        val player = helper.makeMockPlayer(GameType.CREATIVE)
        player.setYRot(0f) // 南（+Z）を向いている
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(block))
        helper.placeAt(player, ItemStack(block), POS.below(), Direction.UP)
        val placed = helper.getBlockState(POS)
        helper.assertTrue(placed.block == block, "case should be placed but was ${placed.block}")
        helper.assertTrue(placed.getValue(CaseBlock.FACING) == Direction.NORTH, "case should face the player (north)")
        helper.succeed()
    }

    /** 部品が足りなければ、電源ボタンでエラーになり、足りない部品を GUI に出す。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun powerWithoutPartsShowsError(helper: GameTestHelper) {
        val be = place(helper, CaseTier.BASIC)
        val player = helper.makeMockPlayer(GameType.SURVIVAL)
        val menu = CaseMenu.server(0, player.inventory, be)
        helper.assertTrue(menu.clickMenuButton(player, CaseMenu.POWER_BUTTON), "power button should be handled")
        helper.assertTrue(menu.status == ComputerStatus.ERROR, "status should be ERROR but was ${menu.status}")
        helper.assertTrue(menu.error == ComputerError.MISSING_PARTS, "error should be MISSING_PARTS but was ${menu.error}")
        helper.assertTrue(
            menu.missingParts == listOf(PartKind.CPU, PartKind.MEMORY, PartKind.STORAGE, PartKind.BOOT),
            "missing parts were ${menu.missingParts}",
        )
        helper.assertTrue(ServerMachines.current!![MachineKey.of(helper.level, helper.absolutePos(POS))] == null, "no machine should be created")
        helper.succeed()
    }

    /** 部品がそろえば電源で動き、もう一度押す・必須の部品を抜く・壊すと止まる。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun powerButtonStartsAndStopsMachine(helper: GameTestHelper) {
        val manager = ServerMachines.current!!
        val key = MachineKey.of(helper.level, helper.absolutePos(POS))
        val be = place(helper, CaseTier.ADVANCED)
        installRequired(be)
        val player = helper.makeMockPlayer(GameType.SURVIVAL)
        val menu = CaseMenu.server(0, player.inventory, be)

        menu.clickMenuButton(player, CaseMenu.POWER_BUTTON)
        helper.assertTrue(menu.status == ComputerStatus.RUNNING, "status should be RUNNING but was ${menu.status}")
        helper.assertTrue(manager[key] != null && manager[key] === be.computer.machine, "running case should own the listed machine")
        menu.clickMenuButton(player, CaseMenu.POWER_BUTTON)
        helper.assertTrue(menu.status == ComputerStatus.OFF, "second press should turn it off")
        helper.assertTrue(manager[key] == null, "machine should be released when turned off")

        be.pressPowerButton()
        be.parts.setStackInSlot(be.tier.firstSlot(PartKind.CPU), ItemStack.EMPTY)
        helper.assertTrue(menu.status == ComputerStatus.ERROR && menu.missingParts == listOf(PartKind.CPU), "removing the CPU should stop it")
        helper.assertTrue(manager[key] == null, "machine should be released when the CPU is removed")

        installRequired(be)
        be.pressPowerButton()
        helper.assertTrue(manager[key] != null, "machine should run again")
        helper.setBlock(POS, Blocks.AIR)
        helper.assertTrue(manager[key] == null, "machine should be released when the case is broken")
        helper.succeed()
    }

    /** 部品はセーブデータに残り、電源の状態は残らない。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun partsAreSavedButPowerIsNot(helper: GameTestHelper) {
        val be = place(helper, CaseTier.SUPERIOR)
        installRequired(be)
        be.pressPowerButton()
        val registries = helper.level.registryAccess()
        val tag = be.saveWithoutMetadata(registries)
        be.pressPowerButton()

        val copy = CaseBlockEntity(be.blockPos, be.blockState)
        copy.loadWithComponents(tag, registries)
        for (slot in 0 until be.tier.slotCount) {
            helper.assertTrue(
                ItemStack.matches(copy.parts.getStackInSlot(slot), be.parts.getStackInSlot(slot)),
                "slot $slot should be restored",
            )
        }
        helper.assertTrue(copy.computer.status == ComputerStatus.OFF, "loaded case should be off")
        helper.succeed()
    }

    /** 壊すとケース自身を落とし、挿していた部品も落とす。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun caseDropsItselfAndParts(helper: GameTestHelper) {
        for (tier in CaseTier.entries) {
            val block = KiBiBlocks.CASES.getValue(tier).get()
            val be = place(helper, tier)
            val state = helper.getBlockState(POS)
            val params = LootParams.Builder(helper.level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(helper.absolutePos(POS)))
                .withParameter(LootContextParams.TOOL, ItemStack(Items.IRON_PICKAXE))
                .withOptionalParameter(LootContextParams.BLOCK_ENTITY, be)
            val drops = state.getDrops(params)
            helper.assertTrue(
                drops.size == 1 && drops[0].`is`(block.asItem()) && drops[0].count == 1,
                "$tier case should drop itself but dropped $drops",
            )
        }
        val be = place(helper, CaseTier.BASIC)
        be.parts.setStackInSlot(0, ItemStack(Items.IRON_INGOT))
        be.parts.setStackInSlot(be.tier.firstSlot(PartKind.EXPANSION), ItemStack(Items.GOLD_INGOT))
        helper.setBlock(POS, Blocks.AIR)
        val area = AABB(helper.absolutePos(POS)).inflate(2.0)
        val items = helper.level.getEntitiesOfClass(ItemEntity::class.java, area).map { it.item.item }
        helper.assertTrue(Items.IRON_INGOT in items && Items.GOLD_INGOT in items, "parts should drop but dropped $items")
        helper.succeed()
    }
}
