package pro.ikili.kibicomputers.core.kernel.fd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.KernelState
import pro.ikili.kibicomputers.core.kernel.RecordingSink
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleEntry
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.obj.KernelObject
import pro.ikili.kibicomputers.core.kernel.process.DEFAULT_HANDLE_LIMIT
import pro.ikili.kibicomputers.core.kernel.process.ModuleHash
import pro.ikili.kibicomputers.core.kernel.process.Process
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.Vfs
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.wait.WakeSink

/** fd 表の操作（#58）: いちばん小さい番号、dup・dup2・renumber・close と参照カウント、上限、読み書きの振り分け */
class FdTableTest {
    private class Obj : KernelObject() {
        var closed = 0

        override fun onLastClose(sink: WakeSink) {
            closed++
        }
    }

    private val kernel = KernelState(8)
    private val sink = RecordingSink()
    private val process = Process(1, 0, ProcessKind.BLOCKING, ModuleHash.of(byteArrayOf(1)), false).also { kernel.processes.add(it) }
    private val fds = FdTable(process, kernel.objects, sink)

    private fun errno(block: () -> Unit): Errno = assertThrows<FsException> { block() }.errno

    @Test
    fun `openはいちばん小さい空き番号を使い、閉じた番号を使い回す`() {
        assertEquals(0, fds.open(Obj()))
        assertEquals(1, fds.open(Obj()))
        assertEquals(2, fds.open(Obj()))
        fds.close(1)
        assertEquals(1, fds.open(Obj()))
        assertEquals(3, fds.size)
    }

    @Test
    fun `dupは同じオブジェクトを指し、両方閉じたときだけonLastClose`() {
        val obj = Obj()
        val a = fds.open(obj, HandleRights.READ, flags = 5)
        val b = fds.dup(a)
        assertEquals(1, b)
        assertSame(obj, fds.objectOf(b))
        assertEquals(HandleEntry(obj.id, 5, HandleRights.READ), fds.entry(b), "権利とフラグも写す")
        assertEquals(2, kernel.objects.refCount(obj.id))
        fds.close(a)
        assertEquals(0, obj.closed)
        fds.close(b)
        assertEquals(1, obj.closed)
        assertNull(kernel.objects[obj.id])
        assertEquals(listOf(obj.id), sink.objects)
    }

    @Test
    fun `dup2は先を閉じてから置き換え、同じ番号なら何もしない`() {
        val a = Obj()
        val b = Obj()
        val fa = fds.open(a)
        val fb = fds.open(b)
        assertEquals(fb, fds.dup2(fa, fb))
        assertEquals(1, b.closed, "置き換えられた側は閉じる")
        assertSame(a, fds.objectOf(fb))
        assertEquals(2, kernel.objects.refCount(a.id))
        assertEquals(fa, fds.dup2(fa, fa))
        assertEquals(2, kernel.objects.refCount(a.id))
        // 空いている番号へも、離れた番号へも
        assertEquals(40, fds.dup2(fa, 40))
        assertSame(a, fds.objectOf(40))
        assertEquals(3, kernel.objects.refCount(a.id))
        // 自分自身の複製に dup2 しても、オブジェクトは閉じない
        fds.dup2(fb, fa)
        assertEquals(0, a.closed)
        assertEquals(3, kernel.objects.refCount(a.id))
    }

    @Test
    fun `renumberは移して元を閉じ、先が開いていれば閉じる`() {
        val a = Obj()
        val b = Obj()
        val fa = fds.open(a)
        val fb = fds.open(b)
        fds.renumber(fa, fb)
        assertEquals(1, b.closed)
        assertSame(a, fds.objectOf(fb))
        assertEquals(Errno.BADF, errno { fds.entry(fa) })
        assertEquals(1, kernel.objects.refCount(a.id))
        fds.renumber(fb, 7)
        assertSame(a, fds.objectOf(7))
        assertEquals(1, fds.size)
        fds.renumber(7, 7)
        assertEquals(1, kernel.objects.refCount(a.id))
    }

    @Test
    fun `開いていないfdと範囲外はBADFで、表は変わらない`() {
        val fa = fds.open(Obj())
        assertEquals(Errno.BADF, errno { fds.close(5) })
        assertEquals(Errno.BADF, errno { fds.dup(5) })
        assertEquals(Errno.BADF, errno { fds.dup2(5, 0) })
        assertEquals(Errno.BADF, errno { fds.dup2(fa, -1) })
        assertEquals(Errno.BADF, errno { fds.dup2(fa, DEFAULT_HANDLE_LIMIT) })
        assertEquals(Errno.BADF, errno { fds.renumber(5, fa) })
        assertEquals(Errno.BADF, errno { fds.renumber(fa, DEFAULT_HANDLE_LIMIT) })
        assertEquals(1, fds.size)
        assertEquals(1, kernel.objects.refCount(fds.entry(fa).objectId))
    }

    @Test
    fun `上限ではMFILEで、openは表に足さず、installは参照を呼び出し側に残す`() {
        repeat(DEFAULT_HANDLE_LIMIT) { fds.open(Obj()) }
        val before = kernel.objects.size
        assertEquals(Errno.MFILE, errno { fds.open(Obj()) })
        assertEquals(before, kernel.objects.size)
        assertEquals(Errno.MFILE, errno { fds.dup(0) })
        assertEquals(1, kernel.objects.refCount(fds.entry(0).objectId))
        val id = kernel.objects.add(Obj())
        assertEquals(Errno.MFILE, errno { fds.install(id) })
        assertEquals(1, kernel.objects.refCount(id))
        // dup2 は番号を増やさないので、上限でもできる
        assertEquals(3, fds.dup2(0, 3))
    }

    @Test
    fun `closeAllはすべて閉じ、共有している相手のオブジェクトは残る`() {
        val shared = Obj()
        val sharedId = kernel.objects.add(shared)
        kernel.objects.retain(sharedId) // ほかのプロセスが持っている分
        fds.install(sharedId)
        val own = Obj()
        fds.open(own)
        fds.dup(1)
        fds.closeAll()
        assertEquals(0, fds.size)
        assertEquals(1, own.closed)
        assertEquals(0, shared.closed)
        assertEquals(1, kernel.objects.refCount(sharedId))
    }

    @Test
    fun `ByteStreamの読み書きは権利を確かめ、空ならWouldBlock、閉じたらEofとPIPE`() {
        val vfs = Vfs(kernel.mounts)
        val stream = ByteStream(4)
        val r = fds.open(stream, HandleRights.READ)
        // 同じストリームを書く権利で指す fd（dup は権利を写すので install で作る）
        kernel.objects.retain(stream.id)
        val wfd = fds.install(stream.id, HandleRights.WRITE)
        val buf = ByteArray(8)
        assertEquals(IoResult.WouldBlock(WaitKey(stream.id, WaitCondition.READABLE)), FdIo.readLocked(kernel, vfs, sink, 1, r, buf, 0, 8))
        assertEquals(IoResult.Error(Errno.BADF), FdIo.writeLocked(kernel, vfs, sink, 1, r, buf, 0, 1))
        assertEquals(IoResult.Error(Errno.BADF), FdIo.readLocked(kernel, vfs, sink, 1, wfd, buf, 0, 1))
        assertEquals(IoResult.Bytes(4), FdIo.writeLocked(kernel, vfs, sink, 1, wfd, "abcdef".toByteArray(), 0, 6))
        assertEquals(IoResult.WouldBlock(WaitKey(stream.id, WaitCondition.WRITABLE)), FdIo.writeLocked(kernel, vfs, sink, 1, wfd, buf, 0, 1))
        assertEquals(IoResult.Bytes(4), FdIo.readLocked(kernel, vfs, sink, 1, r, buf, 0, 8))
        assertEquals("abcd", String(buf, 0, 4))
        assertEquals(IoResult.Error(Errno.FAULT), FdIo.readLocked(kernel, vfs, sink, 1, r, buf, 4, 5))
        assertEquals(IoResult.Error(Errno.BADF), FdIo.readLocked(kernel, vfs, sink, 1, 99, buf, 0, 1))
        stream.closeWrite(sink)
        assertEquals(IoResult.Eof, FdIo.readLocked(kernel, vfs, sink, 1, r, buf, 0, 8))
        stream.closeRead(sink)
        assertEquals(IoResult.Error(Errno.PIPE), FdIo.writeLocked(kernel, vfs, sink, 1, wfd, buf, 0, 1))
    }

    @Test
    fun `OpenFileの複製は位置を共有し、ファイルの終わりはEof`() {
        val vfs = Vfs(kernel.mounts)
        vfs.mount(VfsPath.parse("/"), TestMemoryFileSystem(), readOnly = false, source = "tmp")
        val a = fds.open(vfs.open(VfsPath.parse("/f"), OpenOptions(read = true, write = true, create = true)))
        val b = fds.dup(a)
        assertEquals(IoResult.Bytes(3), FdIo.writeLocked(kernel, vfs, sink, 1, a, "xyz".toByteArray(), 0, 3))
        assertEquals(IoResult.Bytes(2), FdIo.writeLocked(kernel, vfs, sink, 1, b, "12".toByteArray(), 0, 2))
        val c = fds.open(vfs.open(VfsPath.parse("/f"), OpenOptions()))
        val buf = ByteArray(16)
        assertEquals(IoResult.Bytes(5), FdIo.readLocked(kernel, vfs, sink, 1, c, buf, 0, 16))
        assertEquals("xyz12", String(buf, 0, 5))
        assertEquals(IoResult.Eof, FdIo.readLocked(kernel, vfs, sink, 1, c, buf, 0, 16))
        assertEquals(IoResult.Error(Errno.BADF), FdIo.writeLocked(kernel, vfs, sink, 1, c, buf, 0, 1), "読み取り専用で開いたファイル")
        // 普通の KernelObject は読み書きできない
        val o = fds.open(Obj())
        assertEquals(IoResult.Error(Errno.INVAL), FdIo.readLocked(kernel, vfs, sink, 1, o, buf, 0, 1))
    }
}
