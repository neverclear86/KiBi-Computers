package pro.ikili.kibicomputers.core.kernel.vfs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.MemoryAccount
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** VFS をカーネルロックの外で、いくつものスレッドから使う（#52。ADR 0052） */
class VfsLockTest {
    private fun p(s: String) = VfsPath.parse(s)

    @Test
    fun `ロックを持ったままファイルシステムに届くとIllegalStateException`() {
        var held = false
        val vfs = Vfs { held }
        vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test")
        vfs.createDirectory(p("/a"))
        held = true
        assertThrows<IllegalStateException> { vfs.stat(p("/a")) }
        assertThrows<IllegalStateException> { vfs.checkMountPoint(p("/a")) }
        // 表を変えるだけの操作と sizeHint は、ロックの中でよい
        val e = vfs.mountLocked(p("/a"), TestMemoryFileSystem(), readOnly = false, source = "x")
        held = false
        val f = vfs.open(p("/a/f"), OpenOptions(read = true, write = true, create = true))
        vfs.pwrite(f, 0, ByteArray(3), 0, 3)
        held = true
        assertEquals(3, vfs.sizeHintLocked(f))
        vfs.unmount(e.id)
    }

    @Test
    fun `マウントの確かめはロックの外で、根のディレクトリでないものにはマウントしない`() {
        val vfs = Vfs()
        val root = TestMemoryFileSystem()
        vfs.mount(VfsPath.ROOT, root, readOnly = false, source = "test")
        root.createFile(p("/tmp"))
        val e = assertThrows<FsException> { vfs.checkMountPoint(p("/tmp")) }
        assertEquals(Errno.NOTDIR, e.errno)
    }

    private fun concurrently(threads: Int, rounds: Int, body: (Int, Int) -> Unit) {
        val pool = Executors.newFixedThreadPool(threads)
        val barrier = CyclicBarrier(threads)
        try {
            val futures = (0 until threads).map { t ->
                pool.submit {
                    for (r in 0 until rounds) {
                        barrier.await(10, TimeUnit.SECONDS)
                        body(t, r)
                    }
                }
            }
            for (f in futures) f.get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun tmpVfs(): Vfs = Vfs().also {
        it.mount(VfsPath.ROOT, TmpFileSystem(MemoryAccount(1 shl 20)) { 0L }, readOnly = false, source = "tmp")
    }

    @Test
    fun `同じファイルを同時にO_CREATで開いても、O_EXCLでなければEXISTにならない`() {
        val vfs = tmpVfs()
        concurrently(4, 50) { _, r ->
            vfs.open(p("/f$r"), OpenOptions(read = true, write = true, create = true, append = true))
        }
        val wins = java.util.concurrent.atomic.AtomicInteger()
        concurrently(4, 20) { _, r ->
            try {
                vfs.open(p("/x$r"), OpenOptions(write = true, create = true, exclusive = true))
                wins.incrementAndGet()
            } catch (e: FsException) {
                assertEquals(Errno.EXIST, e.errno)
            }
        }
        assertEquals(20, wins.get(), "O_EXCL はひとつだけ成功する")
    }

    @Test
    fun `同時の追記は重ならず、allocateは同時の書き込みを縮めない`() {
        val vfs = tmpVfs()
        val f = vfs.open(p("/log"), OpenOptions(read = true, write = true, create = true, append = true))
        concurrently(4, 100) { t, _ ->
            val line = "t$t;".toByteArray()
            vfs.append(f, line, 0, line.size)
        }
        val size = vfs.size(f)
        assertEquals(4L * 100 * 3, size)
        val buf = ByteArray(size.toInt())
        vfs.pread(f, 0, buf, 0, buf.size)
        assertTrue(String(buf).split(';').filter { it.isNotEmpty() }.all { it.matches(Regex("t[0-3]")) }, "追記が混ざっていない")
        // allocate は伸ばすだけ
        vfs.allocate(f, 10)
        assertEquals(size, vfs.size(f))
        val g = vfs.open(p("/g"), OpenOptions(read = true, write = true, create = true))
        concurrently(2, 200) { t, r ->
            if (t == 0) vfs.pwrite(g, r * 10L, ByteArray(10), 0, 10) else vfs.allocate(g, r * 5L)
        }
        assertEquals(2000, vfs.size(g))
    }

    @Test
    fun `閉じたファイルの知らせは、ロックの外で行うまで積んでおく`() {
        val released = ArrayList<Long>()
        var retired = false
        val fs = object : FileSystem by TestMemoryFileSystem() {
            override val tracksOpenFiles: Boolean get() = true
            val opened = HashMap<Long, Int>()

            override fun retain(node: Long, writable: Boolean) {
                opened.merge(node, 1, Int::plus)
            }

            override fun release(node: Long) {
                released += node
            }

            override fun retire() {
                retired = true
            }
        }
        var held = false
        val vfs = Vfs { held }
        var notified = 0
        vfs.onDeferred = { notified++ }
        val entry = vfs.mount(VfsPath.ROOT, fs, readOnly = false, source = "test")
        val f = vfs.open(p("/f"), OpenOptions(read = true, write = true, create = true))
        assertTrue(f.retained)
        held = true
        vfs.releaseLater(f)
        vfs.drainDeferred()
        assertTrue(released.isEmpty(), "ロックの中では知らせない")
        assertEquals(1, notified)
        held = false
        vfs.drainDeferred()
        assertEquals(listOf(f.node), released)
        assertFalse(f.retained)
        // 外したら retire をロックの外で
        held = true
        vfs.unmount(entry.id)
        assertFalse(retired)
        held = false
        vfs.drainDeferred()
        assertTrue(retired)
        // ディレクトリは知らせない
        assertFalse(vfs.hasDeferred)
    }
}
