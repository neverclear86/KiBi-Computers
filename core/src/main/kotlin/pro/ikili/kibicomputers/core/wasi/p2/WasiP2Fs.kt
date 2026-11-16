package pro.ikili.kibicomputers.core.wasi.p2

import pro.ikili.kibicomputers.core.device.DeviceHandle
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.obj.HandleEntry
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.obj.KernelObject
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.tty.Tty
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FileStat
import pro.ikili.kibicomputers.core.kernel.vfs.FileType
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.OpenFile
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.vfs.fsError
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.HostFunctionBody
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1Fd

/**
 * `wasi:filesystem`（types・preopens）の実装（#267。ADR 0267）。p1 の `path_*`・`fd_*`（ADR 0070・0071）と同じ VFS の操作を呼ぶ薄い層。
 *
 * - `get-directories` は、fd 3 の preopen（`SpawnOptions.ROOT_PREOPEN`）と同じディレクトリを `/` の名前で返す（呼ぶたびに参照を増やした
 *   新しい descriptor）。flags は `read`・`mutate-directory`
 * - パスは基点の descriptor（ディレクトリの `OpenFile`。ほかは `not-directory`）から `VfsPath.resolveBeneath` で解く。絶対パスと外へ
 *   出る `..` は `not-permitted`（`NOTCAPABLE`）。基点の解決はカーネルロックの中、VFS の I/O はロックの外（ADR 0052）
 * - 開くのは `FdTable.openObject` を通す（`/dev/tty`・`/dev/device/<address>` の差し替えは p1 と同じ口。ADR 0054）
 * - シンボリックリンク・ハードリンクはない（ADR 0051）: `symlink-at`・`link-at` は `unsupported`、`readlink-at` は `invalid`。
 *   `path-flags` の `symlink-follow` は受けて無視する
 * - descriptor の `read`・`write` と、`read-via-stream` などの stream は、自分の位置で読み書きする（`OpenFile.position` は使わない）
 */
internal object WasiP2Fs {
    const val TYPES: String = "wasi:filesystem/types"
    const val PREOPENS: String = "wasi:filesystem/preopens"

    /** preopen のある fd（p1 と同じ。ADR 0071） */
    private const val PREOPEN_FD: Int = 3

    // descriptor-flags のビット（宣言の順）
    private const val FLAG_READ = 1
    private const val FLAG_WRITE = 2
    private const val FLAG_MUTATE = 32

    fun functions(t: WasiP2Types): Map<String, Map<String, HostFunctionBody>> {
        val w = t[TYPES]
        val descriptorType = w.type<CanonType.Enum>("descriptor-type")
        val stat = w.type<CanonType.Record>("descriptor-stat")
        val dirEntry = w.type<CanonType.Record>("directory-entry")
        val newTimestamp = w.type<CanonType.Variant>("new-timestamp")
        val openFlags = w.type<CanonType.Flags>("open-flags")
        val hashValue = w.type<CanonType.Record>("metadata-hash-value")
        val datetime = t["wasi:clocks/wall-clock"].type<CanonType.Record>("datetime")

        fun body(f: (ProcessContext, List<CanonValue>) -> CanonValue?) = HostFunctionBody { call, args -> f(P2.ctx(call), args) }

        /** `result<T, error-code>` を返す関数。[FsException] は err にする */
        fun fs(f: (ProcessContext, List<CanonValue>) -> CanonValue?) = body { ctx, a -> P2.fsResult(t) { f(ctx, a) } }

        fun time(nanos: Long): CanonValue = if (nanos == 0L) {
            CanonValue.NONE
        } else {
            CanonValue.some(
                datetime.value(
                    "seconds" to CanonValue.I64(Math.floorDiv(nanos, 1_000_000_000L)),
                    "nanoseconds" to CanonValue.I32(Math.floorMod(nanos, 1_000_000_000L).toInt()),
                ),
            )
        }

        fun typeOf(type: FileType): CanonValue = descriptorType.case(
            when (type) {
                FileType.UNKNOWN -> "unknown"
                FileType.CHARACTER_DEVICE -> "character-device"
                FileType.DIRECTORY -> "directory"
                FileType.REGULAR_FILE -> "regular-file"
            },
        )

        fun statValue(st: FileStat): CanonValue = stat.value(
            "type" to typeOf(st.type),
            "link-count" to CanonValue.I64(st.linkCount),
            "size" to CanonValue.I64(st.size),
            "data-access-timestamp" to time(st.accessedNanos),
            "data-modification-timestamp" to time(st.modifiedNanos),
            "status-change-timestamp" to time(st.changedNanos),
        )

        /** `new-timestamp` → VFS の時刻（`null` は変えない） */
        fun timestamp(ctx: ProcessContext, v: CanonValue): Long? = when (newTimestamp.caseName(v)) {
            "no-change" -> null
            "now" -> ctx.environment.clock.realtimeNanos()
            else -> {
                val d = (v as CanonValue.Case).payload!!
                val s = (datetime.field(d, "seconds") as CanonValue.I64).value
                val ns = (datetime.field(d, "nanoseconds") as CanonValue.I32).value.toLong() and 0xffffffffL
                if (ns >= 1_000_000_000L) fsError(Errno.INVAL)
                try {
                    Math.addExact(Math.multiplyExact(s, 1_000_000_000L), ns)
                } catch (_: ArithmeticException) {
                    fsError(Errno.OVERFLOW)
                }
            }
        }

        fun hash(st: FileStat): CanonValue = hashValue.value("lower" to CanonValue.I64(st.node), "upper" to CanonValue.I64(st.device))

        val types = mapOf(
            "[method]descriptor.read-via-stream" to fs { ctx, a -> stream(ctx, a[0], input = true, offset = P2.u64(a[1]), append = false) },
            "[method]descriptor.write-via-stream" to fs { ctx, a -> stream(ctx, a[0], input = false, offset = P2.u64(a[1]), append = false) },
            "[method]descriptor.append-via-stream" to fs { ctx, a -> stream(ctx, a[0], input = false, offset = 0, append = true) },
            "[method]descriptor.advise" to fs { ctx, a ->
                file(ctx, a[0])
                null
            },
            "[method]descriptor.sync-data" to fs { ctx, a ->
                ctx.vfs.sync(openFile(ctx, a[0]))
                null
            },
            "[method]descriptor.sync" to fs { ctx, a ->
                ctx.vfs.sync(openFile(ctx, a[0]))
                null
            },
            "[method]descriptor.get-flags" to fs { ctx, a -> CanonValue.Flags(P2.entry<DescriptorEntry>(ctx, a[0]).flags) },
            "[method]descriptor.get-type" to fs { ctx, a ->
                val e = P2.entry<DescriptorEntry>(ctx, a[0])
                typeOf(ctx.withKernelLock { kindOf(ctx.kernel.objects[e.objectId]) })
            },
            "[method]descriptor.set-size" to fs { ctx, a ->
                ctx.vfs.setSize(file(ctx, a[0]), P2.u64(a[1]))
                null
            },
            "[method]descriptor.set-times" to fs { ctx, a ->
                ctx.vfs.setTimes(openFile(ctx, a[0]), timestamp(ctx, a[1]), timestamp(ctx, a[2]))
                null
            },
            "[method]descriptor.read" to fs { ctx, a ->
                val f = file(ctx, a[0])
                val n = minOf(P2.u64(a[1]), P2.MAX_READ.toLong()).toInt()
                val buf = ByteArray(n)
                val got = if (n == 0) 0 else ctx.vfs.pread(f, P2.u64(a[2]), buf, 0, n)
                ctx.chargeFuel(got.toLong() / WasiP1Fd.BYTES_PER_FUEL)
                CanonValue.Fields(listOf(CanonValue.Bytes(if (got == n) buf else buf.copyOf(got)), CanonValue.Bool(n > 0 && got == 0)))
            },
            "[method]descriptor.write" to fs { ctx, a ->
                val f = file(ctx, a[0])
                val data = (a[1] as CanonValue.Bytes).bytes
                val n = if (data.isEmpty()) 0 else ctx.vfs.pwrite(f, P2.u64(a[2]), data, 0, data.size)
                ctx.chargeFuel(n.toLong() / WasiP1Fd.BYTES_PER_FUEL)
                CanonValue.I64(n.toLong())
            },
            "[method]descriptor.read-directory" to fs { ctx, a ->
                val dir = directory(ctx, a[0])
                val entries = ctx.vfs.readDirectory(dir)
                ctx.chargeFuel(entries.size.toLong())
                P2.own(ctx, DirectoryStreamEntry(entries))
            },
            "[method]descriptor.create-directory-at" to fs { ctx, a ->
                val target = resolve(ctx, a[0], a[1])
                if (target.lastIsDot) fsError(Errno.EXIST)
                ctx.vfs.createDirectory(target.path)
                null
            },
            "[method]descriptor.stat" to fs { ctx, a ->
                val e = P2.entry<DescriptorEntry>(ctx, a[0])
                val obj = ctx.withKernelLock { ctx.kernel.objects[e.objectId] }
                statValue(if (obj is OpenFile) ctx.vfs.stat(obj) else FileStat(0, kindOf(obj)))
            },
            "[method]descriptor.stat-at" to fs { ctx, a ->
                val target = resolve(ctx, a[0], a[2])
                val st = ctx.vfs.stat(target.path)
                if (target.trailingSlash && st.type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
                statValue(st)
            },
            "[method]descriptor.set-times-at" to fs { ctx, a ->
                val target = resolve(ctx, a[0], a[2])
                ctx.vfs.setTimes(target.path, timestamp(ctx, a[3]), timestamp(ctx, a[4]), requireDirectory = target.trailingSlash)
                null
            },
            "[method]descriptor.link-at" to fs { ctx, a ->
                resolve(ctx, a[0], a[2])
                resolve(ctx, a[3], a[4])
                fsError(Errno.NOTSUP)
            },
            "[method]descriptor.open-at" to fs { ctx, a -> openAt(ctx, a, openFlags) },
            "[method]descriptor.readlink-at" to fs { ctx, a ->
                val target = resolve(ctx, a[0], a[1])
                val st = ctx.vfs.stat(target.path)
                if (target.trailingSlash && st.type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
                fsError(Errno.INVAL)
            },
            "[method]descriptor.remove-directory-at" to fs { ctx, a ->
                val target = resolve(ctx, a[0], a[1])
                when (target.lastName) {
                    "." -> fsError(Errno.INVAL)
                    ".." -> fsError(Errno.NOTEMPTY)
                }
                ctx.vfs.removeDirectory(target.path)
                null
            },
            "[method]descriptor.rename-at" to fs { ctx, a ->
                val from = resolve(ctx, a[0], a[1])
                val to = resolve(ctx, a[2], a[3])
                if (from.lastIsDot || to.lastIsDot) fsError(Errno.INVAL)
                ctx.vfs.rename(from.path, to.path, requireDirectory = from.trailingSlash || to.trailingSlash)
                null
            },
            "[method]descriptor.symlink-at" to fs { ctx, a ->
                resolve(ctx, a[0], a[2])
                fsError(Errno.NOTSUP)
            },
            "[method]descriptor.unlink-file-at" to fs { ctx, a ->
                val target = resolve(ctx, a[0], a[1])
                if (target.trailingSlash) {
                    if (ctx.vfs.stat(target.path).type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
                    fsError(Errno.ISDIR)
                }
                ctx.vfs.removeFile(target.path)
                null
            },
            "[method]descriptor.is-same-object" to body { ctx, a ->
                val x = P2.entry<DescriptorEntry>(ctx, a[0])
                val y = P2.entry<DescriptorEntry>(ctx, a[1])
                val (fx, fy) = ctx.withKernelLock { ctx.kernel.objects[x.objectId] to ctx.kernel.objects[y.objectId] }
                val same = when {
                    fx === fy -> true
                    fx is OpenFile && fy is OpenFile -> try {
                        val sx = ctx.vfs.stat(fx)
                        val sy = ctx.vfs.stat(fy)
                        sx.node == sy.node && sx.device == sy.device
                    } catch (_: FsException) {
                        false
                    }
                    else -> false
                }
                CanonValue.Bool(same)
            },
            "[method]descriptor.metadata-hash" to fs { ctx, a -> hash(ctx.vfs.stat(openFile(ctx, a[0]))) },
            "[method]descriptor.metadata-hash-at" to fs { ctx, a ->
                val target = resolve(ctx, a[0], a[2])
                hash(ctx.vfs.stat(target.path))
            },
            "[method]directory-entry-stream.read-directory-entry" to fs { ctx, a ->
                ctx.withKernelLock {
                    val s = P2.entryLocked<DirectoryStreamEntry>(ctx, a[0])
                    if (s.index >= s.entries.size) {
                        CanonValue.NONE
                    } else {
                        val e = s.entries[s.index++]
                        CanonValue.some(dirEntry.value("type" to typeOf(e.type), "name" to CanonValue.str(e.name)))
                    }
                }
            },
            "filesystem-error-code" to body { ctx, a ->
                val e = P2.entry<ErrorEntry>(ctx, a[0])
                CanonValue.some(WasiP2Errors.code(t, e.errno))
            },
        )
        val preopens = mapOf("get-directories" to body { ctx, _ -> CanonValue.Items(preopens(ctx)) })
        return mapOf(TYPES to types, PREOPENS to preopens)
    }

    private fun kindOf(obj: KernelObject?): FileType = when (obj) {
        is OpenFile -> obj.type
        is Tty, is DeviceHandle -> FileType.CHARACTER_DEVICE
        else -> FileType.UNKNOWN
    }

    /** descriptor [self] が指す [OpenFile]（差し替えた `/dev` のものは `bad-descriptor`） */
    private fun openFile(ctx: ProcessContext, self: CanonValue): OpenFile = ctx.withKernelLock {
        val e = P2.entryLocked<DescriptorEntry>(ctx, self)
        ctx.kernel.objects[e.objectId] as? OpenFile ?: fsError(Errno.BADF)
    }

    /** descriptor [self] が指す普通のファイル（ディレクトリは `is-directory`） */
    private fun file(ctx: ProcessContext, self: CanonValue): OpenFile = openFile(ctx, self).also { if (it.isDirectory) fsError(Errno.ISDIR) }

    /** descriptor [self] が指すディレクトリ（ほかは `not-directory`） */
    private fun directory(ctx: ProcessContext, self: CanonValue): OpenFile = ctx.withKernelLock { directoryLocked(ctx, self) }

    private fun directoryLocked(ctx: ProcessContext, self: CanonValue): OpenFile {
        val e = P2.entryLocked<DescriptorEntry>(ctx, self)
        val obj = ctx.kernel.objects[e.objectId]
        if (obj !is OpenFile || !obj.isDirectory) fsError(Errno.NOTDIR)
        return obj
    }

    /** 辿った先（p1 の `WasiP1Path` と同じ形） */
    private class Target(val path: VfsPath, val trailingSlash: Boolean, val lastName: String) {
        val lastIsDot: Boolean get() = lastName == "." || lastName == ".."
    }

    /** 基点 [self] から [path] を辿る（基点の解決はカーネルロックの中） */
    private fun resolve(ctx: ProcessContext, self: CanonValue, path: CanonValue): Target {
        val text = P2.str(path)
        val dir = directory(ctx, self)
        val resolved = VfsPath.resolveBeneath(dir.path, text)
        val last = text.split('/').lastOrNull { it.isNotEmpty() } ?: ""
        return Target(resolved, VfsPath.hasTrailingSlash(text), last)
    }

    /** `read-via-stream`・`write-via-stream`・`append-via-stream`。stream はファイルの参照を自分で持つ */
    private fun stream(ctx: ProcessContext, self: CanonValue, input: Boolean, offset: Long, append: Boolean): CanonValue = ctx.withKernelLock {
        val e = P2.entryLocked<DescriptorEntry>(ctx, self)
        val obj = ctx.kernel.objects[e.objectId] ?: fsError(Errno.BADF)
        if (obj is OpenFile && obj.isDirectory) fsError(Errno.ISDIR)
        val right = if (input) HandleRights.READ else HandleRights.WRITE
        if (e.handle.rights and right == 0) fsError(Errno.BADF)
        ctx.kernel.objects.retain(e.objectId)
        try {
            P2.ownLocked(ctx, StreamEntry(HandleEntry(e.objectId, rights = right), input, offset, append))
        } catch (ex: Throwable) {
            ctx.kernel.objects.release(e.objectId, ctx.wakeSink)
            throw ex
        }
    }

    /**
     * `open-at(path-flags, path, open-flags, descriptor-flags)`。読み書きは `read`・`write` の flags で決める。`mutate-directory` は
     * 基点が持つときだけ渡す。`file-integrity-sync`・`data-integrity-sync`・`requested-write-sync` は p1 の `DSYNC` などと同じく `unsupported`
     */
    private fun openAt(ctx: ProcessContext, a: List<CanonValue>, openFlags: CanonType.Flags): CanonValue {
        val requested = (a[4] as CanonValue.Flags).bits
        if (requested and (4 or 8 or 16) != 0) fsError(Errno.NOTSUP)
        val target = resolve(ctx, a[0], a[2])
        val base = P2.entry<DescriptorEntry>(ctx, a[0])
        val read = requested and FLAG_READ != 0
        val write = requested and FLAG_WRITE != 0
        val of = a[3]
        val options = OpenOptions(
            read = read,
            write = write,
            create = openFlags.has(of, "create"),
            exclusive = openFlags.has(of, "exclusive"),
            truncate = openFlags.has(of, "truncate"),
            directory = openFlags.has(of, "directory"),
        )
        val file = ctx.vfs.open(target.path, options, target.trailingSlash)
        return try {
            ctx.withKernelLock {
                val id = FdTable.openObject(file, ctx.vfs, ctx.kernel.objects, ctx.kernel.consoleTtyId)
                val rights = (if (read) HandleRights.READ else 0) or (if (write) HandleRights.WRITE else 0)
                val flags = (requested and (FLAG_READ or FLAG_WRITE)) or (base.flags and requested and FLAG_MUTATE)
                try {
                    P2.ownLocked(ctx, DescriptorEntry(HandleEntry(id, rights = rights), flags))
                } catch (ex: Throwable) {
                    ctx.kernel.objects.release(id, ctx.wakeSink)
                    throw ex
                }
            }
        } catch (ex: Throwable) {
            // 表に置く前に失敗した（置いた後の失敗は、上で参照を返している。そのときファイルは表の後始末が閉じる）
            if (ctx.withKernelLock { file.id == 0 }) ctx.vfs.close(file)
            throw ex
        }
    }

    /** `get-directories`。fd 3 がディレクトリなら、それを `/` の名前で（ADR 0071 の preopen と同じ見え方） */
    private fun preopens(ctx: ProcessContext): List<CanonValue> = ctx.withKernelLock {
        val process = ctx.kernel.processes[ctx.pid] ?: P2.trap("プロセスがない")
        val entry = process.handles[PREOPEN_FD] ?: return@withKernelLock emptyList()
        val obj = ctx.kernel.objects[entry.objectId]
        if (obj !is OpenFile || !obj.isDirectory) return@withKernelLock emptyList()
        ctx.kernel.objects.retain(entry.objectId)
        val h = try {
            P2.ownLocked(ctx, DescriptorEntry(HandleEntry(entry.objectId, rights = HandleRights.READ), FLAG_READ or FLAG_MUTATE))
        } catch (ex: Throwable) {
            ctx.kernel.objects.release(entry.objectId, ctx.wakeSink)
            throw ex
        }
        listOf(CanonValue.Fields(listOf(h, CanonValue.str("/"))))
    }
}
