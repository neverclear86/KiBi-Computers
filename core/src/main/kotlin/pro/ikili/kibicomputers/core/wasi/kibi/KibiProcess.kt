package pro.ikili.kibicomputers.core.wasi.kibi

import pro.ikili.kibicomputers.core.kernel.ProcessKilledException
import pro.ikili.kibicomputers.core.kernel.ResourceLimitException
import pro.ikili.kibicomputers.core.kernel.fd.STDERR
import pro.ikili.kibicomputers.core.kernel.fd.STDIN
import pro.ikili.kibicomputers.core.kernel.fd.STDOUT
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.process.ExitReason
import pro.ikili.kibicomputers.core.kernel.process.ExitStatus
import pro.ikili.kibicomputers.core.kernel.process.KillResult
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ProcessArgs
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessListing
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.fsError
import pro.ikili.kibicomputers.core.wasi.WasiUtf8

/**
 * 独自拡張 `kibi:process/process`（#74。ADR 0074）の意味。ABI の層（p1 の import、後の Component Model）に依らない部分。
 *
 * - `[static]process.spawn(path, options)`: [request] で確かめて [SpawnOptions] にし、カーネルの `execWithHandle`
 *   （待機点型は `beginExec`・`finishExecWithHandle`）で子を作る。待ち方は ABI の層が決める
 * - `[method]process.pid`・`status`・`kill`: [pid]・[status]・[kill]
 * - `kill-pid`・`self-pid`・`parent-pid`・`set-signal-ignored`: [killPid]・`ctx.pid`・`ctx.parentPid`・[setSignalIgnored]
 * - `get-cwd`・`set-cwd`: `ctx.cwd`・[setCwd]（カーネルの cwd。ADR 0332）
 * - `list-processes`: [list]（#452。ADR 0452）
 *
 * 失敗は [FsException]。マシン全体の上限（`ResourceLimitException`）は `NFILE`、`KillResult` は [killError] で写す。
 */
internal object KibiProcess {
    /** `args` の要素の数の上限（仮の値。確定は #251）。超えたら `TOOBIG` */
    const val MAX_ARGS: Int = 4096

    /** `env` の組の数の上限（仮の値。確定は #251）。超えたら `TOOBIG` */
    const val MAX_ENV: Int = 4096

    /** `fds` の要素の数の上限（仮の値。確定は #251。fd の数の上限 `DEFAULT_HANDLE_LIMIT` と同じ）。超えたら `INVAL` */
    const val MAX_FDS: Int = 256

    /** `list-processes` の `args` に入れる要素の数の上限（仮の値。確定は #251。ADR 0452） */
    const val MAX_LIST_ARGS: Int = 16

    /** `list-processes` の `args` の UTF-8 のバイト数の合計の上限（仮の値。確定は #251。ADR 0452） */
    const val MAX_LIST_ARG_BYTES: Int = 255

    /** `list-processes` で、呼び出しごとの燃料に加えて1件ごとに引く燃料（仮の値。確定は #251） */
    const val PROCESS_LIST_FUEL_PER_ENTRY: Long = 10

    /** WIT の `signal` の判別子の順（`interrupt`・`quit`・`terminate`・`kill`）。[KillSignal] の並びと同じ */
    val SIGNALS: List<KillSignal> = listOf(KillSignal.INTERRUPT, KillSignal.QUIT, KillSignal.TERMINATE, KillSignal.KILL)

    /** `fd-assign` 1つ。[source] は親の fd（`parent(n)`。符号なしで広げた値）、`null` なら `closed` */
    data class FdAssign(val fd: Long, val source: Long?)

    /** WIT の `process-state` の場合。[ordinal] が判別子（`starting` 0・`running` 1・`waiting` 2・`finished` 3） */
    enum class ListState { STARTING, RUNNING, WAITING, FINISHED }

    /**
     * `process-info` 1つ（ABI に依らない形）。[exit] は [state] が [ListState.FINISHED] のときだけ `null` でない。
     * [args] は上限で切った後の UTF-8 のバイト列（[truncateArgs]。対になっていないサロゲートは U+FFFD にしてある）。
     * ABI の層はこれをそのまま書く（UTF-8 化をもう一度しない。数えたバイト数と書くバイト列を同じものにするため）。
     * `ByteArray` を持つので、[equals]・[hashCode] は中身で比べる
     */
    class Entry(val pid: Int, val parentPid: Int, val state: ListState, val exit: ExitStatus?, val args: List<ByteArray>) {
        override fun equals(other: Any?): Boolean = this === other ||
            other is Entry && pid == other.pid && parentPid == other.parentPid && state == other.state && exit == other.exit &&
            args.size == other.args.size && args.indices.all { args[it].contentEquals(other.args[it]) }

        override fun hashCode(): Int {
            var h = pid
            h = 31 * h + parentPid
            h = 31 * h + state.hashCode()
            h = 31 * h + (exit?.hashCode() ?: 0)
            for (a in args) h = 31 * h + a.contentHashCode()
            return h
        }

        override fun toString(): String =
            "Entry(pid=$pid, parentPid=$parentPid, state=$state, exit=$exit, args=${args.map { String(it, Charsets.UTF_8) }})"
    }

    /** `spawn` の引数（ABI の層が読んだもの）。[env]・[cwd] の `null` は none */
    data class Request(
        val path: String,
        val args: List<String>,
        val env: List<Pair<String, String>>?,
        val cwd: String?,
        val fds: List<FdAssign>,
    )

    /**
     * [request] を確かめて、カーネルの [SpawnOptions] にする（`args` はそのまま。空なら `exec` が path の文字列1つにする）。
     *
     * - `env` は並びを保つ。同じ名前が重なれば後のものを使う。名前が空・`=` や NUL を含むなら `INVAL`、合わせて大きすぎれば `TOOBIG`
     *   （[ProcessArgs.check]。読み込みの前に確かめる）
     * - `fds`: fd 0/1/2 は書かなければ親から引き継ぐ。fd 3 以降は書いたものだけ。同じ fd を2回書いたら `INVAL`。
     *   fd・親の fd が 2^31 以上なら `BADF`。fd の範囲（3..上限）と親の fd が開いているかはカーネルが確かめる（`BADF`）
     */
    @Throws(FsException::class)
    fun options(request: Request): SpawnOptions {
        val env = request.env?.let { list -> LinkedHashMap<String, String>().also { m -> for ((k, v) in list) m[k] = v } }
        ProcessArgs.check(request.args, env ?: emptyMap())
        val stdio = arrayOf<StdioSource>(StdioSource.Inherit, StdioSource.Inherit, StdioSource.Inherit)
        val fds = LinkedHashMap<Int, StdioSource>()
        val seen = HashSet<Long>()
        for (a in request.fds) {
            if (!seen.add(a.fd)) fsError(Errno.INVAL, "fd ${a.fd} を2回書いた")
            val fd = fdOf(a.fd)
            val source = if (a.source == null) StdioSource.Closed else StdioSource.ParentFd(fdOf(a.source))
            if (fd <= STDERR) stdio[fd] = source else fds[fd] = source
        }
        return SpawnOptions(
            stdio = StdioSpec(stdio[STDIN], stdio[STDOUT], stdio[STDERR]),
            args = request.args,
            env = env,
            cwd = request.cwd,
            fds = fds,
        )
    }

    /** 子のハンドル [handle] のプロセスの pid。子のハンドルでなければ `BADF` */
    @Throws(FsException::class)
    fun pid(ctx: ProcessContext, handle: Int): Int = ctx.processHandlePid(handle)

    /** 子のハンドル [handle] の終わり方（待たない。終わっていなければ `null`）。親が受け取ったら刈り取る。子のハンドルでなければ `BADF` */
    @Throws(FsException::class)
    fun status(ctx: ProcessContext, handle: Int): ExitStatus? = ctx.processHandleStatus(handle)

    /** 子のハンドル [handle] のプロセスに [signal] を送る。刈り取られた後なら何もしない（成功）。子のハンドルでなければ `BADF` */
    @Throws(FsException::class, ProcessKilledException::class)
    fun kill(ctx: ProcessContext, handle: Int, signal: KillSignal) {
        killError(ctx.killProcessHandle(handle, signal))?.let { fsError(it, "シグナルを送れない") }
    }

    /** [pid]（符号なしで広げた値）に [signal] を送る。表にない（2^31 以上・0 を含む）なら `SRCH`、init なら `PERM` */
    @Throws(FsException::class, ProcessKilledException::class)
    fun killPid(ctx: ProcessContext, pid: Long, signal: KillSignal) {
        if (pid !in 1L..Int.MAX_VALUE.toLong()) fsError(Errno.SRCH, "pid $pid のプロセスはない")
        killError(ctx.kill(pid.toInt(), signal))?.let { fsError(it, "pid $pid にシグナルを送れない") }
    }

    /** [KillResult] の失敗の errno（成功なら `null`）。送った・無視された・もう終わっていたは成功 */
    fun killError(result: KillResult): Errno? = when (result) {
        KillResult.DELIVERED, KillResult.IGNORED, KillResult.ALREADY_EXITED -> null
        KillResult.NOT_FOUND -> Errno.SRCH
        KillResult.NOT_PERMITTED -> Errno.PERM
    }

    /** 自分が [signal] を無視するか決める。`kill` は無視できないので `INVAL` */
    @Throws(FsException::class)
    fun setSignalIgnored(ctx: ProcessContext, signal: KillSignal, ignored: Boolean) {
        if (!signal.ignorable) fsError(Errno.INVAL, "$signal は無視できない")
        ctx.setSignalIgnored(signal, ignored)
    }

    /** カーネルの cwd を [path] に変える。なければ `NOENT`、ディレクトリでなければ `NOTDIR` */
    @Throws(FsException::class)
    fun setCwd(ctx: ProcessContext, path: String) {
        ctx.changeDirectory(path)
    }

    /** WIT の `signal` の判別子 [disc] の [KillSignal]。範囲外なら `INVAL` */
    @Throws(FsException::class)
    fun signalOf(disc: Int): KillSignal = SIGNALS.getOrNull(disc) ?: fsError(Errno.INVAL, "signal の判別子が不正（$disc）")

    /**
     * マシンの全プロセスの一覧（`list-processes`。待たない）。カーネルロックの中で表を写し（`ctx.listProcesses`）、ロックの外で
     * pid の昇順に並べ替えて `args` を切り詰め、件数 × [PROCESS_LIST_FUEL_PER_ENTRY] の燃料を引く。
     * pid はどれも正の `Int` なので、`Int` の比較が u32 の昇順と一致する（表は割り当ての順なので、pid が 1 に戻ると昇順でない）
     */
    fun list(ctx: ProcessContext): List<Entry> {
        val entries = entries(ctx.listProcesses())
        ctx.chargeFuel(entries.size * PROCESS_LIST_FUEL_PER_ENTRY)
        return entries
    }

    /** カーネルの写し [listed] を pid の昇順に並べ、`process-info` にする */
    fun entries(listed: List<ProcessListing>): List<Entry> = listed.sortedBy { it.pid }.map { entryOf(it) }

    /** カーネルの写し [p] を `process-info` にする */
    fun entryOf(p: ProcessListing): Entry {
        val state = stateOf(p.state)
        val exit = if (state == ListState.FINISHED) checkNotNull(p.exit) { "終わったプロセス ${p.pid} に終わり方がない" } else null
        return Entry(p.pid, p.parentPid, state, exit, truncateArgs(p.args))
    }

    /** カーネルの状態を `process-state` の場合に写す（ADR 0452。状態が増えたらコンパイルで気づけるよう全列挙で書く） */
    fun stateOf(state: ProcessState): ListState = when (state) {
        ProcessState.CREATED, ProcessState.LOADING -> ListState.STARTING
        ProcessState.RUNNABLE, ProcessState.RUNNING, ProcessState.WAITING_FUEL -> ListState.RUNNING
        ProcessState.WAITING_EVENT, ProcessState.WAITING_HOST -> ListState.WAITING
        ProcessState.EXITED, ProcessState.CRASHED, ProcessState.KILLED -> ListState.FINISHED
    }

    /**
     * `args` を上限で切る（ADR 0452）。数え方は UTF-8 のバイト数の和（NUL・区切りは数えない。対になっていないサロゲートは U+FFFD の 3 バイト）。
     * 先頭から順に、[MAX_LIST_ARGS] 個入れたら止める。残りの予算に収まる要素は丸ごと入れる（空文字列は残りが 0 でも入る）。
     * 収まらない要素は、残りの予算以下に UTF-8 の文字の境目で切って入れ、そこで止める（切って空になるなら入れずに止める）。
     * 結果は UTF-8 のバイト列（[WasiUtf8.encode]）で、ABI の層はそのまま書く
     */
    fun truncateArgs(args: List<String>): List<ByteArray> {
        val out = ArrayList<ByteArray>(minOf(args.size, MAX_LIST_ARGS))
        var remaining = MAX_LIST_ARG_BYTES
        for (arg in args) {
            if (out.size == MAX_LIST_ARGS) break
            // 長い要素は先頭の remaining + 1 文字だけを UTF-8 にする（どの文字も 1 バイト以上なので、それで予算を超えると分かる。
            // 途中で割ったサロゲートは最後の文字なので、そのバイトは remaining 以降に来て、切る位置より前に影響しない）
            val head = if (arg.length > remaining + 1) arg.substring(0, remaining + 1) else arg
            val bytes = WasiUtf8.encode(head)
            if (bytes.size <= remaining) {
                out += bytes
                remaining -= bytes.size
                continue
            }
            // 継続バイト（10xxxxxx）を戻って、文字の先頭バイトの手前で切る
            var end = remaining
            while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
            if (end > 0) out += bytes.copyOf(end)
            break
        }
        return out
    }

    /** WIT の `exit-status` の判別子（`exited` 0・`killed` 1・`crashed` 2） */
    fun statusTag(status: ExitStatus): Int = when (status.reason) {
        ExitReason.EXITED -> 0
        ExitReason.KILLED -> 1
        ExitReason.CRASHED -> 2
    }

    /** [block] の `ResourceLimitException`（マシン全体の上限）を `NFILE` にする */
    @Throws(FsException::class)
    inline fun <T> limits(block: () -> T): T = try {
        block()
    } catch (e: ResourceLimitException) {
        fsError(Errno.NFILE, e.message)
    }

    /** `u32` を符号なしで広げた [fd] を fd 番号にする。`Int` に入らなければ `BADF` */
    @Throws(FsException::class)
    private fun fdOf(fd: Long): Int {
        if (fd !in 0L..Int.MAX_VALUE.toLong()) fsError(Errno.BADF, "fd $fd は範囲外")
        return fd.toInt()
    }
}
