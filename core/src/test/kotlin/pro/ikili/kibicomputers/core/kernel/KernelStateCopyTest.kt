package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.io.PipeEnd
import pro.ikili.kibicomputers.core.kernel.process.ExitReason
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessObject
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.ColdBootReason
import pro.ikili.kibicomputers.core.persist.KernelStateCopy
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 保存の写し（`KernelStateCopy`）の深さと、写しの中で殺す手順のテスト（#81、ADR 0081） */
class KernelStateCopyTest {
    private val limit = Duration.ofSeconds(30)

    /** 親（待機点型、子の終わりを待つ）→ 子（ブロッキング型。パイプと一時的なタイマーを持って待つ）→ 孫（待機点型） */
    private class Family(val h: Harness, val m: Machine) {
        val gate = Gate(m)
        val parent: Int
        val child: Int
        val grandchild: Int
        val pipeRead = AtomicInteger()
        val pipeWrite = AtomicInteger()
        val timer = AtomicInteger()
        val parentWakes = AtomicInteger()

        init {
            parent = m.spawnTest(
                h.program(emptyModule()),
                TestDriver(ProcessKind.WAIT_POINT, onResume = { _, _ ->
                    parentWakes.incrementAndGet()
                    StepResult.Exit(0)
                }) { ctx ->
                    val obj = m.lock.withLockValue { m.kernel.processes[ctx.pid]!!.objectId }
                    StepResult.Wait(listOf(WaitKey(obj, WaitCondition.CHILD_EXITED)))
                },
            )
            h.tickUntilTrue { m.stateOf(parent) == ProcessState.WAITING_EVENT }
            child = m.spawnTest(
                h.program(emptyModule()),
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    m.lock.withLockValue {
                        val p = m.kernel.processes[ctx.pid]!!
                        val fds = FdTable(p, m.kernel.objects, m.wakeSink).pipe()
                        pipeRead.set(p.handles[fds.read]!!.objectId)
                        pipeWrite.set(p.handles[fds.write]!!.objectId)
                    }
                    timer.set(ctx.openWaitTimer(Long.MAX_VALUE / 2).objectId)
                    ctx.block(listOf(gate.key))
                    StepResult.Exit(7)
                },
                parentPid = parent,
            )
            h.tickUntilTrue { m.stateOf(child) == ProcessState.WAITING_EVENT }
            grandchild = m.spawnTest(h.program(emptyModule()), gated(gate, kind = ProcessKind.WAIT_POINT), parentPid = child)
            h.tickUntilTrue { m.stateOf(grandchild) == ProcessState.WAITING_EVENT }
        }

        val streamId: Int get() = m.lock.withLockValue { (m.kernel.objects[pipeWrite.get()] as PipeEnd).streamId }
    }

    @Test
    fun `写しの中でブロッキング型を殺しても、元のマシンは変わらない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val f = Family(h, m)
            val streamId = f.streamId
            val before = m.lock.withLockValue {
                val k = m.kernel
                listOf(f.pipeRead.get(), f.pipeWrite.get(), f.timer.get(), streamId).associateWith { k.objects.refCount(it) }
            }
            val childObj = m.lock.withLockValue { m.kernel.processes[f.child]!!.objectId }
            val parentObj = m.lock.withLockValue { m.kernel.processes[f.parent]!!.objectId }
            val handlesBefore = m.lock.withLockValue { m.kernel.processes[f.child]!!.handles.entries() }

            val copy = m.captureCopy(CapturePolicy.KILL_TRANSIENT)!!
            assertEquals(listOf(f.child), copy.killedPids)

            // 元: 状態・fd 表・一時的なタイマー・参照の数・ProcessObject は変わらない
            m.lock.withLockValue {
                val k = m.kernel
                val child = k.processes[f.child]!!
                assertEquals(ProcessState.WAITING_EVENT, child.state)
                assertEquals(handlesBefore, child.handles.entries())
                assertTrue(child.waitTimers.contains(f.timer.get()))
                assertEquals(before, before.keys.associateWith { k.objects.refCount(it) })
                assertNull((k.objects[childObj] as ProcessObject).status)
                assertEquals(0, (k.objects[parentObj] as ProcessObject).pendingChildExits)
                assertFalse((k.objects[streamId] as ByteStream).writeClosed, "元のパイプの相手の端は EOF にならない")
                assertFalse((k.objects[streamId] as ByteStream).readClosed)
                assertEquals(f.child, k.processes[f.grandchild]!!.parentPid)
            }
            repeat(10) {
                h.scheduler.tick()
                Thread.sleep(2)
            }
            assertEquals(0, f.parentWakes.get(), "元のマシンの待ち手（親の CHILD_EXITED）は起きない")
            assertEquals(ProcessState.WAITING_EVENT, m.stateOf(f.parent))

            // 写し: 子の記録・親の数え・孫の付け替え・参照の数
            assertCopyKilled(copy, f, childObj, parentObj, streamId)

            // 元を進めても、写しは変わらない
            f.gate.open()
            h.tickUntilTrue("元のマシンが進まない") { m.stateOf(f.parent) == ProcessState.EXITED }
            assertCopyKilled(copy, f, childObj, parentObj, streamId)
            m.stop()
        }
    }

    private fun assertCopyKilled(copy: KernelStateCopy, f: Family, childObj: Int, parentObj: Int, streamId: Int) {
        val child = copy.processes[f.child]!!
        assertEquals(ProcessState.KILLED, child.state)
        assertEquals(KillSignal.KILL, child.termSignal)
        assertNull(child.wait)
        assertEquals(0, child.handles.size)
        assertTrue(child.waitTimers.isEmpty)
        val status = (copy.objects[childObj] as ProcessObject).status!!
        assertEquals(ExitReason.KILLED, status.reason)
        assertEquals(KillSignal.KILL, status.signal)
        assertEquals(1, (copy.objects[parentObj] as ProcessObject).pendingChildExits)
        assertEquals(0, copy.processes[f.grandchild]!!.parentPid, "子の子は親なしに付け替える")
        assertNull(copy.objects[f.pipeRead.get()])
        assertNull(copy.objects[f.pipeWrite.get()])
        assertNull(copy.objects[f.timer.get()])
        // 写しはすべてのオブジェクトを写すので（#82）、両端を閉じたパイプの中身のストリームは写しの中で閉じる
        assertEquals(0, copy.objects.refCount(streamId))
        assertNull(copy.objects[streamId])
        assertEquals(ColdBootReason.NONE, copy.coldBootReason)
    }

    @Test
    fun `initを写しの中で殺すと、冷えた起動の理由がINIT_KILLED`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val gate = Gate(m)
            val init = m.spawnTest(h.program(emptyModule()), gated(gate))
            h.tickUntilTrue { m.stateOf(init) == ProcessState.WAITING_EVENT }
            m.lock.withLockValue { m.kernel.initPid = init }
            val copy = m.captureCopy(CapturePolicy.SKIP_IF_TRANSIENT)!!
            assertEquals(listOf(init), copy.killedPids)
            assertEquals(ColdBootReason.INIT_KILLED, copy.coldBootReason)
            assertEquals(0, copy.initPid)
            assertEquals(init, m.lock.withLockValue { m.kernel.initPid }, "元の init は変わらない")
            val snapshot = copy.toSource().build(java.util.UUID.randomUUID(), 1, "test")
            assertEquals(ColdBootReason.INIT_KILLED, snapshot.coldBootReason)
            assertEquals(listOf(init), snapshot.killedPids)
            m.lock.withLockValue { m.kernel.initPid = 0 }
            m.stop()
        }
    }
}
