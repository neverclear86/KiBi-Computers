package pro.ikili.kibicomputers.core.kernel.vfs

/**
 * ファイルシステムの容量と使用量（[FileSystem.usage]。`kibi:fs/mounts` の `fs-usage`。#453、ADR 0453）。
 * 数え方はファイルシステムごとに違い、`du` の合計とは合わない（管理の分を含む）。
 *
 * @property totalBytes 全体（バイト）
 * @property usedBytes 使っている量（バイト。管理の分を含む）
 * @property availableBytes 空き（バイト。0 以上。`/tmp` は目安で、書けることは保証しない）
 * @property totalEntries ファイル・ディレクトリの数の上限（根を除く）。`null` は上限なし
 * @property usedEntries ファイル・ディレクトリの数（根を除く）
 */
internal data class FsUsage(
    val totalBytes: Long,
    val usedBytes: Long,
    val availableBytes: Long,
    val totalEntries: Long?,
    val usedEntries: Long,
)
