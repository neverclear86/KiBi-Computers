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
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.display.ScreenBlock
import pro.ikili.kibicomputers.neoforge.display.ScreenBlockEntity
import pro.ikili.kibicomputers.neoforge.input.KeyboardBlockEntity
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks

/** テスト用コンピューターの仮の結線（#307）の GameTest。 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object TestComputerGameTests {
    /**
     * 起動を待つ tick 数の上限。GameTest のサーバーは待たずに tick を進めるので、lua.wasm の読み込み
     * （待機点への変換・燃料の計測コードの挿入・検証。#329）と Lua の起動が終わる前に、普通の上限（100 tick）を使い切ってしまう。
     * 温まっていない JVM で複数のテストが同時に読み込むと、読み込みだけで数秒かかる
     */
    private const val BOOT_TIMEOUT_TICKS = 12_000

    private val CASE = BlockPos(1, 1, 1)
    private val SCREEN = BlockPos(1, 2, 1)
    private val KEYBOARD = BlockPos(1, 1, 2)

    /** 画面の各行の文字列（行末の空白を除く） */
    private fun lines(text: TextBuffer): List<String> = (0 until text.height).map { y ->
        val sb = StringBuilder()
        for (x in 0 until text.width) sb.appendCodePoint(text.getCodePoint(x, y))
        sb.toString().trimEnd()
    }

    private fun build(helper: GameTestHelper): Triple<CaseBlockEntity, ScreenBlockEntity, KeyboardBlockEntity> {
        helper.setBlock(CASE, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        helper.setBlock(
            SCREEN,
            KiBiBlocks.SCREENS.getValue(ScreenTier.MONOCHROME).get().defaultBlockState().setValue(ScreenBlock.FACING, Direction.SOUTH),
        )
        helper.setBlock(KEYBOARD, KiBiBlocks.KEYBOARD.get())
        val case = helper.getBlockEntity<CaseBlockEntity>(CASE)
        // 起動部品のほかの必須の部品（#122〜#124）はまだないので、種類を確かめずに入れる
        for (kind in PartKind.entries.filter { it.required && it != PartKind.BOOT }) {
            case.parts.setStackInSlot(case.tier.firstSlot(kind), ItemStack(Items.IRON_INGOT))
        }
        return Triple(case, helper.getBlockEntity(SCREEN), helper.getBlockEntity(KEYBOARD))
    }

    /** 電源を入れると同梱イメージの init（Lua の REPL。#315）が動いて画面に出し、キーボードで打った式の結果が出る。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun typedLuaExpressionIsEvaluatedOnScreen(helper: GameTestHelper) {
        val (case, screen, keyboard) = build(helper)
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should be running")
        helper.assertTrue(case.wiring.connectedScreen === screen, "the adjacent screen should be connected")
        helper.assertTrue(case.wiring.connectedKeyboard === keyboard, "the adjacent keyboard should be connected")
        val machine = case.computer.machine!!
        var typed = false
        helper.succeedWhen {
            val text = lines(screen.text)
            if (!typed) {
                helper.assertTrue(text.any { it == "KiBi test init: Lua REPL" }, "init banner should be shown: $text")
                helper.assertTrue(text.any { it == ">" }, "prompt should be shown: $text")
                val events = listOf(KeyboardEvent.Text("print(1+1)"), KeyboardEvent.KeyDown(Key.ENTER, 0, false))
                for (packet in KeyboardEventCodec.encode(events)) {
                    helper.assertTrue(keyboard.receive(packet, helper.level.gameTime) == 2, "keyboard should accept the input")
                }
                typed = true
            }
            helper.assertTrue(text.any { it == "> print(1+1)" }, "typed line should be echoed by the TTY: $text")
            helper.assertTrue(text.any { it == "2" }, "Lua should print the result: $text")
            helper.assertTrue(machine.state == MachineState.RUNNING, "machine should keep running")
            case.pressPowerButton()
            helper.assertTrue(case.wiring.connectedKeyboard == null, "turning off should disconnect the keyboard")
            helper.setBlock(CASE, Blocks.AIR)
        }
    }

    /** 画面がなくても起動し、キーボードはつながない。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun bootsWithoutScreen(helper: GameTestHelper) {
        val (case, _, _) = build(helper)
        helper.setBlock(SCREEN, Blocks.AIR)
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should be running")
        helper.assertTrue(case.wiring.connectedScreen == null, "no screen should be connected")
        helper.assertTrue(case.wiring.connectedKeyboard == null, "keyboard is not connected without a screen")
        val machine = case.computer.machine!!
        helper.succeedWhen {
            helper.assertTrue(machine.state == MachineState.RUNNING, "init should start")
            helper.setBlock(CASE, Blocks.AIR)
        }
    }
}
