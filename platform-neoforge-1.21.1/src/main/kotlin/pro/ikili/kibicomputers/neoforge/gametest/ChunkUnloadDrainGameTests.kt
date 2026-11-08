package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerChunkCache
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
        val far = farChunk(helper)
        val result = drainWithRequeueing(helper.level.chunkSource, far, ticket = true, timeoutNanos = TimeUnit.SECONDS.toNanos(30))
        helper.assertTrue(result.drained, "the unload queue should drain (ran the requeueing task ${result.runs} times)")
        helper.assertTrue(result.loadedAfter, "the far chunk should load while draining")
        helper.assertFalse(result.leftInQueue, "the requeueing task should be gone")
        helper.assertTrue(result.runs >= 2, "the task should have requeued itself at least once (ran ${result.runs} times)")
        helper.succeed()
    }

    /**
     * [ChunkUnloadDrain.drain] が上限を過ぎて `false` を返したとき（チャンクが読み込まれないとき）も、
     * 積み直す仕事がアンロードの列に残らない（#543。残ると毎 tick と `stopServer` の `processUnloads` で回り続ける）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun requeueingTaskLeavesQueueWhenDrainTimesOut(helper: GameTestHelper) {
        val far = farChunk(helper)
        // チケットを付けないので、チャンクは読み込まれず、上限（0）ですぐに諦める
        val result = drainWithRequeueing(helper.level.chunkSource, far, ticket = false, timeoutNanos = 0)
        helper.assertFalse(result.drained, "the drain should give up when the chunk never loads")
        helper.assertTrue(result.runs >= 1, "the requeueing task should have run (ran ${result.runs} times)")
        helper.assertFalse(result.leftInQueue, "the requeueing task should not be left in the unload queue")
        helper.succeed()
    }

    private fun farChunk(helper: GameTestHelper): ChunkPos {
        val origin = ChunkPos(helper.absolutePos(BlockPos.ZERO))
        val far = ChunkPos(origin.x + FAR_CHUNKS, origin.z + FAR_CHUNKS)
        helper.assertFalse(helper.level.hasChunk(far.x, far.z), "the far chunk $far should not be loaded yet")
        return far
    }

    private class DrainResult(val drained: Boolean, val loadedAfter: Boolean, val runs: Int, val leftInQueue: Boolean)

    /**
     * [far] が読み込まれるまで自分を積み直す仕事を列に入れて、[ChunkUnloadDrain.drain] で流す。
     * 結果がどうであれ、終わったら仕事を止めて列から外し、チケットも外す（テストが落ちても、列に何も残さない）。
     * [DrainResult.leftInQueue] は、その片付けの後に列に残っているか
     */
    private fun drainWithRequeueing(cache: ServerChunkCache, far: ChunkPos, ticket: Boolean, timeoutNanos: Long): DrainResult {
        // チケットの更新・世代のタスクの開始・メインスレッドの段は、どれもメインスレッドの仕事（pollTask）で進む
        if (ticket) cache.addRegionTicket(TICKET, far, 0, far)
        val loaded = { cache.getChunkNow(far.x, far.z) != null }
        val queue = cache.chunkMap.unloadQueue
        var runs = 0
        var finished = false
        val requeueing = object : Runnable {
            override fun run() {
                runs++
                if (!finished && !loaded()) queue.add(this)
            }
        }
        queue.add(requeueing)
        val drained: Boolean
        val loadedAfter: Boolean
        try {
            drained = ChunkUnloadDrain.drain(cache, System.nanoTime() + timeoutNanos)
            loadedAfter = loaded()
        } finally {
            // 積み直しを止め（列の外で走っている最中でも、次は積み直さない）、列からも外す
            finished = true
            while (queue.remove(requeueing)) Unit
            if (ticket) cache.removeRegionTicket(TICKET, far, 0, far)
        }
        return DrainResult(drained, loadedAfter, runs, queue.contains(requeueing))
    }
}
