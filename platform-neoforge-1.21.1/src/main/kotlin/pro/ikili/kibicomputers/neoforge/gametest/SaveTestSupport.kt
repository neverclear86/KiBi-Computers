package pro.ikili.kibicomputers.neoforge.gametest

import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines
import pro.ikili.kibicomputers.neoforge.machine.WorldMachineSaves
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
