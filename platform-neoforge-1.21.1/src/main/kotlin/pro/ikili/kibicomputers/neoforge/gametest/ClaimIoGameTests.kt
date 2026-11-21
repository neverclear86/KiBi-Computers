package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.persist.ClaimResult
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
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

    private fun place(helper: GameTestHelper, pos: BlockPos, id: UUID): CaseBlockEntity {
        helper.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        val be = helper.getBlockEntity<CaseBlockEntity>(pos)
        val tag = be.saveWithoutMetadata(helper.level.registryAccess())
        tag.putString(CaseBlockEntity.MACHINE_ID_TAG, id.toString())
        tag.putLong(CaseBlockEntity.SAVE_GENERATION_TAG, 0)
        tag.putLong(CaseBlockEntity.OWNER_EPOCH_TAG, 0)
        be.loadWithComponents(tag, helper.level.registryAccess())
        return be
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
}
