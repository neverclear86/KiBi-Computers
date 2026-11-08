package pro.ikili.kibicomputers.neoforge.machine

import com.mojang.logging.LogUtils
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerChunkCache
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * サーバーの停止の前に、チャンクのアンロードの列（`ChunkMap.unloadQueue`）を流しきる（#540）。
 *
 * バニラの `ChunkMap.scheduleUnload` が積む仕事は、チャンクの保存の準備ができていない（`isReadyForSaving` が偽）と、自分を積み直す。
 * 列に仕事が残ったまま（tick の時間が足りないと、列は 2,000 件を超えるまで流れない）チャンクが読み込み直され、
 * まわりのチャンクの世代のタスク（`ChunkGenerationTask`）がそれを参照している間は、その仕事が積み直され続ける。
 * 世代のタスクは、チャンクの読み込みなどの段をメインスレッドで進めるので、ふつうの tick なら時間切れで抜けて、いずれ進む。
 * ところが `MinecraftServer.stopServer` は `processUnloads(() -> true)` で列を流すので、積み直された仕事を回し続けて抜けず、
 * 世代のタスクも進まない（サーバーの停止が終わらない）。
 *
 * ここでは [net.neoforged.neoforge.event.server.ServerStoppingEvent]（`stopServer` の前）で、列にある分を1回ずつ流し、
 * 残ったら、ディメンションのメインスレッドの仕事（[ServerChunkCache.pollTask]: チケットの更新、世代のタスクの開始と、
 * メインスレッドで進む段）を進める、を列が空になるまで繰り返す。積み直された仕事は、世代のタスクが終わって参照が外れれば、
 * 何もせずに終わる（読み込み直されたチャンクは、アンロードを待つ表にもういない）。上限（[timeoutMillis]）を過ぎたら諦める。
 *
 * 列が流れないのは、tick が重くて時間が残らないとき（同期の書き込みが続くアンロード。#84 で軽くする）に起きやすい。
 */
internal object ChunkUnloadDrain {
    private val LOGGER = LogUtils.getLogger()

    /** 停止の前に流しきるのを待つ上限の既定値 */
    const val DEFAULT_TIMEOUT_MILLIS: Long = 30_000

    /** 何も進まなかったときに、ほかのスレッド（世代のタスク・読み書き）を待つ1回の時間 */
    private val IDLE_NANOS = TimeUnit.MILLISECONDS.toNanos(1)

    /**
     * [server] の全ディメンションの列を流しきる。メインスレッドで呼ぶ。
     *
     * @return 全部の列が空になった
     */
    fun drainAll(server: MinecraftServer, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        var drained = true
        for (level in server.allLevels) {
            val cache = level.chunkSource
            val queued = cache.chunkMap.unloadQueue.size
            if (queued == 0) continue
            val start = System.nanoTime()
            val ok = drain(cache, deadline)
            LOGGER.info(
                "Ran {} queued chunk unloads in {} before the server stop ({} ms)",
                queued,
                level.dimension().location(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
            )
            if (!ok) {
                LOGGER.warn(
                    "Chunk unloads in {} did not finish within {} ms before the server stop ({} left)",
                    level.dimension().location(),
                    timeoutMillis,
                    cache.chunkMap.unloadQueue.size,
                )
                drained = false
            }
        }
        return drained
    }

    /**
     * [cache] の列を、空になるか [deadline]（`System.nanoTime` の値）を過ぎるまで流す。メインスレッドで呼ぶ。
     *
     * @return 列が空になった
     */
    fun drain(cache: ServerChunkCache, deadline: Long): Boolean {
        val queue = cache.chunkMap.unloadQueue
        while (true) {
            // 今ある分だけ流す（積み直された分は、メインスレッドの仕事を進めてから流す）
            for (i in 0 until queue.size) {
                val task = queue.poll() ?: break
                task.run()
            }
            if (queue.isEmpty()) return true
            var progressed = false
            while (cache.pollTask()) progressed = true
            if (queue.isEmpty()) return true
            if (System.nanoTime() >= deadline) return false
            if (!progressed) LockSupport.parkNanos(IDLE_NANOS)
        }
    }
}
