package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GameTestListener
import net.minecraft.gametest.framework.GameTestRunner
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines
import pro.ikili.kibicomputers.neoforge.machine.WorldMachineSaves
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * GameTest 用: 離れたチャンク（強制読み込みを外したあと、だれも見ない場所）に置いたケースを、落とし物を出さずに片付ける（#746）。
 * `setBlock(AIR)` は `CaseBlock.onRemove` で部品を1スロットずつ落とす。そのまま強制読み込みを外すと、落とし物はチャンクと一緒に残り
 * （6000 tick）、あとで同じチャンクを読み込んだほかのテストの「落とし物を数える」確かめに出てくる。
 * 落とした後に探して消す方式は、エンティティのセクションがまだ見えない（チャンクの FULL への昇格が後からメインスレッドに積まれる）と
 * 落とし物が検索に出ず、消せないまま残る（実際に 0 件だった）。そのため、ブロックを消す前に部品を空にして、落とす物をなくす
 */
internal fun removeCaseWithoutDrops(level: ServerLevel, pos: BlockPos) {
    val be = checkNotNull(level.getBlockEntity(pos) as? CaseBlockEntity) { "there should be a case at $pos" }
    for (i in 0 until be.parts.slots) be.parts.setStackInSlot(i, ItemStack.EMPTY)
    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)
}

/**
 * GameTest 用: [be] を取り出して書き込みに回し、書き込みのスレッドが空になるのを待って完了を受け取る（#84 の前の同期の書き込みと同じに見える）
 *
 * @return 予約した書き込みの回数（世代のファイルと、振ったときの `owner.bin`）。取り出さなければ 0
 */
internal fun saveNow(be: CaseBlockEntity, policy: CapturePolicy): Int {
    val writes = be.captureAndSubmit(policy)?.writes ?: 0
    ServerMachines.drainSaves()
    return writes
}

/**
 * GameTest 用: 書き込みのスレッドを止めておく仕事を差し込む（#84）。[close] で放す。
 *
 * [helper] を渡さないときは、同じ tick の中で放すこと（ほかのテストの [ServerMachines.drainSaves] がメインスレッドで待つため。`use {}` で囲む）。
 * 放し忘れても、壁時計の [SHORT_LIMIT_SECONDS] で書き込みのスレッドが動き出す。
 *
 * [helper] を渡すと、tick をまたいで止めてよい（#854）。テストが終わったとき（成功・失敗・タイムアウト）に必ず放すので、
 * 上限は、重いマシンで tick の待ちより先に切れない長さ（[HELD_LIMIT_SECONDS]）にする。tick を待つ間に壁時計の上限でゲートが勝手に開くと、
 * 完了が先に届いて「止めている間は完了しない」の確かめが落ちる。ほかのテストと同じバッチにしない（並ぶテストの [ServerMachines.drainSaves] が待つ）
 */
internal class WriterGate(helper: GameTestHelper? = null) : AutoCloseable {
    private val latch = CountDownLatch(1)

    init {
        val writer = checkNotNull(WorldMachineSaves.writer) { "the server should have a save writer" }
        val limit = if (helper == null) SHORT_LIMIT_SECONDS else HELD_LIMIT_SECONDS
        check(writer.execute(UUID.randomUUID()) { latch.await(limit, TimeUnit.SECONDS) }) { "the save writer should accept a task" }
        helper?.testInfo?.addListener(
            object : GameTestListener {
                override fun testStructureLoaded(info: GameTestInfo) {}

                override fun testPassed(info: GameTestInfo, runner: GameTestRunner) = close()

                override fun testFailed(info: GameTestInfo, runner: GameTestRunner) = close()

                override fun testAddedForRerun(old: GameTestInfo, new: GameTestInfo, runner: GameTestRunner) {}
            },
        )
    }

    override fun close() {
        latch.countDown()
    }

    private companion object {
        /** 同じ tick の中で放すゲートを、放し忘れても書き込みのスレッドが止まりきらない上限 */
        const val SHORT_LIMIT_SECONDS = 30L

        /** テストの終わりに必ず放すゲートの、念のための上限 */
        const val HELD_LIMIT_SECONDS = 1_800L
    }
}

/**
 * GameTest 用: 列の外の `owner.bin` の読み手（#877）を止めておく。[WriterGate] は書き込みのスレッドだけを止めるので、列の外の読み手に回る claim
 * （その ID の仕事が列にない ID）の読みは止まらない。claim の確定を tick をまたいで待たせたいテストが、[WriterGate] と並べて使う。
 * 読み手は、差し込んだ読みの口で [close] まで待つ（読みはそのあとで走る）。[close] で放して、口を元に戻す。放し忘れても、
 * テストが終わったとき（成功・失敗・タイムアウト）に放す
 */
internal class SideReaderGate(helper: GameTestHelper, private val store: MachineSaveStore) : AutoCloseable {
    private val latch = CountDownLatch(1)
    private val original = store.sideOwnerReader

    init {
        store.sideOwnerReader = { path ->
            latch.await(HELD_LIMIT_SECONDS, TimeUnit.SECONDS)
            original(path)
        }
        helper.testInfo.addListener(
            object : GameTestListener {
                override fun testStructureLoaded(info: GameTestInfo) {}

                override fun testPassed(info: GameTestInfo, runner: GameTestRunner) = close()

                override fun testFailed(info: GameTestInfo, runner: GameTestRunner) = close()

                override fun testAddedForRerun(old: GameTestInfo, new: GameTestInfo, runner: GameTestRunner) {}
            },
        )
    }

    override fun close() {
        latch.countDown()
        // 差し込んだままにしない（ほかのテストの読みが、放した後の口を通るだけで済むように、元に戻す）
        store.sideOwnerReader = original
    }

    private companion object {
        /** テストの終わりに必ず放すゲートの、念のための上限（[WriterGate] と同じ） */
        const val HELD_LIMIT_SECONDS = 1_800L
    }
}

/**
 * GameTest 用: [level] のチャンクのアンロードの列（`ChunkMap.unloadQueue`）に今ある仕事を1回ずつ流す（#854）。
 * バニラの `processUnloads` は、tick に時間が残らないと列を流さない（2,000 件を超えるまで溜める）ので、重いマシンではアンロードを待つテストが
 * 何 tick 待っても進まない。tick に時間が残っていたのと同じことを、テストの中で起こす。
 * 積み直された仕事（#540）は流さずに次の呼び出しに回し、メインスレッドの仕事（`pollTask`）も進めない（ほかの仕事は tick に任せる）。
 * 列の仕事はそのディメンションの全部のチャンクのものなので、並ぶほかのテストのアンロードも早まる（tick に時間が残っていたときと同じで、バニラの範囲の振る舞い）
 */
internal fun runQueuedUnloads(level: ServerLevel) {
    val queue = level.chunkSource.chunkMap.unloadQueue
    repeat(queue.size) { (queue.poll() ?: return).run() }
}
