package pro.ikili.kibicomputers.core.wasi.p2

import pro.ikili.kibicomputers.core.kernel.ResourceLimitException
import pro.ikili.kibicomputers.core.kernel.fd.FdIo
import pro.ikili.kibicomputers.core.kernel.fd.FdReadiness
import pro.ikili.kibicomputers.core.kernel.fd.IoResult
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.AsyncWait
import pro.ikili.kibicomputers.core.kernel.process.ComponentSuspend
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.OpenFile
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentCall
import pro.ikili.kibicomputers.core.runtime.component.link.HostFunctionBody
import pro.ikili.kibicomputers.core.runtime.component.link.HostWait
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1Fd
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1Poll

/**
 * `wasi:io`（error・poll・streams）の実装（#267。ADR 0267）。読み書きは p1 と同じ [FdIo]（パイプ・TTY）と VFS（ファイル）を使う。
 *
 * - stream の約束（仕様のトラップ）: `write` は直前の `check-write` の許可量を超えたらトラップ、`blocking-write-and-flush` は
 *   4096 バイトを超えたらトラップ。読めるものがない `read` は空のリスト、書く側が閉じたら `closed`、I/O の失敗は
 *   `last-operation-failed`（errno を持つ `error`）
 * - `splice`・`blocking-splice` は WIT の約束どおり、`check-write` → 許可量と `len` の小さい方で `read` → `write` の順で動く。
 *   出力が受けられない（許可量 0）なら、入力を読まずに 0 を返す（`splice`）か、受けられるまで待つ（`blocking-splice`）。
 *   出力が閉じている・失敗しているのは、入力を読む前に分かる。読んだ後の `write` の失敗は、読んだバイトが戻らないまま報告する（WIT の定義の
 *   とおり。`write` は許可量以内なので、待つのは同じパイプへ書く別の書き手がいるときだけ）
 * - 書き込みは受け付けたらすぐ下へ書く（`flush` は何もしない）。パイプが一部しか受けなければ、全部書くまで待って書く
 *   （`FdIo.writeBlocking` と同じ。待つのは BLOCKING のプロセスのスレッドで `ctx.block`）
 * - `poll`: 空のリストはトラップ、上限は p1 の `poll_oneoff` と同じ [WasiP1Poll.MAX_SUBSCRIPTIONS]。待ち方は p1 と同じ
 *   （そろったかを見る → まだなら key を集め、時計があれば一時的なタイマーを 1 つ開いて `ctx.block` → 見直す。ADR 0329）
 *
 * 待機点（#757。ADR 0757）: 待ちうる関数（[WaitingBody]）は、巻き戻せるとき（[ComponentSuspend.canUnwind]）は `ctx.block` の代わりに
 * 巻き戻して待つ（p1 の `poll_oneoff` と同じ形: 時計の一時的なタイマーは閉じずに [AsyncWait] に引き継ぎ、巻き直したら期限を合わせ直す）。
 * 巻き戻すときは、結果の resource を作らず、ゲストに見える状態も変えない（巻き直しで、同じ引数のまま最初からやり直す）。
 * ただし、パイプへの書き込みは一部まで書いてから待つことがあるので、書き終えた数（[StreamEntry.writeProgress]）と、`splice` で
 * 読んだバイト（[StreamEntry.pendingSplice]）を出力の stream に持ち、巻き直したらその続きから書く（重ねて書かない・読んだ分を失わない）。
 * `write`・`write-zeroes` は、巻き直したら許可量をもう一度引かない
 */
internal object WasiP2Io {
    const val ERROR: String = "wasi:io/error"
    const val POLL: String = "wasi:io/poll"
    const val STREAMS: String = "wasi:io/streams"

    /** `poll` の判定ごとに、pollable 1 つにかかる燃料（p1 と同じ仮の値。確定は #251） */
    private const val POLLABLE_FUEL: Long = WasiP1Poll.SUBSCRIPTION_FUEL

    private val EMPTY = CanonValue.Bytes(ByteArray(0))

    /**
     * 待ちうる関数の本体（#757）。巻き戻して待てる（[HostWait.UNWIND]）。待ち方は本体がこれかどうかで決まる（[waitOf]）ので、
     * 名前の表と本体が食い違うことはない（巻き戻すのに `UNWIND` と宣言していない・宣言したのに巻き直しの [ComponentSuspend.Arrival] を
     * 受け取らない、のどちらも作れない）
     */
    class WaitingBody(private val body: HostFunctionBody) : HostFunctionBody {
        override fun call(call: ComponentCall, args: List<CanonValue>): CanonValue? = body.call(call, args)
    }

    /** `wasi:*` のホストの関数の本体 [body] の待ち方（#757） */
    fun waitOf(body: HostFunctionBody): HostWait = if (body is WaitingBody) HostWait.UNWIND else HostWait.NONE

    /**
     * 待ちうる関数の呼び出し 1 回の、待ち方（#757）。[arrival] は、巻き直して着いた呼び出しなら前に巻き戻したときの情報
     */
    class Waiter(private val suspend: ComponentSuspend, val arrival: ComponentSuspend.Arrival?) {
        /** 巻き直して着いた呼び出しか */
        val resumed: Boolean get() = arrival != null

        /** 巻き戻して待てるか */
        fun canUnwind(): Boolean = suspend.canUnwind()

        /** [keys] のどれかがそろうまで待つ。巻き戻したら `true`（呼んだ側は何も変えずにすぐ戻る）、`ctx.block` で待ったら `false` */
        fun await(ctx: ProcessContext, keys: List<WaitKey>, wait: AsyncWait? = null): Boolean {
            if (canUnwind()) {
                suspend.unwind(keys, wait)
                return true
            }
            ctx.block(keys)
            return false
        }

        /** 巻き戻す（[canUnwind] を確かめてから） */
        fun unwind(keys: List<WaitKey>, wait: AsyncWait?) = suspend.unwind(keys, wait)
    }

    fun functions(t: WasiP2Types): Map<String, Map<String, HostFunctionBody>> {
        val streamError = t[STREAMS].type<CanonType.Variant>("stream-error")
        val closed = CanonValue.err(streamError.case("closed"))
        fun failed(ctx: ProcessContext, errno: Errno): CanonValue =
            CanonValue.err(streamError.case("last-operation-failed", P2.own(ctx, ErrorEntry(errno))))

        /** 読みの結果を、`result<list<u8>, stream-error>` にする（[skip] なら数だけ。巻き戻したら `null`） */
        fun readResult(ctx: ProcessContext, r: ReadOutcome, skip: Boolean): CanonValue? = when (r) {
            ReadOutcome.Unwound -> null
            is ReadOutcome.Bytes -> {
                ctx.chargeFuel(r.count.toLong() / WasiP1Fd.BYTES_PER_FUEL)
                CanonValue.ok(if (skip) CanonValue.I64(r.count.toLong()) else CanonValue.Bytes(r.buffer.copyOf(r.count)))
            }
            ReadOutcome.Empty -> CanonValue.ok(if (skip) CanonValue.I64(0) else EMPTY)
            ReadOutcome.Closed -> closed
            is ReadOutcome.Failed -> failed(ctx, r.errno)
        }

        fun writeResult(ctx: ProcessContext, r: WriteOutcome, value: CanonValue? = null): CanonValue? = when (r) {
            WriteOutcome.Unwound -> null
            WriteOutcome.Done -> CanonValue.ok(value)
            WriteOutcome.Closed -> closed
            is WriteOutcome.Failed -> failed(ctx, r.errno)
        }

        fun body(f: (ProcessContext, List<CanonValue>) -> CanonValue?) = HostFunctionBody { call, args -> f(P2.ctx(call), args) }

        /** 待ちうる関数（[WaitingBody]）の本体。入口で、巻き直して着いた呼び出しかを受け取る（#757） */
        fun waiting(f: (ProcessContext, Waiter, List<CanonValue>) -> CanonValue?) = WaitingBody(HostFunctionBody { call, args ->
            val suspend = P2.suspend(call)
            f(P2.ctx(call), Waiter(suspend, suspend.enter()), args)
        })

        val error = mapOf(
            "[method]error.to-debug-string" to body { ctx, a ->
                val e = P2.entry<ErrorEntry>(ctx, a[0])
                CanonValue.str("${e.errno.name.lowercase()}（${WasiP2Errors.name(e.errno)}）")
            },
        )
        val poll = mapOf(
            "[method]pollable.ready" to body { ctx, a -> CanonValue.Bool(readyOnce(ctx, listOf(rep(a[0])))) },
            "[method]pollable.block" to waiting { ctx, w, a ->
                await(ctx, w, listOf(rep(a[0])))
                null
            },
            "poll" to waiting { ctx, w, a ->
                val items = (a[0] as CanonValue.Items).items
                if (items.isEmpty()) P2.trap("poll に pollable が 1 つもない")
                if (items.size > WasiP1Poll.MAX_SUBSCRIPTIONS) P2.trap("poll の pollable が多すぎる（${items.size}）")
                await(ctx, w, items.map(::rep))?.let { ready -> CanonValue.Items(ready.map { CanonValue.I32(it) }) }
            },
        )
        val streams = mapOf(
            "[method]input-stream.read" to body { ctx, a -> readResult(ctx, read(ctx, null, a[0], P2.u64(a[1])), skip = false) },
            "[method]input-stream.blocking-read" to waiting { ctx, w, a -> readResult(ctx, read(ctx, w, a[0], P2.u64(a[1])), skip = false) },
            "[method]input-stream.skip" to body { ctx, a -> readResult(ctx, read(ctx, null, a[0], P2.u64(a[1])), skip = true) },
            "[method]input-stream.blocking-skip" to waiting { ctx, w, a -> readResult(ctx, read(ctx, w, a[0], P2.u64(a[1])), skip = true) },
            "[method]input-stream.subscribe" to body { ctx, a -> subscribe(ctx, a[0], input = true) },
            "[method]output-stream.check-write" to body { ctx, a ->
                when (val r = checkWrite(ctx, a[0])) {
                    is CheckWrite.Allowed -> CanonValue.ok(CanonValue.I64(r.bytes))
                    CheckWrite.Closed -> closed
                    is CheckWrite.Failed -> failed(ctx, r.errno)
                }
            },
            "[method]output-stream.write" to waiting { ctx, w, a ->
                val data = (a[1] as CanonValue.Bytes).bytes
                // 巻き直して着いたなら、許可量は前に引いてある
                if (!w.resumed) takeAllowance(ctx, a[0], data.size.toLong(), "write")
                writeResult(ctx, write(ctx, w, a[0], data, data.size, w.resumed))
            },
            "[method]output-stream.blocking-write-and-flush" to waiting { ctx, w, a ->
                val data = (a[1] as CanonValue.Bytes).bytes
                if (data.size > P2.MAX_BLOCKING_WRITE) P2.trap("blocking-write-and-flush は ${P2.MAX_BLOCKING_WRITE} バイトまで（${data.size}）")
                writeResult(ctx, write(ctx, w, a[0], data, data.size, w.resumed))
            },
            "[method]output-stream.flush" to body { ctx, a -> writeResult(ctx, flush(ctx, a[0])) },
            "[method]output-stream.blocking-flush" to body { ctx, a -> writeResult(ctx, flush(ctx, a[0])) },
            "[method]output-stream.subscribe" to body { ctx, a -> subscribe(ctx, a[0], input = false) },
            "[method]output-stream.write-zeroes" to waiting { ctx, w, a ->
                val n = P2.u64(a[1])
                if (!w.resumed) takeAllowance(ctx, a[0], n, "write-zeroes")
                writeResult(ctx, write(ctx, w, a[0], ByteArray(n.toInt()), n.toInt(), w.resumed))
            },
            "[method]output-stream.blocking-write-zeroes-and-flush" to waiting { ctx, w, a ->
                val n = P2.u64(a[1])
                if (n > P2.MAX_BLOCKING_WRITE) P2.trap("blocking-write-zeroes-and-flush は ${P2.MAX_BLOCKING_WRITE} バイトまで（$n）")
                writeResult(ctx, write(ctx, w, a[0], ByteArray(n.toInt()), n.toInt(), w.resumed))
            },
            "[method]output-stream.splice" to waiting { ctx, w, a -> splice(ctx, w, a, blocking = false, ::readResult, ::writeResult, ::failed, closed) },
            "[method]output-stream.blocking-splice" to waiting { ctx, w, a -> splice(ctx, w, a, blocking = true, ::readResult, ::writeResult, ::failed, closed) },
        )
        return mapOf(ERROR to error, POLL to poll, STREAMS to streams)
    }

    private fun rep(v: CanonValue): Int = (v as CanonValue.Handle).value

    /**
     * `splice`・`blocking-splice`。出力の `check-write`（[blocking] なら、許可量が 0 の間は待つ）→ 許可量と `len` の小さい方で入力を
     * `read`（[blocking] なら少しでも読めるまで待つ）→ 出力へ `write`。運んだ数を返す（巻き戻したら `null`）。
     * 書く途中で巻き戻したら、読んだバイトを出力の stream（[StreamEntry.pendingSplice]）に持ち、巻き直したら読まずにその続きを書く（#757）
     */
    private inline fun splice(
        ctx: ProcessContext,
        w: Waiter,
        a: List<CanonValue>,
        blocking: Boolean,
        readResult: (ProcessContext, ReadOutcome, Boolean) -> CanonValue?,
        writeResult: (ProcessContext, WriteOutcome, CanonValue?) -> CanonValue?,
        failed: (ProcessContext, Errno) -> CanonValue,
        closed: CanonValue,
    ): CanonValue? {
        val out = output(ctx, a[0])
        input(ctx, a[1])
        val len = P2.u64(a[2])
        // 巻き直して着いた: 前に読んだバイトがあれば、読まずに続きを書く
        val pending = if (w.resumed) {
            ctx.withKernelLock {
                val b = out.pendingSplice
                out.pendingSplice = null
                b
            }
        } else {
            null
        }
        val buffer: ByteArray
        val count: Int
        if (pending != null) {
            buffer = pending
            count = pending.size
        } else {
            var allowed = 0L
            while (true) {
                when (val c = checkWrite(ctx, a[0])) {
                    CheckWrite.Closed -> return closed
                    is CheckWrite.Failed -> return failed(ctx, c.errno)
                    is CheckWrite.Allowed -> {
                        allowed = c.bytes
                        if (allowed > 0 || len == 0L) break
                        val key = c.key
                        if (!blocking || key == null) return CanonValue.ok(CanonValue.I64(0))
                        if (w.await(ctx, listOf(key))) return null
                    }
                }
            }
            val r = read(ctx, if (blocking) w else null, a[1], minOf(allowed, len))
            if (r !is ReadOutcome.Bytes) {
                return when (r) {
                    ReadOutcome.Empty -> CanonValue.ok(CanonValue.I64(0))
                    else -> readResult(ctx, r, true)
                }
            }
            buffer = r.buffer
            count = r.count
        }
        val wr = write(ctx, w, a[0], buffer, count, pending != null)
        if (wr == WriteOutcome.Unwound) {
            ctx.withKernelLock { out.pendingSplice = if (buffer.size == count) buffer else buffer.copyOf(count) }
            return null
        }
        if (wr != WriteOutcome.Done) return writeResult(ctx, wr, null)
        ctx.withKernelLock { out.allowance = maxOf(0L, out.allowance - count) }
        ctx.chargeFuel(count.toLong() / WasiP1Fd.BYTES_PER_FUEL)
        return CanonValue.ok(CanonValue.I64(count.toLong()))
    }

    // ---- 読み ----

    internal sealed interface ReadOutcome {
        class Bytes(val buffer: ByteArray, val count: Int) : ReadOutcome
        data object Empty : ReadOutcome
        data object Closed : ReadOutcome
        class Failed(val errno: Errno) : ReadOutcome

        /** 巻き戻して待つ（#757） */
        data object Unwound : ReadOutcome
    }

    private fun input(ctx: ProcessContext, self: CanonValue): StreamEntry {
        val e = P2.entry<StreamEntry>(ctx, self)
        if (!e.input) P2.trap("output-stream を input-stream として使った")
        return e
    }

    private fun output(ctx: ProcessContext, self: CanonValue): StreamEntry {
        val e = P2.entry<StreamEntry>(ctx, self)
        if (e.input) P2.trap("input-stream を output-stream として使った")
        return e
    }

    /**
     * [self] から [len]（[P2.MAX_READ] で切る）まで読む。[wait] があれば少しでも読めるまで待つ（巻き戻したら [ReadOutcome.Unwound]）。
     * 確保は切った長さの分だけ（ゲストの決める長さで先に確保しない）
     */
    fun read(ctx: ProcessContext, wait: Waiter?, self: CanonValue, len: Long): ReadOutcome {
        val rep = rep(self)
        val e = input(ctx, self)
        val handle = e.handle
        if (e.closed || handle == null) return ReadOutcome.Closed
        val n = minOf(len, P2.MAX_READ.toLong()).toInt()
        if (n == 0) return ReadOutcome.Empty
        val buf = ByteArray(n)
        val file = if (e.shared) null else ctx.withKernelLock { FdIo.openFileOf(ctx.kernel, handle, HandleRights.READ) }
        if (file != null) {
            val got = try {
                ctx.vfs.pread(file, e.offset, buf, 0, n)
            } catch (ex: FsException) {
                return ReadOutcome.Failed(ex.errno)
            }
            if (got == 0) return ReadOutcome.Closed
            ctx.withKernelLock { e.offset += got }
            return ReadOutcome.Bytes(buf, got)
        }
        while (true) {
            val r = FdIo.readHandle(ctx, buf, 0, n) { k -> handle.takeIf { k.processes[ctx.pid]?.hostResources?.get(rep) === e } }
            when (r) {
                is IoResult.Bytes -> return if (r.count == 0) ReadOutcome.Empty else ReadOutcome.Bytes(buf, r.count)
                IoResult.Eof -> return ReadOutcome.Closed
                is IoResult.Error -> return ReadOutcome.Failed(r.errno)
                is IoResult.WouldBlock -> if (wait == null) return ReadOutcome.Empty else if (wait.await(ctx, listOf(r.key))) return ReadOutcome.Unwound
            }
        }
    }

    // ---- 書き ----

    internal sealed interface WriteOutcome {
        data object Done : WriteOutcome
        data object Closed : WriteOutcome
        class Failed(val errno: Errno) : WriteOutcome

        /** 巻き戻して待つ（#757。書き終えた数は [StreamEntry.writeProgress]） */
        data object Unwound : WriteOutcome
    }

    internal sealed interface CheckWrite {
        /** 今すぐ受けられる [bytes]。0 のとき、受けられるようになるのを待つ [key]（ファイルなど待たなくてよいものは `null`） */
        class Allowed(val bytes: Long, val key: WaitKey? = null) : CheckWrite
        data object Closed : CheckWrite
        class Failed(val errno: Errno) : CheckWrite
    }

    /** `check-write`。今すぐ受けられる量を許可量として覚えて返す */
    fun checkWrite(ctx: ProcessContext, self: CanonValue): CheckWrite {
        val e = output(ctx, self)
        return ctx.withKernelLock {
            val handle = e.handle
            if (e.closed || handle == null) return@withKernelLock CheckWrite.Closed
            if (FdIo.openFileOf(ctx.kernel, handle, HandleRights.WRITE) != null) {
                e.allowance = P2.FILE_WRITE_ALLOWANCE
                return@withKernelLock CheckWrite.Allowed(e.allowance)
            }
            when (val r = FdIo.pollEntryLocked(ctx.kernel, ctx.vfs, handle, WaitCondition.WRITABLE)) {
                is FdReadiness.Ready -> if (r.hangup) {
                    e.closed = true
                    CheckWrite.Closed
                } else {
                    e.allowance = minOf(r.nbytes, P2.FILE_WRITE_ALLOWANCE)
                    CheckWrite.Allowed(e.allowance)
                }
                is FdReadiness.Pending -> {
                    e.allowance = 0
                    CheckWrite.Allowed(0, r.key)
                }
                is FdReadiness.Error -> if (r.errno == Errno.PIPE) CheckWrite.Closed else CheckWrite.Failed(r.errno)
            }
        }
    }

    /** `write`・`write-zeroes` の許可量を引く。超えたらトラップ（仕様） */
    private fun takeAllowance(ctx: ProcessContext, self: CanonValue, n: Long, what: String) {
        val e = output(ctx, self)
        ctx.withKernelLock {
            if (n > e.allowance) P2.trap("$what が check-write の許可量（${e.allowance}）を超えた（$n）")
            e.allowance -= n
        }
    }

    /**
     * [data] の先頭 [len] バイトを全部書く（パイプが一部しか受けなければ待って続ける）。
     * 巻き戻して待つときは、書き終えた数を [StreamEntry.writeProgress] に入れて [WriteOutcome.Unwound]（#757）。
     * [resumed] なら、巻き直して着いた呼び出しなので、[StreamEntry.writeProgress] から書き続ける（燃料はもう引いてある）
     */
    fun write(ctx: ProcessContext, wait: Waiter, self: CanonValue, data: ByteArray, len: Int, resumed: Boolean): WriteOutcome {
        val rep = rep(self)
        val e = output(ctx, self)
        var done = ctx.withKernelLock {
            val d = if (resumed) e.writeProgress else 0
            e.writeProgress = 0
            d
        }
        val handle = e.handle
        if (e.closed || handle == null) return WriteOutcome.Closed
        if (len == 0) return WriteOutcome.Done
        if (!resumed) ctx.chargeFuel(len.toLong() / WasiP1Fd.BYTES_PER_FUEL)
        val file = if (e.shared) null else ctx.withKernelLock { FdIo.openFileOf(ctx.kernel, handle, HandleRights.WRITE) }
        if (file != null) return writeFile(ctx, e, file, data, len)
        check(done in 0..len) { "書き終えた数 $done が長さ $len を超えている" }
        while (done < len) {
            val r = FdIo.writeHandle(ctx, data, done, len - done) { k -> handle.takeIf { k.processes[ctx.pid]?.hostResources?.get(rep) === e } }
            when (r) {
                is IoResult.Bytes -> done += r.count
                is IoResult.WouldBlock -> if (wait.canUnwind()) {
                    ctx.withKernelLock { e.writeProgress = done }
                    wait.unwind(listOf(r.key), null)
                    return WriteOutcome.Unwound
                } else {
                    ctx.block(listOf(r.key))
                }
                IoResult.Eof -> return WriteOutcome.Closed
                is IoResult.Error -> {
                    if (r.errno != Errno.PIPE) return WriteOutcome.Failed(r.errno)
                    ctx.withKernelLock { e.closed = true }
                    return WriteOutcome.Closed
                }
            }
        }
        return WriteOutcome.Done
    }

    /** ファイルへ、stream の位置（追記なら終わり）から書く（VFS の I/O はカーネルロックの外。ADR 0052） */
    private fun writeFile(ctx: ProcessContext, e: StreamEntry, file: OpenFile, data: ByteArray, len: Int): WriteOutcome {
        var done = 0
        try {
            while (done < len) {
                if (e.append) {
                    val w = ctx.vfs.append(file, data, done, len - done)
                    if (w.count <= 0) return WriteOutcome.Failed(Errno.IO)
                    done += w.count
                    ctx.withKernelLock { e.offset = w.position + w.count }
                } else {
                    val n = ctx.vfs.pwrite(file, e.offset, data, done, len - done)
                    if (n <= 0) return WriteOutcome.Failed(Errno.IO)
                    done += n
                    ctx.withKernelLock { e.offset += n }
                }
            }
        } catch (ex: FsException) {
            return WriteOutcome.Failed(ex.errno)
        }
        return WriteOutcome.Done
    }

    private fun flush(ctx: ProcessContext, self: CanonValue): WriteOutcome {
        val e = output(ctx, self)
        return if (e.closed || e.handle == null) WriteOutcome.Closed else WriteOutcome.Done
    }

    // ---- pollable ----

    private fun subscribe(ctx: ProcessContext, self: CanonValue, input: Boolean): CanonValue {
        val parent = rep(self)
        val e = if (input) input(ctx, self) else output(ctx, self)
        return ctx.withKernelLock {
            val handle = e.handle
            val always = handle == null || ctx.kernel.objects[handle.objectId] is OpenFile
            val p = if (always) PollableEntry.Ready(parent) else PollableEntry.Object(parent, if (input) WaitCondition.READABLE else WaitCondition.WRITABLE)
            P2.childLocked(ctx, parent, p)
        }
    }

    /** 期限 [deadline]（単調時計のナノ秒）の pollable を作る */
    fun deadline(ctx: ProcessContext, deadline: Long): CanonValue = P2.own(ctx, PollableEntry.Deadline(deadline))

    /** いつもそろった pollable（親 [parent] の子）を作る */
    fun alwaysReady(ctx: ProcessContext, parent: Int): CanonValue = ctx.withKernelLock { P2.childLocked(ctx, parent, PollableEntry.Ready(parent)) }

    /**
     * `kibi:*` の resource（親 [parent]。[KernelEntry]）の pollable を作る（`events-poll`・`timers-poll`・`bus-poll`・`process-poll` の
     * `subscribe`。#685）。そろう条件は p1 の `FD_READ` と同じ（`FdIo.pollEntryLocked` の `READABLE`。`ProcessObject` は終わったら）
     */
    fun subscribeKernel(ctx: ProcessContext, parent: Int): CanonValue = ctx.withKernelLock {
        P2.childLocked(ctx, parent, PollableEntry.Object(parent, WaitCondition.READABLE))
    }

    /** [timed] は、まだ期限の来ていない時計の pollable の添字 */
    private class Evaluation(val ready: List<Int>, val keys: List<WaitKey>, val deadline: Long?, val timed: List<Int>)

    /** 全部の pollable を 1 回判定する（待たない） */
    private fun evaluate(ctx: ProcessContext, reps: List<Int>): Evaluation {
        ctx.chargeFuel(POLLABLE_FUEL * reps.size)
        val now = ctx.monotonicNanos()
        return ctx.withKernelLock {
            val table = P2.tableLocked(ctx)
            val ready = ArrayList<Int>()
            val keys = ArrayList<WaitKey>()
            val timed = ArrayList<Int>()
            var deadline: Long? = null
            for ((i, rep) in reps.withIndex()) {
                val p = table[rep] as? PollableEntry ?: P2.trap("ホストの resource $rep は pollable でない")
                when (p) {
                    is PollableEntry.Ready -> ready += i
                    is PollableEntry.Deadline -> if (now >= p.nanos) {
                        ready += i
                    } else {
                        timed += i
                        deadline = minOf(deadline ?: Long.MAX_VALUE, p.nanos)
                    }
                    is PollableEntry.Object -> {
                        val handle = when (val parent = table[p.parent]) {
                            is StreamEntry -> if (parent.closed) null else parent.handle
                            is KernelEntry -> parent.handle
                            else -> P2.trap("pollable の親 ${p.parent} がない")
                        }
                        // 閉じた stream はそろったものとする（読み書きは closed を返す）
                        if (handle == null) {
                            ready += i
                            continue
                        }
                        when (val r = FdIo.pollEntryLocked(ctx.kernel, ctx.vfs, handle, p.condition)) {
                            is FdReadiness.Ready, is FdReadiness.Error -> ready += i
                            is FdReadiness.Pending -> if (r.key !in keys) keys += r.key
                        }
                    }
                }
            }
            Evaluation(ready, keys, deadline, timed)
        }
    }

    private fun readyOnce(ctx: ProcessContext, reps: List<Int>): Boolean = evaluate(ctx, reps).ready.isNotEmpty()

    /**
     * どれかがそろうまで待ち、そろった添字を返す。一時的なタイマーは待つときにだけ開き、返るとき（例外のときも）に閉じる。
     *
     * マシンの動いているタイマーが上限（`TimerLimits.MAX_ARMED_TIMERS`）でタイマーを動かせないときは、トラップにせず、期限の来ていない
     * 時計の pollable を早めにそろったものとして返す（ADR 0267）。p1 の `poll_oneoff` が同じ状況を一時的な不足（`AGAIN`）として返すのに
     * 当たる。p2 の `poll`・`block` にはエラーを返す道がないので、ほかのプロセスがタイマーを使い切っただけでプロセスを落とさないように、
     * 早く起きたものとして扱う（ゲストは時計を読んで待ち直せる）
     */
    fun await(ctx: ProcessContext, wait: Waiter, reps: List<Int>): List<Int>? {
        val inherited = wait.arrival?.wait?.timer
        if (wait.canUnwind()) return awaitUnwinding(ctx, wait, reps, inherited)
        // 巻き戻せないのに引き継いだタイマーがある（来ないはずだが、漏らさない）
        inherited?.let { ctx.closeWaitTimer(it) }
        var timer: WaitKey? = null
        var timerDeadline = 0L
        var timerStale = false
        try {
            while (true) {
                val ev = evaluate(ctx, reps)
                if (ev.ready.isNotEmpty()) return ev.ready
                val keys = ArrayList<WaitKey>(ev.keys.size + 1)
                keys += ev.keys
                val deadline = ev.deadline
                if (deadline != null) {
                    val t = timer
                    try {
                        if (t == null) {
                            timer = ctx.openWaitTimer(deadline)
                        } else if (timerStale || deadline != timerDeadline) {
                            ctx.resetWaitTimer(t, deadline)
                        }
                    } catch (_: ResourceLimitException) {
                        return ev.timed
                    }
                    timerDeadline = deadline
                    timerStale = false
                    keys += timer!!
                }
                check(keys.isNotEmpty()) { "待つものがない" }
                val woke = ctx.block(keys)
                if (timer != null && timer in woke) timerStale = true
            }
        } finally {
            timer?.let { ctx.closeWaitTimer(it) }
        }
    }

    /**
     * 巻き戻して待つ形の [await]（#757。p1 の `poll_oneoff` と同じ）。そろっていれば添字を返す（[timer] は閉じる）。
     * まだなら巻き戻して `null`（時計の pollable があれば、[timer] を引き継ぐか開いて期限を合わせ、閉じずに [AsyncWait] に入れる）。
     * [timer] は前に巻き戻したときのタイマー（なければ `null`）。タイマーを動かせないときは、[await] と同じく早めに返す
     */
    private fun awaitUnwinding(ctx: ProcessContext, wait: Waiter, reps: List<Int>, timer: WaitKey?): List<Int>? {
        var open = timer
        try {
            val ev = evaluate(ctx, reps)
            if (ev.ready.isNotEmpty()) return ev.ready
            val keys = ArrayList<WaitKey>(ev.keys.size + 1)
            keys += ev.keys
            val deadline = ev.deadline
            if (deadline != null) {
                try {
                    val t = open
                    // 引き継いだタイマーは、鳴った後かもしれないので期限を合わせ直す
                    if (t == null) open = ctx.openWaitTimer(deadline) else ctx.resetWaitTimer(t, deadline)
                } catch (_: ResourceLimitException) {
                    return ev.timed
                }
                keys += open!!
            } else {
                open?.let { ctx.closeWaitTimer(it) }
                open = null
            }
            check(keys.isNotEmpty()) { "待つものがない" }
            // p2 の期限は絶対時刻（PollableEntry.Deadline）なので、相対の期限の基準時刻は使わない
            wait.unwind(keys, AsyncWait(open, 0L, null))
            open = null
            return null
        } finally {
            open?.let { ctx.closeWaitTimer(it) }
        }
    }
}
