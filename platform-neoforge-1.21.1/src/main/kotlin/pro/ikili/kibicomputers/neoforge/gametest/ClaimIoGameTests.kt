package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.ClaimResult
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines
import pro.ikili.kibicomputers.neoforge.machine.WorldMachineSaves
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.nio.file.Files
import java.util.UUID

/**
 * claim のメインスレッドの I/O（#585、ADR 0084 の規則 7a）: 写しと知らない ID の NBT をまとめて貼っても、claim は `owner.bin` を
 * 読み書きせず、ディレクトリも作らない（`owner.bin` は最初の世代の前に書き込みのスレッドで書く）
 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object ClaimIoGameTests {
    private val ORIGINAL = BlockPos(1, 1, 1)
    private val COPIES = listOf(BlockPos(0, 1, 0), BlockPos(1, 1, 0), BlockPos(2, 1, 0), BlockPos(0, 1, 1))
    private val UNKNOWN = listOf(BlockPos(2, 1, 1), BlockPos(0, 1, 2), BlockPos(1, 1, 2), BlockPos(2, 1, 2))

    /** [id] を NBT の生の値に持つケースを置く（`null` なら ID なし）。[poweredOn] なら電源が入っていた台（復元の順番待ちに入る） */
    private fun place(helper: GameTestHelper, pos: BlockPos, id: UUID?, poweredOn: Boolean = false): CaseBlockEntity {
        helper.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        val be = helper.getBlockEntity<CaseBlockEntity>(pos)
        be.loadWithComponents(rawTag(helper, be, id?.toString(), poweredOn), helper.level.registryAccess())
        return be
    }

    /** [be] の NBT の ID・電源を [id]・[poweredOn] に書き換えたもの（`/data merge` と同じ。[id] が `null` なら ID なし） */
    private fun rawTag(helper: GameTestHelper, be: CaseBlockEntity, id: String?, poweredOn: Boolean) =
        be.saveWithoutMetadata(helper.level.registryAccess()).also { tag ->
            if (id != null) {
                tag.putString(CaseBlockEntity.MACHINE_ID_TAG, id)
                tag.putLong(CaseBlockEntity.SAVE_GENERATION_TAG, 0)
                tag.putLong(CaseBlockEntity.OWNER_EPOCH_TAG, 0)
            } else {
                tag.remove(CaseBlockEntity.MACHINE_ID_TAG)
                tag.remove(CaseBlockEntity.SAVE_GENERATION_TAG)
                tag.remove(CaseBlockEntity.OWNER_EPOCH_TAG)
            }
            tag.putBoolean(CaseBlockEntity.POWERED_ON_TAG, poweredOn)
        }

    /** 別のバッチにして、ほかのテストの claim（読み）を数え口に混ぜない */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 100, batch = "kibicomputers_claim_io")
    fun pastedCopiesDoNotTouchTheDisk(helper: GameTestHelper) {
        val store: MachineSaveStore = WorldMachineSaves.current ?: run {
            helper.fail("no machine save store")
            return
        }
        val originalId = UUID.randomUUID()
        val original = place(helper, ORIGINAL, originalId)
        helper.runAfterDelay(2) {
            helper.assertTrue(original.lastClaim == ClaimResult.KEPT, "the original should keep its id but was ${original.lastClaim}")
            val reads = store.mainOwnerReads
            // 同じ tick に、写しと知らない ID をまとめて貼る
            val copies = COPIES.map { place(helper, it, originalId) }
            val unknownIds = UNKNOWN.map { UUID.randomUUID() }
            val unknown = UNKNOWN.mapIndexed { i, pos -> place(helper, pos, unknownIds[i]) }
            helper.runAfterDelay(2) {
                for (c in copies) {
                    helper.assertTrue(c.lastClaim == ClaimResult.NEW_ID_DUPLICATE_LOADED, "a copy should get a new id but was ${c.lastClaim}")
                    val id = c.identity!!.id
                    helper.assertTrue(!Files.exists(store.directoryOf(id)), "claim should not create the directory of the new id $id")
                }
                for ((i, c) in unknown.withIndex()) {
                    helper.assertTrue(c.lastClaim == ClaimResult.KEPT, "an unknown id should be kept but was ${c.lastClaim}")
                    helper.assertTrue(c.identity?.id == unknownIds[i], "the unknown id should stay")
                    helper.assertTrue(!Files.exists(store.directoryOf(unknownIds[i])), "claim should not write owner.bin of ${unknownIds[i]}")
                }
                helper.assertTrue(!Files.exists(store.directoryOf(originalId)), "claim should not write owner.bin of the original")
                helper.assertTrue(store.mainOwnerReads == reads, "claim should not read owner.bin: ${store.mainOwnerReads - reads} reads")
                for (pos in COPIES + UNKNOWN + ORIGINAL) helper.setBlock(pos, Blocks.AIR)
                helper.succeed()
            }
        }
    }

    /**
     * 読みの要る claim（#869）: 覚えていない ID（再起動の後と同じ）の台は、`onLoad` で claim を待ちにし、メインスレッドで `owner.bin` を
     * 読まない。待つ間は ID も判定もなく、索引にも入らない。後の tick に、同じ ID の写しは来た順に確定し（先の台が KEPT、後の台が重複）、
     * 待っている間に外した台は索引に残らない。覚えている ID の台は、同じ tick でもその場で決まる（動かした先なので引き取る）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 100, batch = "kibicomputers_claim_io")
    fun claimsThatNeedAReadWaitOffTheMainThread(helper: GameTestHelper) {
        val store: MachineSaveStore = WorldMachineSaves.current ?: run {
            helper.fail("no machine save store")
            return
        }
        val forgotten = UUID.randomUUID()
        val removedId = UUID.randomUUID()
        val knownId = UUID.randomUUID()
        val seeds = listOf(place(helper, COPIES[0], forgotten), place(helper, COPIES[1], removedId), place(helper, COPIES[2], knownId))
        val start = helper.tick
        var phase = 0
        var reads = 0L
        var deferredBefore = 0L
        var placed: List<CaseBlockEntity> = emptyList()
        // onEachTick は最初に1回だけ登録する（runAfterDelay の中で登録すると、GameTest の予定の表を回している途中に足すことになり、
        // 外側の予定がもう一度走ることがある）
        helper.onEachTick {
            when (phase) {
                0 -> {
                    if (helper.tick < start + 2) return@onEachTick
                    helper.assertTrue(seeds.all { it.lastClaim == ClaimResult.KEPT }, "the seeds should keep their ids: ${seeds.map { it.lastClaim }}")
                    // 外して、覚えている owner.bin を忘れる（再起動の後と同じ。knownId は覚えたまま）
                    for (pos in COPIES.take(3)) helper.setBlock(pos, Blocks.AIR)
                    store.forgetOwnerForTest(forgotten)
                    store.forgetOwnerForTest(removedId)
                    reads = store.mainOwnerReads
                    deferredBefore = store.deferredClaims
                    placed = listOf(
                        place(helper, UNKNOWN[0], forgotten),
                        place(helper, UNKNOWN[1], forgotten),
                        place(helper, UNKNOWN[2], removedId),
                        place(helper, UNKNOWN[3], knownId),
                    )
                    phase = 1
                }
                1 -> {
                    val (first, second, removed, known) = placed
                    if (!first.isLoadedOnServer) return@onEachTick
                    // onLoad の tick（決まるのは次の tick の始め）
                    for (be in listOf(first, second, removed)) {
                        helper.assertTrue(be.isClaimDeferred, "the claim at ${be.blockPos} should wait for owner.bin")
                        helper.assertTrue(be.lastClaim == null && be.identity == null, "a waiting case has no id yet: ${be.lastClaim}, ${be.identity}")
                    }
                    helper.assertTrue(store.index.ownerOf(forgotten) == null, "waiting claims should not be indexed")
                    helper.assertTrue(store.deferredClaims - deferredBefore == 3L, "three claims should wait: ${store.deferredClaims - deferredBefore}")
                    helper.assertTrue(known.lastClaim == ClaimResult.TAKEN_OVER && !known.isClaimDeferred, "a remembered id is decided at once: ${known.lastClaim}")
                    // 待っている間に外す
                    helper.setBlock(UNKNOWN[2], Blocks.AIR)
                    phase = 2
                }
                2 -> {
                    val (first, second, removed) = placed
                    if (first.isClaimDeferred || second.isClaimDeferred) {
                        if (helper.tick > start + 80) helper.fail("the waiting claims should be decided")
                        return@onEachTick
                    }
                    phase = 3
                    helper.assertTrue(first.lastClaim == ClaimResult.KEPT, "the first case should keep the id but was ${first.lastClaim}")
                    helper.assertTrue(first.identity?.id == forgotten, "the first case should keep $forgotten: ${first.identity}")
                    helper.assertTrue(
                        second.lastClaim == ClaimResult.NEW_ID_DUPLICATE_LOADED,
                        "the second copy should get a new id but was ${second.lastClaim}",
                    )
                    helper.assertTrue(store.index.ownerOf(forgotten) == first.owner, "the first case should be indexed")
                    helper.assertTrue(!removed.isClaimDeferred && store.index.ownerOf(removedId) == null, "a removed waiting case should not be indexed")
                    helper.assertTrue(store.mainOwnerReads == reads, "claim should not read owner.bin on the main thread: ${store.mainOwnerReads - reads} reads")
                    for (pos in UNKNOWN) helper.setBlock(pos, Blocks.AIR)
                    helper.succeed()
                }
            }
        }
    }

    /**
     * 待つ間の台（#869）: 取り出さず（`captureAndSubmit` が `null`）、電源ボタンも効かない（状態が変わらない）。アンロード
     * （`onChunkUnloaded`）で待ちを取り消し、後の tick に確定しない（索引に入らない・ID がつかない・順番待ちに入らない）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 100, batch = "kibicomputers_claim_io")
    fun deferredClaimCaseIsInertAndUnloadCancelsIt(helper: GameTestHelper) {
        val store: MachineSaveStore = WorldMachineSaves.current ?: run {
            helper.fail("no machine save store")
            return
        }
        val id = UUID.randomUUID()
        store.forgetOwnerForTest(id)
        val be = place(helper, ORIGINAL, id, poweredOn = true)
        val start = helper.tick
        var phase = 0
        helper.onEachTick {
            when (phase) {
                0 -> {
                    if (!be.isLoadedOnServer) return@onEachTick
                    helper.assertTrue(be.isClaimDeferred, "the claim should wait for owner.bin")
                    helper.assertTrue(be.computer.isRestorePending, "a powered-on case shows the restore wait: ${be.computer}")
                    helper.assertTrue(!ServerMachines.isRestoreQueued(be), "a waiting case should not be offered to the restore queue")
                    val status = be.computer.status
                    for (policy in CapturePolicy.entries) {
                        helper.assertTrue(be.captureAndSubmit(policy) == null, "a waiting case should not be captured ($policy)")
                    }
                    helper.assertTrue(be.currentIdentity == null, "capturing should not assign a new id: ${be.currentIdentity}")
                    helper.assertTrue(be.pressPowerButton() == status, "the power button should not work while the claim waits")
                    helper.assertTrue(
                        be.computer.machine == null && be.computer.isRestorePending && be.isClaimDeferred,
                        "the power button should change nothing: ${be.computer}",
                    )
                    // チャンクのアンロード
                    be.onChunkUnloaded()
                    helper.assertTrue(!be.isClaimDeferred && !store.isClaimDeferred(be), "unloading should cancel the waiting claim")
                    phase = 1
                }
                1 -> {
                    // 取り消した待ちは、読みが終わっても確定しない
                    if (helper.tick < start + 30) return@onEachTick
                    helper.assertTrue(be.identity == null && be.lastClaim == null, "a cancelled claim should not be decided: ${be.identity}, ${be.lastClaim}")
                    helper.assertTrue(store.index.ownerOf(id) == null, "a cancelled claim should not be indexed")
                    helper.assertTrue(!ServerMachines.isRestoreQueued(be), "a cancelled claim should not join the restore queue")
                    helper.setBlock(ORIGINAL, Blocks.AIR)
                    helper.succeed()
                }
            }
        }
    }

    /**
     * `onLoad` で待ちになって、順番待ちへの offer を見送った台（印だけ立てた）が、待つ間の `loadAdditional`（`/data merge`）でメモリで
     * 決まる値（形の合わない ID）に出し直されると、その場で確定して順番待ちに入る（`onDeferredClaim` は来ないので、見送った offer を
     * ここで出す。入らないと、FULL の出入りかアンロードまで復元されない）。ID を消した（生の値がなくなった）ときも同じ
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 100, batch = "kibicomputers_claim_io")
    fun reclaimDuringTheWaitOffersTheSkippedRestore(helper: GameTestHelper) {
        val store: MachineSaveStore = WorldMachineSaves.current ?: run {
            helper.fail("no machine save store")
            return
        }
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        store.forgetOwnerForTest(first)
        store.forgetOwnerForTest(second)
        val invalid = place(helper, COPIES[0], first, poweredOn = true)
        val removed = place(helper, COPIES[1], second, poweredOn = true)
        helper.onEachTick {
            if (!invalid.isLoadedOnServer || !removed.isLoadedOnServer) return@onEachTick
            for (be in listOf(invalid, removed)) {
                helper.assertTrue(be.isClaimDeferred && be.computer.isRestorePending, "the claim should wait: ${be.computer}")
                helper.assertTrue(!ServerMachines.isRestoreQueued(be), "a waiting case should not be offered yet")
            }
            // 生の値が、メモリで決まる値（形の合わない ID・ID なし）に変わる
            invalid.loadWithComponents(rawTag(helper, invalid, "not-a-uuid", true), helper.level.registryAccess())
            removed.loadWithComponents(rawTag(helper, removed, null, true), helper.level.registryAccess())
            helper.assertTrue(
                !invalid.isClaimDeferred && invalid.lastClaim == ClaimResult.NEW_ID_INVALID,
                "the invalid id should be decided at once: ${invalid.lastClaim}",
            )
            helper.assertTrue(!removed.isClaimDeferred, "a case without an id should not wait")
            for (be in listOf(invalid, removed)) {
                helper.assertTrue(!store.isClaimDeferred(be), "the old wait should be cancelled")
                helper.assertTrue(ServerMachines.isRestoreQueued(be), "the skipped restore should be offered when the claim is decided: ${be.computer}")
            }
            helper.setBlock(COPIES[0], Blocks.AIR)
            helper.setBlock(COPIES[1], Blocks.AIR)
            helper.succeed()
        }
    }

    /**
     * ID のないまま復元の順番待ちに入っている台が、`/data merge` で（読みの要る）ID を得て待ちになると、順番待ちから外れる
     * （ID のないまま順番が来ると冷えた起動になる）。確定したら、その ID で順番待ちに入り直す
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 100, batch = "kibicomputers_claim_io")
    fun claimDeferredWhileQueuedLeavesTheRestoreQueue(helper: GameTestHelper) {
        val store: MachineSaveStore = WorldMachineSaves.current ?: run {
            helper.fail("no machine save store")
            return
        }
        val id = UUID.randomUUID()
        store.forgetOwnerForTest(id)
        val be = place(helper, ORIGINAL, null, poweredOn = true)
        val start = helper.tick
        var phase = 0
        helper.onEachTick {
            when (phase) {
                0 -> {
                    if (!be.isLoadedOnServer) return@onEachTick
                    helper.assertTrue(!be.isClaimDeferred && ServerMachines.isRestoreQueued(be), "a case without an id should be queued: ${be.computer}")
                    be.loadWithComponents(rawTag(helper, be, id.toString(), true), helper.level.registryAccess())
                    helper.assertTrue(be.isClaimDeferred, "the new id should need a read")
                    helper.assertTrue(!ServerMachines.isRestoreQueued(be), "a waiting case should leave the restore queue")
                    helper.assertTrue(be.computer.isRestorePending, "the restore wait should stay visible: ${be.computer}")
                    phase = 1
                }
                1 -> {
                    if (be.isClaimDeferred) {
                        if (helper.tick > start + 80) helper.fail("the waiting claim should be decided")
                        return@onEachTick
                    }
                    helper.assertTrue(be.identity?.id == id, "the case should keep the merged id: ${be.identity}, ${be.lastClaim}")
                    helper.assertTrue(
                        ServerMachines.isRestoreQueued(be) || be.restoreStarts == 1,
                        "the case should join the restore queue after the claim is decided: ${be.computer}",
                    )
                    helper.setBlock(ORIGINAL, Blocks.AIR)
                    helper.succeed()
                }
            }
        }
    }
}
