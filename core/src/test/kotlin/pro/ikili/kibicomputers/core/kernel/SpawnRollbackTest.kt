package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessObject
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.sched.PermitPool
import pro.ikili.kibicomputers.core.kernel.sched.WorkerPool
import pro.ikili.kibicomputers.core.kernel.vfs.OpenFile
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import java.time.Duration
import java.util.concurrent.ExecutorService
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

    /** [execute] を失敗させられるワーカーの置き場（`unable to create native thread` を再現する） */
    private class FlakyExecutor(private val delegate: ExecutorService) : ExecutorService by delegate {
        @Volatile
        var failure: Throwable? = null

        override fun execute(command: Runnable) {
            failure?.let { throw it }
            delegate.execute(command)
        }
    }

    private fun snapshot(m: Machine, parent: Int?): Snapshot = m.lock.withLockValue {
        val p = parent?.let { m.kernel.processes[it]!! }
        val refs = HashMap<Int, Int>()
        m.kernel.objects.forEachObject { refs[it.id] = m.kernel.objects.refCount(it.id) }
        Snapshot(
            pids = m.kernel.processes.all().map { it.pid },
            parentFds = p?.let { FdTable(it, m.kernel.objects, m.wakeSink).entries().mapValues { e -> e.value.objectId } } ?: emptyMap(),
            children = parent?.let { m.kernel.processes.childrenOf(it) } ?: emptyList(),
            pendingChildExits = p?.let { (m.kernel.objects[it.objectId] as ProcessObject).pendingChildExits } ?: 0,
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

    @ParameterizedTest
    @EnumSource(ProcessKind::class)
    fun `途中の段で失敗したspawnも、進んだ分だけを巻き戻す`(kind: ProcessKind) = timeout(Duration.ofSeconds(60)) {
        Harness().use { h ->
            val m = h.machine()
            val program = h.program(emptyModule())
            val parentGate = Gate(m)
            val parent = m.spawnTest(program, gated(parentGate))
            try {
                m.lock.withLockValue { FdTable(m.kernel.processes[parent]!!, m.kernel.objects, m.wakeSink).pipe() }
                val options = SpawnOptions(fds = mapOf(3 to StdioSource.Inherit, 4 to StdioSource.Inherit))
                val driver = gated(Gate(m), kind = kind)
                // fd は、2つ目を入れた後で失敗する場合（途中まで入った状態）も確かめる
                val cases = SpawnStage.entries.map { it to 1 } + (SpawnStage.FD to 3)
                for ((stage, nth) in cases) {
                    val before = snapshot(m, parent)
                    val failure = IllegalStateException("テストの失敗 $stage#$nth")
                    var seen = 0
                    h.scheduler.hooks.atSpawnStage = { s ->
                        if (s == stage && ++seen == nth) throw failure
                    }
                    val thrown = assertThrows<IllegalStateException>("$stage#$nth") { m.spawnWithHandle(program, driver, options, parent) }
                    h.scheduler.hooks.atSpawnStage = null
                    assertSame(failure, thrown, "$stage#$nth: 包まずに投げ直す")
                    assertEquals(nth, seen, "$stage#$nth: その段まで進んだ")
                    assertEquals(before, snapshot(m, parent), "$stage#$nth: 元に戻る")
                }
                // 巻き戻した後も、ふつうに作れる
                val child = m.spawnWithHandle(program, exiting(5, kind), options, parent)
                h.tickUntilTrue("子が終わらない") { m.processState(child.pid)?.isTerminal == true }
                m.lock.withLockValue {
                    val p = m.kernel.processes[parent]!!
                    assertEquals(1, (m.kernel.objects[p.objectId] as ProcessObject).pendingChildExits)
                    assertEquals(listOf(child.pid), m.kernel.processes.childrenOf(parent))
                }
            } finally {
                parentGate.open()
            }
            h.tickUntilTrue("親が終わらない") { m.processState(parent)?.isTerminal == true }
            assertTrue(m.awaitTermination(5_000))
        }
    }

    /** オブジェクト表にある、開いたファイル（ディレクトリを含む）の数 */
    private fun openFiles(m: Machine): Int = m.lock.withLockValue {
        var n = 0
        m.kernel.objects.forEachObject { if (it is OpenFile) n++ }
        n
    }

    @ParameterizedTest
    @EnumSource(ProcessKind::class)
    fun `まだ表にないfd（preopenのディレクトリ）も、FDの段の途中・後の段のどちらで失敗しても表に残らない`(kind: ProcessKind) =
        timeout(Duration.ofSeconds(60)) {
            Harness().use { h ->
                val m = h.machine()
                m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
                val program = h.program(emptyModule())
                val parentGate = Gate(m)
                val parent = m.spawnTest(program, gated(parentGate))
                try {
                    m.lock.withLockValue { FdTable(m.kernel.processes[parent]!!, m.kernel.objects, m.wakeSink).pipe() }
                    // 新しく作る OpenFile（PendingFd.NewObject）を、既存のオブジェクトの fd の前後に置く
                    val options = SpawnOptions(
                        fds = mapOf(3 to StdioSource.Directory("/"), 4 to StdioSource.Inherit, 5 to StdioSource.Directory("/")),
                    )
                    val driver = gated(Gate(m), kind = kind)
                    val openBefore = openFiles(m)
                    // fd は 0〜2（コンソール）・3（ディレクトリ）・4（既存）・5（ディレクトリ）の順に入る。
                    // FD の段は、3・4・5 を入れた直後（4 番目・5 番目・6 番目）で失敗させる。ほかの段は fd をすべて入れた後
                    val cases = SpawnStage.entries.map { it to 1 } + listOf(SpawnStage.FD to 4, SpawnStage.FD to 5, SpawnStage.FD to 6)
                    for ((stage, nth) in cases) {
                        val before = snapshot(m, parent)
                        val failure = IllegalStateException("テストの失敗 $stage#$nth")
                        var seen = 0
                        h.scheduler.hooks.atSpawnStage = { s ->
                            if (s == stage && ++seen == nth) throw failure
                        }
                        val thrown = assertThrows<IllegalStateException>("$stage#$nth") { m.spawnWithHandle(program, driver, options, parent) }
                        h.scheduler.hooks.atSpawnStage = null
                        assertSame(failure, thrown, "$stage#$nth: 包まずに投げ直す")
                        assertEquals(nth, seen, "$stage#$nth: その段まで進んだ")
                        assertEquals(before, snapshot(m, parent), "$stage#$nth: 元に戻る")
                        assertEquals(openBefore, openFiles(m), "$stage#$nth: 作った OpenFile が表に残る")
                    }
                    // spawn の最後（すべて確保した後）での失敗
                    val before = snapshot(m, parent)
                    h.scheduler.hooks.duringSpawn = { _, _ -> throw IllegalStateException("テストの失敗") }
                    assertThrows<IllegalStateException> { m.spawnWithHandle(program, driver, options, parent) }
                    h.scheduler.hooks.duringSpawn = null
                    assertEquals(before, snapshot(m, parent))
                    assertEquals(openBefore, openFiles(m), "作った OpenFile が表に残る")

                    // 巻き戻した後も、ふつうに作れて、ディレクトリが2つ渡る
                    val child = m.spawnWithHandle(program, exiting(5, kind), options, parent)
                    assertEquals(openBefore + 2, openFiles(m))
                    h.tickUntilTrue("子が終わらない") { m.processState(child.pid)?.isTerminal == true }
                } finally {
                    parentGate.open()
                }
                h.tickUntilTrue("親が終わらない") { m.processState(parent)?.isTerminal == true }
                assertTrue(m.awaitTermination(5_000))
            }
        }

    @Test
    fun `待ち行列に積むときにワーカーを作れず投げても、CPUの待ち行列と許可は元に戻る`() = timeout(Duration.ofSeconds(30)) {
        val executor = FlakyExecutor(java.util.concurrent.Executors.newCachedThreadPool { r -> KernelThreadsProbe.thread(r) })
        Harness(executor = executor).use { h ->
            val m = h.machine()
            val program = h.program(emptyModule())
            // コンソールの Tty は、spawn が作ると巻き戻さない（戻す対象でない）ので、先に作っておく
            m.lock.withLockValue { m.ensureConsoleLocked() }
            val before = snapshot(m, null)
            val failure = OutOfMemoryError("unable to create native thread")
            executor.failure = failure
            val thrown = assertThrows<OutOfMemoryError> { m.spawnTest(program, exiting(0, ProcessKind.WAIT_POINT)) }
            executor.failure = null
            assertSame(failure, thrown, "包まずに投げ直す")
            assertEquals(MachineState.STOPPED, m.state, "起動中でない spawn は、状態を変えない")
            assertEquals(emptyList<Int>(), m.lock.withLockValue { m.kernel.processes.all().map { it.pid } })
            assertTrue(m.lock.withLockValue { m.runtimes.isEmpty() })
            assertEquals(before, snapshot(m, null), "表・オブジェクト・枠が元に戻る")
            assertEquals(4, m.scheduler.permits.availablePermits(), "同時実行の許可を返す")
            assertEquals(0, m.cpu.waiting)
            assertEquals(null, m.cpu.currentOwner)

            // 許可が漏れていなければ、続けて作って動かせる（4 つの許可を使い切る数を回しても詰まらない）
            repeat(8) {
                val pid = m.spawnTest(program, exiting(0, ProcessKind.WAIT_POINT))
                h.tickUntilTrue("終わらない") { m.processState(pid)?.isTerminal == true }
            }
            h.tickUntilTrue("許可が戻らない") { m.scheduler.permits.availablePermits() == 4 }
            m.stop()
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `PermitPoolのsubmitは、ワーカーに渡せず投げたら取った許可を返す`() {
        val executor = FlakyExecutor(java.util.concurrent.Executors.newCachedThreadPool { r -> KernelThreadsProbe.thread(r) })
        val workers = WorkerPool(1L shl 20, 1_000, executor)
        try {
            val pool = PermitPool(2, workers)
            val failure = OutOfMemoryError("unable to create native thread")
            executor.failure = failure
            assertSame(failure, assertThrows<OutOfMemoryError> { pool.submit("a") { } })
            assertEquals(2, pool.availablePermits(), "許可を返す")
            assertEquals(0, pool.waiting)
            // 待ち行列にある仕事は、ワーカーに渡すまで許可を持たないので、外すだけでよい
            pool.acquire()
            pool.acquire()
            pool.submit("queued") { }
            assertEquals(1, pool.waiting)
            assertTrue(pool.remove("queued"))
            assertEquals(0, pool.availablePermits())
        } finally {
            executor.failure = null
            workers.shutdown()
            workers.awaitTermination(5_000)
        }
    }
}
