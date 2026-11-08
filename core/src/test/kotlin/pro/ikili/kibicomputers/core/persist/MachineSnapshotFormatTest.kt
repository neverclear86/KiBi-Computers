package pro.ikili.kibicomputers.core.persist

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

/** 保存データの外枠（`MachineSnapshot`）の形のテスト（#81、ADR 0081） */
class MachineSnapshotFormatTest {
    private val id = UUID.fromString("0123abcd-4567-89ef-0123-456789abcdef")

    private fun bytesOf(snapshot: MachineSnapshot): ByteArray =
        ByteArrayOutputStream().also { out -> DataOutputStream(out).use { snapshot.writeTo(it) } }.toByteArray()

    private fun read(bytes: ByteArray): MachineSnapshot = MachineSnapshot.readFrom(DataInputStream(bytes.inputStream()))

    @Test
    fun `外枠と節を往復する`() {
        val section = SnapshotSection(SnapshotSection.KERNEL, 1, byteArrayOf(1, 2, 3))
        val original = MachineSnapshot(id, 42, "0.0.1", listOf(section), listOf(3, 5), ColdBootReason.NONE.code)
        val back = read(bytesOf(original))
        assertEquals(id, back.machineId)
        assertEquals(42, back.generation)
        assertEquals("0.0.1", back.modVersion)
        assertEquals(1, back.sections.size)
        assertEquals(SnapshotSection.KERNEL, back.sections[0].kind)
        assertArrayEquals(byteArrayOf(1, 2, 3), back.sections[0].payload)
        assertEquals(listOf(3, 5), back.killedPids)
        assertEquals(ColdBootReason.NONE, back.coldBootReason)
        assertFalse(back.requiresColdBoot)
    }

    @Test
    fun `先頭はmagicと版、数値はビッグエンディアン`() {
        val bytes = bytesOf(MachineSnapshot(id, 1, "v"))
        assertArrayEquals("KIBS".toByteArray(), bytes.copyOf(4))
        assertEquals(0, bytes[4].toInt())
        assertEquals(MachineSnapshot.FORMAT_VERSION, bytes[5].toInt())
    }

    @Test
    fun `KILLED節の理由は0から3を往復し、知らない値は冷えた起動が要る側`() {
        for (reason in ColdBootReason.entries) {
            val back = read(bytesOf(MachineSnapshot(id, 1, "v", killedPids = listOf(1), coldBootCode = reason.code)))
            assertEquals(reason, back.coldBootReason)
            assertEquals(reason != ColdBootReason.NONE, back.requiresColdBoot)
        }
        val unknown = read(bytesOf(MachineSnapshot(id, 1, "v", coldBootCode = 9)))
        assertNull(unknown.coldBootReason)
        assertTrue(unknown.requiresColdBoot)
        assertTrue(ColdBootReason.requiresColdBoot(200))
    }

    @Test
    fun `知らない節と長さの合わないKILLED節は読み飛ばす`() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            MachineSnapshot(id, 7, "v", listOf(SnapshotSection(999, 4, byteArrayOf(9, 9)))).writeTo(d)
        }
        val back = read(out.toByteArray())
        assertEquals(listOf(999), back.sections.map { it.kind })
        assertFalse(back.requiresColdBoot)

        // 長さの合わない KILLED 節（個数 2 なのに pid が1つ）だけの保存データは、理由が読めず冷えた起動が要る
        val bad = ByteArrayOutputStream()
        DataOutputStream(bad).use { d ->
            d.write("KIBS".toByteArray())
            d.writeShort(MachineSnapshot.FORMAT_VERSION)
            d.writeUTF("v")
            d.writeLong(id.mostSignificantBits)
            d.writeLong(id.leastSignificantBits)
            d.writeLong(7)
            d.writeShort(SnapshotSection.KILLED)
            d.writeShort(SnapshotSection.KILLED_VERSION)
            d.writeInt(9)
            d.writeInt(2)
            d.writeInt(1)
            d.writeByte(0)
        }
        val skipped = read(bad.toByteArray())
        assertEquals(emptyList<Int>(), skipped.killedPids)
        assertTrue(skipped.requiresColdBoot)
    }

    @Test
    fun `magic・版の違いと途中で切れたものを拒む`() {
        val good = bytesOf(MachineSnapshot(id, 1, "v", listOf(SnapshotSection(1, 1, ByteArray(10)))))
        val badMagic = good.copyOf().also { it[0] = 'X'.code.toByte() }
        assertThrows<IOException> { read(badMagic) }
        val badVersion = good.copyOf().also { it[5] = 2 }
        assertThrows<IOException> { read(badVersion) }
        assertThrows<IOException> { read(good.copyOf(good.size - 3)) }
    }
}
