package pro.ikili.kibicomputers.core.kernel.vfs

import pro.ikili.kibicomputers.core.kernel.MachineLimits
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/** ディスク（[DiskFileSystem]）の割り当て（仮の値。確定は #251） */
internal data class DiskQuota(
    /** 使える量（バイト。中身の大きさ＋項目ごとの [DiskFileSystem.ENTRY_COST]） */
    val maxBytes: Long = MachineLimits.DISK_CAPACITY_BYTES,
    /** ファイル・ディレクトリの数の上限（根を除く） */
    val maxEntries: Int = MachineLimits.DISK_MAX_ENTRIES,
)

/**
 * 開いたままのホストのファイルの数の枠（#52）。マシンごとに1つ作り（[perMachine]）、サーバー全体の数（[total]）と一緒に数える。
 * 台数に比例して開いたままのファイルが増えないようにする（待っているだけの台は、fd を開いていなければ何も持たない）
 */
internal class HostFileBudget(
    private val perMachine: Int = MachineLimits.HOST_FILES_PER_MACHINE,
    private val totalLimit: Int = MachineLimits.HOST_FILES_TOTAL,
    private val total: AtomicInteger = SHARED_TOTAL,
) {
    private val used = AtomicInteger()

    /** このマシンが開いている数 */
    val inUse: Int get() = used.get()

    /** 1つ取る。マシンの枠を超えたら `MFILE`、サーバー全体の枠を超えたら `NFILE` */
    fun acquire() {
        if (used.incrementAndGet() > perMachine) {
            used.decrementAndGet()
            fsError(Errno.MFILE, "開いたままのホストのファイルが、マシンの上限（$perMachine）")
        }
        if (total.incrementAndGet() > totalLimit) {
            total.decrementAndGet()
            used.decrementAndGet()
            fsError(Errno.NFILE, "開いたままのホストのファイルが、サーバー全体の上限（$totalLimit）")
        }
    }

    /** 1つ返す */
    fun release() {
        used.decrementAndGet()
        total.decrementAndGet()
    }

    companion object {
        /** サーバー全体（JVM の中）で開いている数 */
        val SHARED_TOTAL: AtomicInteger = AtomicInteger()
    }
}

/**
 * ホストのディレクトリごとの持ち主の世代（リース。#52）。同じディレクトリを新しく開いた [DiskFileSystem] が世代を進め、
 * 古い世代のものの操作は `IO` で断る（電源を切ってすぐ入れたとき、ディスクを別の台へ挿し替えたときに、
 * 止まり切っていない古いカーネルと新しいカーネルが同じディスクを触らないように）。
 *
 * 1つのディスクの I/O は [Lease.lock] で1本ずつ行う（待つのは、同じディスクを使うスレッドどうしだけ）。
 */
internal object HostDiskLeases {
    class Lease internal constructor(val root: Path) {
        /** このディスクの I/O の排他（世代をまたいで共有する） */
        val lock: ReentrantLock = ReentrantLock()

        /** 今の持ち主の世代（[lock] の中で読み書きする） */
        var generation: Long = 0
            internal set

        internal var users: Int = 0
    }

    private val leases = ConcurrentHashMap<Path, Lease>()

    /** [root]（`toRealPath` したもの）のリースを取り、新しい世代を返す */
    fun acquire(root: Path): Pair<Lease, Long> {
        val lease = leases.compute(root) { _, existing -> (existing ?: Lease(root)).also { it.users++ } }!!
        lease.lock.lock()
        try {
            lease.generation++
            return lease to lease.generation
        } finally {
            lease.lock.unlock()
        }
    }

    /** [acquire] の分を返す。使う者がいなくなったら表から外す */
    fun release(lease: Lease) {
        leases.computeIfPresent(lease.root) { _, l -> if (--l.users <= 0) null else l }
    }

    /** 表にあるリースの数（テスト用） */
    val size: Int get() = leases.size
}
