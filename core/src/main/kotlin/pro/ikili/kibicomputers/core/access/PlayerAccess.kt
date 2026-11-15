package pro.ikili.kibicomputers.core.access

import org.jetbrains.annotations.ApiStatus
import java.util.Locale
import java.util.UUID

/**
 * プレイヤーの識別（#131）。照合は [id]（UUID）で行い、[name] は表示と保留（[PendingInvite]）の照合にだけ使う。
 */
@ApiStatus.Internal
public data class PlayerRef(public val id: UUID, public val name: String)

/**
 * 持ち主・メンバーの記録（#131）。[name] は最後に見た名前（表示用。検査しない）。
 */
@ApiStatus.Internal
public data class AccessMember(public val id: UUID, public val name: String) {
    public companion object {
        /** [player] の記録 */
        @JvmStatic
        public fun of(player: PlayerRef): AccessMember = AccessMember(player.id, player.name)
    }
}

/**
 * 保留の追加（#131）。その名前のプレイヤーが次にアクセスしたとき、期限内ならメンバーに昇格する。
 *
 * @property name 小文字（`Locale.ROOT`）の名前
 * @property createdMillis 作った時刻（エポックのミリ秒）
 */
@ApiStatus.Internal
public data class PendingInvite(public val name: String, public val createdMillis: Long) {
    /** [nowMillis] の時点で期限切れか。ちょうども期限切れ。時計が戻った（作った時刻が未来）ときは年齢 0 とみなす */
    public fun isExpired(nowMillis: Long, rules: AccessRules): Boolean =
        maxOf(0L, nowMillis - createdMillis) >= rules.pendingTtlMillis
}

/** 持ち主と権限の操作の結果（#131） */
@ApiStatus.Internal
public enum class AccessChange {
    /** 足した */
    ADDED,

    /** もうある（同じ名前の保留） */
    ALREADY_PRESENT,

    /** 保留に入れられない名前（[PlayerAccess.isValidPendingName]） */
    INVALID_NAME,

    /** 上限に達している */
    LIMIT_REACHED,

    /** 外した */
    REMOVED,

    /** 見つからない */
    NOT_FOUND,

    /** 読めなかった記録（[PlayerAccess.locked]）なので変えられない */
    LOCKED,

    /** 持ち主がいないので、メンバーや保留を持てない */
    NO_OWNER,
}

/**
 * コンピューター1台の持ち主と権限（#131、ADR 0131）。変わらない値で、変える操作は新しい値を返す（変わらなければ `this`）。
 * 持ち主のブロックエンティティは参照を差し替え、変わったかを参照の比較で知る。
 *
 * ADR 0081 の「owner」（マシン ID を持つブロックの位置。`owner.bin`・`MachineOwner`）とは別物。
 *
 * | 状態 | 操作（GUI・キーボード・破壊）| 管理（追加・削除・持ち主の変更）|
 * | --- | --- | --- |
 * | 持ち主なし（[NONE]）| 誰でも | 誰でも |
 * | 持ち主あり | 持ち主・メンバー・OP | 持ち主・OP |
 * | 読めなかった（[locked]）| OP だけ | OP だけ |
 *
 * 持ち主・メンバーの照合は UUID で行う。名前で照らすのは保留だけ（小文字にして比べる）。時刻は呼び手が渡す（エポックのミリ秒）。
 * スレッド: 値なので、どのスレッドから読んでもよい（使う側はサーバーのメインスレッドだけで読み書きする）。
 */
@ApiStatus.Internal
public class PlayerAccess private constructor(
    /** 持ち主。`null` なら持ち主なし（[locked] のときも `null`） */
    public val owner: AccessMember?,
    /** メンバー（足した順） */
    public val members: List<AccessMember>,
    /** 保留の追加（足した順） */
    public val pending: List<PendingInvite>,
    /** 保存した記録が読めなかった（知らない版・形が崩れている）。fail-closed で、OP だけが操作・管理できる */
    public val locked: Boolean,
) {
    /** 持ち主がいる（[locked] も含む）。爆発・mob の破壊を止めるかはこれで決める */
    public val hasOwner: Boolean get() = owner != null || locked

    private fun isOwner(player: PlayerRef): Boolean = owner?.id == player.id

    private fun isMember(player: PlayerRef): Boolean = members.any { it.id == player.id }

    /** 操作（GUI・キーボード・破壊）できるか。副作用はない */
    public fun mayUse(player: PlayerRef, isOp: Boolean): Boolean = when {
        locked -> isOp
        owner == null -> true
        else -> isOp || isOwner(player) || isMember(player)
    }

    /** 管理（追加・削除・持ち主の変更）できるか。持ち主か OP（持ち主なしなら誰でも）。#110・#206 が使う */
    public fun isManager(player: PlayerRef, isOp: Boolean): Boolean = when {
        locked -> isOp
        owner == null -> true
        else -> isOp || isOwner(player)
    }

    /**
     * [player] がアクセスした（ケースの右クリック・キーボードのセッションを開く）: 期限切れの保留を捨て、名前の一致する期限内の
     * 保留があればメンバーに昇格し（メンバーの上限に達していたら保留を残す）、持ち主・メンバーの最後の名前を更新する。
     * 変わらなければ `this`。持ち主なし・[locked] なら常に `this`
     */
    public fun onAccess(player: PlayerRef, nowMillis: Long, rules: AccessRules): PlayerAccess {
        val currentOwner = owner ?: return this
        if (locked) return this
        var newOwner = currentOwner
        var newMembers = members
        var newPending = pending.filterNot { it.isExpired(nowMillis, rules) }
        when {
            isOwner(player) -> if (currentOwner.name != player.name) newOwner = AccessMember.of(player)
            isMember(player) -> {
                val i = members.indexOfFirst { it.id == player.id }
                if (members[i].name != player.name) newMembers = members.toMutableList().also { it[i] = AccessMember.of(player) }
            }
            else -> {
                val key = normalize(player.name)
                val invite = newPending.firstOrNull { it.name == key }
                if (invite != null && members.size < rules.maxMembers) {
                    newMembers = members + AccessMember.of(player)
                    newPending = newPending - invite
                }
            }
        }
        return copyIfChanged(newOwner, newMembers, newPending)
    }

    /** 期限切れの保留を捨てる（保存の前に呼ぶ）。変わらなければ `this` */
    public fun pruneExpired(nowMillis: Long, rules: AccessRules): PlayerAccess {
        if (pending.none { it.isExpired(nowMillis, rules) }) return this
        return copyIfChanged(owner, members, pending.filterNot { it.isExpired(nowMillis, rules) })
    }

    /**
     * 名前 [name] のプレイヤーを、保留として足す（#110・#206 が呼ぶ）。期限切れの保留は先に捨てる。
     * 名前の形（[isValidPendingName]）、重複（大文字・小文字を無視して同じ名前の保留）、保留の上限を確かめる。
     * 持ち主・メンバーの名前とは照らさない（最後に見た名前で古くなりうる。改名後にその名前を別のプレイヤーが使うことがある）。
     * 同じ名前の持ち主・メンバー本人が保留に入っても、[onAccess] は持ち主・メンバーとして扱うので、保留は昇格せず期限で消える。
     * 呼び手が本人かを UUID で確かめたいときは、[owner]・[members] の [AccessMember.id] で照らす
     */
    public fun addPending(name: String, nowMillis: Long, rules: AccessRules): Result {
        if (locked) return Result(this, AccessChange.LOCKED)
        val currentOwner = owner ?: return Result(this, AccessChange.NO_OWNER)
        if (!isValidPendingName(name)) return Result(this, AccessChange.INVALID_NAME)
        val key = normalize(name)
        val pruned = pruneExpired(nowMillis, rules)
        if (pruned.pending.any { it.name == key }) return Result(pruned, AccessChange.ALREADY_PRESENT)
        if (pruned.pending.size >= rules.maxPending) return Result(pruned, AccessChange.LIMIT_REACHED)
        return Result(PlayerAccess(currentOwner, members, pruned.pending + PendingInvite(key, nowMillis), false), AccessChange.ADDED)
    }

    /**
     * メンバーか保留を外す（#110・#206 が呼ぶ）。持ち主は外せない（[transferOwner] を使う）。
     *
     * [nameOrId] が UUID の文字列ならその UUID のメンバーを、そうでなければその名前（大文字・小文字を無視）の保留を外す。
     * 名前ではメンバーを外さない: [AccessMember.name] は最後に見た名前で古くなりうる（改名後にその名前を別のプレイヤーが使う）ので、
     * 名前で照らすと、今その名前の別人を外すつもりで、改名前に同じ名前だったメンバーまで外れる（[addPending] と同じ考え）。
     * 名前でメンバーを外したい呼び手は、サーバーで今の名前から UUID を引いて（オンラインのプレイヤー・プロフィールのキャッシュ）、
     * その UUID を渡す
     */
    public fun removeMember(nameOrId: String): Result {
        if (locked) return Result(this, AccessChange.LOCKED)
        if (owner == null) return Result(this, AccessChange.NOT_FOUND)
        val id = runCatching { UUID.fromString(nameOrId) }.getOrNull()
        val key = normalize(nameOrId)
        val newMembers = if (id == null) members else members.filterNot { it.id == id }
        val newPending = if (id != null) pending else pending.filterNot { it.name == key }
        val next = copyIfChanged(owner, newMembers, newPending)
        return Result(next, if (next === this) AccessChange.NOT_FOUND else AccessChange.REMOVED)
    }

    /**
     * 持ち主を [newOwner] に変える（#110・#206 が呼ぶ）。元の持ち主はメンバーに残さない。[newOwner] がメンバー・保留にいれば外す。
     * [locked] の記録からも変えられる（OP が直すとき。読めなかった記録は捨てる）
     */
    public fun transferOwner(newOwner: PlayerRef): PlayerAccess {
        if (locked) return ownedBy(newOwner)
        val key = normalize(newOwner.name)
        return PlayerAccess(
            AccessMember.of(newOwner),
            members.filterNot { it.id == newOwner.id },
            pending.filterNot { it.name == key },
            false,
        )
    }

    private fun copyIfChanged(newOwner: AccessMember?, newMembers: List<AccessMember>, newPending: List<PendingInvite>): PlayerAccess =
        if (newOwner == owner && newMembers == members && newPending == pending) this
        else PlayerAccess(newOwner, newMembers, newPending, locked)

    override fun equals(other: Any?): Boolean = other is PlayerAccess &&
        owner == other.owner && members == other.members && pending == other.pending && locked == other.locked

    override fun hashCode(): Int = ((owner.hashCode() * 31 + members.hashCode()) * 31 + pending.hashCode()) * 31 + locked.hashCode()

    override fun toString(): String =
        if (locked) "PlayerAccess(locked)" else "PlayerAccess(owner=$owner, members=$members, pending=$pending)"

    /** 操作の結果。[access] は新しい値（変わらなければ元の値） */
    public class Result(public val access: PlayerAccess, public val change: AccessChange)

    public companion object {
        private val PENDING_NAME = Regex("[A-Za-z0-9_]{1,16}")

        /** 持ち主なし。誰でも操作できる */
        @JvmField
        public val NONE: PlayerAccess = PlayerAccess(null, emptyList(), emptyList(), false)

        /** 読めなかった記録。OP だけが操作できる */
        @JvmField
        public val LOCKED: PlayerAccess = PlayerAccess(null, emptyList(), emptyList(), true)

        /** [player] が持ち主で、メンバーと保留のないもの */
        @JvmStatic
        public fun ownedBy(player: PlayerRef): PlayerAccess = PlayerAccess(AccessMember.of(player), emptyList(), emptyList(), false)

        /**
         * 記録から作る（[PlayerAccessRecord.toAccess] が使う）。重複したメンバー（同じ UUID・持ち主と同じ UUID）と保留（同じ名前）は
         * 最初のものだけ残す。上限を超えていても捨てない
         */
        @JvmStatic
        public fun of(owner: AccessMember, members: List<AccessMember>, pending: List<PendingInvite>): PlayerAccess {
            val seen = HashSet<UUID>().apply { add(owner.id) }
            val names = HashSet<String>()
            return PlayerAccess(
                owner,
                members.filter { seen.add(it.id) },
                pending.map { PendingInvite(normalize(it.name), it.createdMillis) }.filter { names.add(it.name) },
                false,
            )
        }

        /** 保留に入れられる名前か（`[A-Za-z0-9_]{1,16}`。Minecraft のプレイヤー名の形） */
        @JvmStatic
        public fun isValidPendingName(name: String): Boolean = PENDING_NAME.matches(name)

        private fun normalize(name: String): String = name.lowercase(Locale.ROOT)
    }
}
