package pro.ikili.kibicomputers.core.access

import org.jetbrains.annotations.ApiStatus

/**
 * OS からの依頼（[AccessRequest]）を、ゲーム内で承認・拒否するときの判定と反映（#110、ADR 0110）。MC に依らない。
 * 権限は、依頼のときではなく、承認・拒否のときの値（[PlayerAccess]）で判定する
 */
@ApiStatus.Internal
public object AccessApproval {
    /** [apply] の結果の種類 */
    public enum class Outcome {
        /** 反映した（[Decision.access] が新しい値） */
        APPLIED,

        /** 承認する権限がない（[mayDecide] が `false`） */
        NOT_PERMITTED,

        /** 反映に要る相手（持ち主の変更の相手）が分からない */
        TARGET_OFFLINE,

        /** 権限はあるが、何も変わらなかった（もう入っている・上限・見つからない・持ち主なし・読めなかった記録。理由は [Decision.change]） */
        NO_CHANGE,
    }

    /**
     * [apply] の結果。[access] は新しい値（[Outcome.APPLIED] のときだけ元と違う）。[change] は反映・変わらなかった理由
     * （持ち主の変更を反映したときは `null`）
     */
    public class Decision(public val access: PlayerAccess, public val outcome: Outcome, public val change: AccessChange?)

    /**
     * [actor] がこの依頼を承認・拒否してよいか（approve・deny・一覧の絞り込みが共通で使う）。
     * - [current] が持ち主なし（読めなかった記録ではない）で、依頼が持ち主の変更: [actor] が相手本人（[target] の UUID）のときだけ。OP も代われない
     *   （他人に台数を押し付けないため）
     * - それ以外: [PlayerAccess.isManager]（持ち主ありなら持ち主か OP、読めなかった記録なら OP だけ。ADR 0131 の表）
     */
    @JvmStatic
    public fun mayDecide(current: PlayerAccess, request: AccessRequest, target: PlayerRef?, actor: PlayerRef, actorIsOp: Boolean): Boolean =
        if (!current.locked && current.owner == null && request.kind == AccessRequestKind.TRANSFER) {
            target != null && actor.id == target.id
        } else {
            current.isManager(actor, actorIsOp)
        }

    /**
     * 依頼を反映する。[target] は届けたときに固定した相手（持ち主の変更・名前でのメンバーの削除。いなければ `null`）。
     * - 追加: [PlayerAccess.addPending]（メンバーに直接は足さない。本人のアクセスで成立）
     * - 削除: UUID ならそのメンバーを、名前ならその名前の保留を外し、加えて [target] がメンバーならその UUID も外す
     * - 持ち主の変更: [PlayerAccess.transferOwner]。[target] がなければ [Outcome.TARGET_OFFLINE]、もう持ち主なら [Outcome.NO_CHANGE]
     * 読めなかった記録の値を変えずに返した結果（追加・削除）は [Outcome.NO_CHANGE]（`LOCKED`）
     */
    @JvmStatic
    public fun apply(
        current: PlayerAccess,
        request: AccessRequest,
        target: PlayerRef?,
        approver: PlayerRef,
        approverIsOp: Boolean,
        nowMillis: Long,
        rules: AccessRules,
    ): Decision {
        if (!mayDecide(current, request, target, approver, approverIsOp)) return Decision(current, Outcome.NOT_PERMITTED, null)
        return when (request.kind) {
            AccessRequestKind.ADD -> {
                val r = current.addPending(request.argument, nowMillis, rules)
                if (r.change == AccessChange.ADDED) Decision(r.access, Outcome.APPLIED, r.change) else Decision(current, Outcome.NO_CHANGE, r.change)
            }
            AccessRequestKind.REMOVE -> remove(current, request, target)
            AccessRequestKind.TRANSFER -> when {
                target == null -> Decision(current, Outcome.TARGET_OFFLINE, null)
                !current.locked && current.owner?.id == target.id -> Decision(current, Outcome.NO_CHANGE, AccessChange.ALREADY_PRESENT)
                else -> Decision(current.transferOwner(target), Outcome.APPLIED, null)
            }
        }
    }

    private fun remove(current: PlayerAccess, request: AccessRequest, target: PlayerRef?): Decision {
        val first = current.removeMember(request.argument)
        if (first.change == AccessChange.LOCKED) return Decision(current, Outcome.NO_CHANGE, AccessChange.LOCKED)
        var next = first.access
        // 名前での削除: その名前で今オンラインのプレイヤー（届けたときに固定した UUID）がメンバーなら、それも外す
        if (request.uuidArgument == null && target != null && next.members.any { it.id == target.id }) {
            next = next.removeMember(target.id.toString()).access
        }
        return if (next === current) {
            Decision(current, Outcome.NO_CHANGE, AccessChange.NOT_FOUND)
        } else {
            Decision(next, Outcome.APPLIED, AccessChange.REMOVED)
        }
    }
}

/**
 * サーバー全体の承認待ちの依頼の表（ID → 依頼。#110、ADR 0110）。ID は 1 からの連番。大きさに上限があり、期限切れは
 * 追加・取り出し・一覧のときに遅れて捨てる（tick ごとには見回らない）。保存しない。
 * スレッド: サーバーのメインスレッドだけで使う
 *
 * @param T 依頼を出したもの（接続層のブロックエンティティの弱い参照など）
 */
@ApiStatus.Internal
public class AccessRequestBook<T : Any>(private val maxSize: Int) {
    init {
        require(maxSize > 0) { "maxSize must be positive: $maxSize" }
    }

    /** 表の1件。[target] は届けたときに固定した相手（持ち主の変更・名前での削除）、[createdMillis] は届けた時刻 */
    public class Entry<T>(
        public val id: Long,
        public val holder: T,
        public val request: AccessRequest,
        public val target: PlayerRef?,
        public val createdMillis: Long,
    )

    private val entries = LinkedHashMap<Long, Entry<T>>()
    private var nextId = 1L

    /** 今の件数（期限切れを含む） */
    public val size: Int get() = entries.size

    /** 足す。期限切れを捨ててから数え、上限に達していたら足さずに `null` */
    public fun add(holder: T, request: AccessRequest, target: PlayerRef?, nowMillis: Long, ttlMillis: Long): Entry<T>? {
        prune(nowMillis, ttlMillis)
        if (entries.size >= maxSize) return null
        val entry = Entry(nextId++, holder, request, target, nowMillis)
        entries[entry.id] = entry
        return entry
    }

    /** 取り出さずに見る。期限切れなら捨てて `null` */
    public fun peek(id: Long, nowMillis: Long, ttlMillis: Long): Entry<T>? {
        val e = entries[id] ?: return null
        if (expired(e, nowMillis, ttlMillis)) {
            entries.remove(id)
            return null
        }
        return e
    }

    /** 表から取り出す。呼び手が [peek] して確かめた後にだけ呼ぶ。期限切れなら捨てて `null` */
    public fun take(id: Long, nowMillis: Long, ttlMillis: Long): Entry<T>? {
        val e = peek(id, nowMillis, ttlMillis) ?: return null
        entries.remove(id)
        return e
    }

    /** 期限内の依頼（古い順）。期限切れは捨てる */
    public fun list(nowMillis: Long, ttlMillis: Long): List<Entry<T>> {
        prune(nowMillis, ttlMillis)
        return entries.values.toList()
    }

    /** すべて捨てる（サーバーを閉じるとき） */
    public fun clear() {
        entries.clear()
    }

    private fun prune(nowMillis: Long, ttlMillis: Long) {
        entries.values.removeAll { expired(it, nowMillis, ttlMillis) }
    }

    private fun expired(e: Entry<T>, nowMillis: Long, ttlMillis: Long): Boolean = maxOf(0L, nowMillis - e.createdMillis) >= ttlMillis
}
