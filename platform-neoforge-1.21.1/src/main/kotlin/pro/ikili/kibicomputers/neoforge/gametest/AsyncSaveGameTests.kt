package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.ClaimResult
import pro.ikili.kibicomputers.core.persist.MachineOwner
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.core.persist.PendingSave
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines
import pro.ikili.kibicomputers.neoforge.machine.WorldMachineSaves
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.nio.file.Files
import java.util.OptionalLong
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * 書き込みのスレッド（#84、ADR 0084）の GameTest: 予約と完了、アンロードで予約の世代を採る、書き込み中の破壊、振り直し、
 * 予約の重なり、アンロードの書き込み待ちの上限。書き込みのスレッドを止めるとき（[WriterGate]）は、同じ tick の中で放す
 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object AsyncSaveGameTests {
    private val CASE = BlockPos(1, 1, 1)

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

    /** [pos] にケースを置いて電源を入れ、`onLoad`（索引に入る）の後の tick に [then] を呼ぶ */
    private fun withRunningCase(helper: GameTestHelper, pos: BlockPos = CASE, then: (CaseBlockEntity) -> Unit) {
        helper.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        val be = helper.getBlockEntity<CaseBlockEntity>(pos)
        installRequired(be)
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        helper.runAfterDelay(1) { then(be) }
    }

    /** 止めて取り出し、書き込みに回して、止めるのを終える（自動保存の1台分と同じ） */
    private fun submit(helper: GameTestHelper, be: CaseBlockEntity): PendingSave {
        be.computer.prepareUnload()
        val pending = be.captureAndSubmit(CapturePolicy.KILL_TRANSIENT)
        be.computer.endQuiesce()
        helper.assertTrue(pending != null, "the machine should be captured")
        return pending!!
    }

    private fun finish(helper: GameTestHelper, pos: BlockPos = CASE) {
        helper.setBlock(pos, Blocks.AIR)
        helper.succeed()
    }

    /** 規則 2: 書き込みに回した直後は NBT の世代が前のまま、完了を受け取った後に進み、ファイルがある */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 200)
    fun identityAdvancesOnCompletion(helper: GameTestHelper) {
        val store = store(helper)
        withRunningCase(helper) { be ->
            saveNow(be.also { it.computer.prepareUnload() }, CapturePolicy.KILL_TRANSIENT)
            be.computer.endQuiesce()
            val before = be.identity!!
            val pending = WriterGate().use {
                val pending = submit(helper, be)
                helper.assertTrue(be.identity == before, "the identity should not advance before the write but was ${be.identity}")
                helper.assertTrue(be.pendingSave === pending, "the reservation should be pending")
                helper.assertTrue(be.currentIdentity == pending.identity, "the current id should be the reservation")
                helper.assertTrue(pending.identity.generation == before.generation + 1, "the next generation should be reserved: ${pending.identity}")
                helper.assertTrue(store.reservedGeneration(before.id) == OptionalLong.of(pending.identity.generation), "the store should reserve the generation")
                ServerMachines.processCompletions()
                helper.assertTrue(be.identity == before, "nothing should complete while the writer is held")
                pending
            }
            ServerMachines.drainSaves()
            helper.assertTrue(be.identity == pending.identity, "the completion should advance the identity to ${pending.identity} but was ${be.identity}")
            helper.assertTrue(be.pendingSave == null, "the reservation should be cleared")
            helper.assertTrue(Files.isRegularFile(store.generationFile(before.id, pending.identity.generation)), "the generation file should exist")
            finish(helper)
        }
    }

    /**
     * 規則 4・7c: 新しい台（ID なし）の書き込み中にアンロードの取り出しが来ると、同じ ID で2つ目の世代を予約し、
     * NBT は書き終わりを待たずに後の世代を指す。書き終わった後に両方のファイルがあり、遅れた先の完了で戻らない
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 200)
    fun unloadDuringWriteAdoptsTheReservation(helper: GameTestHelper) {
        val store = store(helper)
        withRunningCase(helper) { be ->
            val chunk = helper.level.getChunkAt(helper.absolutePos(CASE))
            val (first, second) = WriterGate().use {
                val first = submit(helper, be)
                helper.assertTrue(first.writes == 2 && be.identity == null, "a new machine gets its id (and owner.bin) from the reservation: $first")
                ServerMachines.saveForUnload(chunk, listOf(be), adopt = true)
                val second = be.pendingSave
                helper.assertTrue(second != null && second !== first, "the unload should reserve another generation")
                helper.assertTrue(second!!.identity.id == first.identity.id, "the unload should not assign a second id: $first, $second")
                helper.assertTrue(second.identity.generation == first.identity.generation + 1, "the generations should be counted: $first, $second")
                helper.assertTrue(be.identity == second.identity, "the unload should adopt the reservation before the write but was ${be.identity}")
                helper.assertTrue(chunk.isUnsaved, "the chunk should be marked unsaved")
                first to second
            }
            ServerMachines.drainSaves()
            val id = first.identity.id
            helper.assertTrue(Files.isRegularFile(store.generationFile(id, first.identity.generation)), "the first generation should be written")
            helper.assertTrue(Files.isRegularFile(store.generationFile(id, second.identity.generation)), "the second generation should be written")
            helper.assertTrue(be.identity == second.identity, "a late completion should not move the identity back but was ${be.identity}")
            helper.assertTrue(store.latestGeneration(id) == OptionalLong.of(second.identity.generation), "the latest generation should be the second")
            be.computer.endQuiesce()
            helper.setBlock(CASE, Blocks.AIR)
            helper.assertTrue(store.index.ownerOf(id) == null, "the removed case should leave the index")
            helper.succeed()
        }
    }

    /** 規則 7・7c: 新しい台（ID なし）の書き込み中にプレイヤーが壊すと、書き終わった後に振った ID のディレクトリも索引の項も残らない */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 200)
    fun breakDuringWriteDeletesTheSaves(helper: GameTestHelper) {
        val store = store(helper)
        withRunningCase(helper) { be ->
            val id = WriterGate().use {
                val pending = submit(helper, be)
                be.pressPowerButton()
                val player = helper.makeMockPlayer(GameType.SURVIVAL)
                val abs = helper.absolutePos(CASE)
                val state = helper.level.getBlockState(abs)
                state.block.playerWillDestroy(helper.level, abs, state, player)
                helper.setBlock(CASE, Blocks.AIR)
                pending.identity.id
            }
            ServerMachines.drainSaves()
            helper.assertTrue(!Files.exists(store.directoryOf(id)), "the directory written during the break should be deleted")
            helper.assertTrue(store.index.ownerOf(id) == null, "the index should not keep the id")
            helper.assertTrue(!store.latestGeneration(id).isPresent, "the store should forget the id")
            helper.succeed()
        }
    }

    /**
     * 同じ台の予約が2つ重なったとき: 先の完了だけを受け取った時点で NBT の世代は先の世代、両方の後に後の世代になり、
     * 予約は後の完了で外れる
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 200)
    fun overlappingReservationsCompleteInOrder(helper: GameTestHelper) {
        withRunningCase(helper) { be ->
            saveNow(be.also { it.computer.prepareUnload() }, CapturePolicy.KILL_TRANSIENT)
            be.computer.endQuiesce()
            WriterGate().use { held ->
                val first = submit(helper, be)
                WriterGate().use { between ->
                    val second = submit(helper, be)
                    helper.assertTrue(second.identity.generation == first.identity.generation + 1, "two generations should be reserved")
                    held.close()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (be.identity != first.identity && System.nanoTime() < deadline) {
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
                        ServerMachines.processCompletions()
                    }
                    helper.assertTrue(be.identity == first.identity, "the first completion should advance to ${first.identity} but was ${be.identity}")
                    helper.assertTrue(be.pendingSave === second, "the second reservation should stay pending")
                    between.close()
                    ServerMachines.drainSaves()
                    helper.assertTrue(be.identity == second.identity, "the second completion should advance to ${second.identity} but was ${be.identity}")
                    helper.assertTrue(be.pendingSave == null, "the reservation should be cleared by the last completion")
                }
            }
            finish(helper)
        }
    }

    /**
     * 振り直しの完了: `owner.bin` が別の位置を指すようになった台を保存すると、新しい ID の世代 1 を書いて採り、NBT も新しい ID を指す。
     * 振り直しの書き込み中に2つ目を予約しても、同じ新しい ID の世代 2 になる。もう1回保存しても ID は変わらない
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 200)
    fun reassignedIdIsAdopted(helper: GameTestHelper) {
        val store = store(helper)
        withRunningCase(helper) { be ->
            saveNow(be.also { it.computer.prepareUnload() }, CapturePolicy.KILL_TRANSIENT)
            be.computer.endQuiesce()
            val old = be.identity!!
            // 別の位置のケースが引き取ったことにする（索引の項を外してから claim）
            val self = be.owner!!
            store.index.removeIfOwner(old.id, self)
            val other = MachineOwner(self.dimension, self.x + 100, self.y, self.z)
            val claim = store.claim(old.id.toString(), old.generation, old.epoch, other)
            helper.assertTrue(claim.result == ClaimResult.TAKEN_OVER, "the other owner should take the id over but was $claim")
            val (first, second) = WriterGate().use {
                val first = submit(helper, be)
                val second = submit(helper, be)
                first to second
            }
            helper.assertTrue(first.reassigned && first.identity.id != old.id, "the save should be reassigned: $first")
            helper.assertTrue(first.identity.generation == 1L, "a reassigned id starts at generation 1: $first")
            helper.assertTrue(!second.reassigned && second.identity.id == first.identity.id, "the overlapping save should keep the new id: $second")
            helper.assertTrue(second.identity.generation == 2L, "the overlapping save should be generation 2: $second")
            ServerMachines.drainSaves()
            helper.assertTrue(be.identity == second.identity, "the case should adopt ${second.identity} but was ${be.identity}")
            helper.assertTrue(be.pendingSave == null, "the reservation should be cleared")
            val tag = be.saveWithoutMetadata(helper.level.registryAccess())
            helper.assertTrue(tag.getString(CaseBlockEntity.MACHINE_ID_TAG) == second.identity.id.toString(), "the NBT should point at the new id: $tag")
            helper.assertTrue(store.index.ownerOf(second.identity.id) == self, "the new id should be indexed")
            // もう1回保存しても新しい ID のまま（孤立したディレクトリが増えない）
            helper.assertTrue(saveNow(be.also { it.computer.prepareUnload() }, CapturePolicy.KILL_TRANSIENT) > 0, "the case should save again")
            be.computer.endQuiesce()
            helper.assertTrue(be.identity?.id == second.identity.id, "the id should stay the new one but was ${be.identity}")
            finish(helper)
        }
    }

    /**
     * アンロードの書き込み待ちの上限（台ごと・期限の共有）: 上限を小さくした置き場で、1つのチャンクの3台を回すと、
     * 台ごとに上限を確かめ、上限で待つのは期限まで1回だけで、期限を過ぎた後は残りの台を待たずに回す
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 400)
    fun unloadCapacityWaitSharesTheDeadline(helper: GameTestHelper) {
        val store = store(helper)
        val level = helper.level
        val chunk = ChunkPos(helper.absolutePos(CASE).x / 16 - 400, helper.absolutePos(CASE).z / 16 - 400)
        level.setChunkForced(chunk.x, chunk.z, true)
        level.getChunk(chunk.x, chunk.z)
        val positions = (0 until 3).map { BlockPos(chunk.minBlockX + 2 + it * 4, 200, chunk.minBlockZ + 7) }
        val cases = positions.map { pos ->
            level.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get().defaultBlockState(), 3)
            (level.getBlockEntity(pos) as CaseBlockEntity).also {
                installRequired(it)
                helper.assertTrue(it.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${it.computer}")
            }
        }
        helper.runAfterDelay(1) {
            val config = ServerMachines.saveConfig
            val maxBytes = store.maxPendingBytes
            val stats = ServerMachines.Stats
            val checks = stats.capacityChecks
            val waits = stats.capacityWaits
            val timeouts = stats.capacityTimeouts
            try {
                ServerMachines.saveConfig = config.copy(writerFlushMillis = 200)
                store.maxPendingBytes = 1
                WriterGate().use { ServerMachines.saveForUnload(level.getChunk(chunk.x, chunk.z), cases, adopt = false) }
            } finally {
                store.maxPendingBytes = maxBytes
                ServerMachines.saveConfig = config
            }
            ServerMachines.drainSaves()
            helper.assertTrue(stats.capacityChecks - checks == 3L, "every case should check the cap: ${stats.capacityChecks - checks}")
            helper.assertTrue(stats.capacityWaits - waits == 1L, "only the second case should wait: ${stats.capacityWaits - waits}")
            helper.assertTrue(stats.capacityTimeouts - timeouts == 1L, "the shared deadline should pass once: ${stats.capacityTimeouts - timeouts}")
            for (be in cases) {
                helper.assertTrue(be.identity != null, "every case should be saved after the writer drains: ${be.blockPos}")
                be.computer.endQuiesce()
                be.pressPowerButton()
            }
            for (pos in positions) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)
            level.setChunkForced(chunk.x, chunk.z, false)
            helper.succeed()
        }
    }
}
