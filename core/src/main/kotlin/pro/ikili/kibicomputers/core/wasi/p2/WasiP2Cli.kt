package pro.ikili.kibicomputers.core.wasi.p2

import pro.ikili.kibicomputers.core.kernel.fd.STDERR
import pro.ikili.kibicomputers.core.kernel.fd.STDIN
import pro.ikili.kibicomputers.core.kernel.fd.STDOUT
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.tty.Tty
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.HostFunctionBody
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1Basic
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1Fd
import java.util.concurrent.ThreadLocalRandom

/**
 * `wasi:cli`・`wasi:clocks`・`wasi:random` の実装（#267。ADR 0267）。値は p1 の層と同じ土台から取る:
 *
 * - `get-arguments` は argv[0] を含む（p1 の `args_get` と同じ並び）。`get-environment` はプロセスの環境変数から `PWD` を除いたもの
 *   （cwd は専用の `initial-cwd` で渡す。親が `PWD` を入れていても、古い値がゲストに見えて `initial-cwd` とずれないようにする。
 *   p1 の `environ_*` が `PWD` を cwd にして見せるのとは違う。ADR 0267・0332）。`initial-cwd` は起動したときのカーネルの cwd
 * - `exit(ok)` → 0、`exit(err)` → 1、`exit-with-code(n)` → n（ADR 0074）。[WasiP2Exit] を投げ、ドライバーが終了コードにする
 * - `get-stdin`・`get-stdout`・`get-stderr` は、呼ぶたびに fd 0/1/2 のオブジェクトの参照を 1 つ増やした新しい stream を返す
 *   （dup と同じ。後で fd が閉じても stream は残る）。fd が閉じていれば、閉じた stream
 * - `get-terminal-*` は、その fd が `Tty` を指すときだけ `some`（p1 の `fd_fdstat_get` の判定と同じ。ADR 0060）
 * - 単調時計は `ctx.monotonicNanos`（マシンを作ったときが 0。復元をまたいでも同じ時計。ADR 0064）、壁時計は環境の実時間。
 *   分解能は p1 と同じ [WasiP1Basic.CLOCK_RESOLUTION_NANOS]
 * - `wasi:random/random` は環境の乱数の元（`EntropySource`。ADR 0070）、`insecure` は速い擬似乱数
 */
internal object WasiP2Cli {
    /** `get-random-bytes`・`get-insecure-random-bytes` の 1 回の上限（仮の値。確定は #251）。len どおりに返す関数なので、超えたらトラップ */
    const val MAX_RANDOM_BYTES: Long = 1L shl 20

    fun functions(t: WasiP2Types): Map<String, Map<String, HostFunctionBody>> {
        fun body(f: (ProcessContext, List<CanonValue>) -> CanonValue?) = HostFunctionBody { call, args -> f(P2.ctx(call), args) }
        val datetime = t["wasi:clocks/wall-clock"].type<CanonType.Record>("datetime")
        fun dt(nanos: Long): CanonValue = datetime.value(
            "seconds" to CanonValue.I64(Math.floorDiv(nanos, 1_000_000_000L)),
            "nanoseconds" to CanonValue.I32(Math.floorMod(nanos, 1_000_000_000L).toInt()),
        )
        val stdio = { fd: Int, input: Boolean -> body { ctx, _ -> stdioStream(ctx, fd, input) } }
        val terminal = { fd: Int, label: String -> body { ctx, _ -> terminal(ctx, fd, label) } }
        return mapOf(
            "wasi:cli/environment" to mapOf(
                "get-environment" to body { ctx, _ ->
                    CanonValue.Items(
                        ctx.env.filterKeys { it != WasiP1Basic.PWD }.map { (k, v) -> CanonValue.Fields(listOf(CanonValue.str(k), CanonValue.str(v))) },
                    )
                },
                "get-arguments" to body { ctx, _ -> CanonValue.Items(ctx.args.map(CanonValue::str)) },
                "initial-cwd" to body { ctx, _ -> CanonValue.some(CanonValue.str(initialCwd(ctx))) },
            ),
            "wasi:cli/exit" to mapOf(
                "exit" to body { _, a -> throw WasiP2Exit(if ((a[0] as CanonValue.Case).index == 0) 0 else 1) },
                "exit-with-code" to body { _, a -> throw WasiP2Exit((a[0] as CanonValue.I32).value and 0xff) },
            ),
            "wasi:cli/stdin" to mapOf("get-stdin" to stdio(STDIN, true)),
            "wasi:cli/stdout" to mapOf("get-stdout" to stdio(STDOUT, false)),
            "wasi:cli/stderr" to mapOf("get-stderr" to stdio(STDERR, false)),
            "wasi:cli/terminal-input" to emptyMap(),
            "wasi:cli/terminal-output" to emptyMap(),
            "wasi:cli/terminal-stdin" to mapOf("get-terminal-stdin" to terminal(STDIN, "terminal-input")),
            "wasi:cli/terminal-stdout" to mapOf("get-terminal-stdout" to terminal(STDOUT, "terminal-output")),
            "wasi:cli/terminal-stderr" to mapOf("get-terminal-stderr" to terminal(STDERR, "terminal-output")),
            "wasi:clocks/monotonic-clock" to mapOf(
                "now" to body { ctx, _ -> CanonValue.I64(ctx.monotonicNanos()) },
                "resolution" to body { _, _ -> CanonValue.I64(WasiP1Basic.CLOCK_RESOLUTION_NANOS) },
                "subscribe-instant" to body { ctx, a -> WasiP2Io.deadline(ctx, P2.u64(a[0])) },
                "subscribe-duration" to body { ctx, a ->
                    val now = ctx.monotonicNanos()
                    val d = P2.u64(a[0])
                    // 飽和加算（u64 の大きな値は「来ない」）
                    WasiP2Io.deadline(ctx, if (d > Long.MAX_VALUE - now) Long.MAX_VALUE else now + d)
                },
            ),
            "wasi:clocks/wall-clock" to mapOf(
                "now" to body { ctx, _ -> dt(ctx.environment.clock.realtimeNanos()) },
                "resolution" to body { _, _ -> dt(WasiP1Basic.CLOCK_RESOLUTION_NANOS) },
            ),
            "wasi:random/random" to mapOf(
                "get-random-bytes" to body { ctx, a -> CanonValue.Bytes(randomBytes(ctx, P2.u64(a[0]), secure = true)) },
                "get-random-u64" to body { ctx, _ -> CanonValue.I64(randomLong(ctx)) },
            ),
            "wasi:random/insecure" to mapOf(
                "get-insecure-random-bytes" to body { ctx, a -> CanonValue.Bytes(randomBytes(ctx, P2.u64(a[0]), secure = false)) },
                "get-insecure-random-u64" to body { _, _ -> CanonValue.I64(ThreadLocalRandom.current().nextLong()) },
            ),
            "wasi:random/insecure-seed" to mapOf(
                "insecure-seed" to body { ctx, _ -> CanonValue.Fields(listOf(CanonValue.I64(randomLong(ctx)), CanonValue.I64(randomLong(ctx)))) },
            ),
        )
    }

    /** `initial-cwd`（初めて使うときのカーネルの cwd を覚える。ドライバーは起動のときに入れる） */
    fun initialCwd(ctx: ProcessContext): String = ctx.withKernelLock {
        val table = P2.tableLocked(ctx)
        (table.initialCwd ?: ctx.cwd.also { table.initialCwd = it }).toString()
    }

    private fun stdioStream(ctx: ProcessContext, fd: Int, input: Boolean): CanonValue = ctx.withKernelLock {
        val process = ctx.kernel.processes[ctx.pid] ?: P2.trap("プロセスがない")
        val entry = process.handles[fd]
        if (entry != null) ctx.kernel.objects.retain(entry.objectId)
        try {
            P2.ownLocked(ctx, StreamEntry(entry, input, shared = true))
        } catch (e: Throwable) {
            if (entry != null) ctx.kernel.objects.release(entry.objectId, ctx.wakeSink)
            throw e
        }
    }

    private fun terminal(ctx: ProcessContext, fd: Int, label: String): CanonValue = ctx.withKernelLock {
        val process = ctx.kernel.processes[ctx.pid] ?: P2.trap("プロセスがない")
        val entry = process.handles[fd]
        if (entry == null || ctx.kernel.objects[entry.objectId] !is Tty) CanonValue.NONE else CanonValue.some(P2.ownLocked(ctx, MarkerEntry(label)))
    }

    private fun randomBytes(ctx: ProcessContext, len: Long, secure: Boolean): ByteArray {
        if (len > MAX_RANDOM_BYTES) P2.trap("乱数のバイト数が上限（$MAX_RANDOM_BYTES）を超えた（$len）")
        val out = ByteArray(len.toInt())
        if (secure) ctx.environment.entropy.fill(out) else ThreadLocalRandom.current().nextBytes(out)
        ctx.chargeFuel(len / WasiP1Fd.BYTES_PER_FUEL)
        return out
    }

    private fun randomLong(ctx: ProcessContext): Long {
        val b = ByteArray(8)
        ctx.environment.entropy.fill(b)
        var v = 0L
        for (x in b) v = (v shl 8) or (x.toLong() and 0xff)
        return v
    }
}

/**
 * `wasi:cli/exit` の `exit`・`exit-with-code` で投げる。ゲストの呼び出しを抜けて、[WasiP2Driver] が [code] を終了コードにする
 * （トラップと区別するため `WasmTrapException` にしない）。スタックトレースは持たない
 */
internal class WasiP2Exit(val code: Int) : RuntimeException("exit($code)", null, false, false)
