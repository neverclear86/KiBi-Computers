package pro.ikili.kibicomputers.core.kernel.io

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.KernelState
import pro.ikili.kibicomputers.core.kernel.RecordingSink
import pro.ikili.kibicomputers.core.kernel.fd.FdIo
import pro.ikili.kibicomputers.core.kernel.fd.FdReadiness
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.fd.IoResult
import pro.ikili.kibicomputers.core.kernel.fd.PipeFds
import pro.ikili.kibicomputers.core.kernel.obj.HandleEntry
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.DEFAULT_HANDLE_LIMIT
import pro.ikili.kibicomputers.core.kernel.process.ModuleHash
import pro.ikili.kibicomputers.core.kernel.process.Process
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.Vfs
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition.READABLE
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition.WRITABLE
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey

/** パイプ（#59）: 作成と読み書き、バッファの上限と待ちの key、片側が閉じたときの EOF と PIPE、fd の数の上限 */
class PipeTest {
    private val kernel = KernelState(8)
    private val vfs = Vfs(kernel.mounts)
    private val sink = RecordingSink()
    private val process = Process(1, 0, ProcessKind.BLOCKING, ModuleHash.of(byteArrayOf(1)), false).also { kernel.processes.add(it) }
    private val fds = FdTable(process, kernel.objects, sink)

    private fun read(fd: Int, len: Int = 64): Pair<IoResult, String> {
        val buf = ByteArray(len)
        val r = FdIo.readLocked(kernel, vfs, sink, 1, fd, buf, 0, len)
        return r to (if (r is IoResult.Bytes) String(buf, 0, r.count) else "")
    }

    private fun write(fd: Int, text: String): IoResult {
        val b = text.toByteArray()
        return FdIo.writeLocked(kernel, vfs, sink, 1, fd, b, 0, b.size)
    }

    private fun streamId(p: PipeFds): Int = (fds.objectOf(p.read) as PipeEnd).streamId

    @Test
    fun `pipeは小さい空き番号に読む端と書く端を入れ、書いたものを読める`() {
        val p = fds.pipe()
        assertEquals(PipeFds(0, 1), p)
        val r = fds.objectOf(p.read) as PipeEnd
        val w = fds.objectOf(p.write) as PipeEnd
        assertTrue(r.reading)
        assertEquals(false, w.reading)
        assertEquals(r.streamId, w.streamId)
        assertEquals(HandleEntry(r.id, rights = HandleRights.READ), fds.entry(p.read))
        assertEquals(HandleEntry(w.id, rights = HandleRights.WRITE), fds.entry(p.write))
        // 中身は両端が1つずつ持つ
        assertEquals(2, kernel.objects.refCount(r.streamId))
        assertEquals(Pipes.DEFAULT_CAPACITY, (kernel.objects[r.streamId] as ByteStream).capacity)

        assertEquals(IoResult.Bytes(5), write(p.write, "hello"))
        assertEquals(IoResult.Bytes(3) to "hel", read(p.read, 3))
        assertEquals(IoResult.Bytes(2) to "lo", read(p.read))
        assertEquals(IoResult.WouldBlock(WaitKey(r.streamId, READABLE)), read(p.read).first)
    }

    @Test
    fun `向きの違う端は権利があってもBADF`() {
        val p = fds.pipe()
        assertEquals(IoResult.Error(Errno.BADF), write(p.read, "x"))
        assertEquals(IoResult.Error(Errno.BADF), read(p.write).first)
        // 権利をすべて付けて入れ直しても、向きは端で決まる
        val readEnd = fds.entry(p.read).objectId
        kernel.objects.retain(readEnd)
        val all = fds.install(readEnd, HandleRights.ALL)
        assertEquals(IoResult.Error(Errno.BADF), write(all, "x"))
        assertEquals(FdReadiness.Error(Errno.BADF), FdIo.pollLocked(kernel, vfs, 1, all, WRITABLE))
    }

    @Test
    fun `バッファの上限まで書け、いっぱいなら書く側が、空なら読む側が中身のkeyで待つ`() {
        val p = fds.pipe(capacity = 4)
        val id = streamId(p)
        assertEquals(FdReadiness.Pending(WaitKey(id, READABLE)), FdIo.pollLocked(kernel, vfs, 1, p.read, READABLE))
        assertEquals(FdReadiness.Ready(4), FdIo.pollLocked(kernel, vfs, 1, p.write, WRITABLE))
        sink.keys.clear()
        assertEquals(IoResult.Bytes(4), write(p.write, "abcdef"))
        assertEquals(listOf(WaitKey(id, READABLE)), sink.keys)
        assertEquals(IoResult.WouldBlock(WaitKey(id, WRITABLE)), write(p.write, "g"))
        assertEquals(FdReadiness.Pending(WaitKey(id, WRITABLE)), FdIo.pollLocked(kernel, vfs, 1, p.write, WRITABLE))
        assertEquals(FdReadiness.Ready(4), FdIo.pollLocked(kernel, vfs, 1, p.read, READABLE))
        sink.keys.clear()
        assertEquals(IoResult.Bytes(2) to "ab", read(p.read, 2))
        assertEquals(listOf(WaitKey(id, WRITABLE)), sink.keys)
        assertEquals(IoResult.Bytes(2), write(p.write, "gh"))
        assertEquals(IoResult.Bytes(4) to "cdgh", read(p.read))
    }

    @Test
    fun `書く端を閉じると、残りを読んだ後にEofで、読む側を起こす`() {
        val p = fds.pipe()
        val id = streamId(p)
        write(p.write, "bye")
        sink.keys.clear()
        fds.close(p.write)
        assertTrue(WaitKey(id, READABLE) in sink.keys)
        assertEquals(FdReadiness.Ready(3, hangup = true), FdIo.pollLocked(kernel, vfs, 1, p.read, READABLE))
        assertEquals(IoResult.Bytes(3) to "bye", read(p.read))
        assertEquals(IoResult.Eof, read(p.read).first)
        // 中身は読む端だけが持つ
        assertEquals(1, kernel.objects.refCount(id))
    }

    @Test
    fun `読む端を閉じると書き込みはPIPEで、書く側を起こす`() {
        val p = fds.pipe(capacity = 1)
        val id = streamId(p)
        write(p.write, "x")
        sink.keys.clear()
        fds.close(p.read)
        assertTrue(WaitKey(id, WRITABLE) in sink.keys)
        assertEquals(FdReadiness.Ready(0, hangup = true), FdIo.pollLocked(kernel, vfs, 1, p.write, WRITABLE))
        assertEquals(IoResult.Error(Errno.PIPE), write(p.write, "y"))
    }

    @Test
    fun `複製した端は、すべて閉じるまで相手側を閉じない`() {
        val p = fds.pipe()
        val w2 = fds.dup(p.write)
        fds.close(p.write)
        assertEquals(IoResult.WouldBlock(WaitKey(streamId(p), READABLE)), read(p.read).first)
        assertEquals(IoResult.Bytes(1), write(w2, "z"))
        fds.close(w2)
        assertEquals(IoResult.Bytes(1) to "z", read(p.read))
        assertEquals(IoResult.Eof, read(p.read).first)
    }

    @Test
    fun `両端を閉じると、端も中身も表から消える`() {
        val before = kernel.objects.size
        val p = fds.pipe()
        val id = streamId(p)
        write(p.write, "left")
        assertEquals(before + 3, kernel.objects.size)
        fds.close(p.read)
        fds.close(p.write)
        assertEquals(before, kernel.objects.size)
        assertNull(kernel.objects[id])
    }

    @Test
    fun `空き番号が2つなければ何も作らずにMFILE`() {
        // 残りの空きを1つにする
        while (DEFAULT_HANDLE_LIMIT - fds.size > 1) fds.open(ByteStream(1))
        val before = kernel.objects.size
        val e = assertThrows<FsException> { fds.pipe() }
        assertEquals(Errno.MFILE, e.errno)
        assertEquals(DEFAULT_HANDLE_LIMIT - 1, fds.size)
        assertEquals(before, kernel.objects.size)
    }
}
