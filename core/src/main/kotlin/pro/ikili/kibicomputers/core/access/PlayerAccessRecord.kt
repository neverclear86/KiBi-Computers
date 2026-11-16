package pro.ikili.kibicomputers.core.access

import org.jetbrains.annotations.ApiStatus
import java.util.UUID

/**
 * 持ち主と権限の保存の形（#131、ADR 0131）。NBT に依存しない。接続層が、ケースのブロックエンティティの NBT の複合タグ
 * `PlayerAccess` と相互に変換する。
 *
 * ```
 * PlayerAccess: {
 *   Version: 1,
 *   Owner: { Id: [I; ...], Name: "Alex" },
 *   Members: [ { Id: [I; ...], Name: "Steve" } ],      // 空なら省く
 *   Pending: [ { Name: "notch", Created: 1731...L } ]  // 空なら省く
 * }
 * ```
 *
 * 持ち主がいなければタグを書かない（[of] が `null`）。知らない版・持ち主の UUID がないときは [PlayerAccess.LOCKED]（[toAccess]）。
 *
 * @property version 版。今は [VERSION]。読めなかった版はそのまま入る
 * @property ownerId 持ち主の UUID。読めなければ `null`
 * @property ownerName 持ち主の最後の名前。なければ `null`
 */
@ApiStatus.Internal
public class PlayerAccessRecord(
    public val version: Int,
    public val ownerId: UUID?,
    public val ownerName: String?,
    public val members: List<AccessMember>,
    public val pending: List<PendingInvite>,
) {
    public companion object {
        /** 今の版 */
        public const val VERSION: Int = 1

        /**
         * [access] の記録。持ち主なしなら `null`（タグを書かない）。
         *
         * @throws IllegalArgumentException [access] が [PlayerAccess.locked]（読めなかった記録は、読んだ生のタグをそのまま書き戻す）
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class)
        public fun of(access: PlayerAccess): PlayerAccessRecord? {
            require(!access.locked) { "a locked PlayerAccess has no record; write back the raw tag instead" }
            val owner = access.owner ?: return null
            return PlayerAccessRecord(VERSION, owner.id, owner.name, access.members, access.pending)
        }

        /** 記録から作る。知らない版・持ち主の UUID がなければ [PlayerAccess.LOCKED]（黙って持ち主なしにしない） */
        @JvmStatic
        public fun toAccess(record: PlayerAccessRecord): PlayerAccess {
            if (record.version != VERSION) return PlayerAccess.LOCKED
            val ownerId = record.ownerId ?: return PlayerAccess.LOCKED
            return PlayerAccess.of(AccessMember(ownerId, record.ownerName ?: ""), record.members, record.pending)
        }
    }
}
