package pro.ikili.kibicomputers.core.kernel.vfs

/**
 * テスト用の、空きの少ないファイルシステム（中身は [inner] に任せる）。保存の失敗の道（#837）を確かめる。
 *
 * - [free] は残りのバイト。伸ばす分（書く・長さを変える）が足りなければ `NOSPC`（ディスクと同じく、書く前に断る）
 * - [stealFreed] が true なら、縮めて空いた分を、すぐにほかのプロセスが使ったことにする（[free] を増やさない）
 * - [failWriteNode] への書き込みは、位置 [failWriteAfter] から先で `IO` になる（そこまでは短い書き込み）
 * - [failStat] があれば、`stat` はどのパスでもそのエラーになる
 * - [onNoSpace] があれば、最初に `NOSPC` を返す直前に一度だけ、中身の [FileSystem] を渡して呼ぶ
 *   （空きの検査の後に、ほかのプロセスがファイルを縮めた・消したことにする）
 */
internal class TightFileSystem(private val inner: FileSystem = TestMemoryFileSystem()) : FileSystem by inner {
    @Volatile var free: Long = Long.MAX_VALUE

    @Volatile var stealFreed: Boolean = false

    @Volatile var failWriteNode: Long? = null

    @Volatile var failWriteAfter: Long = 0

    @Volatile var failStat: Errno? = null

    @Volatile var onNoSpace: ((FileSystem) -> Unit)? = null

    private fun sizeOf(node: Long): Long = inner.statNode(node).size

    /** 長さが [from] から [to] に変わる分を、空きから引く・空きに戻す */
    private fun account(from: Long, to: Long) {
        if (to > from) {
            if (to - from > free) {
                onNoSpace?.let {
                    onNoSpace = null
                    it(inner)
                }
                fsError(Errno.NOSPC)
            }
            free -= to - from
        } else if (!stealFreed) {
            free += from - to
        }
    }

    override fun stat(path: VfsPath): FileStat {
        failStat?.let { fsError(it) }
        return inner.stat(path)
    }

    @Synchronized
    override fun write(node: Long, position: Long, src: ByteArray, off: Int, len: Int): Int {
        var count = len
        if (node == failWriteNode) {
            if (position >= failWriteAfter) fsError(Errno.IO)
            count = minOf(len.toLong(), failWriteAfter - position).toInt()
        }
        val size = sizeOf(node)
        account(size, maxOf(size, position + count))
        return inner.write(node, position, src, off, count)
    }

    @Synchronized
    override fun setSize(node: Long, size: Long) {
        account(sizeOf(node), size)
        inner.setSize(node, size)
    }

    @Synchronized
    override fun append(node: Long, src: ByteArray, off: Int, len: Int): Written {
        val position = sizeOf(node)
        return Written(position, write(node, position, src, off, len))
    }

    @Synchronized
    override fun allocate(node: Long, size: Long) {
        if (sizeOf(node) < size) setSize(node, size)
    }

    @Synchronized
    override fun removeFile(path: VfsPath) {
        val size = inner.stat(path).size
        inner.removeFile(path)
        if (!stealFreed) free += size
    }

    @Synchronized
    override fun rename(from: VfsPath, to: VfsPath, requireDirectory: Boolean) {
        val replaced = try {
            inner.stat(to).takeIf { it.type == FileType.REGULAR_FILE }?.size ?: 0
        } catch (e: FsException) {
            0
        }
        inner.rename(from, to, requireDirectory)
        if (!stealFreed) free += replaced
    }
}
