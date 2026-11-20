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
}
