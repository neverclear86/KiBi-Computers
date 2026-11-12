package pro.ikili.kibicomputers.core.computer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pro.ikili.kibicomputers.core.kernel.DiskSnapshot
import pro.ikili.kibicomputers.core.kernel.ResolvedDisk
import java.nio.file.Files
import java.nio.file.Path

/** ワールドの内蔵ディスクの置き場（#124） */
class DiskStoreTest {
    @TempDir
    lateinit var dir: Path

    private val store by lazy { DiskStore(dir.resolve("disks")) }

    @Test
    fun `新しい識別子は正しい形で毎回違い、ディレクトリを作らない`() {
        val a = store.newId()
        val b = store.newId()
        assertTrue(DiskStore.isValidId(a), a)
        assertTrue(DiskStore.isValidId(b), b)
        assertNotEquals(a, b)
        assertEquals(36, a.length)
        assertFalse(Files.exists(store.root))
    }

    @Test
    fun `形の正しくない識別子はディレクトリを指さない`() {
        val valid = "3f2a9c1e-0b7d-4c8e-9a11-5e6f7a8b9c0d"
        assertTrue(DiskStore.isValidId(valid))
        val invalid = listOf(
            "",
            "..",
            ".",
            "../$valid",
            "$valid/..",
            "a/b",
            "a\\b",
            valid.uppercase(),
            valid.replace("-", ""),
            valid + "0",
            " $valid",
            "$valid\n",
            "3f2a9c1e-0b7d-4c8e-9a11-5e6f7a8b9c0g",
            "0",
        )
        for (id in invalid) {
            assertFalse(DiskStore.isValidId(id), id)
            assertNull(store.directoryOf(id), id)
        }
    }

    @Test
    fun `ディレクトリは根の直下`() {
        val id = store.newId()
        val d = store.directoryOf(id)!!
        assertEquals(store.root, d.parent)
        assertEquals(id, d.fileName.toString())
        assertFalse(Files.exists(d))
    }

    @Test
    fun `写しはティアの容量を返し、知らない・形の正しくない識別子はnull`() {
        val a = store.newId()
        val b = store.newId()
        val map = linkedMapOf(a to PartTier.TIER_1, b to PartTier.TIER_4, "../evil" to PartTier.CREATIVE)
        val disks = store.snapshot(map)
        assertEquals(
            ResolvedDisk(store.directoryOf(a)!!, PartTier.TIER_1.diskBytes, PartTier.TIER_1.diskEntries),
            disks.resolve(a),
        )
        assertEquals(PartTier.TIER_4.diskBytes, disks.resolve(b)!!.capacityBytes)
        assertNull(disks.resolve("../evil"))
        assertNull(disks.resolve(store.newId()))
        // 作った後に元を変えても変わらない
        map.remove(a)
        map[b] = PartTier.TIER_2
        assertEquals(PartTier.TIER_1.diskBytes, disks.resolve(a)!!.capacityBytes)
        assertEquals(PartTier.TIER_4.diskBytes, disks.resolve(b)!!.capacityBytes)
    }

    @Test
    fun `ディスクがなければNONE`() {
        assertSame(DiskSnapshot.EMPTY, store.snapshot(emptyMap()))
        assertSame(DiskSnapshot.EMPTY, store.snapshot(mapOf("x" to PartTier.TIER_1)))
    }
}
