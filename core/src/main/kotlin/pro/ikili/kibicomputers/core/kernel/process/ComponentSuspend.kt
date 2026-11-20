package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.kernel.wait.WaitKey

/**
 * 待機点への変換をかけたコンポーネント（`WAIT_POINT`）のプロセスの、待ちうるホストの関数の入口と出口（#757。ADR 0757）。
 * p1 の `WasiP1Suspend` に当たる。プロセスごとに 1 つ（[KernelLinkEnv.suspend]）で、ホストの関数（サーバー全体で 1 つ）は
 * 状態を持たずにここを使う。
 *
 * - 入口（[enter]）: 巻き直して着いた呼び出しなら、前に巻き戻したときの [AsyncWait] を返す（[Arrival]）。
 *   着いたことは `LinkedComponent` が知らせる（[onArrived]。そのときに退避した値が空になっていることを確かめる）
 * - 出口（[unwind]）: 待つ key と [AsyncWait] を記録し、`LinkedComponent` に巻き戻しを求める。ホストの関数はそのまま戻る
 *   （戻り値は捨てられる）。`WasiP2Driver` が `run` から戻ったところで [takePendingKeys] を `StepResult.Wait` にする
 * - 巻き戻せないところ（作っている途中、realloc・post-return の中、ゲストからゲストへの呼び出しの下）では [canUnwind] が `false`
 *   （呼んだ側は `ctx.block` で待つ。待機点型で作っている途中でないなら、そもそも来ない。`ComponentAsyncify` が判定で断る）
 *
 * 巻き直しでは、ホストの関数をはじめからやり直す（引数は lift し直す。待っている間ゲストのメモリは変わらないので、同じ値になる）。
 * そのため、待ちうる関数は、待つ前に結果の resource を作らず、own の引数を受けない（[pro.ikili.kibicomputers.core.runtime.component.link.HostWait.UNWIND]）。
 * 一部まで進めてから待つもの（パイプへの書き込み）の進み具合は、ホストの resource（`StreamEntry`）に持つ。
 *
 * 状態はプロセスのスレッドだけが読み書きする（step の受け渡しはカーネルロックを通る。待機点型は step ごとにワーカーのスレッドが変わるので、
 * ホストの関数が持つ待ち（[pendingInvoke]・[pendingSpawn]）は念のため `@Volatile` にする）
 */
internal class ComponentSuspend(private val ctx: ProcessContext) {
    /** 巻き直して着いた呼び出しの情報。[wait] は前に巻き戻したときのもの */
    class Arrival(val wait: AsyncWait?)

    /** 巻き戻して結果を待っている `kibi:device` の `invoke` の `pending-call`（ホストの resource の表の rep [slot] と、その `READABLE` の [key]） */
    class PendingInvoke(val slot: Int, val key: WaitKey)

    private var pendingKeys: List<WaitKey>? = null
    private var arrival: Arrival? = null

    /**
     * 巻き戻して結果を待っている `invoke`（#800）。実行時だけの状態で、[AsyncWait] には入れない（カーネルの状態・写しに混ぜない）。
     * `pending-call` はゲストに rep を渡さない、表の隠れた項目（結果を受け取ったら外して閉じる。殺されたら表を閉じるときに取り消される）
     */
    @Volatile
    var pendingInvoke: PendingInvoke? = null

    /**
     * 巻き戻して読み込みを待っている `kibi:process` の `spawn`・`spawn-with-handles`（#800）。実行時だけの状態（[AsyncWait] には入れない）。
     * 殺されたら、カーネルの後始末（`finishLocked` が `HostCompletion` を閉じ、始まっていない読み込みを取り消す）に任せる
     */
    @Volatile
    var pendingSpawn: ExecWait? = null

    /** `LinkedComponent` の巻き直しが、巻き戻したホストの関数の呼び出しに着いた（`LinkEnv.onRewound`） */
    fun onArrived() {
        val wait = ctx.withKernelLock {
            val process = process()
            val left = process.asyncStack?.size ?: 0
            // 止まった呼び出しに着いたのに退避した値が残っている: 巻き直しが食い違った（このプロセスだけを CRASHED にする）
            check(left == 0) { "巻き直しの終わりに退避した値が $left 個残っている" }
            val w = process.asyncWait
            process.asyncWait = null
            w
        }
        arrival = Arrival(wait)
    }

    /** 待ちうるホストの関数の入口。巻き直して着いた呼び出しなら [Arrival]、そうでなければ `null` */
    fun enter(): Arrival? {
        val a = arrival
        arrival = null
        return a
    }

    /** 今の呼び出しで巻き戻せるか（待機点型で、作っている途中でなく、ゲストを呼んでいる途中でなく、コンポーネントが巻き戻せる） */
    fun canUnwind(): Boolean {
        if (ctx.inGuestCall) return false
        val ok = ctx.withKernelLock {
            val p = process()
            p.kind == ProcessKind.WAIT_POINT && p.state != ProcessState.LOADING
        }
        return ok && ctx.component.canUnwind()
    }

    /** [keys] のどれかがそろうまで待つために巻き戻す。[wait] はやり直すときに要る値（なければ `null`） */
    fun unwind(keys: List<WaitKey>, wait: AsyncWait?) {
        require(keys.isNotEmpty()) { "待つ条件がない" }
        check(arrival == null) { "巻き直して着いた情報を受け取らずに巻き戻した" }
        ctx.component.requestUnwind()
        ctx.withKernelLock { process().asyncWait = wait }
        pendingKeys = keys
    }

    /** 巻き戻したときに記録した key がある（ホストの待ちで巻き戻した。燃料切れで巻き戻したなら `false`。#803） */
    val hasPendingKeys: Boolean get() = pendingKeys != null

    /** 巻き戻したときに記録した key を取り出す（`WasiP2Driver` が `run` から戻ったところで） */
    fun takePendingKeys(): List<WaitKey> {
        val keys = checkNotNull(pendingKeys) { "巻き戻したのに待つ key がない" }
        pendingKeys = null
        return keys
    }

    private fun process(): Process = checkNotNull(ctx.kernel.processes[ctx.pid]) { "プロセス ${ctx.pid} がない" }
}
