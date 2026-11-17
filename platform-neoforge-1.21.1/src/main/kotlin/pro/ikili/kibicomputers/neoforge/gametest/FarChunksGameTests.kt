package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.ChunkPos
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import kotlin.math.abs
import kotlin.math.max

/** 離れたチャンクの割り当て役（[FarChunks]。#747）の GameTest */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object FarChunksGameTests {
    /**
     * 続けて払い出した slot は、keeper を含めてほかの slot から読み込みの届かない距離（13 より遠く）離れ、払い出すまで読み込まれておらず、
     * 払い出した後は強制読み込みで FULL になっている。強制読み込みしない slot（[FarChunks.reserve]）も同じ間で払い出し、読み込まない
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun slotsDoNotOverlap(helper: GameTestHelper) {
        val level = helper.level
        val a = FarChunks.allocate(helper, keeper = true)
        val b = FarChunks.allocate(helper)
        val c = FarChunks.reserve(helper)
        val chunks = listOfNotNull(a.chunk, a.keeper, b.chunk, c)
        val slots = listOf(listOfNotNull(a.chunk, a.keeper), listOf(b.chunk), listOf(c))
        for (i in slots.indices) {
            for (j in slots.indices) {
                if (i == j) continue
                for (p in slots[i]) {
                    for (q in slots[j]) {
                        val distance = max(abs(p.x - q.x), abs(p.z - q.z))
                        helper.assertTrue(distance > 13, "the slots should not reach each other: $p and $q are $distance chunks apart")
                    }
                }
            }
        }
        helper.assertTrue(chunks.toSet().size == chunks.size, "every chunk should be different: $chunks")
        helper.assertTrue(a.keeper!!.x - a.chunk.x == FarChunks.KEEPER_DISTANCE && a.keeper.z == a.chunk.z, "the keeper should be ${FarChunks.KEEPER_DISTANCE} chunks east")
        helper.assertTrue(b.keeper == null, "a slot without a keeper should have none")
        for (p in listOf(a.chunk, a.keeper, b.chunk)) {
            helper.assertTrue(p.toLong() in level.forcedChunks, "the allocated chunk $p should be forced")
        }
        helper.assertTrue(level.chunkSource.hasChunk(a.chunk.x, a.chunk.z) && level.chunkSource.hasChunk(b.chunk.x, b.chunk.z), "the allocated chunks should be loaded")
        helper.assertTrue(c.toLong() !in level.forcedChunks && !level.chunkSource.hasChunk(c.x, c.z), "a reserved chunk should not be loaded")
        // テストの場所（GameTest の並び）からも十分離れている
        val here = ChunkPos(helper.absolutePos(BlockPos.ZERO))
        for (p in chunks) {
            helper.assertTrue(max(abs(p.x - here.x), abs(p.z - here.z)) > 1000, "the slot $p should be far from the tests at $here")
        }
        a.release()
        b.release()
        for (p in listOf(a.chunk, a.keeper, b.chunk)) {
            helper.assertTrue(p.toLong() !in level.forcedChunks, "the released chunk $p should not be forced")
        }
        helper.succeed()
    }
}
