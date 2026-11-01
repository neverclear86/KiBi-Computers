package pro.ikili.kibicomputers.core.kernel.vfs

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/**
 * 読み取り専用のイメージ（#55）。MOD の jar に同梱した zip を読み、中身をメモリに持つ（OS 入りフロッピー、同梱プログラムなど）。
 *
 * - 作った後は変わらない。ロックなしで、いくつのマシン・スレッドから同時に使ってもよい（同じイメージは [ImageFileSystem.bundled] で1つを共有する）
 * - 書く操作はすべて `ROFS`
 * - node はパスから決める（パスの UTF-8 の 64 ビット FNV-1a。0 と負は避ける）。MOD を更新してイメージが変わっても、
 *   同じパスのファイルは同じ node のままなので、保存した開いたファイルは同じファイルを指す。消えたパスの node は `STALE`
 * - 時刻は持たない（すべて 0。zip の時刻はビルドの環境で変わるので使わない）
 */
internal class ImageFileSystem private constructor(
    private val byPath: Map<VfsPath, Entry>,
    private val byNode: Map<Long, Entry>,
) : FileSystem {
    private class Entry(val path: VfsPath, val node: Long, val type: FileType, val data: ByteArray?) {
        /** ディレクトリの中身（名前の順）。作るときに埋める */
        var children: List<DirEntry> = emptyList()

        val stat: FileStat = FileStat(node, type, data?.size?.toLong() ?: 0)
    }

    override val readOnly: Boolean get() = true

    /** ファイルとディレクトリの数（根を含む） */
    val entryCount: Int get() = byPath.size

    /** 普通のファイルの中身の合計（バイト） */
    val totalBytes: Long get() = byNode.values.sumOf { it.data?.size?.toLong() ?: 0 }

    override fun stat(path: VfsPath): FileStat = find(path).stat

    override fun statNode(node: Long): FileStat = entry(node).stat

    override fun createFile(path: VfsPath): Long = fsError(Errno.ROFS)

    override fun createDirectory(path: VfsPath): Unit = fsError(Errno.ROFS)

    override fun removeFile(path: VfsPath): Unit = fsError(Errno.ROFS)

    override fun removeDirectory(path: VfsPath): Unit = fsError(Errno.ROFS)

    override fun rename(from: VfsPath, to: VfsPath): Unit = fsError(Errno.ROFS)

    override fun read(node: Long, position: Long, dst: ByteArray, off: Int, len: Int): Int {
        val e = entry(node)
        val data = e.data ?: fsError(Errno.ISDIR)
        if (position < 0) fsError(Errno.INVAL)
        if (position >= data.size || len <= 0) return 0
        val count = minOf(len.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), dst, off, count)
        return count
    }

    override fun write(node: Long, position: Long, src: ByteArray, off: Int, len: Int): Int = fsError(Errno.ROFS)

    override fun setSize(node: Long, size: Long): Unit = fsError(Errno.ROFS)

    override fun readDirectory(node: Long): List<DirEntry> {
        val e = entry(node)
        if (e.type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
        return e.children
    }

    override fun setTimes(node: Long, accessedNanos: Long?, modifiedNanos: Long?): Unit = fsError(Errno.ROFS)

    private fun entry(node: Long): Entry = byNode[node] ?: fsError(Errno.STALE)

    private fun find(path: VfsPath): Entry {
        byPath[path]?.let { return it }
        // 途中にファイルがあれば NOTDIR、なければ NOENT
        var cur = VfsPath.ROOT
        for (name in path.segments) {
            val e = byPath[cur] ?: fsError(Errno.NOENT)
            if (e.type != FileType.DIRECTORY) fsError(Errno.NOTDIR)
            cur = cur.child(name)
        }
        fsError(Errno.NOENT)
    }

    companion object {
        /** 同梱イメージを置くリソースの場所（`/kibicomputers/images/<名前>.zip`） */
        const val RESOURCE_DIR: String = "/kibicomputers/images"

        /** zip の項目の数の上限（壊れたイメージで止まらないため） */
        const val MAX_ENTRIES: Int = 65_536

        /** 中身の合計の上限（バイト） */
        const val MAX_TOTAL_BYTES: Long = 256L * 1024 * 1024

        private val bundledCache = ConcurrentHashMap<String, ImageFileSystem>()

        /**
         * 同梱イメージ [name]（リソース `/kibicomputers/images/<name>.zip`）。初めて使うときに読み、後は同じものを返す。
         * なければ `null`。読めない・壊れているときは [IOException]・[IllegalArgumentException]
         */
        @Throws(IOException::class)
        fun bundled(name: String): ImageFileSystem? {
            require(name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' } && !name.startsWith('.')) {
                "イメージの名前が不正: $name"
            }
            bundledCache[name]?.let { return it }
            val resource = "$RESOURCE_DIR/$name.zip"
            val fs = fromResource(resource, ImageFileSystem::class.java.classLoader) ?: return null
            return bundledCache.putIfAbsent(name, fs) ?: fs
        }

        /** クラスローダー [loader] のリソース [resource]（`/` で始まる）の zip を読む。なければ `null` */
        @Throws(IOException::class)
        fun fromResource(resource: String, loader: ClassLoader): ImageFileSystem? {
            val stream = loader.getResourceAsStream(resource.removePrefix("/")) ?: return null
            return stream.use { fromZip(it) }
        }

        /**
         * zip [input] を読む（閉じない）。項目の名前は `/` 区切りのパス。親のディレクトリは項目がなくても作る。
         * `..` を含む名前、同じパスの重複、ファイルの下の項目は [IllegalArgumentException]
         */
        @Throws(IOException::class)
        fun fromZip(input: InputStream): ImageFileSystem {
            val files = LinkedHashMap<VfsPath, ByteArray?>()
            files[VfsPath.ROOT] = null
            var total = 0L
            val zip = ZipInputStream(input)
            val buf = ByteArray(8192)
            while (true) {
                val ze = zip.nextEntry ?: break
                require(files.size < MAX_ENTRIES) { "項目が多すぎる（上限 $MAX_ENTRIES）" }
                val path = parseName(ze.name)
                if (ze.isDirectory) {
                    val prev = files[path]
                    require(!files.containsKey(path) || (prev == null && path.isRoot)) { "重複した項目: ${ze.name}" }
                    files[path] = null
                } else {
                    require(!path.isRoot && !files.containsKey(path)) { "重複した項目: ${ze.name}" }
                    val out = ByteArrayOutputStream()
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        total += n
                        require(total <= MAX_TOTAL_BYTES) { "中身が大きすぎる（上限 $MAX_TOTAL_BYTES バイト）" }
                        out.write(buf, 0, n)
                    }
                    files[path] = out.toByteArray()
                }
            }
            return build(files)
        }

        /** zip の項目の名前をパスにする。`\` は使わない前提（zip の規約どおり `/` 区切り） */
        private fun parseName(name: String): VfsPath {
            require('\\' !in name) { "項目の名前に \\ がある: $name" }
            require(name.split('/').none { it == ".." }) { "項目の名前に .. がある: $name" }
            return try {
                VfsPath.parse(name)
            } catch (e: FsException) {
                throw IllegalArgumentException("項目の名前が不正（${e.errno}）: $name", e)
            }
        }

        private fun build(files: Map<VfsPath, ByteArray?>): ImageFileSystem {
            // 親のディレクトリを補う
            val all = LinkedHashMap<VfsPath, ByteArray?>(files)
            for (path in files.keys) {
                var parent = path.parent
                while (parent != null) {
                    if (all.containsKey(parent)) {
                        require(all[parent] == null) { "ファイル $parent の下に項目がある" }
                    } else {
                        all[parent] = null
                    }
                    parent = parent.parent
                }
            }
            val byPath = HashMap<VfsPath, Entry>(all.size * 2)
            val byNode = HashMap<Long, Entry>(all.size * 2)
            for ((path, data) in all) {
                val node = nodeOf(path)
                val e = Entry(path, node, if (data == null) FileType.DIRECTORY else FileType.REGULAR_FILE, data)
                val clash = byNode.put(node, e)
                check(clash == null) { "node が衝突した: ${clash!!.path} と $path" }
                byPath[path] = e
            }
            val children = HashMap<VfsPath, MutableList<DirEntry>>()
            for (e in byPath.values) {
                val parent = e.path.parent ?: continue
                children.getOrPut(parent) { ArrayList() }.add(DirEntry(e.path.name, e.type, e.node))
            }
            for ((parent, list) in children) {
                list.sortBy { it.name }
                byPath.getValue(parent).children = java.util.Collections.unmodifiableList(list)
            }
            return ImageFileSystem(byPath, byNode)
        }

        /** パスの node（`/a/b` の UTF-8 の FNV-1a 64 ビット。正の値にし、0 は 1 にする） */
        internal fun nodeOf(path: VfsPath): Long {
            var h = -0x340d631b7bdddcdbL // 14695981039346656037
            for (b in path.toString().toByteArray(Charsets.UTF_8)) {
                h = h xor (b.toLong() and 0xff)
                h *= 0x100000001b3L
            }
            val v = h and Long.MAX_VALUE
            return if (v == 0L) 1L else v
        }
    }
}
