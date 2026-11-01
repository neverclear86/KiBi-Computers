package pro.ikili.kibicomputers.core.kernel.vfs

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.MemoryAccount
import pro.ikili.kibicomputers.core.runtime.WasmMemory

/** メモリ上のファイルシステム（`/tmp`。#53） */
class TmpFileSystemTest {
    private fun p(text: String) = VfsPath.parse(text)

    private val chunk = TmpFileSystem.CHUNK_BYTES
    private val nodeCost = TmpFileSystem.NODE_COST
    private var now = 1_000L

    private fun tmp(pages: Int = 4): Pair<TmpFileSystem, MemoryAccount> {
        val account = MemoryAccount(pages)
        return TmpFileSystem(account) { now } to account
    }

    private fun TmpFileSystem.file(path: String, text: String = ""): Long {
        val node = createFile(p(path))
        val bytes = text.toByteArray()
        if (bytes.isNotEmpty()) assertEquals(bytes.size, write(node, 0, bytes, 0, bytes.size))
        return node
    }

    private fun TmpFileSystem.text(node: Long): String {
        val buf = ByteArray(statNode(node).size.toInt())
        assertEquals(buf.size, read(node, 0, buf, 0, buf.size))
        return String(buf)
    }

    @Test
    fun `作って書いて読み、使った分をRAMに数える`() {
        val (fs, account) = tmp()
        assertEquals(0L, account.usedBytes, "根は数えない")
        fs.createDirectory(p("/d"))
        val node = fs.file("/d/hello.txt", "hello")
        assertEquals("hello", fs.text(node))
        assertEquals(FileType.REGULAR_FILE, fs.stat(p("/d/hello.txt")).type)
        assertEquals(node, fs.stat(p("/d/hello.txt")).node)
        val expected = 2 * nodeCost + "d".length + "hello.txt".length + chunk
        assertEquals(expected, fs.usedBytes)
        assertEquals(expected, account.usedBytes)
        assertEquals(0, account.usedPages, "線形メモリのページには入らない")
        assertEquals(listOf(DirEntry("hello.txt", FileType.REGULAR_FILE, node)), fs.readDirectory(fs.stat(p("/d")).node))
    }

    @Test
    fun `塊をまたぐ読み書きと、穴は0で数えない`() {
        val (fs, account) = tmp()
        val node = fs.file("/a")
        val data = ByteArray(chunk + 10) { (it % 251).toByte() }
        assertEquals(data.size, fs.write(node, chunk - 5L, data, 0, data.size))
        assertEquals(chunk - 5L + data.size, fs.statNode(node).size)
        // 塊 0・1・2 を使う
        assertEquals(nodeCost + 1 + 3L * chunk, account.usedBytes)
        val back = ByteArray(data.size)
        assertEquals(back.size, fs.read(node, chunk - 5L, back, 0, back.size))
        assertArrayEquals(data, back)

        // 伸ばしただけの所は 0 で、RAM を使わない
        val before = account.usedBytes
        fs.setSize(node, 100L * chunk)
        assertEquals(before, account.usedBytes)
        val hole = ByteArray(16) { 1 }
        assertEquals(16, fs.read(node, 50L * chunk, hole, 0, 16))
        assertArrayEquals(ByteArray(16), hole)
        // 終わりから先は 0 バイト
        assertEquals(0, fs.read(node, 100L * chunk, hole, 0, 16))
    }

    @Test
    fun `縮めると塊を返し、伸ばし直すと0が見える`() {
        val (fs, account) = tmp()
        val node = fs.file("/a")
        val data = ByteArray(3 * chunk) { 7 }
        fs.write(node, 0, data, 0, data.size)
        fs.setSize(node, 10)
        assertEquals(nodeCost + 1 + chunk, account.usedBytes)
        fs.setSize(node, 20)
        val buf = ByteArray(20)
        assertEquals(20, fs.read(node, 0, buf, 0, 20))
        assertArrayEquals(ByteArray(10) { 7 } + ByteArray(10), buf)
        fs.setSize(node, 0)
        assertEquals(nodeCost + 1, account.usedBytes)
    }

    @Test
    fun `RAMが尽きたらNOSPCで、書けた分までの短い書き込みになる`() {
        val (fs, account) = tmp(pages = 1) // 64 KiB
        val node = fs.file("/a")
        val big = ByteArray(WasmMemory.PAGE_SIZE)
        val n = fs.write(node, 0, big, 0, big.size)
        assertTrue(n in 1 until big.size, "短い書き込み: $n")
        assertEquals(0, n % chunk)
        assertEquals(n.toLong(), fs.statNode(node).size)
        assertErrno(Errno.NOSPC) { fs.write(node, n.toLong(), big, 0, 1) }
        assertTrue(account.usedBytes <= account.limitBytes)
        // 線形メモリも同じ枠から取る
        assertFalse(account.tryReserve(1), "RAM は /tmp で埋まっている")
        fs.removeFile(p("/a"))
        assertEquals(0L, account.usedBytes)
        assertTrue(account.tryReserve(1))
        // 線形メモリで埋まっていれば /tmp には作れない
        assertErrno(Errno.NOSPC) { fs.createFile(p("/b")) }
        assertErrno(Errno.NOENT) { fs.stat(p("/b")) }
        account.release(1)
        fs.createFile(p("/b"))
    }

    @Test
    fun `消すとすぐ返し、開いていたnodeはSTALE`() {
        val (fs, account) = tmp()
        val node = fs.file("/a", "abc")
        fs.removeFile(p("/a"))
        assertEquals(0L, account.usedBytes)
        assertErrno(Errno.STALE) { fs.statNode(node) }
        assertErrno(Errno.STALE) { fs.read(node, 0, ByteArray(1), 0, 1) }
        assertErrno(Errno.NOENT) { fs.stat(p("/a")) }
        assertNotEquals(node, fs.file("/a"), "node は使い回さない")
    }

    @Test
    fun `ディレクトリの規則`() {
        val (fs, _) = tmp()
        fs.createDirectory(p("/d"))
        fs.file("/d/f")
        assertErrno(Errno.EXIST) { fs.createDirectory(p("/d")) }
        assertErrno(Errno.EXIST) { fs.createFile(p("/d/f")) }
        assertErrno(Errno.NOENT) { fs.createFile(p("/x/f")) }
        assertErrno(Errno.NOTDIR) { fs.createFile(p("/d/f/g")) }
        assertErrno(Errno.NOTDIR) { fs.stat(p("/d/f/g")) }
        assertErrno(Errno.NOTEMPTY) { fs.removeDirectory(p("/d")) }
        assertErrno(Errno.ISDIR) { fs.removeFile(p("/d")) }
        assertErrno(Errno.NOTDIR) { fs.removeDirectory(p("/d/f")) }
        assertErrno(Errno.BUSY) { fs.removeDirectory(VfsPath.ROOT) }
        assertErrno(Errno.ISDIR) { fs.read(fs.stat(p("/d")).node, 0, ByteArray(1), 0, 1) }
        assertErrno(Errno.NOTDIR) { fs.readDirectory(fs.stat(p("/d/f")).node) }
        fs.removeFile(p("/d/f"))
        fs.removeDirectory(p("/d"))
        assertEquals(emptyList<DirEntry>(), fs.readDirectory(TmpFileSystem.ROOT_NODE))
        assertEquals(0L, fs.usedBytes)
    }

    @Test
    fun `名前の変更はPOSIXの規則で、置き換えた分を返す`() {
        val (fs, account) = tmp()
        val a = fs.file("/a", "aaa")
        fs.file("/b", "bbb")
        fs.rename(p("/a"), p("/b"))
        assertEquals("aaa", fs.text(fs.stat(p("/b")).node))
        assertEquals(a, fs.stat(p("/b")).node, "node は変わらない")
        assertErrno(Errno.NOENT) { fs.stat(p("/a")) }
        assertEquals(nodeCost + 1 + chunk, account.usedBytes)

        fs.createDirectory(p("/d"))
        fs.createDirectory(p("/e"))
        fs.file("/e/x")
        assertErrno(Errno.ISDIR) { fs.rename(p("/b"), p("/d")) }
        assertErrno(Errno.NOTDIR) { fs.rename(p("/d"), p("/b")) }
        assertErrno(Errno.NOTEMPTY) { fs.rename(p("/d"), p("/e")) }
        assertErrno(Errno.INVAL) { fs.rename(p("/d"), p("/d/sub")) }
        assertErrno(Errno.NOENT) { fs.rename(p("/nope"), p("/z")) }
        assertErrno(Errno.NOENT) { fs.rename(p("/b"), p("/nope/z")) }
        // 空のディレクトリは置き換えられる
        fs.rename(p("/e"), p("/d"))
        assertEquals(listOf("x"), fs.readDirectory(fs.stat(p("/d")).node).map { it.name })
        // 長い名前に変えると、名前の分が増える
        val before = account.usedBytes
        fs.rename(p("/b"), p("/d/longer-name"))
        assertEquals(before - 1 + "longer-name".length, account.usedBytes)
    }

    @Test
    fun `時刻`() {
        val (fs, _) = tmp()
        now = 5
        fs.createDirectory(p("/d"))
        now = 10
        val node = fs.file("/d/f")
        assertEquals(10, fs.statNode(node).modifiedNanos)
        assertEquals(10, fs.stat(p("/d")).modifiedNanos, "中身が変わったディレクトリ")
        now = 20
        fs.write(node, 0, byteArrayOf(1), 0, 1)
        val st = fs.statNode(node)
        assertEquals(20, st.modifiedNanos)
        assertEquals(20, st.changedNanos)
        assertEquals(10, st.accessedNanos, "読み書きでは atime を変えない")
        now = 30
        fs.setTimes(node, 1, null)
        assertEquals(1, fs.statNode(node).accessedNanos)
        assertEquals(20, fs.statNode(node).modifiedNanos)
        assertEquals(30, fs.statNode(node).changedNanos)
    }

    @Test
    fun `外すと中身を捨ててRAMを返す`() {
        val (fs, account) = tmp()
        val vfs = Vfs()
        val mount = vfs.mount(p("/tmp"), fs, readOnly = false, source = "tmp")
        val f = vfs.open(p("/tmp/x"), OpenOptions(read = true, write = true, create = true))
        vfs.write(f, "data".toByteArray(), 0, 4)
        vfs.createDirectory(p("/tmp/sub"))
        assertTrue(account.usedBytes > 0)
        vfs.unmount(mount.id)
        assertEquals(0L, account.usedBytes)
        assertEquals(0L, fs.usedBytes)
        assertEquals(1, fs.nodeCount)
        assertErrno(Errno.IO) { vfs.read(f, ByteArray(4), 0, 4) }
    }
}
