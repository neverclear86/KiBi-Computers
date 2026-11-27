package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.neoforged.fml.ModList
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.ComputerError
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
import pro.ikili.kibicomputers.core.persist.SaveReadiness
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

    /** tick をまたいで待たせる claim の、読みの待ちの上限（[WriterGate] の helper ありの上限と同じ 1,800 秒。#872） */
    private const val HELD_CLAIM_WAIT_MILLIS = 1_800_000L

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

    /**
     * 止めてから保存の判定を見て、READY なら止めたまま書いてケースの NBT を返す。READY でなければ放して `null`
     * （止めたままだと燃料待ちが起こされず、READY に近づかないことがある）。止めた後は新しい step が始まらないので、
     * 確かめた判定のまま写しが取られる
     */
    private fun saveAndTagIfReady(helper: GameTestHelper, be: CaseBlockEntity): CompoundTag? {
        be.computer.prepareUnload()
        try {
            if (be.computer.saveReadiness() != SaveReadiness.READY) return null
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
            // 取り消した前の復元の遅れた知らせ（通し番号が古い）は、今の復元の記録を上書きしない（#708）
            be.noteRestoreOutcome(be.restoreStarts - 1, RestoreOutcome.CANCELLED)
            helper.assertTrue(
                be.lastRestoreOutcome == RestoreOutcome.RESTORED,
                "a stale notification should not overwrite the outcome: ${be.lastRestoreOutcome}",
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
     * 有効な ID と書けた世代を持つ電源の入ったケースが FULL になったばかりのチャンクにあり、`onLoad` の前に FULL に戻った経路
     * （`restorePromoted`）が来ても、そこでは順番待ちに入らず、`onLoad` が ID の `claim` を通してから順番待ちに入って、
     * 保存した世代から戻る（冷えた起動にならない。#653）。`restorePromoted` が呼ぶ `restorePromotedCase` を、置き直した直後
     * （次の tick の `onLoad` の前）に呼んで、通しで確かめる
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun restoresAPromotedChunkCaseAfterOnLoad(helper: GameTestHelper) {
        val first = placeComputer(helper)
        var copy: CaseBlockEntity? = null
        helper.startSequence().thenWaitUntil { assertPrompt(helper, first) }.thenExecute {
            val tag = saveAndTag(helper, first)
            helper.assertTrue(tag.getBoolean(CaseBlockEntity.POWERED_ON_TAG), "a running case should save PoweredOn: $tag")
            val be = reload(helper, tag)
            copy = be
            helper.assertTrue(!be.isLoadedOnServer, "onLoad should not have run yet")
            // FULL に戻った経路が onLoad の前に来た
            ServerMachines.restorePromotedCase(be)
            helper.assertTrue(!be.computer.isRestorePending, "the promoted path should not queue the case before onLoad: ${be.computer}")
        }.thenWaitUntil {
            val be = copy!!
            helper.assertTrue(be.isLoadedOnServer, "onLoad should run")
            assertRestored(helper, be)
        }.thenExecute {
            val be = copy!!
            helper.assertTrue(
                be.restoreStarts == 1 && be.lastRestoreSource != null && be.lastRestoreOutcome == RestoreOutcome.RESTORED,
                "the restore should not cold-boot: starts=${be.restoreStarts}, source=${be.lastRestoreSource}, " +
                    "outcome=${be.lastRestoreOutcome}, claim=${be.lastClaim}, ${be.computer}",
            )
            be.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }.thenSucceed()
    }

    /**
     * claim が `owner.bin` の読みを待った台（#869。覚えていない ID。再起動の後と同じ）も、確定した後に復元の順番待ちに入り、
     * 保存した世代から戻る（ID のないまま順番が来て冷えた起動にならない。#653 と同じ不具合）。待つ間は FULL に戻った経路
     * （`restorePromotedCase`）でも順番待ちに入らず、メインスレッドで `owner.bin` を読まない
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun restoresACaseWhoseClaimWaitedForARead(helper: GameTestHelper) {
        val first = placeComputer(helper)
        var copy: CaseBlockEntity? = null
        var sawWaiting = false
        var reads = 0L
        helper.startSequence().thenWaitUntil { assertPrompt(helper, first) }.thenExecute {
            val tag = saveAndTag(helper, first)
            val id = first.identity!!.id
            val be = reload(helper, tag)
            copy = be
            val store = WorldMachineSaves.current!!
            store.forgetOwnerForTest(id)
            reads = store.mainOwnerReads
        }.thenWaitUntil {
            val be = copy!!
            helper.assertTrue(be.isLoadedOnServer, "onLoad should run")
            if (be.isClaimDeferred) {
                // onLoad の tick（確定は次の tick の始め）
                sawWaiting = true
                helper.assertTrue(be.lastClaim == null && be.currentIdentity == null, "a waiting case has no id yet")
                // FULL に戻った経路が来ても、ID のないまま順番待ちに入れない（入れると冷えた起動になり、下の確かめが落ちる）
                ServerMachines.restorePromotedCase(be)
                helper.fail("waiting for the claim")
            }
            assertRestored(helper, be)
        }.thenExecute {
            val be = copy!!
            helper.assertTrue(sawWaiting, "the claim should have waited for owner.bin")
            helper.assertTrue(be.lastClaim == ClaimResult.KEPT, "the case should keep its id but was ${be.lastClaim}")
            helper.assertTrue(WorldMachineSaves.current!!.mainOwnerReads == reads, "claim should not read owner.bin on the main thread")
            helper.assertTrue(
                be.restoreStarts == 1 && be.lastRestoreSource != null && be.lastRestoreOutcome == RestoreOutcome.RESTORED,
                "the restore should not cold-boot: starts=${be.restoreStarts}, source=${be.lastRestoreSource}, " +
                    "outcome=${be.lastRestoreOutcome}, claim=${be.lastClaim}, ${be.computer}",
            )
            be.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }.thenSucceed()
    }

    /**
     * 規則 7a の例外（#869、ADR 0084）: マシンのある台（ID を持たずに動いている台）が `/data merge` で読みの要る ID を得ると、
     * 待たずに同期の claim で確定する（メインスレッドで `owner.bin` を 1 回読む）。待ちにならないので取り出しも効き、
     * 動いている状態を失わない（その ID で書いた世代から、置き直した後に戻る）。数え口を比べるので、専用のバッチにする
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS, batch = "claim_sync_exception")
    fun runningCaseClaimsAMergedIdSynchronously(helper: GameTestHelper) {
        val be = placeComputer(helper)
        val store = WorldMachineSaves.current!!
        val merged = UUID.randomUUID()
        var copy: CaseBlockEntity? = null
        helper.startSequence().thenWaitUntil {
            helper.assertTrue(be.isLoadedOnServer, "onLoad should run")
        }.thenExecute {
            val machine = be.computer.machine
            helper.assertTrue(machine != null, "the case should run: ${be.computer}")
            helper.assertTrue(be.currentIdentity == null, "the running case should not have an id yet: ${be.currentIdentity}")
            // 覚えていない ID（再起動の後と同じく、claim は owner.bin を読む）
            store.forgetOwnerForTest(merged)
            val reads = store.mainOwnerReads
            val deferred = store.deferredClaims
            val tag = be.saveWithoutMetadata(helper.level.registryAccess()).also { tag ->
                tag.putString(CaseBlockEntity.MACHINE_ID_TAG, merged.toString())
                tag.putLong(CaseBlockEntity.SAVE_GENERATION_TAG, 0)
                tag.putLong(CaseBlockEntity.OWNER_EPOCH_TAG, 0)
            }
            be.loadWithComponents(tag, helper.level.registryAccess())
            helper.assertTrue(!be.isClaimDeferred && store.deferredClaims == deferred, "a case with a machine should not wait")
            helper.assertTrue(
                be.lastClaim == ClaimResult.KEPT && be.identity?.id == merged,
                "the merged id should be kept: ${be.lastClaim}, ${be.identity}",
            )
            helper.assertTrue(
                store.mainOwnerReads == reads + 1,
                "the synchronous claim reads owner.bin once: ${store.mainOwnerReads - reads}",
            )
            helper.assertTrue(be.computer.machine === machine, "the machine should keep running")
        }.thenWaitUntil { assertPrompt(helper, be) }.thenExecute {
            typeLine(helper, keyboardAt(helper), WRITE_MARKER)
        }.thenWaitUntil { assertScreenShows(helper, be, MARKED) }.thenExecute {
            val tag = saveAndTag(helper, be)
            helper.assertTrue(
                be.identity?.id == merged && be.identity!!.generation > 0,
                "the state should be written under $merged: ${be.identity}",
            )
            copy = reload(helper, tag)
        }.thenWaitUntil { assertRestored(helper, copy!!) }.thenExecute {
            val c = copy!!
            helper.assertTrue(c.lastRestoreSource?.id == merged, "the case should restore from $merged but was ${c.lastRestoreSource}")
            typeLine(helper, keyboardAt(helper), "cat /tmp/m575")
        }.thenWaitUntil { assertScreenShows(helper, copy!!, MARKER) }.thenExecute {
            copy!!.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }.thenSucceed()
    }

    private fun keyboardAt(helper: GameTestHelper): KeyboardBlockEntity = helper.getBlockEntity(KEYBOARD)

    /**
     * claim が読みを待つ間に、チャンクが FULL でなくなる（#869、ADR 0084・0085）: 待つ台は取り出さず、`chunkDemoted` が順番待ちの印を下ろす。
     * FULL でないまま確定すると、順番待ちには入れない（`offerRestoreAfterClaim` の `hasChunkAt`）。FULL に戻ったときに
     * `restorePromotedCase` が入れ、保存した世代から戻る（`/tmp` の印が残る）。待つ間のケースの NBT（チャンクの保存が書くもの）は
     * 生の値（MachineId・SaveGeneration・OwnerEpoch）のままで、次の読み込みは同じ値で同じ判定をする。
     * 書き込みのスレッドを tick をまたいで止める（読みを待たせる）ので、専用のバッチにする。
     * ゲートを放した後の確定は壁時計で待つ（[claimDecideWait]。#912）。tick の上限は、前の段（起動・降格の待ち）が使う
     * [BOOT_TIMEOUT_TICKS] に、確定の待ちの分（[CLAIM_DECIDE_TIMEOUT_TICKS]）を別に足して、壁時計の上限が必ず先に来るようにする
     */
    @JvmStatic
    @GameTest(
        template = SmokeGameTests.EMPTY,
        timeoutTicks = BOOT_TIMEOUT_TICKS + CLAIM_DECIDE_TIMEOUT_TICKS,
        batch = "deferred_claim_demotion",
    )
    fun deferredClaimWhileTheChunkLeavesFull(helper: GameTestHelper) {
        val far = FarComputer(helper)
        val level = helper.level
        val first = far.case
        helper.assertTrue(first.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${first.computer}")
        val store = WorldMachineSaves.current!!
        var saved: MachineIdentity? = null
        var copy: CaseBlockEntity? = null
        var gate: WriterGate? = null
        var sideGate: SideReaderGate? = null
        var reads = 0L
        var wait: WallClockWait? = null
        // 壁時計の上限で落ちたときの診断（thenWaitUntil の中の失敗は tick の上限まで握りつぶされるので、次の thenExecute で落とす）
        var expired: String? = null

        fun assertRawTag(be: CaseBlockEntity, what: String) {
            val id = saved!!
            val tag = be.saveWithoutMetadata(level.registryAccess())
            helper.assertTrue(
                tag.getString(CaseBlockEntity.MACHINE_ID_TAG) == id.id.toString() &&
                    tag.getLong(CaseBlockEntity.SAVE_GENERATION_TAG) == id.generation &&
                    tag.getLong(CaseBlockEntity.OWNER_EPOCH_TAG) == id.epoch &&
                    tag.getBoolean(CaseBlockEntity.POWERED_ON_TAG),
                "$what: the NBT should keep the raw values of $id but was $tag",
            )
        }

        helper.startSequence().thenWaitUntil { assertPrompt(helper, first) }.thenExecute {
            typeLine(helper, far.keyboard, WRITE_MARKER)
        }.thenWaitUntil { assertScreenShows(helper, first, MARKED) }.thenExecute {
            val tag = saveAndTag(helper, first)
            saved = first.identity
            // 置き直して、覚えていない ID（再起動の後と同じ）の NBT を onLoad の前に読ませる。読みは書き込みのスレッドに回り、門の後ろで待つ
            level.setBlock(far.pos, Blocks.AIR.defaultBlockState(), 3)
            level.setBlock(far.pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get().defaultBlockState(), 3)
            val be = level.getBlockEntity(far.pos) as CaseBlockEntity
            be.loadWithComponents(tag, level.registryAccess())
            store.forgetOwnerForTest(saved!!.id)
            // claim の読みの待ちの上限（壁時計。writerFlushMillis が claimWaitMillis になる）を、ゲートと同じ長さにする（テストの間だけ。#872）。
            // 重いマシンで降格を待つ数 tick が 30 秒を超えると、読みを待たずに新しい UUID（NEW_ID_UNREADABLE）になって落ちる
            restoreSaveConfigWhenDone(helper, ServerMachines.saveConfig)
            ServerMachines.saveConfig = ServerMachines.saveConfig.copy(writerFlushMillis = HELD_CLAIM_WAIT_MILLIS)
            gate = WriterGate(helper)
            // 仕事のない ID の読みは列の外の読み手に回る（#877）ので、そちらも止める
            sideGate = SideReaderGate(helper, store)
            reads = store.mainOwnerReads
            copy = be
        }.thenWaitUntil {
            helper.assertTrue(copy!!.isLoadedOnServer, "onLoad should run")
        }.thenExecute {
            val be = copy!!
            helper.assertTrue(be.isClaimDeferred, "the claim should wait for owner.bin")
            helper.assertTrue(
                be.computer.isRestorePending && !ServerMachines.isRestoreQueued(be),
                "only the restore mark is set: ${be.computer}",
            )
            assertRawTag(be, "while waiting")
            far.setFull(false)
        }.thenWaitUntil {
            helper.assertTrue(!level.isLoaded(far.pos), "the chunk should no longer be FULL")
            // FULL でなくなったチャンクは tick の終わりに流す（saveForDemotion → chunkDemoted）
            helper.assertTrue(!copy!!.computer.isRestorePending, "the demotion should drop the restore mark: ${copy!!.computer}")
        }.thenExecute {
            val be = copy!!
            helper.assertTrue(!be.isRemoved && be.isClaimDeferred, "the case should still wait in memory")
            helper.assertTrue(be.computer.machine == null && be.pendingSave == null, "a waiting case is not captured: ${be.computer}")
            helper.assertTrue(
                !be.computer.isRestorePending && !ServerMachines.isRestoreQueued(be),
                "the demotion should drop the restore mark: ${be.computer}",
            )
            // 取り出さなかったので、チャンクの保存が書く NBT も生の値のまま（次の読み込みは同じ値で claim する）
            assertRawTag(be, "after the demotion")
            gate!!.close()
            gate = null
            sideGate!!.close()
            sideGate = null
            wait = claimDecideWait()
        }.thenWaitUntil {
            val be = copy!!
            if (!be.isClaimDeferred) return@thenWaitUntil
            // 上限を過ぎたら、診断を覚えて待ちを終える（次の thenExecute が落とす）
            val w = wait!!
            if (!w.pace()) {
                expired = claimDecideDiagnostics(store, w, listOf(be), helper)
                return@thenWaitUntil
            }
            helper.fail("the waiting claim should be decided")
        }.thenExecute {
            expired?.let { helper.fail("the waiting claim should be decided: $it") }
            val be = copy!!
            val id = saved!!
            helper.assertTrue(be.lastClaim == ClaimResult.KEPT && be.identity == id, "the case should keep $id: ${be.lastClaim}, ${be.identity}")
            helper.assertTrue(
                !ServerMachines.isRestoreQueued(be) && be.computer.machine == null,
                "a case outside FULL chunks is not offered: ${be.computer}",
            )
            far.setFull(true)
        }.thenWaitUntil { assertRestored(helper, copy!!) }.thenExecute {
            val be = copy!!
            val id = saved!!
            val source = be.lastRestoreSource
            helper.assertTrue(
                source != null && source.id == id.id && source.generation == id.generation,
                "the case should restore $id but was $source",
            )
            helper.assertTrue(store.mainOwnerReads == reads, "claim should not read owner.bin on the main thread")
            typeLine(helper, far.keyboard, "cat /tmp/m575")
        }.thenWaitUntil { assertScreenShows(helper, copy!!, MARKER) }.thenExecute {
            copy!!.pressPowerButton()
            far.remove()
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
            // シェルが restored を受けるキューを開いていた（#636 の切り分けのログに出す数。0 なら描き直さない）
            helper.assertTrue(machine!!.restoredEventQueues >= 1, "restored should reach the shell's queue: ${machine.restoredEventQueues}")
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

    /**
     * Lua のプログラムを丸ごと保存して戻す（#89）: 標準 OS のシェルから `lua -e` で、`parallel.waitForAll` の中の3つのコルーチン
     * （`restored` を待つもの、`os.sleep` をくり返して数えるもの、キーを待つもの）が待っている間に書き、置き直して戻す。
     * 戻した後に、`restored` を受けた行、数え終えた行、戻した後に押したキーを受けた行が画面に出る。画面の中身は保存しないので
     * （ADR 0087、#630）、確かめるのは戻した後に出た行だけ。印は連結で作り、打った行（画面に残る）と一致させない。
     *
     * 数えるコルーチンは、`restored` を受けるまで `os.sleep` をくり返し（書く時点で必ず眠りの途中にいる）、受けてから3回眠って数える。
     * 回数を決め打ちにすると、GameTest のサーバーは tick の間で待たないので、`os.sleep`（実時間）を待つ間に tick の上限を使い切るか、
     * 遅いランナーでは書く前に数え終えてしまう
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun restoresALuaProgramWaitingInParallel(helper: GameTestHelper) {
        val first = placeComputer(helper)
        var copy: CaseBlockEntity? = null
        val script = "os.queueEvent('o'); os.pullEvent('o'); local r, m = false, 0; parallel.waitForAll(" +
            "function() local e = os.pullEvent('restored'); r = true; print('lr,' .. e) end, " +
            "function() while not r do os.sleep(0.05) end; for i = 1, 3 do os.sleep(0.05); m = m + 1 end; " +
            "print('ln,' .. m) end, " +
            "function() print('lw' .. 'ait'); repeat local _, k = os.pullEvent('key') until k == keys.q; print('lk,' .. m) end)"
        helper.startSequence().thenWaitUntil { assertPrompt(helper, first) }.thenExecute {
            typeLine(helper, helper.getBlockEntity(KEYBOARD), "lua -e \"$script\"")
        }.thenWaitUntil {
            val text = lines(first.wiring.connectedScreen!!.text)
            helper.assertTrue(text.any { it == "lwait" }, "lua should wait in parallel: $text")
        }.thenWaitUntil {
            // step の途中で書くと、写しの中で Lua が殺されてシェルだけが戻る。数えるコルーチンのタイマーは tick と無関係に鳴るので、
            // 止めてから READY を確かめ、止めたまま書く（READY でなければ放して次の tick にやり直す）
            val tag = saveAndTagIfReady(helper, first)
            helper.assertTrue(tag != null, "lua should be at a wait point: ${first.computer.saveReadiness()}")
            copy = reload(helper, tag!!)
        }.thenWaitUntil {
            assertRestored(helper, copy!!)
        }.thenExecute {
            val be = copy!!
            helper.assertTrue(
                be.restoreStarts == 1 && be.lastRestoreOutcome == RestoreOutcome.RESTORED,
                "the restore should not cold-boot: starts=${be.restoreStarts}, outcome=${be.lastRestoreOutcome}, ${be.computer}",
            )
        }.thenWaitUntil {
            val text = lines(copy!!.wiring.connectedScreen!!.text)
            helper.assertTrue(text.any { it == "lr,restored" }, "the restored lua should receive restored: $text")
            helper.assertTrue(text.any { it == "ln,3" }, "the restored lua should keep counting: $text")
        }.thenExecute {
            for (packet in KeyboardEventCodec.encode(listOf(KeyboardEvent.KeyDown(Key.Q, 0, false)))) {
                helper.assertTrue(helper.getBlockEntity<KeyboardBlockEntity>(KEYBOARD).receive(UUID(0, 1), packet) == 1, "keyboard should accept the key")
            }
        }.thenWaitUntil {
            val text = lines(copy!!.wiring.connectedScreen!!.text)
            helper.assertTrue(text.any { it == "lk,3" }, "the restored lua should receive the key: $text")
        }.thenExecute {
            copy!!.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }.thenSucceed()
    }

    /**
     * Esc を押した後に保存しても、戻すとキーを打つ前に行を描き直し（#636）、次の Enter で行を実行する（#769。シェルは Esc の続きを
     * 待たずに区切り、キーの境目の待ちに戻っている）。キーボードの画面で Esc を押して閉じようとすると Esc がコンピューターに届くので、
     * この形で保存されることがある
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun restoredShellRedrawsAfterEscape(helper: GameTestHelper) {
        val first = placeComputer(helper)
        var copy: CaseBlockEntity? = null
        fun send(event: KeyboardEvent) {
            for (packet in KeyboardEventCodec.encode(listOf(event))) {
                helper.assertTrue(helper.getBlockEntity<KeyboardBlockEntity>(KEYBOARD).receive(UUID(0, 1), packet) == 1, "keyboard should accept the input")
            }
        }
        helper.startSequence().thenWaitUntil { assertPrompt(helper, first) }.thenExecute {
            send(KeyboardEvent.Text("echo pending"))
        }.thenWaitUntil {
            val text = lines(first.wiring.connectedScreen!!.text)
            helper.assertTrue(text.any { it == "$ echo pending" }, "the typed text should be shown: $text")
            helper.assertTrue(first.computer.machine!!.isAtWaitPoint, "the shell should wait for the next key")
        }.thenExecute {
            send(KeyboardEvent.KeyDown(Key.ESCAPE, 0, false))
            // Esc は、シェルが読んで区切るまで保存しない。待機点かどうかだけでは、Esc を読み終えたかは分からないので、Esc を届けて
            // 読ませるのに十分な時間を置く。読み終えたことの決定的な確認は、core の StandardOsRestoreTest・ShellEscapeKeyTest
            // （TTY の入力が空であることを見る）
        }.thenIdle(20).thenWaitUntil {
            helper.assertTrue(first.computer.machine!!.isAtWaitPoint, "the shell should wait for the next key after Esc")
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
            helper.assertTrue(text.any { it == "$ echo pending" }, "the restored shell should redraw the line after Esc: $text")
        }.thenExecute {
            // Esc の後の Enter は、エスケープシーケンスの続きとして捨てられず、行を実行する（#769）
            send(KeyboardEvent.KeyDown(Key.ENTER, 0, false))
        }.thenWaitUntil {
            val text = lines(copy!!.wiring.connectedScreen!!.text)
            helper.assertTrue(text.any { it == "pending" }, "Enter after Esc should run the line: $text")
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
        // だれも読み込んでいない離れたチャンク（#747）
        val slot = FarChunks.allocate(helper, keeper = true)
        val pos = slot.pos(6, 200, 9)
        level.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get().defaultBlockState(), 3)
        val be = level.getBlockEntity(pos) as CaseBlockEntity
        installRequired(be)
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        val config = ServerMachines.saveConfig
        // 順番待ちが、書き込みの終わりを十分待つようにする（テストの間だけ）
        ServerMachines.saveConfig = config.copy(restoreWriteWaitMillis = 60_000)
        // 途中で落ちても、後のバッチに 60 秒の設定を残さない（#872）
        restoreSaveConfigWhenDone(helper, config)
        var starts = 0
        var gate: WriterGate? = null
        // onLoad（追跡と索引）を待ってから、書き込みを積む
        helper.runAfterDelay(1) {
            be.computer.prepareUnload()
            gate = WriterGate(helper)
            helper.assertTrue(be.captureAndSubmit(CapturePolicy.KILL_TRANSIENT) != null, "the case should submit a write")
            // マシンだけを外し（電源の状態は残る）、書き込みの終わりを待つ復元の順番待ちに入れてから、FULL でなくす
            be.computer.chunkUnloaded()
            starts = be.restoreStarts
            helper.assertTrue(be.computer.beginRestore(), "the powered-on case should wait for a restore")
            ServerMachines.offerRestore(be)
            slot.setFull(false)
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
                slot.setFull(true)
            }.thenWaitUntil {
                helper.assertTrue(
                    be.computer.isRestorePending || be.computer.machine != null,
                    "the case should wait for a restore after the chunk is FULL again: ${be.computer}",
                )
            }.thenExecute {
                // 動いていれば電源を切ってから（順番待ちの台に電源ボタンを押すと起動するので押さない）、部品の落とし物を残さずに消す（#746）
                if (be.computer.status == ComputerStatus.RUNNING) be.pressPowerButton()
                removeCaseWithoutDrops(level, pos)
                slot.release()
            }.thenSucceed()
        }
    }

    // ---- FULL に戻ったチャンクの復元（#575） ----

    /**
     * だれも読み込んでいないチャンク（[FarChunks] で割り当てる。#747）に、ケース・画面・キーボードを置く。
     * 5 チャンク離れたチャンク（keeper）を強制読み込みにしておくので、ケースのチャンクの強制読み込みを外すと、FULL でなくなるが
     * アンロードはされない（#545）
     */
    private class FarComputer(helper: GameTestHelper) {
        val level = helper.level
        val slot = FarChunks.allocate(helper, keeper = true)
        val pos = slot.pos(6, 200, 9)
        val case: CaseBlockEntity

        init {
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
        fun setFull(full: Boolean) = slot.setFull(full)

        /** FULL でなくなって、マシンが外された */
        fun assertDemoted(helper: GameTestHelper) {
            helper.assertTrue(!level.isLoaded(pos), "the chunk should no longer be FULL")
            helper.assertTrue(!case.isRemoved, "the chunk should stay in memory")
            helper.assertTrue(case.computer.machine == null, "the machine should be taken out: ${case.computer}")
        }

        /** 電源を切り、置いたものを外して、強制読み込みを戻す */
        fun remove() {
            setFull(true)
            if (case.computer.status == ComputerStatus.RUNNING) case.pressPowerButton()
            // 画面とキーボードは何も落とさない。ケースは部品を落とさずに消す（#746）
            for (p in listOf(pos.above(), pos.south())) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3)
            removeCaseWithoutDrops(level, pos)
            slot.release()
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
        val far = FarComputer(helper)
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
        val far = FarComputer(helper)
        val be = far.case
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        val config = ServerMachines.saveConfig
        var before: MachineIdentity? = null
        var gate: WriterGate? = null
        // 途中で落ちても、後のバッチに変えた設定を残さない（#872）
        restoreSaveConfigWhenDone(helper, config)
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
            gate = WriterGate(helper)
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
        val far = FarComputer(helper)
        val be = far.case
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        val config = ServerMachines.saveConfig
        var gate: WriterGate? = null
        var starts = 0
        // 途中で落ちても、後のバッチに期限 0 ms の設定を残さない（#872）
        restoreSaveConfigWhenDone(helper, config)
        var before = RestoreStats.now()
        helper.startSequence().thenWaitUntil { assertPrompt(helper, be) }.thenExecute {
            // 順番が来たら、書き込みを待たずに期限切れにする（テストの間だけ）
            ServerMachines.saveConfig = config.copy(restoreWriteWaitMillis = 0)
            starts = be.restoreStarts
            before = RestoreStats.now()
            gate = WriterGate(helper)
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
     * 復元の数え口（#708）: [ServerMachines.recordRestoreStart]・[ServerMachines.countRestoreFinished] が、結果ごとに決まった口だけを1つずつ増やす。
     * 1回の tick の中で前後を比べるので、並行するほかのテストの復元は混ざらない。終わりの側は、ログを出す [ServerMachines.recordRestoreFinished]
     * ではなく数えるだけの口を呼ぶ（作り物の冷えた起動をログに出さず、「起動ごとに1回の要約」（#88）も使い切らない。ログの出し方は core の
     * `ColdBootLogTest` が受け持つ）
     */
    @JvmStatic
    @GameTest(template = "empty")
    fun restoreStatsCountEachOutcome(helper: GameTestHelper) {
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
        ServerMachines.countRestoreFinished(RestoreOutcome.RESTORED, null)
        expect("restored", before, before.copy(restored = before.restored + 1))

        before = RestoreStats.now()
        ServerMachines.countRestoreFinished(RestoreOutcome.COLD_BOOT, ColdBootCause.RESTORE_FAILED)
        expect("fallback", before, before.copy(fallbacks = before.fallbacks + 1))

        before = RestoreStats.now()
        ServerMachines.countRestoreFinished(RestoreOutcome.COLD_BOOT, ColdBootCause.MOD_VERSION)
        expect("version mismatch", before, before.copy(fallbacks = before.fallbacks + 1, versionMismatches = before.versionMismatches + 1))

        before = RestoreStats.now()
        ServerMachines.countRestoreFinished(RestoreOutcome.CANCELLED, null)
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
            // 配線まで待つ（知らせは復元の途中が終わる前に配られる）。冷えた起動・受け付けなかったときも配線はされるので、すぐ次で落ちる
            val be = copy!!
            val machine = be.computer.machine
            helper.assertTrue(machine != null && !machine.isRestoring, "the case should finish restoring: ${be.computer}")
            helper.assertTrue(be.wiring.connectedMachine === machine, "the restored machine should be wired")
        }.thenExecute {
            val be = copy!!
            val after = RestoreStats.now()
            helper.assertTrue(
                be.restoreStarts == 1 && be.lastRestoreSource != null && be.lastRestoreOutcome == RestoreOutcome.RESTORED,
                "the restore should not cold-boot: starts=${be.restoreStarts}, source=${be.lastRestoreSource}, " +
                    "outcome=${be.lastRestoreOutcome}, claim=${be.lastClaim}, before $before, after $after, ${be.computer}",
            )
            helper.assertTrue(
                after == before.copy(fromSave = before.fromSave + 1, restored = before.restored + 1),
                "a restore from the save should be counted once: before $before, after $after",
            )
            be.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }.thenSucceed()
    }

    /**
     * 復元の用意が投げた台（#808）: `startRestore` の前の用意（設定・ディスク・保存元）が投げても、順番待ちのまま RUNNING と表示し続けず、
     * ERROR（UNAVAILABLE）にして順番待ちを抜ける。`PoweredOn` は残し（次の読み込みで復元し直す）、つなぎは外れ、例外は呼び手
     * （`RestoreQueue`）に返る。マシンは作らない
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun failedRestorePreparationLeavesAnErrorNotRunning(helper: GameTestHelper) {
        helper.setBlock(CASE, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get().defaultBlockState())
        val be = helper.getBlockEntity(CASE) as CaseBlockEntity
        installRequired(be)
        be.computer.loadPoweredOn(true)
        helper.assertTrue(be.computer.beginRestore(), "a powered-on case should wait for a restore")
        helper.assertTrue(
            be.computer.isRestorePending && be.computer.status == ComputerStatus.RUNNING,
            "should show RUNNING while waiting: ${be.computer}",
        )
        val boom = IllegalStateException("preparation failed (test)")
        be.restorePreparationHook = Runnable { throw boom }
        var thrown: Throwable? = null
        try {
            be.startRestore(false)
        } catch (e: IllegalStateException) {
            thrown = e
        } finally {
            be.restorePreparationHook = null
        }
        helper.assertTrue(thrown === boom, "the preparation failure should reach the caller but was $thrown")
        helper.assertTrue(!be.computer.isRestorePending, "the case should leave the restore wait")
        helper.assertTrue(
            be.computer.status == ComputerStatus.ERROR && be.computer.error == ComputerError.UNAVAILABLE,
            "the case should show ERROR/UNAVAILABLE but was ${be.computer}",
        )
        helper.assertTrue(be.computer.poweredOn, "PoweredOn should stay so the next load restores again")
        helper.assertTrue(be.computer.machine == null, "no machine should be created")
        helper.assertTrue(be.wiring.connectedMachine == null, "nothing should be wired")
        helper.setBlock(CASE, Blocks.AIR)
        helper.succeed()
    }
}
