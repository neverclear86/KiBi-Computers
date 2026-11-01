package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.ChildSelector
import pro.ikili.kibicomputers.core.kernel.process.ExitReason
import pro.ikili.kibicomputers.core.kernel.process.ExitStatus
import pro.ikili.kibicomputers.core.kernel.process.KillResult
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriver
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.ReapResult
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 強制終了（最小限のシグナル）と Ctrl+C（#46、ADR 0046） */
class ProcessKillTest {
    private val limit = Duration.ofSeconds(30)

    private fun Machine.kill(pid: Int, signal: KillSignal = KillSignal.TERMINATE): KillResult = lock.withLockValue { killLocked(pid, signal) }

    /** ホスト呼び出しを1つ出して待つドライバー（実行されたら [ran] を立て、受け取った結果を [got] に入れる） */
    private fun hostCaller(ran: AtomicBoolean, got: AtomicReference<Any?> = AtomicReference()): ProcessDriver =
        runDriver(configure = { ctx, b ->
            b.host(ctx) { c, _, _ ->
                got.set(c.callOnMainThread { ran.set(true); "done" })
                LongArray(0)
            }
        })

    /**
     * 親（[go] が開くまで待ってから、子を刈り取り続ける）の下に [child] を作り、[reached] の状態になったら kill する。
     * 親が受け取った結果と、同じマシンのほかのプロセス・マシンの状態が変わらないことを確かめる
     */
    private fun killWhile(
        fuelPerTick: Long = 1_000_000,
        tick: Boolean = true,
        childProgram: (Harness) -> Program = { it.program(emptyModule()) },
        child: (Machine) -> ProcessDriver,
        reached: (ProcessState) -> Boolean,
    ) = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = fuelPerTick)
            m.fillFuel()
            val program = h.program(emptyModule())
            val go = Gate(m)
            val results = Collections.synchronizedList(ArrayList<ReapResult>())
            val parent = m.spawnTest(program, doing { ctx ->
                ctx.block(listOf(go.key))
                while (true) {
                    val r = ctx.waitChild(ChildSelector.Any)
                    if (r == ReapResult.NoChild) break
                    results += r
                }
            })
            val other = m.spawnTest(program, gated(Gate(m)))
            val pid = m.spawnTest(childProgram(h), child(m), parentPid = parent)
            val until = { reached(m.stateOf(pid)) }
            if (tick) h.tickUntilTrue("状態に届かない: ${m.stateOf(pid)}", until) else eventually(10_000, "状態に届かない", until)
            assertEquals(KillResult.DELIVERED, m.kill(pid))
            assertEquals(0, m.pendingHostCalls)
            go.open()
            m.fillFuel()
            h.tickUntilTrue { m.stateOf(parent).isTerminal }
            assertEquals(listOf<ReapResult>(ReapResult.Reaped(ExitStatus(pid, ExitReason.KILLED, 0, KillSignal.TERMINATE))), results.toList())
            assertFalse(m.stateOf(other).isTerminal, "ほかのプロセスは止まらない")
            assertEquals(MachineState.RUNNING, m.state)
            m.stop()
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `計算中の子をkillできる`() = killWhile(child = { runDriver() }, childProgram = { it.program(spinForever()) }, reached = { it == ProcessState.RUNNING || it == ProcessState.WAITING_FUEL })

    @Test
    fun `燃料待ちの子をkillできる`() = killWhile(fuelPerTick = 5, child = { runDriver() }, childProgram = { it.program(spinForever(sleepMillis = 1)) }, reached = { it == ProcessState.WAITING_FUEL })

    @Test
    fun `blockで待っている子をkillできる`() = killWhile(child = { gated(Gate(it)) }, reached = { it == ProcessState.WAITING_EVENT })

    @Test
    fun `待機点で待っている子をkillできる`() = killWhile(child = { gated(Gate(it), kind = ProcessKind.WAIT_POINT) }, reached = { it == ProcessState.WAITING_EVENT })

    @Test
    fun `ホスト呼び出しを待っている子をkillできる`() = killWhile(
        tick = false,
        child = { hostCaller(AtomicBoolean()) },
        childProgram = { it.program(hostCallModule()) },
        reached = { it == ProcessState.WAITING_HOST },
    )

    @Test
    fun `killするとまだ実行していないホスト呼び出しはすぐ外れ、tickを回しても実行されない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            m.fillFuel()
            val ran = AtomicBoolean()
            val pid = m.spawnTest(h.program(hostCallModule()), hostCaller(ran))
            eventually { m.stateOf(pid) == ProcessState.WAITING_HOST }
            assertEquals(1, m.pendingHostCalls)
            assertEquals(KillResult.DELIVERED, m.kill(pid))
            assertEquals(0, m.pendingHostCalls, "kill の直後に列から外れる")
            repeat(5) { h.scheduler.tick() }
            eventually { m.stateOf(pid) == ProcessState.KILLED }
            repeat(5) { h.scheduler.tick() }
            assertFalse(ran.get(), "止めたプロセスのホスト呼び出しは実行しない")
            assertEquals(KillSignal.TERMINATE, m.process(pid)!!.termSignal)
        }
    }

    @Test
    fun `2つのうち1つだけkillすると、もう片方のホスト呼び出しは残って実行される`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            m.fillFuel()
            val program = h.program(hostCallModule())
            val ranA = AtomicBoolean()
            val ranB = AtomicBoolean()
            val gotB = AtomicReference<Any?>()
            val a = m.spawnTest(program, hostCaller(ranA))
            val b = m.spawnTest(program, hostCaller(ranB, gotB))
            eventually { m.stateOf(a) == ProcessState.WAITING_HOST && m.stateOf(b) == ProcessState.WAITING_HOST }
            assertEquals(2, m.pendingHostCalls)
            m.kill(a)
            assertEquals(1, m.pendingHostCalls)
            h.tickUntilTrue { m.stateOf(b).isTerminal }
            assertEquals(ProcessState.EXITED, m.stateOf(b))
            assertEquals("done", gotB.get())
            assertTrue(ranB.get())
            assertFalse(ranA.get())
            assertEquals(ProcessState.KILLED, m.stateOf(a))
        }
    }

    @Test
    fun `Ctrl+Cでもまだ実行していないホスト呼び出しは実行されない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            m.fillFuel()
            val ran = AtomicBoolean()
            val pid = m.spawnTest(h.program(hostCallModule()), hostCaller(ran))
            eventually { m.stateOf(pid) == ProcessState.WAITING_HOST }
            m.lock.withLockValue { m.consoleTtyLocked().foregroundPid = pid }
            m.offerConsoleInput(byteArrayOf(0x03))
            assertEquals(0, m.pendingHostCalls)
            h.tickUntilTrue { m.stateOf(pid).isTerminal }
            repeat(5) { h.scheduler.tick() }
            assertFalse(ran.get())
            assertEquals(ExitStatus(pid, ExitReason.KILLED, 0, KillSignal.INTERRUPT), m.exitStatusOf(pid))
        }
    }

    @Test
    fun `止めた後に積もうとしたホスト呼び出しは積まれずに断られる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val pid = m.spawnTest(h.program(emptyModule()), gated(Gate(m)))
            h.tickUntilTrue { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            val ran = AtomicBoolean()
            // kill と同じロックの中で、止めた直後に積む（確かめと積み込みが別のロックだと、ここで積まれてしまう）
            val future = m.lock.withLockValue {
                assertEquals(KillResult.DELIVERED, m.killLocked(pid, KillSignal.TERMINATE))
                m.submitHostCall(pid) { ran.set(true) }
            }
            assertEquals(0, m.pendingHostCalls)
            val e = runCatching { future.get() }.exceptionOrNull()
            assertTrue(e is ExecutionException && e.cause is ProcessKilledException, e.toString())
            h.tickUntilTrue { m.stateOf(pid).isTerminal }
            // 終わったプロセスからも積めない
            assertTrue(runCatching { m.submitHostCall(pid) { ran.set(true) }.get() }.exceptionOrNull()?.cause is ProcessKilledException)
            h.scheduler.tick()
            assertFalse(ran.get())
        }
    }

    @Test
    fun `無視したシグナルでは止まらず、KILLは無視できず、無視は子に引き継がない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val program = h.program(emptyModule())
            val gate = Gate(m)
            val grandchild = AtomicInteger()
            val ignoreKill = AtomicReference<Throwable>()
            val pid = m.spawnTest(program, doing { ctx ->
                ctx.setSignalIgnored(KillSignal.INTERRUPT, true)
                ctx.setSignalIgnored(KillSignal.QUIT, true)
                ctx.setSignalIgnored(KillSignal.QUIT, false)
                ignoreKill.set(runCatching { ctx.setSignalIgnored(KillSignal.KILL, true) }.exceptionOrNull())
                grandchild.set(ctx.spawn(program, gated(gate)))
                ctx.block(listOf(gate.key))
            })
            h.tickUntilTrue { grandchild.get() != 0 && m.stateOf(pid) == ProcessState.WAITING_EVENT && m.stateOf(grandchild.get()) == ProcessState.WAITING_EVENT }
            assertTrue(ignoreKill.get() is IllegalArgumentException)
            assertEquals(KillResult.IGNORED, m.kill(pid, KillSignal.INTERRUPT))
            h.tickUntilTrue { true }
            assertFalse(m.stateOf(pid).isTerminal)
            assertEquals(KillResult.DELIVERED, m.kill(grandchild.get(), KillSignal.INTERRUPT), "無視の設定は子に引き継がない")
            h.tickUntilTrue { m.stateOf(grandchild.get()).isTerminal }
            assertEquals(KillResult.DELIVERED, m.kill(pid, KillSignal.KILL))
            h.tickUntilTrue { m.stateOf(pid).isTerminal }
            assertEquals(ExitStatus(pid, ExitReason.KILLED, 0, KillSignal.KILL), m.exitStatusOf(pid))
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `終わったプロセスはALREADY_EXITED、ないpidはNOT_FOUND`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val pid = m.spawnTest(h.program(emptyModule()), exiting(0))
            h.tickUntilTrue { m.stateOf(pid).isTerminal }
            assertEquals(KillResult.ALREADY_EXITED, m.kill(pid))
            assertEquals(KillResult.NOT_FOUND, m.kill(9999))
        }
    }

    @Test
    fun `自分へのkillはProcessKilledExceptionでKILLEDになる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val thrown = AtomicReference<Throwable>()
            val pid = m.spawnTest(h.program(emptyModule()), doing { ctx ->
                try {
                    ctx.kill(ctx.pid, KillSignal.TERMINATE)
                } catch (t: Throwable) {
                    thrown.set(t)
                    throw t
                }
            })
            h.tickUntilTrue { m.stateOf(pid).isTerminal }
            assertTrue(thrown.get() is ProcessKilledException)
            assertEquals(ExitStatus(pid, ExitReason.KILLED, 0, KillSignal.TERMINATE), m.exitStatusOf(pid))
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `Ctrl+CとCtrl+バックスラッシュは前のプロセスをINTERRUPTとQUITで止める`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val program = h.program(emptyModule())
            val bystander = m.spawnTest(program, gated(Gate(m)))
            for ((byte, signal) in listOf(0x03.toByte() to KillSignal.INTERRUPT, 0x1C.toByte() to KillSignal.QUIT)) {
                val pid = m.spawnTest(program, gated(Gate(m), kind = ProcessKind.WAIT_POINT))
                h.tickUntilTrue { m.stateOf(pid) == ProcessState.WAITING_EVENT }
                m.lock.withLockValue { m.consoleTtyLocked().foregroundPid = pid }
                m.offerConsoleInput(byteArrayOf(byte))
                h.tickUntilTrue { m.stateOf(pid).isTerminal }
                assertEquals(ExitStatus(pid, ExitReason.KILLED, 0, signal), m.exitStatusOf(pid))
                // 前のプロセスが終わっていれば何も止めない
                m.offerConsoleInput(byteArrayOf(byte))
            }
            m.lock.withLockValue { m.consoleTtyLocked().foregroundPid = 0 }
            m.offerConsoleInput(byteArrayOf(0x03))
            h.tickUntilTrue { true }
            assertFalse(m.stateOf(bystander).isTerminal, "前のプロセスでないものは止まらない")
            m.stop()
            assertTrue(m.awaitTermination(5_000))
        }
    }
}
