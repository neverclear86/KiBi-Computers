package pro.ikili.kibicomputers.core.persist.snapshot

import pro.ikili.kibicomputers.core.persist.DeltaBase
import pro.ikili.kibicomputers.core.persist.SnapshotFormatException
import pro.ikili.kibicomputers.core.persist.SnapshotSection
import java.util.UUID

/**
 * `DELTA_BASE` 節（種類 5、版 1。#83、ADR 0083）の中身: 基準の世代（i64）・基準のファイルの長さ（i64）・基準のファイルの CRC32C（i32）。
 * 基準の UUID は書かない（外枠の UUID と同じ）
 */
internal object DeltaBaseCodec {
    fun section(base: DeltaBase): SnapshotSection {
        val out = SnapshotWriter(SnapshotSection.DELTA_BASE_LENGTH)
        out.i64(base.generation)
        out.i64(base.fileLength)
        out.i32(base.crc32c)
        return SnapshotSection.wrap(SnapshotSection.DELTA_BASE, SnapshotSection.DELTA_BASE_VERSION, out.toByteArray())
    }

    @Throws(SnapshotFormatException::class)
    fun read(id: UUID, payload: ByteArray): DeltaBase {
        val input = SnapshotReader(payload)
        val generation = input.i64()
        val length = input.i64()
        val crc = input.i32()
        input.expectEnd()
        if (generation < 0 || length < 0) throw SnapshotFormatException("DELTA_BASE 節の値が不正（世代 $generation・長さ $length）")
        return DeltaBase(id, generation, length, crc)
    }
}
