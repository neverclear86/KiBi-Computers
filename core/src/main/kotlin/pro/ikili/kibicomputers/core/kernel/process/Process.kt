package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.kernel.obj.HandleEntry
import pro.ikili.kibicomputers.core.kernel.obj.HandleTable
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey

/** プロセスの動き方。どちらを使うかは ABI 層が決める（#63・#72・#73）。 */
internal enum class ProcessKind {
    /** ホスト関数の中でスレッドを止めて待つ（p1 の `_start`、p2 の同期呼び出し）。待っている間は保存できない */
    BLOCKING,

    /** export から戻って待つ（reactor、p3 のコールバック方式）。待っている間は保存できる */
    WAIT_POINT,
}

/** プロセスの状態 */
internal enum class ProcessState {
    /** 表に足しただけ（インスタンスはまだない） */
    CREATED,

    /** CPU を取ってインスタンスを作っている途中（start 関数の実行を含む） */
    LOADING,

    /** 動ける（CPU・許可・燃料を待っている） */
    RUNNABLE,

    /** CPU を持って動いている */
    RUNNING,

    /** step の途中で燃料切れの処理の中にいる（次の tick を待つ） */
    WAITING_FUEL,

    /** イベント（[WaitKey]）を待っている */
    WAITING_EVENT,

    /** 接続層での処理の結果を待っている */
    WAITING_HOST,

    /** 自分から終わった */
    EXITED,

    /** 停止の印がないのに、トラップ・例外で終わった */
    CRASHED,

    /** 止められた */
    KILLED,
    ;

    /** 終わっている（ゾンビ） */
    val isTerminal: Boolean get() = this == EXITED || this == CRASHED || this == KILLED
}

/** ハンドル表の上限（仮の値。確定は #251）。fd の数の上限（超えると `MFILE`）を兼ねる */
internal const val DEFAULT_HANDLE_LIMIT: Int = 256

/**
 * プロセス（保存の対象）。番号・列挙・バイト列だけを持つ。実行時だけのものは [ProcessRuntime] に分ける。
 */
internal class Process(
    /** 自分の pid */
    val pid: Int,
    /** 親の pid（init の親は 0） */
    val parentPid: Int,
    /** 動き方 */
    val kind: ProcessKind,
    /** インスタンスを作ったモジュールの識別子（#30 の結論 4） */
    val moduleHash: ModuleHash,
    /** 燃料を計測しないプロセス（テスト専用） */
    val unmetered: Boolean,
) {
    /** 状態 */
    var state: ProcessState = ProcessState.CREATED

    /** 終了コード（終わっていなければ `null`） */
    var exitCode: Int? = null

    /** 待っている [WaitKey] の並び（待っていなければ `null`） */
    var wait: List<WaitKey>? = null

    /** ハンドル表（fd 表。0/1/2 は標準入出力。操作は `FdTable` を通す。Component Model の resource の表は #265） */
    val handles: HandleTable<HandleEntry> = HandleTable(DEFAULT_HANDLE_LIMIT)
}
