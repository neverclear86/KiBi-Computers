package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.io.ReadResult
import pro.ikili.kibicomputers.core.kernel.io.WriteResult
import pro.ikili.kibicomputers.core.kernel.obj.HandleEntry
import pro.ikili.kibicomputers.core.kernel.obj.HandleTable
import pro.ikili.kibicomputers.core.kernel.obj.KernelObject
import pro.ikili.kibicomputers.core.kernel.obj.ObjectTable
import pro.ikili.kibicomputers.core.kernel.process.ModuleHash
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.wait.WakeSink
import pro.ikili.kibicomputers.core.runtime.FakeWasmRuntime

/** 起こされた key を記録する [WakeSink] */
internal class RecordingSink : WakeSink {
    val keys = mutableListOf<WaitKey>()
    val objects = mutableListOf<Int>()

    override fun wake(key: WaitKey) {
        keys += key
    }

    override fun wakeObject(objectId: Int) {
        objects += objectId
    }
}

class HandleTableTest {
    @Test
    fun `いちばん小さい空き番号を使い、閉じた番号を使い回す`() {
        val table = HandleTable<HandleEntry>(4)
        assertEquals(0, table.insert(HandleEntry(10)))
        assertEquals(1, table.insert(HandleEntry(11)))
        assertEquals(2, table.insert(HandleEntry(12)))
        assertEquals(HandleEntry(11), table.remove(1))
        assertNull(table.get(1))
        assertEquals(1, table.insert(HandleEntry(13)))
        assertEquals(3, table.insert(HandleEntry(14)))
        assertEquals(-1, table.insert(HandleEntry(15)), "上限で -1")
        assertEquals(listOf(0, 1, 2, 3), table.entries().keys.toList())
    }

    @Test
    fun `insertAtで置き換える・広げる`() {
        val table = HandleTable<HandleEntry>(64)
        table.insert(HandleEntry(1))
        table.insertAt(0, HandleEntry(2))
        assertEquals(HandleEntry(2), table[0])
        table.insertAt(40, HandleEntry(3))
        assertEquals(HandleEntry(3), table[40])
        assertEquals(2, table.size)
        assertEquals(1, table.insert(HandleEntry(4)))
        assertThrows<IllegalArgumentException> { table.insertAt(64, HandleEntry(5)) }
    }
}

class ObjectTableTest {
    private class Obj : KernelObject() {
        var closed = 0

        override fun onLastClose(sink: WakeSink) {
            closed++
        }
    }

    @Test
    fun `2つのハンドルから同じオブジェクトを指し、両方閉じたときだけonLastClose`() {
        val objects = ObjectTable()
        val obj = Obj()
        val id = objects.add(obj)
        val handles = HandleTable<HandleEntry>(8)
        handles.insert(HandleEntry(id))
        objects.retain(id)
        handles.insert(HandleEntry(id))
        val sink = RecordingSink()
        objects.release(handles.remove(0)!!.objectId, sink)
        assertEquals(0, obj.closed)
        assertEquals(obj, objects[id])
        objects.release(handles.remove(1)!!.objectId, sink)
        assertEquals(1, obj.closed)
        assertEquals(listOf(id), sink.objects)
        assertNull(objects[id])
    }

    @Test
    fun `IDは使い回さない`() {
        val objects = ObjectTable()
        val a = objects.add(Obj())
        objects.release(a, WakeSink.NONE)
        val b = objects.add(Obj())
        assertNotEquals(a, b)
    }
}

class ByteStreamTest {
    private fun stream(capacity: Int = 8): Pair<ByteStream, ObjectTable> {
        val objects = ObjectTable()
        val s = ByteStream(capacity)
        objects.add(s)
        return s to objects
    }

    @Test
    fun `読み書きでき、容量いっぱいでWouldBlock`() {
        val (s, _) = stream(4)
        assertEquals(WriteResult.Bytes(4), s.write("abcdef".toByteArray(), 0, 6, WakeSink.NONE))
        assertEquals(WriteResult.WouldBlock, s.write(byteArrayOf(1), 0, 1, WakeSink.NONE))
        val buf = ByteArray(3)
        assertEquals(ReadResult.Bytes(3), s.read(buf, 0, 3, WakeSink.NONE))
        assertEquals("abc", String(buf))
        assertEquals(WriteResult.Bytes(2), s.write("xy".toByteArray(), 0, 2, WakeSink.NONE))
        val rest = ByteArray(8)
        assertEquals(ReadResult.Bytes(3), s.read(rest, 0, 8, WakeSink.NONE))
        assertEquals("dxy", String(rest, 0, 3))
        assertEquals(ReadResult.WouldBlock, s.read(rest, 0, 8, WakeSink.NONE))
    }

    @Test
    fun `書く側を閉じると残りを読んだ後にEof、読む側を閉じるとClosed`() {
        val (s, _) = stream()
        s.write("hi".toByteArray(), 0, 2, WakeSink.NONE)
        s.closeWrite(WakeSink.NONE)
        val buf = ByteArray(4)
        assertEquals(ReadResult.Bytes(2), s.read(buf, 0, 4, WakeSink.NONE))
        assertEquals(ReadResult.Eof, s.read(buf, 0, 4, WakeSink.NONE))
        val (t, _) = stream()
        t.closeRead(WakeSink.NONE)
        assertEquals(WriteResult.Closed, t.write(buf, 0, 1, WakeSink.NONE))
    }

    @Test
    fun `isReadyが状態どおりに変わる`() {
        val (s, _) = stream(2)
        assertFalse(s.isReady(WaitCondition.READABLE))
        assertTrue(s.isReady(WaitCondition.WRITABLE))
        s.write("ab".toByteArray(), 0, 2, WakeSink.NONE)
        assertTrue(s.isReady(WaitCondition.READABLE))
        assertFalse(s.isReady(WaitCondition.WRITABLE))
        s.closeRead(WakeSink.NONE)
        assertTrue(s.isReady(WaitCondition.WRITABLE))
        val (t, _) = stream(2)
        t.closeWrite(WakeSink.NONE)
        assertTrue(t.isReady(WaitCondition.READABLE))
    }

    @Test
    fun `読んだらWRITABLE、書いたらREADABLE、閉じたら相手側のkeyだけを知らせる`() {
        val (s, _) = stream()
        val sink = RecordingSink()
        s.write(byteArrayOf(1, 2), 0, 2, sink)
        assertEquals(listOf(WaitKey(s.id, WaitCondition.READABLE)), sink.keys)
        sink.keys.clear()
        s.read(ByteArray(1), 0, 1, sink)
        assertEquals(listOf(WaitKey(s.id, WaitCondition.WRITABLE)), sink.keys)
        sink.keys.clear()
        s.closeWrite(sink)
        assertEquals(listOf(WaitKey(s.id, WaitCondition.READABLE)), sink.keys)
        sink.keys.clear()
        s.closeRead(sink)
        assertEquals(listOf(WaitKey(s.id, WaitCondition.WRITABLE)), sink.keys)
    }

    @Test
    fun `バッファは使うまで確保せず、読み切ったら手放し、容量を超えない`() {
        val (s, _) = stream(1000)
        assertEquals(0, s.allocatedBytes)
        s.write(ByteArray(10), 0, 10, WakeSink.NONE)
        assertEquals(256, s.allocatedBytes)
        s.read(ByteArray(10), 0, 10, WakeSink.NONE)
        assertEquals(0, s.allocatedBytes)
        while (s.write(ByteArray(300), 0, 300, WakeSink.NONE) is WriteResult.Bytes) Unit
        assertEquals(1000, s.available)
        assertTrue(s.allocatedBytes <= 1000, "確保 ${s.allocatedBytes}")
    }
}

class ProcessTest {
    @Test
    fun `Programのハッシュはloadに渡したバイト列のSHA-256`() {
        val runtime = FakeWasmRuntime()
        val a = "module-a".toByteArray()
        val b = "module-b".toByteArray()
        runtime.define(a, emptyModule()).define(b, emptyModule())
        val pa = Program.load(runtime, a)
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(a)
        assertTrue(expected.contentEquals(pa.hash.bytes()))
        assertEquals(ModuleHash.of(a), pa.hash)
        assertNotEquals(pa.hash, Program.load(runtime, b).hash, "1バイト違えば別の値")
        assertEquals(64, pa.hash.toString().length)
    }

    @Test
    fun `spawnしたプロセスのmoduleHashと親のpid`() {
        Harness().use { h ->
            val machine = h.machine()
            val program = h.program(emptyModule())
            val pid = machine.spawnTest(program, TestDriver(pro.ikili.kibicomputers.core.kernel.process.ProcessKind.BLOCKING) { pro.ikili.kibicomputers.core.kernel.process.StepResult.Exit(3) })
            h.scheduler.tick()
            eventually { machine.stateOf(pid).isTerminal }
            val process = machine.lock.withLockValue { machine.kernel.processes[pid]!! }
            assertEquals(program.hash, process.moduleHash)
            assertEquals(0, process.parentPid)
            assertEquals(3, process.exitCode)
        }
    }
}

internal inline fun <T> java.util.concurrent.locks.ReentrantLock.withLockValue(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
