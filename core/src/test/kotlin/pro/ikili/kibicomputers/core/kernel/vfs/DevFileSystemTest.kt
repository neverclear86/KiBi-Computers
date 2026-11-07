package pro.ikili.kibicomputers.core.kernel.vfs

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** デバイスファイルの `/dev`（#485） */
class DevFileSystemTest {
    private fun p(text: String) = VfsPath.parse(text)

    private fun vfs(): Vfs = Vfs().also {
        it.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        it.mount(p("/dev"), DevFileSystem(), readOnly = false, source = "dev")
    }

    private fun errno(block: () -> Unit): Errno = assertThrows<FsException> { block() }.errno

    @Test
    fun `nullは読むと終わりで、書くと捨てる`() {
        val v = vfs()
        val f = v.open(p("/dev/null"), OpenOptions(read = true, write = true, create = true, truncate = true))
        assertEquals(FileType.CHARACTER_DEVICE, f.type)
        assertEquals(5, v.write(f, "hello".toByteArray(), 0, 5))
        assertEquals(0, v.read(f, ByteArray(8), 0, 8))
        assertEquals(0L, v.stat(f).size)
        val a = v.open(p("/dev/null"), OpenOptions(read = false, write = true, append = true))
        assertEquals(3, v.write(a, "abc".toByteArray(), 0, 3))
    }

    @Test
    fun `zeroは読むと0で埋まり、書くと捨てる`() {
        val v = vfs()
        val f = v.open(p("/dev/zero"), OpenOptions(read = true, write = true))
        val buf = ByteArray(16) { 7 }
        assertEquals(10, v.read(f, buf, 3, 10))
        assertArrayEquals(ByteArray(3) { 7 } + ByteArray(10) + ByteArray(3) { 7 }, buf)
        assertEquals(4, v.write(f, ByteArray(4), 0, 4))
    }

    @Test
    fun `ttyは端末として開くもの`() {
        val v = vfs()
        val tty = v.open(p("/dev/tty"), OpenOptions(read = true, write = true))
        assertTrue(v.isTerminalLocked(tty))
        assertFalse(v.isTerminalLocked(v.open(p("/dev/null"), OpenOptions())))
        assertFalse(v.isTerminalLocked(v.open(p("/dev"), OpenOptions(directory = true))))
        assertEquals(Errno.NXIO, errno { v.read(tty, ByteArray(1), 0, 1) })
    }

    @Test
    fun `中身は決まっていて、作る・消す・名前を変えるはできない`() {
        val v = vfs()
        val dir = v.open(p("/dev"), OpenOptions(directory = true))
        assertEquals(listOf("null", "tty", "zero"), v.readDirectory(dir).map { it.name })
        assertTrue(v.readDirectory(dir).all { it.type == FileType.CHARACTER_DEVICE })
        assertEquals(Errno.NOENT, errno { v.stat(p("/dev/random")) })
        assertEquals(Errno.NOTDIR, errno { v.stat(p("/dev/null/x")) })
        assertEquals(Errno.ACCES, errno { v.open(p("/dev/new"), OpenOptions(write = true, create = true)) })
        assertEquals(Errno.ACCES, errno { v.createDirectory(p("/dev/d")) })
        assertEquals(Errno.ACCES, errno { v.removeFile(p("/dev/null")) })
        assertEquals(Errno.ACCES, errno { v.rename(p("/dev/null"), p("/dev/nil")) })
        assertEquals(Errno.NOTDIR, errno { v.open(p("/dev/null"), OpenOptions(directory = true)) })
        // touch /dev/null は通る
        v.setTimes(p("/dev/null"), 1, 2)
    }
}
