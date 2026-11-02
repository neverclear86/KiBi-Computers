package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.process.HostCompletion
import pro.ikili.kibicomputers.core.kernel.process.HostWait
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.FakeModule
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** ホスト呼び出しとプログラムの読み込みを、スレッドを持たずに待つ（#358） */
class HostWaitTest {
    private val limit = Duration.ofSeconds(60)

    private fun Harness.tickUntil(message: String = "条件がそろわない", condition: () -> Boolean) = eventually(10_000, message) {
        scheduler.tick()
        Thread.sleep(1)
        condition()
    }

    /** スレッドを持たず、WASM のスタックもなく、待機点で待っている */
    private fun Machine.parkedAtWaitPoint(pid: Int): Boolean = lock.withLockValue {
        val rt = runtime(pid) ?: return@withLockValue false
        rt.process.state == ProcessState.WAITING_EVENT && rt.thread == null && !rt.stackActive
    }

    /** 表に残っている、ホスト側の処理の待ちのオブジェクトの数 */
    private fun Machine.hostCompletions(): Int = lock.withLockValue {
        var n = 0
        kernel.objects.forEachObject { if (it is HostCompletion) n++ }
        n
    }

    /** [calls] 個のホスト呼び出しを待たずに続けて出し、まとめて待ってから、結果を [out] に入れて終わる待機点型のドライバー */
    private fun submitter(out: AtomicReference<List<Any?>>, calls: List<HostCall<Any?>>): TestDriver {
        val waits = AtomicReference<List<HostWait<Any?>>>()
        return TestDriver(
            ProcessKind.WAIT_POINT,
            onResume = { ctx, _ ->
                val ws = waits.get()
                if (!ws.all { it.future.isDone }) {
                    // まだ終わっていないものがあれば待ち直す
                    StepResult.Wait(ws.map { it.key })
                } else {
                    out.set(ws.map { w -> runCatching { ctx.takeHostResult(w) }.fold({ it }, { it }) })
                    StepResult.Exit(0)
                }
            },
        ) { ctx ->
            val ws = calls.map { ctx.submitHostCall(it) }
            waits.set(ws)
            StepResult.Wait(ws.map { it.key })
        }
    }

    @Test
    fun `待機点型はホスト呼び出しをスレッドを持たずに待ち、結果と例外を受け取る`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine().also { it.fillFuel() }
            val ranOn = AtomicReference<Thread>()
            val out = AtomicReference<List<Any?>>()
            val pid = m.spawnTest(
                h.program(emptyModule()),
                submitter(
                    out,
                    listOf(
                        HostCall<Any?> { ranOn.set(Thread.currentThread()); 42 },
                        HostCall<Any?> { throw IllegalArgumentException("ワールドで失敗") },
                    ),
                ),
            )
            // tick を回さない間は、呼び出しが実行されずに待機点で待つ
            eventually { m.parkedAtWaitPoint(pid) && m.pendingHostCalls == 2 }
            assertTrue(m.isAtWaitPoint, "ホスト呼び出しを待つ待機点型は待機点にいる")
            assertEquals(2, m.hostCompletions())
            assertEquals(h.scheduler.config.maxConcurrentMachines, h.scheduler.permits.availablePermits(), "許可を返していない")
            h.tickUntil { m.stateOf(pid) == ProcessState.EXITED }
            assertSame(Thread.currentThread(), ranOn.get())
            val results = out.get()
            assertEquals(42, results[0])
            assertTrue(results[1] is IllegalArgumentException, results.toString())
            // 待ちのオブジェクトは閉じた
            assertEquals(0, m.hostCompletions())
        }
    }

    @Test
    fun `続けて出したホスト呼び出しは出した順に実行する`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(maxHostCallsPerTick = 2).also { it.fillFuel() }
            val order = Collections.synchronizedList(ArrayList<Int>())
            val out = AtomicReference<List<Any?>>()
            val pid = m.spawnTest(h.program(emptyModule()), submitter(out, (1..5).map { i -> HostCall<Any?> { order += i; i * 10 } }))
            eventually { m.parkedAtWaitPoint(pid) && m.pendingHostCalls == 5 }
            h.tickUntil { m.stateOf(pid) == ProcessState.EXITED }
            assertEquals(listOf(1, 2, 3, 4, 5), order.toList())
            assertEquals(listOf<Any?>(10, 20, 30, 40, 50), out.get())
        }
    }

    @Test
    fun `ホスト呼び出しを待つ待機点型をkillすると、実行していない呼び出しは実行せず、待ちのオブジェクトも閉じる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine().also { it.fillFuel() }
            val ran = AtomicBoolean()
            val out = AtomicReference<List<Any?>>()
            val pid = m.spawnTest(h.program(emptyModule()), submitter(out, listOf(HostCall<Any?> { ran.set(true) })))
            eventually { m.parkedAtWaitPoint(pid) }
            m.lock.withLockValue { m.killLocked(pid, KillSignal.KILL) }
            eventually { m.stateOf(pid).isTerminal }
            assertEquals(ProcessState.KILLED, m.stateOf(pid))
            repeat(3) { h.scheduler.tick() }
            assertFalse(ran.get())
            assertNull(out.get())
            assertEquals(0, m.pendingHostCalls)
            assertEquals(0, m.hostCompletions())
            assertTrue(m.lock.withLockValue { m.rt(pid).hostWaits.isEmpty() })
        }
    }

    @Test
    fun `closeHostWaitで閉じたホスト呼び出しは実行しない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val ran = AtomicBoolean()
            val ranOther = AtomicBoolean()
            val pid = m.spawnTest(
                h.program(emptyModule()),
                TestDriver(ProcessKind.WAIT_POINT, onResume = { ctx, _ -> StepResult.Exit(0) }) { ctx ->
                    val dropped = ctx.submitHostCall { ran.set(true) }
                    val kept = ctx.submitHostCall { ranOther.set(true) }
                    ctx.closeHostWait(dropped)
                    // もう閉じていれば何もしない
                    ctx.closeHostWait(dropped)
                    StepResult.Wait(listOf(kept.key))
                },
            )
            h.tickUntil { m.stateOf(pid) == ProcessState.EXITED }
            assertFalse(ran.get())
            assertTrue(ranOther.get())
        }
    }

    @Test
    fun `待機点型がstepの中で止まって待つ口を呼ぶとCRASHEDになる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val ran = AtomicBoolean()
            val pid = m.spawnTest(
                h.program(emptyModule()),
                TestDriver(ProcessKind.WAIT_POINT) { ctx ->
                    ctx.callOnMainThread { ran.set(true) }
                    StepResult.Exit(0)
                },
            )
            h.tickUntil { m.stateOf(pid).isTerminal }
            assertEquals(ProcessState.CRASHED, m.stateOf(pid))
            assertTrue(m.rt(pid).lastError is IllegalStateException, m.rt(pid).lastError.toString())
            assertFalse(ran.get())
        }
    }

    @Test
    fun `ホスト呼び出しを待つ200台がいても、ワーカーのスレッドは許可の数程度`() = timeout(limit) {
        val permits = 2
        Harness(SchedulerConfig.builder().maxConcurrentMachines(permits).maxBlockingProcesses(8).build()).use { h ->
            val program = h.program(emptyModule())
            val machines = (1..200).map { h.machine().also { it.fillFuel() } }
            val outs = machines.map { AtomicReference<List<Any?>>() }
            val pids = machines.mapIndexed { i, m -> m.spawnTest(program, submitter(outs[i], listOf(HostCall<Any?> { i }))) }
            eventually(30_000, "待機点にそろわない") { machines.indices.all { machines[it].parkedAtWaitPoint(pids[it]) } }
            assertTrue(machines.all { it.isAtWaitPoint })
            // ワーカーは許可の数＋定数（WorkerThreadsTest と同じ見込み）。待っている台はスレッドを持たない
            assertTrue(h.scheduler.workers.poolSize <= permits + 3, "ワーカーの数: ${h.scheduler.workers.poolSize}")
            assertEquals(permits, h.scheduler.permits.availablePermits())
            h.tickUntil("全部が終わらない") { machines.indices.all { machines[it].stateOf(pids[it]) == ProcessState.EXITED } }
            assertEquals(machines.indices.map { listOf<Any?>(it) }, outs.map { it.get() })
        }
    }

    @Test
    fun `起動の手順はinitの読み込みを待つ間、起動用のスレッドを持たない`() = timeout(limit) {
        val machinesCount = 8
        // 起動用のスレッドは 2 本。台数はそれより多い
        val config = SchedulerConfig.builder().maxConcurrentMachines(2).maxBlockingProcesses(8).compilerThreads(1).build()
        Harness(config).use { h ->
            val gate = CountDownLatch(1)
            h.runtime.beforeLoad = { bytes -> if (String(bytes).startsWith("init-")) gate.await() }
            try {
                val started = AtomicInteger()
                val init = FakeModule.builder().fuelMetered().export("_start", VOID) { _, _ -> started.incrementAndGet(); LongArray(0) }.build()
                val disks = (0 until machinesCount).associate { i ->
                    val bytes = "init-$i".toByteArray().also { h.runtime.define(it, init) }
                    "d$i" to TestMemoryFileSystem().also { fs ->
                        fs.createDirectory(VfsPath.parse("/sbin"))
                        val node = fs.createFile(VfsPath.parse(BootConfig.INIT_PATH))
                        fs.write(node, 0, bytes, 0, bytes.size)
                    }
                }
                h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> disks[id]?.let { BootVolume(it, readOnly = false) } }
                val machines = disks.keys.map { id -> h.machine().also { assertTrue(it.boot(BootConfig.builder().bootDisks(listOf(id)).build())) } }
                // 全台の init の読み込みが積まれ、起動用のスレッドはどれも動いていない
                eventually(10_000, "読み込みが積まれない: ${h.scheduler.programs.loadingCount}") {
                    h.scheduler.programs.loadingCount == machinesCount && h.scheduler.bootExecutor.activeCount == 0
                }
                assertTrue(machines.all { it.isBooting })
                // 1 台は待つ間に取り消す
                machines[0].stop()
                eventually { !machines[0].isBooting }
                assertEquals(MachineState.STOPPED, machines[0].state)
                gate.countDown()
                h.tickUntil("起動が終わらない") { machines.none { it.isBooting } }
                // 取り消した台は init を作らない。ほかは init が動いて終わり、STOPPED に戻る
                h.tickUntil { machines.all { it.state == MachineState.STOPPED } && started.get() == machinesCount - 1 }
                repeat(5) { h.scheduler.tick() }
                assertEquals(machinesCount - 1, started.get())
            } finally {
                gate.countDown()
            }
        }
    }
}
