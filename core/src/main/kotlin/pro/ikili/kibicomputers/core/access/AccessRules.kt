package pro.ikili.kibicomputers.core.access

import org.jetbrains.annotations.ApiStatus

/**
 * 持ち主と権限（#131、ADR 0131）の仮値の束。コンフィグの区画 `access`（[pro.ikili.kibicomputers.core.config.KiBiConfig.accessRules]）
 * から作る。どれも仮の値で、確定は #251。
 *
 * @property opPermissionLevel この権限レベル以上のプレイヤー（OP）は、持ち主のあるコンピューターも操作・破壊・管理できる
 * @property pendingTtlMillis 保留の追加（[PendingInvite]）の期限（ミリ秒）。作ってからこれだけ経ったら（ちょうども）期限切れ
 * @property maxMembers 1台のメンバーの上限。これに達していたら、保留をメンバーに昇格しない
 * @property maxPending 1台の保留の上限。これに達していたら、保留を足さない
 */
@ApiStatus.Internal
public class AccessRules(
    public val opPermissionLevel: Int,
    public val pendingTtlMillis: Long,
    public val maxMembers: Int,
    public val maxPending: Int,
) {
    init {
        require(pendingTtlMillis > 0) { "pendingTtlMillis must be positive: $pendingTtlMillis" }
        require(maxMembers >= 0) { "maxMembers must not be negative: $maxMembers" }
        require(maxPending >= 0) { "maxPending must not be negative: $maxPending" }
    }

    override fun equals(other: Any?): Boolean = other is AccessRules &&
        opPermissionLevel == other.opPermissionLevel && pendingTtlMillis == other.pendingTtlMillis &&
        maxMembers == other.maxMembers && maxPending == other.maxPending

    override fun hashCode(): Int =
        ((opPermissionLevel * 31 + pendingTtlMillis.hashCode()) * 31 + maxMembers) * 31 + maxPending

    override fun toString(): String =
        "AccessRules(opPermissionLevel=$opPermissionLevel, pendingTtlMillis=$pendingTtlMillis, maxMembers=$maxMembers, maxPending=$maxPending)"

    public companion object {
        /** OP とみなす権限レベルの既定（仮値） */
        public const val DEFAULT_OP_PERMISSION_LEVEL: Int = 2

        /** 保留の期限の既定（時間。仮値） */
        public const val DEFAULT_PENDING_TTL_HOURS: Int = 24

        /** メンバーの上限の既定（仮値） */
        public const val DEFAULT_MAX_MEMBERS: Int = 32

        /** 保留の上限の既定（仮値） */
        public const val DEFAULT_MAX_PENDING: Int = 8

        private const val HOUR_MILLIS: Long = 60L * 60 * 1000

        /** 時間をミリ秒に直す */
        @JvmStatic
        public fun hoursToMillis(hours: Int): Long = hours.toLong() * HOUR_MILLIS

        /** 既定の値 */
        @JvmField
        public val DEFAULT: AccessRules = AccessRules(
            DEFAULT_OP_PERMISSION_LEVEL,
            hoursToMillis(DEFAULT_PENDING_TTL_HOURS),
            DEFAULT_MAX_MEMBERS,
            DEFAULT_MAX_PENDING,
        )
    }
}
