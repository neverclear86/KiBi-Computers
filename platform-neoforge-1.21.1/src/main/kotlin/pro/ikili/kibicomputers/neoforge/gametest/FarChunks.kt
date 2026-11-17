package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GameTestListener
import net.minecraft.gametest.framework.GameTestRunner
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos

/**
 * GameTest 用: 「だれも読み込んでいない離れたチャンク」の割り当て役（#747）。
 *
 * 以前は各テストが「テストの場所から向き × 400 チャンク」などの慣習で場所を選んでいたが、テストの場所は GameTest の並び
 * （1 行 8 件、x に 8 ブロックずつ）で変わるので、同じ向きを使うテストどうしが列の関係しだいで同じチャンクを使った（#746）。
 * `ServerLevel.setChunkForced` は参照カウントではないので、相手が外すとこちらの強制読み込みも外れ、相手が読み込むとアンロードを待つ側が
 * 待ちきれない。そこで、サーバーの実行ごとに1つの基点と単調なカウンターを持ち、絶対の座標で [STRIDE] チャンクずつ離した場所（slot）を
 * 払い出す。
 *
 * - 基点は、最初に頼んだテストの起点のチャンクから [BASE_OFFSET] だけ x・z の両方にずらした所。GameTest の起点は実行ごとにランダム
 *   （`GameTestServer.startTests`）なので、前の実行の残りもの（ディスクのチャンク、`chunks.dat` の強制読み込み）を踏まない。
 *   GameTest の並び（x に数チャンク、z に十数チャンク）や、負荷試験の見る人の移動先（x に +62,500 チャンク）とも重ならない。
 * - slot は -x に [STRIDE] ずつ進み、使い回さない（アンロードは後から非同期に起き、落とし物やディスクのチャンクも残るため）。
 * - 強制読み込みのチャンクのチケットの段階は 31 ＋ 距離で、読み込まれたまま残るのは距離 13 まで。keeper（+x に [KEEPER_DISTANCE]）を
 *   含めても、隣の slot とは 50 チャンク以上離れる。
 * - 払い出すときに、そのチャンクがまだ読み込まれていないこと（FULL でない段階も含めて `ChunkHolder` がないこと。[isTracked]）、
 *   強制読み込みが新しく付いたことを確かめる。外れたら（重なったら）ずらさずに落とす。
 * - [allocate] は、テストが終わったとき（成功・失敗・タイムアウト）に強制読み込みを外す。途中の付け外しはテストに任せる。
 *
 * テストの関数・シーケンスはメインスレッドで走るので、カウンターは素の変数でよい
 */
internal object FarChunks {
    /** slot の間（チャンク）。keeper の 5 ＋ 読み込まれたまま残る距離 13 に、十分な余裕を足した値 */
    const val STRIDE = 64

    /** keeper（ケースのチャンクを FULL でないがアンロードもされない段階 36 に保つ強制読み込み）のケースのチャンクからの x の距離 */
    const val KEEPER_DISTANCE = 5

    /** 基点の、最初に頼んだテストの起点のチャンクからのずれ（x・z の両方） */
    const val BASE_OFFSET = -2000

    private val LOGGER = System.getLogger("kibicomputers.gametest")

    private var level: ServerLevel? = null
    private var base: ChunkPos? = null
    private var next = 0

    /**
     * 強制読み込みしない slot を1つ払い出す（場所だけが要るテスト向け。チケットはテストが付け外しする）。
     * まだ読み込まれていないことを確かめる
     */
    fun reserve(helper: GameTestHelper): ChunkPos {
        val level = helper.level
        if (this.level !== level) {
            // サーバー（ワールド）ごとに基点を決め直す
            this.level = level
            val origin = ChunkPos(helper.absolutePos(BlockPos.ZERO))
            base = ChunkPos(origin.x + BASE_OFFSET, origin.z + BASE_OFFSET)
            next = 0
        }
        val b = base!!
        val chunk = ChunkPos(b.x - next++ * STRIDE, b.z)
        LOGGER.log(System.Logger.Level.DEBUG) { "far chunk slot ${next - 1} at $chunk for ${helper.testInfo.testName}" }
        helper.assertTrue(!isTracked(level, chunk), "the far chunk $chunk should not be loaded yet")
        return chunk
    }

    /**
     * チャンク [chunk] の `ChunkHolder` があるか（どの段階でも読み込まれているか、アンロード待ちか）。
     * `ServerChunkCache.hasChunk` は FULL のチャンクでしか真にならず、ほかのチケットの縁で読み込まれた段階 34〜44 のチャンクを
     * 見落とすので、`ServerMachines.findHolder` と同じく、見える表・更新中の表・アンロード待ちの表を引く（AT で読めるようにしたもの）。
     * まだ処理されていないチケットの変化は数えない
     */
    private fun isTracked(level: ServerLevel, chunk: ChunkPos): Boolean {
        val map = level.chunkSource.chunkMap
        val key = chunk.toLong()
        return map.getVisibleChunkIfPresent(key) != null ||
            map.getUpdatingChunkIfPresent(key) != null ||
            map.pendingUnloads.containsKey(key)
    }

    /**
     * slot を1つ払い出し、強制読み込みにして読み込む。[keeper] なら、+x に [KEEPER_DISTANCE] 離れたチャンクも強制読み込みにする
     * （ケースのチャンクより先に付ける）。テストが終わったときに両方を外す
     */
    fun allocate(helper: GameTestHelper, keeper: Boolean = false): FarSlot {
        val chunk = reserve(helper)
        val level = helper.level
        val slot = FarSlot(level, chunk, if (keeper) ChunkPos(chunk.x + KEEPER_DISTANCE, chunk.z) else null)
        // 重なりを見つけたら、ほかのテストの強制読み込みを外さないように、自分が付けたものだけを外して落とす。
        // 終わりに外すリスナーは、付けるのに全部成功してから付ける（失敗するとテストの失敗でリスナーが走り、相手のものまで外れるため）
        slot.keeper?.let {
            helper.assertTrue(!isTracked(level, it), "the keeper chunk $it should not be loaded yet")
            helper.assertTrue(level.setChunkForced(it.x, it.z, true), "the keeper chunk $it should not be forced by anyone else")
        }
        if (!level.setChunkForced(chunk.x, chunk.z, true)) {
            slot.releaseKeeper()
            helper.fail("the far chunk $chunk should not be forced by anyone else")
        }
        helper.testInfo.addListener(
            object : GameTestListener {
                override fun testStructureLoaded(info: GameTestInfo) {}

                override fun testPassed(info: GameTestInfo, runner: GameTestRunner) = slot.release()

                override fun testFailed(info: GameTestInfo, runner: GameTestRunner) = slot.release()

                override fun testAddedForRerun(old: GameTestInfo, new: GameTestInfo, runner: GameTestRunner) {}
            },
        )
        level.getChunk(chunk.x, chunk.z)
        return slot
    }
}

/** [FarChunks.allocate] が払い出した離れたチャンク [chunk] と、あれば keeper のチャンク [keeper] */
internal class FarSlot(val level: ServerLevel, val chunk: ChunkPos, val keeper: ChunkPos?) {
    /** [chunk] の中のブロックの位置（[dx]・[dz] はチャンクの中の 0〜15） */
    fun pos(dx: Int, y: Int, dz: Int): BlockPos = BlockPos(chunk.minBlockX + dx, y, chunk.minBlockZ + dz)

    /** [chunk] を FULL にする（強制読み込みを付けて読み込む）・FULL でなくす（強制読み込みを外す） */
    fun setFull(full: Boolean) {
        level.setChunkForced(chunk.x, chunk.z, full)
        if (full) level.getChunk(chunk.x, chunk.z)
    }

    /** keeper の強制読み込みを外す（チャンクをアンロードさせる） */
    fun releaseKeeper() {
        keeper?.let { level.setChunkForced(it.x, it.z, false) }
    }

    /** 強制読み込みを両方外す。slot はほかと重ならないので、何度呼んでもよい */
    fun release() {
        level.setChunkForced(chunk.x, chunk.z, false)
        releaseKeeper()
    }
}
