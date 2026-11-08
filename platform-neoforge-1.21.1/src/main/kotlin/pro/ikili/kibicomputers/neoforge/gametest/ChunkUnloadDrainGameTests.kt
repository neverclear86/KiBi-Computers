package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.TicketType
import net.minecraft.world.level.ChunkPos
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.neoforge.machine.ChunkUnloadDrain
import java.util.Comparator
import java.util.concurrent.TimeUnit

/** サーバーの停止の前に、チャンクのアンロードの列を流しきる（#540。[ChunkUnloadDrain]）GameTest */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object ChunkUnloadDrainGameTests {
    /** テストの場所から離れた（読み込まれていない）チャンクまでの距離 */
    private const val FAR_CHUNKS = 400

    /** 離れたチャンクを読み込ませるチケット（チャンクを FULL にする段階） */
    private val TICKET: TicketType<ChunkPos> = TicketType.create("kibicomputers_drain_test", Comparator.comparingLong(ChunkPos::toLong))

    /**
     * 読み込まれていないチャンクにチケットを付け、それが読み込まれるまで自分を積み直す仕事を、アンロードの列に入れる
     * （読み込み直されたチャンクの、古いアンロードの仕事と同じふるまい。世代のタスクがメインスレッドで進まないと終わらない）。
     * `stopServer` と同じく列を流すだけだと回り続けるが、[ChunkUnloadDrain.drain] はメインスレッドの仕事を進めながら流すので、
     * チャンクが読み込まれて列が空になる
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun drainRunsMainThreadTasksBetweenRequeues(helper: GameTestHelper) {
        val level = helper.level
        val cache = level.chunkSource
        val origin = ChunkPos(helper.absolutePos(BlockPos.ZERO))
        val far = ChunkPos(origin.x + FAR_CHUNKS, origin.z + FAR_CHUNKS)
        helper.assertFalse(level.hasChunk(far.x, far.z), "the far chunk $far should not be loaded yet")
        // チケットの更新・世代のタスクの開始・メインスレッドの段は、どれもメインスレッドの仕事（pollTask）で進む
        cache.addRegionTicket(TICKET, far, 0, far)
        val loaded = { cache.getChunkNow(far.x, far.z) != null }
        val queue = cache.chunkMap.unloadQueue
        var runs = 0
        val requeueing = object : Runnable {
            override fun run() {
                runs++
                if (!loaded()) queue.add(this)
            }
        }
        queue.add(requeueing)
        val drained: Boolean
        val loadedAfter: Boolean
        try {
            drained = ChunkUnloadDrain.drain(cache, System.nanoTime() + TimeUnit.SECONDS.toNanos(30))
            loadedAfter = loaded()
        } finally {
            cache.removeRegionTicket(TICKET, far, 0, far)
        }
        helper.assertTrue(drained, "the unload queue should drain (ran the requeueing task $runs times)")
        helper.assertTrue(loadedAfter, "the far chunk should load while draining")
        helper.assertFalse(queue.contains(requeueing), "the requeueing task should be gone")
        helper.assertTrue(runs >= 2, "the task should have requeued itself at least once (ran $runs times)")
        helper.succeed()
    }
}
