package pro.ikili.kibicomputers.core.kernel.vfs

/**
 * 正規化した絶対パス（名前の並び）。`.`・`..`・空の名前を含まない。保存できる値（文字列の並び）だけを持つ。
 *
 * VFS 全体のパスにも、ファイルシステムの中のパス（そのファイルシステムの根からのパス）にも使う。
 *
 * シンボリックリンクは扱わない（ADR 0051）ので、`..` は名前の並びの上で取り除いてよい（辿る前に解いても結果が同じ）。
 */
internal class VfsPath private constructor(
    /** 根からの名前の並び。根なら空 */
    val segments: List<String>,
) {
    /** 根か */
    val isRoot: Boolean get() = segments.isEmpty()

    /** 最後の名前。根なら空文字列 */
    val name: String get() = segments.lastOrNull() ?: ""

    /** 親。根なら `null` */
    val parent: VfsPath? get() = if (isRoot) null else VfsPath(segments.subList(0, segments.size - 1).toList())

    /** 名前の数 */
    val depth: Int get() = segments.size

    /** 子の [name]。名前を確かめる（`/` や `..` は不可） */
    fun child(name: String): VfsPath {
        checkName(name)
        if (name == "." || name == "..") fsError(Errno.INVAL, "名前に . と .. は使えない")
        return VfsPath(segments + name).also { it.checkLength() }
    }

    /** [prefix] で始まる（同じパスを含む） */
    fun startsWith(prefix: VfsPath): Boolean =
        prefix.segments.size <= segments.size && segments.subList(0, prefix.segments.size) == prefix.segments

    /** [prefix] から見たパス（[prefix] で始まること） */
    fun relativeTo(prefix: VfsPath): VfsPath {
        require(startsWith(prefix)) { "$this は $prefix の下にない" }
        return if (prefix.isRoot) this else VfsPath(segments.subList(prefix.segments.size, segments.size).toList())
    }

    /** [other] をこのパスの下に付ける */
    fun append(other: VfsPath): VfsPath = if (other.isRoot) this else VfsPath(segments + other.segments).also { it.checkLength() }

    override fun equals(other: Any?): Boolean = other is VfsPath && other.segments == segments

    override fun hashCode(): Int = segments.hashCode()

    override fun toString(): String = if (isRoot) "/" else segments.joinToString("/", prefix = "/")

    private fun checkLength() {
        var bytes = 0
        for (s in segments) bytes += utf8Length(s) + 1
        if (bytes > MAX_PATH_BYTES) fsError(Errno.NAMETOOLONG, "パスが長すぎる")
    }

    companion object {
        /** 1つの名前の長さの上限（UTF-8 のバイト数。POSIX の NAME_MAX と同じ） */
        const val MAX_NAME_BYTES: Int = 255

        /** パス全体の長さの上限（UTF-8 のバイト数。Linux の PATH_MAX と同じ） */
        const val MAX_PATH_BYTES: Int = 4096

        /** 根 */
        val ROOT: VfsPath = VfsPath(emptyList())

        /** 絶対パス（`/` で始まらなくても根から）を正規化する */
        fun parse(text: String): VfsPath = resolve(ROOT, text)

        /**
         * [text] を [base] から解く。`/` で始まれば根から。`.` と空の名前は飛ばし、`..` は1つ上へ（根の上は根）。
         *
         * 空文字列は `NOENT`、長すぎる名前・パスは `NAMETOOLONG`、NUL を含めば `INVAL`、対になっていないサロゲートは `ILSEQ`。
         */
        fun resolve(base: VfsPath, text: String): VfsPath = walk(base, text, beneath = false)

        /**
         * [text] を [base] から解くが、[base] の外へは出さない（WASI の、ディレクトリの fd から辿るときの規則）。
         * 絶対パスや、`..` で [base] より上へ出るパスは `NOTCAPABLE`（途中で出て戻ってくるものも不可）。
         */
        fun resolveBeneath(base: VfsPath, text: String): VfsPath = walk(base, text, beneath = true)

        /** [text] が `/` で終わる（`/` だけのときを除く）。終わればディレクトリでなければならない */
        fun hasTrailingSlash(text: String): Boolean = text.length > 1 && text.endsWith('/')

        private fun walk(base: VfsPath, text: String, beneath: Boolean): VfsPath {
            if (text.isEmpty()) fsError(Errno.NOENT, "空のパス")
            if (utf8Length(text) > MAX_PATH_BYTES) fsError(Errno.NAMETOOLONG, "パスが長すぎる")
            val absolute = text.startsWith('/')
            if (beneath && absolute) fsError(Errno.NOTCAPABLE, "絶対パスは使えない")
            val stack = ArrayList<String>(if (absolute) 8 else base.segments.size + 8)
            if (!absolute) stack.addAll(base.segments)
            val floor = if (beneath) base.segments.size else 0
            for (part in text.split('/')) {
                when (part) {
                    "", "." -> continue
                    ".." -> {
                        if (stack.size <= floor) {
                            if (beneath) fsError(Errno.NOTCAPABLE, "$base の外へ出るパス")
                            continue
                        }
                        stack.removeAt(stack.size - 1)
                    }
                    else -> {
                        checkName(part)
                        stack.add(part)
                    }
                }
            }
            return VfsPath(stack).also { it.checkLength() }
        }

        private fun checkName(name: String) {
            if (name.isEmpty()) fsError(Errno.INVAL, "空の名前")
            for (i in name.indices) {
                val c = name[i]
                when {
                    c == '\u0000' -> fsError(Errno.INVAL, "名前に NUL がある")
                    c == '/' -> fsError(Errno.INVAL, "名前に / がある")
                    c.isHighSurrogate() -> if (i + 1 >= name.length || !name[i + 1].isLowSurrogate()) fsError(Errno.ILSEQ)
                    c.isLowSurrogate() -> if (i == 0 || !name[i - 1].isHighSurrogate()) fsError(Errno.ILSEQ)
                }
            }
            if (utf8Length(name) > MAX_NAME_BYTES) fsError(Errno.NAMETOOLONG, "名前が長すぎる")
        }

        /** UTF-8 にしたときのバイト数（サロゲートの対は 4 バイト） */
        private fun utf8Length(s: String): Int {
            var n = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                n += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> {
                        i++
                        4
                    }
                    else -> 3
                }
                i++
            }
            return n
        }
    }
}
