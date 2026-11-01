package pro.ikili.kibicomputers.core.kernel.vfs

import pro.ikili.kibicomputers.core.kernel.MemoryAccount
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
 * - 外したら（[onUnmounted]）中身をすべて捨て、RAM を返す
 * - 時刻は [clock]（エポックからのナノ秒）。読んだ時刻（atime）は、作った時と [setTimes] だけで変える（読むたびには変えない）
 * - 保存（#82）までは実行時だけ
 *
 * マシンのカーネルロックの中で使う（ロックは持たない）。
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

    /** このファイルシステムが RAM から確保しているバイト数 */
    var usedBytes: Long = 0
        private set

    /** ファイルとディレクトリの数（根を含む） */
    val nodeCount: Int get() = nodes.size

    // ---- パスの操作 ----

    override fun stat(path: VfsPath): FileStat = find(path).stat()

    override fun statNode(node: Long): FileStat = node(node).stat()

    override fun createFile(path: VfsPath): Long = create(path, FileType.REGULAR_FILE).id

    override fun createDirectory(path: VfsPath) {
        create(path, FileType.DIRECTORY)
    }

    override fun removeFile(path: VfsPath) {
        val n = find(path)
        if (n.type == FileType.DIRECTORY) fsError(Errno.ISDIR)
        detach(path)
        free(n, path.name)
    }

    override fun removeDirectory(path: VfsPath) {
        if (path.isRoot) fsError(Errno.BUSY)
        val n = find(path)
        if (n.type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
        if (n.children!!.isNotEmpty()) fsError(Errno.NOTEMPTY)
        detach(path)
        free(n, path.name)
    }

    override fun rename(from: VfsPath, to: VfsPath) {
        if (from.isRoot || to.isRoot) fsError(Errno.BUSY)
        val n = find(from)
        val dst = directoryOf(to)
        val old = dst.children!![to.name]
        if (old === n) return
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
        release(nameCost(from.name))
        if (old != null) free(old, to.name)
        dst.children[to.name] = n
        val now = clock()
        n.changedNanos = now
        touch(src, now)
        touch(dst, now)
    }

    // ---- node の操作 ----

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

    override fun write(node: Long, position: Long, src: ByteArray, off: Int, len: Int): Int {
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

    override fun setSize(node: Long, size: Long) {
        val n = file(node)
        if (size < 0) fsError(Errno.INVAL)
        if (size < n.size) {
            val chunks = n.chunks!!
            // 残す塊の数（端の塊は残し、切った後ろを 0 にする。後で伸ばしたときに 0 が見えるように）
            val keep = (size + CHUNK_BYTES - 1) / CHUNK_BYTES
            val dropped = chunks.tailMap(keep, true)
            release(dropped.size.toLong() * CHUNK_BYTES)
            dropped.clear()
            val tail = (size % CHUNK_BYTES).toInt()
            if (tail != 0) chunks[size / CHUNK_BYTES]?.fill(0, tail, CHUNK_BYTES)
        }
        n.size = size
        touch(n, clock())
    }

    override fun readDirectory(node: Long): List<DirEntry> {
        val n = node(node)
        val children = n.children ?: fsError(Errno.NOTDIR)
        return children.map { (name, child) -> DirEntry(name, child.type, child.id) }
    }

    override fun setTimes(node: Long, accessedNanos: Long?, modifiedNanos: Long?) {
        val n = node(node)
        if (accessedNanos != null) n.accessedNanos = accessedNanos
        if (modifiedNanos != null) n.modifiedNanos = modifiedNanos
        n.changedNanos = clock()
    }

    /** 中身をすべて捨て、RAM を返す（根だけの空のファイルシステムに戻る。node は使い回さない） */
    override fun onUnmounted() {
        nodes.clear()
        root.children!!.clear()
        nodes[root.id] = root
        release(usedBytes)
    }

    // ---- 中身 ----

    private fun node(id: Long): Node = nodes[id] ?: fsError(Errno.STALE)

    private fun file(id: Long): Node {
        val n = node(id)
        if (n.type == FileType.DIRECTORY) fsError(Errno.ISDIR)
        return n
    }

    private fun find(path: VfsPath): Node {
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
        release(NODE_COST + nameCost(name) + data)
    }

    private fun touch(n: Node, now: Long) {
        n.modifiedNanos = now
        n.changedNanos = now
    }

    private fun reserve(bytes: Long) {
        if (!memory.tryReserveBytes(bytes)) fsError(Errno.NOSPC, "RAM が足りない")
        usedBytes += bytes
    }

    private fun release(bytes: Long) {
        if (bytes == 0L) return
        check(bytes <= usedBytes) { "/tmp の使用量が負になる: $usedBytes - $bytes" }
        usedBytes -= bytes
        memory.releaseBytes(bytes)
    }

    private fun nameCost(name: String): Long = name.toByteArray(Charsets.UTF_8).size.toLong()

    companion object {
        /** 根の node */
        const val ROOT_NODE: Long = 1

        /** 中身を確保する塊の大きさ（バイト） */
        const val CHUNK_BYTES: Int = 4096

        /** ファイル・ディレクトリ1つの RAM の消費（名前と中身を除く。仮の値。確定は #251） */
        const val NODE_COST: Long = 256
    }
}
