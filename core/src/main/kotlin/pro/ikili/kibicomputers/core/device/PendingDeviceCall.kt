package pro.ikili.kibicomputers.core.device

import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.kernel.obj.KernelObject
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WakeSink

/**
 * 待たずに積んだデバイスの呼び出し（#65。WIT の `pending-call`）。カーネルオブジェクトで、fd としてプロセスのハンドル表に入る。
 * 終わると `READABLE` になる（`poll_oneoff` で待てる。#358 の `HostCompletion` と同じ待ちの仕組み）。
 *
 * 保存できる値（[state]）だけを持つ。カーネルロックの中で使う。1プロセス [DeviceLimits.MAX_PENDING_CALLS] 個まで。
 */
internal class PendingDeviceCall(state: State) : KernelObject() {
    /** 呼び出しの進み具合 */
    sealed class State {
        /** まだ実行していない（メインスレッドの列にある）。保存して戻すと積み直す */
        data class Queued(val address: String, val method: String, val args: List<DeviceValue>) : State()

        /** 実行し始めた（後で返すメソッドが終えるのを待っている）。保存すると `failed("interrupted")` になる */
        data object Running : State()

        /** 終わった */
        data class Done(val result: CallResult) : State()
    }

    /** 進み具合 */
    var state: State = state
        private set

    /** 実行し始めた（[State.Queued] のときだけ変える） */
    fun markRunning() {
        if (state is State.Queued) state = State.Running
    }

    /** 終わった（2回目以降は何もしない）。変えたら `true` */
    fun finish(result: CallResult): Boolean {
        if (state is State.Done) return false
        state = State.Done(result)
        return true
    }

    /** 終わっていれば結果。終わっていなければ `null`（WIT の `take` が `none` を返す） */
    val result: CallResult? get() = (state as? State.Done)?.result

    override fun canWait(condition: WaitCondition): Boolean = condition == WaitCondition.READABLE

    override fun isReady(condition: WaitCondition): Boolean = condition == WaitCondition.READABLE && state is State.Done

    /** 最後の fd を閉じた。まだ終わっていなければ、実行時の側が呼び出しを取り消す（#532） */
    override fun onLastClose(sink: WakeSink) {
        if (state !is State.Done) sink.deviceCallClosed(id)
    }

    /** 保存の写し（中身の形だけ。バイトの形は #81・#82） */
    sealed class Snapshot {
        /** まだ実行していない呼び出し（戻すと積み直す） */
        data class Queued(val address: String, val method: String, val args: List<DeviceValue>) : Snapshot()

        /** 終わった呼び出し */
        data class Done(val result: CallResult) : Snapshot()
    }

    /** 保存の写しを取る。実行中のもの（後で返すメソッドを待っている）は、戻せないので `failed("interrupted")` にする */
    fun snapshot(): Snapshot = when (val s = state) {
        is State.Queued -> Snapshot.Queued(s.address, s.method, s.args)
        State.Running -> Snapshot.Done(CallResult.Error(CallError.INTERRUPTED))
        is State.Done -> Snapshot.Done(s.result)
    }

    /** 写し（#82）。実行中のものは [snapshot] と同じく `failed("interrupted")` にする */
    override fun copyForSnapshot(): KernelObject = restore(snapshot())

    override fun toString(): String = "PendingDeviceCall($id, $state)"

    companion object {
        /** 写し [snapshot] から戻す。[Snapshot.Queued] は、表に入れた後に `DeviceDispatcher` で積み直す（`Machine.dispatchPendingDeviceCall`） */
        fun restore(snapshot: Snapshot): PendingDeviceCall = when (snapshot) {
            is Snapshot.Queued -> PendingDeviceCall(State.Queued(snapshot.address, snapshot.method, snapshot.args))
            is Snapshot.Done -> PendingDeviceCall(State.Done(snapshot.result))
        }
    }
}
