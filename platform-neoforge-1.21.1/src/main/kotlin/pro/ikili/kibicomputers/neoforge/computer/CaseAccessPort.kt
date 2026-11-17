package pro.ikili.kibicomputers.neoforge.computer

import pro.ikili.kibicomputers.core.access.AccessPort
import pro.ikili.kibicomputers.core.access.AccessRequest
import pro.ikili.kibicomputers.core.access.AccessRequestLimiter
import pro.ikili.kibicomputers.core.access.AccessRequestRules
import pro.ikili.kibicomputers.core.access.AccessView

/**
 * ケース（[owner]）の `kibi:computer/access` の口（#110、ADR 0110）。BE が1つ持ち（[CaseBlockEntity.accessPort]）、作り直したマシンにも
 * 同じインスタンスを渡すので、再起動をまたいでも枠（[limiter]）は続く。保存しない（BE を読み込み直すと新しい枠になる）。
 *
 * - [view]・[requestRules]・[limiter] の `tryReserve` は、プロセスのスレッドから呼ばれる。ワールドに触らず、`@Volatile` の
 *   [CaseBlockEntity.playerAccess] とコンフィグ（[CaseAccess.requestRules]）を読むだけ
 * - [deliver] はメインスレッド（マシンの通知の列）から呼ばれる。BE をそのまま使い、`level.getBlockEntity` は呼ばない（チャンクを読み込まない）
 */
internal class CaseAccessPort(private val owner: CaseBlockEntity) : AccessPort {
    override val limiter: AccessRequestLimiter = AccessRequestLimiter()

    override fun view(): AccessView = AccessView(owner.playerAccess, CaseAccess.rules())

    override fun requestRules(): AccessRequestRules = CaseAccess.requestRules()

    override fun deliver(request: AccessRequest) {
        AccessRequests.deliver(owner, request)
    }
}
