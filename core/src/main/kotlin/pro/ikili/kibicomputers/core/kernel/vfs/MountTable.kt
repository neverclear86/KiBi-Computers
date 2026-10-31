package pro.ikili.kibicomputers.core.kernel.vfs

/**
 * マウントの1つ（保存の対象）。ファイルシステムそのものは実行時だけのものなので持たない（[Vfs] が ID で引く）。
 */
internal class MountEntry(
    /** マウントの ID（1 から。使い回さない） */
    val id: Int,
    /** マウントした場所（VFS の絶対パス） */
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
 * マウントの表（保存の対象）。マシンのカーネルロックの中で使う。
 *
 * パスは、いちばん長く一致するマウントの位置で、どのマウントに属するかを決める。
 * 1つの位置に重ねてマウントはしない（`BUSY`）。
 */
internal class MountTable {
    private val list = ArrayList<MountEntry>(4)

    /** 次に割り当てる ID */
    var nextMountId: Int = 1
        private set

    /** マウントの並び（足した順） */
    val entries: List<MountEntry> get() = list

    /** [point] にマウントを足す。もうマウントがあれば `BUSY` */
    fun add(point: VfsPath, readOnly: Boolean, source: String): MountEntry {
        if (at(point) != null) fsError(Errno.BUSY, "$point にはもうマウントがある")
        var id = nextMountId
        while (id <= 0 || get(id) != null) id = if (id == Int.MAX_VALUE || id <= 0) 1 else id + 1
        nextMountId = if (id == Int.MAX_VALUE) 1 else id + 1
        val entry = MountEntry(id, point, readOnly, source)
        list.add(entry)
        return entry
    }

    /** [id] のマウントを外す。なければ `INVAL`、下に別のマウントがあれば `BUSY` */
    fun remove(id: Int): MountEntry {
        val entry = get(id) ?: fsError(Errno.INVAL, "マウント $id はない")
        if (list.any { it !== entry && it.point.startsWith(entry.point) }) fsError(Errno.BUSY, "${entry.point} の下にマウントがある")
        list.remove(entry)
        return entry
    }

    operator fun get(id: Int): MountEntry? = list.firstOrNull { it.id == id }

    /** [point] にちょうどあるマウント */
    fun at(point: VfsPath): MountEntry? = list.firstOrNull { it.point == point }

    /** [path] が属するマウント（いちばん長く一致する位置のもの）。どこにも属さなければ `null` */
    fun covering(path: VfsPath): MountEntry? {
        var best: MountEntry? = null
        for (e in list) {
            if (path.startsWith(e.point) && (best == null || e.point.depth > best.point.depth)) best = e
        }
        return best
    }

    /** [path] の真下より深いところにマウントがある（[path] がマウントの位置の祖先） */
    fun hasMountBelow(path: VfsPath): Boolean = list.any { it.point.depth > path.depth && it.point.startsWith(path) }

    /** [path] か、その下にマウントがある */
    fun hasMountAtOrBelow(path: VfsPath): Boolean = list.any { it.point.startsWith(path) }

    /** [path] の下のマウントの位置へ向かう、[path] の直下の名前（マウントの位置そのものと、その祖先） */
    fun childNamesTowardMounts(path: VfsPath): Set<String> {
        val result = sortedSetOf<String>()
        for (e in list) {
            if (e.point.depth > path.depth && e.point.startsWith(path)) result.add(e.point.segments[path.depth])
        }
        return result
    }
}
