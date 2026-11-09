package pro.ikili.kibicomputers.core.persist

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.device.CallError
import pro.ikili.kibicomputers.core.device.CallResult
import pro.ikili.kibicomputers.core.device.DeviceHandle
import pro.ikili.kibicomputers.core.device.PendingDeviceCall
import pro.ikili.kibicomputers.core.kernel.event.EventFilter
import pro.ikili.kibicomputers.core.kernel.event.EventQueue
import pro.ikili.kibicomputers.core.kernel.event.EventValue
import pro.ikili.kibicomputers.core.kernel.event.KernelEvent
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.io.PipeEnd
import pro.ikili.kibicomputers.core.kernel.obj.KernelObject
import pro.ikili.kibicomputers.core.kernel.process.ExitReason
import pro.ikili.kibicomputers.core.kernel.process.ExitStatus
import pro.ikili.kibicomputers.core.kernel.process.HostCompletion
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ProcessObject
import pro.ikili.kibicomputers.core.kernel.time.Timer
import pro.ikili.kibicomputers.core.kernel.tty.Tty
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FileType
import pro.ikili.kibicomputers.core.kernel.vfs.OpenFile
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.wait.NoopWakeSink
import pro.ikili.kibicomputers.core.persist.snapshot.KernelObjectCodec
import pro.ikili.kibicomputers.core.persist.snapshot.SnapshotReader
import pro.ikili.kibicomputers.core.persist.snapshot.SnapshotWriter

/** カーネルオブジェクトの中身の形（#82）。全種類を書いて読み、もう一度書いて同じバイト列になるかで往復を確かめる */
class KernelObjectCodecTest {
    private fun bytesOf(obj: KernelObject, path: VfsPath? = null): ByteArray =
        SnapshotWriter(64).also { KernelObjectCodec.write(it, obj, path) }.toByteArray()

    /** 書いて読み、同じ種類で、もう一度書くと同じバイト列になる。読んだものを返す */
    private fun <T : KernelObject> roundTrip(obj: T, path: VfsPath? = null): T {
        val kind = KernelObjectCodec.kindOf(obj)
        val bytes = bytesOf(obj, path)
        val back = KernelObjectCodec.read(kind, SnapshotReader(bytes))
        assertEquals(obj.javaClass, back.obj.javaClass)
        assertEquals(path, back.filePath)
        assertArrayEquals(bytes, bytesOf(back.obj, back.filePath), "${obj.javaClass.simpleName} の往復")
        @Suppress("UNCHECKED_CAST")
        return back.obj as T
    }

    @Test
    fun `プロセス・パイプ・ストリーム・開いたファイル`() {
        val p = ProcessObject(5).apply {
            status = ExitStatus(5, ExitReason.KILLED, 0, KillSignal.KILL)
            pendingChildExits = 2
        }
        assertEquals(KillSignal.KILL, roundTrip(p).status!!.signal)
        roundTrip(ProcessObject(6))
        assertTrue(roundTrip(PipeEnd(9, true)).reading)

        // head がずれたストリーム
        val s = ByteStream(8)
        s.write("abcdefgh".toByteArray(), 0, 8, NoopWakeSink)
        s.read(ByteArray(5), 0, 5, NoopWakeSink)
        s.write("XY".toByteArray(), 0, 2, NoopWakeSink)
        s.closeWrite(NoopWakeSink)
        val back = roundTrip(s)
        assertArrayEquals("fghXY".toByteArray(), back.availableBytes())
        assertTrue(back.writeClosed)

        val f = OpenFile(3, 42, FileType.REGULAR_FILE, VfsPath.parse("/mnt/a.txt"), true, true, true).apply { position = 123 }
        val bf = roundTrip(f, VfsPath.parse("/renamed.txt"))
        assertEquals(123, bf.position)
        roundTrip(f)
    }

    @Test
    fun `タイマーはキューの番号をそのまま持つ（閉じたキューを指していても0に直さない）`() {
        val t = Timer(4242, -9)
        t.arm(1_000, 500)
        val back = roundTrip(t)
        assertEquals(4242, back.eventQueueId)
        assertTrue(back.sendsEvents)
        assertTrue(back.armed)
        assertEquals(1_000, back.deadlineNanos)
        assertEquals(500, back.intervalNanos)
        assertEquals(t.generation, back.generation)
        roundTrip(Timer())
    }

    @Test
    fun `イベントキューは全種類の値とあふれた数を持つ`() {
        val q = EventQueue(EventFilter(setOf("a", "b")), 2)
        val values = listOf(EventValue.Integer(-1), EventValue.Float(1.5), EventValue.Boolean(true), EventValue.Text("日本語"), EventValue.Bytes(byteArrayOf(1, 2)))
        q.offer(KernelEvent("a", "src", values), NoopWakeSink)
        q.offer(KernelEvent("b"), NoopWakeSink)
        q.offer(KernelEvent("a"), NoopWakeSink)
        val back = roundTrip(q)
        assertEquals(1, back.dropped)
        assertEquals(2, back.size)
        assertEquals(setOf("a", "b"), back.filter.types)
    }

    @Test
    fun `端末は入力の途中と出力を持つ`() {
        val tty = Tty(64, 64)
        val first = "echo hi\n".toByteArray()
        tty.input(first, 0, first.size, NoopWakeSink)
        val typing = "ls -l\u001b[".toByteArray()
        tty.input(typing, 0, typing.size, NoopWakeSink)
        tty.write("out".toByteArray(), 0, 3, NoopWakeSink)
        roundTrip(tty)
    }

    @Test
    fun `ホストの処理・デバイス・デバイスの呼び出し`() {
        assertTrue(roundTrip(HostCompletion().apply { done = true }).done)
        assertEquals("addr", roundTrip(DeviceHandle("addr", "screen")).address)

        val args = listOf(DeviceValue.none(), DeviceValue.of(1L), DeviceValue.of(2.5), DeviceValue.of(true), DeviceValue.of("s"), DeviceValue.of(byteArrayOf(3)))
        roundTrip(PendingDeviceCall(PendingDeviceCall.State.Queued("addr", "m", args)))
        // 実行し始めたものは interrupted で失敗したことにする
        val running = PendingDeviceCall(PendingDeviceCall.State.Queued("addr", "m", emptyList())).apply { markRunning() }
        val failed = roundTrip(running).snapshot() as PendingDeviceCall.Snapshot.Done
        assertEquals(CallResult.Error(CallError.Failed("interrupted")), failed.result)
        val errors = listOf(
            CallError.Kernel(Errno.ACCES),
            CallError.Detached,
            CallError.NoSuchMethod,
            CallError.BadArguments("bad"),
            CallError.Failed("x".repeat(70_000)),
        )
        for (e in errors) {
            val done = PendingDeviceCall(PendingDeviceCall.State.Done(CallResult.Error(e)))
            roundTrip(done)
        }
        val values = PendingDeviceCall(PendingDeviceCall.State.Done(CallResult.Values(args)))
        assertEquals(CallResult.Values(args), (roundTrip(values).snapshot() as PendingDeviceCall.Snapshot.Done).result)
    }

    @Test
    fun `知らない種類と、中身の余りはSnapshotFormatException`() {
        assertThrows<SnapshotFormatException> { KernelObjectCodec.read(99, SnapshotReader(ByteArray(0))) }
        val bytes = bytesOf(HostCompletion()) + byteArrayOf(0)
        assertThrows<SnapshotFormatException> { KernelObjectCodec.read(KernelObjectCodec.HOST_COMPLETION, SnapshotReader(bytes)) }
    }
}
