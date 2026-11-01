package pro.ikili.kibicomputers.core.kernel.process

/**
 * プロセスに送るシグナル（#46、ADR 0046）。効き方は「無視する」か「止める（`KILLED`）」の2つだけで、ハンドラーはない。
 *
 * プロセスがシグナルを受けて何かしたいとき（Lua の REPL で Ctrl+C を行の取り消しにするなど）は、
 * イベントキュー（#63）で届ける形を後で足す。整数への写し方は ABI 層（#74）。
 */
internal enum class KillSignal(
    /** [ProcessContext.setSignalIgnored] で無視できる */
    val ignorable: Boolean,
) {
    /** Ctrl+C */
    INTERRUPT(true),

    /** Ctrl+\ */
    QUIT(true),

    /** 普通の終了の求め（kill の既定） */
    TERMINATE(true),

    /** 無視できない */
    KILL(false),
}

/** [ProcessContext.kill] の結果 */
internal enum class KillResult {
    /** 止める印を付けた（止まったことは終了待ちで知る） */
    DELIVERED,

    /** 相手がそのシグナルを無視している */
    IGNORED,

    /** 相手はもう終わっている（ゾンビ） */
    ALREADY_EXITED,

    /** 表にない pid */
    NOT_FOUND,

    /** 送れない相手（init） */
    NOT_PERMITTED,
}

/** どの子を待つか（[ProcessContext.tryReap] など） */
internal sealed interface ChildSelector {
    /** どれかの子（pid の割り当ての順でいちばん古い、終わった子） */
    data object Any : ChildSelector

    /** pid が [pid] の子 */
    data class Pid(val pid: Int) : ChildSelector
}

/** 刈り取りの結果 */
internal sealed interface ReapResult {
    /** 刈り取った（表から外した） */
    data class Reaped(val status: ExitStatus) : ReapResult

    /** 待つ相手はいるが、まだ終わっていない */
    data object Running : ReapResult

    /** 子が1つもない（[ChildSelector.Any]）、または [ChildSelector.Pid] が自分の子でない */
    data object NoChild : ReapResult
}
