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
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.fsError

/**
 * 独自拡張 `kibi:process/process`（#74。ADR 0074）の意味。ABI の層（p1 の import、後の Component Model）に依らない部分。
 *
 * - `[static]process.spawn(path, options)`: [request] で確かめて [SpawnOptions] にし、カーネルの `execWithHandle`
 *   （待機点型は `beginExec`・`finishExecWithHandle`）で子を作る。待ち方は ABI の層が決める
 * - `[method]process.pid`・`status`・`kill`: [pid]・[status]・[kill]
 * - `kill-pid`・`self-pid`・`parent-pid`・`set-signal-ignored`: [killPid]・`ctx.pid`・`ctx.parentPid`・[setSignalIgnored]
 * - `get-cwd`・`set-cwd`: `ctx.cwd`・[setCwd]（カーネルの cwd。ADR 0332）
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

    /** WIT の `signal` の判別子の順（`interrupt`・`quit`・`terminate`・`kill`）。[KillSignal] の並びと同じ */
    val SIGNALS: List<KillSignal> = listOf(KillSignal.INTERRUPT, KillSignal.QUIT, KillSignal.TERMINATE, KillSignal.KILL)

    /** `fd-assign` 1つ。[source] は親の fd（`parent(n)`。符号なしで広げた値）、`null` なら `closed` */
    data class FdAssign(val fd: Long, val source: Long?)

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
