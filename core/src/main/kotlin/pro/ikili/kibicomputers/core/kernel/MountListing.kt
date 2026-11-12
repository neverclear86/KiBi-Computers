package pro.ikili.kibicomputers.core.kernel

import pro.ikili.kibicomputers.core.kernel.vfs.FsUsage

/**
 * マウント1つの写し（`Machine.listMounts`。`kibi:fs/mounts` の `list-mounts`。#453、ADR 0453）。外では変わらない。
 *
 * @property id マウントの ID（`MountEntry.id`。p1 の `filestat.dev` と同じ値）
 * @property point マウントの位置（VFS の絶対パス）
 * @property source 何をマウントしたか（`disk:<識別子>`・`tmp`・`dev`）
 * @property readOnly 読み取り専用でマウントしたか、ファイルシステムが読み取り専用か
 * @property usage 容量と使用量。ファイルシステムがまだつながっていない・分からない・写した後に外れたなら `null`
 */
internal data class MountListing(
    val id: Int,
    val point: String,
    val source: String,
    val readOnly: Boolean,
    val usage: FsUsage?,
)

/**
 * 挿さっているディスク1枚の写し（`Machine.listDisks`。`kibi:fs/mounts` の `list-disks`。#453、ADR 0453）。外では変わらない。
 * 容量は挿したときの実体（`ResolvedDisk`）から取り、ディスクは開かない。
 *
 * @property diskId ディスクの識別子（source は `disk:` ＋これ）
 * @property capacityBytes 使える量（バイト）
 * @property maxEntries ファイル・ディレクトリの数の上限（根を除く）
 * @property mountPoint マウントの位置。マウントしていない（開いている途中も含む）なら `null`
 */
internal data class DiskListing(
    val diskId: String,
    val capacityBytes: Long,
    val maxEntries: Int,
    val mountPoint: String?,
)
