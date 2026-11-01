package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.kernel.obj.KernelObject
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition

/** 終わり方（#46） */
internal enum class ExitReason {
    /** 自分から終わった */
    EXITED,

    /** 止められた（シグナル、マシンの停止） */
    KILLED,

    /** トラップ・例外で終わった */
    CRASHED,
}

/** 終わったプロセスの記録（保存の対象。#46） */
internal data class ExitStatus(
    val pid: Int,
    val reason: ExitReason,
    /** [ExitReason.EXITED] のときの終了コード（32 ビットそのまま。それ以外は 0） */
    val code: Int,
    /** [ExitReason.KILLED] のうち、シグナルで止められたときのシグナル（マシンの停止なら `null`） */
    val signal: KillSignal?,
)

/**
 * プロセス1つに1つの、終了を待つためのカーネルオブジェクト（保存の対象。#46、ADR 0046）。
 *
 * カーネルが参照を1つ持ち、刈り取るときに返す。p2/p3 の resource（#74）が参照を持てば、刈り取られた後も [status] を読める。
 *
 * - [WaitCondition.EXITED]: このプロセスが終わった（[status] がある）
 * - [WaitCondition.CHILD_EXITED]: 刈り取られていない、終わった子がある
 */
internal class ProcessObject(val pid: Int) : KernelObject() {
    /** 終わったら入る */
    var status: ExitStatus? = null

    /** 刈り取られていない、終わった子の数（表から作り直せる） */
    var pendingChildExits: Int = 0

    override fun canWait(condition: WaitCondition): Boolean =
        condition == WaitCondition.EXITED || condition == WaitCondition.CHILD_EXITED

    override fun isReady(condition: WaitCondition): Boolean = when (condition) {
        WaitCondition.EXITED -> status != null
        WaitCondition.CHILD_EXITED -> pendingChildExits > 0
        else -> false
    }
}
