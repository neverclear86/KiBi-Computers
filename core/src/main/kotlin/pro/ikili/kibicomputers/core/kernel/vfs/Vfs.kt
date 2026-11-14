package pro.ikili.kibicomputers.core.kernel.vfs

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 仮想ファイルシステム（マシンに1つ）。マウントの表（[mounts]。保存の対象）と、マウントの ID → [FileSystem]（実行時だけ）を持つ。
 * 設計は ADR 0051・0052。
 *
 * - パスは、いちばん長く一致するマウントの位置で、どのファイルシステムに属するかを決める（名前は、その名前を引くディレクトリの
 *   ファイルシステムの規則で比べる。ディスクの中では大文字小文字を区別しない。#606。[MountTable]）
 * - マウントの位置の祖先（`/mnt/disk` をマウントしたときの `/mnt`、根に何もマウントしていないときの `/`）は、
 *   下のファイルシステムになくてもディレクトリとして見える（中身はマウントの位置へ向かう名前だけ。書けない）
 * - マウントの位置と、その祖先は、消す・名前を変えることができない（`BUSY`）
 * - マウントをまたぐ名前の変更は `XDEV`
 * - 開いたファイル（[OpenFile]）は `(マウントの ID, node)` で指す。マウントが外れたら、そのファイルの操作は `IO`
 *
 * ## スレッドとカーネルロック（#52。ADR 0052）
 *
 * ファイルシステムはホストの I/O をすることがあるので、**ファイルシステムに届く操作は、マシンのカーネルロックの外で呼ぶ**。
 * ロックを持ったまま届いたら [IllegalStateException]（[lockHeld] で確かめる）。同じマシンのいくつものスレッドから同時に呼んでよい。
 *
 * - マウントの表を変える操作（[mountLocked]・[unmount]・[attach]・[revokeAll]）はカーネルロックの中で呼ぶ。ファイルシステムの
 *   I/O をする操作を呼ばない。変えるたびに、表とファイルシステムの写しを作り直して公開する（パスの操作はその写しだけを見る）
 * - [mount]（4 引数）は、ロックの外で位置を確かめ（[checkMountPoint]）、続けて表を変える便利な口（マシンを使わないテスト用）
 * - [OpenFile.position] は読まない・変えない（[read]・[write]・[seek] を除く。これらは、ほかのスレッドと分け合わない開いたファイルにだけ使う）。
 *   fd から使う開いたファイルの位置は、呼び出し側がカーネルロックの中で読み書きする
 * - ロックの中で呼んでよいのは、表を変える操作と [sizeHintLocked]・[specialLocked]・[pathHintLocked]・[releaseLater] だけ
 */
internal class Vfs(
    /** マウントの表（保存の対象。`KernelState.mounts`） */
    val mounts: MountTable = MountTable(),
    /** 今のスレッドがカーネルロックを持っているか（マシンを使わないテストは、いつも `false`） */
    private val lockHeld: () -> Boolean = { false },
) {
    /** 公開する写し（変えない） */
    private class View(val table: MountTable, val fileSystems: Map<Int, FileSystem>)

    /** 後でロックの外で行う、ファイルシステムへの知らせ */
    private sealed interface Deferred {
        class Release(val fs: FileSystem, val node: Long) : Deferred

        class Retire(val fs: FileSystem) : Deferred
    }

    private val fileSystems = HashMap<Int, FileSystem>()

    @Volatile
    private var view = View(mounts.snapshot(), emptyMap())

    private val deferred = ConcurrentLinkedQueue<Deferred>()

    /**
     * 後で行う知らせ（[releaseLater]・外したファイルシステムの [FileSystem.retire]）が積まれたときに呼ばれる（カーネルロックの中のことがある）。
     * マシンは、ロックの外で [drainDeferred] を呼ぶ仕事を積む。`null` なら、次にファイルシステムに届く操作で行う
     */
    @Volatile
    var onDeferred: (() -> Unit)? = null

    // ---- マウント ----

    /**
     * [point] にマウントしてよいか確かめる（カーネルロックの外。下のファイルシステムの `stat` をすることがある）。
     * [point] がディレクトリ以外のものなら `NOTDIR`
     */
    fun checkMountPoint(point: VfsPath) {
        val existing = statOrNull(point)
        if (existing != null && existing.type != FileType.DIRECTORY) fsError(Errno.NOTDIR, "$point はディレクトリではない")
    }

    /**
     * [fs] を [point] にマウントする（カーネルロックの中。ファイルシステムを呼ばない）。もうマウントがあれば `BUSY`。
     * 位置の確かめは、前もってロックの外で [checkMountPoint] で行う（確かめてからの間に位置が変わることは防がない。
     * マウントすれば、その位置はマウントの根として見えるので壊れない）
     */
    fun mountLocked(point: VfsPath, fs: FileSystem, readOnly: Boolean, source: String): MountEntry = synchronized(this) {
        val entry = mounts.add(point, readOnly, source, fs.ignoresCase)
        fileSystems[entry.id] = fs
        publish()
        entry
    }

    /**
     * [fs] を [point] にマウントする（カーネルロックの外。[checkMountPoint] → [mountLocked]）。[point] がディレクトリ以外のものなら `NOTDIR`、
     * もうマウントがあれば `BUSY`。[point] はなくてもよい（マウントの位置はディレクトリとして見える）
     */
    fun mount(point: VfsPath, fs: FileSystem, readOnly: Boolean, source: String): MountEntry {
        checkMountPoint(point)
        return mountLocked(point, fs, readOnly, source)
    }

    /**
     * マウント [id] を外す（カーネルロックの中）。開いているファイルは、この後の操作が `IO` になる。ファイルシステムには
     * [FileSystem.onUnmounted] で知らせ、ロックの外で [FileSystem.retire] を呼ぶ
     */
    fun unmount(id: Int): MountEntry = unmountLocked(id, forced = false)

    /**
     * マウント [id] を、下のマウントを見ずに外す（ディスクを抜いたとき。カーネルロックの中。#56）。ほかは [unmount] と同じ
     * （ファイルシステムには [FileSystem.onUnmounted] で知らせるだけで、I/O はしない）
     */
    fun unmountForcedLocked(id: Int): MountEntry = unmountLocked(id, forced = true)

    private fun unmountLocked(id: Int, forced: Boolean): MountEntry = synchronized(this) {
        val entry = if (forced) mounts.removeForced(id) else mounts.remove(id)
        val fs = fileSystems.remove(id)
        publish()
        if (fs != null) {
            fs.onUnmounted()
            if (fs.tracksOpenFiles) defer(Deferred.Retire(fs))
        }
        entry
    }

    /** 復元したマウントの表の [id] に、作り直したファイルシステムをつなぐ（#56・#82。カーネルロックの中） */
    fun attach(id: Int, fs: FileSystem): Unit = synchronized(this) {
        requireNotNull(mounts[id]) { "マウント $id はない" }
        fileSystems[id] = fs
        mounts.setIgnoresCase(id, fs.ignoresCase)
        publish()
    }

    /**
     * マシンが止まった（カーネルロックの中）。各ファイルシステムに [FileSystem.revoke] で知らせ、ロックの外で [FileSystem.retire] を呼ぶ
     * （ホストのディレクトリを使うものは、この後の操作が `IO` になる）
     */
    fun revokeAll(): Unit = synchronized(this) {
        for (fs in fileSystems.values) {
            fs.revoke()
            if (fs.tracksOpenFiles) defer(Deferred.Retire(fs))
        }
    }

    // ---- パスの操作 ----

    /** [path] の属性。マウントの位置ならマウントしたファイルシステムの根 */
    fun stat(path: VfsPath): FileStat = statOrNull(path) ?: fsError(Errno.NOENT, "$path はない")

    /**
     * [path] を開く。[OpenFile] を返す（オブジェクトの表に足すのは呼び出し側）。
     * [trailingSlash] は、パスが `/` で終わっていたか（終わっていればディレクトリでなければならない）。
     *
     * 確かめてから変えるまでの間に、同じマシンのほかのスレッドが変えることがある。`O_CREAT`（`O_EXCL` なし）で作ろうとして
     * `EXIST` になったとき（ほかのスレッドが先に作った）と、開く・`O_TRUNC` で `STALE` になったとき（間に置き換えられた）は、引き直す。
     *
     * 開いたファイルの数の上限（`MFILE`・`NFILE`。[FileSystem.retain]）で失敗するときは、ファイルを作らず、切り詰めもしない（#434）。
     * 作る前に枠を取り（[FileSystem.reserveOpen]）、あるファイルは開いたことを知らせてから切り詰める
     */
    fun open(path: VfsPath, options: OpenOptions, trailingSlash: Boolean = false): OpenFile {
        if (options.create && options.directory) fsError(Errno.INVAL, "O_CREAT と O_DIRECTORY は一緒に使えない")
        if (options.truncate && !options.write) fsError(Errno.INVAL, "O_TRUNC には書く権利が要る")
        if (options.append && !options.write) fsError(Errno.INVAL, "追記には書く権利が要る")
        repeat(MAX_RETRIES - 1) { openOnce(path, options, trailingSlash, lastTry = false)?.let { return it } }
        return checkNotNull(openOnce(path, options, trailingSlash, lastTry = true))
    }

    // 1回開く。引き直すなら null（lastTry ならエラーのまま投げる）
    private fun openOnce(path: VfsPath, options: OpenOptions, trailingSlash: Boolean, lastTry: Boolean): OpenFile? {
        val existing = statOrNull(path)
        if (existing != null) {
            if (options.create && options.exclusive) fsError(Errno.EXIST, "$path はもうある")
            if (existing.type == FileType.DIRECTORY) {
                if (options.write || options.truncate) fsError(Errno.ISDIR, "$path はディレクトリ")
                return OpenFile(existing.device.toInt(), existing.node, FileType.DIRECTORY, path, options.read, false, false)
            }
            if (options.directory || trailingSlash) fsError(Errno.NOTDIR, "$path はディレクトリではない")
            val loc = locate(path) ?: fsError(Errno.IO, "$path のマウントがない")
            if (options.write && loc.readOnly) fsError(Errno.ROFS)
            // 先に開いたことを知らせ（上限なら、切り詰める前に失敗する。#434）、それから切り詰める
            val file = try {
                opened(loc, existing.node, existing.type, path, options, reserved = false)
            } catch (e: FsException) {
                if (e.errno != Errno.STALE || lastTry) throw e
                return null
            }
            if (options.truncate) {
                try {
                    loc.fs.setSize(existing.node, 0)
                } catch (e: FsException) {
                    close(file)
                    if (e.errno != Errno.STALE || lastTry) throw e
                    return null
                }
            }
            return file
        }
        if (!options.create) fsError(Errno.NOENT, "$path はない")
        if (trailingSlash) fsError(Errno.ISDIR, "/ で終わるパスに普通のファイルは作れない")
        val parent = stat(path.parent ?: fsError(Errno.EXIST))
        if (parent.type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
        val loc = locate(path)
        if (loc == null || parent.device == 0L) fsError(Errno.ROFS, "マウントの祖先のディレクトリには作れない")
        if (loc.readOnly) fsError(Errno.ROFS)
        // 開いたままにする枠を、作る前に取る（上限なら、何も作らずに失敗する。#434）
        val reserved = loc.fs.tracksOpenFiles
        if (reserved) loc.fs.reserveOpen()
        val node = try {
            loc.fs.createFile(loc.relative)
        } catch (e: FsException) {
            if (reserved) loc.fs.cancelOpen()
            // ほかのスレッドが先に作った。O_EXCL でなければ、あるものを開き直す
            if (e.errno != Errno.EXIST || options.exclusive || lastTry) throw e
            return null
        } catch (e: Throwable) {
            if (reserved) loc.fs.cancelOpen()
            throw e
        }
        return opened(loc, node, FileType.REGULAR_FILE, path, options, reserved)
    }

    /**
     * 開いたファイルを作り、ファイルシステムに開いたことを知らせる（[FileSystem.retain]）。
     * [reserved] なら [FileSystem.reserveOpen] で取った枠を使う（成功しても失敗しても、その枠は retain が引き受ける）
     */
    private fun opened(loc: Location, node: Long, type: FileType, path: VfsPath, options: OpenOptions, reserved: Boolean): OpenFile {
        val file = OpenFile(loc.mount.id, node, type, path, options.read, options.write, options.append)
        if (loc.fs.tracksOpenFiles && type == FileType.REGULAR_FILE) {
            loc.fs.retain(node, options.write, reserved)
            file.retained = true
        } else if (reserved) {
            loc.fs.cancelOpen()
        }
        return file
    }

    /** ディレクトリを作る。あれば（マウントの位置とその祖先を含む）`EXIST` */
    fun createDirectory(path: VfsPath) {
        if (statOrNull(path) != null) fsError(Errno.EXIST, "$path はもうある")
        val loc = writableLocation(path)
        loc.fs.createDirectory(loc.relative)
    }

    /** 普通のファイルを消す */
    fun removeFile(path: VfsPath) {
        if (view.table.hasMountAtOrBelow(path)) fsError(Errno.ISDIR, "$path はマウントの位置")
        val loc = writableLocation(path)
        loc.fs.removeFile(loc.relative)
    }

    /** 空のディレクトリを消す。マウントの位置と、その祖先は `BUSY` */
    fun removeDirectory(path: VfsPath) {
        if (view.table.hasMountAtOrBelow(path)) fsError(Errno.BUSY, "$path はマウントの位置か、その祖先")
        val loc = writableLocation(path)
        loc.fs.removeDirectory(loc.relative)
    }

    /**
     * 名前を変える。マウントをまたげば `XDEV`。マウントの位置と、その祖先は `BUSY`。
     * [requireDirectory]（末尾の `/`）なら、元がディレクトリでなければ `NOTDIR`
     */
    fun rename(from: VfsPath, to: VfsPath, requireDirectory: Boolean = false) {
        val table = view.table
        if (table.hasMountAtOrBelow(from) || table.hasMountAtOrBelow(to)) fsError(Errno.BUSY, "マウントの位置か、その祖先")
        val src = writableLocation(from)
        val dst = writableLocation(to)
        if (src.mount.id != dst.mount.id) fsError(Errno.XDEV, "マウントをまたぐ名前の変更")
        if (from == to) {
            val st = stat(from)
            if (requireDirectory && st.type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
            return
        }
        if (to.startsWith(from)) fsError(Errno.INVAL, "ディレクトリを自分の下へは移せない")
        src.fs.rename(src.relative, dst.relative, requireDirectory)
    }

    /**
     * [path] の時刻を変える（`null` は変えない。両方 `null` なら、あるか確かめるだけ）。規則は開いたファイルの [setTimes] と同じで、
     * 読み取り専用のマウントと、マウントの祖先のディレクトリ（どの FS にもない）は `ROFS`。
     * [requireDirectory]（末尾の `/`）なら、ディレクトリでなければ `NOTDIR`。確かめてから変えるまでに置き換えられたら（`STALE`）引き直す
     */
    fun setTimes(path: VfsPath, accessedNanos: Long?, modifiedNanos: Long?, requireDirectory: Boolean = false) {
        for (attempt in 1..MAX_RETRIES) {
            val st = stat(path)
            if (requireDirectory && st.type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
            if (accessedNanos == null && modifiedNanos == null) return
            val loc = locate(path)
            if (loc == null || st.device == 0L) fsError(Errno.ROFS, "マウントの祖先のディレクトリは変えられない")
            if (loc.readOnly) fsError(Errno.ROFS)
            try {
                loc.fs.setTimes(st.node, accessedNanos, modifiedNanos)
                return
            } catch (e: FsException) {
                if (e.errno != Errno.STALE || attempt == MAX_RETRIES) throw e
            }
        }
    }

    // ---- 開いたファイルの操作 ----

    /** 今の位置から読み、位置を進める。読めた数を返す（終わりなら 0）。ほかのスレッドと分け合わない開いたファイルにだけ使う */
    fun read(file: OpenFile, dst: ByteArray, off: Int, len: Int): Int {
        val n = pread(file, file.position, dst, off, len)
        file.position += n
        return n
    }

    /** [position] から読む（位置は変えない） */
    fun pread(file: OpenFile, position: Long, dst: ByteArray, off: Int, len: Int): Int {
        checkRange(dst.size, off, len)
        if (!file.readable) fsError(Errno.BADF, "読む権利がない")
        if (file.isDirectory) fsError(Errno.ISDIR)
        if (position < 0) fsError(Errno.INVAL)
        if (len == 0) return 0
        return fsOf(file).read(file.node, position, dst, off, len)
    }

    /** 今の位置（追記なら終わり）に書き、位置を進める。書けた数を返す。ほかのスレッドと分け合わない開いたファイルにだけ使う */
    fun write(file: OpenFile, src: ByteArray, off: Int, len: Int): Int {
        if (file.append) {
            val w = append(file, src, off, len)
            file.position = w.position + w.count
            return w.count
        }
        val n = pwrite(file, file.position, src, off, len)
        file.position += n
        return n
    }

    /** [position] に書く（位置は変えない。追記でも [position] に書く） */
    fun pwrite(file: OpenFile, position: Long, src: ByteArray, off: Int, len: Int): Int {
        checkRange(src.size, off, len)
        if (!file.writable) fsError(Errno.BADF, "書く権利がない")
        if (file.isDirectory) fsError(Errno.ISDIR)
        if (position < 0) fsError(Errno.INVAL)
        if (position > Long.MAX_VALUE - len) fsError(Errno.FBIG)
        if (len == 0) return 0
        return fsOf(file).write(file.node, position, src, off, len)
    }

    /** 終わりに足す（追記。大きさを読んで書くまでを、ファイルシステムの中で不可分に行う）。書いた位置と数を返す */
    fun append(file: OpenFile, src: ByteArray, off: Int, len: Int): Written {
        checkRange(src.size, off, len)
        if (!file.writable) fsError(Errno.BADF, "書く権利がない")
        if (file.isDirectory) fsError(Errno.ISDIR)
        val fs = fsOf(file)
        if (len == 0) return Written(fs.statNode(file.node).size, 0)
        return fs.append(file.node, src, off, len)
    }

    /** [size] まで伸ばす（縮めない。書く権利が要る） */
    fun allocate(file: OpenFile, size: Long) {
        if (!file.writable) fsError(Errno.BADF, "書く権利がない")
        if (file.isDirectory) fsError(Errno.ISDIR)
        if (size < 0) fsError(Errno.INVAL)
        fsOf(file).allocate(file.node, size)
    }

    /** 普通のファイルの大きさ（`seek` の `END` 用） */
    fun size(file: OpenFile): Long {
        if (file.isDirectory) fsError(Errno.ISDIR)
        return fsOf(file).statNode(file.node).size
    }

    /** 位置を動かし、新しい位置を返す。負になるなら `INVAL`。ほかのスレッドと分け合わない開いたファイルにだけ使う */
    fun seek(file: OpenFile, offset: Long, whence: Whence): Long {
        if (file.isDirectory) fsError(Errno.ISDIR)
        val base = when (whence) {
            Whence.SET -> 0L
            Whence.CUR -> file.position
            Whence.END -> size(file)
        }
        val next = seekTarget(base, offset)
        file.position = next
        return next
    }

    /** 開いたファイルの属性 */
    fun stat(file: OpenFile): FileStat {
        if (file.mountId == 0) return syntheticStat()
        return fsOf(file).statNode(file.node).copy(device = file.mountId.toLong())
    }

    /** 大きさを変える（書く権利が要る） */
    fun setSize(file: OpenFile, size: Long) {
        if (!file.writable) fsError(Errno.BADF, "書く権利がない")
        if (file.isDirectory) fsError(Errno.ISDIR)
        if (size < 0) fsError(Errno.INVAL)
        fsOf(file).setSize(file.node, size)
    }

    /**
     * 時刻を変える（`null` は変えない）。書く権利は要らない（POSIX の `futimens` と同じ）。
     * 読み取り専用のマウントと、マウントの祖先のディレクトリ（どの FS にもない）は `ROFS`
     */
    fun setTimes(file: OpenFile, accessedNanos: Long?, modifiedNanos: Long?) {
        if (file.mountId == 0) fsError(Errno.ROFS)
        val fs = fsOf(file)
        if (view.table[file.mountId]?.readOnly != false || fs.readOnly) fsError(Errno.ROFS)
        fs.setTimes(file.node, accessedNanos, modifiedNanos)
    }

    /** 書き出す */
    fun sync(file: OpenFile) {
        if (file.mountId == 0) return
        fsOf(file).sync(file.node)
    }

    /**
     * ディレクトリの中身を名前の順に返す。ファイルシステムの中身に、マウントの位置へ向かう名前（ディレクトリ）を足す
     * （同じ名前があればマウントの側を見せる。大文字小文字を区別しないディレクトリでは、そろえると同じ名前も。#606）。`.` と `..` は含めない
     */
    fun readDirectory(file: OpenFile): List<DirEntry> {
        if (!file.isDirectory) fsError(Errno.NOTDIR)
        val v = view
        val byName = sortedMapOf<String, DirEntry>()
        if (file.mountId != 0) {
            for (e in fsOf(file).readDirectory(file.node)) byName[e.name] = e
        }
        // 大文字小文字を区別しないディレクトリ（ディスク）なら、マウントの位置とそろえると同じ名前は、マウントの側だけを見せる（#606）
        val fold = v.table.ignoresCaseIn(file.path)
        for (name in v.table.childNamesTowardMounts(file.path)) {
            if (fold) byName.keys.removeIf { it != name && DiskNames.sameName(it, name) }
            val child = file.path.child(name)
            val node = v.table.at(child)?.let { m ->
                v.fileSystems[m.id]?.let { fs ->
                    checkUnlocked()
                    runCatching { fs.stat(VfsPath.ROOT).node }.getOrNull()
                }
            }
            byName[name] = DirEntry(name, FileType.DIRECTORY, node ?: 0)
        }
        return byName.values.toList()
    }

    /**
     * ほかのスレッドと分け合わない開いたファイル（fd の表に足さなかったもの）を閉じる。ファイルシステムに開いたことを知らせていれば、
     * 閉じたことを知らせる（カーネルロックの外）
     */
    fun close(file: OpenFile) {
        if (!file.retained) return
        file.retained = false
        val fs = view.fileSystems[file.mountId] ?: return
        checkUnlocked()
        fs.release(file.node)
    }

    // ---- カーネルロックの中で呼んでよいもの ----

    /** 普通のファイルの大きさの目安（`poll` の `nbytes`。I/O をしない）。ディレクトリ・外れたマウントは 0 */
    fun sizeHintLocked(file: OpenFile): Long {
        if (file.mountId == 0 || file.isDirectory) return 0
        return view.fileSystems[file.mountId]?.sizeHint(file.node) ?: 0
    }

    /** マウント [mountId] のファイルシステム（公開した写しから引く。I/O をしない。外れていれば `null`。#82） */
    fun fileSystemLocked(mountId: Int): FileSystem? = view.fileSystems[mountId]

    /**
     * [file] の、ファイルシステムの中の今のパスの目安（[FileSystem.pathHint]。I/O をしない。#82）。
     * マウントの祖先のディレクトリ（`mountId` が 0）・外れたマウント・分からないものは `null`
     */
    fun pathHintLocked(file: OpenFile): VfsPath? {
        if (file.mountId == 0) return null
        return view.fileSystems[file.mountId]?.pathHint(file.node)
    }

    /**
     * [file] を fd に入れるときの差し替え先（`/dev/tty`・`/dev/device/<address>`。[FileSystem.special]。I/O をしない）。
     * ファイルシステムには、node と開いたときのパスの最後の名前を渡す（#693。マウントの中の相対パスは作らない。復元した
     * [OpenFile] のパスはマウントより浅いことがあり、ロックの中で投げうるため）。普通のファイル・ディレクトリ・外れたマウントは `null`
     */
    fun specialLocked(file: OpenFile): SpecialFile? {
        if (file.mountId == 0 || file.isDirectory) return null
        return view.fileSystems[file.mountId]?.special(file.node, file.path.name)
    }

    /**
     * fd の表で最後の参照が外れた開いたファイル [file] の知らせ（[FileSystem.release]）を、後でロックの外で行うよう積む
     * （カーネルロックの中から呼んでよい。マウントが外れていれば何もしない。そのファイルシステムは [FileSystem.retire] で閉じる）
     */
    fun releaseLater(file: OpenFile) {
        if (!file.retained) return
        file.retained = false
        val fs = view.fileSystems[file.mountId] ?: return
        defer(Deferred.Release(fs, file.node))
    }

    /** 積んだ知らせを行う（カーネルロックの外。持っていれば何もしない）。ファイルシステムの例外は捨てる */
    fun drainDeferred() {
        if (lockHeld()) return
        while (true) {
            val d = deferred.poll() ?: return
            try {
                when (d) {
                    is Deferred.Release -> d.fs.release(d.node)
                    is Deferred.Retire -> d.fs.retire()
                }
            } catch (e: Exception) {
                // 閉じるときの失敗は、知らせる先がない
            }
        }
    }

    /** 積んだまま行っていない知らせがあるか（テスト用） */
    val hasDeferred: Boolean get() = deferred.isNotEmpty()

    // ---- 中身 ----

    private fun defer(d: Deferred) {
        deferred.add(d)
        onDeferred?.invoke()
    }

    private fun publish() {
        view = View(mounts.snapshot(), HashMap(fileSystems))
    }

    private class Location(val mount: MountEntry, val fs: FileSystem, val relative: VfsPath) {
        val readOnly: Boolean get() = mount.readOnly || fs.readOnly
    }

    /** ファイルシステムに届く前に、カーネルロックを持っていないことを確かめ、積んだ知らせを行う */
    private fun checkUnlocked() {
        check(!lockHeld()) { "カーネルロックを持ったまま VFS のファイルシステムを呼んだ（ADR 0052）" }
        if (deferred.isNotEmpty()) drainDeferred()
    }

    /** [path] が属するマウント。どこにも属さなければ `null`。ファイルシステムがつながっていなければ `IO` */
    private fun locate(path: VfsPath): Location? {
        val v = view
        val mount = v.table.covering(path) ?: return null
        val fs = v.fileSystems[mount.id] ?: fsError(Errno.IO, "マウント ${mount.id} のファイルシステムがない")
        checkUnlocked()
        // 先頭の名前を落とす（covering は大文字小文字を区別せずに一致することがあるので、relativeTo では確かめない。#606）
        return Location(mount, fs, path.dropLeading(mount.point.depth))
    }

    /** 書く操作の場所。マウントの祖先のディレクトリ（どの FS にもない）か、読み取り専用なら `ROFS` */
    private fun writableLocation(path: VfsPath): Location {
        val loc = locate(path)
        if (loc == null) {
            if (path.isRoot || view.table.hasMountBelow(path)) fsError(Errno.ROFS)
            val parent = path.parent
            if (parent != null && statOrNull(parent)?.device == 0L) fsError(Errno.ROFS)
            fsError(Errno.NOENT, "$path はない")
        }
        if (loc.readOnly) fsError(Errno.ROFS)
        return loc
    }

    private fun statOrNull(path: VfsPath): FileStat? {
        val loc = locate(path)
        if (loc != null) {
            try {
                return loc.fs.stat(loc.relative).copy(device = loc.mount.id.toLong())
            } catch (e: FsException) {
                if (e.errno != Errno.NOENT && e.errno != Errno.NOTDIR) throw e
                if (view.table.hasMountBelow(path)) return syntheticStat()
                if (e.errno == Errno.NOTDIR) throw e
                return null
            }
        }
        if (path.isRoot || view.table.hasMountBelow(path)) return syntheticStat()
        return null
    }

    private fun syntheticStat(): FileStat = FileStat(node = 0, type = FileType.DIRECTORY, device = 0)

    private fun fsOf(file: OpenFile): FileSystem {
        if (file.mountId == 0) fsError(Errno.ISDIR)
        val v = view
        if (v.table[file.mountId] == null) fsError(Errno.IO, "マウント ${file.mountId} は外れた")
        val fs = v.fileSystems[file.mountId] ?: fsError(Errno.IO, "マウント ${file.mountId} のファイルシステムがない")
        checkUnlocked()
        return fs
    }

    private fun checkRange(size: Int, off: Int, len: Int) {
        if (off < 0 || len < 0 || off > size - len) fsError(Errno.FAULT, "バッファの範囲外")
    }

    companion object {
        /** 確かめてから変えるまでに、ほかのスレッドに変えられたときに引き直す回数の上限（実際にはほぼ起きない） */
        const val MAX_RETRIES: Int = 8

        /** `seek` の新しい位置（[base] + [offset]）。あふれたら `OVERFLOW`、負なら `INVAL` */
        fun seekTarget(base: Long, offset: Long): Long {
            val next = base + offset
            if ((offset > 0 && next < base) || (offset < 0 && next > base)) fsError(Errno.OVERFLOW)
            if (next < 0) fsError(Errno.INVAL, "負の位置")
            return next
        }
    }
}
