package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines
import pro.ikili.kibicomputers.neoforge.machine.WorldMachineSaves
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * GameTest 用: 離れたチャンク（強制読み込みを外したあと、だれも見ない場所）に置いたケースを、落とし物を残さずに片付ける（#746）。
 * `setBlock(AIR)` は `CaseBlock.onRemove` で部品を1スロットずつ落とす。そのまま強制読み込みを外すと、落とし物はチャンクと一緒に残り
 * （6000 tick）、あとで同じチャンクを読み込んだほかのテストの「落とし物を数える」確かめに出てくる。
 * 落とし物は同じ呼び出しの中で出て、次の tick まで動かないので、その位置の近くだけを消せば、ほかのテストの物には触れない
 */
internal fun removeCaseAndDrops(level: ServerLevel, pos: BlockPos) {
    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)
    for (e in level.getEntitiesOfClass(ItemEntity::class.java, AABB(pos).inflate(DROP_RADIUS))) e.discard()
}

/** [removeCaseAndDrops] で消す落とし物の範囲（ブロック）。`Containers.dropItemStack` はブロックの中から出すので 1 で足りるが、余裕を見る */
private const val DROP_RADIUS = 2.0

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
