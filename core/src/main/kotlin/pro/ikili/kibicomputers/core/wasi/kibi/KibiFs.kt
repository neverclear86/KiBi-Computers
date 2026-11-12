package pro.ikili.kibicomputers.core.wasi.kibi

import pro.ikili.kibicomputers.core.kernel.BootConfig
import pro.ikili.kibicomputers.core.kernel.DiskListing
import pro.ikili.kibicomputers.core.kernel.MachineLimits
import pro.ikili.kibicomputers.core.kernel.MountListing
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.FsUsage

/**
 * 独自拡張 `kibi:fs/mounts`（#56。ADR 0056）の意味。ABI の層（p1 の import、後の p2/p3）に依らない部分。
 *
 * - `mount`: [mount]（その台に今挿さっている `disk:<識別子>` だけ。相対パスはカーネルの cwd から）
 * - `unmount`: [unmount]
 * - `list-mounts`: [listMounts]（#453。ADR 0453）
 * - `list-disks`: [listDisks]（#453）
 *
 * 文字列の上限・UTF-8 の検査は ABI の層が行う（`source` の誤りは `invalid`、`point` が長すぎれば `name-too-long`）。
 */
internal object KibiFs {
    /** `source` の長さの上限（バイト。`point` と同じ） */
    const val MAX_SOURCE_BYTES: Int = 4096

    /** `list-mounts`・`list-disks` で、呼び出しごとの燃料に加えて1件ごとに引く燃料（仮の値。確定は #251） */
    const val MOUNT_LIST_FUEL_PER_ENTRY: Long = 10

    /** WIT の `fs-kind`。[ordinal] が判別子（`disk` 0・`image` 1・`tmp` 2・`dev` 3） */
    enum class Kind { DISK, IMAGE, TMP, DEV }

    /** `mount-info` 1つ（ABI に依らない形）。[usage] が `null` なら `none` */
    data class MountInfo(val point: String, val source: String, val kind: Kind, val readOnly: Boolean, val id: Int, val usage: FsUsage?)

    /** `disk-info` 1つ（ABI に依らない形）。[source] は `disk:<識別子>`、[mountPoint] が `null` なら `none` */
    data class DiskInfo(val source: String, val capacityBytes: Long, val maxEntries: Int, val mountPoint: String?)

    @Throws(FsException::class)
    fun mount(ctx: ProcessContext, source: String, point: String, readOnly: Boolean) {
        ctx.mount(source, point, readOnly)
    }

    @Throws(FsException::class)
    fun unmount(ctx: ProcessContext, point: String) {
        ctx.unmount(point)
    }

    /**
     * マウントの一覧（`list-mounts`。待たない）。並びはマウントを足した順。カーネルロックの中で表を写し、ロックの外で容量を読み
     * （`ctx.listMounts`）、件数 × [MOUNT_LIST_FUEL_PER_ENTRY] の燃料を引く
     */
    fun listMounts(ctx: ProcessContext): List<MountInfo> {
        val entries = ctx.listMounts().map { mountInfoOf(it) }
        ctx.chargeFuel(entries.size * MOUNT_LIST_FUEL_PER_ENTRY)
        return entries
    }

    /** 挿さっているディスクの一覧（`list-disks`。待たない）。並びはスロットの順。件数 × [MOUNT_LIST_FUEL_PER_ENTRY] の燃料を引く */
    fun listDisks(ctx: ProcessContext): List<DiskInfo> {
        val entries = ctx.listDisks().map { diskInfoOf(it) }
        ctx.chargeFuel(entries.size * MOUNT_LIST_FUEL_PER_ENTRY)
        return entries
    }

    /** カーネルの写し [m] を `mount-info` にする */
    fun mountInfoOf(m: MountListing): MountInfo = MountInfo(m.point, m.source, kindOf(m.source), m.readOnly, m.id, m.usage)

    /** カーネルの写し [d] を `disk-info` にする */
    fun diskInfoOf(d: DiskListing): DiskInfo =
        DiskInfo(MachineLimits.DISK_SOURCE_PREFIX + d.diskId, d.capacityBytes, d.maxEntries, d.mountPoint)

    /**
     * マウントの source から種類を決める（ADR 0453。ファイルシステムの型からは決めない。つながっていない項目にも同じ規則で決まる）。
     * `disk:image:` → [Kind.IMAGE]、ほかの `disk:` → [Kind.DISK]、`tmp` → [Kind.TMP]、`dev` → [Kind.DEV]。
     * 知らない source は [Kind.DISK] に倒す（例外を投げるとゲストがトラップするため）
     */
    fun kindOf(source: String): Kind = when {
        source.startsWith(IMAGE_SOURCE_PREFIX) -> Kind.IMAGE
        source.startsWith(MachineLimits.DISK_SOURCE_PREFIX) -> Kind.DISK
        source == MachineLimits.TMP_SOURCE -> Kind.TMP
        source == MachineLimits.DEV_SOURCE -> Kind.DEV
        else -> Kind.DISK
    }

    /** 同梱イメージのマウントの source の始まり（`disk:image:`） */
    private val IMAGE_SOURCE_PREFIX: String = MachineLimits.DISK_SOURCE_PREFIX + BootConfig.IMAGE_DISK_PREFIX
}
