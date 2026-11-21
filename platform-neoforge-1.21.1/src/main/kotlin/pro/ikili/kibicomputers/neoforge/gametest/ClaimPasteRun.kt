package pro.ikili.kibicomputers.neoforge.gametest

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.persist.MachineOwner
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.core.persist.OwnerRecord
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.machine.WorldMachineSaves
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.util.Locale
import java.util.UUID

/**
 * claim の負荷の測定（#585。`runLoadTestServer -PloadTestClaimPaste=100,1000`）。ケースだけを [count] 台、1 tick で貼り、
 * 次の tick の `onLoad` で走る claim の重さを測る。場面は3つ:
 *
 * - 写し: 読み込まれている原本と同じ `MachineId` の NBT（全部 `NEW_ID_DUPLICATE_LOADED`）
 * - 知らない ID: このワールドにない UUID（`owner.bin` がないので `KEPT`）
 * - 開き直した後: 貼る位置を持ち主にした `owner.bin` を置いてから置き場を開き直し（再起動の後と同じく、覚えている `owner.bin` が空）、
 *   先読みが終わるのを待ってから、その ID を貼る（先読みの値で `KEPT`）
 * - 開き直した直後（#869）: 同じく開き直し、先読みを待たずに同じ tick に貼る。先読みが読み終えた ID はその場で決まり、未読の ID は
 *   claim を待ちにして（メインスレッドで読まない）、先読みに先に読んでもらってから後の tick に確定する。直前に書いた `owner.bin` は
 *   OS のキャッシュに載っていて先読みがすぐ終わりうるので、貼った時点で先読みが終わっていたかも表に出す
 *
 * 部品を入れないので電源は入らず、復元は走らない。結果は Markdown の表でログに出す。
 */
internal class ClaimPasteRun(private val helper: GameTestHelper, private val count: Int) {
    private enum class Scene(val label: String) {
        DUPLICATE("写し（読み込まれている原本と同じ ID）"),
        UNKNOWN("知らない ID（owner.bin なし）"),
        REOPENED("開き直した後（owner.bin あり、持ち主が自分）"),
        REOPENED_NO_WAIT("開き直した直後（先読みを待たない）"),
    }

    private class Row(
        val scene: Scene,
        val claims: Long,
        val claimMillis: Double,
        val reads: Long,
        val readMillis: Double,
        val prefetchHits: Long,
        val maxTickMillis: Double,
        val pendingAfter: Int,
        val drainMillis: Double,
        val results: Map<String, Int>,
        val deferred: Long,
        val maxDeferredMillis: Double,
        val prefetchRequests: Long,
        val writerReads: Long,
        val prefetchDoneAtPaste: Boolean,
    )

    private val level = helper.level
    private val columns = LoadGameTests.Layout.columns(count)
    private val positions = List(count) { LoadGameTests.Layout.casePos(it, columns) }

    /** 原本（写しの場面）。貼る範囲の外に置く */
    private val originalPos = BlockPos(0, 1, -2)
    private val forced = ArrayList<ChunkPos>()
    private val rows = ArrayList<Row>()

    private var scene = 0
    private var step = Step.SETUP
    private var waitTicks = 0
    private var original: CaseBlockEntity? = null
    private var pasted: List<CaseBlockEntity> = emptyList()
    private var before = Counters.ZERO
    private var tickMax = 0L
    private var lastTick = 0L
    private var reopenedIds: List<UUID> = emptyList()
    private var prefetchWaitStart = 0L
    private var prefetchDoneAtPaste = true
    private var pasteNanos = 0L

    private enum class Step { SETUP, WAIT_PREFETCH, PASTE, WAIT_CLAIMS, CLEAR, DONE }

    private data class Counters(
        val claims: Long,
        val claimNanos: Long,
        val reads: Long,
        val readNanos: Long,
        val prefetchHits: Long,
        val deferred: Long,
        val prefetchRequests: Long,
        val writerReads: Long,
    ) {
        companion object {
            val ZERO = Counters(0, 0, 0, 0, 0, 0, 0, 0)

            fun of(s: MachineSaveStore) = Counters(
                s.claimCount, s.claimNanos, s.mainOwnerReads, s.mainOwnerReadNanos, s.prefetchHits, s.deferredClaims, s.prefetchRequests,
                s.writerOwnerReadRequests,
            )
        }
    }

    fun start() {
        forceChunks()
        // 原本: 知らない ID の NBT を読ませる（次の tick に KEPT で読み込まれる）
        original = place(originalPos, UUID.randomUUID())
        helper.onEachTick(::tick)
    }

    private fun store(): MachineSaveStore = WorldMachineSaves.current ?: error("no machine save store")

    private fun tick() {
        // 試験の呼び出しの間隔（20 TPS なら 50 ms。claim が重い tick はその分だけ延びる）
        val now = System.nanoTime()
        if (lastTick != 0L) tickMax = maxOf(tickMax, now - lastTick)
        lastTick = now
        when (step) {
            Step.SETUP -> {
                if (Scene.entries[scene] == Scene.DUPLICATE && original?.lastClaim == null) return
                if (Scene.entries[scene] == Scene.REOPENED) {
                    prepareReopen()
                    prefetchWaitStart = System.nanoTime()
                    step = Step.WAIT_PREFETCH
                    waitTicks = 0
                } else if (Scene.entries[scene] == Scene.REOPENED_NO_WAIT) {
                    // 開き直したのと同じ tick に貼る（先読みを待たない。#869）
                    prepareReopen()
                    paste()
                } else {
                    step = Step.PASTE
                }
            }
            Step.WAIT_PREFETCH -> {
                // 再起動の後は、ワールドの読み込みの間に先読みが進む。ここでは終わるのを待ってから貼る（上限 30 秒）
                if (!store().ownerPrefetchDone && System.nanoTime() - prefetchWaitStart < 30_000_000_000L) return
                step = Step.PASTE
            }
            Step.PASTE -> paste()
            Step.WAIT_CLAIMS -> {
                waitTicks++
                if (pasted.any { it.lastClaim == null }) {
                    // 待ちにした claim は、先読み・書き込みのスレッドの読みを待つ（#869）。GameTest のサーバーは tick を待たずに回すので、時間でも見る
                    if (waitTicks > 200 && System.nanoTime() - pasteNanos > 60_000_000_000L) helper.fail("claims did not run within 200 ticks and 60 s")
                    return
                }
                record()
                clear()
                step = Step.CLEAR
                waitTicks = 0
            }
            Step.CLEAR -> {
                if (++waitTicks < 3) return
                scene++
                if (scene >= Scene.entries.size) {
                    finish()
                    step = Step.DONE
                } else {
                    step = Step.SETUP
                }
            }
            Step.DONE -> Unit
        }
    }

    private fun place(rel: BlockPos, id: UUID): CaseBlockEntity {
        helper.setBlock(rel, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        val be = helper.getBlockEntity<CaseBlockEntity>(rel)
        val tag = be.saveWithoutMetadata(level.registryAccess())
        tag.putString(CaseBlockEntity.MACHINE_ID_TAG, id.toString())
        tag.putLong(CaseBlockEntity.SAVE_GENERATION_TAG, 0)
        tag.putLong(CaseBlockEntity.OWNER_EPOCH_TAG, 0)
        be.loadWithComponents(tag, level.registryAccess())
        return be
    }

    private fun ownerAt(rel: BlockPos): MachineOwner {
        val abs = helper.absolutePos(rel)
        return MachineOwner(level.dimension().location().toString(), abs.x, abs.y, abs.z)
    }

    /** 置き場を開き直す前に、貼る位置を持ち主にした owner.bin を置く（原本は外してから開き直し、開き直した後に置き直す） */
    private fun prepareReopen() {
        helper.setBlock(originalPos, Blocks.AIR)
        val root = store().root
        reopenedIds = List(count) { UUID.randomUUID() }
        for (i in 0 until count) {
            OwnerRecord.write(root.resolve(reopenedIds[i].toString()).resolve(OwnerRecord.FILE_NAME), OwnerRecord(0, ownerAt(positions[i])))
        }
        WorldMachineSaves.reopenForTest()
        original = null
    }

    private fun paste() {
        val s = Scene.entries[scene]
        val ids = when (s) {
            Scene.DUPLICATE -> List(count) { original!!.identity!!.id }
            Scene.UNKNOWN -> List(count) { UUID.randomUUID() }
            Scene.REOPENED, Scene.REOPENED_NO_WAIT -> reopenedIds
        }
        before = Counters.of(store())
        prefetchDoneAtPaste = store().ownerPrefetchDone
        pasteNanos = System.nanoTime()
        tickMax = 0
        lastTick = System.nanoTime()
        pasted = positions.mapIndexed { i, rel -> place(rel, ids[i]) }
        step = Step.WAIT_CLAIMS
        waitTicks = 0
    }

    private fun record() {
        val st = store()
        val after = Counters.of(st)
        val pendingAfter = st.pendingCount
        val drainStart = System.nanoTime()
        st.flush(120_000)
        val drainMillis = (System.nanoTime() - drainStart) / 1e6
        st.pollCompleted()
        val results = pasted.groupingBy { it.lastClaim.toString() }.eachCount().toSortedMap()
        rows += Row(
            Scene.entries[scene],
            after.claims - before.claims,
            (after.claimNanos - before.claimNanos) / 1e6,
            after.reads - before.reads,
            (after.readNanos - before.readNanos) / 1e6,
            after.prefetchHits - before.prefetchHits,
            tickMax / 1e6,
            pendingAfter,
            drainMillis,
            results,
            after.deferred - before.deferred,
            // 最大は置き場ごとの値なので、この場面で待ちにしたときだけ出す
            if (after.deferred > before.deferred) st.maxDeferredClaimNanos / 1e6 else 0.0,
            after.prefetchRequests - before.prefetchRequests,
            after.writerReads - before.writerReads,
            prefetchDoneAtPaste,
        )
        LOGGER.info("Claim paste ({} cases): {} done", count, Scene.entries[scene])
    }

    private fun clear() {
        for (rel in positions) helper.setBlock(rel, Blocks.AIR)
        pasted = emptyList()
    }

    private fun forceChunks() {
        val a = helper.absolutePos(BlockPos(0, 0, -2))
        val b = helper.absolutePos(BlockPos(columns * LoadGameTests.Layout.STEP_X, 0, (count / columns + 1) * LoadGameTests.Layout.STEP_Z))
        for (cx in (minOf(a.x, b.x) shr 4)..(maxOf(a.x, b.x) shr 4)) {
            for (cz in (minOf(a.z, b.z) shr 4)..(maxOf(a.z, b.z) shr 4)) {
                if (level.setChunkForced(cx, cz, true)) forced += ChunkPos(cx, cz)
            }
        }
    }

    private fun finish() {
        original?.let { helper.setBlock(originalPos, Blocks.AIR) }
        for (chunk in forced) level.setChunkForced(chunk.x, chunk.z, false)
        val sb = StringBuilder()
        sb.append(
            "| 場面 | claim | claim の合計 (ms) | メインスレッドの読み（回 / ms） | 先読みの値を使った | 待ちにした | 確定までの最大 (ms) | " +
                "先読みに頼んだ / 書き込みのスレッドに頼んだ | 貼った時点で先読みが終わっていた | tick の間隔の最大 (ms) | 直後の書き込み待ち | " +
                "流しきるまで (ms) | 判定 |\n",
        )
        sb.append("| --- | --: | --: | --: | --: | --: | --: | --: | --- | --: | --: | --: | --- |\n")
        for (r in rows) {
            sb.append(
                String.format(
                    Locale.ROOT, "| %s | %d | %.1f | %d / %.1f | %d | %d | %.1f | %d / %d | %s | %.1f | %d | %.0f | %s |\n",
                    r.scene.label, r.claims, r.claimMillis, r.reads, r.readMillis, r.prefetchHits, r.deferred, r.maxDeferredMillis,
                    r.prefetchRequests, r.writerReads, if (r.prefetchDoneAtPaste) "はい" else "いいえ", r.maxTickMillis, r.pendingAfter,
                    r.drainMillis, r.results,
                ),
            )
        }
        LOGGER.info("Claim paste ({} cases)\n{}", count, sb)
        helper.succeed()
    }

    companion object {
        private val LOGGER = LogUtils.getLogger()

        /** 測る台数（カンマ区切り）を渡すシステムプロパティ（`-PloadTestClaimPaste`） */
        const val PROPERTY: String = "kibicomputers.loadTest.claimPaste"
    }
}
