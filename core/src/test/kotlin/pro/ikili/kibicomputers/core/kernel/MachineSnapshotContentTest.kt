package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.io.PipeEnd
import pro.ikili.kibicomputers.core.kernel.obj.HandleEntry
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.time.Timer
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.ColdBootReason
import pro.ikili.kibicomputers.core.persist.ModuleStore
import pro.ikili.kibicomputers.core.persist.SaveReadiness
import pro.ikili.kibicomputers.core.persist.SnapshotSection
import pro.ikili.kibicomputers.core.persist.snapshot.KernelLimits
import pro.ikili.kibicomputers.core.persist.snapshot.KernelSectionCodec
import pro.ikili.kibicomputers.core.persist.snapshot.SnapshotReader
import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 写しの中身（#82）: `KERNEL` 節の往復、ABI 層の写し、保存できないものの判定 */
class MachineSnapshotContentTest {
    private val limit = Duration.ofSeconds(30)

    @Test
    fun `待機点のプロセスとオブジェクトを写し、KERNEL節を往復できる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val pid = m.spawnTest(h.program(emptyModule()), gated(gate, kind = ProcessKind.WAIT_POINT))
            h.tickUntilTrue { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            // イベントを出すタイマー（キューはもう閉じている。#82 の A: 番号はそのまま書き、読むときも確かめない）
            val missingQueue = 4242
            val (timerId, pipeWriteId) = m.lock.withLockValue {
                val p = m.kernel.processes[pid]!!
                val id = m.kernel.objects.add(Timer(missingQueue, 7))
                p.handles.insert(HandleEntry(id))
                val fds = FdTable(p, m.kernel.objects, m.wakeSink).pipe()
                id to p.handles[fds.write]!!.objectId
            }
            assertEquals(SaveReadiness.READY, m.saveReadiness())
            val copy = m.captureCopy(CapturePolicy.SKIP_IF_TRANSIENT)!!
            assertEquals(ColdBootReason.NONE, copy.coldBootReason)
            assertTrue(copy.killedPids.isEmpty())
            assertEquals(setOf(pid), copy.abiCaptures.keys)

            val decoded = KernelSectionCodec.read(KernelSectionCodec.write(copy), KernelLimits(64))
            assertEquals(ProcessState.WAITING_EVENT, decoded.processes[pid]!!.state)
            assertEquals(listOf(gate.key), decoded.processes[pid]!!.wait)
            val timer = decoded.objects[timerId] as Timer
            assertEquals(missingQueue, timer.eventQueueId)
            assertTrue(timer.sendsEvents, "閉じたキューを指すタイマーも、イベントを出すタイマーのまま")
            assertEquals(7, timer.eventTag)
            val pipe = decoded.objects[pipeWriteId] as PipeEnd
            assertEquals(copy.objects.refCount(pipe.streamId), decoded.objects.refCount(pipe.streamId))
            copy.objects.forEachEntrySorted { id, obj, ref ->
                assertEquals(obj.javaClass, decoded.objects[id]!!.javaClass)
                assertEquals(ref, decoded.objects.refCount(id))
            }

            val snapshot = copy.toSource().build(UUID.randomUUID(), 1, "test")
            assertEquals(listOf(SnapshotSection.KERNEL, SnapshotSection.PROCESS_ABI), snapshot.sections.map { it.kind })
            val abi = SnapshotReader(snapshot.sections[1].payload)
            assertEquals(pid, abi.i32())
            assertEquals(SnapshotSection.ABI_LAYER_TEST, abi.u16())
            assertEquals(1, abi.u16())
            abi.expectEnd()
            gate.open()
            m.stop()
        }
    }

    @Test
    fun `保存に対応していないドライバーのプロセスはUNSAVEABLEで、写しの中で殺す`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val pid = m.spawnTest(h.program(emptyModule()), UnsaveableTestDriver(gated(gate, kind = ProcessKind.WAIT_POINT)))
            h.tickUntilTrue { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            assertEquals(SaveReadiness.UNSAVEABLE, m.saveReadiness())
            val copy = m.captureCopy(CapturePolicy.SKIP_IF_TRANSIENT)!!
            assertEquals(listOf(pid), copy.killedPids)
            assertFalse(pid in copy.abiCaptures)
            assertEquals(ProcessState.WAITING_EVENT, m.stateOf(pid), "元は変わらない")
            gate.open()
            m.stop()
        }
    }

    @Test
    fun `モジュールがストアにないプロセスはUNSAVEABLE`() = timeout(limit) {
        val store = MemoryModuleStore()
        Harness(moduleStore = store).use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val program = h.program(emptyModule())
            val pid = m.spawnTest(program, gated(gate, kind = ProcessKind.WAIT_POINT))
            h.tickUntilTrue { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            assertEquals(SaveReadiness.READY, m.saveReadiness())
            store.remove(program.moduleSource.originalHash)
            assertEquals(SaveReadiness.UNSAVEABLE, m.saveReadiness())
            assertEquals(listOf(pid), m.captureCopy(CapturePolicy.SKIP_IF_TRANSIENT)!!.killedPids)
            gate.open()
            m.stop()
        }
    }

    @Test
    fun `置き場がなければ（ModuleStore NONE）どのプロセスも保存できない`() = timeout(limit) {
        Harness(moduleStore = ModuleStore.NONE).use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val pid = m.spawnTest(h.program(emptyModule()), gated(gate, kind = ProcessKind.WAIT_POINT))
            h.tickUntilTrue { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            assertEquals(SaveReadiness.UNSAVEABLE, m.saveReadiness())
            gate.open()
            m.stop()
        }
    }

    @Test
    fun `ABI層の写しが取れなければ、写しの中で殺してKILLED節に入れる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val driver = gated(gate, kind = ProcessKind.WAIT_POINT)
            driver.abiPayload = null
            val pid = m.spawnTest(h.program(emptyModule()), driver)
            h.tickUntilTrue { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            assertEquals(SaveReadiness.READY, m.saveReadiness(), "静的な判定では分からない")
            val copy = m.captureCopy(CapturePolicy.SKIP_IF_TRANSIENT)!!
            assertEquals(listOf(pid), copy.killedPids)
            assertTrue(copy.abiCaptures.isEmpty())
            assertEquals(ProcessState.KILLED, copy.processes[pid]!!.state)
            val snapshot = copy.toSource().build(UUID.randomUUID(), 1, "test")
            assertEquals(listOf(pid), snapshot.killedPids)
            assertEquals(listOf(SnapshotSection.KERNEL), snapshot.sections.map { it.kind })
            gate.open()
            m.stop()
        }
    }

    @Test
    fun `ゾンビはドライバーもストアも見ずにREADYで、ABI層の写しを取らない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val parent = m.spawnTest(h.program(emptyModule()), gated(gate, kind = ProcessKind.WAIT_POINT))
            h.tickUntilTrue { m.stateOf(parent) == ProcessState.WAITING_EVENT }
            val child = m.spawnTest(h.program(emptyModule()), UnsaveableTestDriver(TestDriver(ProcessKind.WAIT_POINT) { StepResult.Exit(3) }), parentPid = parent)
            h.tickUntilTrue { m.stateOf(child) == ProcessState.EXITED }
            assertEquals(SaveReadiness.READY, m.saveReadiness())
            val copy = m.captureCopy(CapturePolicy.SKIP_IF_TRANSIENT)!!
            assertTrue(copy.killedPids.isEmpty())
            assertEquals(setOf(parent), copy.abiCaptures.keys)
            assertEquals(ProcessState.EXITED, copy.processes[child]!!.state)
            gate.open()
            m.stop()
        }
    }

    @Test
    fun `initを殺して冷えた起動になったら、中身を書かずKILLED節だけ`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val other = m.spawnTest(h.program(emptyModule()), gated(gate, kind = ProcessKind.WAIT_POINT))
            val driver = gated(gate, kind = ProcessKind.WAIT_POINT)
            driver.abiPayload = null
            val init = m.spawnTest(h.program(emptyModule()), driver)
            h.tickUntilTrue { m.stateOf(init) == ProcessState.WAITING_EVENT && m.stateOf(other) == ProcessState.WAITING_EVENT }
            m.lock.withLockValue { m.kernel.initPid = init }
            val copy = m.captureCopy(CapturePolicy.SKIP_IF_TRANSIENT)!!
            assertEquals(ColdBootReason.INIT_KILLED, copy.coldBootReason)
            assertTrue(copy.abiCaptures.isEmpty(), "取った中身は捨てる")
            assertTrue(copy.tmpfsCaptures.isEmpty())
            val snapshot = copy.toSource().build(UUID.randomUUID(), 1, "test")
            assertTrue(snapshot.sections.isEmpty())
            assertEquals(ColdBootReason.INIT_KILLED.code, snapshot.coldBootCode)
            gate.open()
            m.stop()
        }
    }

    @Test
    fun `execで読み込んだモジュールは置き場に書く`() = timeout(limit) {
        val store = MemoryModuleStore()
        Harness(moduleStore = store).use { h ->
            val program = h.program(emptyModule())
            assertTrue(store.contains(program.moduleSource.originalHash))
            assertTrue(store.contains(ModuleStore.sha256(store.get(program.moduleSource.originalHash)!!)))
        }
    }
}
