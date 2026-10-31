package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig

/** ホスト import を登録する口。`ctx.instantiate` に渡す（最初のインスタンスは [ProcessDriver.configure]）。 */
internal fun interface InstanceConfigurer {
    /** `builder.hostFunction(...)` でホスト関数（`ctx.hostFunction` で包んだもの）を登録する。`builder.fuel` は呼ばない（カーネルが設定する） */
    fun configure(builder: WasmInstanceConfig.Builder)
}

/**
 * ABI 層（WASI p1、Component Model の p2・p3、`kibi:*`）が実装する、プロセスの動かし方。
 *
 * カーネルは p1・p2・p3 のどれなのかを知らない。ABI 層はカーネルの共通の部品（ハンドル表、[WaitKey] と待ち、`ByteStream`）を使う。
 */
internal interface ProcessDriver {
    /** 動き方 */
    val kind: ProcessKind

    /**
     * `LOADING` の中、最初のインスタンスを作る前に、プロセスのスレッドで1回だけ呼ばれる（CPU と許可は持っている。カーネルロックは持っていない）。
     * ホスト import を [builder] に登録する。`builder.fuel` は呼ばない（呼ぶと `IllegalStateException` でプロセスが `CRASHED`）。
     * `ctx.instance` はまだ呼べない。例外は「例外で抜けたとき」の規則で `CRASHED`（停止の印があれば `KILLED`）
     */
    fun configure(ctx: ProcessContext, builder: WasmInstanceConfig.Builder)

    /** プロセスのスレッドで呼ばれる。`BLOCKING` は終わるまで戻らない（待ちは `ctx.block`）。`WAIT_POINT` は待つところで戻る */
    fun start(ctx: ProcessContext): StepResult

    /** `WAIT_POINT` だけ。待っていた条件がそろったら、プロセスのスレッドで呼ばれる。[ready] は空でない */
    fun resume(ctx: ProcessContext, ready: List<WaitKey>): StepResult
}

/** step の結果 */
internal sealed interface StepResult {
    /** [keys] のどれかがそろうまで待つ（`WAIT_POINT` だけ）。[keys] は空でない */
    data class Wait(val keys: List<WaitKey>) : StepResult

    /** 終了コード [code] で終わる */
    data class Exit(val code: Int) : StepResult
}
