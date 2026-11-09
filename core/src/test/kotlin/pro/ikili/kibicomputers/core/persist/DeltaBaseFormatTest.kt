package pro.ikili.kibicomputers.core.persist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/** `DELTA_BASE` 節の置き方（#83、ADR 0083）。`readFrom` と `readDeltaBase` の判断が揃う */
class DeltaBaseFormatTest {
    private val id = UUID.fromString("0123abcd-4567-89ef-0123-456789abcdef")
    private val base = DeltaBase(id, 7, 1234, 0x5a5a1234)

    private fun bytesOf(snapshot: MachineSnapshot): ByteArray =
        ByteArrayOutputStream().also { out -> DataOutputStream(out).use { snapshot.writeTo(it) } }.toByteArray()

    private fun read(bytes: ByteArray): MachineSnapshot = MachineSnapshot.readFrom(DataInputStream(bytes.inputStream()))

    private fun readBase(bytes: ByteArray): DeltaBase? = MachineSnapshot.readDeltaBase(DataInputStream(bytes.inputStream()))

    /** 外枠と、節の並び（種類・版・中身） */
    private fun raw(vararg sections: Triple<Int, Int, ByteArray>, cut: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.write("KIBS".toByteArray())
            d.writeShort(MachineSnapshot.FORMAT_VERSION)
            d.writeUTF("test")
            d.writeLong(id.mostSignificantBits)
            d.writeLong(id.leastSignificantBits)
            d.writeLong(8)
            for ((kind, version, payload) in sections) {
                d.writeShort(kind)
                d.writeShort(version)
                d.writeInt(payload.size)
                d.write(payload)
            }
        }
        val bytes = out.toByteArray()
        return bytes.copyOf(bytes.size - cut)
    }

    private fun basePayload(length: Int = 20): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeLong(base.generation)
            d.writeLong(base.fileLength)
            d.writeInt(base.crc32c)
        }
        return out.toByteArray().copyOf(length)
    }

    private val deltaSection get() = Triple(SnapshotSection.DELTA_BASE, SnapshotSection.DELTA_BASE_VERSION, basePayload())
    private val kernel = Triple(SnapshotSection.KERNEL, SnapshotSection.KERNEL_VERSION, byteArrayOf(1, 2))
    private val killed = Triple(SnapshotSection.KILLED, SnapshotSection.KILLED_VERSION, byteArrayOf(0, 0, 0, 0, 0))

    @Test
    fun `往復、DELTA_BASEは外枠の直後に1つだけ書かれ、sectionsに入らない`() {
        val section = SnapshotSection(SnapshotSection.KERNEL, 1, byteArrayOf(1))
        val bytes = bytesOf(MachineSnapshot(id, 8, "test", listOf(section), deltaBase = base))
        // 外枠: magic 4 + 版 2 + UTF(2 + 4) + UUID 16 + 世代 8 = 36
        val head = DataInputStream(bytes.inputStream(36, 8))
        assertEquals(SnapshotSection.DELTA_BASE, head.readUnsignedShort())
        assertEquals(SnapshotSection.DELTA_BASE_VERSION, head.readUnsignedShort())
        assertEquals(SnapshotSection.DELTA_BASE_LENGTH, head.readInt())
        val back = read(bytes)
        assertEquals(base, back.deltaBase)
        assertEquals(listOf(SnapshotSection.KERNEL), back.sections.map { it.kind })
        assertEquals(base, readBase(bytes))
        // 全体の世代
        val full = bytesOf(MachineSnapshot(id, 8, "test", listOf(section)))
        assertNull(read(full).deltaBase)
        assertNull(readBase(full))
        // sections に DELTA_BASE を入れて作ると落ちる・UUID が違うと落ちる
        assertThrows<IllegalArgumentException> {
            MachineSnapshot(id, 8, "test", listOf(SnapshotSection(SnapshotSection.DELTA_BASE, 1, ByteArray(20))))
        }
        assertThrows<IllegalArgumentException> { MachineSnapshot(UUID.randomUUID(), 8, "test", deltaBase = base) }
    }

    @Test
    fun `壊れたものを両方の読み手で読む`() {
        // 2 番目にある: readFrom は拒み、readDeltaBase は先頭が別の節なので null
        val second = raw(kernel, deltaSection, killed)
        assertThrows<SnapshotFormatException> { read(second) }
        assertNull(readBase(second))
        // 先頭と 3 番目の 2 つ: readFrom は拒み、readDeltaBase は中身
        val twice = raw(deltaSection, kernel, deltaSection, killed)
        assertThrows<SnapshotFormatException> { read(twice) }
        assertEquals(base, readBase(twice))
        // 先頭で版 2
        val v2 = raw(Triple(SnapshotSection.DELTA_BASE, 2, basePayload()), kernel)
        assertThrows<SnapshotFormatException> { read(v2) }
        assertThrows<SnapshotFormatException> { readBase(v2) }
        // 先頭で長さ 19
        val short = raw(Triple(SnapshotSection.DELTA_BASE, 1, basePayload(19)), kernel)
        assertThrows<SnapshotFormatException> { read(short) }
        assertThrows<SnapshotFormatException> { readBase(short) }
        // 途中で切れた頭（種類と版の途中）・中身の途中
        for (cut in listOf(26, 24, 5)) {
            val cutBytes = raw(deltaSection, cut = cut)
            assertThrows<SnapshotFormatException> { read(cutBytes) }
            assertThrows<SnapshotFormatException> { readBase(cutBytes) }
        }
        // DELTA_BASE なし: どちらも全体
        val none = raw(kernel, killed)
        assertNull(read(none).deltaBase)
        assertNull(readBase(none))
        // 節なし
        assertNull(readBase(raw()))
        assertTrue(read(raw()).sections.isEmpty())
    }
}
