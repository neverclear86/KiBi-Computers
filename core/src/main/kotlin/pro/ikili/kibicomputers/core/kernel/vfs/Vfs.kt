package pro.ikili.kibicomputers.core.kernel.vfs

/**
 * 仮想ファイルシステム（マシンに1つ）。マウントの表（[mounts]。保存の対象）と、マウントの ID → [FileSystem]（実行時だけ）を持つ。
 * 設計は ADR 0051。
 *
 * - パスは、いちばん長く一致するマウントの位置で、どのファイルシステムに属するかを決める
 * - マウントの位置の祖先（`/mnt/disk` をマウントしたときの `/mnt`、根に何もマウントしていないときの `/`）は、
 *   下のファイルシステムになくてもディレクトリとして見える（中身はマウントの位置へ向かう名前だけ。書けない）
 * - マウントの位置と、その祖先は、消す・名前を変えることができない（`BUSY`）
 * - マウントをまたぐ名前の変更は `XDEV`
 * - 開いたファイル（[OpenFile]）は `(マウントの ID, node)` で指す。マウントが外れたら、そのファイルの操作は `IO`
 *
 * マシンのカーネルロックの中で、CPU を持つプロセスのスレッドから使う。
 */
internal class Vfs(
    /** マウントの表（保存の対象。`KernelState.mounts`） */
    val mounts: MountTable = MountTable(),
) {
    private val fileSystems = HashMap<Int, FileSystem>()

    // ---- マウント ----

    /**
     * [fs] を [point] にマウントする。[point] がディレクトリ以外のものなら `NOTDIR`、もうマウントがあれば `BUSY`。
     * [point] はなくてもよい（マウントの位置はディレクトリとして見える）
     */
    fun mount(point: VfsPath, fs: FileSystem, readOnly: Boolean, source: String): MountEntry {
        val existing = statOrNull(point)
        if (existing != null && existing.type != FileType.DIRECTORY) fsError(Errno.NOTDIR, "$point はディレクトリではない")
        val entry = mounts.add(point, readOnly, source)
        fileSystems[entry.id] = fs
        return entry
    }

    /** マウント [id] を外す。開いているファイルは、この後の操作が `IO` になる */
    fun unmount(id: Int): MountEntry {
        val entry = mounts.remove(id)
        fileSystems.remove(id)
        return entry
    }

    /** 復元したマウントの表の [id] に、作り直したファイルシステムをつなぐ（#56・#82） */
    fun attach(id: Int, fs: FileSystem) {
        requireNotNull(mounts[id]) { "マウント $id はない" }
        fileSystems[id] = fs
    }

    // ---- パスの操作 ----

    /** [path] の属性。マウントの位置ならマウントしたファイルシステムの根 */
    fun stat(path: VfsPath): FileStat = statOrNull(path) ?: fsError(Errno.NOENT, "$path はない")

    /**
     * [path] を開く。[OpenFile] を返す（オブジェクトの表に足すのは呼び出し側）。
     * [trailingSlash] は、パスが `/` で終わっていたか（終わっていればディレクトリでなければならない）
     */
    fun open(path: VfsPath, options: OpenOptions, trailingSlash: Boolean = false): OpenFile {
        if (options.create && options.directory) fsError(Errno.INVAL, "O_CREAT と O_DIRECTORY は一緒に使えない")
        if (options.truncate && !options.write) fsError(Errno.INVAL, "O_TRUNC には書く権利が要る")
        if (options.append && !options.write) fsError(Errno.INVAL, "追記には書く権利が要る")
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
            if (options.truncate) loc.fs.setSize(existing.node, 0)
            return OpenFile(loc.mount.id, existing.node, existing.type, path, options.read, options.write, options.append)
        }
        if (!options.create) fsError(Errno.NOENT, "$path はない")
        if (trailingSlash) fsError(Errno.ISDIR, "/ で終わるパスに普通のファイルは作れない")
        val parent = stat(path.parent ?: fsError(Errno.EXIST))
        if (parent.type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
        val loc = locate(path)
        if (loc == null || parent.device == 0L) fsError(Errno.ROFS, "マウントの祖先のディレクトリには作れない")
        if (loc.readOnly) fsError(Errno.ROFS)
        val node = loc.fs.createFile(loc.relative)
        return OpenFile(loc.mount.id, node, FileType.REGULAR_FILE, path, options.read, options.write, options.append)
    }

    /** ディレクトリを作る。あれば（マウントの位置とその祖先を含む）`EXIST` */
    fun createDirectory(path: VfsPath) {
        if (statOrNull(path) != null) fsError(Errno.EXIST, "$path はもうある")
        val loc = writableLocation(path)
        loc.fs.createDirectory(loc.relative)
    }

    /** 普通のファイルを消す */
    fun removeFile(path: VfsPath) {
        if (mounts.hasMountAtOrBelow(path)) fsError(Errno.ISDIR, "$path はマウントの位置")
        val loc = writableLocation(path)
        loc.fs.removeFile(loc.relative)
    }

    /** 空のディレクトリを消す。マウントの位置と、その祖先は `BUSY` */
    fun removeDirectory(path: VfsPath) {
        if (mounts.hasMountAtOrBelow(path)) fsError(Errno.BUSY, "$path はマウントの位置か、その祖先")
        val loc = writableLocation(path)
        loc.fs.removeDirectory(loc.relative)
    }

    /** 名前を変える。マウントをまたげば `XDEV`。マウントの位置と、その祖先は `BUSY` */
    fun rename(from: VfsPath, to: VfsPath) {
        if (mounts.hasMountAtOrBelow(from) || mounts.hasMountAtOrBelow(to)) fsError(Errno.BUSY, "マウントの位置か、その祖先")
        val src = writableLocation(from)
        val dst = writableLocation(to)
        if (src.mount.id != dst.mount.id) fsError(Errno.XDEV, "マウントをまたぐ名前の変更")
        if (from == to) {
            stat(from)
            return
        }
        if (to.startsWith(from)) fsError(Errno.INVAL, "ディレクトリを自分の下へは移せない")
        src.fs.rename(src.relative, dst.relative)
    }

    /**
     * [path] の時刻を変える（`null` は変えない。両方 `null` なら、あるか確かめるだけ）。規則は開いたファイルの [setTimes] と同じで、
     * 読み取り専用のマウントと、マウントの祖先のディレクトリ（どの FS にもない）は `ROFS`
     */
    fun setTimes(path: VfsPath, accessedNanos: Long?, modifiedNanos: Long?) {
        val st = stat(path)
        if (accessedNanos == null && modifiedNanos == null) return
        val loc = locate(path)
        if (loc == null || st.device == 0L) fsError(Errno.ROFS, "マウントの祖先のディレクトリは変えられない")
        if (loc.readOnly) fsError(Errno.ROFS)
        loc.fs.setTimes(st.node, accessedNanos, modifiedNanos)
    }

    // ---- 開いたファイルの操作 ----

    /** 今の位置から読み、位置を進める。読めた数を返す（終わりなら 0） */
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

    /** 今の位置（追記なら終わり）に書き、位置を進める。書けた数を返す */
    fun write(file: OpenFile, src: ByteArray, off: Int, len: Int): Int {
        checkRange(src.size, off, len)
        if (!file.writable) fsError(Errno.BADF, "書く権利がない")
        val fs = fsOf(file)
        val position = if (file.append) fs.statNode(file.node).size else file.position
        val n = pwrite(file, position, src, off, len)
        file.position = position + n
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

    /** 位置を動かし、新しい位置を返す。負になるなら `INVAL` */
    fun seek(file: OpenFile, offset: Long, whence: Whence): Long {
        if (file.isDirectory) fsError(Errno.ISDIR)
        val base = when (whence) {
            Whence.SET -> 0L
            Whence.CUR -> file.position
            Whence.END -> fsOf(file).statNode(file.node).size
        }
        val next = base + offset
        if ((offset > 0 && next < base) || (offset < 0 && next > base)) fsError(Errno.OVERFLOW)
        if (next < 0) fsError(Errno.INVAL, "負の位置")
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
        if (mounts[file.mountId]!!.readOnly || fs.readOnly) fsError(Errno.ROFS)
        fs.setTimes(file.node, accessedNanos, modifiedNanos)
    }

    /** 書き出す */
    fun sync(file: OpenFile) {
        if (file.mountId == 0) return
        fsOf(file).sync(file.node)
    }

    /**
     * ディレクトリの中身を名前の順に返す。ファイルシステムの中身に、マウントの位置へ向かう名前（ディレクトリ）を足す
     * （同じ名前があればマウントの側を見せる）。`.` と `..` は含めない
     */
    fun readDirectory(file: OpenFile): List<DirEntry> {
        if (!file.isDirectory) fsError(Errno.NOTDIR)
        val byName = sortedMapOf<String, DirEntry>()
        if (file.mountId != 0) {
            for (e in fsOf(file).readDirectory(file.node)) byName[e.name] = e
        }
        for (name in mounts.childNamesTowardMounts(file.path)) {
            val child = file.path.child(name)
            val node = mounts.at(child)?.let { m -> fileSystems[m.id]?.let { runCatching { it.stat(VfsPath.ROOT).node }.getOrNull() } }
            byName[name] = DirEntry(name, FileType.DIRECTORY, node ?: 0)
        }
        return byName.values.toList()
    }

    // ---- 中身 ----

    private class Location(val mount: MountEntry, val fs: FileSystem, val relative: VfsPath) {
        val readOnly: Boolean get() = mount.readOnly || fs.readOnly
    }

    /** [path] が属するマウント。どこにも属さなければ `null`。ファイルシステムがつながっていなければ `IO` */
    private fun locate(path: VfsPath): Location? {
        val mount = mounts.covering(path) ?: return null
        val fs = fileSystems[mount.id] ?: fsError(Errno.IO, "マウント ${mount.id} のファイルシステムがない")
        return Location(mount, fs, path.relativeTo(mount.point))
    }

    /** 書く操作の場所。マウントの祖先のディレクトリ（どの FS にもない）か、読み取り専用なら `ROFS` */
    private fun writableLocation(path: VfsPath): Location {
        val loc = locate(path)
        if (loc == null) {
            if (path.isRoot || mounts.hasMountBelow(path)) fsError(Errno.ROFS)
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
                if (mounts.hasMountBelow(path)) return syntheticStat()
                if (e.errno == Errno.NOTDIR) throw e
                return null
            }
        }
        if (path.isRoot || mounts.hasMountBelow(path)) return syntheticStat()
        return null
    }

    private fun syntheticStat(): FileStat = FileStat(node = 0, type = FileType.DIRECTORY, device = 0)

    private fun fsOf(file: OpenFile): FileSystem {
        if (file.mountId == 0) fsError(Errno.ISDIR)
        if (mounts[file.mountId] == null) fsError(Errno.IO, "マウント ${file.mountId} は外れた")
        return fileSystems[file.mountId] ?: fsError(Errno.IO, "マウント ${file.mountId} のファイルシステムがない")
    }

    private fun checkRange(size: Int, off: Int, len: Int) {
        if (off < 0 || len < 0 || off > size - len) fsError(Errno.FAULT, "バッファの範囲外")
    }
}
