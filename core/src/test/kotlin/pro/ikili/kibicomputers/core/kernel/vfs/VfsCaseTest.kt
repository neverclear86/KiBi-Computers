package pro.ikili.kibicomputers.core.kernel.vfs

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pro.ikili.kibicomputers.core.kernel.MemoryAccount
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * マウントの位置を、大文字小文字を区別しない FS（ディスク）の中では区別せずに比べる（#606。ADR 0051・0052）。
 * 名前の比べ方は [DiskFileSystem] の規則に頼り、ホストの規則には頼らない（Windows でも Linux でも同じに動く）
 */
class VfsCaseTest {
    @TempDir
    lateinit var dir: Path

    private val opened = ArrayList<DiskFileSystem>()

    @AfterEach
    fun closeAll() {
        for (fs in opened) fs.retire()
    }

    private fun p(text: String) = VfsPath.parse(text)

    private val create = OpenOptions(read = true, write = true, create = true)

    private fun disk(name: String = "disk"): DiskFileSystem =
        DiskFileSystem.open(dir.resolve(name), budget = HostFileBudget(8, 16, AtomicInteger())).also { opened += it }

    private fun tmpfs(): TmpFileSystem = TmpFileSystem(MemoryAccount(4)) { 0L }

    private fun Vfs.writeText(path: String, text: String) {
        val f = open(p(path), create.copy(truncate = true))
        val bytes = text.toByteArray()
        assertEquals(bytes.size, write(f, bytes, 0, bytes.size))
        close(f)
    }

    private fun Vfs.readText(path: String): String {
        val f = open(p(path), OpenOptions())
        val buf = ByteArray(1024)
        val n = read(f, buf, 0, buf.size)
        close(f)
        return String(buf, 0, n)
    }

    private fun Vfs.names(path: String): List<String> = readDirectory(open(p(path), OpenOptions())).map { it.name }

    /** 根がディスク、`/tmp` が tmpfs のマシンと同じ形 */
    private class Setup(val vfs: Vfs, val root: DiskFileSystem, val rootMount: MountEntry, val tmpMount: MountEntry)

    private fun setup(): Setup {
        val vfs = Vfs()
        val root = disk()
        val rootMount = vfs.mount(VfsPath.ROOT, root, readOnly = false, source = "disk:root")
        val tmpMount = vfs.mount(p("/tmp"), tmpfs(), readOnly = false, source = "tmp")
        return Setup(vfs, root, rootMount, tmpMount)
    }

    @Test
    fun `ディスクの中では大文字小文字の違うパスもマウントに属する`() {
        val s = setup()
        val vfs = s.vfs
        // ディスクに tmp があっても、/TMP/x は tmpfs にでき、ディスクには届かない
        s.root.createDirectory(p("/tmp"))
        vfs.writeText("/TMP/x", "on tmpfs")
        assertEquals("on tmpfs", vfs.readText("/tmp/x"))
        assertEquals(s.tmpMount.id.toLong(), vfs.stat(p("/TMP/x")).device)
        assertEquals(s.tmpMount.id.toLong(), vfs.stat(p("/TMP")).device)
        assertErrno(Errno.NOENT) { s.root.stat(p("/tmp/x")) }
        // マウントの位置として扱う
        vfs.writeText("/a", "a")
        assertErrno(Errno.BUSY) { vfs.rename(p("/TMP"), p("/z")) }
        assertErrno(Errno.BUSY) { vfs.rename(p("/a"), p("/Tmp")) }
        assertErrno(Errno.XDEV) { vfs.rename(p("/a"), p("/TMP/a")) }
        assertErrno(Errno.BUSY) { vfs.removeDirectory(p("/TMP")) }
        assertErrno(Errno.ISDIR) { vfs.removeFile(p("/TMP")) }
        assertErrno(Errno.EXIST) { vfs.createDirectory(p("/TMP")) }
        assertErrno(Errno.BUSY) { vfs.mount(p("/TMP"), TestMemoryFileSystem(), readOnly = false, source = "x") }
        // 綴りは指定されたまま
        assertEquals(p("/tmp"), vfs.mounts.at(p("/TMP"))?.point)
    }

    @Test
    fun `大文字小文字を区別するFSの中では区別する`() {
        val s = setup()
        val vfs = s.vfs
        val lower = vfs.mount(p("/tmp/a"), TestMemoryFileSystem(), readOnly = false, source = "a")
        // tmpfs の中の /tmp/A は /tmp/a のマウントに一致しない
        vfs.createDirectory(p("/tmp/A"))
        vfs.writeText("/tmp/A/x", "tmpfs")
        assertEquals(s.tmpMount.id.toLong(), vfs.stat(p("/tmp/A/x")).device)
        assertEquals(lower.id.toLong(), vfs.stat(p("/TMP/a")).device)
        // /tmp/A にも別にマウントできる
        val upper = vfs.mount(p("/tmp/A"), TestMemoryFileSystem(), readOnly = false, source = "A")
        assertEquals(upper.id.toLong(), vfs.stat(p("/TMP/A")).device)
        assertEquals(listOf("A", "a"), vfs.names("/tmp"))
    }

    @Test
    fun `根に何もマウントしていなければ区別する`() {
        val vfs = Vfs()
        val tmp = vfs.mount(p("/tmp"), tmpfs(), readOnly = false, source = "tmp")
        assertEquals(tmp.id.toLong(), vfs.stat(p("/tmp")).device)
        assertErrno(Errno.NOENT) { vfs.stat(p("/TMP")) }
        vfs.mount(p("/TMP"), tmpfs(), readOnly = false, source = "tmp2")
        assertEquals(listOf("TMP", "tmp"), vfs.names("/"))
    }

    @Test
    fun `readdirではそろえると同じ名前を1つだけ見せる`() {
        val vfs = Vfs()
        val root = disk()
        vfs.mount(VfsPath.ROOT, root, readOnly = false, source = "disk:root")
        // ホストで外から TMP を作った
        Files.createDirectory(root.root.resolve("TMP"))
        val tmp = vfs.mount(p("/tmp"), tmpfs(), readOnly = false, source = "tmp")
        // 古い表のように、大文字小文字だけ違う祖先を持つマウント
        val a = vfs.mount(p("/mnt/a"), TestMemoryFileSystem(), readOnly = false, source = "a")
        val b = vfs.mount(p("/MNT/b"), TestMemoryFileSystem(), readOnly = false, source = "b")
        assertEquals(listOf("mnt", "tmp"), vfs.names("/"))
        val entries = vfs.readDirectory(vfs.open(VfsPath.ROOT, OpenOptions()))
        assertEquals(FileType.DIRECTORY, entries.single { it.name == "tmp" }.type)
        assertEquals(tmp.id.toLong(), vfs.stat(p("/TMP")).device)
        // /mnt は合成のディレクトリだが、根のディスクの下なので、名前を引くときは区別しない（ADR 0051）
        assertEquals(listOf("a", "b"), vfs.names("/mnt"))
        assertEquals(a.id.toLong(), vfs.stat(p("/Mnt/A")).device)
        assertEquals(b.id.toLong(), vfs.stat(p("/mnt/B")).device)
    }

    @Test
    fun `ディスクにない祖先は合成のディレクトリとして見える`() {
        val vfs = Vfs()
        vfs.mount(VfsPath.ROOT, disk(), readOnly = false, source = "disk:root")
        val m = vfs.mount(p("/a/b"), TestMemoryFileSystem(), readOnly = false, source = "x")
        val ancestor = vfs.stat(p("/A"))
        assertEquals(FileType.DIRECTORY, ancestor.type)
        assertEquals(0, ancestor.device)
        assertEquals(m.id.toLong(), vfs.stat(p("/A/B")).device)
        assertEquals(listOf("b"), vfs.names("/A"))
        assertErrno(Errno.BUSY) { vfs.removeDirectory(p("/A")) }
    }

    @Test
    fun `区別しない親を強制的に外すと、残ったマウントは区別して比べる`() {
        val vfs = Vfs()
        val root = vfs.mount(VfsPath.ROOT, disk(), readOnly = false, source = "disk:root")
        val x = vfs.mount(p("/mnt/x"), TestMemoryFileSystem(), readOnly = false, source = "x")
        assertEquals(x.id.toLong(), vfs.stat(p("/MNT/X")).device)
        vfs.unmountForcedLocked(root.id)
        assertErrno(Errno.NOENT) { vfs.stat(p("/MNT/X")) }
        assertEquals(x.id.toLong(), vfs.stat(p("/mnt/x")).device)
    }

    @Test
    fun `マウントの表の下の確かめも大文字小文字の規則に従う`() {
        val s = setup()
        val vfs = s.vfs
        val nested = vfs.mount(p("/TMP/x"), TestMemoryFileSystem(), readOnly = false, source = "x")
        val table = vfs.mounts
        assertTrue(table.isAtOrBelow(nested, s.tmpMount))
        assertTrue(table.isAtOrBelow(s.tmpMount, s.tmpMount))
        assertFalse(table.isAtOrBelow(s.tmpMount, nested))
        // 下にマウントがあれば外せない
        assertErrno(Errno.BUSY) { vfs.unmount(s.tmpMount.id) }
        vfs.unmount(nested.id)
        vfs.unmount(s.tmpMount.id)
        // tmpfs の中は区別する
        val tmp2 = vfs.mount(p("/tmp"), tmpfs(), readOnly = false, source = "tmp")
        val lower = vfs.mount(p("/tmp/a"), TestMemoryFileSystem(), readOnly = false, source = "a")
        val upper = vfs.mount(p("/tmp/A/y"), TestMemoryFileSystem(), readOnly = false, source = "y")
        assertTrue(table.isAtOrBelow(upper, tmp2))
        assertFalse(table.isAtOrBelow(upper, lower))
        vfs.unmount(lower.id)
    }

    @Test
    fun `重なった古い表を戻しても例外にせず、決まった1つを選ぶ`() {
        val table = MountTable()
        val root = MountEntry(1, VfsPath.ROOT, false, "disk:root")
        val lower = MountEntry(2, p("/tmp"), false, "tmp")
        val upper = MountEntry(3, p("/TMP"), false, "disk:other")
        table.restore(listOf(root, lower, upper), 4)
        // FS をつなぐ前は区別する
        assertEquals(upper, table.covering(p("/TMP/x")))
        assertEquals(setOf("TMP", "tmp"), table.childNamesTowardMounts(VfsPath.ROOT))
        // 区別しない FS をつなぐと、足した順で先のものを選ぶ
        val vfs = Vfs(table)
        vfs.attach(root.id, disk())
        assertEquals(lower, vfs.mounts.covering(p("/TMP/x")))
        assertEquals(lower, vfs.mounts.at(p("/Tmp")))
        assertEquals(setOf("tmp"), vfs.mounts.childNamesTowardMounts(VfsPath.ROOT))
        vfs.attach(lower.id, tmpfs())
        vfs.attach(upper.id, TestMemoryFileSystem())
        assertEquals(lower.id.toLong(), vfs.stat(p("/TMP")).device)
        assertEquals(listOf("tmp"), vfs.names("/"))
        // 先のものを外せば、残ったほうが見える
        vfs.unmount(lower.id)
        assertEquals(upper.id.toLong(), vfs.stat(p("/tmp")).device)
    }
}
