package pro.ikili.kibicomputers.core.kernel.vfs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal fun assertErrno(expected: Errno, block: () -> Unit) {
    val e = assertThrows<FsException> { block() }
    assertEquals(expected, e.errno, e.message)
}

class VfsPathTest {
    private fun p(text: String) = VfsPath.parse(text)

    @Test
    fun `正規化する`() {
        assertEquals("/", p("/").toString())
        assertEquals("/a/b", p("/a//b/").toString())
        assertEquals("/a/c", p("/a/./b/../c").toString())
        assertEquals("/", p("/../..").toString())
        assertEquals("/b", p("/a/../../b").toString())
        assertEquals(listOf("a", "b"), p("a/b").segments)
        assertTrue(p("/").isRoot)
    }

    @Test
    fun `相対パスはbaseから解く`() {
        val base = p("/home/user")
        assertEquals(p("/home/user/x"), VfsPath.resolve(base, "x"))
        assertEquals(p("/home/other"), VfsPath.resolve(base, "../other"))
        assertEquals(p("/etc"), VfsPath.resolve(base, "/etc"))
        assertEquals(base, VfsPath.resolve(base, "."))
    }

    @Test
    fun `beneathはbaseの外へ出さない`() {
        val base = p("/disk")
        assertEquals(p("/disk/a"), VfsPath.resolveBeneath(base, "a/b/.."))
        assertEquals(base, VfsPath.resolveBeneath(base, "a/.."))
        assertErrno(Errno.NOTCAPABLE) { VfsPath.resolveBeneath(base, "..") }
        assertErrno(Errno.NOTCAPABLE) { VfsPath.resolveBeneath(base, "../disk/a") }
        assertErrno(Errno.NOTCAPABLE) { VfsPath.resolveBeneath(base, "/disk/a") }
    }

    @Test
    fun `おかしなパスはエラー`() {
        assertErrno(Errno.NOENT) { p("") }
        assertErrno(Errno.INVAL) { p("/a\u0000b") }
        assertErrno(Errno.ILSEQ) { p("/a\uD800") }
        assertErrno(Errno.NAMETOOLONG) { p("/" + "x".repeat(256)) }
        p("/" + "x".repeat(255))
        // 3 バイトの文字 86 個 = 258 バイト
        assertErrno(Errno.NAMETOOLONG) { p("/" + "あ".repeat(86)) }
        assertErrno(Errno.NAMETOOLONG) { p("/" + List(30) { "y".repeat(200) }.joinToString("/")) }
        assertErrno(Errno.INVAL) { VfsPath.ROOT.child("..") }
        assertErrno(Errno.INVAL) { VfsPath.ROOT.child("a/b") }
    }

    @Test
    fun `親と子と前方一致`() {
        val ab = p("/a/b")
        assertEquals(p("/a"), ab.parent)
        assertNull(VfsPath.ROOT.parent)
        assertEquals("b", ab.name)
        assertEquals(p("/a/b/c"), ab.child("c"))
        assertTrue(ab.startsWith(p("/a")))
        assertTrue(ab.startsWith(VfsPath.ROOT))
        assertFalse(p("/ab").startsWith(p("/a")))
        assertEquals(p("/b"), ab.relativeTo(p("/a")))
        assertEquals(VfsPath.ROOT, ab.relativeTo(ab))
    }

    @Test
    fun `末尾のスラッシュ`() {
        assertTrue(VfsPath.hasTrailingSlash("a/"))
        assertFalse(VfsPath.hasTrailingSlash("/"))
        assertFalse(VfsPath.hasTrailingSlash("a"))
    }

    @Test
    fun `errnoはWASI_p1の値`() {
        assertEquals(0, Errno.SUCCESS.code)
        assertEquals(2, Errno.ACCES.code)
        assertEquals(8, Errno.BADF.code)
        assertEquals(44, Errno.NOENT.code)
        assertEquals(54, Errno.NOTDIR.code)
        assertEquals(69, Errno.ROFS.code)
        assertEquals(76, Errno.NOTCAPABLE.code)
        Errno.entries.forEachIndexed { i, e -> assertEquals(i, e.code) }
        assertEquals(Errno.XDEV, Errno.ofCode(75))
        assertNull(Errno.ofCode(77))
    }
}
