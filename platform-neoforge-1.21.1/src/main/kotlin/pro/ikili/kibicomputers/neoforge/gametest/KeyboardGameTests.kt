package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.display.ScreenTier
import pro.ikili.kibicomputers.core.input.Key
import pro.ikili.kibicomputers.core.input.KeyModifiers
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.input.KeyboardEventCodec
import pro.ikili.kibicomputers.core.input.KeyboardListener
import pro.ikili.kibicomputers.core.input.TerminalKeyEncoder
import pro.ikili.kibicomputers.neoforge.display.ScreenBlock
import pro.ikili.kibicomputers.neoforge.input.KeyboardBlock
import pro.ikili.kibicomputers.neoforge.input.KeyboardBlockEntity
import pro.ikili.kibicomputers.neoforge.input.KeyboardInput
import pro.ikili.kibicomputers.neoforge.input.KeyboardInputPayload
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks

/** キーボードブロックと入力（#142）の GameTest。 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object KeyboardGameTests {
    private val POS = BlockPos(1, 1, 1)

    /** 置いたプレイヤーの方を向き、ツルハシで壊すと自身を落とす。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun keyboardFacesPlacerAndDropsItself(helper: GameTestHelper) {
        val block = KiBiBlocks.KEYBOARD.get()
        val player = helper.makeMockPlayer(GameType.CREATIVE)
        player.setYRot(0f) // 南（+Z）を向いている
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(block))
        helper.placeAt(player, ItemStack(block), POS.below(), Direction.UP)
        val state = helper.getBlockState(POS)
        helper.assertTrue(state.block == block, "keyboard should be placed but was ${state.block}")
        helper.assertTrue(state.getValue(KeyboardBlock.FACING) == Direction.NORTH, "keyboard should face the player")
        val params = LootParams.Builder(helper.level)
            .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(helper.absolutePos(POS)))
            .withParameter(LootContextParams.TOOL, ItemStack(Items.IRON_PICKAXE))
            .withOptionalParameter(LootContextParams.BLOCK_ENTITY, helper.getBlockEntity<KeyboardBlockEntity>(POS))
        val drops = state.getDrops(params)
        helper.assertTrue(drops.size == 1 && drops[0].`is`(block.asItem()), "keyboard should drop itself but dropped $drops")
        helper.succeed()
    }

    /** 画面の前・前の下・隣のキーボードを見つける。遠いもの・画面でないものは見つけない。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun screenFindsAdjacentKeyboard(helper: GameTestHelper) {
        val screen = BlockPos(1, 2, 1)
        val screenState = KiBiBlocks.SCREENS.getValue(ScreenTier.MONOCHROME).get().defaultBlockState()
            .setValue(ScreenBlock.FACING, Direction.SOUTH)
        helper.setBlock(screen, screenState)
        val absScreen = helper.absolutePos(screen)
        fun find() = KeyboardInput.findKeyboard(helper.level, absScreen)
        helper.assertTrue(find() == null, "no keyboard yet")
        // 正面の前の1つ下（机の上）
        val desk = screen.relative(Direction.SOUTH).below()
        helper.setBlock(desk, KiBiBlocks.KEYBOARD.get())
        helper.assertTrue(find() == helper.absolutePos(desk), "keyboard in front and below should be found but was ${find()}")
        helper.setBlock(desk, Blocks.AIR)
        // 横
        val side = screen.relative(Direction.EAST)
        helper.setBlock(side, KiBiBlocks.KEYBOARD.get())
        helper.assertTrue(find() == helper.absolutePos(side), "keyboard beside should be found")
        // 正面の前があれば、そちらを先に選ぶ
        val front = screen.relative(Direction.SOUTH)
        helper.setBlock(front, KiBiBlocks.KEYBOARD.get())
        helper.assertTrue(find() == helper.absolutePos(front), "keyboard in front should win")
        helper.setBlock(front, Blocks.AIR)
        helper.setBlock(side, Blocks.AIR)
        // 斜め上は対象外
        helper.setBlock(screen.relative(Direction.SOUTH).above(), KiBiBlocks.KEYBOARD.get())
        helper.assertTrue(find() == null, "diagonal keyboard above should not be found")
        // 画面でない位置からは探さない
        helper.assertTrue(KeyboardInput.findKeyboard(helper.level, helper.absolutePos(POS)) == null, "non-screen has no keyboard")
        helper.succeed()
    }

    /** 届いたパケットを読んで、登録した相手に配る。端末向けのバイト列にもできる。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun keyboardDeliversInputFromNearbyPlayer(helper: GameTestHelper) {
        helper.setBlock(POS, KiBiBlocks.KEYBOARD.get())
        val keyboard = helper.getBlockEntity<KeyboardBlockEntity>(POS)
        val received = ArrayList<KeyboardEvent>()
        keyboard.addListener(KeyboardListener { received += it })
        val player = helper.makeMockServerPlayerInLevel()
        player.moveTo(Vec3.atCenterOf(helper.absolutePos(POS.east())))
        val events = listOf(
            KeyboardEvent.KeyDown(Key.H, KeyModifiers.NONE, false),
            KeyboardEvent.Text("hé日本"),
            KeyboardEvent.KeyUp(Key.H, KeyModifiers.NONE),
            KeyboardEvent.KeyDown(Key.C, KeyModifiers.CONTROL, false),
            KeyboardEvent.Paste("a\nb"),
            KeyboardEvent.KeyDown(Key.ENTER, KeyModifiers.NONE, false),
        )
        val data = KeyboardEventCodec.encode(events).single()
        val n = KeyboardInput.receive(player, KeyboardInputPayload(helper.absolutePos(POS), data))
        helper.assertTrue(n == events.size, "all events should be delivered but was $n")
        helper.assertTrue(received == events, "listener should receive the events in order but got $received")
        val bytes = String(TerminalKeyEncoder.encodeAll(received), Charsets.UTF_8)
        helper.assertTrue(bytes == "hé日本\u0003a\rb\r", "terminal bytes were ${bytes.toByteArray().toList()}")
        helper.succeed()
    }

    /** 遠いプレイヤー・壊れたパケット・キーボードでない位置への入力は捨てる。量の上限を超えた分も捨てる。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun keyboardRejectsInvalidInput(helper: GameTestHelper) {
        helper.setBlock(POS, KiBiBlocks.KEYBOARD.get())
        val keyboard = helper.getBlockEntity<KeyboardBlockEntity>(POS)
        var count = 0
        keyboard.addListener(KeyboardListener { count++ })
        val abs = helper.absolutePos(POS)
        val data = KeyboardEventCodec.encode(listOf(KeyboardEvent.Text("x"))).single()
        val player = helper.makeMockServerPlayerInLevel()
        // 遠い
        player.moveTo(Vec3.atCenterOf(abs).add(0.0, 0.0, 30.0))
        helper.assertTrue(KeyboardInput.receive(player, KeyboardInputPayload(abs, data)) == -1, "far player should be rejected")
        player.moveTo(Vec3.atCenterOf(abs.east()))
        // 壊れている
        helper.assertTrue(KeyboardInput.receive(player, KeyboardInputPayload(abs, byteArrayOf(9))) == -1, "malformed should be rejected")
        // キーボードでない
        helper.assertTrue(
            KeyboardInput.receive(player, KeyboardInputPayload(abs.above(), data)) == -1,
            "input to a non-keyboard should be rejected",
        )
        helper.assertTrue(count == 0, "nothing should be delivered but $count were")
        // 同じ tick に送りすぎると、上限を超えた分は捨てる
        val big = KeyboardEventCodec.encode(listOf(KeyboardEvent.Paste("y".repeat(KeyboardEventCodec.MAX_TEXT_BYTES)))).single()
        var accepted = 0
        repeat(10) { if (KeyboardInput.receive(player, KeyboardInputPayload(abs, big)) > 0) accepted++ }
        helper.assertTrue(accepted in 1..9, "budget should accept some and reject the rest but accepted $accepted")
        helper.succeed()
    }
}
