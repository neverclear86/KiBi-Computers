package pro.ikili.kibicomputers.core.kernel.vfs

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.channels.NonWritableChannelException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * ホストのディレクトリを、そのままファイルシステムとして見せるディスク（#52。ADR 0052）。CC: Tweaked の `WritableFileMount` と同じく、
 * ホストのファイルが正（読み書きはその都度ホストに対して行い、中身のキャッシュもワールドの保存との同期もない）。
 *
 * - ディスクのディレクトリには、普通のファイルとディレクトリだけを置く。名前はゲームの中の名前のまま（ハッシュにしない）。
 *   管理用のファイルはない。外で直接編集したものは、すぐに見える
 * - 名前の規則は [DiskNames]（Windows で使えない名前は、どの OS でも `INVAL`。大文字小文字だけ違う名前は同じ名前）
 * - シンボリックリンク・ジャンクションなど、普通のファイル・ディレクトリ以外のホストのものは、無いものとして扱う（一覧に出さない）。
 *   パスは根から1段ずつ確かめるので、途中にリンクを置かれてもディスクの外へは出ない
 * - node は、マウントのたびに 1（根）から、初めて見たときに割り当てる（使い回さない）。名前を変えても変わらない。
 *   外で消されたら `STALE`
 * - 容量: 普通のファイルの大きさの合計＋項目（根を除く）1つあたり [ENTRY_COST] バイト（[DiskQuota]）。マウントのときに木をたどって数え、
 *   その後はメモリ上の数で管理する（外での変更は、次のマウントまで数に入らないことがある。CC と同じ）
 * - 時刻はホストの属性（ctime は mtime と同じ。Java の基本属性に ctime がないため）
 * - ホストのファイルは、fd で開いている間だけ開いたままにする（[retain]・[release]。CC と同じ）。数は [HostFileBudget] で数える。
 *   開いていない node の読み書き（復元した開いたファイルなど）は、その都度開いて閉じる
 * - 排他: このディスクの操作は、リース（[HostDiskLeases]）の I/O のロックで1本ずつ行う。古い世代・[revoke] の後の操作は `IO`
 * - 割り込み: [FileChannel] は割り込まれると閉じる。割り込まれたスレッドの操作は `IO` にし、ほかのスレッドが使う開いたままの
 *   ファイルは開き直す（あるプロセスの停止が、同じマシンのほかのプロセスのファイルを壊さない）
 */
internal class DiskFileSystem private constructor(
    /** ディスクのディレクトリ（`toRealPath` したもの） */
    val root: Path,
    private val quota: DiskQuota,
    override val readOnly: Boolean,
    private val budget: HostFileBudget,
    private val lease: HostDiskLeases.Lease,
    private val generation: Long,
) : FileSystem {
    /** 開いたままのホストのファイル（[refs] は [retain] の数） */
    private class Handle(var channel: FileChannel, var writable: Boolean, var identity: Any?) {
        var refs = 0
    }

    /** node の種類と、ディスクの根からの実際の名前の並び */
    private class NodeInfo(val type: FileType, var segments: List<String>)

    private var nextNode = ROOT_NODE + 1
    private val byKey = HashMap<String, Long>()
    private val nodes = HashMap<Long, NodeInfo>()
    private val handles = HashMap<Long, Handle>()

    /** `poll` の目安（[sizeHint]。ロックなしで読む） */
    private val sizes = ConcurrentHashMap<Long, Long>()

    /**
     * node → ディスクの根からの名前の並び（[pathHint]。ロックなしで読む。#82）。[nodes] を変える所で一緒に変える。
     * 並びは変えない `List` を入れ替える（読む側が途中の並びを見ない）
     */
    private val paths = ConcurrentHashMap<Long, List<String>>()

    @Volatile
    private var revoked = false

    @Volatile
    private var retired = false

    /** 使っている量（バイト。中身＋項目ごとの [ENTRY_COST]） */
    @Volatile
    var usedBytes: Long = 0
        private set

    /** ファイル・ディレクトリの数（根を除く） */
    @Volatile
    var usedEntries: Int = 0
        private set

    init {
        byKey[""] = ROOT_NODE
        nodes[ROOT_NODE] = NodeInfo(FileType.DIRECTORY, emptyList())
        paths[ROOT_NODE] = emptyList()
    }

    override val tracksOpenFiles: Boolean get() = true

    /** 開いたままのホストのファイルの数（テスト用） */
    val openHandles: Int get() = lease.lock.withLock { handles.size }

    /** node の今のパス（ディスクの根から。テスト用。保存は [pathHint] を使う）。知らない node は `null` */
    fun pathOf(node: Long): VfsPath? = lease.lock.withLock { pathHint(node) }

    /**
     * node の今のパスの目安（ディスクの根から。#82 で、開いたファイルの node → パスを保存するときに使う）。知らない node は `null`。
     * カーネルロックの中から呼ばれるので、[lease] のロックを取らない（ホストの I/O の間ずっと持たれるため。ADR 0082）。
     * 名前の変更と同時に読むと、古いパスのことがある
     */
    override fun pathHint(node: Long): VfsPath? = paths[node]?.fold(VfsPath.ROOT) { p, n -> p.child(n) }

    // ---- パスの操作 ----

    override fun stat(path: VfsPath): FileStat = io {
        val r = resolve(path) ?: fsError(Errno.NOENT, "$path はない")
        statOf(r.node, r.attrs)
    }

    override fun statNode(node: Long): FileStat = io {
        val info = info(node)
        val attrs = attrsOf(hostPath(info.segments)) ?: stale(node)
        if (typeOf(attrs) != info.type) stale(node)
        statOf(node, attrs)
    }

    override fun createFile(path: VfsPath): Long = io { create(path, FileType.REGULAR_FILE) }

    override fun createDirectory(path: VfsPath) {
        io { create(path, FileType.DIRECTORY) }
    }

    override fun removeFile(path: VfsPath): Unit = io {
        writable()
        val r = resolve(path) ?: fsError(Errno.NOENT, "$path はない")
        if (r.type == FileType.DIRECTORY) fsError(Errno.ISDIR)
        closeHandle(r.node)
        host { Files.delete(hostPath(r.segments)) }
        forget(r.node)
        usedBytes -= r.attrs.size() + ENTRY_COST
        usedEntries--
    }

    override fun removeDirectory(path: VfsPath): Unit = io {
        writable()
        if (path.isRoot) fsError(Errno.BUSY)
        val r = resolve(path) ?: fsError(Errno.NOENT, "$path はない")
        if (r.type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
        host { Files.delete(hostPath(r.segments)) }
        forget(r.node)
        usedBytes -= ENTRY_COST
        usedEntries--
    }

    override fun rename(from: VfsPath, to: VfsPath, requireDirectory: Boolean): Unit = io {
        writable()
        if (from.isRoot || to.isRoot) fsError(Errno.BUSY)
        val src = resolve(from) ?: fsError(Errno.NOENT, "$from はない")
        if (requireDirectory && src.type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
        DiskNames.check(to.name)
        val dstParent = resolveDirectory(to.parent!!)
        val old = findChild(dstParent, to.name)
        if (old != null && old.node == src.node) {
            // 大文字小文字だけの名前の変更（同じものを指す）。名前が同じなら何もしない
            if (old.segments.last() == to.name) return@io
        } else {
            if (to.startsWith(from)) fsError(Errno.INVAL, "ディレクトリを自分の下へは移せない")
            if (old != null) {
                if (src.type == FileType.DIRECTORY && old.type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
                if (src.type != FileType.DIRECTORY && old.type == FileType.DIRECTORY) fsError(Errno.ISDIR)
                if (old.type == FileType.DIRECTORY && hasVisibleChildren(old.segments)) fsError(Errno.NOTEMPTY)
            }
        }
        val target = dstParent.segments + to.name
        val srcHost = hostPath(src.segments)
        if (old != null && old.node != src.node) {
            closeHandle(old.node)
            // 置き換えられる側は消える（ホストの move が置き換える。大文字小文字の違う名前で置いていても、名前は新しいほうにそろえる）
            if (old.segments.last() != to.name) host { Files.delete(hostPath(old.segments)) }
            forget(old.node)
            usedBytes -= (if (old.type == FileType.REGULAR_FILE) old.attrs.size() else 0) + ENTRY_COST
            usedEntries--
        }
        if (old != null && old.node == src.node) {
            // 大文字小文字だけの変更は、ホストによっては同じものとして何もしないので、一度別の名前を通す
            val temp = hostPath(dstParent.segments + ".kibi-rename-${System.nanoTime()}")
            host { Files.move(srcHost, temp) }
            host { Files.move(temp, hostPath(target)) }
        } else {
            host { Files.move(srcHost, hostPath(target), StandardCopyOption.REPLACE_EXISTING) }
        }
        relocate(src.segments, target)
    }

    // ---- node の操作 ----

    override fun read(node: Long, position: Long, dst: ByteArray, off: Int, len: Int): Int = io {
        if (position < 0) fsError(Errno.INVAL)
        if (len <= 0) return@io 0
        withChannel(node, write = false) { ch ->
            val buf = ByteBuffer.wrap(dst, off, len)
            var pos = position
            while (buf.hasRemaining()) {
                val n = ch.read(buf, pos)
                if (n <= 0) break
                pos += n
            }
            len - buf.remaining()
        }
    }

    override fun write(node: Long, position: Long, src: ByteArray, off: Int, len: Int): Int = io {
        writable()
        if (position < 0) fsError(Errno.INVAL)
        if (len <= 0) return@io 0
        withChannel(node, write = true) { ch -> writeAt(node, ch, position, src, off, len) }
    }

    override fun append(node: Long, src: ByteArray, off: Int, len: Int): Written = io {
        writable()
        withChannel(node, write = true) { ch ->
            val position = ch.size()
            Written(position, if (len <= 0) 0 else writeAt(node, ch, position, src, off, len))
        }
    }

    override fun setSize(node: Long, size: Long): Unit = io {
        writable()
        if (size < 0) fsError(Errno.INVAL)
        withChannel(node, write = true) { ch -> resize(node, ch, size) }
    }

    override fun allocate(node: Long, size: Long): Unit = io {
        writable()
        withChannel(node, write = true) { ch -> if (ch.size() < size) resize(node, ch, size) }
    }

    override fun sizeHint(node: Long): Long = sizes[node] ?: 0

    override fun readDirectory(node: Long): List<DirEntry> = io {
        val info = info(node)
        if (info.type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
        val dir = hostPath(info.segments)
        if (attrsOf(dir)?.isDirectory != true) stale(node)
        listVisible(info.segments).map { (name, attrs) ->
            val type = typeOf(attrs)
            DirEntry(name, type, nodeFor(info.segments + name, type))
        }
    }

    override fun setTimes(node: Long, accessedNanos: Long?, modifiedNanos: Long?): Unit = io {
        writable()
        val info = info(node)
        val path = hostPath(info.segments)
        if (attrsOf(path) == null) stale(node)
        val view = Files.getFileAttributeView(path, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        host { view.setTimes(modifiedNanos?.let(::fileTime), accessedNanos?.let(::fileTime), null) }
    }

    override fun sync(node: Long): Unit = io {
        if (info(node).type != FileType.REGULAR_FILE) return@io
        withChannel(node, write = false) { ch -> ch.force(true) }
    }

    // ---- 開いたファイル ----

    override fun reserveOpen() {
        budget.acquire()
    }

    override fun cancelOpen() {
        budget.release()
    }

    override fun retain(node: Long, writable: Boolean, reserved: Boolean) {
        // 予約した枠で、まだ使っていないもの（最後に残っていれば返す）
        var spare = reserved
        try {
            io { retainLocked(node, writable) { if (spare) spare = false else budget.acquire() } }
        } finally {
            if (spare) budget.release()
        }
    }

    // [acquire] は、新しくホストのファイルを開くときに枠を1つ取る（予約した枠を使うか、新しく取る）
    private inline fun retainLocked(node: Long, writable: Boolean, acquire: () -> Unit) {
        val info = info(node)
        if (info.type != FileType.REGULAR_FILE) return
        val want = writable && !readOnly
        val h = handles[node]
        if (h != null && (h.writable || !want)) {
            refresh(node, info, h)
            h.refs++
            return
        }
        if (h == null) acquire()
        val (ch, identity) = try {
            openChannel(node, info, want)
        } catch (e: Throwable) {
            if (h == null) budget.release()
            throw e
        }
        if (h == null) {
            handles[node] = Handle(ch, want, identity).also { it.refs = 1 }
        } else {
            // 読むために開いていたものを、書けるもので開き直す
            closeQuietly(h.channel)
            h.channel = ch
            h.identity = identity
            h.writable = true
            h.refs++
        }
    }

    override fun release(node: Long) {
        lease.lock.withLock {
            val h = handles[node] ?: return
            if (--h.refs > 0) return
            handles.remove(node)
            closeQuietly(h.channel)
            budget.release()
        }
    }

    override fun onUnmounted() {
        revoked = true
    }

    override fun revoke() {
        revoked = true
    }

    override fun retire() {
        lease.lock.withLock {
            revoked = true
            if (retired) return
            retired = true
            for (h in handles.values) {
                closeQuietly(h.channel)
                budget.release()
            }
            handles.clear()
        }
        HostDiskLeases.release(lease)
    }

    // ---- 中身 ----

    /** パスを辿った先 */
    private class Resolved(val node: Long, val type: FileType, val segments: List<String>, val attrs: BasicFileAttributes)

    /** このディスクの操作を、リースの I/O のロックの中で行う。古い世代・止められた後は `IO` */
    private inline fun <T> io(block: () -> T): T {
        lease.lock.lock()
        try {
            if (revoked || lease.generation != generation) fsError(Errno.IO, "ディスクはもう使えない（止められたか、別のカーネルが開いた）")
            return host(block)
        } finally {
            lease.lock.unlock()
        }
    }

    private fun writable() {
        if (readOnly) fsError(Errno.ROFS)
    }

    private fun info(node: Long): NodeInfo = nodes[node] ?: fsError(Errno.STALE)

    private fun stale(node: Long): Nothing {
        if (node != ROOT_NODE) {
            closeHandle(node)
            forget(node)
        }
        fsError(Errno.STALE)
    }

    private fun hostPath(segments: List<String>): Path {
        var p = root
        for (s in segments) p = p.resolve(s)
        return p
    }

    /** 属性（リンクをたどらない）。ない・普通のファイルとディレクトリ以外なら `null` */
    private fun attrsOf(path: Path): BasicFileAttributes? {
        val attrs = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: NoSuchFileException) {
            return null
        } catch (e: NotDirectoryException) {
            return null
        } catch (e: FileSystemException) {
            // 途中が普通のファイル（ENOTDIR）・名前として不正など
            return null
        }
        return if (attrs.isRegularFile || attrs.isDirectory) attrs else null
    }

    private fun typeOf(attrs: BasicFileAttributes): FileType = if (attrs.isDirectory) FileType.DIRECTORY else FileType.REGULAR_FILE

    private fun key(segments: List<String>): String = segments.joinToString("/") { DiskNames.fold(it) }

    private fun nodeFor(segments: List<String>, type: FileType): Long {
        val k = key(segments)
        val existing = byKey[k]
        if (existing != null) {
            val info = nodes[existing]!!
            if (info.type == type) {
                info.segments = segments
                paths[existing] = segments
                return existing
            }
            // 外で別の種類に置き換えられた
            closeHandle(existing)
            forget(existing)
        }
        val node = nextNode++
        byKey[k] = node
        nodes[node] = NodeInfo(type, segments)
        paths[node] = segments
        return node
    }

    /** node を表から外す（ディレクトリなら、その下も） */
    private fun forget(node: Long) {
        val info = nodes.remove(node) ?: return
        paths.remove(node)
        val k = key(info.segments)
        byKey.remove(k)
        sizes.remove(node)
        if (info.type == FileType.DIRECTORY) {
            val prefix = "$k/"
            val below = byKey.entries.filter { it.key.startsWith(prefix) }
            for (e in below) {
                byKey.remove(e.key)
                nodes.remove(e.value)
                paths.remove(e.value)
                sizes.remove(e.value)
                closeHandle(e.value)
            }
        }
    }

    /** 名前を変えた木を付け替える（node は変えない） */
    private fun relocate(from: List<String>, to: List<String>) {
        val fromKey = key(from)
        val prefix = "$fromKey/"
        val moved = byKey.entries.filter { it.key == fromKey || it.key.startsWith(prefix) }.map { it.key to it.value }
        for ((k, _) in moved) byKey.remove(k)
        for ((_, node) in moved) {
            val info = nodes[node] ?: continue
            info.segments = to + info.segments.drop(from.size)
            paths[node] = info.segments
            byKey[key(info.segments)] = node
        }
    }

    /** [parent] の下の [name]（大文字小文字をそろえて探す）。なければ `null` */
    private fun findChild(parent: Resolved, name: String): Resolved? {
        if (!DiskNames.isValid(name)) return null
        val exact = parent.segments + name
        val attrs = attrsOf(hostPath(exact))
        if (attrs != null) {
            // 大文字小文字を区別しないホスト（Windows・macOS）では、違う大文字小文字でも見つかる。実際の名前を一覧から引く
            val actual = listVisible(parent.segments).firstOrNull { DiskNames.fold(it.first) == DiskNames.fold(name) }?.first ?: name
            val segs = parent.segments + actual
            val type = typeOf(attrs)
            return Resolved(nodeFor(segs, type), type, segs, attrs)
        }
        val folded = DiskNames.fold(name)
        val match = listVisible(parent.segments).firstOrNull { DiskNames.fold(it.first) == folded } ?: return null
        val segs = parent.segments + match.first
        val type = typeOf(match.second)
        return Resolved(nodeFor(segs, type), type, segs, match.second)
    }

    /** [path] を根から1段ずつ辿る。なければ `null`、途中がディレクトリでなければ `NOTDIR` */
    private fun resolve(path: VfsPath): Resolved? {
        val rootAttrs = attrsOf(root) ?: fsError(Errno.IO, "ディスクのディレクトリ $root がない")
        var cur = Resolved(ROOT_NODE, FileType.DIRECTORY, emptyList(), rootAttrs)
        for (name in path.segments) {
            if (cur.type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
            cur = findChild(cur, name) ?: return null
        }
        return cur
    }

    private fun resolveDirectory(path: VfsPath): Resolved {
        val r = resolve(path) ?: fsError(Errno.NOENT, "$path はない")
        if (r.type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
        return r
    }

    /**
     * ディレクトリの中身のうち、見せるもの（使える名前の、普通のファイルとディレクトリ）を名前の順に。
     * 大文字小文字だけ違う名前が2つあれば（Linux のホストで外から置いたもの）、名前の順で先のものだけ
     */
    private fun listVisible(segments: List<String>): List<Pair<String, BasicFileAttributes>> {
        val dir = hostPath(segments)
        val names = ArrayList<String>()
        host {
            Files.newDirectoryStream(dir).use { stream -> for (p in stream) names += p.fileName.toString() }
        }
        names.sort()
        val seen = HashSet<String>()
        val out = ArrayList<Pair<String, BasicFileAttributes>>()
        for (name in names) {
            if (!DiskNames.isValid(name)) continue
            val attrs = attrsOf(dir.resolve(name)) ?: continue
            if (!seen.add(DiskNames.fold(name))) continue
            out += name to attrs
        }
        return out
    }

    private fun hasVisibleChildren(segments: List<String>): Boolean = host {
        Files.newDirectoryStream(hostPath(segments)).use { it.iterator().hasNext() }
    }

    private fun create(path: VfsPath, type: FileType): Long {
        writable()
        val parentPath = path.parent ?: fsError(Errno.EXIST)
        val parent = resolveDirectory(parentPath)
        if (findChild(parent, path.name) != null) fsError(Errno.EXIST)
        DiskNames.check(path.name)
        if (usedEntries >= quota.maxEntries || usedBytes + ENTRY_COST > quota.maxBytes) fsError(Errno.NOSPC, "ディスクがいっぱい")
        val segs = parent.segments + path.name
        val target = hostPath(segs)
        host {
            if (type == FileType.DIRECTORY) Files.createDirectory(target) else Files.createFile(target)
        }
        usedEntries++
        usedBytes += ENTRY_COST
        val node = nodeFor(segs, type)
        if (type == FileType.REGULAR_FILE) sizes[node] = 0
        return node
    }

    private fun statOf(node: Long, attrs: BasicFileAttributes): FileStat {
        val type = typeOf(attrs)
        val size = if (type == FileType.REGULAR_FILE) attrs.size() else 0
        if (type == FileType.REGULAR_FILE) sizes[node] = size
        val mtime = nanosOf(attrs.lastModifiedTime())
        return FileStat(node, type, size, accessedNanos = nanosOf(attrs.lastAccessTime()), modifiedNanos = mtime, changedNanos = mtime)
    }

    /** 開いて、開いたもの（ホストのファイル）を見分ける値と一緒に返す */
    private fun openChannel(node: Long, info: NodeInfo, write: Boolean): Pair<FileChannel, Any?> {
        val path = hostPath(info.segments)
        val attrs = attrsOf(path)
        if (attrs?.isRegularFile != true) stale(node)
        val options = if (write) {
            setOf(StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        } else {
            setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        }
        val ch = try {
            FileChannel.open(path, options)
        } catch (e: NoSuchFileException) {
            stale(node)
        }
        return ch to identityOf(attrs)
    }

    /**
     * ホストのファイルを見分ける値。POSIX のホストは `fileKey`（デバイスと inode）。ない環境（Windows）は作成時刻
     * （Windows のトンネリングで作成時刻が引き継がれたときは、[sameFile] が大きさも比べる）
     */
    private fun identityOf(attrs: BasicFileAttributes): Any? = attrs.fileKey() ?: attrs.creationTime()

    private fun sameFile(h: Handle, attrs: BasicFileAttributes): Boolean {
        if (identityOf(attrs) != h.identity) return false
        return attrs.fileKey() != null || h.channel.size() == attrs.size()
    }

    /**
     * 開いたままの [h] が、今 [node] のパスにあるファイルを指しているか確かめ、外で置き換えられていたら（別のファイルに書いて名前を変える保存など）
     * 開き直す。開いたままの fd からも、外で保存した新しい中身が見える（「ホストのファイルが正」。ADR 0052）
     */
    private fun refresh(node: Long, info: NodeInfo, h: Handle) {
        val attrs = attrsOf(hostPath(info.segments)) ?: stale(node)
        if (!attrs.isRegularFile) stale(node)
        if (h.channel.isOpen && sameFile(h, attrs)) return
        val (ch, identity) = openChannel(node, info, h.writable)
        closeQuietly(h.channel)
        h.channel = ch
        h.identity = identity
    }

    /**
     * [node] のチャンネルで [block] を行う。開いたままのもの（[retain]）があればそれを、なければその場で開いて閉じる。
     * 開いたままのものが割り込みで閉じていたら、割り込まれたスレッドなら `IO`、ほかのスレッドなら開き直して1回やり直す
     */
    private fun <T> withChannel(node: Long, write: Boolean, block: (FileChannel) -> T): T {
        val info = info(node)
        if (info.type == FileType.DIRECTORY) fsError(Errno.ISDIR)
        val h = handles[node]
        if (h == null || (write && !h.writable)) {
            val (ch, _) = openChannel(node, info, write)
            try {
                return channelOp(ch, block)
            } finally {
                closeQuietly(ch)
            }
        }
        repeat(2) { attempt ->
            try {
                if (Thread.currentThread().isInterrupted) fsError(Errno.IO, "割り込まれた")
                refresh(node, info, h)
                return channelOp(h.channel, block)
            } catch (e: ClosedChannelException) {
                // 割り込まれたスレッドが閉じた。ほかのスレッドなら、次の refresh で開き直す
                if (Thread.currentThread().isInterrupted || attempt == 1) fsError(Errno.IO, "割り込まれた")
            }
        }
        error("ここには来ない")
    }

    private fun <T> channelOp(ch: FileChannel, block: (FileChannel) -> T): T = try {
        block(ch)
    } catch (e: NonWritableChannelException) {
        fsError(Errno.ACCES, "書けないファイル")
    }

    private fun writeAt(node: Long, ch: FileChannel, position: Long, src: ByteArray, off: Int, len: Int): Int {
        if (position > Long.MAX_VALUE - len) fsError(Errno.FBIG)
        val before = ch.size()
        val grow = maxOf(0L, position + len - before)
        val room = quota.maxBytes - usedBytes
        var count = len
        if (grow > room) {
            count = (len - (grow - maxOf(0L, room))).coerceAtMost(len.toLong()).toInt()
            if (count <= 0) fsError(Errno.NOSPC, "ディスクがいっぱい")
        }
        val buf = ByteBuffer.wrap(src, off, count)
        var pos = position
        while (buf.hasRemaining()) pos += ch.write(buf, pos)
        val after = ch.size()
        usedBytes += after - before
        sizes[node] = after
        return count
    }

    private fun resize(node: Long, ch: FileChannel, size: Long) {
        val before = ch.size()
        if (size > before) {
            if (size - before > quota.maxBytes - usedBytes) fsError(Errno.NOSPC, "ディスクがいっぱい")
            ch.write(ByteBuffer.wrap(ByteArray(1)), size - 1)
        } else if (size < before) {
            ch.truncate(size)
        }
        val after = ch.size()
        usedBytes += after - before
        sizes[node] = after
    }

    private fun closeHandle(node: Long) {
        val h = handles.remove(node) ?: return
        closeQuietly(h.channel)
        budget.release()
    }

    /** ホストの例外を errno にする */
    private inline fun <T> host(block: () -> T): T = try {
        block()
    } catch (e: FsException) {
        throw e
    } catch (e: NoSuchFileException) {
        fsError(Errno.NOENT, e.message)
    } catch (e: FileAlreadyExistsException) {
        fsError(Errno.EXIST, e.message)
    } catch (e: DirectoryNotEmptyException) {
        fsError(Errno.NOTEMPTY, e.message)
    } catch (e: NotDirectoryException) {
        fsError(Errno.NOTDIR, e.message)
    } catch (e: AccessDeniedException) {
        fsError(Errno.ACCES, e.message)
    } catch (e: ClosedChannelException) {
        fsError(Errno.IO, "ファイルが閉じられた（割り込まれた）")
    } catch (e: IOException) {
        fsError(Errno.IO, e.toString())
    }

    companion object {
        /** 根の node */
        const val ROOT_NODE: Long = 1

        /** ファイル・ディレクトリ1つの容量の消費（中身を除く。`/tmp` と同じ。仮の値。確定は #251） */
        const val ENTRY_COST: Long = 256

        /**
         * [root] をディスクとして開く（なければ作る）。木をたどって使用量を数えるので、カーネルロックの外・起動用のスレッドから呼ぶ。
         * 同じディレクトリの前の持ち主（ほかの [DiskFileSystem]）は、この時点で古い世代になり、その後の操作は `IO`
         */
        @Throws(IOException::class)
        fun open(root: Path, quota: DiskQuota = DiskQuota(), readOnly: Boolean = false, budget: HostFileBudget = HostFileBudget()): DiskFileSystem {
            if (!readOnly) Files.createDirectories(root)
            val real = root.toRealPath()
            if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) throw NotDirectoryException(real.toString())
            val (lease, generation) = HostDiskLeases.acquire(real)
            val fs = DiskFileSystem(real, quota, readOnly, budget, lease, generation)
            try {
                fs.recount()
            } catch (e: Throwable) {
                fs.retire()
                throw e
            }
            return fs
        }

        private fun nanosOf(t: FileTime): Long = t.to(TimeUnit.NANOSECONDS)

        private fun fileTime(nanos: Long): FileTime = FileTime.from(nanos, TimeUnit.NANOSECONDS)

        private fun closeQuietly(ch: FileChannel) {
            try {
                ch.close()
            } catch (e: IOException) {
                // 閉じるときの失敗は、知らせる先がない
            }
        }
    }

    /** 木をたどって使用量を数え直す（見せないものは数えない） */
    private fun recount() {
        var bytes = 0L
        var entries = 0
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir == root) return FileVisitResult.CONTINUE
                    if (!attrs.isDirectory || !DiskNames.isValid(dir.fileName.toString())) return FileVisitResult.SKIP_SUBTREE
                    entries++
                    bytes += ENTRY_COST
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile && DiskNames.isValid(file.fileName.toString())) {
                        entries++
                        bytes += attrs.size() + ENTRY_COST
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        usedBytes = bytes
        usedEntries = entries
    }
}

private inline fun <T> java.util.concurrent.locks.ReentrantLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
