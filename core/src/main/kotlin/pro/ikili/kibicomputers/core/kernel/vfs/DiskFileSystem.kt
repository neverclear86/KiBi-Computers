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
 *   名前を引くときと一覧は同じ規則で選ぶ（大文字小文字だけ違う名前がホストに2つあれば、名前の順で先の見せるもの。[findChild]）
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
    /** 名前は大文字小文字を区別しない（[DiskNames.sameName]。ADR 0052。#606） */
    override val ignoresCase: Boolean get() = true

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

    /**
     * 容量（[DiskQuota]）と、メモリ上の使用量（#453、ADR 0453）。開かない・数え直さない（リースに触らない）。
     * [usedBytes] と [usedEntries] は別々に読むので、書いている途中なら一瞬ずれることがある。
     * 外で書き足されて使用量が容量を超えても、空きは 0 で止める
     */
    override fun usage(): FsUsage {
        val used = usedBytes
        return FsUsage(
            totalBytes = quota.maxBytes,
            usedBytes = used,
            availableBytes = maxOf(0L, quota.maxBytes - used),
            totalEntries = quota.maxEntries.toLong(),
            usedEntries = usedEntries.toLong(),
        )
    }

    /**
     * ホストの名前の変更（`Files.move`）の直前に呼ぶ口（テスト専用。例外を投げさせて、失敗の道を試す）。リースのロックの中で呼ばれる
     */
    @Volatile
    var beforeHostMove: ((from: Path, to: Path) -> Unit)? = null

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

    /**
     * 保存から戻した開いたファイルの [node]（前のカーネルの番号）を、ディスクの根からのパス [path]・種類 [type] として表に入れる（#85、ADR 0085）。
     * まだマウントしていない（ほかのスレッドと分け合わない）ファイルシステムに、起動用のスレッドから呼ぶ。ホストの I/O はしない
     * （パスが消えていれば、その node の操作は今の規則どおり `STALE`。ADR 0052）。[nextNode] はそれより大きくする。
     * 同じ node・同じパスがもう入っていれば何もしない。違う node が同じパスを持っていれば、後のものは入れない（その操作は `STALE`）
     *
     * @return 入れた（か、同じものが入っていた）
     */
    fun adoptNode(node: Long, path: VfsPath, type: FileType): Boolean = lease.lock.withLock {
        if (node <= ROOT_NODE) return@withLock node == ROOT_NODE && path.isRoot
        val segments = path.segments
        val k = key(segments)
        val existing = nodes[node]
        if (existing != null) return@withLock key(existing.segments) == k && existing.type == type
        if (byKey.containsKey(k)) return@withLock false
        byKey[k] = node
        nodes[node] = NodeInfo(type, segments)
        paths[node] = segments
        if (nextNode <= node) nextNode = node + 1
        true
    }

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

    /**
     * 名前の変更。ホストの操作を先に済ませ、node の表と使用量は最後に直す（途中で失敗しても、表と使用量がホストと食い違わない。#433）。
     * 置き換えは [replaceHost]、大文字小文字だけの変更は [renameCase]。置き換え先の大文字小文字が違えば、置き換えてから名前をそろえる
     * （そろえるのに失敗しても、元の大文字小文字へ戻せていれば、古い大文字小文字のまま成功にする。そろえれば同じ名前なので。
     * 戻せずに一時的な名前に残ったときは、表をその名前に合わせて失敗にする）
     */
    override fun rename(from: VfsPath, to: VfsPath, requireDirectory: Boolean): Unit = io {
        writable()
        if (from.isRoot || to.isRoot) fsError(Errno.BUSY)
        val src = resolve(from) ?: fsError(Errno.NOENT, "$from はない")
        if (requireDirectory && src.type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
        DiskNames.check(to.name)
        val dstParent = resolveDirectory(to.parent!!)
        // 自分の下へ移すかは、解決した結果を大文字小文字をそろえた鍵で比べる（`/d` → `/D/x` も。ホストには触らない）
        val srcKey = key(src.segments)
        val dstParentKey = key(dstParent.segments)
        if (dstParentKey == srcKey || dstParentKey.startsWith("$srcKey/")) fsError(Errno.INVAL, "ディレクトリを自分の下へは移せない")
        val old = findChild(dstParent, to.name)
        val target = dstParent.segments + to.name
        if (old == null) {
            // 置き換えない（こちらの規則では無くても、ホストに見せていないものがあれば EXIST。外のものを消さない）
            host { moveHost(hostPath(src.segments), hostPath(target)) }
            relocate(src.segments, target)
            return@io
        }
        if (old.node == src.node) {
            // 大文字小文字だけの名前の変更（同じものを指す）。名前が同じなら何もしない
            if (old.segments.last() == to.name) return@io
            val (moved, error) = renameCase(src.segments, target)
            relocate(src.segments, moved)
            if (error != null) throw error
            return@io
        }
        if (src.type == FileType.DIRECTORY && old.type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
        if (src.type != FileType.DIRECTORY && old.type == FileType.DIRECTORY) fsError(Errno.ISDIR)
        if (old.type == FileType.DIRECTORY && hasVisibleChildren(old.segments)) fsError(Errno.NOTEMPTY)
        // 置き換えられる側の開いたままのファイルは閉じる（閉じても、次の操作がその場で開き直すだけ）
        closeHandle(old.node)
        val leftover = replaceHost(hostPath(src.segments), old.segments, old.type == FileType.DIRECTORY)
        // ここから先は、ホストでは置き換えが済んでいる。forget は relocate より先（鍵が同じなので、後だと移したものを消す）
        forget(old.node)
        if (!leftover) {
            // 退かしたものが残ったときは、その空のディレクトリが見える項目として残るので、古い項目の分は数えたままにする
            usedBytes -= (if (old.type == FileType.REGULAR_FILE) old.attrs.size() else 0) + ENTRY_COST
            usedEntries--
        }
        relocate(src.segments, old.segments)
        if (old.segments.last() != to.name) {
            // 名前を新しい大文字小文字にそろえる。置き換えは済んでいるので、元の大文字小文字へ戻せたなら（moved == old.segments）、
            // 古い大文字小文字のまま成功にする。一時的な名前に残ったときは、表をそこへ付け替えて失敗にする（成功と言わない）
            val (moved, error) = renameCase(old.segments, target)
            relocate(old.segments, moved)
            if (error != null && moved != old.segments) throw error
        }
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

    /**
     * [parent] の下の [name]（大文字小文字をそろえて探す）。なければ `null`。
     *
     * 一覧（[listVisible]）と同じ規則で選ぶ（#433）: ホストの名前の一覧から、そろえると [name] と同じものを候補にし、名前の順で
     * 最初の「見せるもの」（[visibleAttrs]）を返す。返す名前と属性は同じ項目のもの。属性を読むのは候補だけ（ふつうは1つ）なので、
     * 1段あたりのホストの I/O は「名前の一覧1回＋属性1回」（項目ごとの属性は読まない）。
     *
     * 完全に同じ名前の項目があっても一覧は省けない。Linux では大文字小文字違い（`a` と `A`）が並ぶことがあり、名前の順で先のものを
     * 選ぶには一覧が要る。Windows・macOS では完全一致の属性の読み取りが違う大文字小文字や 8.3 の別名にも当たるので、実際の名前は
     * 一覧でしか分からない（一覧は長い名前しか返さないので、8.3 の別名は候補に出ない）
     */
    private fun findChild(parent: Resolved, name: String): Resolved? {
        if (!DiskNames.isValid(name)) return null
        val dir = hostPath(parent.segments)
        // 候補（ふつうは0〜1個。2個目からだけリストを作る）
        var first: String? = null
        var more: ArrayList<String>? = null
        host {
            Files.newDirectoryStream(dir).use { stream ->
                for (p in stream) {
                    val n = p.fileName.toString()
                    if (!DiskNames.sameName(n, name)) continue
                    val f = first
                    if (f == null) {
                        first = n
                    } else {
                        val list = more ?: arrayListOf(f).also { more = it }
                        list += n
                    }
                }
            }
        }
        val candidates: List<String> = more?.apply { sort() } ?: listOfNotNull(first)
        for (n in candidates) {
            val attrs = visibleAttrs(dir, n) ?: continue
            val segs = parent.segments + n
            val type = typeOf(attrs)
            return Resolved(nodeFor(segs, type), type, segs, attrs)
        }
        return null
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
            // 見せるものかを確かめてから重複を見る（[findChild] と同じ「名前の順で最初の見せるもの」）
            val attrs = visibleAttrs(dir, name) ?: continue
            if (!seen.add(DiskNames.fold(name))) continue
            out += name to attrs
        }
        return out
    }

    /** ディレクトリ [dir] の項目 [name] を見せるなら、その属性（使える名前の、普通のファイルかディレクトリ）。見せないなら `null` */
    private fun visibleAttrs(dir: Path, name: String): BasicFileAttributes? {
        if (!DiskNames.isValid(name)) return null
        return attrsOf(dir.resolve(name))
    }

    /** ホストの名前の変更（[options] がなければ置き換えない。あれば `EXIST`）。先にテスト用の [beforeHostMove] を呼ぶ */
    private fun moveHost(from: Path, to: Path, vararg options: StandardCopyOption) {
        beforeHostMove?.invoke(from, to)
        Files.move(from, to, *options)
    }

    /**
     * [src] で、置き換え先の実際の名前 [oldSegments] の項目を置き換える。失敗したら、ホストは変わらない（#433）。
     *
     * - ファイル: `ATOMIC_MOVE` の1手（POSIX は `rename(2)`、Windows は `MoveFileEx(MOVEFILE_REPLACE_EXISTING)`）。
     *   `REPLACE_EXISTING` だけの move は、先に置き換え先を消してから移すので、移すのに失敗すると置き換え先が消える
     * - ディレクトリ（空なのは確かめ済み）: Windows は既存のディレクトリを `MoveFileEx` で置き換えられないので、どの OS でも同じ手順にする。
     *   古いものを一時的な名前へ退かし、[src] を移し、成功したら退かしたものを消す。移すのに失敗したら、退かしたものを戻す。
     *   退かしたものを消せなかったときは、空のディレクトリのまま残す（置き換えは成功）。戻せなかったときは、退かしたものの表を
     *   一時的な名前へ付け替えて失敗にする
     *
     * @return 退かしたものが空のディレクトリとして残ったか（残れば、使用量にその1項目を数えたままにする）
     */
    private fun replaceHost(src: Path, oldSegments: List<String>, directory: Boolean): Boolean {
        val dst = hostPath(oldSegments)
        if (!directory) {
            host { moveHost(src, dst, StandardCopyOption.ATOMIC_MOVE) }
            return false
        }
        val asideSegments = oldSegments.dropLast(1) + tempName()
        val aside = hostPath(asideSegments)
        host { moveHost(dst, aside) }
        try {
            host { moveHost(src, dst) }
        } catch (e: FsException) {
            try {
                host { moveHost(aside, dst) }
            } catch (ignored: FsException) {
                // 戻せなければ、退かした空のディレクトリが一時的な名前のまま残る。表もそこへ合わせる
                relocate(oldSegments, asideSegments)
            }
            throw e
        }
        return try {
            host { Files.delete(aside) }
            false
        } catch (ignored: FsException) {
            // 空のディレクトリが残るだけ
            true
        }
    }

    /**
     * 大文字小文字だけの名前の変更（[from] → [to]。最後の名前だけが違う）。ホストによっては同じものとして何もしないので、
     * 一度一時的な名前を通す。2手目は置き換えない（Linux で、見せていない大文字小文字違いが外から置かれていれば `EXIST`。外のものを消さない）。
     * 2手目が失敗したら元の名前へ戻す。
     *
     * @return ホストでの今の名前の並びと、失敗したときの例外。成功なら [to] と `null`、戻せたら [from]、戻せなければ一時的な名前
     */
    private fun renameCase(from: List<String>, to: List<String>): Pair<List<String>, FsException?> {
        val temp = from.dropLast(1) + tempName()
        try {
            host { moveHost(hostPath(from), hostPath(temp)) }
        } catch (e: FsException) {
            return from to e
        }
        try {
            host { moveHost(hostPath(temp), hostPath(to)) }
            return to to null
        } catch (e: FsException) {
            return try {
                host { moveHost(hostPath(temp), hostPath(from)) }
                from to e
            } catch (ignored: FsException) {
                temp to e
            }
        }
    }

    private fun tempName(): String = ".kibi-rename-${System.nanoTime()}"

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
         * 同じディレクトリの前の持ち主（ほかの [DiskFileSystem]）は、この時点で古い世代になり、その後の操作は `IO`。
         *
         * [cancelled] は開き手（起動・復元の手順）が取り消されたかを返す。世代を進める直前にリースのロックの中で見て、真なら
         * 世代を進めずに [DiskOpenCancelledException] を投げる（[HostDiskLeases.acquire]。#588）。volatile を読むだけにする
         */
        @Throws(IOException::class)
        fun open(
            root: Path,
            quota: DiskQuota = DiskQuota(),
            readOnly: Boolean = false,
            budget: HostFileBudget = HostFileBudget(),
            cancelled: () -> Boolean = { false },
        ): DiskFileSystem {
            if (!readOnly) Files.createDirectories(root)
            val real = root.toRealPath()
            if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) throw NotDirectoryException(real.toString())
            val (lease, generation) = HostDiskLeases.acquire(real, cancelled)
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
