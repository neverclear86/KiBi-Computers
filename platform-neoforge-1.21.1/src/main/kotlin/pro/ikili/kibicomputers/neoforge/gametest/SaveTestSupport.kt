package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import pro.ikili.kibicomputers.core.persist.CapturePolicy
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
 * GameTest 用: 書き込みのスレッドを止めておく仕事を差し込む（#84）。[close] で放す。同じ tick の中で放すこと
 * （ほかのテストの [ServerMachines.drainSaves] がメインスレッドで待つため。`use {}` で囲む）
 */
internal class WriterGate : AutoCloseable {
    private val latch = CountDownLatch(1)

    init {
        val writer = checkNotNull(WorldMachineSaves.writer) { "the server should have a save writer" }
        check(writer.execute(UUID.randomUUID()) { latch.await(GATE_LIMIT_SECONDS, TimeUnit.SECONDS) }) { "the save writer should accept a task" }
    }

    override fun close() {
        latch.countDown()
    }

    private companion object {
        /** 放し忘れても、書き込みのスレッドが止まりきらない上限 */
        const val GATE_LIMIT_SECONDS = 30L
    }
}
