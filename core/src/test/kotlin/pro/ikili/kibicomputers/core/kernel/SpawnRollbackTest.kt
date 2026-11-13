package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessObject
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** spawn が表に足した後で失敗したときの巻き戻し（#629）。init の巻き戻しは [BootTest] で確かめる */
internal class SpawnRollbackTest {
    /** 巻き戻しで元に戻るべきもの */
    private data class Snapshot(
        val pids: List<Int>,
        val parentFds: Map<Int, Int>,
        val children: List<Int>,
        val pendingChildExits: Int,
        val refCounts: Map<Int, Int>,
        val initPid: Int,
        val blocking: Int,
        val dedicatedThreads: Int,
        val runtimes: Set<Int>,
    )

    private fun snapshot(m: Machine, parent: Int): Snapshot = m.lock.withLockValue {
        val p = m.kernel.processes[parent]!!
        val refs = HashMap<Int, Int>()
        m.kernel.objects.forEachObject { refs[it.id] = m.kernel.objects.refCount(it.id) }
        Snapshot(
            pids = m.kernel.processes.all().map { it.pid },
            parentFds = FdTable(p, m.kernel.objects, m.wakeSink).entries().mapValues { it.value.objectId },
            children = m.kernel.processes.childrenOf(parent),
            pendingChildExits = (m.kernel.objects[p.objectId] as ProcessObject).pendingChildExits,
            refCounts = refs,
            initPid = m.kernel.initPid,
            blocking = m.scheduler.blockingProcesses,
            dedicatedThreads = m.scheduler.dedicatedThreads.size,
            runtimes = m.runtimes.keys.toSet(),
        )
    }

    @ParameterizedTest
    @EnumSource(ProcessKind::class)
    fun `表に足した後で失敗したspawnは、表・オブジェクト・fd・枠を巻き戻し、元の例外をそのまま投げる`(kind: ProcessKind) =
        timeout(Duration.ofSeconds(30)) {
            Harness().use { h ->
                val m = h.machine()
                val program = h.program(emptyModule())
                val parentGate = Gate(m)
                val parent = m.spawnTest(program, gated(parentGate))
                try {
                    // 子に渡す fd（パイプの両端）。巻き戻すと参照の数が元に戻る
                    m.lock.withLockValue { FdTable(m.kernel.processes[parent]!!, m.kernel.objects, m.wakeSink).pipe() }
                    val options = SpawnOptions(fds = mapOf(3 to StdioSource.Inherit, 4 to StdioSource.Inherit))
                    // Gate はオブジェクトを1つ足すので、写しを取る前に作る
                    val driver = gated(Gate(m), kind = kind)
                    val before = snapshot(m, parent)
                    val failure = IllegalStateException("テストの失敗")
                    var seenPid = 0
                    h.scheduler.hooks.duringSpawn = { _, pid ->
                        seenPid = pid
                        throw failure
                    }
                    val thrown = assertThrows<IllegalStateException> {
                        m.spawnWithHandle(program, driver, options, parent)
                    }
                    assertSame(failure, thrown, "包まずに投げ直す")
                    h.scheduler.hooks.duringSpawn = null
                    assertTrue(seenPid > 0)
                    val after = snapshot(m, parent)
                    assertEquals(before, after)
                    // 親は子の終わりを受け取らない（存在しない子のゾンビ・知らせを作らない）
                    assertEquals(null, m.process(seenPid))

                    // 巻き戻した後も、ふつうに作れる（pid は使い回さない）
                    val child = m.spawnWithHandle(program, exiting(5, kind), options, parent)
                    assertTrue(child.pid > seenPid, "pid は使い回さない")
                    h.tickUntilTrue("子が終わらない") { m.processState(child.pid)?.isTerminal == true }
                    m.lock.withLockValue {
                        val p = m.kernel.processes[parent]!!
                        assertEquals(1, (m.kernel.objects[p.objectId] as ProcessObject).pendingChildExits, "終わった子は1つだけ")
                        assertEquals(listOf(child.pid), m.kernel.processes.childrenOf(parent))
                    }
                } finally {
                    parentGate.open()
                }
                h.tickUntilTrue("親が終わらない") { m.processState(parent)?.isTerminal == true }
                assertTrue(m.awaitTermination(5_000))
            }
        }

    @ParameterizedTest
    @EnumSource(ProcessKind::class)
    fun `親のない最後のプロセスの巻き戻しでは、awaitTerminationが終わり、状態は変えない`(kind: ProcessKind) =
        timeout(Duration.ofSeconds(30)) {
            Harness().use { h ->
                val m = h.machine()
                val program = h.program(emptyModule())
                h.scheduler.hooks.duringSpawn = { _, _ -> throw OutOfMemoryError("テストの失敗") }
                assertThrows<OutOfMemoryError> { m.spawnTest(program, gated(Gate(m), kind = kind)) }
                h.scheduler.hooks.duringSpawn = null
                assertEquals(MachineState.STOPPED, m.state)
                assertEquals(emptyList<Int>(), m.lock.withLockValue { m.kernel.processes.all().map { it.pid } })
                assertEquals(0, m.scheduler.blockingProcesses)
                assertTrue(m.awaitTermination(1_000))
            }
        }
}
