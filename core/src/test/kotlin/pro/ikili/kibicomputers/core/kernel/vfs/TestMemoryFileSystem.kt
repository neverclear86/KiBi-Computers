package pro.ikili.kibicomputers.core.kernel.vfs

/** テスト用の、メモリ上の小さなファイルシステム（本物の `/tmp` は #53） */
internal class TestMemoryFileSystem(override val readOnly: Boolean = false) : FileSystem {
    private class Node(val id: Long, val type: FileType) {
        var data = ByteArray(0)
        val children = sortedMapOf<String, Long>()
    }

    private var nextNode = 1L
    private val nodes = HashMap<Long, Node>()
    private val root = newNode(FileType.DIRECTORY)

    /** 書いた回数 */
    var syncCount: Int = 0
        private set

    private fun newNode(type: FileType): Node = Node(nextNode++, type).also { nodes[it.id] = it }

    private fun find(path: VfsPath): Node {
        var cur = root
        for (name in path.segments) {
            if (cur.type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
            cur = nodes[cur.children[name] ?: fsError(Errno.NOENT)]!!
        }
        return cur
    }

    private fun parentOf(path: VfsPath): Node {
        val p = find(path.parent ?: fsError(Errno.EXIST))
        if (p.type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
        return p
    }

    private fun node(id: Long): Node = nodes[id] ?: fsError(Errno.STALE)

    private fun statOf(n: Node) = FileStat(n.id, n.type, if (n.type == FileType.REGULAR_FILE) n.data.size.toLong() else 0)

    override fun stat(path: VfsPath): FileStat = statOf(find(path))

    override fun statNode(node: Long): FileStat = statOf(node(node))

    override fun createFile(path: VfsPath): Long {
        val p = parentOf(path)
        if (p.children.containsKey(path.name)) fsError(Errno.EXIST)
        return newNode(FileType.REGULAR_FILE).also { p.children[path.name] = it.id }.id
    }

    override fun createDirectory(path: VfsPath) {
        val p = parentOf(path)
        if (p.children.containsKey(path.name)) fsError(Errno.EXIST)
        p.children[path.name] = newNode(FileType.DIRECTORY).id
    }

    override fun removeFile(path: VfsPath) {
        val n = find(path)
        if (n.type == FileType.DIRECTORY) fsError(Errno.ISDIR)
        parentOf(path).children.remove(path.name)
        nodes.remove(n.id)
    }

    override fun removeDirectory(path: VfsPath) {
        if (path.isRoot) fsError(Errno.BUSY)
        val n = find(path)
        if (n.type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
        if (n.children.isNotEmpty()) fsError(Errno.NOTEMPTY)
        parentOf(path).children.remove(path.name)
        nodes.remove(n.id)
    }

    override fun rename(from: VfsPath, to: VfsPath) {
        val n = find(from)
        val dst = parentOf(to)
        dst.children[to.name]?.let { old -> nodes.remove(old) }
        parentOf(from).children.remove(from.name)
        dst.children[to.name] = n.id
    }

    override fun read(node: Long, position: Long, dst: ByteArray, off: Int, len: Int): Int {
        val n = node(node)
        if (position >= n.data.size) return 0
        val count = minOf(len.toLong(), n.data.size - position).toInt()
        System.arraycopy(n.data, position.toInt(), dst, off, count)
        return count
    }

    override fun write(node: Long, position: Long, src: ByteArray, off: Int, len: Int): Int {
        val n = node(node)
        val end = position + len
        if (end > n.data.size) n.data = n.data.copyOf(end.toInt())
        System.arraycopy(src, off, n.data, position.toInt(), len)
        return len
    }

    override fun setSize(node: Long, size: Long) {
        val n = node(node)
        n.data = n.data.copyOf(size.toInt())
    }

    override fun readDirectory(node: Long): List<DirEntry> {
        val n = node(node)
        if (n.type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
        return n.children.map { (name, id) -> DirEntry(name, nodes[id]!!.type, id) }
    }

    override fun sync(node: Long) {
        syncCount++
    }
}
