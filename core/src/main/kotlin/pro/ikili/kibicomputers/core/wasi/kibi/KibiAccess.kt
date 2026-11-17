package pro.ikili.kibicomputers.core.wasi.kibi

import pro.ikili.kibicomputers.core.access.AccessRequest
import pro.ikili.kibicomputers.core.access.AccessRequestKind
import pro.ikili.kibicomputers.core.access.AccessRequestLimiter
import pro.ikili.kibicomputers.core.access.AccessPort
import pro.ikili.kibicomputers.core.access.AccessMember
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.fsError

/**
 * 独自拡張 `kibi:computer/access`（#110。ADR 0110）の意味。ABI の層に依らない部分。どれも待たない。
 *
 * - `get-access`: 今の持ち主・メンバー・保留（期限切れの保留は外して見せる。値は変えない）。承認待ちの依頼は見せない
 * - `request-add`・`request-remove`・`request-transfer`: 依頼を受け付ける（ok は「受け付けた」の意味。反映は、ゲーム内で持ち主か OP が
 *   承認したとき）。同期で返すエラーは `invalid`（形の誤り）・`not-supported`（口がない）・`again`（承認待ちが上限）だけ
 *
 * ワーカー（プロセスのスレッド）から呼ぶ。ワールド・ブロックエンティティには触らない: 読むのはポートの `@Volatile` の値（[AccessPort.view]）と
 * 設定（[AccessPort.requestRules]）と枠（[AccessPort.limiter]）だけ。受け付けた依頼は、マシンの通知の列
 * （[ProcessContext.postHostCallback]）でメインスレッドに渡し、[AccessPort.deliver] を呼ぶ（配る順を守り、メインスレッドの予算に乗る）
 */
internal object KibiAccess {
    /** 引数の文字列の上限（バイト。名前は 16、UUID は 36 なので余裕を見た値。超えたら `invalid`） */
    const val MAX_ARGUMENT_BYTES: Int = 64

    /** `get-access` の 1 件（持ち主・メンバー・保留のそれぞれ）あたりの燃料（仮の値。確定は #251。`list-mounts` と同じ） */
    const val ENTRY_FUEL: Long = KibiFs.MOUNT_LIST_FUEL_PER_ENTRY

    /** WIT の `access-state`。[ordinal] が判別子（unowned 0・owned 1・locked 2） */
    enum class State { UNOWNED, OWNED, LOCKED }

    /** `player`。[id] は UUID の小文字の正規形 */
    class Player(val name: String, val id: String)

    /** `access-info`（ABI に依らない形）。[invites] は保存している形（小文字） */
    class AccessInfo(val state: State, val owner: Player?, val members: List<Player>, val invites: List<String>)

    /** `get-access`。口がなければ `NOTSUP`。(1 + メンバー + 保留) × [ENTRY_FUEL] の燃料を引く */
    @Throws(FsException::class)
    fun getAccess(ctx: ProcessContext): AccessInfo {
        val port = port(ctx)
        val view = port.view()
        val access = view.access.pruneExpired(nowMillis(ctx), view.rules)
        val owner = access.owner
        val info = when {
            access.locked -> AccessInfo(State.LOCKED, null, emptyList(), emptyList())
            owner == null -> AccessInfo(State.UNOWNED, null, emptyList(), emptyList())
            else -> AccessInfo(State.OWNED, player(owner), access.members.map(::player), access.pending.map { it.name })
        }
        ctx.chargeFuel((1L + info.members.size + info.invites.size) * ENTRY_FUEL)
        return info
    }

    /**
     * `request-*`。口がなければ `NOTSUP`、形が違えば `INVAL`、このコンピューターの承認待ちが上限なら `AGAIN`。
     * 同じ種類・同じ引数の、期限内の依頼があれば、まとめて ok（通知もしない）
     */
    @Throws(FsException::class)
    fun request(ctx: ProcessContext, kind: AccessRequestKind, argument: String) {
        val port = port(ctx)
        val request = AccessRequest.parse(kind, argument) ?: fsError(Errno.INVAL, "名前か UUID の形が違う")
        when (port.limiter.tryReserve(request, nowMillis(ctx), port.requestRules())) {
            AccessRequestLimiter.Reserve.FULL -> fsError(Errno.AGAIN, "承認待ちの依頼が上限")
            AccessRequestLimiter.Reserve.MERGED -> Unit
            AccessRequestLimiter.Reserve.ACCEPTED -> ctx.postHostCallback { port.deliver(request) }
        }
    }

    private fun port(ctx: ProcessContext): AccessPort = ctx.environment.access ?: fsError(Errno.NOTSUP, "持ち主と権限の口がない")

    private fun nowMillis(ctx: ProcessContext): Long = ctx.environment.clock.realtimeNanos() / 1_000_000

    private fun player(m: AccessMember): Player = Player(m.name, m.id.toString())
}
