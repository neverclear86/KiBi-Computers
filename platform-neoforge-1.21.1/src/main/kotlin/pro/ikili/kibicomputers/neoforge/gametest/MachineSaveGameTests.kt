package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ChunkHolder
import net.minecraft.server.level.TicketType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.LevelChunk
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.ClaimResult
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.core.persist.PendingSave
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines
import pro.ikili.kibicomputers.neoforge.machine.WorldMachineSaves
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.nio.file.Files
import java.util.Comparator
import java.util.OptionalLong
import java.util.UUID

/** 待機点保存（#81、ADR 0081）の GameTest: マシンの ID、世代のファイル、チャンクの印、アンロードと停止の手順 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object MachineSaveGameTests {
    private val CASE = BlockPos(1, 1, 1)
    private val OTHER = BlockPos(0, 1, 0)

    private fun store(helper: GameTestHelper): MachineSaveStore {
        val store = WorldMachineSaves.current
        helper.assertTrue(store != null, "the server should have a machine save store")
        return store!!
    }

    private fun installRequired(be: CaseBlockEntity) {
        for (kind in PartKind.entries.filter { it.required && it != PartKind.BOOT }) {
            be.parts.setStackInSlot(be.tier.firstSlot(kind), ItemStack(Items.IRON_INGOT))
        }
    }

    private fun placeRunning(helper: GameTestHelper, pos: BlockPos): CaseBlockEntity {
        helper.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        val be = helper.getBlockEntity<CaseBlockEntity>(pos)
        installRequired(be)
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        return be
    }

    /** チャンクの NBT（`block_entities` だけ）に [tags] を入れたもの */
    private fun chunkData(vararg tags: CompoundTag): CompoundTag {
        val list = ListTag()
        for (t in tags) list.add(t)
        return CompoundTag().apply { put("block_entities", list) }
    }

    /**
     * 取り出して書くと ID が振られ、世代のファイルと `owner.bin` ができる。`saveWithFullMetadata`（`/data get`・`/clone` と同じ）は
     * 印を動かさず、チャンクの保存の NBT で持ち主の位置のタグだけが印を動かす。`/data merge` で ID は変わらず、
     * 写したタグを別のケースに読ませると新しい ID になる。停止の手順はチャンクの保存の後に印が新しい世代を指す。
     * プレイヤーの破壊はディレクトリを消し、`setRemoved` だけでは消さない
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 400)
    fun identityGenerationsAndMarks(helper: GameTestHelper) {
        val store = store(helper)
        val dimension = helper.level.dimension().location().toString()
        val be = placeRunning(helper, CASE)
        // 置いたブロックエンティティの onLoad は次の tick に来る（索引に入るのはそこ）
        helper.runAfterDelay(1) { afterPlace(helper, store, dimension, be) }
    }

    private fun afterPlace(helper: GameTestHelper, store: MachineSaveStore, dimension: String, be: CaseBlockEntity) {
        helper.assertTrue(be.identity == null, "a case gets no id until the first capture")
        helper.assertTrue(be.plannedWrites == 2, "the first save also writes owner.bin")

        // アンロードの手順と同じく止めてから取り出す（止めている間は1回だけ）
        be.computer.prepareUnload()
        val writes = saveNow(be, CapturePolicy.KILL_TRANSIENT)
        helper.assertTrue(writes == 2, "the first save writes owner.bin and a generation but wrote $writes")
        val identity = be.identity!!
        val id = identity.id
        helper.assertTrue(Files.isRegularFile(store.generationFile(id, identity.generation)), "the generation file should exist")
        helper.assertTrue(Files.isRegularFile(store.directoryOf(id).resolve("owner.bin")), "owner.bin should exist")
        helper.assertTrue(saveNow(be, CapturePolicy.KILL_TRANSIENT) == 0, "a machine is captured only once while quiesced")
        be.computer.endQuiesce()

        // /data get block・/clone・ピックブロックと同じ保存は印を動かさない
        val tag = be.saveWithFullMetadata(helper.level.registryAccess())
        helper.assertTrue(tag.getString(CaseBlockEntity.MACHINE_ID_TAG) == id.toString(), "the tag should carry the id: $tag")
        helper.assertTrue(!store.chunkGeneration(id).isPresent, "saving the block entity alone should not mark the chunk")

        // 持ち主でない位置のタグは無視し、持ち主の位置のタグで印が動く
        val moved = tag.copy().apply { putInt("x", getInt("x") + 7) }
        helper.assertTrue(ServerMachines.markChunkGenerations(dimension, chunkData(moved)) == 0, "a tag at another position should be ignored")
        helper.assertTrue(!store.chunkGeneration(id).isPresent, "the mark should not move for a non-owner tag")
        helper.assertTrue(ServerMachines.markChunkGenerations(dimension, chunkData(tag)) == 1, "the owner's tag should mark")
        helper.assertTrue(store.chunkGeneration(id).asLong == identity.generation, "the mark should point at the saved generation")

        // /data merge で ID を書き換えても、確定した ID は変わらない
        val merged = tag.copy().apply { putString(CaseBlockEntity.MACHINE_ID_TAG, UUID.randomUUID().toString()) }
        be.loadWithComponents(merged, helper.level.registryAccess())
        helper.assertTrue(be.identity?.id == id, "a loaded case should keep its id on /data merge but was ${be.identity}")

        // 写したタグを別のケースに読ませると、読み込み中の重複として新しい ID になる
        helper.setBlock(OTHER, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        val copy = helper.getBlockEntity<CaseBlockEntity>(OTHER)
        copy.loadWithComponents(tag, helper.level.registryAccess())
        // copy の onLoad（持ち主を確かめる）を待つ
        helper.runAfterDelay(2) { afterLoad(helper, store, be, copy, id, identity.generation) }
    }

    private fun afterLoad(helper: GameTestHelper, store: MachineSaveStore, be: CaseBlockEntity, copy: CaseBlockEntity, id: UUID, firstGeneration: Long) {
        helper.assertTrue(copy.lastClaim == ClaimResult.NEW_ID_DUPLICATE_LOADED, "a duplicate should get a new id but was ${copy.lastClaim}")
        val copyId = copy.identity?.id
        helper.assertTrue(copyId != null && copyId != id, "the duplicate should not share the id: $copyId")
        // 見積もり: owner.bin を書いてある台は 1、claim が振った ID の owner.bin をまだ書いていない台は 2（#585）
        helper.assertTrue(be.plannedWrites == 1, "a case whose owner.bin is written plans 1 write but planned ${be.plannedWrites}")
        helper.assertTrue(copy.plannedWrites == 2, "a case with an id from the claim plans owner.bin too but planned ${copy.plannedWrites}")

        // 停止の手順: 書いて setChanged し、チャンクの保存の後に印が新しい世代を指す
        helper.assertTrue(ServerMachines.saveForStop(listOf(be), keepQuiesced = false) == 1, "the stop save should write the machine")
        val afterStop = be.identity!!
        helper.assertTrue(afterStop.generation > firstGeneration, "the stop save should write a new generation")
        helper.assertTrue(afterStop.id == id, "the stop save should keep the id but was ${afterStop.id}")
        helper.level.chunkSource.save(true)
        helper.assertTrue(
            store.chunkGeneration(id).asLong == afterStop.generation,
            "the chunk save should mark ${afterStop.generation} but was ${store.chunkGeneration(id)}",
        )

        // claim は owner.bin を書かない（新しい ID の owner.bin は最初の世代の前に書く。#585）
        helper.assertTrue(!Files.exists(store.directoryOf(copyId!!)), "the claim should not create the directory of the new id")
        // setRemoved だけ（ブロックを運ぶ MOD と同じ）ではディレクトリを消さない（書いた後の台と同じく、ディレクトリを置いて確かめる）
        Files.createDirectories(store.directoryOf(copyId))
        helper.setBlock(OTHER, Blocks.AIR)
        ServerMachines.drainSaves()
        helper.assertTrue(Files.isDirectory(store.directoryOf(copyId)), "setRemoved alone should keep the directory")
        // プレイヤーの破壊では消す
        be.pressPowerButton()
        val player = helper.makeMockPlayer(GameType.SURVIVAL)
        val abs = helper.absolutePos(CASE)
        val state = helper.level.getBlockState(abs)
        state.block.playerWillDestroy(helper.level, abs, state, player)
        // 消すのは書き込みのスレッド（#84）
        ServerMachines.drainSaves()
        helper.assertTrue(!Files.exists(store.directoryOf(id)), "a player breaking the case should delete its directory")
        helper.setBlock(CASE, Blocks.AIR)
        helper.succeed()
    }

    /**
     * 止まった台の知らせが届く前に停止の手順が走っても（#698）、電源を切った状態で NBT に載る。停止の手順では tick が回らず、
     * 外した後の知らせは捨てられるので、手順の中でケースがそろえる（結線も外す）。CRASHED の窓そのものは core の単体テストで確かめる
     * （GameTest からは本物のマシンを CRASHED にできない）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 1200)
    fun stopSaveSettlesAStoppedMachine(helper: GameTestHelper) {
        val be = placeRunning(helper, CASE)
        val machine = be.computer.machine!!
        helper.startSequence().thenWaitUntil {
            helper.assertTrue(machine.state == MachineState.RUNNING && !machine.isBooting, "the machine should boot but was ${machine.state}")
        }.thenExecute {
            machine.stop()
            helper.assertTrue(machine.awaitTermination(5_000), "the machine should stop")
            helper.assertTrue(machine.state == MachineState.STOPPED, "the machine should be STOPPED but was ${machine.state}")
            // 知らせはまだ届いていない（tick が回っていない）
            helper.assertTrue(be.computer.poweredOn, "the case should still be powered before the notification")
            val changes = be.setChangedCalls
            helper.assertTrue(ServerMachines.saveForStop(listOf(be), keepQuiesced = false) == 0, "a stopped machine should not be captured")
            helper.assertTrue(be.setChangedCalls > changes, "the stop save should call setChanged so that saveAllChunks writes the NBT")
            helper.assertTrue(!be.computer.poweredOn, "the stop save should power the case off")
            helper.assertTrue(be.computer.machine == null, "the stop save should release the machine")
            helper.assertTrue(be.computer.status == ComputerStatus.OFF, "the case should show OFF but was ${be.computer}")
            helper.assertTrue(be.wiring.connectedMachine == null, "the stop save should disconnect the wiring")
            val tag = be.saveWithoutMetadata(helper.level.registryAccess())
            helper.assertTrue(!tag.getBoolean(CaseBlockEntity.POWERED_ON_TAG), "the NBT should say powered off: $tag")
        }.thenExecuteAfter(3) {
            helper.assertTrue(be.computer.status == ComputerStatus.OFF && !be.computer.poweredOn, "a late notification should change nothing")
            helper.setBlock(CASE, Blocks.AIR)
        }.thenSucceed()
    }

    /**
     * 止まった台の知らせが届く前にアンロード・FULL でなくなった経路（[ServerMachines.saveForUnload]）が走っても（#698）、
     * 電源を切った状態で NBT に載る。アンロード中は `setChanged` を呼べない（隣のブロックがチャンクを読み直しうる）ので、
     * チャンクに `isUnsaved` を付けて書かせる。この1行が抜けると、`PoweredOn=false` がディスクに行かない
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 1200)
    fun unloadSaveSettlesAStoppedMachine(helper: GameTestHelper) {
        val be = placeRunning(helper, CASE)
        val machine = be.computer.machine!!
        helper.startSequence().thenWaitUntil {
            helper.assertTrue(machine.state == MachineState.RUNNING && !machine.isBooting, "the machine should boot but was ${machine.state}")
        }.thenExecute {
            machine.stop()
            helper.assertTrue(machine.awaitTermination(5_000), "the machine should stop")
            helper.assertTrue(machine.state == MachineState.STOPPED, "the machine should be STOPPED but was ${machine.state}")
            // 知らせはまだ届いていない（tick が回っていない）
            helper.assertTrue(be.computer.poweredOn, "the case should still be powered before the notification")
            val chunk = helper.level.getChunkAt(helper.absolutePos(CASE))
            chunk.isUnsaved = false
            val changes = be.setChangedCalls
            ServerMachines.saveForUnload(chunk, listOf(be), adopt = true)
            helper.assertTrue(be.setChangedCalls == changes, "the unload save should not call setChanged (it can reload the chunk through neighbors)")
            helper.assertTrue(!be.computer.poweredOn, "the unload save should power the case off")
            helper.assertTrue(be.computer.machine == null, "the unload save should release the machine")
            helper.assertTrue(be.computer.status == ComputerStatus.OFF, "the case should show OFF but was ${be.computer}")
            helper.assertTrue(be.wiring.connectedMachine == null, "the unload save should disconnect the wiring")
            helper.assertTrue(chunk.isUnsaved, "the chunk should be marked unsaved so that PoweredOn=false reaches the disk")
            val tag = be.saveWithoutMetadata(helper.level.registryAccess())
            helper.assertTrue(!tag.getBoolean(CaseBlockEntity.POWERED_ON_TAG), "the NBT should say powered off: $tag")
        }.thenExecuteAfter(3) {
            helper.assertTrue(be.computer.status == ComputerStatus.OFF && !be.computer.poweredOn, "a late notification should change nothing")
            helper.setBlock(CASE, Blocks.AIR)
        }.thenSucceed()
    }

    /**
     * 取り出しの後に [be] のマシンを止める（ゲストが止めたのと同じ `stop()`。STOPPED まで待つ）フックを付けて [body] を回す（#698）。
     * 止めた後に [afterStop] を呼ぶ。取り出す前のそろえでは動いていて、写した後のそろえだけが止まった台を見る。
     * フックは [be] にだけ付く（[ServerMachines.afterCaptureForTest] はケースごと。#713）ので、既定のバッチでほかのテストと並べてよい
     */
    private fun stoppingAfterCapture(helper: GameTestHelper, be: CaseBlockEntity, afterStop: () -> Unit = {}, body: () -> Unit): Boolean {
        var stopped = false
        ServerMachines.afterCaptureForTest[be] = {
            if (!stopped) {
                // フックの中では投げない（保存の経路の途中なので）。確かめるのは後
                val machine = be.computer.machine
                stopped = machine != null && be.computer.poweredOn
                machine?.stop()
                if (machine?.awaitTermination(5_000) != true) stopped = false
                afterStop()
            }
        }
        try {
            body()
        } finally {
            ServerMachines.afterCaptureForTest.remove(be)
        }
        return stopped
    }

    /**
     * 停止の手順の、写した後のそろえ（#698）: 取り出した後・知らせの前にゲストが止めた台も、電源を切った状態で NBT に載る
     * （写した世代は残るが、電源が切れているので戻されない）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 1200)
    fun stopSaveSettlesAMachineStoppedAfterCapture(helper: GameTestHelper) {
        val be = placeRunning(helper, CASE)
        val machine = be.computer.machine!!
        helper.startSequence().thenWaitUntil {
            helper.assertTrue(machine.state == MachineState.RUNNING && !machine.isBooting, "the machine should boot but was ${machine.state}")
        }.thenExecute {
            var saved = 0
            val stopped = stoppingAfterCapture(helper, be) { saved = ServerMachines.saveForStop(listOf(be), keepQuiesced = false) }
            helper.assertTrue(stopped, "the hook should stop a powered machine after the capture")
            helper.assertTrue(saved == 1, "the running machine should be captured before it stops but saved $saved")
            helper.assertTrue(!be.computer.poweredOn, "the settle after the capture should power the case off")
            helper.assertTrue(be.computer.machine == null, "the settle after the capture should release the machine")
            helper.assertTrue(be.computer.status == ComputerStatus.OFF, "the case should show OFF but was ${be.computer}")
            helper.assertTrue(be.wiring.connectedMachine == null, "the settle after the capture should disconnect the wiring")
            val tag = be.saveWithoutMetadata(helper.level.registryAccess())
            helper.assertTrue(!tag.getBoolean(CaseBlockEntity.POWERED_ON_TAG), "the NBT should say powered off: $tag")
        }.thenExecuteAfter(3) {
            helper.assertTrue(be.computer.status == ComputerStatus.OFF && !be.computer.poweredOn, "a late notification should change nothing")
            helper.setBlock(CASE, Blocks.AIR)
        }.thenSucceed()
    }

    /**
     * FULL でなくなった経路（`adopt = false`）の、写した後のそろえ（#698）: 取り出した後にゲストが止めた台も電源を切り、
     * `setChanged` を呼ばずに（[CaseBlockEntity.settleStopped] の `quiet`）チャンクに `isUnsaved` を付ける。
     * `adopt = false` では予約の世代を NBT に載せない（印を付けない）ので、印はそろえの1行だけが付ける
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 1200)
    fun unloadSaveSettlesAMachineStoppedAfterCapture(helper: GameTestHelper) {
        val be = placeRunning(helper, CASE)
        val machine = be.computer.machine!!
        helper.startSequence().thenWaitUntil {
            helper.assertTrue(machine.state == MachineState.RUNNING && !machine.isBooting, "the machine should boot but was ${machine.state}")
        }.thenExecute {
            val chunk = helper.level.getChunkAt(helper.absolutePos(CASE))
            chunk.isUnsaved = false
            var changes = -1
            var holder: ChunkHolder? = null
            val stopped = stoppingAfterCapture(helper, be, afterStop = {
                // 取り出しの間に付いた印（とバニラの保存が下ろしたフラグ）を外し、そろえが付けるかを見る。
                // フックの中なので投げない（lowerHolderFlag は使わない）。holder がそのチャンクを持つかは後で確かめる
                chunk.isUnsaved = false
                holder = helper.level.chunkSource.chunkMap.getVisibleChunkIfPresent(chunk.pos.toLong())
                holder?.wasAccessibleSinceLastSave = false
                changes = be.setChangedCalls
            }) {
                ServerMachines.saveForUnload(chunk, listOf(be), adopt = false)
            }
            helper.assertTrue(stopped, "the hook should stop a powered machine after the capture")
            helper.assertTrue(be.pendingSave != null, "the running machine should be captured before it stops")
            helper.assertTrue(!be.computer.poweredOn, "the settle after the capture should power the case off")
            helper.assertTrue(be.computer.machine == null, "the settle after the capture should release the machine")
            helper.assertTrue(be.computer.status == ComputerStatus.OFF, "the case should show OFF but was ${be.computer}")
            helper.assertTrue(be.setChangedCalls == changes, "the settle should not call setChanged on the unload path")
            helper.assertTrue(chunk.isUnsaved, "the settle after the capture should mark the chunk unsaved")
            helper.assertTrue(holder != null && holder!!.latestChunk === chunk, "the chunk should be the latest chunk of its holder")
            helper.assertTrue(holder!!.wasAccessibleSinceLastSave(), "the settle after the capture should raise the holder flag so that the vanilla save writes the chunk")
            val tag = be.saveWithoutMetadata(helper.level.registryAccess())
            helper.assertTrue(!tag.getBoolean(CaseBlockEntity.POWERED_ON_TAG), "the NBT should say powered off: $tag")
        }.thenExecuteAfter(3) {
            helper.assertTrue(be.computer.status == ComputerStatus.OFF && !be.computer.poweredOn, "a late notification should change nothing")
            ServerMachines.drainSaves()
            helper.setBlock(CASE, Blocks.AIR)
        }.thenSucceed()
    }

    /**
     * 本物の FULL でなくなった経路（`saveForDemotion` → `saveForUnload(adopt = false)` → `chunkDemoted`。#545・#698）:
     * 取り出した後・知らせの前にゲストが止めた台は、外された後も電源が切れている（`chunkDemoted` だけでは電源が入ったまま残り、
     * FULL に戻ると復元の順番待ちに入る）。FULL に戻しても起動し直さない
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 1200)
    fun demotedChunkSettlesAMachineStoppedAfterCapture(helper: GameTestHelper) {
        val level = helper.level
        // だれも読み込んでいない離れたチャンク（#747）
        val slot = FarChunks.allocate(helper, keeper = true)
        val chunk = slot.chunk
        val pos = slot.pos(7, 200, 3)
        level.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get().defaultBlockState(), 3)
        val be = level.getBlockEntity(pos) as CaseBlockEntity
        installRequired(be)
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        val machine = be.computer.machine!!
        var stopped = false
        fun cleanUp() {
            ServerMachines.afterCaptureForTest.remove(be)
            slot.release()
        }
        helper.startSequence().thenWaitUntil {
            helper.assertTrue(be in ServerMachines.trackedCases(), "the case should be tracked after onLoad")
            helper.assertTrue(machine.state == MachineState.RUNNING && !machine.isBooting, "the machine should boot but was ${machine.state}")
        }.thenExecute {
            // フックは be にだけ付く（ケースごと。#713）ので、tick をまたいで付けたままでも、並行するテストの保存では呼ばれず、上書きもされない
            ServerMachines.afterCaptureForTest[be] = {
                // フックは tick の終わりの保存の経路の中で呼ばれるので、投げない。確かめるのは後
                if (!stopped) {
                    val powered = be.computer.poweredOn && be.computer.machine === machine
                    machine.stop()
                    stopped = machine.awaitTermination(5_000) && powered
                }
            }
            // 置いたときの印を外し、そろえ（FULL でなくなった経路では setChanged が届かない）が付けるかを見る
            level.getChunk(chunk.x, chunk.z).isUnsaved = false
            slot.setFull(false)
        }.thenWaitUntil {
            helper.assertTrue(be.demotedChunk != null, "the chunk should be demoted")
            helper.assertTrue(be.computer.machine == null, "the machine should be released but was ${be.computer}")
            // 印は、外すのと同じ呼び出しの中（saveForUnload の写した後のそろえ）で付く。ただし、続く tick のワールドの tick で
            // バニラの急ぎの保存（ChunkMap.processUnloads → saveChunkIfNeeded）が書いて消すことがある（#722）。そのときは
            // 電源の切れた NBT がもうディスク（書き込み待ちを含む）に行っているので、どちらかなら通す
            helper.assertTrue(
                demotedChunkSettled(helper, be, chunk, pos),
                "the demoted chunk should be marked unsaved or saved powered off (waiting)",
            )
        }.thenExecute {
            ServerMachines.afterCaptureForTest.remove(be)
            try {
                helper.assertTrue(stopped, "the hook should stop a powered machine after the demotion capture")
                helper.assertTrue(!be.isRemoved, "the chunk should stay in memory")
                helper.assertTrue(!be.computer.poweredOn, "the settle after the demotion capture should power the case off")
                helper.assertTrue(be.computer.status == ComputerStatus.OFF, "the case should show OFF but was ${be.computer}")
                helper.assertTrue(
                    demotedChunkSettled(helper, be, chunk, pos),
                    "the demoted chunk should still be marked unsaved or saved powered off",
                )
                val tag = be.saveWithoutMetadata(level.registryAccess())
                helper.assertTrue(!tag.getBoolean(CaseBlockEntity.POWERED_ON_TAG), "the NBT should say powered off: $tag")
                // FULL に戻しても、電源が切れているので復元の順番待ちに入らない
                slot.setFull(true)
            } catch (t: Throwable) {
                cleanUp()
                throw t
            }
        }.thenExecuteAfter(5) {
            try {
                helper.assertTrue(level.getBlockEntity(pos) === be, "the block entity should stay in memory")
                helper.assertTrue(!be.computer.isRestorePending && be.computer.machine == null, "the case should not restore but was ${be.computer}")
                helper.assertTrue(!be.computer.poweredOn && be.computer.status == ComputerStatus.OFF, "the case should stay off but was ${be.computer}")
                ServerMachines.drainSaves()
                // 部品の落とし物をチャンクに残さない（#746）
                removeCaseWithoutDrops(level, pos)
            } finally {
                cleanUp()
            }
        }.thenSucceed()
    }

    /**
     * 本物のアンロードの経路（`ChunkEvent.Unload` → `ChunkMap.save`）: 強制読み込みを外したチャンクのケースは、
     * アンロードで取り出して書かれ、ディスクに行ったチャンクの NBT が書いた世代を指し、印も同じ世代になる
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 2400)
    fun chunkUnloadSavesTheMachine(helper: GameTestHelper) {
        val store = store(helper)
        val level = helper.level
        // だれも読み込んでいない離れたチャンク（#747）
        val slot = FarChunks.allocate(helper)
        val chunk = slot.chunk
        val pos = slot.pos(3, 200, 5)
        level.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get().defaultBlockState(), 3)
        val be = level.getBlockEntity(pos) as CaseBlockEntity
        installRequired(be)
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        slot.setFull(false)
        helper.succeedWhen {
            helper.assertTrue(!level.chunkSource.hasChunk(chunk.x, chunk.z), "the chunk should unload")
            // 共有のカウンター（ServerMachines.Stats.unloadCaptures）は並行するほかのテストのチャンクでも動くので見ない（#548・#708）。
            // 取り出されたことは、このケースの ID がディスク上のチャンクに載り、その世代のファイルと印がそろうことで確かめる
            // （電源を入れただけでは ID は振られない）
            val data = level.chunkSource.chunkMap.read(chunk).join().orElse(null)
            helper.assertTrue(data != null, "the chunk should be on disk")
            val tag = data!!.getList("block_entities", Tag.TAG_COMPOUND.toInt()).map { it as CompoundTag }
                .firstOrNull { it.getInt("x") == pos.x && it.getInt("y") == pos.y && it.getInt("z") == pos.z }
            helper.assertTrue(tag != null && tag.contains(CaseBlockEntity.MACHINE_ID_TAG), "the saved chunk should have the case with an id: $tag")
            val id = UUID.fromString(tag!!.getString(CaseBlockEntity.MACHINE_ID_TAG))
            val generation = tag.getLong(CaseBlockEntity.SAVE_GENERATION_TAG)
            // NBT は書き終わりを待たずに予約の世代を指す（#84 の規則 4）。書き終わった完了を受け取るまで待つ
            helper.assertTrue(store.latestGeneration(id) == OptionalLong.of(generation), "the chunk should point at the latest generation")
            helper.assertTrue(Files.isRegularFile(store.generationFile(id, generation)), "the generation file should exist")
            helper.assertTrue(store.chunkGeneration(id) == OptionalLong.of(generation), "the mark should follow the chunk save")
        }
    }

    /**
     * FULL でなくなったがメモリに残るチャンク（#545）: 5 チャンク離れたチャンクを強制読み込みにしたまま（ケースのチャンクの
     * チケットの段階は 36。FULL でなく、アンロードもされない）、ケースのチャンクの強制読み込みを外すと、マシンが取り出されて書かれ、
     * 外される。チャンクはアンロードされず、ブロックエンティティは残る。FULL に戻すと、同じブロックエンティティが ID を保ったまま、
     * 電源が切れている（アンロードの後に読み込み直したのと同じ）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 1200)
    fun demotedChunkSavesAndStopsTheMachine(helper: GameTestHelper) {
        val store = store(helper)
        val level = helper.level
        // だれも読み込んでいない離れたチャンク（#747）
        val slot = FarChunks.allocate(helper, keeper = true)
        val chunk = slot.chunk
        val pos = slot.pos(6, 200, 9)
        level.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get().defaultBlockState(), 3)
        val be = level.getBlockEntity(pos) as CaseBlockEntity
        installRequired(be)
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        // 共有のカウンター（ServerMachines.Stats）は、同じバッチで並んで動くほかのテストのチャンクでも動くので見ない（#548）。
        // このテストのブロックエンティティだけを見る
        // onLoad（追跡と索引）を待ってから外す
        helper.runAfterDelay(1) {
            helper.assertTrue(be in ServerMachines.trackedCases(), "the case should be tracked after onLoad")
            slot.setFull(false)
            var id: UUID? = null
            helper.startSequence().thenWaitUntil {
                // アンロードされていない: アンロードなら setRemoved され、追跡からも外れる。FULL でなくなっただけなら両方そのまま
                helper.assertTrue(!be.isRemoved, "the chunk should stay in memory (the block entity was removed)")
                helper.assertTrue(be in ServerMachines.trackedCases(), "the demoted case should stay tracked")
                // 外された: マシンがなく、電源が切れている（取り出して書いた世代は下で確かめる）
                helper.assertTrue(be.computer.machine == null, "the machine should be stopped but was ${be.computer}")
                helper.assertTrue(be.computer.status == ComputerStatus.OFF, "the case should be off but was ${be.computer}")
                helper.assertTrue(!level.isLoaded(pos), "the chunk should no longer be FULL")
                val identity = be.identity
                helper.assertTrue(identity != null, "the demotion should assign an id")
                helper.assertTrue(Files.isRegularFile(store.generationFile(identity!!.id, identity.generation)), "the generation file should exist")
                helper.assertTrue(store.latestGeneration(identity.id).asLong == identity.generation, "the case should point at the latest generation")
                id = identity.id
            }.thenExecute { afterDemotion(helper, slot, pos, be, id!!) }.thenSucceed()
        }
    }

    private fun afterDemotion(helper: GameTestHelper, slot: FarSlot, pos: BlockPos, be: CaseBlockEntity, id: UUID) {
        val level = helper.level
        // FULL に戻す: 同じブロックエンティティが ID を保ち、電源は切れている。電源はまた入る
        slot.setFull(true)
        helper.assertTrue(level.getBlockEntity(pos) === be, "the block entity should stay in memory")
        helper.assertTrue(be.computer.status == ComputerStatus.OFF, "the case should stay off after the chunk is FULL again")
        helper.assertTrue(be.identity?.id == id, "the case should keep its id but was ${be.identity}")
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "the case should run again but was ${be.computer}")
        be.pressPowerButton()
        // 部品の落とし物をチャンクに残さない（#746）
        removeCaseWithoutDrops(level, pos)
        slot.release()
    }

    /**
     * ADR 0084 の規則 5（#567）: FULL でなくなったチャンクには `setChanged` が届かないので、書き終わった完了を受け取ったときに
     * そのチャンクへ unsaved を付ける。前の世代をディスクのチャンクに置いてから、書き込みのスレッドを止めたまま FULL でなくし、
     * 完了を受け取る前は付かず（世代も進まない）、受け取った後に付くことを確かめる。そのままアンロードすると、
     * ディスクのチャンクの NBT と印が新しい世代を指す（付け忘れると前の世代のまま）。
     * 書き込みのスレッドを tick をまたいで止めるので、ほかのテストと同じバッチにしない
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 1200, batch = "demoted_chunk_unsaved")
    fun demotedChunkIsMarkedUnsavedOnCompletion(helper: GameTestHelper) {
        val store = store(helper)
        val level = helper.level
        // だれも読み込んでいない離れたチャンク（#747）
        val slot = FarChunks.allocate(helper, keeper = true)
        val chunk = slot.chunk
        val pos = slot.pos(4, 200, 11)
        level.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get().defaultBlockState(), 3)
        val be = level.getBlockEntity(pos) as CaseBlockEntity
        installRequired(be)
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        // onLoad（追跡と索引）を待ってから、前の世代を書いてディスクのチャンクに載せる
        helper.runAfterDelay(1) {
            be.computer.prepareUnload()
            helper.assertTrue(saveNow(be, CapturePolicy.KILL_TRANSIENT) > 0, "the case should be saved")
            be.computer.endQuiesce()
            val first = be.identity!!
            level.chunkSource.save(true)
            helper.assertTrue(diskGeneration(helper, chunk, pos) == first.generation, "the chunk on disk should point at ${first.generation}")

            // FULL でなくすと、取り出した書き込みが書き込みのスレッドで止まる（受け取るまで tick をまたぐ）
            val gate = WriterGate()
            slot.setFull(false)
            var pending: PendingSave? = null
            helper.startSequence().thenWaitUntil {
                helper.assertTrue(be.demotedChunk != null, "the chunk should be demoted")
                helper.assertTrue(be.computer.machine == null, "the machine should be stopped but was ${be.computer}")
            }.thenExecute {
                val demoted = be.demotedChunk!!
                try {
                    helper.assertTrue(!level.isLoaded(pos), "the chunk should no longer be FULL")
                    helper.assertTrue(!be.isRemoved, "the chunk should stay in memory")
                    pending = be.pendingSave
                    helper.assertTrue(pending != null, "the demotion should leave a pending save")
                    helper.assertTrue(be.identity == first, "the identity should not advance before the write but was ${be.identity}")
                    // 取り出す前に付いた印（setBlock など）を外し、完了を受け取る前は付かないことを見る
                    demoted.isUnsaved = false
                    ServerMachines.processCompletions()
                    helper.assertTrue(!demoted.isUnsaved, "the chunk should not be unsaved before the completion")
                    helper.assertTrue(be.identity == first, "nothing should complete while the writer is held")
                } finally {
                    gate.close()
                }
                ServerMachines.drainSaves()
                helper.assertTrue(be.identity == pending!!.identity, "the completion should advance to ${pending!!.identity} but was ${be.identity}")
                helper.assertTrue(demoted.isUnsaved, "the completion should mark the demoted chunk unsaved")
                // そのままアンロードする（ケースはマシンを持たないので、アンロードの手順は何もしない）
                slot.releaseKeeper()
            }.thenWaitUntil {
                helper.assertTrue(!level.chunkSource.hasChunk(chunk.x, chunk.z), "the chunk should unload")
                val identity = pending!!.identity
                val generation = diskGeneration(helper, chunk, pos)
                helper.assertTrue(generation == identity.generation, "the chunk on disk should point at ${identity.generation} but was $generation")
                helper.assertTrue(store.chunkGeneration(identity.id) == OptionalLong.of(identity.generation), "the mark should follow the chunk save")
            }.thenSucceed()
        }
    }

    /**
     * #761: FULL でないままメモリに残るチャンクに完了で付けた印が、アンロードを待たずにバニラの保存でディスクに行く。
     * バニラの保存（`saveChunkIfNeeded`・`saveAllChunks`）は `ChunkHolder.wasAccessibleSinceLastSave` が立った holder しか書かず、
     * FULL でない holder のフラグは一度保存を通ると偽になる。書き込みのスレッドを止めたまま FULL でなくし、保存を1回通して
     * フラグを下ろしてから完了を受け取る。受け取った後はフラグが立ち直り、チャンクがメモリに残ったままの `save(true)` で、
     * ディスクのチャンクの NBT と印が新しい世代を指す（立て直さないと前の世代のまま）。
     * 書き込みのスレッドを tick をまたいで止めるので、ほかのテストと同じバッチにしない
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 1200, batch = "demoted_chunk_holder_flag")
    fun demotedChunkCompletionReachesDiskWhileInMemory(helper: GameTestHelper) {
        val store = store(helper)
        val level = helper.level
        // だれも読み込んでいない離れたチャンク（#747）
        val slot = FarChunks.allocate(helper, keeper = true)
        val chunk = slot.chunk
        val pos = slot.pos(6, 200, 9)
        level.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get().defaultBlockState(), 3)
        val be = level.getBlockEntity(pos) as CaseBlockEntity
        installRequired(be)
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        // onLoad（追跡と索引）を待ってから、前の世代を書いてディスクのチャンクに載せる
        helper.runAfterDelay(1) {
            be.computer.prepareUnload()
            helper.assertTrue(saveNow(be, CapturePolicy.KILL_TRANSIENT) > 0, "the case should be saved")
            be.computer.endQuiesce()
            val first = be.identity!!
            level.chunkSource.save(true)
            helper.assertTrue(diskGeneration(helper, chunk, pos) == first.generation, "the chunk on disk should point at ${first.generation}")

            val gate = WriterGate()
            slot.setFull(false)
            var pending: PendingSave? = null
            helper.startSequence().thenWaitUntil {
                helper.assertTrue(be.demotedChunk != null, "the chunk should be demoted")
                helper.assertTrue(be.computer.machine == null, "the machine should be stopped but was ${be.computer}")
            }.thenExecute {
                try {
                    val demoted = be.demotedChunk!!
                    helper.assertTrue(!level.isLoaded(pos), "the chunk should no longer be FULL")
                    pending = be.pendingSave
                    helper.assertTrue(pending != null, "the demotion should leave a pending save")
                    val holder = level.chunkSource.chunkMap.getVisibleChunkIfPresent(chunk.toLong())
                    helper.assertTrue(holder != null && holder.latestChunk === demoted, "the demoted chunk should stay in its holder")
                    // バニラの保存を1回通すと、FULL でない holder のフラグが下りる（以後、バニラの保存はこの holder を書かない）
                    level.chunkSource.save(true)
                    helper.assertTrue(!holder!!.wasAccessibleSinceLastSave(), "a save should clear the flag of a holder below FULL")
                    helper.assertTrue(be.identity == first, "nothing should complete while the writer is held")
                } finally {
                    gate.close()
                }
                ServerMachines.drainSaves()
                val identity = pending!!.identity
                helper.assertTrue(be.identity == identity, "the completion should advance to $identity but was ${be.identity}")
                val holder = level.chunkSource.chunkMap.getVisibleChunkIfPresent(chunk.toLong())!!
                helper.assertTrue(holder.wasAccessibleSinceLastSave(), "the completion should raise the flag of the demoted holder")
                // チャンクはメモリに残ったまま、バニラの保存が新しい世代を書く
                level.chunkSource.save(true)
                helper.assertTrue(!be.isRemoved, "the chunk should stay in memory")
                val generation = diskGeneration(helper, chunk, pos)
                helper.assertTrue(generation == identity.generation, "the chunk on disk should point at ${identity.generation} but was $generation")
                helper.assertTrue(store.chunkGeneration(identity.id) == OptionalLong.of(identity.generation), "the mark should follow the chunk save")
                helper.assertTrue(!holder.wasAccessibleSinceLastSave(), "the save should clear the flag again")
                slot.releaseKeeper()
            }.thenSucceed()
        }
    }

    /**
     * 停止の手順（`saveForStop`）で、FULL でなくなったばかりのチャンク（1人プレイの「保存してタイトルに戻る」で、プレイヤーの
     * チケットが先に外れたとき。tick の終わりの `saveForDemotion` はまだ回っていない）のケースも、書けた世代でチャンクに未保存の印が付く
     * （#653。`setChanged` は FULL でないチャンクに届かないので呼ばず、`demotedChunk` もまだない）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 1200)
    fun stopMarksAChunkThatJustLeftFull(helper: GameTestHelper) {
        stopOnChunkThatJustLeftFull(helper, stopFirst = false) { be, latest, changes ->
            helper.assertTrue(ServerMachines.saveForStop(listOf(be), keepQuiesced = false) == 1, "the stop should save the case")
            val identity = be.identity
            helper.assertTrue(identity != null, "the stop should adopt the written generation")
            helper.assertTrue(latest.isUnsaved, "the stop should mark the chunk that left FULL unsaved")
            helper.assertTrue(be.setChangedCalls == changes, "the stop should not call setChanged on a chunk that is not FULL")
            val tag = be.saveWithoutMetadata(helper.level.registryAccess())
            helper.assertTrue(
                tag.getString(CaseBlockEntity.MACHINE_ID_TAG) == identity!!.id.toString() &&
                    tag.getLong(CaseBlockEntity.SAVE_GENERATION_TAG) == identity.generation,
                "the NBT should point at $identity: $tag",
            )
            // 次の tick の終わりに、FULL でなくなった経路がマシンを外す
            be.demotedChunk != null && be.computer.machine == null
        }
    }

    /**
     * 停止の手順（`saveForStop`）で、FULL でなくなったばかりのチャンクの止まった台（ゲストが止めた。知らせはまだ届いていない）は、
     * `setChanged` を呼ばずに（`quiet` でそろえて）電源を切り、チャンクに直接未保存の印を付ける（#653・#698）。取り出しはしない
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 1200)
    fun stopMarksAChunkThatJustLeftFullForAStoppedMachine(helper: GameTestHelper) {
        stopOnChunkThatJustLeftFull(helper, stopFirst = true) { be, latest, changes ->
            helper.assertTrue(be.computer.poweredOn, "the case should still be powered before the notification")
            helper.assertTrue(ServerMachines.saveForStop(listOf(be), keepQuiesced = false) == 0, "a stopped machine should not be captured")
            helper.assertTrue(be.setChangedCalls == changes, "the stop should not call setChanged on a chunk that is not FULL")
            helper.assertTrue(!be.computer.poweredOn && be.computer.machine == null, "the stop should power the case off but was ${be.computer}")
            helper.assertTrue(latest.isUnsaved, "the stop should mark the chunk that left FULL unsaved so that PoweredOn=false reaches the disk")
            helper.assertTrue(be !in ServerMachines.unmarkedCases, "a visible chunk should be marked at once")
            val tag = be.saveWithoutMetadata(helper.level.registryAccess())
            helper.assertTrue(!tag.getBoolean(CaseBlockEntity.POWERED_ON_TAG), "the NBT should say powered off: $tag")
            true
        }
    }

    /**
     * 離れたチャンクに動いている台を置き、強制読み込みを外して FULL でなくなったところ（tick の終わりの `saveForDemotion` の前）で
     * [check] を呼ぶ（チャンクの印は外してから渡す。3つ目の引数は、その時点の `setChangedCalls`）。[check] が真を返すまで待ってから片付ける。
     * [stopFirst] なら、FULL でなくす前にマシンを止める（ゲストが止めたのと同じ `stop()`。tick が回らないので知らせは届かない）。
     * [powered] が偽なら、電源を入れずに置く（[stopFirst] は見ない）。
     * チャンクは [FarChunks] で割り当てる（#747）
     */
    private fun stopOnChunkThatJustLeftFull(
        helper: GameTestHelper,
        stopFirst: Boolean,
        powered: Boolean = true,
        check: (CaseBlockEntity, LevelChunk, Int) -> Boolean,
    ) {
        val level = helper.level
        // だれも読み込んでいない離れたチャンク（#747）
        val slot = FarChunks.allocate(helper, keeper = true)
        val chunk = slot.chunk
        val pos = slot.pos(9, 200, 6)
        level.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get().defaultBlockState(), 3)
        val be = level.getBlockEntity(pos) as CaseBlockEntity
        installRequired(be)
        if (powered) helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        val machine = be.computer.machine
        fun cleanUp() = slot.release()
        var done = false
        helper.startSequence().thenWaitUntil {
            helper.assertTrue(be in ServerMachines.trackedCases(), "the case should be tracked after onLoad")
            if (machine != null) {
                helper.assertTrue(machine.state == MachineState.RUNNING && !machine.isBooting, "the machine should boot but was ${machine.state}")
            }
        }.thenIdle(3).thenExecute {
            try {
                if (stopFirst && machine != null) {
                    machine.stop()
                    helper.assertTrue(machine.awaitTermination(5_000), "the machine should stop")
                    helper.assertTrue(machine.state == MachineState.STOPPED, "the machine should be STOPPED but was ${machine.state}")
                }
                // 置くときの getChunk の一時的なチケット（UNKNOWN。1 tick で切れる）が切れるのを待ってから、
                // チケットを外し、tick を待たずにチケットの段階を下げる（1人プレイの切断の後の pollTask と同じ）
                slot.setFull(false)
                var polls = 0
                while (level.hasChunkAt(pos) && polls++ < 10_000) level.chunkSource.pollTask()
                helper.assertTrue(
                    !level.hasChunkAt(pos),
                    "the chunk should no longer be FULL (ticket level ${level.chunkSource.chunkMap.getVisibleChunkIfPresent(chunk.toLong())?.ticketLevel})",
                )
                helper.assertTrue(be.demotedChunk == null, "the tick end should not have handled the demotion yet")
                val latest = level.chunkSource.chunkMap.getVisibleChunkIfPresent(chunk.toLong())?.latestChunk as? LevelChunk
                helper.assertTrue(latest != null && latest.blockEntities[pos] === be, "the chunk should stay in memory with the case")
                // 置いたときの印を外し、停止の手順が付けるかを見る
                latest!!.isUnsaved = false
                done = check(be, latest, be.setChangedCalls)
            } catch (t: Throwable) {
                cleanUp()
                throw t
            }
        }.thenWaitUntil {
            done = done || (be.demotedChunk != null && be.computer.machine == null)
            helper.assertTrue(done, "the demotion should release the machine but was ${be.computer}")
        }.thenExecute {
            try {
                ServerMachines.drainSaves()
                slot.setFull(true)
                // 強制読み込みを外すとチャンクごと残るので、部品を落とさずに消す（#746）
                removeCaseWithoutDrops(level, pos)
            } finally {
                cleanUp()
            }
        }.thenSucceed()
    }

    /**
     * 読み込み済みのケースに、`claim` で新しい ID を振られる NBT（検査に外れる ID）を読ませる。`onLoad` の後なのでその場で `claim` が走り、
     * NBT と違う ID になるので、印が次の tick（か停止）に予約される（[ServerMachines.markChangedLater]）
     */
    private fun claimANewId(helper: GameTestHelper, be: CaseBlockEntity) {
        helper.assertTrue(be.isLoadedOnServer && be.identity == null, "the case should be loaded without an id")
        val tag = be.saveWithoutMetadata(helper.level.registryAccess())
        tag.putString(CaseBlockEntity.MACHINE_ID_TAG, "not-a-uuid")
        be.loadWithComponents(tag, helper.level.registryAccess())
        helper.assertTrue(be.lastClaim == ClaimResult.NEW_ID_INVALID, "the case should get a new id but was ${be.lastClaim}")
        helper.assertTrue(be.identity != null, "the claim should give the case an id")
        helper.assertTrue(ServerMachines.isChangedLater(be), "the claim should reserve the mark for later")
    }

    /** NBT [tag] が、[be] の `claim` で振られた ID を指している */
    private fun assertTagHasIdentity(helper: GameTestHelper, be: CaseBlockEntity, tag: CompoundTag?) {
        val identity = be.identity!!
        helper.assertTrue(
            tag != null && tag.getString(CaseBlockEntity.MACHINE_ID_TAG) == identity.id.toString() &&
                tag.getLong(CaseBlockEntity.SAVE_GENERATION_TAG) == identity.generation &&
                tag.getLong(CaseBlockEntity.OWNER_EPOCH_TAG) == identity.epoch,
            "the NBT should point at $identity: $tag",
        )
    }

    /**
     * 停止の始め（と tick の始め）に流す `claim` の印（#762）: 電源の切れた台（停止で取り出さない）が読み込みの `claim` で新しい ID を
     * 振られ、tick が来ないうちに流すと、FULL のチャンクに未保存の印が付き、続くチャンクの保存が新しい ID をディスクに書く
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 400)
    fun flushingTheClaimMarksReachesTheDisk(helper: GameTestHelper) {
        val level = helper.level
        // ほかのテストのチャンクと重ならないように、離れたチャンクに置く（#747）
        val slot = FarChunks.allocate(helper)
        val chunk = slot.chunk
        val pos = slot.pos(4, 200, 11)
        level.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get().defaultBlockState(), 3)
        val be = level.getBlockEntity(pos) as CaseBlockEntity
        // onLoad を待つ
        helper.startSequence().thenWaitUntil {
            helper.assertTrue(be.isLoadedOnServer, "the case should run onLoad")
        }.thenExecute {
            try {
                val levelChunk = level.getChunkAt(pos)
                // 予約と流すのは同じ tick の中で行う（次の tick の始めも同じものを流すため）
                claimANewId(helper, be)
                // 読ませたときの印を外し、流す手順が付けるかを見る
                levelChunk.isUnsaved = false
                ServerMachines.flushChangedLater()
                helper.assertTrue(!ServerMachines.isChangedLater(be), "the flush should consume the reservation")
                helper.assertTrue(levelChunk.isUnsaved, "the flush should mark the FULL chunk unsaved")
                assertTagHasIdentity(helper, be, be.saveWithoutMetadata(level.registryAccess()))
                // 続くチャンクの保存（停止では saveAllChunks）が、新しい ID をディスクに書く
                level.chunkSource.save(true)
                val data = level.chunkSource.chunkMap.read(chunk).join().orElse(null)
                helper.assertTrue(data != null, "the chunk should be on disk")
                assertTagHasIdentity(helper, be, caseTagIn(data!!, pos))
                removeCaseWithoutDrops(level, pos)
            } finally {
                slot.release()
            }
        }.thenSucceed()
    }

    /**
     * FULL でなくなったチャンク（`onLoad` の後、次の tick までにチケットの段階が下がった。1人プレイの「保存してタイトルに戻る」など）の
     * 電源の切れた台の `claim` の印も、流すと `setChanged` を呼ばずに（#698）チャンクに直接付き、holder の保存のフラグも立つ（#762・#761）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 1200)
    fun flushingTheClaimMarksAChunkThatJustLeftFull(helper: GameTestHelper) {
        stopOnChunkThatJustLeftFull(helper, stopFirst = false, powered = false) { be, latest, _ ->
            claimANewId(helper, be)
            val holder = lowerHolderFlag(helper, latest)
            latest.isUnsaved = false
            val changes = be.setChangedCalls
            ServerMachines.flushChangedLater()
            helper.assertTrue(!ServerMachines.isChangedLater(be), "the flush should consume the reservation")
            helper.assertTrue(be.setChangedCalls == changes, "the flush should not call setChanged on a chunk that is not FULL")
            helper.assertTrue(latest.isUnsaved, "the flush should mark the chunk that left FULL unsaved")
            helper.assertTrue(holder.wasAccessibleSinceLastSave(), "the flush should raise the holder flag")
            assertTagHasIdentity(helper, be, be.saveWithoutMetadata(helper.level.registryAccess()))
            true
        }
    }

    /**
     * アンロード（`saveForUnload`）は、まだ流れていない `claim` の印をそのチャンクに付けて外す（#762。アンロードの `untrack` が予約を捨てるため）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun unloadMarksAChunkForAReservedClaimMark(helper: GameTestHelper) {
        val level = helper.level
        val state = KiBiBlocks.CASES.getValue(CaseTier.BASIC).get().defaultBlockState()
        // 電源の切れた、どのチャンクにも入っていないケース（取り出しの対象にならない）
        val far = FarChunks.reserve(helper).getBlockAt(0, 100, 0)
        val be = CaseBlockEntity(far, state)
        be.setLevel(level)
        ServerMachines.markChangedLater(be)
        val chunk = level.getChunkAt(helper.absolutePos(CASE))
        chunk.isUnsaved = false
        ServerMachines.saveForUnload(chunk, listOf(be), adopt = true)
        helper.assertTrue(chunk.isUnsaved, "the unload should mark the chunk for the reserved mark")
        helper.assertTrue(!ServerMachines.isChangedLater(be), "the unload should consume the reservation")
        helper.succeed()
    }

    /**
     * FULL になったチャンクの電源が入っていたケースでも、`onLoad`（ID の `claim`）の前なら、FULL に戻った経路（`restorePromoted`）は
     * 復元の順番待ちに入れない（#653。入れると `claim` の前に順番が来て、ID がないとして冷えた起動になる。順番待ちには `onLoad` が入れる）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun promotedChunkWaitsForOnLoadBeforeRestoring(helper: GameTestHelper) {
        val state = KiBiBlocks.CASES.getValue(CaseTier.BASIC).get().defaultBlockState()
        // ディスクから読み込んで FULL になったばかりのチャンクのケース（ブロックエンティティはあるが、onLoad はまだ）
        val be = CaseBlockEntity(helper.absolutePos(CASE), state)
        be.setLevel(helper.level)
        be.loadCustomOnly(CompoundTag().apply { putBoolean(CaseBlockEntity.POWERED_ON_TAG, true) }, helper.level.registryAccess())
        helper.assertTrue(be.computer.poweredOn && !be.isLoadedOnServer, "the case should be powered on and not loaded yet")
        ServerMachines.restorePromotedCase(be)
        helper.assertTrue(!be.computer.isRestorePending, "the case should wait for onLoad but was ${be.computer}")
        helper.succeed()
    }

    /**
     * [chunk] を最新に持つ `ChunkHolder` の保存のフラグ（`wasAccessibleSinceLastSave`）を下ろして返す（#761）。
     * FULL でなくなった holder のフラグは、バニラの急ぎの保存が一度試すと偽になる。その状態を作り、
     * 未保存の印を付ける経路が立て直すかを見る
     */
    private fun lowerHolderFlag(helper: GameTestHelper, chunk: LevelChunk): ChunkHolder {
        val holder = helper.level.chunkSource.chunkMap.getVisibleChunkIfPresent(chunk.pos.toLong())
        helper.assertTrue(holder != null && holder.latestChunk === chunk, "the chunk should be the latest chunk of its holder")
        holder!!.wasAccessibleSinceLastSave = false
        helper.assertTrue(!holder.wasAccessibleSinceLastSave(), "the holder flag should be lowered")
        return holder
    }

    /**
     * FULL でなくなったチャンクの止まった台（取り出す前のそろえ。`saveForUnload(adopt = false)`）は、電源を切ってチャンクに印を付けるとき、
     * holder の保存のフラグも立て直す（#761）。バニラの急ぎの保存がフラグを下ろした後でも、`PoweredOn=false` がディスクに行く
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 1200)
    fun demotionSettleOfAStoppedMachineRaisesTheHolderFlag(helper: GameTestHelper) {
        stopOnChunkThatJustLeftFull(helper, stopFirst = true) { be, latest, _ ->
            helper.assertTrue(be.computer.poweredOn, "the case should still be powered before the notification")
            val holder = lowerHolderFlag(helper, latest)
            ServerMachines.saveForUnload(latest, listOf(be), adopt = false)
            helper.assertTrue(!be.computer.poweredOn && be.computer.machine == null, "the save should power the case off but was ${be.computer}")
            helper.assertTrue(latest.isUnsaved, "the save should mark the chunk unsaved")
            helper.assertTrue(holder.wasAccessibleSinceLastSave(), "the settle before the capture should raise the holder flag")
            true
        }
    }

    /**
     * 停止の手順で取り出し済みの台（`isCaptured`）で、チャンクの NBT が古い世代を指すとき、`saveForUnload(adopt = false)` は
     * チャンクの印と holder の保存のフラグを立て直す（#761）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 1200)
    fun demotionOfACapturedMachineRaisesTheHolderFlag(helper: GameTestHelper) {
        val be = placeRunning(helper, CASE)
        val machine = be.computer.machine!!
        helper.startSequence().thenWaitUntil {
            helper.assertTrue(machine.state == MachineState.RUNNING && !machine.isBooting, "the machine should boot but was ${machine.state}")
        }.thenExecute {
            try {
                // 取り出す（止めたまま）。書けた後の印は processCompletions が付けるので、チャンクにはまだ書いていない（NBT は古い世代のまま）
                helper.assertTrue(ServerMachines.saveForStop(listOf(be), keepQuiesced = true) == 1, "the stop should save the case")
                helper.assertTrue(be.computer.isCaptured && be.computer.machine != null, "the case should be captured but was ${be.computer}")
                val chunk = helper.level.getChunkAt(helper.absolutePos(CASE))
                val id = be.identity!!
                helper.assertTrue(
                    WorldMachineSaves.current!!.chunkGeneration(id.id).let { !it.isPresent || it.asLong != id.generation },
                    "the chunk mark should not follow the written generation yet",
                )
                chunk.isUnsaved = false
                val holder = lowerHolderFlag(helper, chunk)
                ServerMachines.saveForUnload(chunk, listOf(be), adopt = false)
                helper.assertTrue(chunk.isUnsaved, "the save should mark the chunk unsaved")
                helper.assertTrue(holder.wasAccessibleSinceLastSave(), "the save should raise the holder flag for a captured machine")
            } finally {
                be.computer.endQuiesce()
            }
            helper.setBlock(CASE, Blocks.AIR)
        }.thenSucceed()
    }

    /**
     * チャンクの holder が見えないケース（アンロード待ち。`visibleChunkMap` から消えて、アンロードの仕事がまだ走っていない）は、
     * `markChunkUnsaved` では印を付けられない。覚えておいて、続くアンロード（`saveForUnload`）がそのチャンクに `isUnsaved` を付ける（#653）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun unloadMarksACaseWhoseChunkHolderWasGone(helper: GameTestHelper) {
        val level = helper.level
        val state = KiBiBlocks.CASES.getValue(CaseTier.BASIC).get().defaultBlockState()
        // だれも読み込んでいないチャンクのケース（holder が見えない。#747）
        val far = FarChunks.reserve(helper).getBlockAt(0, 100, 0)
        helper.assertTrue(level.chunkSource.chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(far)) == null, "the far chunk should have no holder")
        val be = CaseBlockEntity(far, state)
        be.setLevel(level)
        ServerMachines.markChunkUnsaved(be)
        helper.assertTrue(be in ServerMachines.unmarkedCases, "a case without a visible chunk should be remembered")
        // アンロードの手順が、そのチャンクに印を付けて忘れる
        val chunk = level.getChunkAt(helper.absolutePos(CASE))
        chunk.isUnsaved = false
        val holder = lowerHolderFlag(helper, chunk)
        ServerMachines.saveForUnload(chunk, listOf(be), adopt = false)
        helper.assertTrue(chunk.isUnsaved, "the unload should mark the chunk unsaved")
        helper.assertTrue(holder.wasAccessibleSinceLastSave(), "the unload should raise the holder flag so that the vanilla save writes the chunk")
        helper.assertTrue(be !in ServerMachines.unmarkedCases, "the unload should forget the case")
        helper.succeed()
    }

    /**
     * アンロード待ちのチャンク（`processUnloads` が holder をアンロード待ちの表に移し、`visibleChunkMap` から消えたが、アンロードの仕事が
     * まだ走っていない）のケースも、`markChunkUnsaved` がその holder を引いて、チャンクの印と holder の保存のフラグを付ける（#761）。
     * アンロードの仕事が走る前に FULL でない段階のチケットが戻ると、バニラは同じ holder を使い直し、アンロードの仕事は何もせず終わる
     * （`ChunkEvent.Unload` は来ない）。フラグが下りたままだと、メモリに残ったままの `save(true)` がこのチャンクを書かない。
     * 時間が残らない tick を作るため、`processUnloads` には「時間がない」を渡し（アンロードの仕事は列に積まれたまま走らない）、
     * チケットの更新は `runDistanceManagerUpdates` で進める（どちらも AT で呼べるようにした）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 400)
    fun pendingUnloadChunkRevivedBelowFullReachesDisk(helper: GameTestHelper) {
        val level = helper.level
        val cache = level.chunkSource
        val map = cache.chunkMap
        // だれも読み込んでいない離れたチャンク（#747）
        val slot = FarChunks.allocate(helper)
        val chunkPos = slot.chunk
        val key = chunkPos.toLong()
        val pos = slot.pos(3, 200, 5)
        val chunk = level.getChunk(chunkPos.x, chunkPos.z)
        level.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get().defaultBlockState(), 3)
        val be = level.getBlockEntity(pos) as CaseBlockEntity
        // 置いたケースをディスクに載せておく（後で印が書かれたかを、チャンクの印が消えたかで見る）
        cache.save(true)
        // getChunk が付けた一時のチケット（UNKNOWN）が次の tick で外れるのを待ってから、強制読み込みを外す
        helper.runAfterDelay(2) {
            val holder = map.getVisibleChunkIfPresent(key)
            helper.assertTrue(holder != null && holder.latestChunk === chunk, "the chunk should be the latest chunk of its holder")
            var revived = false
            try {
                // チケットを外し、アンロード待ちにする（時間がないので、アンロードの仕事は列に積まれたまま走らない）。
                // この呼び出しの中では tick が回らないので、アンロードの仕事は走らない
                slot.setFull(false)
                cache.runDistanceManagerUpdates()
                for (attempt in 0 until 20) {
                    if (map.pendingUnloads.containsKey(key)) break
                    map.processUnloads { false }
                }
                cache.runDistanceManagerUpdates()
                helper.assertTrue(
                    map.pendingUnloads.get(key) === holder,
                    "the holder should wait for its unload (ticket level ${holder!!.ticketLevel}, generation refs ${holder.generationRefCount})",
                )
                helper.assertTrue(map.getVisibleChunkIfPresent(key) == null, "the holder should no longer be visible")
                helper.assertTrue(!be.isRemoved, "the block entity should stay while the unload waits")
                // バニラの保存が一度試してフラグを下ろし、印も書いた後の状態
                chunk.isUnsaved = false
                holder.wasAccessibleSinceLastSave = false
                ServerMachines.markChunkUnsaved(be)
                helper.assertTrue(chunk.isUnsaved, "the chunk waiting for its unload should be marked unsaved")
                helper.assertTrue(holder.wasAccessibleSinceLastSave(), "the holder waiting for its unload should get the save flag")
                helper.assertTrue(be !in ServerMachines.unmarkedCases, "a case whose holder waits for its unload should be marked at once")
                // アンロードの仕事が走る前に、FULL でない段階（34）のチケットで戻す。同じ holder が使い直される
                cache.addRegionTicket(REVIVE_TICKET, chunkPos, -1, chunkPos)
                revived = true
                cache.runDistanceManagerUpdates()
                helper.assertTrue(map.getVisibleChunkIfPresent(key) === holder, "the same holder should be revived")
                helper.assertTrue(!level.isLoaded(pos), "the revived chunk should stay below FULL")
                // メモリに残ったまま、バニラの保存がチャンクを書く（積んであったアンロードの仕事は何もせず終わる）
                cache.save(true)
                helper.assertTrue(!chunk.isUnsaved, "the vanilla save should write the revived chunk")
                helper.assertTrue(map.getVisibleChunkIfPresent(key) === holder, "the revived holder should stay")
            } finally {
                if (revived) cache.removeRegionTicket(REVIVE_TICKET, chunkPos, -1, chunkPos)
            }
            helper.succeed()
        }
    }

    /** アンロード待ちのチャンクを FULL でない段階で戻すチケット（#761） */
    private val REVIVE_TICKET: TicketType<ChunkPos> =
        TicketType.create("kibicomputers_revive_test", Comparator.comparingLong(ChunkPos::toLong))

    /** ディスク（書き込み待ちを含む）のチャンク [chunk] の NBT で、[pos] のケースのタグが指す世代 */
    private fun diskGeneration(helper: GameTestHelper, chunk: ChunkPos, pos: BlockPos): Long {
        val data = helper.level.chunkSource.chunkMap.read(chunk).join().orElse(null)
        helper.assertTrue(data != null, "the chunk should be on disk")
        val tag = caseTagIn(data!!, pos)
        helper.assertTrue(tag != null && tag.contains(CaseBlockEntity.SAVE_GENERATION_TAG), "the saved chunk should have the case with a generation: $tag")
        return tag!!.getLong(CaseBlockEntity.SAVE_GENERATION_TAG)
    }

    /** チャンクの NBT [data] の中の、[pos] のブロックエンティティのタグ */
    private fun caseTagIn(data: CompoundTag, pos: BlockPos): CompoundTag? =
        data.getList("block_entities", Tag.TAG_COMPOUND.toInt()).map { it as CompoundTag }
            .firstOrNull { it.getInt("x") == pos.x && it.getInt("y") == pos.y && it.getInt("z") == pos.z }

    /**
     * FULL でなくなったチャンクで、止まった台のそろえが保存まで届いたか（#722）。チャンクに未保存の印が付いているか、
     * 印がもう消えていれば、ディスク（IOWorker の書き込み待ちを含む）の [pos] のケースが `PoweredOn=false` を持っているか。
     * 印は外すのと同じ呼び出しの中で付くが、バニラの急ぎの保存（`ChunkMap.processUnloads` → `saveChunkIfNeeded`）が
     * 続く tick で書いて消すことがあるので、印だけでは揺れる。置いた直後の保存で `PoweredOn=true` が載っていることや、
     * まだ書かれていないことがあるので、タグがないときと `PoweredOn=true` のときは通さない。ディスクは印がないときだけ読む
     */
    private fun demotedChunkSettled(helper: GameTestHelper, be: CaseBlockEntity, chunk: ChunkPos, pos: BlockPos): Boolean {
        if (be.demotedChunk?.isUnsaved == true) return true
        val data = helper.level.chunkSource.chunkMap.read(chunk).join().orElse(null) ?: return false
        val tag = caseTagIn(data, pos) ?: return false
        return tag.contains(CaseBlockEntity.POWERED_ON_TAG) && !tag.getBoolean(CaseBlockEntity.POWERED_ON_TAG)
    }

    /**
     * 爆発（`onBlockExploded`）で壊れたケースは、保存データのディレクトリを消す。ほかのテストのブロックを壊さないよう、
     * 離れたチャンクで爆発させる
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 400)
    fun explosionDeletesTheSaves(helper: GameTestHelper) {
        val store = store(helper)
        val level = helper.level
        val slot = FarChunks.allocate(helper)
        val pos = slot.pos(8, 200, 8)
        level.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get().defaultBlockState(), 3)
        val be = level.getBlockEntity(pos) as CaseBlockEntity
        installRequired(be)
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        // onLoad（索引に入る）を待ってから取り出して書く
        helper.runAfterDelay(1) {
            be.computer.prepareUnload()
            helper.assertTrue(saveNow(be, CapturePolicy.KILL_TRANSIENT) > 0, "the case should be saved")
            be.computer.endQuiesce()
            val id = be.identity!!.id
            helper.assertTrue(Files.isDirectory(store.directoryOf(id)), "the save directory should exist")
            // 中心のブロックは必ず壊れる強さ（ケースの爆発耐性 6）
            level.explode(null, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 4.0f, Level.ExplosionInteraction.BLOCK)
            slot.release()
            helper.assertTrue(level.getBlockEntity(pos) !is CaseBlockEntity, "the explosion should destroy the case")
            ServerMachines.drainSaves()
            helper.assertTrue(!Files.exists(store.directoryOf(id)), "an exploded case should delete its directory")
            helper.succeed()
        }
    }
}
