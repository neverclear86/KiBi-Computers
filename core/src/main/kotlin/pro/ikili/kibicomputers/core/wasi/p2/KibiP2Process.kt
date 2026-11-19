package pro.ikili.kibicomputers.core.wasi.p2

import pro.ikili.kibicomputers.core.kernel.fd.STDERR
import pro.ikili.kibicomputers.core.kernel.fd.STDIN
import pro.ikili.kibicomputers.core.kernel.fd.STDOUT
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.ComponentSuspend
import pro.ikili.kibicomputers.core.kernel.process.ExecWait
import pro.ikili.kibicomputers.core.kernel.process.ExitStatus
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessArgs
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessObject
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.OpenFile
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.vfs.fsError
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.HostFunctionBody
import pro.ikili.kibicomputers.core.wasi.kibi.KibiProcess
import pro.ikili.kibicomputers.core.wasi.p2.KibiP2.body

/**
 * `kibi:process/process`・`kibi:process/process-poll` の p2 の実装（#685。ADR 0685）。意味は p1 と同じ（[KibiProcess]）。
 *
 * - `spawn` の引数は、Canonical ABI が全引数を lift してから（align・範囲・UTF-8・判別子の誤りはそこでトラップ）この本体を呼ぶ（#616。ADR 0616）。
 *   本体の検査は、`request()` が path → args・env（個数と合計バイト数）→ cwd → fds（個数と `parent(n)`）の順に確かめ、その後に `KibiProcess.options`
 *   （fd の範囲・fd の重複・args と env の NUL・env の名前の誤り。args・env の一部の検査はここなので fds より後）、最後に `handles` の個数。上限とコードは p1 と同じ。p2 のゲストが番号で知っている fd は 0/1/2 だけなので
 *   `fds` の `parent(n)` は n が 0..2 のときだけ（それ以外は `bad-descriptor`。process.wit の `fd-source.parent` のとおり）
 * - `spawn`・`spawn-with-handles` は待ちうる関数（[WasiP2Io.waiting]。#800）。待機点型では p1 の UNWIND と同じく巻き戻して読み込みを待ち、
 *   ブロッキング型（と作っている途中）は `ctx.execWithHandle` で待つ（読み込みの間は CPU と許可を返す）
 * - `spawn-with-handles`: `options.fds` を当ててから `handles` を当てる。同じ fd を2回（`handles` の中で、または `fds` と `handles` で）
 *   書いたら `invalid`。渡せるのはパイプの端・`Tty`・共有の位置の stream（`get-stdin` など）・`descriptor`（権利は flags の read・write から。どちらもなければ開いたときの権利）・`process`。
 *   自分の位置を持つファイルの stream は `not-supported`、閉じた stream は `bad-descriptor`
 */
internal object KibiP2Process {
    const val PROCESS: String = "kibi:process/process"
    const val PROCESS_POLL: String = "kibi:process/process-poll"

    const val LABEL: String = "process"

    // descriptor-flags のビット（WasiP2Fs と同じ）
    private const val FLAG_READ = 1
    private const val FLAG_WRITE = 2

    fun functions(t: WasiP2Types): Map<String, Map<String, HostFunctionBody>> {
        val process = mapOf(
            "[static]process.spawn" to WasiP2Io.waiting { ctx, w, a -> spawnResult { spawn(ctx, w, a[0], a[1], emptyList()) } },
            "[method]process.pid" to body { ctx, a -> CanonValue.I32(ctx.processHandlePid(KibiP2.handle(ctx, a[0], LABEL))) },
            "[method]process.status" to body { ctx, a ->
                KibiP2.result {
                    ctx.processHandleStatus(KibiP2.handle(ctx, a[0], LABEL))?.let { CanonValue.some(exitStatus(it)) } ?: CanonValue.NONE
                }
            },
            "[method]process.kill" to body { ctx, a ->
                KibiP2.result {
                    val h = KibiP2.handle(ctx, a[0], LABEL)
                    val sig = KibiProcess.signalOf((a[1] as CanonValue.Case).index)
                    KibiProcess.killError(ctx.killProcessHandle(h, sig))?.let { fsError(it, "シグナルを送れない") }
                    null
                }
            },
            "kill-pid" to body { ctx, a ->
                KibiP2.result {
                    KibiProcess.killPid(ctx, KibiP2.u32(a[0]), KibiProcess.signalOf((a[1] as CanonValue.Case).index))
                    null
                }
            },
            "list-processes" to body { ctx, _ -> CanonValue.Items(KibiProcess.list(ctx).map(::processInfo)) },
            "self-pid" to body { ctx, _ -> CanonValue.I32(ctx.pid) },
            "parent-pid" to body { ctx, _ -> CanonValue.I32(ctx.parentPid) },
            "set-signal-ignored" to body { ctx, a ->
                KibiP2.result {
                    KibiProcess.setSignalIgnored(ctx, KibiProcess.signalOf((a[0] as CanonValue.Case).index), (a[1] as CanonValue.Bool).value)
                    null
                }
            },
            "get-cwd" to body { ctx, _ -> CanonValue.str(ctx.cwd.toString()) },
            "set-cwd" to body { ctx, a ->
                KibiP2.result {
                    val path = P2.str(a[0])
                    KibiP2.checkBytes(path, VfsPath.MAX_PATH_BYTES, Errno.NAMETOOLONG, "path")
                    KibiProcess.setCwd(ctx, path)
                    null
                }
            },
        )
        val poll = mapOf(
            "subscribe" to body { ctx, a -> KibiP2.subscribe(ctx, a[0], LABEL) },
            "spawn-with-handles" to WasiP2Io.waiting { ctx, w, a -> spawnResult { spawn(ctx, w, a[0], a[1], (a[2] as CanonValue.Items).items) } },
        )
        return mapOf(PROCESS to process, PROCESS_POLL to poll)
    }

    /** [spawn] の結果を `result<process, error-code>` にする。巻き戻したら（[spawn] が `null`）`null`（戻り値は捨てられる） */
    private inline fun spawnResult(block: () -> CanonValue.Handle?): CanonValue? {
        var unwound = false
        val r = KibiP2.result { block().also { if (it == null) unwound = true } }
        return if (unwound) null else r
    }

    /**
     * `spawn`・`spawn-with-handles`。子のハンドルを表に置いて own の値を返す。
     *
     * 巻き戻せるとき（#800。p1 の UNWIND と同じ形）: `ctx.beginExec` の [ExecWait] を [ComponentSuspend.pendingSpawn] に持って巻き戻し、
     * 巻き直して着いたら、引数を確かめ直さず（`handles` の borrow も解決し直さず）にそれで子を作る（`ctx.finishExecWithHandle`）。子のハンドルは
     * 待ち終わってから置く（[pro.ikili.kibicomputers.core.runtime.component.link.HostWait.UNWIND] の約束）。読み込みが始めから終わっていれば
     * 巻き戻さずに子を作る。巻き直して着いたのに手元にない（起きないはず）なら、`beginExec` からやり直す（子はまだ作っていない）。
     * 待っている間ゲストは止まっているので、`handles` で借りたハンドルは閉じられない（解決した [StdioSource.Object] はそのまま使える）。
     * 巻き戻せないとき（ブロッキング型・作っている途中）は `ctx.execWithHandle`（読み込みの間は CPU と許可を返す）。
     * 巻き戻したら `null`
     */
    @Throws(FsException::class)
    private fun spawn(
        ctx: ProcessContext,
        w: WasiP2Io.Waiter,
        pathValue: CanonValue,
        optionsValue: CanonValue,
        handles: List<CanonValue>,
    ): CanonValue.Handle? {
        val place = KibiP2.placement(ctx, LABEL)
        if (!w.resumed && !w.canUnwind()) {
            val options = spawnOptions(ctx, pathValue, optionsValue, handles)
            val h = KibiProcess.limits { ctx.execWithHandle(options.first, KernelDrivers.DEFAULT, options.second, place) }
            return CanonValue.Handle(h.fd)
        }
        val s = w.suspend
        var pending = s.pendingSpawn
        if (pending != null && !w.resumed) {
            // 新しい呼び出しなのに前の読み込みが残っている（起きないはず）。閉じてから最初から行う
            s.pendingSpawn = null
            ctx.closeHostWait(pending.wait)
            pending = null
        }
        if (pending == null) {
            val (path, options) = spawnOptions(ctx, pathValue, optionsValue, handles)
            // 置き場の空きは読み込みの前に確かめる（子を作る前にカーネルがもう一度確かめる）
            ctx.checkHandleRoom(place)
            pending = KibiProcess.limits { ctx.beginExec(path, KernelDrivers.DEFAULT, options) }
            s.pendingSpawn = pending
        }
        if (!pending.wait.future.isDone) {
            w.unwind(listOf(pending.key), null)
            return null
        }
        s.pendingSpawn = null
        val h = KibiProcess.limits { ctx.finishExecWithHandle(pending, place) }
        return CanonValue.Handle(h.fd)
    }

    /**
     * `spawn` の引数を確かめ、`handles` を当てて、実行ファイルのパスと [SpawnOptions] にする。
     *
     * 引数はすべて lift 済み（Component Model の順。#616）なので、ここで確かめるのはホストの検査だけ。順は [request]（`path` → `args`・`env` の個数と合計 →
     * `cwd` → `fds` の個数と `parent(n)`）→ [KibiProcess.options]（fd の範囲・重複、NUL、env の名前）→ `handles` の個数（`spawn-with-handles` だけ）。
     * `handles` の個数は `KibiProcess.options` の後に確かめる
     */
    @Throws(FsException::class)
    private fun spawnOptions(ctx: ProcessContext, pathValue: CanonValue, optionsValue: CanonValue, handles: List<CanonValue>): Pair<String, SpawnOptions> {
        val request = request(pathValue, optionsValue)
        var options = KibiProcess.options(request)
        if (handles.size > KibiProcess.MAX_FDS) fsError(Errno.INVAL, "handles は ${KibiProcess.MAX_FDS} 個まで（${handles.size}）")
        if (handles.isNotEmpty()) options = withHandles(ctx, options, request.fds.mapTo(HashSet()) { it.fd }, handles)
        return request.path to options
    }

    /** `spawn` の引数（lift 済み）を、引数の順に p1 と同じ上限・同じコードで確かめて [KibiProcess.Request] にする */
    @Throws(FsException::class)
    private fun request(pathValue: CanonValue, optionsValue: CanonValue): KibiProcess.Request {
        val path = P2.str(pathValue)
        KibiP2.checkBytes(path, VfsPath.MAX_PATH_BYTES, Errno.NAMETOOLONG, "path")
        val o = (optionsValue as CanonValue.Fields).fields
        var budget = ProcessArgs.MAX_BYTES.toLong()
        fun take(bytes: Long) {
            if (bytes > budget) fsError(Errno.TOOBIG, "引数と環境変数が大きすぎる（上限 ${ProcessArgs.MAX_BYTES} バイト）")
            budget -= bytes
        }
        val argItems = (o[0] as CanonValue.Items).items
        if (argItems.size > KibiProcess.MAX_ARGS) fsError(Errno.TOOBIG, "引数は ${KibiProcess.MAX_ARGS} 個まで（${argItems.size}）")
        val args = argItems.map { v -> P2.str(v).also { take(utf8Length(it) + 1) } }
        val env = ((o[1] as CanonValue.Case).payload as CanonValue.Items?)?.let { list ->
            if (list.items.size > KibiProcess.MAX_ENV) fsError(Errno.TOOBIG, "環境変数は ${KibiProcess.MAX_ENV} 組まで（${list.items.size}）")
            list.items.map { pair ->
                val (k, v) = (pair as CanonValue.Fields).fields
                val name = P2.str(k)
                val value = P2.str(v)
                take(utf8Length(name) + 1 + utf8Length(value) + 1)
                name to value
            }
        }
        val cwd = ((o[2] as CanonValue.Case).payload)?.let { P2.str(it) }
        if (cwd != null) KibiP2.checkBytes(cwd, VfsPath.MAX_PATH_BYTES, Errno.NAMETOOLONG, "cwd")
        val fdItems = (o[3] as CanonValue.Items).items
        if (fdItems.size > KibiProcess.MAX_FDS) fsError(Errno.INVAL, "fds は ${KibiProcess.MAX_FDS} 個まで（${fdItems.size}）")
        val fds = fdItems.map { item ->
            val (fd, source) = (item as CanonValue.Fields).fields
            val s = source as CanonValue.Case
            val parent = if (s.index == 0) KibiP2.u32(s.payload!!) else null
            // カーネルの fd 表には preopen（fd 3）なども入るが、p2 のゲストは fd 3 以降を番号でなく resource で持つ。番号で指せるのは 0..2 だけ（ほかは handles で渡す）
            if (parent != null && parent > STDERR) fsError(Errno.BADF, "p2 の fds の parent は 0..2（$parent）")
            KibiProcess.FdAssign(KibiP2.u32(fd), parent)
        }
        return KibiProcess.Request(path, args, env, cwd, fds)
    }

    /** `handles` を [options] に当てる（`fds` の後。[used] は `fds` で書いた fd） */
    @Throws(FsException::class)
    private fun withHandles(ctx: ProcessContext, options: SpawnOptions, used: Set<Long>, handles: List<CanonValue>): SpawnOptions {
        val seen = HashSet(used)
        val stdio = arrayOf(options.stdio.stdin, options.stdio.stdout, options.stdio.stderr)
        val fds = LinkedHashMap(options.fds)
        ctx.withKernelLock {
            for (item in handles) {
                val (fdValue, sourceValue) = (item as CanonValue.Fields).fields
                val fdLong = KibiP2.u32(fdValue)
                if (!seen.add(fdLong)) fsError(Errno.INVAL, "fd $fdLong を2回書いた")
                if (fdLong > Int.MAX_VALUE) fsError(Errno.BADF, "fd $fdLong は範囲外")
                val fd = fdLong.toInt()
                val source = sourceLocked(ctx, sourceValue as CanonValue.Case)
                if (fd <= STDERR) stdio[fd] = source else fds[fd] = source
            }
        }
        return options.copy(stdio = options.stdio.copy(stdin = stdio[STDIN], stdout = stdio[STDOUT], stderr = stdio[STDERR]), fds = fds)
    }

    /** `handle-source` の実体を、子の fd に割り当てる [StdioSource.Object] にする（カーネルロックの中） */
    @Throws(FsException::class)
    private fun sourceLocked(ctx: ProcessContext, s: CanonValue.Case): StdioSource {
        val v = checkNotNull(s.payload)
        return when (s.index) {
            0, 1 -> {
                val e = P2.entryLocked<StreamEntry>(ctx, v)
                if (e.input != (s.index == 0)) P2.trap("${e.label} を ${if (s.index == 0) "input" else "output"} として渡した")
                val h = e.handle
                if (h == null || e.closed) fsError(Errno.BADF, "閉じた stream は渡せない")
                // 自分の位置で読み書きするファイルの stream は、fd にすると共有の位置になって意味が変わる
                if (!e.shared && ctx.kernel.objects[h.objectId] is OpenFile) fsError(Errno.NOTSUP, "ファイルの stream は渡せない（descriptor を渡す）")
                StdioSource.Object(h.objectId, h.rights and (if (e.input) HandleRights.READ else HandleRights.WRITE))
            }
            2 -> {
                val e = P2.entryLocked<DescriptorEntry>(ctx, v)
                var rights = 0
                if (e.flags and FLAG_READ != 0) rights = rights or HandleRights.READ
                if (e.flags and FLAG_WRITE != 0) rights = rights or HandleRights.WRITE
                // 読み書きの flags のないディレクトリ（mutate-directory だけなど）は、開いたときの権利のまま渡す
                StdioSource.Object(e.handle.objectId, if (rights == 0) e.handle.rights else rights)
            }
            3 -> {
                val e = KibiP2.entryLocked(ctx, v, LABEL)
                if (ctx.kernel.objects[e.handle.objectId] !is ProcessObject) fsError(Errno.BADF, "子のハンドルでない")
                StdioSource.Object(e.handle.objectId, e.handle.rights)
            }
            else -> P2.trap("handle-source の判別子が不正（${s.index}）")
        }
    }

    private fun utf8Length(s: String): Long = s.toByteArray(Charsets.UTF_8).size.toLong()

    /** `exit-status` */
    private fun exitStatus(s: ExitStatus): CanonValue = when (KibiProcess.statusTag(s)) {
        0 -> CanonValue.Case(0, CanonValue.I32(s.code))
        1 -> CanonValue.Case(1, s.signal?.let { CanonValue.some(CanonValue.Case(KibiProcess.SIGNALS.indexOf(it), null)) } ?: CanonValue.NONE)
        else -> CanonValue.Case(2, null)
    }

    /** `process-info`（`args` は切り詰めた UTF-8。文字の境目で切ってあるので、そのまま文字列に戻せる） */
    private fun processInfo(e: KibiProcess.Entry): CanonValue {
        val state = when (e.state) {
            KibiProcess.ListState.FINISHED -> CanonValue.Case(3, exitStatus(checkNotNull(e.exit)))
            else -> CanonValue.Case(e.state.ordinal, null)
        }
        return CanonValue.Fields(
            listOf(
                CanonValue.I32(e.pid),
                CanonValue.I32(e.parentPid),
                state,
                CanonValue.Items(e.args.map { CanonValue.str(String(it, Charsets.UTF_8)) }),
            ),
        )
    }
}
