package pro.ikili.kibicomputers.core.wasi.p1

import pro.ikili.kibicomputers.core.kernel.ProcessKilledException
import pro.ikili.kibicomputers.core.kernel.ResourceLimitException
import pro.ikili.kibicomputers.core.kernel.fd.FdIo
import pro.ikili.kibicomputers.core.kernel.fd.FdReadiness
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.time.saturatingAdd
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.runtime.WasmMemory

/**
 * WASI p1 の `poll_oneoff`（#72、ADR 0072）。カーネルの待ちの土台（[WaitKey]、`ctx.block`）の上の薄い層。
 *
 * - fd の購読（`FD_READ`・`FD_WRITE`）は、fd が指すオブジェクトの `(objectId, READABLE / WRITABLE)` で待つ（判定は [FdIo.pollLocked]）。
 *   イベントキュー（#63）とタイマー（#64）の fd も `FD_READ` で待てる
 * - 時計の購読は、入った時点で単調時計の期限にし、1回の呼び出しにつき一時的なタイマー1つ（`ctx.openWaitTimer`）で、
 *   いちばん早い期限に合わせて待つ。そろったかは「単調時計の今 ≥ 期限」で決める
 * - [blocking] が `true`（`BLOCKING` のプロセス）なら、そろうまで `ctx.block` で待つ。`false` なら、そろっていなければ `AGAIN`
 *   （待ちに入る形は #329 が [evaluate] の [Outcome.Wait] を使って足す）
 * - 燃料: 呼び出しごとの `WasiP1Fd.CALL_FUEL`（[WasiP1] の包みが引く）に加え、判定のたびに購読1つにつき [SUBSCRIPTION_FUEL]
 */
internal class WasiP1Poll(private val ctx: ProcessContext, private val basic: WasiP1Basic, private val blocking: Boolean) {
    /** 読み込んだ購読（実行時だけ。保存できる値だけで作ってある） */
    sealed interface Subscription {
        val userdata: Long

        /** 時計。[deadlineNanos] はマシンの単調時計の期限 */
        data class Clock(override val userdata: Long, val deadlineNanos: Long) : Subscription

        /** fd の読み（`READABLE`）か書き（`WRITABLE`） */
        data class Fd(override val userdata: Long, val fd: Int, val condition: WaitCondition) : Subscription

        /** 読んだ時点で失敗が決まった購読（知らない時計、知らないフラグ）。[type] は p1 の eventtype */
        data class Invalid(override val userdata: Long, val type: Int, val errno: Errno) : Subscription
    }

    /** 結果の `event` 1つ */
    data class Event(val userdata: Long, val error: Int, val type: Int, val nbytes: Long = 0, val hangup: Boolean = false)

    /** 1回の判定の結果 */
    sealed interface Outcome {
        /** そろった。[events] は `out` に書く順（購読の順） */
        class Ready(val events: List<Event>) : Outcome

        /** まだ。[keys] は fd の key、[deadlineNanos] は時計の購読のいちばん早い期限（なければ `null`） */
        class Wait(val keys: List<WaitKey>, val deadlineNanos: Long?) : Outcome
    }

    /** `poll_oneoff(in*, out*, nsubscriptions, nevents*)`。p1 の errno を返す */
    @Throws(ProcessKilledException::class)
    fun pollOneoff(memory: WasmMemory, inPtr: Int, outPtr: Int, count: Int, neventsPtr: Int): Int {
        val n = P1.u32(count.toLong())
        if (n == 0L || n > MAX_SUBSCRIPTIONS) return Errno.INVAL.code
        if (!P1.inBounds(memory, inPtr, n * P1.SUBSCRIPTION_SIZE) ||
            !P1.inBounds(memory, outPtr, n * P1.EVENT_SIZE) ||
            !P1.inBounds(memory, neventsPtr, 4)
        ) {
            return Errno.FAULT.code
        }
        // 全部読んでから書く（in と out が重なっていても壊れないように）
        val raw = memory.read(inPtr, (n * P1.SUBSCRIPTION_SIZE).toInt())
        val subscriptions = parse(raw, n.toInt()) ?: return Errno.INVAL.code
        val events = try {
            await(subscriptions) ?: return Errno.AGAIN.code
        } catch (_: ResourceLimitException) {
            // 一時的なタイマーを動かせない（マシンの動いているタイマーが上限）。一時的な資源の不足
            return Errno.AGAIN.code
        }
        val out = ByteArray(events.size * P1.EVENT_SIZE)
        events.forEachIndexed { i, e ->
            val at = i * P1.EVENT_SIZE
            P1.putI64(out, at, e.userdata)
            P1.putI16(out, at + 8, e.error)
            out[at + 10] = e.type.toByte()
            P1.putI64(out, at + 16, e.nbytes)
            P1.putI16(out, at + 24, if (e.hangup) P1.EVENTRWFLAGS_FD_READWRITE_HANGUP else 0)
        }
        memory.write(outPtr, out)
        memory.writeI32(neventsPtr, events.size)
        return 0
    }

    /** 購読を読む。知らないタグがあれば `null`（呼び出し全体の `INVAL`） */
    private fun parse(raw: ByteArray, count: Int): List<Subscription>? {
        val monotonicNow = ctx.monotonicNanos()
        var realtimeNow: Long? = null
        val subs = ArrayList<Subscription>(count)
        for (i in 0 until count) {
            val at = i * P1.SUBSCRIPTION_SIZE
            val userdata = P1.getI64(raw, at)
            subs += when (raw[at + 8].toInt() and 0xff) {
                P1.EVENTTYPE_CLOCK -> {
                    val id = P1.getI32(raw, at + 16)
                    val timeout = P1.getI64(raw, at + 24)
                    val flags = (raw[at + 40].toInt() and 0xff) or ((raw[at + 41].toInt() and 0xff) shl 8)
                    val absolute = flags and P1.SUBCLOCKFLAGS_ABSTIME != 0
                    val deadline = when {
                        flags and P1.SUBCLOCKFLAGS_ABSTIME.inv() != 0 -> null
                        id !in P1.CLOCK_REALTIME..P1.CLOCK_THREAD_CPUTIME -> null
                        !absolute -> saturatingAdd(monotonicNow, u64(timeout))
                        id == P1.CLOCK_MONOTONIC -> u64(timeout)
                        id == P1.CLOCK_REALTIME -> {
                            // 入った時点で単調時計へ写す（その後に実時間が飛んでも追わない。ADR 0064）
                            val real = realtimeNow ?: ctx.environment.clock.realtimeNanos().also { realtimeNow = it }
                            val t = u64(timeout)
                            if (t == Long.MAX_VALUE) Long.MAX_VALUE else saturatingAdd(monotonicNow, t - real)
                        }
                        // PROCESS_CPUTIME・THREAD_CPUTIME: clock_time_get と同じ起点
                        else -> saturatingAdd(basic.startNanos, u64(timeout))
                    }
                    if (deadline == null) {
                        Subscription.Invalid(userdata, P1.EVENTTYPE_CLOCK, Errno.INVAL)
                    } else {
                        Subscription.Clock(userdata, deadline)
                    }
                }
                P1.EVENTTYPE_FD_READ -> Subscription.Fd(userdata, P1.getI32(raw, at + 16), WaitCondition.READABLE)
                P1.EVENTTYPE_FD_WRITE -> Subscription.Fd(userdata, P1.getI32(raw, at + 16), WaitCondition.WRITABLE)
                // 知らないタグ: 中身を解釈できないので、呼び出し全体を INVAL にする（wasmtime と同じ）
                else -> return null
            }
        }
        return subs
    }

    /**
     * そろうまで判定を繰り返す。そろったイベントを返す。[blocking] でなく、そろっていなければ `null`。
     * 一時的なタイマーは待つときにだけ作り、返るとき（例外のときも）に必ず閉じる
     */
    @Throws(ProcessKilledException::class, ResourceLimitException::class)
    private fun await(subscriptions: List<Subscription>): List<Event>? {
        var timer: WaitKey? = null
        var timerDeadline = 0L
        var timerStale = false
        try {
            while (true) {
                when (val outcome = evaluate(subscriptions)) {
                    is Outcome.Ready -> return outcome.events
                    is Outcome.Wait -> {
                        if (!blocking) return null
                        val keys = ArrayList<WaitKey>(outcome.keys.size + 1)
                        keys += outcome.keys
                        val deadline = outcome.deadlineNanos
                        if (deadline != null) {
                            val t = timer
                            if (t == null) {
                                timer = ctx.openWaitTimer(deadline)
                            } else if (timerStale || deadline != timerDeadline) {
                                // 鳴ったのに期限が来ていない（時計が戻された、など）。まだの期限に合わせ直す
                                ctx.resetWaitTimer(t, deadline)
                            }
                            timerDeadline = deadline
                            timerStale = false
                            keys += timer!!
                        }
                        val ready = ctx.block(keys)
                        if (timer != null && timer in ready) timerStale = true
                    }
                }
            }
        } finally {
            timer?.let { ctx.closeWaitTimer(it) }
        }
    }

    /**
     * 全部の購読を1回判定する（待たない）。カーネルロックは自分で取る。そろった購読（エラーを含む）が1つでもあれば [Outcome.Ready]。
     * 判定のたびに購読1つにつき [SUBSCRIPTION_FUEL] を引く
     */
    fun evaluate(subscriptions: List<Subscription>): Outcome {
        ctx.chargeFuel(SUBSCRIPTION_FUEL * subscriptions.size)
        val now = ctx.monotonicNanos()
        return ctx.withKernelLock {
            var events: ArrayList<Event>? = null
            val keys = ArrayList<WaitKey>()
            var deadline: Long? = null
            fun add(e: Event) {
                (events ?: ArrayList<Event>().also { events = it }) += e
            }
            for (s in subscriptions) {
                when (s) {
                    is Subscription.Clock ->
                        if (now >= s.deadlineNanos) {
                            add(Event(s.userdata, 0, P1.EVENTTYPE_CLOCK))
                        } else {
                            deadline = minOf(deadline ?: Long.MAX_VALUE, s.deadlineNanos)
                        }
                    is Subscription.Fd -> {
                        val type = if (s.condition == WaitCondition.READABLE) P1.EVENTTYPE_FD_READ else P1.EVENTTYPE_FD_WRITE
                        when (val r = FdIo.pollLocked(ctx.kernel, ctx.vfs, ctx.pid, s.fd, s.condition)) {
                            is FdReadiness.Ready -> add(Event(s.userdata, 0, type, r.nbytes, r.hangup))
                            is FdReadiness.Pending -> if (r.key !in keys) keys += r.key
                            is FdReadiness.Error -> add(Event(s.userdata, r.errno.code, type))
                        }
                    }
                    is Subscription.Invalid -> add(Event(s.userdata, s.errno.code, s.type))
                }
            }
            val ready = events
            if (ready != null) Outcome.Ready(ready) else Outcome.Wait(keys, deadline)
        }
    }

    /** u64 の値を、`Long` の範囲に飽和させる（2^63 以上は [Long.MAX_VALUE]） */
    private fun u64(v: Long): Long = if (v < 0) Long.MAX_VALUE else v

    companion object {
        /** 1回の呼び出しの購読の上限（仮の値。確定は #251。Linux の `poll` が `RLIMIT_NOFILE` を超えると `EINVAL` なのに合わせる） */
        const val MAX_SUBSCRIPTIONS: Int = 1024

        /** 判定のたびに、購読1つにかかる燃料（仮の値。確定は #251） */
        const val SUBSCRIPTION_FUEL: Long = 10
    }
}
