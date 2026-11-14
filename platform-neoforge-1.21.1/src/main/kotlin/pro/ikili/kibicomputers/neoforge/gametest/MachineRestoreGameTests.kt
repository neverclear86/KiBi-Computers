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
import net.neoforged.fml.ModList
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
import pro.ikili.kibicomputers.core.persist.ClaimResult
import pro.ikili.kibicomputers.core.persist.ColdBootCause
import pro.ikili.kibicomputers.core.persist.MachineIdentity
import pro.ikili.kibicomputers.core.persist.RestoreOutcome
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.display.ScreenBlock
import pro.ikili.kibicomputers.neoforge.input.KeyboardBlockEntity
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines
import pro.ikili.kibicomputers.neoforge.machine.WorldMachineSaves
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

    /**
     * 置き場が保存データに書き、戻すときに比べる MOD のバージョン（#88）が、MOD のコンテナの版と同じで、`"unknown"` でない。
     * 数え口（[ServerMachines.Stats]）は読みも書きもしない（ログの出し方の判定は core の `ColdBootLogTest` が受け持つ）
     */
    @JvmStatic
    @GameTest(template = "empty")
    fun saveStoreRecordsTheModVersion(helper: GameTestHelper) {
        val store = WorldMachineSaves.current
        helper.assertTrue(store != null, "the save store should exist while the server runs")
        val expected = ModList.get().getModContainerById(KiBiComputers.MOD_ID).map { it.modInfo.version.toString() }.orElse(null)
        helper.assertTrue(expected != null, "the mod container should exist")
        helper.assertTrue(store!!.modVersion != "unknown", "the save store should know the mod version")
        helper.assertTrue(store.modVersion == expected, "the save store should use the mod container version: ${store.modVersion} != $expected")
        helper.succeed()
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
        var copy: CaseBlockEntity? = null
        helper.startSequence().thenWaitUntil { assertPrompt(helper, first) }.thenExecute {
            val tag = saveAndTag(helper, first)
            helper.assertTrue(tag.getBoolean(CaseBlockEntity.POWERED_ON_TAG), "a running case should save PoweredOn: $tag")
            copy = reload(helper, tag)
            helper.assertTrue(copy!!.computer.poweredOn, "the reloaded case should be powered on")
        }.thenWaitUntil {
            val be = copy!!
            val machine = be.computer.machine
            helper.assertTrue(machine != null && !machine.isRestoring, "the case should finish restoring: ${be.computer}")
            helper.assertTrue(be.wiring.connectedMachine === machine, "the restored machine should be wired")
            helper.assertTrue(be.wiring.connectedScreen != null, "the screen should be connected after restoring")
        }.thenExecute {
            val be = copy!!
            // 共有の ServerMachines.Stats は並行するほかのテストでも動くので見ない（#708）。このケースの記録を見る
            helper.assertTrue(
                be.restoreStarts == 1 && be.lastRestoreSource != null && be.lastRestoreOutcome == RestoreOutcome.RESTORED,
                "the restore should not cold-boot: starts=${be.restoreStarts}, source=${be.lastRestoreSource}, " +
                    "outcome=${be.lastRestoreOutcome}, claim=${be.lastClaim}, ${be.computer}",
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

    /**
     * 戻すと、キーを打つ前にシェルがプロンプトと入力中の行を描き直す（`restored` イベント。#87、ADR 0087）。画面も置き直して
     * （ワールドを読み込み直したときと同じく、表示は空から始まる）、描き直しが前の画面の残りでないことを確かめる
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun restoredShellRedrawsThePrompt(helper: GameTestHelper) {
        val first = placeComputer(helper)
        var copy: CaseBlockEntity? = null
        helper.startSequence().thenWaitUntil { assertPrompt(helper, first) }.thenExecute {
            // 確定しない入力（Enter を押さない）
            for (packet in KeyboardEventCodec.encode(listOf(KeyboardEvent.Text("echo pending")))) {
                helper.assertTrue(helper.getBlockEntity<KeyboardBlockEntity>(KEYBOARD).receive(UUID(0, 1), packet) == 1, "keyboard should accept the input")
            }
        }.thenWaitUntil {
            val text = lines(first.wiring.connectedScreen!!.text)
            helper.assertTrue(text.any { it == "$ echo pending" }, "the typed text should be shown: $text")
        }.thenExecute {
            val tag = saveAndTag(helper, first)
            helper.setBlock(SCREEN, Blocks.AIR)
            helper.setBlock(
                SCREEN,
                KiBiBlocks.SCREENS.getValue(ScreenTier.COLOR).get().defaultBlockState().setValue(ScreenBlock.FACING, Direction.SOUTH),
            )
            copy = reload(helper, tag)
        }.thenWaitUntil {
            val be = copy!!
            val machine = be.computer.machine
            helper.assertTrue(machine != null && !machine.isRestoring, "the case should finish restoring: ${be.computer}")
            val screen = be.wiring.connectedScreen
            helper.assertTrue(screen != null, "the screen should be connected after restoring")
            val text = lines(screen!!.text)
            helper.assertTrue(text.any { it == "$ echo pending" }, "the restored shell should redraw the line without a key: $text")
        }.thenExecute {
            val events = listOf(KeyboardEvent.KeyDown(Key.ENTER, 0, false))
            for (packet in KeyboardEventCodec.encode(events)) {
                helper.assertTrue(helper.getBlockEntity<KeyboardBlockEntity>(KEYBOARD).receive(UUID(0, 1), packet) == 1, "keyboard should accept the input")
            }
        }.thenWaitUntil {
            val text = lines(copy!!.wiring.connectedScreen!!.text)
            helper.assertTrue(text.any { it == "pending" }, "the restored shell should run the redrawn line: $text")
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
        var starts = 0
        var gate: WriterGate? = null
        // onLoad（追跡と索引）を待ってから、書き込みを積む
        helper.runAfterDelay(1) {
            be.computer.prepareUnload()
            gate = WriterGate()
            helper.assertTrue(be.captureAndSubmit(CapturePolicy.KILL_TRANSIENT) != null, "the case should submit a write")
            // マシンだけを外し（電源の状態は残る）、書き込みの終わりを待つ復元の順番待ちに入れてから、FULL でなくす
            be.computer.chunkUnloaded()
            starts = be.restoreStarts
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
                    be.restoreStarts == starts,
                    "no restore should start in a non-FULL chunk: ${be.restoreStarts - starts} started",
                )
                helper.assertTrue(be.computer.machine == null, "no machine should be created in a non-FULL chunk: ${be.computer}")
                helper.assertTrue(be.computer.status == ComputerStatus.OFF, "the case should look off: ${be.computer}")
                helper.assertTrue(be.computer.poweredOn, "the power state should be kept for the next load")
                ServerMachines.saveConfig = config
                // FULL に戻すと、tick の終わりに順番待ちに入る（書き込みは終わっているので、次の tick には始まりうる）
                level.setChunkForced(chunk.x, chunk.z, true)
                level.getChunk(chunk.x, chunk.z)
            }.thenWaitUntil {
                helper.assertTrue(
                    be.computer.isRestorePending || be.computer.machine != null,
                    "the case should wait for a restore after the chunk is FULL again: ${be.computer}",
                )
            }.thenExecute {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)
                level.setChunkForced(chunk.x, chunk.z, false)
                level.setChunkForced(keeper.x, keeper.z, false)
            }.thenSucceed()
        }
    }

    // ---- FULL に戻ったチャンクの復元（#575） ----

    /**
     * だれも読み込んでいないチャンク（テストの場所から [dx]・[dz] チャンク離す。テストごとに変える）に、ケース・画面・キーボードを置く。
     * 5 チャンク離れたチャンクを強制読み込みにしておくので、ケースのチャンクの強制読み込みを外すと、FULL でなくなるがアンロードはされない（#545）
     */
    private class FarComputer(helper: GameTestHelper, dx: Int, dz: Int) {
        val level = helper.level
        val chunk = ChunkPos(helper.absolutePos(CASE).x / 16 + dx, helper.absolutePos(CASE).z / 16 + dz)
        val keeper = ChunkPos(chunk.x + 5, chunk.z)
        val pos = BlockPos(chunk.minBlockX + 6, 200, chunk.minBlockZ + 9)
        val case: CaseBlockEntity

        init {
            level.setChunkForced(keeper.x, keeper.z, true)
            setFull(true)
            level.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get().defaultBlockState(), 3)
            level.setBlock(
                pos.above(),
                KiBiBlocks.SCREENS.getValue(ScreenTier.COLOR).get().defaultBlockState().setValue(ScreenBlock.FACING, Direction.SOUTH),
                3,
            )
            level.setBlock(pos.south(), KiBiBlocks.KEYBOARD.get().defaultBlockState(), 3)
            case = level.getBlockEntity(pos) as CaseBlockEntity
            installRequired(case)
        }

        val keyboard: KeyboardBlockEntity get() = level.getBlockEntity(pos.south()) as KeyboardBlockEntity

        /** ケースのチャンクを FULL にする・FULL でなくす（強制読み込みの付け外し） */
        fun setFull(full: Boolean) {
            level.setChunkForced(chunk.x, chunk.z, full)
            if (full) level.getChunk(chunk.x, chunk.z)
        }

        /** FULL でなくなって、マシンが外された */
        fun assertDemoted(helper: GameTestHelper) {
            helper.assertTrue(!level.isLoaded(pos), "the chunk should no longer be FULL")
            helper.assertTrue(!case.isRemoved, "the chunk should stay in memory")
            helper.assertTrue(case.computer.machine == null, "the machine should be taken out: ${case.computer}")
        }

        /** 電源を切り、置いたものを外して、強制読み込みを戻す */
        fun remove() {
            setFull(true)
            if (case.computer.poweredOn) case.pressPowerButton()
            for (p in listOf(pos, pos.above(), pos.south())) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3)
            level.setChunkForced(chunk.x, chunk.z, false)
            level.setChunkForced(keeper.x, keeper.z, false)
        }
    }

    /** `/tmp`（tmpfs。写しに入り、ディスクには残らない）に印を書く行。答えの行 [MARKED] が出たら書き終わっている */
    private const val WRITE_MARKER = "echo marker-575 > /tmp/m575 && echo marked-575"
    private const val MARKED = "marked-575"
    private const val MARKER = "marker-575"

    private fun assertScreenShows(helper: GameTestHelper, be: CaseBlockEntity, line: String) {
        val screen = be.wiring.connectedScreen
        helper.assertTrue(screen != null, "the screen should be connected")
        val text = lines(screen!!.text)
        helper.assertTrue(text.any { it == line }, "the screen should show '$line': $text")
    }

    /** 戻し終わって、画面がつながった */
    private fun assertRestored(helper: GameTestHelper, be: CaseBlockEntity) {
        val machine = be.computer.machine
        helper.assertTrue(machine != null && !machine.isRestoring, "the case should finish restoring: ${be.computer}")
        helper.assertTrue(be.wiring.connectedMachine === machine, "the restored machine should be wired")
        helper.assertTrue(be.wiring.connectedScreen != null, "the screen should be connected after restoring")
    }

    /**
     * FULL に戻る経路（#575）: FULL でなくなったときに取り出して書いた世代から、FULL に戻ったときに戻る（`/tmp` の印が残る）。
     * 読み込みの `claim` で新しい ID を振られて冷えた起動をした台でも、その後に書いた世代があれば戻す（振った直後だけ冷えた起動にする）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun restoresTheDemotionSaveWhenTheChunkIsFullAgain(helper: GameTestHelper) {
        val far = FarComputer(helper, 440, 440)
        val be = far.case
        // onLoad の前に、検査に外れる ID と、電源が入っていた印を読ませる（onLoad で新しい ID を振り、冷えた起動になる）
        val tag = be.saveWithFullMetadata(helper.level.registryAccess())
        tag.putString(CaseBlockEntity.MACHINE_ID_TAG, "not-a-uuid")
        tag.putBoolean(CaseBlockEntity.POWERED_ON_TAG, true)
        be.loadWithComponents(tag, helper.level.registryAccess())
        var saved: MachineIdentity? = null
        helper.startSequence().thenWaitUntil {
            helper.assertTrue(be.lastClaim == ClaimResult.NEW_ID_INVALID, "the case should get a new id but was ${be.lastClaim}")
            helper.assertTrue(be.computer.machine != null, "the case should cold-boot: ${be.computer}")
        }.thenExecute {
            helper.assertTrue(be.lastRestoreSource == null, "a new id should cold-boot but was ${be.lastRestoreSource}")
        }.thenWaitUntil { assertPrompt(helper, be) }.thenExecute {
            typeLine(helper, far.keyboard, WRITE_MARKER)
        }.thenWaitUntil { assertScreenShows(helper, be, MARKED) }.thenExecute {
            far.setFull(false)
        }.thenWaitUntil {
            far.assertDemoted(helper)
            // 書き込みの完了を受け取って、取り出した世代に進んだ
            helper.assertTrue(be.pendingSave == null, "the demotion save should complete")
            helper.assertTrue((be.identity?.generation ?: 0) > 0, "the demotion should write a generation but was ${be.identity}")
        }.thenExecute {
            saved = be.identity
            far.setFull(true)
        }.thenWaitUntil { assertRestored(helper, be) }.thenExecute {
            val source = be.lastRestoreSource
            val expected = saved!!
            helper.assertTrue(
                source != null && source.id == expected.id && source.generation == expected.generation,
                "the case should restore $expected but was $source",
            )
            helper.assertTrue(be.computer.machine!!.state == MachineState.RUNNING, "the restored machine should run")
            typeLine(helper, far.keyboard, "cat /tmp/m575")
        }.thenWaitUntil { assertScreenShows(helper, be, MARKER) }.thenExecute { far.remove() }.thenSucceed()
    }

    /**
     * 競合（#575）: FULL でなくなったときの書き込みが、tick の `processCompletions` の後・順番待ちの前に終わると、順番が来たときに
     * `identity` はまだ前の世代を指す（完了を受け取っていない）。それでも予約の世代（取り出した世代）から戻る。
     * 窓を決定的に作るため、書き込みのスレッドを止めたまま FULL でなくして戻し、順番待ちに入ったら、列から外して書き込みを終わらせ、
     * 完了を受け取らずに（`awaitWrites` だけで）順番が来たのと同じに始める。書き込みのスレッドを tick をまたいで止めるので、専用のバッチにする
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS, batch = "restore_pending_generation")
    fun restoresTheReservedGenerationBeforeTheCompletionArrives(helper: GameTestHelper) {
        val far = FarComputer(helper, -440, -440)
        val be = far.case
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        val config = ServerMachines.saveConfig
        var before: MachineIdentity? = null
        var gate: WriterGate? = null
        helper.startSequence().thenWaitUntil { assertPrompt(helper, be) }.thenExecute {
            // 前の世代（印なし）を書いておく
            be.computer.prepareUnload()
            try {
                helper.assertTrue(saveNow(be, CapturePolicy.KILL_TRANSIENT) > 0, "the case should be saved")
            } finally {
                be.computer.endQuiesce()
            }
            before = be.identity
            helper.assertTrue(before != null, "the save should assign an id")
            typeLine(helper, far.keyboard, WRITE_MARKER)
        }.thenWaitUntil { assertScreenShows(helper, be, MARKED) }.thenExecute {
            // 順番待ちが書き込みの終わりを十分待つようにする（テストの間だけ）
            ServerMachines.saveConfig = config.copy(restoreWriteWaitMillis = 60_000)
            gate = WriterGate()
            far.setFull(false)
        }.thenWaitUntil {
            far.assertDemoted(helper)
            helper.assertTrue(be.pendingSave != null, "the demotion should reserve a generation")
        }.thenExecute {
            helper.assertTrue(be.identity == before, "the identity should not advance before the completion: ${be.identity}")
            far.setFull(true)
        }.thenWaitUntil {
            helper.assertTrue(be.computer.isRestorePending, "the case should wait for a restore: ${be.computer}")
        }.thenExecute {
            // 列から外し（二重に始めない）、書き込みを終わらせて、完了を受け取らないまま順番が来たのと同じに始める
            ServerMachines.cancelRestore(be)
            gate!!.close()
            gate = null
            val store = WorldMachineSaves.current!!
            val id = be.currentIdentity!!.id
            helper.assertTrue(store.awaitWrites(id, 10_000), "the demotion save should finish")
            val reserved = be.pendingSave?.identity
            val previous = before!!
            helper.assertTrue(be.identity == previous, "the completion should not be taken yet: ${be.identity}")
            helper.assertTrue(
                reserved != null && reserved.generation > previous.generation,
                "the reserved generation should be newer than $previous but was $reserved",
            )
            be.startRestore(false)
            val source = be.lastRestoreSource
            helper.assertTrue(
                source != null && source.id == reserved!!.id && source.generation == reserved.generation,
                "the case should restore the reserved $reserved but was $source",
            )
            ServerMachines.saveConfig = config
        }.thenWaitUntil { assertRestored(helper, be) }.thenExecute {
            typeLine(helper, far.keyboard, "cat /tmp/m575")
        }.thenWaitUntil { assertScreenShows(helper, be, MARKER) }.thenExecute { far.remove() }.thenSucceed()
    }

    /**
     * 書き込みの期限切れ（#575、ADR 0084 の規則 9）: FULL に戻ったときに、その ID の書き込みが期限までに終わらなければ、冷えた起動にする。
     * 遅れていた書き込みが後から終わると、`identity` はその世代に進む。書き込みのスレッドを tick をまたいで止めるので、専用のバッチにする
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS, batch = "restore_write_timeout")
    fun coldBootsWhenTheWriteDoesNotFinishInTime(helper: GameTestHelper) {
        val far = FarComputer(helper, 440, -440)
        val be = far.case
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        val config = ServerMachines.saveConfig
        var gate: WriterGate? = null
        var starts = 0
        var before = RestoreStats.now()
        helper.startSequence().thenWaitUntil { assertPrompt(helper, be) }.thenExecute {
            // 順番が来たら、書き込みを待たずに期限切れにする（テストの間だけ）
            ServerMachines.saveConfig = config.copy(restoreWriteWaitMillis = 0)
            starts = be.restoreStarts
            before = RestoreStats.now()
            gate = WriterGate()
            far.setFull(false)
        }.thenWaitUntil {
            far.assertDemoted(helper)
            helper.assertTrue(be.pendingSave != null, "the demotion should reserve a generation")
        }.thenExecute {
            far.setFull(true)
        }.thenWaitUntil {
            helper.assertTrue(be.computer.machine != null, "the restore should start: ${be.computer}")
        }.thenExecute {
            helper.assertTrue(be.lastRestoreSource == null, "a timed-out write should cold-boot but was ${be.lastRestoreSource}")
            // 共有の ServerMachines.Stats は並行するほかのテストでも動くので見ない（#708）。このケースの記録を見る
            helper.assertTrue(be.restoreStarts > starts, "the restore should start from this case")
            helper.assertTrue(be.lastRestoreTimedOut, "the restore should see the write timeout")
            // このバッチは専用なので、数え口も見る（#708）。ほかの復元で増えても落ちないよう、増えたかだけを比べる
            val after = RestoreStats.now()
            helper.assertTrue(after.writeTimeouts > before.writeTimeouts, "the write timeout should be counted")
            helper.assertTrue(after.coldStarts > before.coldStarts, "the cold boot should be counted")
            val pending = be.pendingSave
            helper.assertTrue(pending != null, "the write should still be pending")
            ServerMachines.saveConfig = config
            gate!!.close()
            gate = null
            ServerMachines.drainSaves()
            // 遅れていた書き込みの完了を受け取って、その世代に進む（ADR 0084 の「失う場面」と同じ形）
            helper.assertTrue(be.pendingSave == null && be.identity == pending!!.identity, "the late write should be adopted: ${be.identity}")
        }.thenWaitUntil { assertPrompt(helper, be) }.thenExecute { far.remove() }.thenSucceed()
    }

    /** [ServerMachines.Stats] の復元の数え口の、ある時点の値（#708） */
    private data class RestoreStats(
        val fromSave: Long,
        val coldStarts: Long,
        val writeTimeouts: Long,
        val restored: Long,
        val fallbacks: Long,
        val versionMismatches: Long,
    ) {
        companion object {
            fun now() = with(ServerMachines.Stats) {
                RestoreStats(restoresFromSave, restoresColdStart, restoreWriteTimeouts, restored, restoreFallbacks, restoreVersionMismatches)
            }
        }
    }

    /**
     * 復元の数え口（#708）: [ServerMachines.recordRestoreStart]・[ServerMachines.recordRestoreFinished] が、結果ごとに決まった口だけを1つずつ増やす。
     * 1回の tick の中で前後を比べるので、並行するほかのテストの復元は混ざらない
     */
    @JvmStatic
    @GameTest(template = "empty")
    fun restoreStatsCountEachOutcome(helper: GameTestHelper) {
        val pos = helper.absolutePos(CASE)
        fun expect(label: String, before: RestoreStats, expected: RestoreStats) {
            val after = RestoreStats.now()
            helper.assertTrue(after == expected, "$label: expected $expected but was $after (before $before)")
        }

        var before = RestoreStats.now()
        ServerMachines.recordRestoreStart(fromSave = true, writesTimedOut = false)
        expect("from save", before, before.copy(fromSave = before.fromSave + 1))

        before = RestoreStats.now()
        ServerMachines.recordRestoreStart(fromSave = false, writesTimedOut = false)
        expect("cold start", before, before.copy(coldStarts = before.coldStarts + 1))

        before = RestoreStats.now()
        ServerMachines.recordRestoreStart(fromSave = false, writesTimedOut = true)
        expect("write timeout", before, before.copy(coldStarts = before.coldStarts + 1, writeTimeouts = before.writeTimeouts + 1))

        before = RestoreStats.now()
        ServerMachines.recordRestoreFinished(pos, RestoreOutcome.RESTORED, null)
        expect("restored", before, before.copy(restored = before.restored + 1))

        before = RestoreStats.now()
        ServerMachines.recordRestoreFinished(pos, RestoreOutcome.COLD_BOOT, ColdBootCause.RESTORE_FAILED)
        expect("fallback", before, before.copy(fallbacks = before.fallbacks + 1))

        before = RestoreStats.now()
        ServerMachines.recordRestoreFinished(pos, RestoreOutcome.COLD_BOOT, ColdBootCause.MOD_VERSION)
        expect("version mismatch", before, before.copy(fallbacks = before.fallbacks + 1, versionMismatches = before.versionMismatches + 1))

        before = RestoreStats.now()
        ServerMachines.recordRestoreFinished(pos, RestoreOutcome.CANCELLED, null)
        expect("cancelled", before, before)
        helper.succeed()
    }

    /**
     * 保存した世代から戻した復元は、始まりで [RestoreStats.fromSave]、終わりで [RestoreStats.restored] を1つずつ増やし、冷えた起動の口は増やさない
     * （#708。[CaseBlockEntity] が `recordRestoreStart`・`recordRestoreFinished` を呼び忘れると落ちる）。
     * 数え口は共有なので、ほかの復元が混ざらないように専用のバッチにする
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS, batch = "restore_stats")
    fun restoreFromSaveIsCounted(helper: GameTestHelper) {
        val first = placeComputer(helper)
        var before = RestoreStats.now()
        var copy: CaseBlockEntity? = null
        helper.startSequence().thenWaitUntil { assertPrompt(helper, first) }.thenExecute {
            val tag = saveAndTag(helper, first)
            before = RestoreStats.now()
            copy = reload(helper, tag)
        }.thenWaitUntil {
            val be = copy!!
            helper.assertTrue(be.lastRestoreOutcome == RestoreOutcome.RESTORED, "the restore should finish: ${be.computer}")
        }.thenExecute {
            val after = RestoreStats.now()
            helper.assertTrue(
                after == before.copy(fromSave = before.fromSave + 1, restored = before.restored + 1),
                "a restore from the save should be counted once: before $before, after $after",
            )
            helper.setBlock(CASE, Blocks.AIR)
        }.thenSucceed()
    }
}
