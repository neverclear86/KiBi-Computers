package pro.ikili.kibicomputers.core.wasi.p2

import pro.ikili.kibicomputers.core.kernel.fd.STDERR
import pro.ikili.kibicomputers.core.kernel.fd.STDIN
import pro.ikili.kibicomputers.core.kernel.fd.STDOUT
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
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
 * - `spawn` の引数の上限は p1 の lift と同じ順・同じコード（path → args → env → cwd → fds）。p2 のゲストの fd 表は 0/1/2 だけなので、
 *   `fds` の `parent(n)` は n が 0..2 のときだけ（それ以外は `invalid`）
 * - 読み込みを待つのは `ctx.execWithHandle`（p1 の BLOCK と同じ待ち方。読み込みの間は CPU と許可を返す）
 * - `spawn-with-handles`: `options.fds` を当ててから `handles` を当てる。同じ fd を2回（`handles` の中で、または `fds` と `handles` で）
 *   書いたら `invalid`。渡せるのはパイプの端・`Tty`・共有の位置の stream（`get-stdin` など）・`descriptor`（権利は flags から）・`process`。
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
            "[static]process.spawn" to body { ctx, a -> KibiP2.result { spawn(ctx, a[0], a[1], emptyList()) } },
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
            "spawn-with-handles" to body { ctx, a ->
                KibiP2.result {
                    val handles = (a[2] as CanonValue.Items).items
                    if (handles.size > KibiProcess.MAX_FDS) fsError(Errno.INVAL, "handles は ${KibiProcess.MAX_FDS} 個まで（${handles.size}）")
                    spawn(ctx, a[0], a[1], handles)
                }
            },
        )
        return mapOf(PROCESS to process, PROCESS_POLL to poll)
    }

    /** `spawn`・`spawn-with-handles`。子のハンドルを表に置いて own の値を返す */
    @Throws(FsException::class)
    private fun spawn(ctx: ProcessContext, pathValue: CanonValue, optionsValue: CanonValue, handles: List<CanonValue>): CanonValue {
        val request = request(pathValue, optionsValue)
        var options = KibiProcess.options(request)
        if (handles.isNotEmpty()) options = withHandles(ctx, options, request.fds.mapTo(HashSet()) { it.fd }, handles)
        val h = KibiProcess.limits { ctx.execWithHandle(request.path, KernelDrivers.DEFAULT, options, KibiP2.placement(ctx, LABEL)) }
        return CanonValue.Handle(h.fd)
    }

    /** `spawn` の引数を、p1 の lift と同じ順・同じ上限で確かめて [KibiProcess.Request] にする */
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
            // p2 のゲストの fd 表は 0/1/2 だけ（fd 3 以降は wasi:filesystem の descriptor にある。handles で渡す）
            if (parent != null && parent > STDERR) fsError(Errno.INVAL, "p2 の fds の parent は 0..2（$parent）")
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
