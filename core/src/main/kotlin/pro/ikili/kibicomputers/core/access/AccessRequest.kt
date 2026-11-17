package pro.ikili.kibicomputers.core.access

import org.jetbrains.annotations.ApiStatus
import java.util.Locale
import java.util.UUID

/** OS からの依頼の種類（#110、ADR 0110） */
@ApiStatus.Internal
public enum class AccessRequestKind {
    /** 名前で保留に足す（[PlayerAccess.addPending]） */
    ADD,

    /** メンバー（UUID）か保留（名前）を外す（[PlayerAccess.removeMember]） */
    REMOVE,

    /** 持ち主を変える（[PlayerAccess.transferOwner]） */
    TRANSFER,
}

/**
 * OS からの依頼（#110、ADR 0110）。[argument] は形を確かめたもの（[parse]）: 名前はそのまま（比べるときは小文字）、UUID は小文字の正規形。
 * 同じ種類で、小文字にした引数が同じなら等しい（同じ依頼はまとめる）
 */
@ApiStatus.Internal
public class AccessRequest private constructor(
    public val kind: AccessRequestKind,
    public val argument: String,
) {
    private val key: String = argument.lowercase(Locale.ROOT)

    /** [argument] が UUID（[REMOVE] で UUID を渡したとき）なら、その UUID */
    public val uuidArgument: UUID? get() = if (UUID_FORM.matches(argument)) UUID.fromString(argument) else null

    override fun equals(other: Any?): Boolean = other is AccessRequest && kind == other.kind && key == other.key

    override fun hashCode(): Int = kind.hashCode() * 31 + key.hashCode()

    override fun toString(): String = "AccessRequest($kind, $argument)"

    public companion object {
        /** 正規の UUID の形（`UUID.fromString` は崩れた形も通すので、先にこれで確かめる） */
        private val UUID_FORM = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        /** [value] が正規の UUID の形か */
        @JvmStatic
        public fun isUuidForm(value: String): Boolean = UUID_FORM.matches(value)

        /**
         * 依頼を作る。形が違えば `null`:
         * - [AccessRequestKind.ADD]・[AccessRequestKind.TRANSFER]: プレイヤー名の形（[PlayerAccess.isValidPendingName]）
         * - [AccessRequestKind.REMOVE]: プレイヤー名の形か、正規の UUID の形（小文字の正規形にする）。名前に `-` は入らないので、形で見分けられる
         */
        @JvmStatic
        public fun parse(kind: AccessRequestKind, argument: String): AccessRequest? = when {
            PlayerAccess.isValidPendingName(argument) -> AccessRequest(kind, argument)
            kind == AccessRequestKind.REMOVE && isUuidForm(argument) -> AccessRequest(kind, UUID.fromString(argument).toString())
            else -> null
        }
    }
}

/**
 * 依頼の期限と、1台の承認待ちの上限（#110。仮値。確定は #251）。コンフィグの区画 `access`
 * （[pro.ikili.kibicomputers.core.config.KiBiConfig.accessRequestRules]）から作る
 *
 * @property ttlMillis 依頼の期限（ミリ秒）。作ってからこれだけ経ったら（ちょうども）期限切れ
 * @property maxOutstanding 1台の承認待ちの上限。これに達したら、新しい依頼は `again`
 */
@ApiStatus.Internal
public class AccessRequestRules(public val ttlMillis: Long, public val maxOutstanding: Int) {
    init {
        require(ttlMillis > 0) { "ttlMillis must be positive: $ttlMillis" }
        require(maxOutstanding > 0) { "maxOutstanding must be positive: $maxOutstanding" }
    }

    /** [createdMillis] に作った依頼が、[nowMillis] の時点で期限切れか。時計が戻った（作った時刻が未来）ときは年齢 0 とみなす */
    public fun isExpired(createdMillis: Long, nowMillis: Long): Boolean = maxOf(0L, nowMillis - createdMillis) >= ttlMillis

    override fun equals(other: Any?): Boolean = other is AccessRequestRules && ttlMillis == other.ttlMillis && maxOutstanding == other.maxOutstanding

    override fun hashCode(): Int = ttlMillis.hashCode() * 31 + maxOutstanding

    override fun toString(): String = "AccessRequestRules(ttlMillis=$ttlMillis, maxOutstanding=$maxOutstanding)"

    public companion object {
        /** 依頼の期限の既定（秒。仮値） */
        public const val DEFAULT_TTL_SECONDS: Int = 300

        /** 1台の承認待ちの上限の既定（仮値） */
        public const val DEFAULT_MAX_OUTSTANDING: Int = 4

        /** 既定の値 */
        @JvmField
        public val DEFAULT: AccessRequestRules = AccessRequestRules(DEFAULT_TTL_SECONDS * 1000L, DEFAULT_MAX_OUTSTANDING)
    }
}

/** どのスレッドからでも読める、今の持ち主と権限と、その仮値（`get-access` 用。#110） */
@ApiStatus.Internal
public class AccessView(public val access: PlayerAccess, public val rules: AccessRules)

/**
 * 持ち主と権限の、接続層への口（#110、ADR 0110）。接続層がケース（ブロックエンティティ）ごとに1つ実装し、BE が生きている間、
 * 同じインスタンスを [pro.ikili.kibicomputers.core.kernel.MachineEnvironment.access] で渡し続ける。
 *
 * ワーカー（プロセスのスレッド。`kibi:computer/access`）が触るのは [view]・[requestRules]・[limiter] の `tryReserve` だけ。
 * [deliver] と [limiter] の `release` は、接続層（メインスレッド）だけが呼ぶ
 */
@ApiStatus.Internal
public interface AccessPort {
    /** どのスレッドからでも呼べる。ワールドに触らない（`@Volatile` の値と設定を読むだけ） */
    public fun view(): AccessView

    /** どのスレッドからでも呼べる。今の依頼の期限と件数（コンフィグの写し） */
    public fun requestRules(): AccessRequestRules

    /**
     * このケースの承認待ちの枠。毎回同じインスタンスを返す。ワーカーが `tryReserve` し、接続層が、届けで捨てたときと承認したときに、
     * 同じものへ `release` する
     */
    public val limiter: AccessRequestLimiter

    /** メインスレッド（tick の中）から呼ばれる。`tryReserve` が [AccessRequestLimiter.Reserve.ACCEPTED] になった依頼を届ける */
    public fun deliver(request: AccessRequest)
}

/**
 * 1台の承認待ちの数え方（#110）。期限付きの記録を持ち、期限内の件数で数える（届ける前にマシンを閉じて、届けも承認もされずに残った記録も、
 * 期限で消える）。スレッド安全（中は `synchronized`）。保存しない
 */
@ApiStatus.Internal
public class AccessRequestLimiter {
    /** [tryReserve] の結果 */
    public enum class Reserve {
        /** 新しく枠を取った（届ける） */
        ACCEPTED,

        /** 同じ依頼が期限内にある（届けない。枠も増やさない） */
        MERGED,

        /** 上限に達している（`again`） */
        FULL,
    }

    private class Record(val request: AccessRequest, val createdMillis: Long)

    private val records = ArrayList<Record>()

    /** ワーカーから呼ぶ。期限切れの記録を捨ててから数える */
    public fun tryReserve(request: AccessRequest, nowMillis: Long, rules: AccessRequestRules): Reserve = synchronized(records) {
        records.removeAll { rules.isExpired(it.createdMillis, nowMillis) }
        when {
            records.any { it.request == request } -> Reserve.MERGED
            records.size >= rules.maxOutstanding -> Reserve.FULL
            else -> {
                records += Record(request, nowMillis)
                Reserve.ACCEPTED
            }
        }
    }

    /** メインスレッドから呼ぶ。承認・取り下げ（届けたときに捨てたもの）で枠を返す。拒否では返さない。記録がなければ何もしない */
    public fun release(request: AccessRequest) {
        synchronized(records) {
            val i = records.indexOfFirst { it.request == request }
            if (i >= 0) records.removeAt(i)
        }
    }

    /** 今の記録の数（期限切れを含む。テスト用） */
    public val size: Int get() = synchronized(records) { records.size }
}
