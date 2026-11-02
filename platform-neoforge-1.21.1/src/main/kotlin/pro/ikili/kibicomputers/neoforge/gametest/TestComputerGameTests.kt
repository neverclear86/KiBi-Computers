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
import java.util.UUID

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
                    helper.assertTrue(keyboard.receive(UUID(0, 1), packet) == 2, "keyboard should accept the input")
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

    /** キーボードで1行打つ（Enter まで） */
    private fun typeLine(helper: GameTestHelper, keyboard: KeyboardBlockEntity, line: String) {
        val events = listOf(KeyboardEvent.Text(line), KeyboardEvent.KeyDown(Key.ENTER, 0, false))
        for (packet in KeyboardEventCodec.encode(events)) {
            helper.assertTrue(keyboard.receive(UUID(0, 1), packet) == 2, "keyboard should accept the input")
        }
    }

    /** 電源が入ったまま画面を壊すと、出力がなくてもすぐに画面とキーボードから外す（#397）。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun breakingScreenWhileRunningDisconnectsAtOnce(helper: GameTestHelper) {
        val (case, screen, keyboard) = build(helper)
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should be running")
        val machine = case.computer.machine!!
        val console = case.wiring.connectedConsole!!
        // 壊した直後に確かめたこと（succeedWhen は失敗すると次の tick にやり直すので、結果を取っておく）
        var problem: String? = null
        var broken = false
        helper.succeedWhen {
            if (!broken) {
                val text = lines(screen.text)
                helper.assertTrue(text.any { it == ">" }, "prompt should be shown: $text")
                helper.setBlock(SCREEN, Blocks.AIR)
                broken = true
                problem = when {
                    !screen.isRemoved -> "the screen block entity should be removed"
                    case.wiring.connectedScreen != null -> "breaking the screen should disconnect it at once"
                    case.wiring.connectedConsole != null -> "breaking the screen should drop the console"
                    case.wiring.connectedKeyboard != null || keyboard.owner != null -> "breaking the screen should free the keyboard"
                    console.backlogBytes != 0 -> "nothing should be kept for the removed screen: ${console.backlogBytes}"
                    else -> null
                }
            }
            helper.assertTrue(problem == null, problem ?: "")
            helper.assertTrue(machine.state == MachineState.RUNNING, "machine should keep running without a screen")
            case.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }
    }

    /**
     * 出力を止めている最中（画面に書ききれずに取っておいた分がある間）に画面を壊すと、たまった分を書かずに捨て、
     * 外れた画面にはもう書かない（#397）。止めている間はマシンが出力を配らないので、画面の取り外しの知らせで気付く。
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun breakingScreenWhileOutputIsHeldDiscardsBacklog(helper: GameTestHelper) {
        val (case, screen, keyboard) = build(helper)
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should be running")
        val machine = case.computer.machine!!
        val console = case.wiring.connectedConsole!!
        var typed = false
        var broken = false
        var problem: String? = null
        var removedText: List<String> = emptyList()
        var ticksAfterBreak = 0
        helper.succeedWhen {
            if (!typed) {
                val text = lines(screen.text)
                helper.assertTrue(text.any { it == ">" }, "prompt should be shown: $text")
                // 1行送りの間に文字を挟むと1回ごとに画面全体を写すので、1 tick の上限をすぐに超えて出力を止める（#357）
                typeLine(helper, keyboard, "while true do io.write((\"\\27[Sx\"):rep(5000)) end")
                typed = true
            }
            if (!broken) {
                helper.assertTrue(console.backlogBytes > 0, "the console should hold the output (backlog ${console.backlogBytes})")
                val droppedBefore = console.droppedBytes
                val backlog = console.backlogBytes
                helper.setBlock(SCREEN, Blocks.AIR)
                broken = true
                removedText = lines(screen.text)
                problem = when {
                    case.wiring.connectedScreen != null -> "breaking the screen should disconnect it while the output is held"
                    console.backlogBytes != 0 -> "the backlog should be discarded: ${console.backlogBytes}"
                    console.droppedBytes != droppedBefore + backlog ->
                        "the whole backlog ($backlog) should be dropped: ${console.droppedBytes - droppedBefore}"
                    else -> null
                }
            }
            helper.assertTrue(problem == null, problem ?: "")
            // 外れた画面には、もう書かない
            helper.assertTrue(lines(screen.text) == removedText, "the removed screen should not be written to")
            helper.assertTrue(machine.state == MachineState.RUNNING, "machine should keep running without a screen")
            helper.assertTrue(++ticksAfterBreak > 20, "watch the removed screen for a while")
            case.pressPowerButton()
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

    /**
     * 1つのキーボードは、最初につないだケースだけが持つ（#360）。隣に置いた2台目のケースには、入力が届かない。
     * 1台目が切れたあとに入れ直した2台目は、つなげる。
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun secondCaseDoesNotTakeTheKeyboard(helper: GameTestHelper) {
        val (first, _, keyboard) = build(helper)
        // 2台目: キーボードの東隣のケースと、その上の画面
        val secondPos = KEYBOARD.east()
        helper.setBlock(secondPos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        helper.setBlock(
            secondPos.above(),
            KiBiBlocks.SCREENS.getValue(ScreenTier.MONOCHROME).get().defaultBlockState().setValue(ScreenBlock.FACING, Direction.SOUTH),
        )
        val second = helper.getBlockEntity<CaseBlockEntity>(secondPos)
        for (kind in PartKind.entries.filter { it.required && it != PartKind.BOOT }) {
            second.parts.setStackInSlot(second.tier.firstSlot(kind), ItemStack(Items.IRON_INGOT))
        }
        helper.assertTrue(first.pressPowerButton() == ComputerStatus.RUNNING, "first case should be running")
        helper.assertTrue(second.pressPowerButton() == ComputerStatus.RUNNING, "second case should be running")
        helper.assertTrue(first.wiring.connectedKeyboard === keyboard, "the first case should take the keyboard")
        helper.assertTrue(second.wiring.connectedKeyboard == null, "the second case should not take the keyboard")
        helper.assertTrue(keyboard.owner === first.wiring, "the keyboard should deliver only to the first case")
        // 1台目を切ると空く。2台目を入れ直すとつながる
        first.pressPowerButton()
        helper.assertTrue(keyboard.owner == null, "turning the first case off should free the keyboard")
        second.pressPowerButton()
        helper.assertTrue(second.pressPowerButton() == ComputerStatus.RUNNING, "second case should restart")
        helper.assertTrue(second.wiring.connectedKeyboard === keyboard, "the restarted second case should take the free keyboard")
        second.pressPowerButton()
        helper.setBlock(CASE, Blocks.AIR)
        helper.setBlock(secondPos, Blocks.AIR)
        helper.succeed()
    }
}
