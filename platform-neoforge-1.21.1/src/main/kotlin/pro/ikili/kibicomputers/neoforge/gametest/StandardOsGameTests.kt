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
import pro.ikili.kibicomputers.core.computer.PartTier
import pro.ikili.kibicomputers.core.display.ScreenTier
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.input.Key
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.input.KeyboardEventCodec
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.computer.DiskItem
import pro.ikili.kibicomputers.neoforge.computer.TestComputerWiring
import pro.ikili.kibicomputers.neoforge.computer.WorldDisks
import pro.ikili.kibicomputers.neoforge.display.ScreenBlock
import pro.ikili.kibicomputers.neoforge.display.ScreenBlockEntity
import pro.ikili.kibicomputers.neoforge.input.KeyboardBlockEntity
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** ゲームの中で標準 OS を起動する仮の起動経路（#461）の GameTest。 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object StandardOsGameTests {
    /** 起動を待つ tick 数の上限（[TestComputerGameTests] と同じ理由で長くとる。init とシェルの読み込みを2回待つ） */
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

    /** キーボードで1行打つ（Enter まで） */
    private fun typeLine(helper: GameTestHelper, keyboard: KeyboardBlockEntity, line: String) {
        val events = listOf(KeyboardEvent.Text(line), KeyboardEvent.KeyDown(Key.ENTER, 0, false))
        for (packet in KeyboardEventCodec.encode(events)) {
            helper.assertTrue(keyboard.receive(UUID(0, 1), packet) == 2, "keyboard should accept the input")
        }
    }

    private data class Rig(val case: CaseBlockEntity, val screen: ScreenBlockEntity, val keyboard: KeyboardBlockEntity)

    /** ケース（HDD Tier 1 入り）・画面・キーボードを置き、電源を入れる */
    private fun placeComputer(helper: GameTestHelper): Rig {
        helper.setBlock(CASE, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        helper.setBlock(
            SCREEN,
            KiBiBlocks.SCREENS.getValue(ScreenTier.COLOR).get().defaultBlockState().setValue(ScreenBlock.FACING, Direction.SOUTH),
        )
        helper.setBlock(KEYBOARD, KiBiBlocks.KEYBOARD.get())
        val case = helper.getBlockEntity<CaseBlockEntity>(CASE)
        val screen = helper.getBlockEntity<ScreenBlockEntity>(SCREEN)
        val keyboard = helper.getBlockEntity<KeyboardBlockEntity>(KEYBOARD)
        for (kind in PartKind.entries.filter { it.required && it != PartKind.BOOT && it != PartKind.STORAGE }) {
            case.parts.setStackInSlot(case.tier.firstSlot(kind), ItemStack(Items.IRON_INGOT))
        }
        case.parts.setStackInSlot(case.tier.firstSlot(PartKind.STORAGE), ItemStack(KiBiBlocks.part(PartTier.TIER_1, PartKind.STORAGE)))
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${case.computer}")
        return Rig(case, screen, keyboard)
    }

    /** ケースの HDD（`~`）の、ワールドの中のディレクトリ */
    private fun homeDirectory(helper: GameTestHelper, case: CaseBlockEntity): Path {
        val store = WorldDisks.current
        helper.assertTrue(store != null, "the server should have a disk store")
        val id = DiskItem.diskIdOf(case.parts.getStackInSlot(case.tier.firstSlot(PartKind.STORAGE)))
        helper.assertTrue(id != null, "the disk should get an ID")
        return store!!.directoryOf(id!!)!!
    }

    /**
     * ケースと HDD・画面・キーボードで電源を入れると、標準 OS のシェルのプロンプトが出る。`~`（`/home`）は HDD で、
     * `echo hi > ~/a; cat ~/a` が動き、電源を入れ直した後も `~/a` が残る。
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun shellWritesHomeOnDiskAndKeepsItAcrossPowerCycles(helper: GameTestHelper) {
        val (case, screen, keyboard) = placeComputer(helper)
        val file = homeDirectory(helper, case).resolve("a")
        var step = 0
        helper.succeedWhen {
            val text = lines(screen.text)
            helper.assertTrue(case.wiring.bootFailure == null, "boot should not fail but was ${case.wiring.bootFailure}: $text")
            when (step) {
                0 -> {
                    helper.assertTrue(
                        text.any { it == "kibi: booting from disk ${TestComputerWiring.OS_BOOT_DISK}" },
                        "should boot the OS image: $text",
                    )
                    helper.assertTrue(text.any { it == "$" }, "the shell prompt should be shown: $text")
                    typeLine(helper, keyboard, "echo hi > ~/a; cat ~/a")
                    step = 1
                    helper.fail("typed the first line")
                }
                1 -> {
                    helper.assertTrue(text.any { it == "$ echo hi > ~/a; cat ~/a" }, "the typed line should be echoed: $text")
                    helper.assertTrue(text.any { it == "hi" }, "cat should print the file: $text")
                    helper.assertTrue(Files.isRegularFile(file) && Files.readString(file) == "hi\n", "~/a should be on the disk: $text")
                    // 電源を入れ直す
                    case.pressPowerButton()
                    helper.assertTrue(case.computer.status == ComputerStatus.OFF, "case should turn off but was ${case.computer}")
                    helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should run again but was ${case.computer}")
                    step = 2
                    helper.fail("power cycled")
                }
                2 -> {
                    helper.assertTrue(text.none { it == "hi" }, "a new display should start empty: $text")
                    helper.assertTrue(text.any { it == "$" }, "the shell prompt should be shown again: $text")
                    typeLine(helper, keyboard, "cat ~/a")
                    step = 3
                    helper.fail("typed the second line")
                }
                else -> {
                    helper.assertTrue(text.any { it == "$ cat ~/a" }, "the typed line should be echoed: $text")
                    helper.assertTrue(text.any { it == "hi" }, "~/a should survive the power cycle: $text")
                    helper.assertTrue(case.computer.machine?.state == MachineState.RUNNING, "machine should keep running")
                    case.pressPowerButton()
                    helper.setBlock(CASE, Blocks.AIR)
                }
            }
        }
    }

    /**
     * 標準 OS のイメージの `/bin/lua`（#603）: シェルの `printf` で `~/s.lua`（`#!/bin/lua` のスクリプト）を HDD に書き、
     * `lua ~/s.lua` と、シバンで `~/s.lua` を直接動かせる。
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun shellRunsLuaScriptWrittenToHome(helper: GameTestHelper) {
        val (case, screen, keyboard) = placeComputer(helper)
        val file = homeDirectory(helper, case).resolve("s.lua")
        // シェルの組み込みの printf が `\n` を改行にする（`\\n` は Kotlin の文字列の中の `\n` そのもの）
        val line = "printf '#!/bin/lua\\nprint(\"lua\"..6*7)\\n' > ~/s.lua; lua ~/s.lua; ~/s.lua"
        var typed = false
        helper.succeedWhen {
            val text = lines(screen.text)
            helper.assertTrue(case.wiring.bootFailure == null, "boot should not fail but was ${case.wiring.bootFailure}: $text")
            if (!typed) {
                helper.assertTrue(text.any { it == "$" }, "the shell prompt should be shown: $text")
                typeLine(helper, keyboard, line)
                typed = true
                helper.fail("typed the line")
            }
            // 打った行には `lua42` が含まれないので、出力の行だけが数に入る（`lua ~/s.lua` とシバンで2回）
            helper.assertTrue(text.count { it == "lua42" } == 2, "lua should run the script twice: $text")
            helper.assertTrue(text.last { it.isNotEmpty() } == "$", "the shell should be back at the prompt: $text")
            helper.assertTrue(
                Files.isRegularFile(file) && Files.readString(file) == "#!/bin/lua\nprint(\"lua\"..6*7)\n",
                "~/s.lua should be on the disk: $text",
            )
            case.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }
    }
}
