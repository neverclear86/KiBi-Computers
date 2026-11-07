package pro.ikili.kibicomputers.core.kernel.vfs

/**
 * デバイスファイルを出すファイルシステム（devfs 相当。#485。`/dev` にマウントする）。中身は決まった3つのキャラクターデバイスだけで、
 * 状態を持たない（RAM にも数えない。保存するものもない）。
 *
 * - `null`: 読むと終わり（0 バイト）、書くと全部を捨てる
 * - `zero`: 読むと 0 のバイトで埋まる、書くと全部を捨てる
 * - `tty`: そのプロセスの制御端末。開くと、fd はこのファイルではなく端末の `Tty` を指す（[isTerminal]。差し替えは fd の表に入れるところ、
 *   `FdTable.openFile` で行う）。ここを通して読み書きしたら `NXIO`
 *
 * どれも大きさは 0。`O_TRUNC`（[setSize]）・時刻の変更（[setTimes]）は受け付けて何もしない（Linux の `/dev/null` と同じく、
 * `> /dev/null` や `touch /dev/null` が通るように）。ファイルを作る・消す・名前を変えるのは `ACCES`（中身は決まっている）。
 * どの操作も I/O をしないので、どのスレッドから同時に呼んでもよく、排他も要らない。
 */
internal class DevFileSystem : FileSystem {
    override fun stat(path: VfsPath): FileStat {
        if (path.isRoot) return ROOT_STAT
        if (path.depth != 1) {
            // `/dev/null/x` は途中がディレクトリでない
            if (byName(path.segments[0]) != null) fsError(Errno.NOTDIR)
            fsError(Errno.NOENT)
        }
        val node = byName(path.name) ?: fsError(Errno.NOENT)
        return deviceStat(node)
    }

    override fun statNode(node: Long): FileStat = when (node) {
        ROOT_NODE -> ROOT_STAT
        NULL_NODE, ZERO_NODE, TTY_NODE -> deviceStat(node)
        else -> fsError(Errno.STALE)
    }

    override fun createFile(path: VfsPath): Long = fsError(Errno.ACCES, "/dev には作れない")

    override fun createDirectory(path: VfsPath) {
        fsError(Errno.ACCES, "/dev には作れない")
    }

    override fun removeFile(path: VfsPath) {
        stat(path)
        fsError(Errno.ACCES, "/dev のファイルは消せない")
    }

    override fun removeDirectory(path: VfsPath) {
        if (path.isRoot) fsError(Errno.BUSY)
        stat(path)
        fsError(Errno.NOTDIR)
    }

    override fun rename(from: VfsPath, to: VfsPath, requireDirectory: Boolean) {
        stat(from)
        fsError(Errno.ACCES, "/dev のファイルの名前は変えられない")
    }

    override fun read(node: Long, position: Long, dst: ByteArray, off: Int, len: Int): Int = when (device(node)) {
        NULL_NODE -> 0
        ZERO_NODE -> {
            dst.fill(0, off, off + len)
            len
        }
        else -> fsError(Errno.NXIO, "/dev/tty は端末として開く")
    }

    override fun write(node: Long, position: Long, src: ByteArray, off: Int, len: Int): Int = when (device(node)) {
        NULL_NODE, ZERO_NODE -> len
        else -> fsError(Errno.NXIO, "/dev/tty は端末として開く")
    }

    override fun setSize(node: Long, size: Long) {
        device(node)
        if (size < 0) fsError(Errno.INVAL)
    }

    override fun append(node: Long, src: ByteArray, off: Int, len: Int): Written = Written(0, write(node, 0, src, off, len))

    override fun allocate(node: Long, size: Long) {
        device(node)
    }

    override fun sizeHint(node: Long): Long = 0

    override fun readDirectory(node: Long): List<DirEntry> {
        if (node != ROOT_NODE) {
            statNode(node)
            fsError(Errno.NOTDIR)
        }
        return ENTRIES
    }

    override fun setTimes(node: Long, accessedNanos: Long?, modifiedNanos: Long?) {
        statNode(node)
    }

    /** [node] が制御端末（`/dev/tty`）か。カーネルロックの中から呼ばれる（I/O をしない） */
    override fun isTerminal(node: Long): Boolean = node == TTY_NODE

    /** デバイスの node（根なら `ISDIR`、知らなければ `STALE`） */
    private fun device(node: Long): Long = when (node) {
        NULL_NODE, ZERO_NODE, TTY_NODE -> node
        ROOT_NODE -> fsError(Errno.ISDIR)
        else -> fsError(Errno.STALE)
    }

    private fun byName(name: String): Long? = ENTRIES.firstOrNull { it.name == name }?.node

    private fun deviceStat(node: Long): FileStat = FileStat(node, FileType.CHARACTER_DEVICE)

    companion object {
        /** 根の node */
        const val ROOT_NODE: Long = 1

        /** `/dev/null` の node */
        const val NULL_NODE: Long = 2

        /** `/dev/zero` の node */
        const val ZERO_NODE: Long = 3

        /** `/dev/tty` の node */
        const val TTY_NODE: Long = 4

        private val ROOT_STAT = FileStat(ROOT_NODE, FileType.DIRECTORY)

        /** 根の中身（名前の順） */
        private val ENTRIES = listOf(
            DirEntry("null", FileType.CHARACTER_DEVICE, NULL_NODE),
            DirEntry("tty", FileType.CHARACTER_DEVICE, TTY_NODE),
            DirEntry("zero", FileType.CHARACTER_DEVICE, ZERO_NODE),
        )
    }
}
