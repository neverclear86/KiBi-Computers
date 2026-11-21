package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.access.AccessRequest
import pro.ikili.kibicomputers.core.access.AccessRequestKind
import pro.ikili.kibicomputers.core.access.AccessRequestLimiter
import pro.ikili.kibicomputers.core.access.PlayerAccess
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.neoforge.computer.AccessRequests
import pro.ikili.kibicomputers.neoforge.computer.CaseAccess
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks

/**
 * チャンクが FULL でない（#545 の降格中）ケースへの依頼（#110、ADR 0110）。approve・deny・`list` は、チャンクを読み込まず
 * （`isLoaded` のまま）、表にも枠にも触らず、「依頼がない」と同じ文言を返す。FULL に戻れば、同じ ID を承認できる。
 *
 * 降格の作り方は `MachineSaveGameTests.demotedChunkSavesAndStopsTheMachine` と同じ（離れたチャンクと keeper のチャンクを強制読み込みし、
 * ケースを置いて `onLoad` を待ってから外す）。マシンは動かさない
 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object AccessDemotionGameTests {
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 600)
    fun demotedCasesKeepTheirRequests(helper: GameTestHelper) {
        val level = helper.level
        val alex = AccessRequestGameTests.player(helper, "Alex110z")
        val stranger = AccessRequestGameTests.player(helper, "Stranger110z")
        val op = AccessRequestGameTests.player(helper, "Op110z", op = true)
        // だれも読み込んでいない離れたチャンク（#747）
        val slot = FarChunks.allocate(helper, keeper = true)
        val pos = slot.pos(3, 200, 7)
        AccessRequestGameTests.onEnd(helper) {
            if (level.getBlockEntity(pos) is CaseBlockEntity) removeCaseWithoutDrops(level, pos)
            slot.release()
        }
        level.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get().defaultBlockState(), 3)
        val be = level.getBlockEntity(pos) as CaseBlockEntity
        be.replacePlayerAccess(PlayerAccess.ownedBy(CaseAccess.ref(alex)))
        // onLoad と、getChunk が付けた一時のチケット（UNKNOWN）が外れるのを待ってから、依頼・降格・確かめを1つの tick の中で行う（#854）。
        // 依頼の期限は壁時計（既定 300 秒）なので、重いマシンで tick をまたぐと、確かめる前に期限で消えた。
        // 降格はチケットの更新（runDistanceManagerUpdates）で起き、AccessRequests は isLoaded しか見ないので、tick の終わりの処理を待たなくてよい
        helper.runAfterDelay(2) {
            helper.assertTrue(be in ServerMachines.trackedCases(), "the case should be tracked after onLoad")
            val id = AccessRequestGameTests.request(be, AccessRequestKind.ADD, "Bob110z")!!
            slot.setFull(false)
            level.chunkSource.runDistanceManagerUpdates()
            helper.assertTrue(!be.isRemoved, "the chunk should stay in memory (the block entity was removed)")
            helper.assertTrue(!level.isLoaded(pos), "the chunk should no longer be FULL")
            val before = be.playerAccess
            for (p in listOf(alex, stranger, op)) p.clear()
            for (p in listOf(alex, stranger)) {
                AccessRequestGameTests.command(helper, p, "approve $id")
                AccessRequestGameTests.command(helper, p, "deny $id")
            }
            for (p in listOf(alex, op)) AccessRequestGameTests.command(helper, p, "list")
            val unknown = List(2) { AccessRequests.KEY_UNKNOWN }
            helper.assertTrue(alex.keys().take(2) == unknown && stranger.keys() == unknown, "a demoted case should answer like an unknown request: ${alex.messages} ${stranger.messages}")
            // OP の一覧には、同時に動くほかのテストの依頼が出うるので、この依頼が出ないことだけを見る
            helper.assertTrue(alex.keys().drop(2) == listOf(AccessRequests.KEY_LIST_EMPTY), "a demoted case should not be listed: ${alex.messages}")
            helper.assertTrue(id !in op.listedIds() && op.messages.isNotEmpty(), "a demoted case should not be listed: ${op.messages}")
            helper.assertTrue(AccessRequests.peek(id) != null, "the request should stay")
            helper.assertTrue(be.playerAccess === before, "nothing should change: ${be.playerAccess}")
            helper.assertTrue(be.accessPort.limiter.size == 1, "the slot should stay")
            helper.assertTrue(!level.isLoaded(pos), "the commands should not load the chunk")

            // FULL に戻すと、同じ BE で、同じ ID を承認でき、枠が返る
            slot.setFull(true)
            helper.assertTrue(level.isLoaded(pos), "the chunk should be FULL again")
            helper.assertTrue(level.getBlockEntity(pos) === be, "the block entity should stay the same")
            alex.clear()
            AccessRequestGameTests.command(helper, alex, "approve $id")
            helper.assertTrue(AccessRequests.KEY_APPROVED_ADD in alex.keys(), "the owner should approve after promotion: ${alex.messages}")
            helper.assertTrue(be.playerAccess.pending.map { it.name } == listOf("bob110z"), "${be.playerAccess}")
            val next = be.accessPort.limiter.tryReserve(AccessRequest.parse(AccessRequestKind.ADD, "Bob110z")!!, System.currentTimeMillis(), be.accessPort.requestRules())
            helper.assertTrue(next == AccessRequestLimiter.Reserve.ACCEPTED, "the slot should be released: $next")
            helper.succeed()
        }
    }
}
