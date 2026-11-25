package pro.ikili.kibicomputers.core.kernel.vfs

import pro.ikili.kibicomputers.core.kernel.MemoryAccount
import pro.ikili.kibicomputers.core.kernel.process.StackHeadroom
import java.util.TreeMap

/**
 * メモリ上のファイルシステム（`/tmp`。#53）。中身はマシンの RAM（[MemoryAccount]）から確保し、プロセスの線形メモリと同じ枠で数える。
 * RAM が足りなければ書き込み・作成は `NOSPC`（書けた分があれば、そこまでの短い書き込み）。
 *
 * - RAM に数えるもの（どれも仮の値。確定は #251）:
 *   - ファイル・ディレクトリ1つにつき [NODE_COST] バイトと、名前の UTF-8 のバイト数（根は数えない）
 *   - 普通のファイルの中身は [CHUNK_BYTES] バイトの塊ごと。書いたことのない塊（穴）は数えない（伸ばしただけのファイルは疎）
 * - node は 1（根）から順に割り当て、使い回さない。名前を変えても変わらない
 * - 消したファイルは、すぐに中身を返す。開いたままでも、その後の操作は `STALE`
 *   （FS には開く・閉じるが伝わらないので、最後に閉じる時を待てない。ADR 0051）
 * - 外したら（[onUnmounted]）中身をすべて捨て、RAM を返す。その後の操作はすべて `IO`（[revoked]。#432）
 *   VFS をカーネルロックの外で使うので、外す前にマウントを見つけたスレッドが、外した後に作る・書くことがある。
 *   そのときに RAM を確保すると、返す時がないので、断る
 * - 時刻は [clock]（エポックからのナノ秒）。読んだ時刻（atime）は、作った時と [setTimes] だけで変える（読むたびには変えない）
 * - 動いている間の中身は、待機点保存と一緒に `TMPFS` 節に保存する（#82、ADR 0082。[snapshot]・[restore]）
 *
 * カーネルロックの外で、同じマシンのいくつものスレッドから同時に使う。中身は自分のモニター（`@Synchronized`・`synchronized(this)`）で守る（中は I/O のないメモリの操作だけ）。
 *
 * スタックの余裕（#883）: 変える操作（[createFile]・[createDirectory]・[removeFile]・[removeDirectory]・[rename]・[write]・[append]・
 * [setSize]・[allocate]）は、ゲストのホストの関数の本体（VFS の操作）の深さから呼ばれる。中で RAM の勘定（マシン → サーバー全体の別々の CAS）と、
 * 自分の [usedBytes]・木を順に変えるので、途中でスタックが尽きると、勘定と [usedBytes] がずれる（[onUnmounted] で返す量が合わず、
 * マシン・サーバー全体に残る）か、木が半端になる（`size` の外の塊は、次の保存の `TMPFS` 節が読めなくなる）。返す側（[releaseRam]）で確かめると
 * 返すこと自体が飛ばされ、同じ深さで `catch` して戻すとまた溢れる（ADR 0043）ので、操作の入口で 1 回、モニターを取る前・何も変える前に
 * [StackHeadroom.TMPFS_FRAMES] 段を確かめる（ディスクの `io`・`release` と同じ形）。足りなければ何も変えずに `StackOverflowError` を投げる
 * （呼び手は飲まずに伝え、プロセスだけが `call stack exhausted` で終わる）。中から同じクラスの操作を呼ぶ形（[append] → 書く、
 * [allocate] → 大きさを変える）は、確かめを 2 回払わないよう、中身を `...Locked` に分けている。
 * 読むだけの操作と [setTimes] は勘定に触らないので確かめない。[onUnmounted] は浅いところ（最後のプロセスの終わりの `finishLocked`・
 * 主スレッド・起動用のスレッドの `stop` から `unmountTmpLocked`、`MachineRestore.discard`）からだけ呼ばれ、ゲストの `unmount` は `/tmp` を
 * `BUSY` で断るので、ゲストの深さからは入らない
 */
internal class TmpFileSystem(
    private val memory: MemoryAccount,
    private val clock: () -> Long,
) : FileSystem {
    private class Node(val id: Long, val type: FileType, now: Long) {
        var size = 0L
        var accessedNanos = now
        var modifiedNanos = now
        var changedNanos = now

        /** 普通のファイルの中身（塊の番号 → 塊）。ない塊は 0 */
        val chunks: TreeMap<Long, ByteArray>? = if (type == FileType.REGULAR_FILE) TreeMap() else null

        /** ディレクトリの中身（名前 → node） */
        val children: TreeMap<String, Node>? = if (type == FileType.DIRECTORY) TreeMap() else null

        fun stat() = FileStat(
            id,
            type,
            if (type == FileType.REGULAR_FILE) size else 0,
            accessedNanos = accessedNanos,
            modifiedNanos = modifiedNanos,
            changedNanos = changedNanos,
        )
    }

    private var nextNode = ROOT_NODE
    private val nodes = HashMap<Long, Node>()
    private val root = Node(nextNode++, FileType.DIRECTORY, clock()).also { nodes[it.id] = it }

    /** 外した（[onUnmounted]）。この後の操作は `IO`（[sizeHint] は 0） */
    private var revoked = false

    /** このファイルシステムが RAM から確保しているバイト数 */
    @Volatile
    var usedBytes: Long = 0
        private set

    /** ファイルとディレクトリの数（根を含む） */
    val nodeCount: Int @Synchronized get() = nodes.size

    /**
     * 容量はマシンの RAM 全体、空きはその残り（プロセスの線形メモリなどとも分け合う。#453、ADR 0453）。空きは目安で、
     * サーバー全体の勘定で断られることがあるので、書けることは保証しない。項目の数の上限はない（`null`）。根は数えない
     */
    override fun usage(): FsUsage = FsUsage(
        totalBytes = memory.limitBytes,
        usedBytes = usedBytes,
        availableBytes = maxOf(0L, memory.limitBytes - memory.usedBytes),
        totalEntries = null,
        usedEntries = maxOf(0, nodeCount - 1).toLong(),
    )

    // ---- パスの操作 ----

    @Synchronized
    override fun stat(path: VfsPath): FileStat = find(path).stat()

    @Synchronized
    override fun statNode(node: Long): FileStat = node(node).stat()

    override fun createFile(path: VfsPath): Long = mutate { create(path, FileType.REGULAR_FILE).id }

    override fun createDirectory(path: VfsPath) {
        mutate { create(path, FileType.DIRECTORY) }
    }

    override fun removeFile(path: VfsPath): Unit = mutate {
        val n = find(path)
        if (n.type == FileType.DIRECTORY) fsError(Errno.ISDIR)
        detach(path)
        free(n, path.name)
    }

    override fun removeDirectory(path: VfsPath): Unit = mutate {
        if (path.isRoot) fsError(Errno.BUSY)
        val n = find(path)
        if (n.type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
        if (n.children!!.isNotEmpty()) fsError(Errno.NOTEMPTY)
        detach(path)
        free(n, path.name)
    }

    override fun rename(from: VfsPath, to: VfsPath, requireDirectory: Boolean): Unit = mutate {
        if (from.isRoot || to.isRoot) fsError(Errno.BUSY)
        val n = find(from)
        if (requireDirectory && n.type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
        val dst = directoryOf(to)
        val old = dst.children!![to.name]
        if (old === n) return@mutate
        if (to.startsWith(from)) fsError(Errno.INVAL, "ディレクトリを自分の下へは移せない")
        if (old != null) {
            if (n.type == FileType.DIRECTORY && old.type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
            if (n.type != FileType.DIRECTORY && old.type == FileType.DIRECTORY) fsError(Errno.ISDIR)
            if (old.type == FileType.DIRECTORY && old.children!!.isNotEmpty()) fsError(Errno.NOTEMPTY)
        }
        // 新しい名前の分を先に確保する（足りなければ何も変えない）
        reserve(nameCost(to.name))
        val src = directoryOf(from)
        src.children!!.remove(from.name)
        releaseRam(nameCost(from.name))
        if (old != null) free(old, to.name)
        dst.children[to.name] = n
        val now = clock()
        n.changedNanos = now
        touch(src, now)
        touch(dst, now)
    }

    // ---- node の操作 ----

    @Synchronized
    override fun read(node: Long, position: Long, dst: ByteArray, off: Int, len: Int): Int {
        val n = file(node)
        if (position < 0) fsError(Errno.INVAL)
        if (position >= n.size || len <= 0) return 0
        val count = minOf(len.toLong(), n.size - position).toInt()
        var done = 0
        while (done < count) {
            val pos = position + done
            val index = pos / CHUNK_BYTES
            val inChunk = (pos % CHUNK_BYTES).toInt()
            val step = minOf(count - done, CHUNK_BYTES - inChunk)
            val chunk = n.chunks!![index]
            if (chunk == null) {
                dst.fill(0, off + done, off + done + step)
            } else {
                System.arraycopy(chunk, inChunk, dst, off + done, step)
            }
            done += step
        }
        return count
    }

    override fun write(node: Long, position: Long, src: ByteArray, off: Int, len: Int): Int = mutate { writeLocked(node, position, src, off, len) }

    // 確かめは入口の 1 回だけ（塊ごとに確かめると、塊を足した後・`size` を直す前に尽きる窓が残り、大きな書き込みでは何回も払う）
    private fun writeLocked(node: Long, position: Long, src: ByteArray, off: Int, len: Int): Int {
        val n = file(node)
        if (position < 0) fsError(Errno.INVAL)
        if (len <= 0) return 0
        if (position > Long.MAX_VALUE - len) fsError(Errno.FBIG)
        val chunks = n.chunks!!
        var done = 0
        while (done < len) {
            val pos = position + done
            val index = pos / CHUNK_BYTES
            val inChunk = (pos % CHUNK_BYTES).toInt()
            val step = minOf(len - done, CHUNK_BYTES - inChunk)
            var chunk = chunks[index]
            if (chunk == null) {
                if (!memory.tryReserveBytes(CHUNK_BYTES.toLong())) {
                    if (done == 0) fsError(Errno.NOSPC, "RAM が足りない")
                    break
                }
                usedBytes += CHUNK_BYTES
                chunk = ByteArray(CHUNK_BYTES)
                chunks[index] = chunk
            }
            System.arraycopy(src, off + done, chunk, inChunk, step)
            done += step
        }
        val end = position + done
        if (end > n.size) n.size = end
        touch(n, clock())
        return done
    }

    override fun append(node: Long, src: ByteArray, off: Int, len: Int): Written = mutate {
        val position = file(node).size
        Written(position, writeLocked(node, position, src, off, len))
    }

    override fun allocate(node: Long, size: Long): Unit = mutate {
        if (file(node).size < size) setSizeLocked(node, size)
    }

    @Synchronized
    override fun sizeHint(node: Long): Long = if (revoked) 0 else nodes[node]?.takeIf { it.type == FileType.REGULAR_FILE }?.size ?: 0

    override fun setSize(node: Long, size: Long): Unit = mutate { setSizeLocked(node, size) }

    private fun setSizeLocked(node: Long, size: Long) {
        val n = file(node)
        if (size < 0) fsError(Errno.INVAL)
        if (size < n.size) {
            val chunks = n.chunks!!
            // 残す塊の数（端の塊は残し、切った後ろを 0 にする。後で伸ばしたときに 0 が見えるように）
            val keep = (size + CHUNK_BYTES - 1) / CHUNK_BYTES
            val dropped = chunks.tailMap(keep, true)
            releaseRam(dropped.size.toLong() * CHUNK_BYTES)
            dropped.clear()
            val tail = (size % CHUNK_BYTES).toInt()
            if (tail != 0) chunks[size / CHUNK_BYTES]?.fill(0, tail, CHUNK_BYTES)
        }
        n.size = size
        touch(n, clock())
    }

    @Synchronized
    override fun readDirectory(node: Long): List<DirEntry> {
        val n = node(node)
        val children = n.children ?: fsError(Errno.NOTDIR)
        return children.map { (name, child) -> DirEntry(name, child.type, child.id) }
    }

    @Synchronized
    override fun setTimes(node: Long, accessedNanos: Long?, modifiedNanos: Long?) {
        val n = node(node)
        if (accessedNanos != null) n.accessedNanos = accessedNanos
        if (modifiedNanos != null) n.modifiedNanos = modifiedNanos
        n.changedNanos = clock()
    }

    /** 中身をすべて捨て、RAM を返す。この後の操作は `IO`（外す前に場所を見つけたスレッドが、RAM を確保したまま残さないように。#432） */
    @Synchronized
    override fun onUnmounted() {
        revoked = true
        nodes.clear()
        root.children!!.clear()
        nodes[root.id] = root
        releaseRam(usedBytes)
    }

    /**
     * 木と [usedBytes] の食い違いを確かめる（テスト用。スタックが尽きた後の状態を見る。#883・#902）。食い違いの説明の一覧（なければ空）。
     * 根から辿った node が [nodes] とちょうど同じこと（孤立した node・2 回指される node がない）、普通のファイルの塊が `size` の中にあること
     * （`TmpfsSectionCodec.read` が断る形でないこと）、木から数え直した RAM が [usedBytes] と合うこと。マシンの勘定は、ほかの使い手と分け合うので見ない
     */
    @Synchronized
    internal fun consistencyProblemsForTest(): List<String> {
        val problems = ArrayList<String>()
        if (revoked) {
            if (usedBytes != 0L) problems += "外した後に usedBytes $usedBytes が残った"
            return problems
        }
        val seen = HashMap<Long, Node>()
        var counted = 0L
        val pending = ArrayDeque<Node>()
        seen[root.id] = root
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val n = pending.removeLast()
            if (nodes[n.id] !== n) problems += "node ${n.id} が木にあるのに nodes にない（別の node が入っている）"
            n.chunks?.let { chunks ->
                counted += chunks.size.toLong() * CHUNK_BYTES
                val last = chunks.lastEntry()
                if (last != null && last.key * CHUNK_BYTES >= n.size) problems += "node ${n.id} の塊 ${last.key} が size ${n.size} の外にある"
            }
            n.children?.forEach { (name, child) ->
                counted += NODE_COST + nameCost(name)
                if (seen.put(child.id, child) != null) problems += "node ${child.id} が 2 回指されている（$name）" else pending.addLast(child)
            }
        }
        for (id in nodes.keys) if (id !in seen) problems += "node $id が根から辿れない"
        if (counted != usedBytes) problems += "木から数え直した RAM $counted が usedBytes $usedBytes と違う"
        return problems
    }

    // ---- 保存（#82） ----

    /**
     * 中身の写しを取る（#82、ADR 0082）。自分のモニターの中で取る（止めたマシンなのでプロセスは書いていない）。
     * 外した後なら `null`。[TmpSnapshot.nodes] は node の番号の順
     */
    @Synchronized
    fun snapshot(): TmpSnapshot? {
        if (revoked) return null
        val list = nodes.values.sortedBy { it.id }.map { n ->
            TmpNode(
                n.id,
                n.type,
                n.size,
                n.accessedNanos,
                n.modifiedNanos,
                n.changedNanos,
                n.children?.map { (name, child) -> name to child.id },
                n.chunks?.map { (index, chunk) -> index to chunk.copyOf() },
            )
        }
        return TmpSnapshot(nextNode, list)
    }

    /** RAM に数えるバイト数（[usedBytes] と同じ数え方。#82 の数え直し） */
    private fun countedBytes(snapshot: TmpSnapshot): Long {
        var total = 0L
        for (n in snapshot.nodes) {
            n.children?.forEach { (name, _) -> total += NODE_COST + nameCost(name) }
            n.chunks?.let { total += it.size.toLong() * CHUNK_BYTES }
        }
        return total
    }

    // ---- 中身 ----

    /**
     * 変える操作を、スタックの余裕を確かめてから自分のモニターの中で行う（#883）。確かめはモニターを取る前・何も変える前
     * （足りなければ何も変えずに `StackOverflowError`）。中は I/O のないメモリの操作と勘定の 2 段の CAS だけで、深さに上限がある
     */
    private inline fun <T> mutate(block: () -> T): T {
        StackHeadroom.ensure(StackHeadroom.TMPFS_FRAMES)
        return synchronized(this, block)
    }

    private fun node(id: Long): Node {
        live()
        return nodes[id] ?: fsError(Errno.STALE)
    }

    /** 外した後なら `IO` */
    private fun live() {
        if (revoked) fsError(Errno.IO, "/tmp はもう外した")
    }

    private fun file(id: Long): Node {
        val n = node(id)
        if (n.type == FileType.DIRECTORY) fsError(Errno.ISDIR)
        return n
    }

    private fun find(path: VfsPath): Node {
        live()
        var cur = root
        for (name in path.segments) {
            val children = cur.children ?: fsError(Errno.NOTDIR)
            cur = children[name] ?: fsError(Errno.NOENT)
        }
        return cur
    }

    /** [path] の親のディレクトリ。根なら `EXIST`（根は作れない・すでにある） */
    private fun directoryOf(path: VfsPath): Node {
        val p = find(path.parent ?: fsError(Errno.EXIST))
        if (p.type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
        return p
    }

    private fun create(path: VfsPath, type: FileType): Node {
        val parent = directoryOf(path)
        if (parent.children!!.containsKey(path.name)) fsError(Errno.EXIST)
        reserve(NODE_COST + nameCost(path.name))
        val now = clock()
        val n = Node(nextNode++, type, now)
        nodes[n.id] = n
        parent.children[path.name] = n
        touch(parent, now)
        return n
    }

    /** 親から [path] の名前を外す（RAM は [free] が返す） */
    private fun detach(path: VfsPath) {
        val parent = directoryOf(path)
        parent.children!!.remove(path.name)
        touch(parent, clock())
    }

    /** 名前を外した [n] を捨て、その分（[name] を含む）の RAM を返す。ディレクトリは空であること */
    private fun free(n: Node, name: String) {
        nodes.remove(n.id)
        val chunks = n.chunks
        val data = if (chunks == null) 0L else chunks.size.toLong() * CHUNK_BYTES
        chunks?.clear()
        releaseRam(NODE_COST + nameCost(name) + data)
    }

    private fun touch(n: Node, now: Long) {
        n.modifiedNanos = now
        n.changedNanos = now
    }

    private fun reserve(bytes: Long) {
        if (!memory.tryReserveBytes(bytes)) fsError(Errno.NOSPC, "RAM が足りない")
        usedBytes += bytes
    }

    private fun releaseRam(bytes: Long) {
        if (bytes == 0L) return
        check(bytes <= usedBytes) { "/tmp の使用量が負になる: $usedBytes - $bytes" }
        usedBytes -= bytes
        memory.releaseBytes(bytes)
    }

    private fun nameCost(name: String): Long = name.toByteArray(Charsets.UTF_8).size.toLong()

    /**
     * `/tmp` の中身の写し（#82）。[nodes] は根を含むすべての node（番号の順）。木の形（根から1回ずつ指される）は読む側が確かめる
     */
    class TmpSnapshot(val nextNode: Long, val nodes: List<TmpNode>)

    /** node 1つ。ディレクトリは [children]（名前 → 子の node）、普通のファイルは [chunks]（塊の番号 → [CHUNK_BYTES] バイト） */
    class TmpNode(
        val id: Long,
        val type: FileType,
        val size: Long,
        val accessedNanos: Long,
        val modifiedNanos: Long,
        val changedNanos: Long,
        val children: List<Pair<String, Long>>?,
        val chunks: List<Pair<Long, ByteArray>>?,
    )

    companion object {
        /** 根の node */
        const val ROOT_NODE: Long = 1

        /**
         * 写し [snapshot] から作り直す（#82。戻すのは #85）。RAM は中身から数え直して [memory] から確保し、足りなければ `null`
         * （冷えた起動にする）。木の形は読む側（`TmpfsSectionCodec`）が確かめたものを渡す
         */
        fun restore(memory: MemoryAccount, clock: () -> Long, snapshot: TmpSnapshot): TmpFileSystem? {
            val fs = TmpFileSystem(memory, clock)
            val bytes = fs.countedBytes(snapshot)
            if (bytes > 0 && !memory.tryReserveBytes(bytes)) return null
            // 数えた後、組む途中で失敗する（`copyOf` の `OutOfMemoryError` など）と、fs を呼び手に返せず [onUnmounted] でも返らないので、ここで返す
            // （起動用のスレッドの浅いところからだけ呼ばれるので、同じ深さで戻してよい。#883）
            try {
                synchronized(fs) {
                    fs.usedBytes = bytes
                    fs.nodes.clear()
                    val built = HashMap<Long, Node>()
                    for (t in snapshot.nodes) {
                        val n = if (t.id == ROOT_NODE) fs.root else Node(t.id, t.type, 0)
                        n.size = t.size
                        n.accessedNanos = t.accessedNanos
                        n.modifiedNanos = t.modifiedNanos
                        n.changedNanos = t.changedNanos
                        t.chunks?.forEach { (index, data) -> n.chunks!![index] = data.copyOf() }
                        built[t.id] = n
                    }
                    fs.root.children!!.clear()
                    for (t in snapshot.nodes) t.children?.forEach { (name, child) -> built.getValue(t.id).children!![name] = built.getValue(child) }
                    fs.nodes.putAll(built)
                    fs.nextNode = snapshot.nextNode
                }
            } catch (t: Throwable) {
                if (bytes > 0) memory.releaseBytes(bytes)
                throw t
            }
            return fs
        }

        /** 中身を確保する塊の大きさ（バイト） */
        const val CHUNK_BYTES: Int = 4096

        /** ファイル・ディレクトリ1つの RAM の消費（名前と中身を除く。仮の値。確定は #251） */
        const val NODE_COST: Long = 256
    }
}
