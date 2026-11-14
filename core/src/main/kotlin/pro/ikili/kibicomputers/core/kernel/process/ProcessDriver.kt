package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.persist.AbiPayload
import pro.ikili.kibicomputers.core.runtime.StateCaptureMode
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
     * `ctx.instance` はまだ呼べない。例外は「例外で抜けたとき」の規則で `CRASHED`（停止の印があれば `KILLED`）。
     * コンポーネントのプロセス（[Program.component]。#263）では呼ばない（ホストの関数はリンクの計画が持つ。[builder] の代わりに
     * 中の core インスタンスごとにカーネルが設定する）
     */
    fun configure(ctx: ProcessContext, builder: WasmInstanceConfig.Builder)

    /** プロセスのスレッドで呼ばれる。`BLOCKING` は終わるまで戻らない（待ちは `ctx.block`）。`WAIT_POINT` は待つところで戻る */
    fun start(ctx: ProcessContext): StepResult

    /**
     * `WAIT_POINT` だけ。待っていた条件がそろったら、プロセスのスレッドで呼ばれる。[ready] は空でない。
     * [StepResult.WaitFuel] の後（燃料が補充されて再開する）だけは、[ready] は空
     */
    fun resume(ctx: ProcessContext, ready: List<WaitKey>): StepResult
}

/**
 * 待機点保存に対応した ABI 層のドライバー（#82、ADR 0082）。対応していないドライバーのプロセスは保存できない（写しの中で殺す）。
 * 層の ID は `SnapshotSection.ABI_LAYER_*`（0 はテスト用に予約）
 */
internal interface SnapshotCapableDriver : ProcessDriver {
    /** 層の ID（u16） */
    val abiLayer: Int

    /** 層の中身の版（u16） */
    val abiLayerVersion: Int

    /**
     * カーネルロックの中、待機点で呼ばれる。`PROCESS_ABI` 節の層の中身（pid・層の ID・版の後ろ）を返す。
     * 保存できなければ `null`（写しの中で殺す）
     */
    fun captureAbi(rt: ProcessRuntime): ByteArray?

    /**
     * [mode] でインスタンスの状態を取って、層の中身を返す（#83）。[StateCaptureMode.FULL] 以外は、インスタンスが
     * [pro.ikili.kibicomputers.core.runtime.WasmInstance.tracksChanges] のときだけ呼ばれる。既定は FULL だけ受け付ける
     */
    fun captureAbi(rt: ProcessRuntime, mode: StateCaptureMode): ByteArray? = if (mode == StateCaptureMode.FULL) captureAbi(rt) else null

    /**
     * カーネルロックの中、待機点で呼ばれる（#84）。[mode] でインスタンスの状態の写しを取り、バイト列にするのは後（書き込みのスレッド）に回す。
     * 写しは元と何も共有しない。既定は [captureAbi] の結果をそのまま包む（その場で符号化する）
     */
    fun capturePayload(rt: ProcessRuntime, mode: StateCaptureMode): AbiPayload? = captureAbi(rt, mode)?.let(AbiPayload::encoded)
}

/** step の結果 */
internal sealed interface StepResult {
    /** [keys] のどれかがそろうまで待つ（`WAIT_POINT` だけ）。[keys] は空でない */
    data class Wait(val keys: List<WaitKey>) : StepResult

    /**
     * 燃料が補充されるまで、スレッドを持たずに待つ（`WAIT_POINT` だけ。止まりうる関数の中の燃料切れで巻き戻した。#339）。
     * 補充されたら、`resume(ctx, emptyList())` で続きから動かす
     */
    data object WaitFuel : StepResult

    /** 終了コード [code] で終わる */
    data class Exit(val code: Int) : StepResult
}
