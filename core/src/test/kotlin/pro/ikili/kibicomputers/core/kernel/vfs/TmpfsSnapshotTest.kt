package pro.ikili.kibicomputers.core.kernel.vfs

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.MemoryAccount
import pro.ikili.kibicomputers.core.persist.SnapshotFormatException
import pro.ikili.kibicomputers.core.persist.snapshot.TmpfsSectionCodec

/** `/tmp` の写しと `TMPFS` 節（#82） */
class TmpfsSnapshotTest {
    private fun p(text: String) = VfsPath.parse(text)

    private var now = 1_000L

    private fun TmpFileSystem.read(path: String): ByteArray {
        val node = stat(p(path)).node
        val buf = ByteArray(statNode(node).size.toInt())
        assertEquals(buf.size, read(node, 0, buf, 0, buf.size))
        return buf
    }

    @Test
    fun `写しをTMPFS節にして読み、作り直すと中身と時刻と使った量が同じ`() {
        val account = MemoryAccount(64)
        val fs = TmpFileSystem(account) { now }
        fs.createDirectory(p("/d"))
        val a = fs.createFile(p("/d/a.txt"))
        val data = ByteArray(TmpFileSystem.CHUNK_BYTES + 100) { (it % 251).toByte() }
        fs.write(a, 0, data, 0, data.size)
        now = 2_000
        val sparse = fs.createFile(p("/sparse"))
        fs.setSize(sparse, 50L * TmpFileSystem.CHUNK_BYTES)
        fs.write(sparse, 20L * TmpFileSystem.CHUNK_BYTES, byteArrayOf(1, 2, 3), 0, 3)

        val snapshot = fs.snapshot()!!
        val bytes = TmpfsSectionCodec.write(3, snapshot)
        val decoded = TmpfsSectionCodec.read(bytes)
        assertEquals(3, decoded.mountId)

        val account2 = MemoryAccount(64)
        val back = TmpFileSystem.restore(account2, { now }, decoded.snapshot)!!
        assertEquals(fs.usedBytes, back.usedBytes)
        assertEquals(account.usedBytes, account2.usedBytes)
        assertArrayEquals(data, back.read("/d/a.txt"))
        assertArrayEquals(fs.read("/sparse"), back.read("/sparse"))
        assertEquals(fs.stat(p("/d/a.txt")), back.stat(p("/d/a.txt")))
        assertEquals(fs.readDirectory(fs.stat(p("/d")).node), back.readDirectory(back.stat(p("/d")).node))
        // 番号は続きから
        val c = back.createFile(p("/c"))
        assertEquals(fs.createFile(p("/c")), c)
    }

    @Test
    fun `RAMが足りなければ作り直せない`() {
        val fs = TmpFileSystem(MemoryAccount(64)) { now }
        val a = fs.createFile(p("/a"))
        val data = ByteArray(4 * TmpFileSystem.CHUNK_BYTES) { 1 }
        fs.write(a, 0, data, 0, data.size)
        val snapshot = TmpfsSectionCodec.read(TmpfsSectionCodec.write(1, fs.snapshot()!!)).snapshot
        assertNull(TmpFileSystem.restore(MemoryAccount(TmpFileSystem.CHUNK_BYTES.toLong()), { now }, snapshot))
    }

    @Test
    fun `木の形が壊れていればSnapshotFormatException`() {
        val fs = TmpFileSystem(MemoryAccount(64)) { now }
        fs.createFile(p("/a"))
        val bytes = TmpfsSectionCodec.write(1, fs.snapshot()!!)
        assertThrows<SnapshotFormatException> { TmpfsSectionCodec.read(bytes.copyOf(bytes.size - 1)) }
        // 根からたどれない node（根の子の数を 0 にする）
        val snapshot = fs.snapshot()!!
        val root = snapshot.nodes.first { it.id == TmpFileSystem.ROOT_NODE }
        val broken = TmpFileSystem.TmpSnapshot(
            snapshot.nextNode,
            snapshot.nodes.map {
                if (it.id == root.id) {
                    TmpFileSystem.TmpNode(it.id, it.type, it.size, it.accessedNanos, it.modifiedNanos, it.changedNanos, emptyList(), null)
                } else {
                    it
                }
            },
        )
        assertThrows<SnapshotFormatException> { TmpfsSectionCodec.read(TmpfsSectionCodec.write(1, broken)) }
    }
}
