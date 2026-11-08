package pro.ikili.kibicomputers.core.persist

import org.jetbrains.annotations.ApiStatus

/**
 * マシン（またはプロセス）を今保存できるか（#81、ADR 0081）。土台は `Machine.isAtWaitPoint`（#365）。
 *
 * マシン全体の判定は、プロセスの判定のうち、[TRANSIENT] があれば [TRANSIENT]、なければ [UNSAVEABLE] があれば [UNSAVEABLE]、
 * どちらもなければ [READY]。起動中・再起動の待ちのマシンは、プロセスによらず [TRANSIENT]。
 */
@ApiStatus.Internal
public enum class SaveReadiness {
    /** 待機点にいる（燃料待ちで巻き戻したものを含む）。このまま写せる */
    READY,

    /** 待機点型で step の途中など。待てば [READY] になる（起動中・再起動の待ちは、待っても来ないことがある） */
    TRANSIENT,

    /** ブロッキング型で待機点にいない。待っても来ないので、写しの中で殺す */
    UNSAVEABLE,
}

/** 写しを取るときの、[SaveReadiness.TRANSIENT] の扱い（#81） */
@ApiStatus.Internal
public enum class CapturePolicy {
    /** [SaveReadiness.TRANSIENT] なら写さない（自動保存。前の世代を残す） */
    SKIP_IF_TRANSIENT,

    /** 必ず写す。[SaveReadiness.TRANSIENT] のプロセスも写しの中で殺す（アンロード・停止・待ちの予算切れ） */
    KILL_TRANSIENT,
}

/**
 * 写しから戻すときに、状態を復元せず冷えた起動にする理由（`KILLED` 節の u8。#81、ADR 0081）。
 * 読むとき、知らない値は「冷えた起動が要る」として扱う（[requiresColdBoot]）。
 */
@ApiStatus.Internal
public enum class ColdBootReason(
    /** 節に書く値 */
    public val code: Int,
) {
    /** 要らない */
    NONE(0),

    /** init を写しの中で殺した */
    INIT_KILLED(1),

    /** 起動の途中だった（init がまだない） */
    BOOTING(2),

    /** 再起動を求められて待っていた */
    REBOOT_PENDING(3),
    ;

    public companion object {
        /** [code] の理由。知らない値なら `null` */
        @JvmStatic
        public fun fromCode(code: Int): ColdBootReason? = entries.firstOrNull { it.code == code }

        /** [code] が冷えた起動を求めるか（0 以外。知らない値も真） */
        @JvmStatic
        public fun requiresColdBoot(code: Int): Boolean = code != NONE.code
    }
}

/**
 * 1台のマシンを止めている印（`Machine.quiesce`。#81）。止めている間は、スケジューラがこのマシンに新しい step を割り当てない。
 * 走っている step は待機点まで走る。[close] で続ける（2回呼んでもよい）。
 */
@ApiStatus.Internal
public interface QuiesceHandle : AutoCloseable {
    /** 待たずに、今の判定 */
    public val readiness: SaveReadiness

    /** 止めるのをやめて続ける */
    override fun close()
}
