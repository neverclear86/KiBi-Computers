package pro.ikili.kibicomputers.core.kernel.vfs

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.device.DeviceInfo
import pro.ikili.kibicomputers.core.kernel.EntropySource

/** デバイスファイルの `/dev`（#485・#54） */
class DevFileSystemTest {
    private fun p(text: String) = VfsPath.parse(text)

    private fun vfs(fs: DevFileSystem = DevFileSystem()): Vfs = Vfs().also {
        it.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        it.mount(p("/dev"), fs, readOnly = false, source = "dev")
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
        assertSame(SpecialFile.Terminal, v.specialLocked(tty))
        assertNull(v.specialLocked(v.open(p("/dev/null"), OpenOptions())))
        assertNull(v.specialLocked(v.open(p("/dev"), OpenOptions(directory = true))))
        assertEquals(Errno.NXIO, errno { v.read(tty, ByteArray(1), 0, 1) })
    }

    @Test
    fun `中身は決まっていて、作る・消す・名前を変えるはできない`() {
        val v = vfs()
        val dir = v.open(p("/dev"), OpenOptions(directory = true))
        assertEquals(listOf("device", "null", "random", "tty", "urandom", "zero"), v.readDirectory(dir).map { it.name })
        assertTrue(v.readDirectory(dir).filter { it.name != "device" }.all { it.type == FileType.CHARACTER_DEVICE })
        assertEquals(Errno.NOENT, errno { v.stat(p("/dev/nothing")) })
        assertEquals(Errno.NOTDIR, errno { v.stat(p("/dev/null/x")) })
        assertEquals(Errno.ACCES, errno { v.open(p("/dev/new"), OpenOptions(write = true, create = true)) })
        assertEquals(Errno.ACCES, errno { v.createDirectory(p("/dev/d")) })
        assertEquals(Errno.ACCES, errno { v.removeFile(p("/dev/null")) })
        assertEquals(Errno.ACCES, errno { v.rename(p("/dev/null"), p("/dev/nil")) })
        assertEquals(Errno.NOTDIR, errno { v.open(p("/dev/null"), OpenOptions(directory = true)) })
        // touch /dev/null は通る
        v.setTimes(p("/dev/null"), 1, 2)
    }

    @Test
    fun `usageはすべて0で、項目の上限もsome(0)`() {
        assertEquals(FsUsage(0, 0, 0, 0, 0), DevFileSystem().usage())
    }

    @Test
    fun `randomとurandomは乱数の元で埋まり、書くと捨てる`() {
        var calls = 0
        val entropy = EntropySource { bytes ->
            calls++
            bytes.fill(0x5A)
        }
        val v = vfs(DevFileSystem(entropy = entropy))
        for (name in listOf("random", "urandom")) {
            assertEquals(FileType.CHARACTER_DEVICE, v.stat(p("/dev/$name")).type)
            val f = v.open(p("/dev/$name"), OpenOptions(read = true, write = true))
            val whole = ByteArray(8)
            assertEquals(8, v.read(f, whole, 0, 8))
            assertArrayEquals(ByteArray(8) { 0x5A }, whole)
            // 一部だけ埋める
            val part = ByteArray(6) { 1 }
            assertEquals(3, v.read(f, part, 2, 3))
            assertArrayEquals(byteArrayOf(1, 1, 0x5A, 0x5A, 0x5A, 1), part)
            assertEquals(4, v.write(f, ByteArray(4), 0, 4))
        }
        assertEquals(4, calls)
        // 既定の元でも読める（同じ値が続かない）
        val dv = vfs()
        val r = dv.open(p("/dev/random"), OpenOptions(read = true))
        val a = ByteArray(32)
        val b = ByteArray(32)
        assertEquals(32, dv.read(r, a, 0, 32))
        assertEquals(32, dv.read(r, b, 0, 32))
        assertFalse(a.contentEquals(b))
    }

    private class Devices : DeviceDirectory {
        val table = LinkedHashMap<String, String>()

        override fun list(): List<DeviceInfo> = table.map { DeviceInfo(it.key, it.value) }

        override fun kindOf(address: String): String? = table[address]
    }

    private val a1 = "123e4567-e89b-12d3-a456-426614174000"
    private val a2 = "00000000-0000-4000-8000-000000000001"

    @Test
    fun `deviceはつないでいるデバイスのaddressを並べる`() {
        val devices = Devices()
        val fs = DevFileSystem(devices = devices)
        val v = vfs(fs)
        assertEquals(FileType.DIRECTORY, v.stat(p("/dev/device")).type)
        val dir = v.open(p("/dev/device"), OpenOptions(directory = true))
        assertEquals(emptyList<DirEntry>(), v.readDirectory(dir))
        devices.table[a1] = "kibi:screen"
        devices.table[a2] = "kibi:keyboard"
        assertEquals(listOf(a2, a1), v.readDirectory(dir).map { it.name })
        assertTrue(v.readDirectory(dir).all { it.type == FileType.CHARACTER_DEVICE })
        val st = v.stat(p("/dev/device/$a1"))
        assertEquals(FileType.CHARACTER_DEVICE, st.type)
        assertEquals(DevFileSystem.deviceNode(a1), st.node)
        assertNotEquals(DevFileSystem.deviceNode(a1), DevFileSystem.deviceNode(a2))
        assertTrue(DevFileSystem.deviceNode(a1) > DevFileSystem.DEVICE_DIR_NODE, "固定の node と重ならない")
        assertEquals(st.node, fs.statNode(st.node).node)
        // 開くと差し替え先はデバイス。外れたら Detached、node は STALE
        val f = v.open(p("/dev/device/$a1"), OpenOptions(read = true, write = true, truncate = true))
        assertEquals(SpecialFile.Device(a1, "kibi:screen"), v.specialLocked(f))
        assertEquals(Errno.NXIO, errno { v.read(f, ByteArray(1), 0, 1) })
        devices.table.remove(a1)
        assertSame(SpecialFile.Detached, v.specialLocked(f))
        assertEquals(Errno.STALE, errno { fs.statNode(st.node) })
        assertEquals(Errno.NOENT, errno { v.stat(p("/dev/device/$a1")) })
    }

    @Test
    fun `deviceの中は知らない名前がNOENTで、作る・消すはできない`() {
        val devices = Devices().also { it.table[a1] = "kibi:screen" }
        val v = vfs(DevFileSystem(devices = devices))
        assertEquals(Errno.NOENT, errno { v.stat(p("/dev/device/${a1.uppercase()}")) }, "大文字は別の名前")
        assertEquals(Errno.NOENT, errno { v.stat(p("/dev/device/not-a-uuid")) })
        assertEquals(Errno.NOENT, errno { v.stat(p("/dev/device/$a2")) })
        assertEquals(Errno.NOTDIR, errno { v.stat(p("/dev/device/$a1/x")) })
        assertEquals(Errno.NOENT, errno { v.stat(p("/dev/device/$a2/x")) })
        assertEquals(Errno.ACCES, errno { v.open(p("/dev/device/new"), OpenOptions(write = true, create = true)) })
        assertEquals(Errno.ACCES, errno { v.removeFile(p("/dev/device/$a1")) })
        assertEquals(Errno.ACCES, errno { v.removeDirectory(p("/dev/device")) })
        assertEquals(Errno.ISDIR, errno { v.removeFile(p("/dev/device")) })
        assertEquals(Errno.NOTDIR, errno { v.removeDirectory(p("/dev/null")) })
        assertEquals(Errno.ACCES, errno { v.rename(p("/dev/device/$a1"), p("/dev/device/x")) })
        assertEquals(Errno.ISDIR, errno { v.open(p("/dev/device"), OpenOptions(write = true)) })
        // touch は通る
        v.setTimes(p("/dev/device/$a1"), 1, 2)
    }

    /** [a1] と node が同じになる address（上位と下位の 64 ビットの入れ替え・ビット 62 だけ違うもの。#693） */
    private val swapped = "a4564266-1417-4000-123e-4567e89b12d3"
    private val bit62 = "523e4567-e89b-12d3-a456-426614174000"

    @Test
    fun `nodeが同じになる2つのデバイスは、開いたパスの名前のデバイスに差し替わる`() {
        for (b in listOf(swapped, bit62)) {
            assertEquals(DevFileSystem.deviceNode(a1), DevFileSystem.deviceNode(b), "前提: node が衝突する")
            // 先に a1 を入れる（node で引くと、先に見つかる a1 が開いてしまう）
            val devices = Devices().also {
                it.table[a1] = "kibi:screen"
                it.table[b] = "kibi:keyboard"
            }
            val v = vfs(DevFileSystem(devices = devices))
            val fb = v.open(p("/dev/device/$b"), OpenOptions(read = true, write = true))
            assertEquals(SpecialFile.Device(b, "kibi:keyboard"), v.specialLocked(fb))
            val fa = v.open(p("/dev/device/$a1"), OpenOptions(read = true, write = true))
            assertEquals(SpecialFile.Device(a1, "kibi:screen"), v.specialLocked(fa))
            // 一覧と stat では同じ node に見える（既知の制約）
            assertEquals(v.stat(p("/dev/device/$a1")), v.stat(p("/dev/device/$b")))
            assertEquals(FileType.CHARACTER_DEVICE, v.stat(p("/dev/device/$b")).type)
        }
    }

    @Test
    fun `開いてから差し替えるまでに外れたら、nodeが同じ別のデバイスが残っていてもDetached`() {
        val devices = Devices().also {
            it.table[a1] = "kibi:screen"
            it.table[swapped] = "kibi:keyboard"
        }
        val fs = DevFileSystem(devices = devices)
        val v = vfs(fs)
        val node = DevFileSystem.deviceNode(swapped)
        val f = v.open(p("/dev/device/$swapped"), OpenOptions(read = true))
        devices.table.remove(swapped)
        assertSame(SpecialFile.Detached, v.specialLocked(f))
        // 片方が残っていれば node は生きている。両方外れたら STALE
        assertEquals(node, fs.statNode(node).node)
        devices.table.remove(a1)
        assertEquals(Errno.STALE, errno { fs.statNode(node) })
    }

    @Test
    fun `specialは名前がaddressの形でないかnodeと合わなければDetachedで、例外を投げない`() {
        val devices = Devices().also {
            it.table[a1] = "kibi:screen"
            it.table["NOT-A-UUID"] = "kibi:screen"
        }
        val fs = DevFileSystem(devices = devices)
        val node = DevFileSystem.deviceNode(a1)
        assertEquals(SpecialFile.Device(a1, "kibi:screen"), fs.special(node, a1))
        assertSame(SpecialFile.Detached, fs.special(node, a2), "node と合わない名前")
        assertSame(SpecialFile.Detached, fs.special(node, a1.uppercase()))
        assertSame(SpecialFile.Detached, fs.special(node, "NOT-A-UUID"))
        assertSame(SpecialFile.Detached, fs.special(node, ""))
        assertSame(SpecialFile.Terminal, fs.special(DevFileSystem.TTY_NODE, "tty"))
        assertNull(fs.special(DevFileSystem.NULL_NODE, "null"))
    }
}
