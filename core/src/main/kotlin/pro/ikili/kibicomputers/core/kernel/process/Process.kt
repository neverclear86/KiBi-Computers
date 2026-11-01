package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.kernel.obj.HandleEntry
import pro.ikili.kibicomputers.core.kernel.obj.HandleTable
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import java.util.EnumSet

/**
 * プロセスの動き方。どちらを使うかは ABI 層が決める（#63・#72・#73）。
 * p1 のコマンド（`_start`）は、読み込み時の待機点への変換（#329、ADR 0316）ができれば `WAIT_POINT`、断られたら `BLOCKING`
 */
internal enum class ProcessKind {
    /** ホスト関数の中でスレッドを止めて待つ（変換を断った p1 の `_start`、p2 の同期呼び出し）。待っている間は保存できない */
    BLOCKING,

    /**
     * export から戻って待つ（変換した p1 の `_start`、reactor、p3 のコールバック方式）。待っている間は保存できる。
     * 変換した p1 は、止まりうる import の中で巻き戻して `_start` から戻り、起こされたら巻き直して `_start` を呼び直す
     */
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
    parentPid: Int,
    /** 動き方 */
    val kind: ProcessKind,
    /** インスタンスを作ったモジュールの識別子（#30 の結論 4） */
    val moduleHash: ModuleHash,
    /** 燃料を計測しないプロセス（テスト専用） */
    val unmetered: Boolean,
    /** 引数（argv。最初の要素はプログラムの名前）。作った後は変わらない（#45） */
    val args: List<String> = emptyList(),
    /** 環境変数（名前 → 値。並びを保つ）。作った後は変わらない（#45） */
    val env: Map<String, String> = emptyMap(),
    /** 作業ディレクトリ（VFS の絶対パス。#45） */
    cwd: VfsPath = VfsPath.ROOT,
) {
    /** 親の pid（親なしは 0）。親が先に終わったら 0 に付け替える（#46） */
    var parentPid: Int = parentPid

    /** このプロセスの `ProcessObject` のオブジェクトID（表に入れる前・テストで直接作ったものは 0。#46） */
    var objectId: Int = 0

    /** 作業ディレクトリ（VFS の絶対パス）。変えるのは `ProcessContext.changeDirectory` */
    var cwd: VfsPath = cwd

    /** 状態 */
    var state: ProcessState = ProcessState.CREATED

    /** 終了コード（終わっていなければ `null`） */
    var exitCode: Int? = null

    /** シグナルで `KILLED` になったときのシグナル（マシンの停止・終わっていなければ `null`。#46） */
    var termSignal: KillSignal? = null

    /** 無視するシグナル（[KillSignal.KILL] は入らない。子には引き継がない。#46） */
    val ignoredSignals: MutableSet<KillSignal> = EnumSet.noneOf(KillSignal::class.java)

    /** 待っている [WaitKey] の並び（待っていなければ `null`） */
    var wait: List<WaitKey>? = null

    /**
     * 待機点への変換をかけたプロセスが、巻き戻しで退避した値（#329。変換していないプロセスは `null`）。
     * 空でなければ、再開は巻き直しから（状態のグローバルは、待っている間は常に `NORMAL`）
     */
    var asyncStack: AsyncStack? = null

    /** 巻き戻しで待っている呼び出しを、やり直すときに要る値（#329。待っている間だけ `null` でない） */
    var asyncWait: AsyncWait? = null

    /**
     * `openWaitTimer` で開いて、まだ閉じていない一時的なタイマーのオブジェクトID（#329）。一時的なタイマーの持ち主はここだけ。
     * 不変条件: [asyncWait] のタイマーは、ここに入っている
     */
    val waitTimers: WaitTimerSet = WaitTimerSet()

    /** ハンドル表（fd 表。0/1/2 は標準入出力。操作は `FdTable` を通す。Component Model の resource の表は #265） */
    val handles: HandleTable<HandleEntry> = HandleTable(DEFAULT_HANDLE_LIMIT)
}
