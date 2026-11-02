package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.kernel.obj.KernelObject
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import java.util.concurrent.CompletableFuture

/**
 * ホスト側の処理（メインスレッドでのホスト呼び出し、実行ファイルの読み込み）の終わりを待つためのカーネルオブジェクト（#358）。
 * 終わったら `READABLE` になる。
 *
 * 保存できる値（終わったか）だけを持つ。結果の値は実行時だけのもの（[ProcessRuntime.hostWaits]）。
 * 持ち主はそれを作ったプロセスだけで、ハンドル表には入れない（`ProcessContext.takeHostResult`・`closeHostWait`、
 * プロセスの終わりで閉じる）。カーネルロックの中で使う。
 */
internal class HostCompletion : KernelObject() {
    /** 処理が終わった（成功・失敗・取り消しのどれでも） */
    var done: Boolean = false

    override fun canWait(condition: WaitCondition): Boolean = condition == WaitCondition.READABLE

    override fun isReady(condition: WaitCondition): Boolean = condition == WaitCondition.READABLE && done
}

/**
 * プロセスが出した、終わりを待つホスト側の処理（#358）。`ProcessContext.submitHostCall` などが返す。
 *
 * 待機点型は [key] を `StepResult.Wait` に入れて待ち（スレッドを持たない）、そろったら `ProcessContext.takeHostResult` で
 * 結果を受け取る（オブジェクトも閉じる）。待つのをやめるときは `ProcessContext.closeHostWait` で閉じる。
 * プロセスが終わると、閉じていないものはカーネルが閉じる。
 *
 * p1 の止まりうる import では、[key] で巻き戻して待ち、巻き直して着いた呼び出しが同じ [HostWait] から結果を受け取る
 * （ABI 層が [HostWait] を持っておく）。
 */
internal class HostWait<T> internal constructor(
    /** 終わると `READABLE` になる [HostCompletion] の key */
    val key: WaitKey,
    internal val future: CompletableFuture<T>,
)

/** [ProcessRuntime.hostWaits] の1つ。[abandon] は、結果を受け取らずに閉じるとき（待つのをやめた）に呼ぶ */
internal class PendingHostWait(val future: CompletableFuture<*>, val abandon: (() -> Unit)?)

/**
 * プロセスからの `exec` の、読み込み（コンパイル）を待っている途中（#358）。`ProcessContext.beginExec` が返し、
 * [wait] がそろったら `ProcessContext.finishExec` でプロセスを作る。
 */
internal class ExecWait internal constructor(
    /** 読み込みの終わりを待つ。[wait] の `key` を `StepResult.Wait` に入れる */
    val wait: HostWait<Program>,
    internal val pending: PendingExec,
) {
    /** 読み込みの終わりを待つ key（[wait] の `key`） */
    val key: WaitKey get() = wait.key
}

/** `Machine.beginExec` の結果（読み込みを始めた実行ファイル）。`Machine.finishExec` に渡す */
internal class PendingExec internal constructor(
    /** 読み込む WASM のパス（シバンをたどった後ならインタプリタ） */
    val path: String,
    /** プロセスに渡す argv */
    val args: List<String>,
    val drivers: ProcessDriverFactory,
    val options: SpawnOptions,
    val parentPid: Int,
    /** 読み込みの結果（呼び出しごとに別のもの。待つのをやめたら `cancel(false)`） */
    val future: CompletableFuture<Program>,
)
