package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.display.ScreenTier
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.input.Key
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.input.KeyboardEventCodec
import pro.ikili.kibicomputers.core.kernel.MachineClock
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.computer.ProvisionalBoot
import pro.ikili.kibicomputers.neoforge.display.ScreenBlock
import pro.ikili.kibicomputers.neoforge.display.ScreenBlockEntity
import pro.ikili.kibicomputers.neoforge.input.KeyboardBlockEntity
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.util.UUID

/**
 * マシンから見えるゲーム内時間（#439）。接続層がワールドの gameTime を tick ごとにマシンの時計へ写す。
 *
 * 写すのはサーバーの tick の始め（ワールドの tick より前）なので、テストの中で読む gameTime より 0〜1 tick 遅れる。
 * ほかのテストと並んで動くので、gameTime は書き換えず、待って進める。
 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object WorldClockGameTests {
    /** 起動を待つ tick 数の上限（[TestComputerGameTests] と同じ理由で長くする） */
    private const val BOOT_TIMEOUT_TICKS = 12_000

    /** 時間を進めて確かめるときに待つ tick 数 */
    private const val WAIT_TICKS = 20

    private val CASE = BlockPos(1, 1, 1)
    private val SCREEN = BlockPos(1, 2, 1)
    private val KEYBOARD = BlockPos(1, 2, 2)

    private fun build(helper: GameTestHelper): Triple<CaseBlockEntity, ScreenBlockEntity, KeyboardBlockEntity> {
        helper.setBlock(CASE, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        helper.setBlock(
            SCREEN,
            KiBiBlocks.SCREENS.getValue(ScreenTier.MONOCHROME).get().defaultBlockState().setValue(ScreenBlock.FACING, Direction.SOUTH),
        )
        helper.setBlock(KEYBOARD, KiBiBlocks.KEYBOARD.get())
        val case = helper.getBlockEntity<CaseBlockEntity>(CASE)
        case.fallbackBootDisk = ProvisionalBoot.TEST_BOOT_DISK
        for (kind in PartKind.entries.filter { it.required && it != PartKind.BOOT }) {
            case.parts.setStackInSlot(case.tier.firstSlot(kind), ItemStack(Items.IRON_INGOT))
        }
        return Triple(case, helper.getBlockEntity(SCREEN), helper.getBlockEntity(KEYBOARD))
    }

    private fun lines(text: TextBuffer): List<String> = (0 until text.height).map { y ->
        val sb = StringBuilder()
        for (x in 0 until text.width) sb.appendCodePoint(text.getCodePoint(x, y))
        sb.toString().trimEnd()
    }

    /** マシンの時計はワールドの gameTime を返し、ワールドの時間が進むと同じだけ進む。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun machineClockFollowsWorldGameTime(helper: GameTestHelper) {
        val (case, _, _) = build(helper)
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should be running")
        val clock = case.computer.machine!!.environment.clock
        helper.assertTrue(clock !== MachineClock.SYSTEM, "the machine should get the world's clock, not MachineClock.SYSTEM")
        val level = helper.level
        val g1 = level.gameTime
        val w1 = clock.worldTicks()
        helper.assertTrue(g1 - w1 in 0..1, "worldTicks should follow gameTime: gameTime=$g1 worldTicks=$w1")
        helper.assertTrue(w1 > 0, "worldTicks should not be 0 in a running world: $w1")
        helper.runAfterDelay(WAIT_TICKS.toLong()) {
            val g2 = level.gameTime
            val w2 = clock.worldTicks()
            helper.assertTrue(g2 - w2 in 0..1, "worldTicks should follow gameTime: gameTime=$g2 worldTicks=$w2")
            helper.assertTrue(g2 - g1 >= WAIT_TICKS, "gameTime should advance while waiting: $g1 -> $g2")
            helper.assertTrue(w2 - w1 >= WAIT_TICKS - 1, "worldTicks should advance with gameTime: $w1 -> $w2 (gameTime $g1 -> $g2)")
            case.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
            helper.succeed()
        }
    }

    /** ゲストの Lua から `os.epoch("ingame")`（`kibi:computer/clock` の `world-ticks` × 3600）で、ワールドの gameTime が見える。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun luaSeesWorldGameTime(helper: GameTestHelper) {
        val (case, screen, keyboard) = build(helper)
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should be running")
        val level = helper.level
        var typedAt = -1L
        helper.succeedWhen {
            val text = lines(screen.text)
            if (typedAt < 0) {
                helper.assertTrue(text.any { it == ">" }, "prompt should be shown: $text")
                val events = listOf(KeyboardEvent.Text("print(os.epoch(\"ingame\") // 3600)"), KeyboardEvent.KeyDown(Key.ENTER, 0, false))
                for (packet in KeyboardEventCodec.encode(events)) {
                    helper.assertTrue(keyboard.receive(UUID(0, 1), packet) == 2, "keyboard should accept the input")
                }
                typedAt = level.gameTime
            }
            val now = level.gameTime
            val values = text.mapNotNull { it.toLongOrNull() }
            helper.assertTrue(values.isNotEmpty(), "Lua should print the in-game ticks: $text")
            val v = values.last()
            // 打った時点（写した値は 1 tick 遅れうる）から、今までの間の値
            helper.assertTrue(v in (typedAt - 1)..now, "os.epoch('ingame') // 3600 should be the world's gameTime: $v not in ${typedAt - 1}..$now")
            case.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }
    }
}
