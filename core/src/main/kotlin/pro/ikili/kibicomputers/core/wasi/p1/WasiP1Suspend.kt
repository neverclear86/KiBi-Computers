package pro.ikili.kibicomputers.core.wasi.p1

import pro.ikili.kibicomputers.core.kernel.process.AsyncWait
import pro.ikili.kibicomputers.core.kernel.process.Process
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.runtime.WasmAsyncState
import pro.ikili.kibicomputers.core.runtime.WasmInstance

/** p1 の止まりうる import の待ち方（[WasiP1.register]） */
internal enum class P1Wait {
    /** ホスト関数の中で `ctx.block` で待つ（`BLOCKING` のプロセス） */
    BLOCK,

    /** 巻き戻して `_start` から戻り、`StepResult.Wait` で待つ（待機点への変換をかけた `WAIT_POINT` のプロセス。#329） */
    UNWIND,

    /** 待たずに `AGAIN` を返す */
    NONE,
}

/**
 * 待機点への変換をかけた p1 のプロセスの、止まりうる import（[WasiP1.WAIT_IMPORTS]）の入口と出口の共通の処理（#329）。
 *
 * - 入口（[enter]）: 状態が巻き直し中なら、退避した値が空になっていることを確かめて `NORMAL` に戻す（止まった呼び出しに着いた）。
 *   その呼び出しは最初からやり直す。前に巻き戻したときの [AsyncWait] は [resumed] で受け取る
 * - 出口（[unwind]）: 待つ key と [AsyncWait] を記録し、状態を `UNWINDING` にする。呼んだ側はダミーの戻り値（errno 0）で戻り、
 *   戻り値は変換したコードが捨てる。[WasiP1Driver] が `_start` から戻ったところで [takePendingKeys] を `StepResult.Wait` にする
 * - インスタンスを作っている途中（モジュールの start 関数の中）は巻き戻せないので、[canUnwind] が `false`（呼んだ側は `ctx.block` で待つ）
 *
 * 状態のグローバルは、待っている間は常に `NORMAL`（ドライバーが巻き戻しを受け取ったら戻し、再開の直前に `REWINDING` にする）。
 */
internal class WasiP1Suspend(private val ctx: ProcessContext) {
    /** 今の呼び出しのインスタンス（[enter] で入れる） */
    private var caller: WasmInstance? = null

    /** 巻き戻すときに記録した、待つ key（`_start` から戻るまでの間だけ。実行時だけ） */
    private var pendingKeys: List<WaitKey>? = null

    /** 巻き直して着いた呼び出しなら、前に巻き戻したときの [AsyncWait]（[enter] で入れ、呼んだ側が読む） */
    var resumed: AsyncWait? = null
        private set

    /** 止まりうる import の入口。[instance] は呼んだインスタンス */
    fun enter(instance: WasmInstance) {
        caller = instance
        resumed = null
        if (!instance.module.isAsyncified) return
        if (instance.asyncState != WasmAsyncState.REWINDING) return
        val process = ctx.withKernelLock { process() }
        val left = ctx.withKernelLock { process.asyncStack?.size ?: 0 }
        // 止まった呼び出しに着いたのに退避した値が残っている: 巻き直しが食い違った（このプロセスだけを CRASHED にする）
        check(left == 0) { "巻き直しの終わりに退避した値が $left 個残っている" }
        instance.asyncState = WasmAsyncState.NORMAL
        resumed = ctx.withKernelLock {
            val w = process.asyncWait
            process.asyncWait = null
            w
        }
    }

    /** 今の呼び出しで巻き戻せるか（変換したモジュールで、インスタンスを作っている途中でない） */
    fun canUnwind(): Boolean {
        val instance = caller ?: return false
        if (!instance.module.isAsyncified) return false
        return ctx.withKernelLock { process().state != ProcessState.LOADING }
    }

    /** [keys] のどれかがそろうまで待つために巻き戻す。[wait] はやり直すときに要る値（なければ `null`） */
    fun unwind(keys: List<WaitKey>, wait: AsyncWait?) {
        require(keys.isNotEmpty()) { "待つ条件がない" }
        val instance = checkNotNull(caller) { "止まりうる import の外で巻き戻した" }
        ctx.withKernelLock { process().asyncWait = wait }
        pendingKeys = keys
        instance.asyncState = WasmAsyncState.UNWINDING
    }

    /** 巻き戻したときに記録した key を取り出す（[WasiP1Driver] が `_start` から戻ったところで） */
    fun takePendingKeys(): List<WaitKey> {
        val keys = checkNotNull(pendingKeys) { "巻き戻したのに待つ key がない" }
        pendingKeys = null
        return keys
    }

    private fun process(): Process = checkNotNull(ctx.kernel.processes[ctx.pid]) { "プロセス ${ctx.pid} がない" }
}
