package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.ChunkPos
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
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.display.ScreenBlock
import pro.ikili.kibicomputers.neoforge.input.KeyboardBlockEntity
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.util.UUID

/**
 * ワールド・チャンクの読み込みでの復元（#85、ADR 0085）の GameTest: 入力待ちの標準 OS を書き、ケースを置き直して同じ NBT を読ませると
 * （チャンクを読み込み直したのと同じ `onLoad`）、保存した世代から戻り、画面とキーボードにつながって、打った行に答える。
 * 電源を切った台は戻さない。戻している途中に電源を切ると、古いマシンにはつながない
 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object MachineRestoreGameTests {
    /** 起動を待つ tick 数の上限（[StandardOsGameTests] と同じ理由で長くとる） */
    private const val BOOT_TIMEOUT_TICKS = 12_000

    private val CASE = BlockPos(1, 1, 1)
    private val SCREEN = BlockPos(1, 2, 1)
    private val KEYBOARD = BlockPos(1, 1, 2)

    private fun installRequired(be: CaseBlockEntity) {
        for (kind in PartKind.entries.filter { it.required && it != PartKind.BOOT }) {
            be.parts.setStackInSlot(be.tier.firstSlot(kind), ItemStack(Items.IRON_INGOT))
        }
    }

    private fun typeLine(helper: GameTestHelper, keyboard: KeyboardBlockEntity, line: String) {
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
        installRequired(be)
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        return be
    }

    /** シェルの入力待ちまで待つ */
    private fun assertPrompt(helper: GameTestHelper, be: CaseBlockEntity) {
        val screen = be.wiring.connectedScreen
        helper.assertTrue(screen != null, "the screen should be connected")
        val text = lines(screen!!.text)
        helper.assertTrue(be.wiring.bootFailure == null, "boot should not fail but was ${be.wiring.bootFailure}: $text")
        helper.assertTrue(text.any { it == "$" }, "the shell prompt should be shown: $text")
    }

    /** 止めて書き、ケースの NBT を返す（アンロードの手順と同じ） */
    private fun saveAndTag(helper: GameTestHelper, be: CaseBlockEntity): CompoundTag {
        be.computer.prepareUnload()
        try {
            helper.assertTrue(saveNow(be, CapturePolicy.KILL_TRANSIENT) > 0, "the case should be saved")
        } finally {
            be.computer.endQuiesce()
        }
        return be.saveWithFullMetadata(helper.level.registryAccess())
    }

    /** ケースを外し（`setRemoved`。ディレクトリは残る）、置き直して [tag] を読ませる（次の tick の `onLoad` で復元の順番待ちに入る） */
    private fun reload(helper: GameTestHelper, tag: CompoundTag): CaseBlockEntity {
        helper.setBlock(CASE, Blocks.AIR)
        helper.setBlock(CASE, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        val be = helper.getBlockEntity<CaseBlockEntity>(CASE)
        be.loadWithComponents(tag, helper.level.registryAccess())
        return be
    }

    /**
     * 入力待ちの標準 OS を書いてから置き直すと、保存した世代から戻る（冷えた起動はしない）。戻した後に画面とキーボードがつながり、
     * 打った行にシェルが答える
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun restoresARunningComputerOnLoad(helper: GameTestHelper) {
        val first = placeComputer(helper)
        var restored = 0L
        var coldStarts = 0L
        var copy: CaseBlockEntity? = null
        helper.startSequence().thenWaitUntil { assertPrompt(helper, first) }.thenExecute {
            val tag = saveAndTag(helper, first)
            helper.assertTrue(tag.getBoolean(CaseBlockEntity.POWERED_ON_TAG), "a running case should save PoweredOn: $tag")
            restored = ServerMachines.Stats.restored
            coldStarts = ServerMachines.Stats.restoresColdStart + ServerMachines.Stats.restoreFallbacks
            copy = reload(helper, tag)
            helper.assertTrue(copy!!.computer.poweredOn, "the reloaded case should be powered on")
        }.thenWaitUntil {
            val be = copy!!
            val machine = be.computer.machine
            helper.assertTrue(machine != null && !machine.isRestoring, "the case should finish restoring: ${be.computer}")
            helper.assertTrue(ServerMachines.Stats.restored > restored, "the machine should be restored from the save")
            helper.assertTrue(be.wiring.connectedMachine === machine, "the restored machine should be wired")
            helper.assertTrue(be.wiring.connectedScreen != null, "the screen should be connected after restoring")
        }.thenExecute {
            val be = copy!!
            helper.assertTrue(
                ServerMachines.Stats.restoresColdStart + ServerMachines.Stats.restoreFallbacks == coldStarts,
                "the restore should not cold-boot",
            )
            helper.assertTrue(be.computer.machine!!.state == MachineState.RUNNING, "the restored machine should run")
            typeLine(helper, helper.getBlockEntity(KEYBOARD), "echo restored-ok")
        }.thenWaitUntil {
            val text = lines(copy!!.wiring.connectedScreen!!.text)
            helper.assertTrue(text.any { it == "restored-ok" }, "the restored shell should answer: $text")
        }.thenExecute {
            copy!!.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }.thenSucceed()
    }

    /** 電源を切った台の NBT は PoweredOn が偽で、置き直しても戻さない。タグのない古いセーブデータも電源が切れている */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 400)
    fun poweredOffCaseIsNotRestored(helper: GameTestHelper) {
        val first = placeComputer(helper)
        first.pressPowerButton()
        helper.assertTrue(!first.computer.poweredOn, "turning off should clear the power state")
        val tag = first.saveWithFullMetadata(helper.level.registryAccess())
        helper.assertTrue(tag.contains(CaseBlockEntity.POWERED_ON_TAG) && !tag.getBoolean(CaseBlockEntity.POWERED_ON_TAG), "PoweredOn should be false: $tag")
        val copy = reload(helper, tag)
        val old = tag.copy().apply { remove(CaseBlockEntity.POWERED_ON_TAG) }
        helper.runAfterDelay(3) {
            helper.assertTrue(copy.computer.machine == null && copy.computer.status == ComputerStatus.OFF, "a powered-off case should stay off: ${copy.computer}")
            val legacy = reload(helper, old)
            helper.runAfterDelay(3) {
                helper.assertTrue(legacy.computer.machine == null && !legacy.computer.poweredOn, "a save without PoweredOn should be off: ${legacy.computer}")
                helper.setBlock(CASE, Blocks.AIR)
                helper.succeed()
            }
        }
    }

    /** 戻している途中（マシンを作ってから、終わりの知らせが届くまで）に電源を切ると、古いマシンにはつながず、電源が切れる */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun turningOffWhileRestoringDoesNotWireTheOldMachine(helper: GameTestHelper) {
        val first = placeComputer(helper)
        var copy: CaseBlockEntity? = null
        helper.startSequence().thenWaitUntil { assertPrompt(helper, first) }.thenExecute {
            copy = reload(helper, saveAndTag(helper, first))
        }.thenWaitUntil {
            helper.assertTrue(copy!!.computer.machine != null, "the restore should start")
        }.thenExecute {
            val be = copy!!
            val machine = be.computer.machine!!
            helper.assertTrue(machine.isRestoring, "the machine should still be restoring in the tick it was created")
            be.pressPowerButton()
            helper.assertTrue(be.computer.machine == null && !be.computer.poweredOn, "turning off should remove the machine: ${be.computer}")
        }.thenIdle(20).thenExecute {
            val be = copy!!
            helper.assertTrue(be.wiring.connectedMachine == null, "the old machine should not be wired")
            helper.assertTrue(be.computer.machine == null, "no machine should be created")
            helper.setBlock(CASE, Blocks.AIR)
        }.thenSucceed()
    }

    /**
     * 復元の順番待ちの間にチャンクが FULL でなくなると（#545）、順番待ちから外れ、電源が切れた見た目に戻る。
     * 外れていないと、順番が来たときに FULL でないチャンクにマシンを作ってしまう。電源の状態は残るので、FULL に戻れば復元の順番待ちに入る。
     * 順番待ちが書き込みの終わりを待つように、書き込みのスレッドを止めたまま書き込みを1つ積んでおく
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 400, batch = "demoted_pending_restore")
    fun demotedChunkDropsAPendingRestore(helper: GameTestHelper) {
        val level = helper.level
        val chunk = ChunkPos(helper.absolutePos(CASE).x / 16 - 420, helper.absolutePos(CASE).z / 16 + 420)
        val keeper = ChunkPos(chunk.x + 5, chunk.z)
        val pos = BlockPos(chunk.minBlockX + 6, 200, chunk.minBlockZ + 9)
        level.setChunkForced(keeper.x, keeper.z, true)
        level.setChunkForced(chunk.x, chunk.z, true)
        level.getChunk(chunk.x, chunk.z)
        level.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get().defaultBlockState(), 3)
        val be = level.getBlockEntity(pos) as CaseBlockEntity
        installRequired(be)
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        val config = ServerMachines.saveConfig
        // 順番待ちが、書き込みの終わりを十分待つようにする（テストの間だけ）
        ServerMachines.saveConfig = config.copy(restoreWriteWaitMillis = 60_000)
        var starts = 0L
        var gate: WriterGate? = null
        // onLoad（追跡と索引）を待ってから、書き込みを積む
        helper.runAfterDelay(1) {
            be.computer.prepareUnload()
            gate = WriterGate()
            helper.assertTrue(be.captureAndSubmit(CapturePolicy.KILL_TRANSIENT) != null, "the case should submit a write")
            // マシンだけを外し（電源の状態は残る）、書き込みの終わりを待つ復元の順番待ちに入れてから、FULL でなくす
            be.computer.chunkUnloaded()
            starts = ServerMachines.Stats.restoresFromSave + ServerMachines.Stats.restoresColdStart
            helper.assertTrue(be.computer.beginRestore(), "the powered-on case should wait for a restore")
            ServerMachines.offerRestore(be)
            level.setChunkForced(chunk.x, chunk.z, false)
            helper.startSequence().thenWaitUntil {
                helper.assertTrue(!level.isLoaded(pos), "the chunk should no longer be FULL")
                helper.assertTrue(!be.computer.isRestorePending, "the pending restore should be dropped: ${be.computer}")
            }.thenExecute {
                // 書き込みを終わらせても、順番待ちから外れているので始まらない
                gate!!.close()
                ServerMachines.drainSaves()
            }.thenIdle(20).thenExecute {
                helper.assertTrue(
                    ServerMachines.Stats.restoresFromSave + ServerMachines.Stats.restoresColdStart == starts,
                    "no restore should start in a non-FULL chunk",
                )
                helper.assertTrue(be.computer.machine == null, "no machine should be created in a non-FULL chunk: ${be.computer}")
                helper.assertTrue(be.computer.status == ComputerStatus.OFF, "the case should look off: ${be.computer}")
                helper.assertTrue(be.computer.poweredOn, "the power state should be kept for the next load")
                ServerMachines.saveConfig = config
                level.setChunkForced(chunk.x, chunk.z, true)
                level.getChunk(chunk.x, chunk.z)
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)
                level.setChunkForced(chunk.x, chunk.z, false)
                level.setChunkForced(keeper.x, keeper.z, false)
            }.thenSucceed()
        }
    }
}
