package pro.ikili.kibicomputers.core.kernel.vfs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImageFileSystemTest {
    private fun p(text: String) = VfsPath.parse(text)

    /** 名前 → 中身（`null` はディレクトリの項目）の zip を作る */
    private fun zip(vararg entries: Pair<String, String?>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, text) in entries) {
                z.putNextEntry(ZipEntry(name))
                if (text != null) z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun image(vararg entries: Pair<String, String?>) = ImageFileSystem.fromZip(ByteArrayInputStream(zip(*entries)))

    private fun FileSystem.readAll(node: Long): String {
        val buf = ByteArray(4096)
        val n = read(node, 0, buf, 0, buf.size)
        return String(buf, 0, n)
    }

    @Test
    fun `ファイルとディレクトリが見える`() {
        val fs = image("bin/init" to "#!init", "etc/" to null, "readme.txt" to "hi")
        assertTrue(fs.readOnly)
        assertEquals(FileType.DIRECTORY, fs.stat(VfsPath.ROOT).type)
        // 親のディレクトリは項目がなくても作る
        assertEquals(FileType.DIRECTORY, fs.stat(p("/bin")).type)
        assertEquals(FileType.DIRECTORY, fs.stat(p("/etc")).type)
        val init = fs.stat(p("/bin/init"))
        assertEquals(FileType.REGULAR_FILE, init.type)
        assertEquals(6, init.size)
        assertEquals("#!init", fs.readAll(init.node))
        assertEquals(init, fs.statNode(init.node))
        assertEquals(listOf("bin", "etc", "readme.txt"), fs.readDirectory(fs.stat(VfsPath.ROOT).node).map { it.name })
        assertEquals(
            listOf(DirEntry("init", FileType.REGULAR_FILE, init.node)),
            fs.readDirectory(fs.stat(p("/bin")).node),
        )
        assertEquals(emptyList<DirEntry>(), fs.readDirectory(fs.stat(p("/etc")).node))
        assertEquals(5, fs.entryCount)
        assertEquals(8, fs.totalBytes)
    }

    @Test
    fun `位置を指定して読む`() {
        val fs = image("a" to "abcdef")
        val node = fs.stat(p("/a")).node
        val buf = ByteArray(4)
        assertEquals(3, fs.read(node, 3, buf, 1, 3))
        assertEquals("def", String(buf, 1, 3))
        assertEquals(2, fs.read(node, 4, buf, 0, 4))
        assertEquals(0, fs.read(node, 6, buf, 0, 4))
        assertEquals(0, fs.read(node, Long.MAX_VALUE, buf, 0, 4))
        assertErrno(Errno.ISDIR) { fs.read(fs.stat(VfsPath.ROOT).node, 0, buf, 0, 4) }
    }

    @Test
    fun `ないものとファイルの下`() {
        val fs = image("a" to "x")
        assertErrno(Errno.NOENT) { fs.stat(p("/b")) }
        assertErrno(Errno.NOENT) { fs.stat(p("/b/c")) }
        assertErrno(Errno.NOTDIR) { fs.stat(p("/a/c")) }
        assertErrno(Errno.NOTDIR) { fs.readDirectory(fs.stat(p("/a")).node) }
        assertErrno(Errno.STALE) { fs.statNode(12345) }
    }

    @Test
    fun `書く操作は ROFS`() {
        val fs = image("a" to "x")
        val node = fs.stat(p("/a")).node
        assertErrno(Errno.ROFS) { fs.createFile(p("/b")) }
        assertErrno(Errno.ROFS) { fs.createDirectory(p("/d")) }
        assertErrno(Errno.ROFS) { fs.removeFile(p("/a")) }
        assertErrno(Errno.ROFS) { fs.removeDirectory(p("/")) }
        assertErrno(Errno.ROFS) { fs.rename(p("/a"), p("/b")) }
        assertErrno(Errno.ROFS) { fs.write(node, 0, ByteArray(1), 0, 1) }
        assertErrno(Errno.ROFS) { fs.setSize(node, 0) }
        assertErrno(Errno.ROFS) { fs.setTimes(node, 1, 1) }
    }

    @Test
    fun `node はパスで決まり、中身が変わっても同じ`() {
        val a = image("bin/init" to "v1", "x" to "1")
        val b = image("other" to "z", "bin/init" to "version 2")
        assertEquals(a.stat(p("/bin/init")).node, b.stat(p("/bin/init")).node)
        assertEquals(ImageFileSystem.nodeOf(p("/bin/init")), a.stat(p("/bin/init")).node)
        assertNotEquals(a.stat(p("/x")).node, a.stat(p("/bin/init")).node)
        assertTrue(a.stat(VfsPath.ROOT).node > 0)
        // 消えたパスの node は STALE
        assertErrno(Errno.STALE) { b.statNode(a.stat(p("/x")).node) }
    }

    @Test
    fun `不正な zip は読まない`() {
        assertThrows<IllegalArgumentException> { image("../evil" to "x") }
        assertThrows<IllegalArgumentException> { image("a/../b" to "x") }
        assertThrows<IllegalArgumentException> { image("a" to "x", "a/b" to "y") }
        assertThrows<IllegalArgumentException> { image("a/" to null, "a" to "x") }
        assertThrows<IllegalArgumentException> { image("a\\b" to "x") }
        assertThrows<IllegalArgumentException> { image("bad\u0000name" to "x") }
    }

    @Test
    fun `VFS にマウントして読める・書けない`() {
        val vfs = Vfs()
        vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "disk:root")
        // マウントは読み書きでも、ファイルシステムが読み取り専用なら書けない
        vfs.mount(p("/mnt/img"), image("bin/init" to "init!"), readOnly = false, source = "disk:img")
        val f = vfs.open(p("/mnt/img/bin/init"), OpenOptions())
        val buf = ByteArray(16)
        assertEquals(5, vfs.read(f, buf, 0, buf.size))
        assertEquals("init!", String(buf, 0, 5))
        assertErrno(Errno.ROFS) { vfs.open(p("/mnt/img/bin/init"), OpenOptions(read = true, write = true)) }
        assertErrno(Errno.ROFS) { vfs.open(p("/mnt/img/new"), OpenOptions(write = true, create = true)) }
        assertErrno(Errno.ROFS) { vfs.setTimes(f, 1, 1) }
    }

    @Test
    fun `同梱イメージをリソースから読み、同じものを共有する`() {
        val fs = ImageFileSystem.bundled("sample")!!
        assertSame(fs, ImageFileSystem.bundled("sample"))
        assertEquals("hello image", fs.readAll(fs.stat(p("/hello.txt")).node).trim())
        assertEquals("init", fs.readAll(fs.stat(p("/bin/init")).node).trim())
        assertEquals("deep", fs.readAll(fs.stat(p("/lib/sub/data.txt")).node).trim())
        assertEquals(listOf("bin", "hello.txt", "lib"), fs.readDirectory(fs.stat(VfsPath.ROOT).node).map { it.name })
        assertNull(ImageFileSystem.bundled("no-such-image"))
        assertThrows<IllegalArgumentException> { ImageFileSystem.bundled("../sample") }
        assertThrows<IllegalArgumentException> { ImageFileSystem.bundled("") }
    }
}
