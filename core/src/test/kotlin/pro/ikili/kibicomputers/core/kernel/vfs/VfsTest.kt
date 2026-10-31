package pro.ikili.kibicomputers.core.kernel.vfs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VfsTest {
    private fun p(text: String) = VfsPath.parse(text)

    private val rw = OpenOptions(read = true, write = true)
    private val create = OpenOptions(read = true, write = true, create = true)

    private fun vfsWithRoot(): Pair<Vfs, TestMemoryFileSystem> {
        val vfs = Vfs()
        val fs = TestMemoryFileSystem()
        vfs.mount(VfsPath.ROOT, fs, readOnly = false, source = "disk:root")
        return vfs to fs
    }

    private fun Vfs.writeText(path: String, text: String) {
        val f = open(p(path), create.copy(truncate = true))
        val bytes = text.toByteArray()
        assertEquals(bytes.size, write(f, bytes, 0, bytes.size))
    }

    private fun Vfs.readText(path: String): String {
        val f = open(p(path), OpenOptions())
        val buf = ByteArray(1024)
        val n = read(f, buf, 0, buf.size)
        return String(buf, 0, n)
    }

    @Test
    fun `作って書いて読む`() {
        val (vfs, _) = vfsWithRoot()
        vfs.writeText("/hello.txt", "hello")
        assertEquals("hello", vfs.readText("/hello.txt"))
        assertEquals(5, vfs.stat(p("/hello.txt")).size)
        assertEquals(FileType.REGULAR_FILE, vfs.stat(p("/hello.txt")).type)
    }

    @Test
    fun `位置と読み書き`() {
        val (vfs, _) = vfsWithRoot()
        val f = vfs.open(p("/a"), create)
        vfs.write(f, "abcdef".toByteArray(), 0, 6)
        assertEquals(6, f.position)
        assertEquals(2, vfs.seek(f, 2, Whence.SET))
        val buf = ByteArray(3)
        assertEquals(3, vfs.read(f, buf, 0, 3))
        assertEquals("cde", String(buf))
        assertEquals(5, f.position)
        assertEquals(3, vfs.seek(f, -2, Whence.CUR))
        assertEquals(4, vfs.seek(f, -2, Whence.END))
        assertEquals(2, vfs.read(f, buf, 0, 3))
        assertEquals(0, vfs.read(f, buf, 0, 3))
        assertErrno(Errno.INVAL) { vfs.seek(f, -1, Whence.SET) }
        assertErrno(Errno.OVERFLOW) { vfs.seek(f, Long.MAX_VALUE, Whence.CUR) }
        // 位置を変えない読み書き
        assertEquals(1, vfs.pwrite(f, 0, "X".toByteArray(), 0, 1))
        assertEquals(1, vfs.pread(f, 0, buf, 0, 1))
        assertEquals('X', buf[0].toInt().toChar())
        assertEquals(6, f.position)
        assertErrno(Errno.FAULT) { vfs.read(f, buf, 2, 2) }
    }

    @Test
    fun `追記と切り詰め`() {
        val (vfs, _) = vfsWithRoot()
        vfs.writeText("/log", "ab")
        val f = vfs.open(p("/log"), OpenOptions(write = true, append = true))
        vfs.seek(f, 0, Whence.SET)
        vfs.write(f, "cd".toByteArray(), 0, 2)
        assertEquals(4, f.position)
        assertEquals("abcd", vfs.readText("/log"))
        vfs.open(p("/log"), OpenOptions(write = true, truncate = true))
        assertEquals(0, vfs.stat(p("/log")).size)
        vfs.setSize(f, 3)
        assertEquals(3, vfs.stat(f).size)
        assertErrno(Errno.INVAL) { vfs.open(p("/log"), OpenOptions(truncate = true)) }
        assertErrno(Errno.INVAL) { vfs.open(p("/log"), OpenOptions(append = true)) }
    }

    @Test
    fun `権利がなければBADF`() {
        val (vfs, _) = vfsWithRoot()
        vfs.writeText("/f", "x")
        val ro = vfs.open(p("/f"), OpenOptions(read = true))
        assertErrno(Errno.BADF) { vfs.write(ro, ByteArray(1), 0, 1) }
        assertErrno(Errno.BADF) { vfs.setSize(ro, 0) }
        val wo = vfs.open(p("/f"), OpenOptions(read = false, write = true))
        assertErrno(Errno.BADF) { vfs.read(wo, ByteArray(1), 0, 1) }
    }

    @Test
    fun `開くときの規則`() {
        val (vfs, _) = vfsWithRoot()
        vfs.createDirectory(p("/dir"))
        vfs.writeText("/f", "x")
        assertErrno(Errno.NOENT) { vfs.open(p("/none"), OpenOptions()) }
        assertErrno(Errno.NOENT) { vfs.open(p("/nodir/x"), create) }
        assertErrno(Errno.NOTDIR) { vfs.open(p("/f/x"), create) }
        assertErrno(Errno.EXIST) { vfs.open(p("/f"), create.copy(exclusive = true)) }
        assertErrno(Errno.ISDIR) { vfs.open(p("/dir"), rw) }
        assertErrno(Errno.NOTDIR) { vfs.open(p("/f"), OpenOptions(directory = true)) }
        assertErrno(Errno.NOTDIR) { vfs.open(p("/f"), OpenOptions(), trailingSlash = true) }
        assertErrno(Errno.ISDIR) { vfs.open(p("/new"), create, trailingSlash = true) }
        assertErrno(Errno.INVAL) { vfs.open(p("/new"), create.copy(directory = true)) }
        val d = vfs.open(p("/dir"), OpenOptions(directory = true))
        assertTrue(d.isDirectory)
        assertErrno(Errno.ISDIR) { vfs.read(d, ByteArray(1), 0, 1) }
        assertErrno(Errno.NOTDIR) { vfs.readDirectory(vfs.open(p("/f"), OpenOptions())) }
    }

    @Test
    fun `ディレクトリの操作`() {
        val (vfs, _) = vfsWithRoot()
        vfs.createDirectory(p("/d"))
        vfs.writeText("/d/b", "1")
        vfs.writeText("/d/a", "2")
        assertErrno(Errno.EXIST) { vfs.createDirectory(p("/d")) }
        assertErrno(Errno.NOTEMPTY) { vfs.removeDirectory(p("/d")) }
        val entries = vfs.readDirectory(vfs.open(p("/d"), OpenOptions()))
        assertEquals(listOf("a", "b"), entries.map { it.name })
        vfs.rename(p("/d/a"), p("/d/c"))
        assertEquals("2", vfs.readText("/d/c"))
        assertErrno(Errno.INVAL) { vfs.rename(p("/d"), p("/d/sub")) }
        vfs.removeFile(p("/d/b"))
        vfs.removeFile(p("/d/c"))
        vfs.removeDirectory(p("/d"))
        assertErrno(Errno.NOENT) { vfs.stat(p("/d")) }
    }

    @Test
    fun `マウントは長い位置が勝ち、祖先はディレクトリに見える`() {
        val (vfs, root) = vfsWithRoot()
        val disk = TestMemoryFileSystem()
        val m = vfs.mount(p("/mnt/disk"), disk, readOnly = false, source = "disk:1")
        vfs.writeText("/mnt/disk/x", "on disk")
        assertEquals("on disk", vfs.readText("/mnt/disk/x"))
        assertEquals(m.id.toLong(), vfs.stat(p("/mnt/disk/x")).device)
        // /mnt は根の FS にないが、ディレクトリとして見える
        val mnt = vfs.stat(p("/mnt"))
        assertEquals(FileType.DIRECTORY, mnt.type)
        assertEquals(0, mnt.device)
        assertEquals(listOf("disk"), vfs.readDirectory(vfs.open(p("/mnt"), OpenOptions())).map { it.name })
        // 根の中身にも mnt が出る
        vfs.writeText("/z", "")
        assertEquals(listOf("mnt", "z"), vfs.readDirectory(vfs.open(VfsPath.ROOT, OpenOptions())).map { it.name })
        // 祖先のディレクトリには作れない
        assertErrno(Errno.ROFS) { vfs.open(p("/mnt/other"), create) }
        assertErrno(Errno.EXIST) { vfs.createDirectory(p("/mnt")) }
        // 根の FS には書いていない
        assertErrno(Errno.NOENT) { root.stat(p("/mnt")) }
    }

    @Test
    fun `根に何もなければ根はマウントの祖先として見える`() {
        val vfs = Vfs()
        vfs.mount(p("/tmp"), TestMemoryFileSystem(), readOnly = false, source = "tmp")
        assertEquals(FileType.DIRECTORY, vfs.stat(VfsPath.ROOT).type)
        assertEquals(listOf("tmp"), vfs.readDirectory(vfs.open(VfsPath.ROOT, OpenOptions())).map { it.name })
        assertErrno(Errno.NOENT) { vfs.stat(p("/etc")) }
        assertErrno(Errno.ROFS) { vfs.open(p("/etc"), create) }
        assertErrno(Errno.ROFS) { vfs.createDirectory(p("/etc")) }
        vfs.writeText("/tmp/a", "a")
        assertEquals("a", vfs.readText("/tmp/a"))
    }

    @Test
    fun `マウントの位置は消せず、またいだ名前の変更はXDEV`() {
        val (vfs, _) = vfsWithRoot()
        vfs.createDirectory(p("/mnt"))
        vfs.mount(p("/mnt/disk"), TestMemoryFileSystem(), readOnly = false, source = "disk:1")
        vfs.writeText("/a", "a")
        assertErrno(Errno.BUSY) { vfs.removeDirectory(p("/mnt/disk")) }
        assertErrno(Errno.BUSY) { vfs.removeDirectory(p("/mnt")) }
        assertErrno(Errno.BUSY) { vfs.rename(p("/mnt"), p("/m2")) }
        assertErrno(Errno.BUSY) { vfs.rename(p("/a"), p("/mnt/disk")) }
        assertErrno(Errno.XDEV) { vfs.rename(p("/a"), p("/mnt/disk/a")) }
        assertErrno(Errno.BUSY) { vfs.mount(p("/mnt/disk"), TestMemoryFileSystem(), readOnly = false, source = "x") }
        assertErrno(Errno.NOTDIR) { vfs.mount(p("/a"), TestMemoryFileSystem(), readOnly = false, source = "x") }
    }

    @Test
    fun `読み取り専用はROFS`() {
        val vfs = Vfs()
        val image = TestMemoryFileSystem()
        image.createFile(p("/prog"))
        vfs.mount(VfsPath.ROOT, image, readOnly = true, source = "image")
        vfs.open(p("/prog"), OpenOptions())
        assertErrno(Errno.ROFS) { vfs.open(p("/prog"), rw) }
        assertErrno(Errno.ROFS) { vfs.open(p("/new"), create) }
        assertErrno(Errno.ROFS) { vfs.createDirectory(p("/d")) }
        assertErrno(Errno.ROFS) { vfs.removeFile(p("/prog")) }
        // FS 自身が読み取り専用でも同じ
        val v2 = Vfs()
        v2.mount(VfsPath.ROOT, TestMemoryFileSystem(readOnly = true), readOnly = false, source = "image")
        assertErrno(Errno.ROFS) { v2.open(p("/new"), create) }
    }

    @Test
    fun `外したマウントの開いたファイルはIO`() {
        val (vfs, _) = vfsWithRoot()
        val m = vfs.mount(p("/disk"), TestMemoryFileSystem(), readOnly = false, source = "disk:1")
        val f = vfs.open(p("/disk/x"), create)
        vfs.write(f, "x".toByteArray(), 0, 1)
        // 下にマウントがあるうちは外せない
        val sub = vfs.mount(p("/disk/sub"), TestMemoryFileSystem(), readOnly = false, source = "disk:2")
        assertErrno(Errno.BUSY) { vfs.unmount(m.id) }
        vfs.unmount(sub.id)
        vfs.unmount(m.id)
        assertErrno(Errno.IO) { vfs.read(f, ByteArray(1), 0, 1) }
        assertErrno(Errno.IO) { vfs.stat(f) }
        // 同じ位置にマウントし直しても、ID が違うので戻らない
        val again = vfs.mount(p("/disk"), TestMemoryFileSystem(), readOnly = false, source = "disk:1")
        assertNotEquals(m.id, again.id)
        assertErrno(Errno.IO) { vfs.write(f, ByteArray(1), 0, 1) }
        assertErrno(Errno.INVAL) { vfs.unmount(m.id) }
    }

    @Test
    fun `消したファイルのnodeはSTALE`() {
        val (vfs, fs) = vfsWithRoot()
        val f = vfs.open(p("/x"), create)
        vfs.removeFile(p("/x"))
        assertErrno(Errno.STALE) { vfs.read(f, ByteArray(1), 0, 1) }
        vfs.writeText("/y", "y")
        vfs.sync(vfs.open(p("/y"), OpenOptions()))
        assertEquals(1, fs.syncCount)
    }

    @Test
    fun `復元したマウントの表にファイルシステムをつなぎ直す`() {
        val (vfs, fs) = vfsWithRoot()
        vfs.writeText("/keep", "kept")
        val f = vfs.open(p("/keep"), OpenOptions())
        // 保存の対象（マウントの表と OpenFile）だけを残し、実行時のものを作り直す
        val restored = Vfs(vfs.mounts)
        assertErrno(Errno.IO) { restored.stat(p("/keep")) }
        restored.attach(vfs.mounts.at(VfsPath.ROOT)!!.id, fs)
        val buf = ByteArray(4)
        assertEquals(4, restored.read(f, buf, 0, 4))
        assertEquals("kept", String(buf))
    }
}
