package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.access.PlayerAccess
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.display.ScreenTier
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.input.Key
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.input.KeyboardEventCodec
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.neoforge.computer.AccessRequests
import pro.ikili.kibicomputers.neoforge.computer.CaseAccess
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.display.ScreenBlock
import pro.ikili.kibicomputers.neoforge.input.KeyboardBlockEntity
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.util.UUID

/**
 * ケースで動かした標準 OS の `access` コマンドから、持ち主への知らせまでを通しで確かめる（#110、ADR 0110）。
 * ケースが渡す `AccessPort`（`CaseBlockEntity.accessPort`）が、新しく起動したマシン（`pressPowerButton`）と、保存から戻したマシン
 * （`startRestore`）の両方につながり、`get-access` がワーカーから `playerAccess` を読み、依頼がメインスレッドの
 * `AccessRequests.deliver` に届くことを見る。外すと `access` が `not supported on this computer` になる
 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object AccessOsGameTests {
    /** 起動を待つ tick 数の上限（[StandardOsGameTests] と同じ理由で長くとる） */
    private const val BOOT_TIMEOUT_TICKS = 12_000

    private val CASE = BlockPos(1, 1, 1)
    private val SCREEN = BlockPos(1, 2, 1)
    private val KEYBOARD = BlockPos(1, 1, 2)

    private fun typeLine(helper: GameTestHelper, line: String) {
        val keyboard = helper.getBlockEntity<KeyboardBlockEntity>(KEYBOARD)
        val events = listOf(KeyboardEvent.Text(line), KeyboardEvent.KeyDown(Key.ENTER, 0, false))
        for (packet in KeyboardEventCodec.encode(events)) {
            helper.assertTrue(keyboard.receive(UUID(0, 1), packet) == 2, "keyboard should accept the input")
        }
    }

    private fun lines(text: TextBuffer): List<String> = (0 until text.height).map { y ->
        val sb = StringBuilder()
        for (x in 0 until text.width) sb.appendCodePoint(text.getCodePoint(x, y))
        sb.toString().trimEnd()
    }

    /** ケース・画面・キーボードを置き、電源を入れる */
    private fun placeComputer(helper: GameTestHelper): CaseBlockEntity {
        helper.setBlock(CASE, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        helper.setBlock(
            SCREEN,
            KiBiBlocks.SCREENS.getValue(ScreenTier.COLOR).get().defaultBlockState().setValue(ScreenBlock.FACING, Direction.SOUTH),
        )
        helper.setBlock(KEYBOARD, KiBiBlocks.KEYBOARD.get())
        val be = helper.getBlockEntity<CaseBlockEntity>(CASE)
        for (kind in PartKind.entries.filter { it.required && it != PartKind.BOOT }) {
            be.parts.setStackInSlot(be.tier.firstSlot(kind), ItemStack(Items.IRON_INGOT))
        }
        return be
    }

    private fun assertPrompt(helper: GameTestHelper, be: CaseBlockEntity) {
        val screen = be.wiring.connectedScreen
        helper.assertTrue(screen != null, "the screen should be connected")
        val text = lines(screen!!.text)
        helper.assertTrue(be.wiring.bootFailure == null, "boot should not fail but was ${be.wiring.bootFailure}: $text")
        helper.assertTrue(text.any { it == "$" }, "the shell prompt should be shown: $text")
    }

    private fun assertScreenHas(helper: GameTestHelper, be: CaseBlockEntity, prefix: String) {
        val text = lines(be.wiring.connectedScreen!!.text)
        helper.assertTrue(text.any { it.startsWith(prefix) }, "the screen should show '$prefix': $text")
    }

    /** 画面にまだ出ていない行を待つために、行の始まりが [prefix] の行の数を数える */
    private fun count(be: CaseBlockEntity, prefix: String): Int = lines(be.wiring.connectedScreen!!.text).count { it.startsWith(prefix) }

    /** 起動したマシンの `access` は、持ち主を書き、`access add` の依頼が持ち主へのチャットと表に届く */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun bootedMachineRequestsReachTheOwner(helper: GameTestHelper) {
        val alex = AccessRequestGameTests.player(helper, "Alex110o")
        val be = placeComputer(helper)
        be.replacePlayerAccess(PlayerAccess.ownedBy(CaseAccess.ref(alex)))
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        helper.startSequence().thenWaitUntil { assertPrompt(helper, be) }.thenExecute {
            typeLine(helper, "access")
        }.thenWaitUntil {
            assertScreenHas(helper, be, "state: owned")
            assertScreenHas(helper, be, "owner: Alex110o ${alex.uuid}")
            typeLine(helper, "access add Bob110o")
        }.thenWaitUntil {
            assertScreenHas(helper, be, "access: requested: add Bob110o")
            helper.assertTrue(AccessRequests.KEY_REQUEST_ADD in alex.keys(), "the owner should be told: ${alex.messages}")
            val id = alex.messages.flatMap(AccessRequestGameTests::commandsOf).mapNotNull { Regex("approve (\\d+)").find(it)?.groupValues?.get(1)?.toLong() }
            helper.assertTrue(id.size == 1 && AccessRequests.peek(id[0]) != null, "the request should be in the table: $id")
            helper.assertTrue(be.accessPort.limiter.size == 1, "the slot should be taken")
        }.thenExecute {
            be.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }.thenSucceed()
    }

    /** 保存から戻したマシンでも `access` が使え、依頼が届く（`startRestore` が `AccessPort` をつなぐ） */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun restoredMachineRequestsReachTheOwner(helper: GameTestHelper) {
        val alex = AccessRequestGameTests.player(helper, "Alex110p")
        val first = placeComputer(helper)
        first.replacePlayerAccess(PlayerAccess.ownedBy(CaseAccess.ref(alex)))
        helper.assertTrue(first.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${first.computer}")
        var copy: CaseBlockEntity? = null
        helper.startSequence().thenWaitUntil { assertPrompt(helper, first) }.thenExecute {
            first.computer.prepareUnload()
            try {
                helper.assertTrue(saveNow(first, CapturePolicy.KILL_TRANSIENT) > 0, "the case should be saved")
            } finally {
                first.computer.endQuiesce()
            }
            val tag: CompoundTag = first.saveWithFullMetadata(helper.level.registryAccess())
            helper.setBlock(CASE, Blocks.AIR)
            helper.setBlock(CASE, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
            val be = helper.getBlockEntity<CaseBlockEntity>(CASE)
            be.loadWithComponents(tag, helper.level.registryAccess())
            copy = be
            helper.assertTrue(be.playerAccess.owner?.id == alex.uuid, "the owner should be restored: ${be.playerAccess}")
        }.thenWaitUntil {
            val be = copy!!
            val machine = be.computer.machine
            helper.assertTrue(machine != null && !machine.isRestoring, "the case should finish restoring: ${be.computer}")
            helper.assertTrue(be.wiring.connectedMachine === machine, "the restored machine should be wired")
            helper.assertTrue(be.wiring.connectedScreen != null, "the screen should be connected after restoring")
            helper.assertTrue(be.restoreStarts == 1, "the machine should be restored, not booted again: ${be.restoreStarts}")
            alex.clear()
            typeLine(helper, "access add Carl110p")
        }.thenWaitUntil {
            val be = copy!!
            assertScreenHas(helper, be, "access: requested: add Carl110p")
            helper.assertTrue(AccessRequests.KEY_REQUEST_ADD in alex.keys(), "the owner should be told: ${alex.messages}")
            helper.assertTrue(be.accessPort.limiter.size == 1, "the slot should be taken")
            helper.assertTrue(count(be, "access: not supported") == 0, "access should be supported after restoring")
        }.thenExecute {
            copy!!.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }.thenSucceed()
    }
}
