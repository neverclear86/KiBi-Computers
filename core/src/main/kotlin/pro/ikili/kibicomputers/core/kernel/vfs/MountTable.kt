package pro.ikili.kibicomputers.core.kernel.vfs

/**
 * マウントの1つ（保存の対象）。ファイルシステムそのものは実行時だけのものなので持たない（[Vfs] が ID で引く）。
 */
internal class MountEntry(
    /** マウントの ID（1 から。使い回さない） */
    val id: Int,
    /** マウントした場所（VFS の絶対パス。指定された綴りのまま） */
    val point: VfsPath,
    /** 読み取り専用でマウントしたか */
    val readOnly: Boolean,
    /**
     * 何をマウントしたか（`disk:<識別子>`、`tmp` など。中身はマウントする側が決める）。
     * 復元のときに、ファイルシステムを作り直す手がかりにする（#56・#82）
     */
    val source: String,
)

/**
 * マウントの表（保存の対象）。マシンのカーネルロックの中で使う（ロックの外からは [Vfs] が公開する写しを読む）。
 *
 * パスは、いちばん長く一致するマウントの位置で、どのマウントに属するかを決める。
 * 1つの位置に重ねてマウントはしない（`BUSY`）。
 *
 * ## 大文字小文字（#606。ADR 0051）
 *
 * マウントの位置とパスの「一致」は、名前を1つずつ、**その名前を引くディレクトリが属する FS の規則**で比べる。
 * 大文字小文字を区別しない FS（ディスク。[FileSystem.ignoresCase]）のディレクトリの中の名前は [DiskNames.sameName] で、
 * それ以外（`/tmp` など。どの FS にも属さない合成のディレクトリも）は `==` で比べる。
 * 根がディスクなら `/TMP/x` は `/tmp` のマウントに属し、`/tmp`（tmpfs）の下の `/tmp/A` は `/tmp/a` のマウントに一致しない。
 *
 * そのために、マウントごとに「位置の i 番目の名前を区別せずに比べるか」の印（[folds]）を、表を変えるたびに作り直す
 * （パスの操作のたびには作らない）。FS が区別しないかは実行時だけの値（保存しない）で、[add]・[setIgnoresCase] で知らせる。
 * 知らせのないマウント（復元してまだ FS をつないでいないもの）は、区別するとして扱う。
 *
 * 古い表（#606 より前は区別して比べていた）では、大文字小文字だけ違う位置に重ねてマウントしていることがある。
 * そのときも例外にせず、深いほう、同じ深さなら足した順で先のものを選ぶ。
 */
internal class MountTable {
    private val list = ArrayList<MountEntry>(4)

    /** 大文字小文字を区別しない FS をつないだマウントの ID（実行時だけ。保存しない） */
    private val foldingIds = HashSet<Int>(4)

    /**
     * [list] と同じ並びの、位置の名前ごとの比べ方の印（`folds[k][i]` が真なら、`list[k]` の位置の i 番目の名前を区別せずに比べる）。
     * 作り直すときは配列ごと新しくする（写しと分け合うので、作った配列は変えない）
     */
    private var folds: Array<BooleanArray> = emptyArray()

    /** 次に割り当てる ID */
    var nextMountId: Int = 1
        private set

    /** マウントの並び（足した順） */
    val entries: List<MountEntry> get() = list

    /**
     * [point] にマウントを足す。もうマウントがあれば（大文字小文字の規則も含めて同じ位置。#606）`BUSY`。
     * [ignoresCase] は、マウントする FS が大文字小文字を区別しないか（[FileSystem.ignoresCase]）
     */
    fun add(point: VfsPath, readOnly: Boolean, source: String, ignoresCase: Boolean = false): MountEntry {
        if (at(point) != null) fsError(Errno.BUSY, "$point にはもうマウントがある")
        var id = nextMountId
        while (id <= 0 || get(id) != null) id = if (id == Int.MAX_VALUE || id <= 0) 1 else id + 1
        nextMountId = if (id == Int.MAX_VALUE) 1 else id + 1
        val entry = MountEntry(id, point, readOnly, source)
        list.add(entry)
        if (ignoresCase) foldingIds.add(id)
        recompute()
        return entry
    }

    /**
     * [id] のマウントを外す。なければ `INVAL`、下（より深いところ）に別のマウントがあれば `BUSY`。
     * 古い表で同じ位置に重なったマウント（#606）は下とみなさない（どちらからでも外せる）
     */
    fun remove(id: Int): MountEntry {
        val entry = get(id) ?: fsError(Errno.INVAL, "マウント $id はない")
        if (list.any { it.point.depth > entry.point.depth && isAtOrBelow(it, entry) }) fsError(Errno.BUSY, "${entry.point} の下にマウントがある")
        drop(entry)
        return entry
    }

    /**
     * [id] のマウントを、下のマウントを見ずに外す（ディスクを抜いたとき。#56）。なければ `INVAL`。
     * 下のマウントは残る（祖先は合成のディレクトリとして見える。ADR 0051。比べ方の印も作り直す）
     */
    fun removeForced(id: Int): MountEntry {
        val entry = get(id) ?: fsError(Errno.INVAL, "マウント $id はない")
        drop(entry)
        return entry
    }

    /** マウント [id] の FS が大文字小文字を区別しないかを知らせる（復元した表に FS をつないだとき。#606）。なければ何もしない */
    fun setIgnoresCase(id: Int, value: Boolean) {
        if (get(id) == null) return
        val changed = if (value) foldingIds.add(id) else foldingIds.remove(id)
        if (changed) recompute()
    }

    /** [source] をマウントしたもの（足した順で最初のもの）。なければ `null`（#56） */
    fun bySource(source: String): MountEntry? = list.firstOrNull { it.source == source }

    /** 今の中身の写し（[Vfs] が、カーネルロックの外から読むために公開する。写しは変えない） */
    fun snapshot(): MountTable = MountTable().also {
        it.list.addAll(list)
        it.foldingIds.addAll(foldingIds)
        // 印の配列は作った後に変えないので、分け合ってよい（外側の配列は新しくする）
        it.folds = folds.copyOf()
        it.nextMountId = nextMountId
    }

    /** 保存データから読んだ値を入れる（#82。空の表に）。FS はまだつながっていないので、大文字小文字は区別するとして扱う */
    fun restore(entries: List<MountEntry>, nextMountId: Int) {
        check(list.isEmpty()) { "空の表にだけ戻す" }
        list.addAll(entries)
        this.nextMountId = nextMountId
        recompute()
    }

    operator fun get(id: Int): MountEntry? = list.firstOrNull { it.id == id }

    /** [point] にちょうどあるマウント（大文字小文字の規則も含めて同じ位置。重なっていれば足した順で先のもの） */
    fun at(point: VfsPath): MountEntry? {
        for (k in list.indices) {
            if (list[k].point.depth == point.depth && matches(k, point, point.depth)) return list[k]
        }
        return null
    }

    /** [path] が属するマウント（いちばん長く一致する位置のもの。同じ深さなら足した順で先のもの）。どこにも属さなければ `null` */
    fun covering(path: VfsPath): MountEntry? {
        var best: MountEntry? = null
        for (k in list.indices) {
            val e = list[k]
            val depth = e.point.depth
            if (depth <= path.depth && (best == null || depth > best.point.depth) && matches(k, path, depth)) best = e
        }
        return best
    }

    /** [path] の真下より深いところにマウントがある（[path] がマウントの位置の祖先） */
    fun hasMountBelow(path: VfsPath): Boolean = list.indices.any { k -> list[k].point.depth > path.depth && matches(k, path, path.depth) }

    /** [path] か、その下にマウントがある */
    fun hasMountAtOrBelow(path: VfsPath): Boolean = list.indices.any { k -> list[k].point.depth >= path.depth && matches(k, path, path.depth) }

    /** [entry] が [base] の位置か、その下にあるか（大文字小文字の規則も含めて。[entry] 自身と、古い表で同じ位置に重なったものも真。#606） */
    fun isAtOrBelow(entry: MountEntry, base: MountEntry): Boolean {
        val k = list.indexOf(entry)
        if (k < 0 || entry.point.depth < base.point.depth) return false
        return matches(k, base.point, base.point.depth)
    }

    /**
     * [path] の下のマウントの位置へ向かう、[path] の直下の名前（マウントの位置そのものと、その祖先）。綴りはマウントの位置のまま。
     * [path] が大文字小文字を区別しないディレクトリなら、そろえると同じ名前は1つにする（足した順で先のマウントの綴り）
     */
    fun childNamesTowardMounts(path: VfsPath): Set<String> {
        val result = sortedSetOf<String>()
        val fold = ignoresCaseIn(path)
        for (k in list.indices) {
            val e = list[k]
            if (e.point.depth > path.depth && matches(k, path, path.depth)) {
                val name = e.point.segments[path.depth]
                if (fold && result.any { DiskNames.sameName(it, name) }) continue
                result.add(name)
            }
        }
        return result
    }

    /**
     * ディレクトリ [dir] の中の名前を、大文字小文字を区別せずに比べるか
     * （[dir] が属するマウントの FS で決まる。どこにも属さない合成のディレクトリは区別する）
     */
    fun ignoresCaseIn(dir: VfsPath): Boolean {
        val m = covering(dir) ?: return false
        return m.id in foldingIds
    }

    // ---- 中身 ----

    private fun drop(entry: MountEntry) {
        list.remove(entry)
        foldingIds.remove(entry.id)
        recompute()
    }

    /** `list[k]` の位置と [path] の、先頭 [n] 個の名前が一致するか（[n] は両方の深さ以下） */
    private fun matches(k: Int, path: VfsPath, n: Int): Boolean = matches(list[k].point, folds[k], path, n)

    /**
     * 印を作り直す（表を変えたとき。マウントは数個なので、素直に探す）。
     * `list[k]` の位置の i 番目の名前を引くディレクトリ（位置の先頭 i 個の名前）が属するのは、深さ i 以下のマウントのうち
     * いちばん深く一致するもの。浅いマウントから順に印を決めれば、そのマウントの印はもう決まっている
     */
    private fun recompute() {
        val done = arrayOfNulls<BooleanArray>(list.size)
        for (k in list.indices.sortedBy { list[it].point.depth }) {
            val point = list[k].point
            val fold = BooleanArray(point.depth)
            for (i in 0 until point.depth) {
                var best = -1
                for (j in list.indices) {
                    val d = list[j].point.depth
                    val known = done[j] ?: continue
                    // 同じ深さなら足した順で先のもの（covering と同じ）
                    if (d > i || (best >= 0 && d <= list[best].point.depth)) continue
                    if (matches(list[j].point, known, point, d)) best = j
                }
                fold[i] = best >= 0 && list[best].id in foldingIds
            }
            done[k] = fold
        }
        folds = Array(list.size) { checkNotNull(done[it]) }
    }

    private companion object {
        /** [point] と [path] の先頭 [n] 個の名前を、[fold] の印（[point] の名前ごと）で比べる。文字列を作らない */
        fun matches(point: VfsPath, fold: BooleanArray, path: VfsPath, n: Int): Boolean {
            val a = point.segments
            val b = path.segments
            for (i in 0 until n) {
                val x = a[i]
                val y = b[i]
                if (x == y) continue
                if (!fold[i] || !DiskNames.sameName(x, y)) return false
            }
            return true
        }
    }
}
