package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GameTestListener
import net.minecraft.gametest.framework.GameTestRunner
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
    /** 離れたチャンクを読み込ませるチケット（チャンクを FULL にする段階） */
    private val TICKET: TicketType<ChunkPos> = TicketType.create("kibicomputers_drain_test", Comparator.comparingLong(ChunkPos::toLong))

    /** [far] のまわりの 8 チャンク（FULL にして、[far] の LIGHT 以降の段が依存する分の生成を済ませる） */
    private fun ringOf(far: ChunkPos): List<ChunkPos> =
        (-1..1).flatMap { dx -> (-1..1).mapNotNull { dz -> if (dx == 0 && dz == 0) null else ChunkPos(far.x + dx, far.z + dz) } }

    /**
     * 読み込まれていないチャンクにチケットを付け、それが読み込まれるまで自分を積み直す仕事を、アンロードの列に入れる
     * （読み込み直されたチャンクの、古いアンロードの仕事と同じふるまい。世代のタスクがメインスレッドで進まないと終わらない）。
     * `stopServer` と同じく列を流すだけだと回り続けるが、[ChunkUnloadDrain.drain] はメインスレッドの仕事を進めながら流すので、
     * チャンクが読み込まれて列が空になる。
     *
     * 時間のかかるワールド生成は、`drain` の外で先に済ませる（#854）。重いマシンでは、離れたチャンクの生成が `drain` の壁時計の上限
     * （30 秒）に間に合わなかった。ただし [far] 自身を FULL にしてから外すと、FULL の future が完了したまま残り、FULL のチケットを
     * 付け直すだけ（チケットの更新だけ）で読み込み直されてしまうので、メインスレッドの段を進めなくても通る。そこで、[far] は一度も
     * FULL にせず、まわりの 8 チャンクだけを FULL にして生成を済ませる（[far] は段階 34 で、LIGHT より前までしか進まない）。
     * まわりは、ほかの離れたチャンクのテスト（[FarChunks.allocate]）と同じく `getChunk` で、上限なしに同期で生成を待つ（tick の数で上限を
     * 置くと、ワーカーのスレッドだけが遅いとき、壁時計の上限と変わらなくなる。まわりの `getChunk` の一時のチケットも段階 33 なので、
     * [far] は段階 34 のまま）。`drain` に残る仕事は、[far] のチケットの更新、LIGHT・SPAWN の段、
     * FULL への昇格（メインスレッドの段を含む）になる。[far] のチケットは `drain` の中で付けるので、tick の間のサーバーの `pollTask` に
     * 先を越されることはない
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 1200)
    fun drainRunsMainThreadTasksBetweenRequeues(helper: GameTestHelper) {
        val far = FarChunks.reserve(helper)
        val ring = ringOf(far)
        val cache = helper.level.chunkSource
        // テストが途中で落ちても（タイムアウトを含む）、まわりのチケットを外す（[far] のチケットは drainWithRequeueing が外す）
        val cleanUp = { ring.forEach { cache.removeRegionTicket(TICKET, it, 0, it) } }
        helper.testInfo.addListener(
            object : GameTestListener {
                override fun testStructureLoaded(info: GameTestInfo) {}

                override fun testPassed(info: GameTestInfo, runner: GameTestRunner) = cleanUp()

                override fun testFailed(info: GameTestInfo, runner: GameTestRunner) = cleanUp()

                override fun testAddedForRerun(old: GameTestInfo, new: GameTestInfo, runner: GameTestRunner) {}
            },
        )
        ring.forEach { cache.addRegionTicket(TICKET, it, 0, it) }
        ring.forEach { helper.level.getChunk(it.x, it.z) }
        helper.startSequence().thenExecute {
            helper.assertTrue(ring.all { cache.getChunkNow(it.x, it.z) != null }, "the chunks around the far chunk should be FULL")
            val result = drainWithRequeueing(cache, far, ticket = true, timeoutNanos = TimeUnit.SECONDS.toNanos(30))
            helper.assertFalse(result.loadedBefore, "the far chunk should not be FULL before the drain")
            helper.assertTrue(result.drained, "the unload queue should drain (ran the requeueing task ${result.runs} times)")
            helper.assertTrue(result.loadedAfter, "the far chunk should load while draining")
            helper.assertFalse(result.leftInQueue, "the requeueing task should be gone")
            helper.assertTrue(result.runs >= 2, "the task should have requeued itself at least once (ran ${result.runs} times)")
        }.thenSucceed()
    }

    /**
     * [ChunkUnloadDrain.drain] が上限を過ぎて `false` を返したとき（チャンクが読み込まれないとき）も、
     * 積み直す仕事がアンロードの列に残らない（#543。残ると毎 tick と `stopServer` の `processUnloads` で回り続ける）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun requeueingTaskLeavesQueueWhenDrainTimesOut(helper: GameTestHelper) {
        val far = FarChunks.reserve(helper)
        // チケットを付けないので、チャンクは読み込まれず、上限（0）ですぐに諦める
        val result = drainWithRequeueing(helper.level.chunkSource, far, ticket = false, timeoutNanos = 0)
        helper.assertFalse(result.drained, "the drain should give up when the chunk never loads")
        helper.assertTrue(result.runs >= 1, "the requeueing task should have run (ran ${result.runs} times)")
        helper.assertFalse(result.leftInQueue, "the requeueing task should not be left in the unload queue")
        helper.succeed()
    }

    private class DrainResult(val loadedBefore: Boolean, val drained: Boolean, val loadedAfter: Boolean, val runs: Int, val leftInQueue: Boolean)

    /**
     * [far] が読み込まれるまで自分を積み直す仕事を列に入れて、[ChunkUnloadDrain.drain] で流す。
     * 結果がどうであれ、終わったら仕事を止めて列から外し、チケットも外す（テストが落ちても、列に何も残さない）。
     * [DrainResult.leftInQueue] は、その片付けの後に列に残っているか
     */
    private fun drainWithRequeueing(cache: ServerChunkCache, far: ChunkPos, ticket: Boolean, timeoutNanos: Long): DrainResult {
        // チケットの更新・世代のタスクの開始・メインスレッドの段は、どれもメインスレッドの仕事（pollTask）で進む
        if (ticket) cache.addRegionTicket(TICKET, far, 0, far)
        val loaded = { cache.getChunkNow(far.x, far.z) != null }
        val loadedBefore = loaded()
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
        return DrainResult(loadedBefore, drained, loadedAfter, runs, queue.contains(requeueing))
    }
}
