package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.KernelLinkEnv
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentHost
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentProgram
import pro.ikili.kibicomputers.core.runtime.component.link.HostFunction
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.call
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertNull
import pro.ikili.kibicomputers.core.kernel.process.KillResult
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** コンポーネントのプロセス（#263）: カーネルの中でリンクし、core インスタンスをまたいで動かす。読み込みの途中の失敗（#692）も確かめる */
class ComponentProcessTest {
    private val limit = Duration.ofSeconds(60)
    private val pids = AtomicInteger(-1)
    private val hostCalls = AtomicInteger()

    /** ホストの関数 `test:host/kernel.pid`（呼んだプロセスの pid を返す） */
    private val host = ComponentHost.of(
        LinkTesting.host(
            "test:host/kernel",
            "pid" to HostFunction(LinkTesting.fn(emptyList(), CanonType.U32)) { call, _ ->
                hostCalls.incrementAndGet()
                CanonValue.I32((call.env as KernelLinkEnv).ctx.pid)
            },
        ),
    )

    /** 2 つの core インスタンス: b の `run` が a の `spin` を呼び、ホストの `pid` も呼ぶ */
    private val text = """
        (component
          (import "test:host/kernel" (instance ${'$'}h (export "pid" (func (result u32)))))
          (alias export ${'$'}h "pid" (func ${'$'}pid))
          (core func ${'$'}pid_lower (canon lower (func ${'$'}pid)))
          (core module ${'$'}a
            (import "host" "pid" (func ${'$'}pid (result i32)))
            (func (export "spin") (param i32) (result i32)
              (local ${'$'}i i32)
              (loop ${'$'}l
                (local.set ${'$'}i (i32.add (local.get ${'$'}i) (i32.const 1)))
                (br_if ${'$'}l (i32.lt_u (local.get ${'$'}i) (local.get 0))))
              (i32.add (local.get ${'$'}i) (call ${'$'}pid))))
          (core instance ${'$'}hi (export "pid" (func ${'$'}pid_lower)))
          (core instance ${'$'}ia (instantiate ${'$'}a (with "host" (instance ${'$'}hi))))
          (core module ${'$'}b
            (import "a" "spin" (func ${'$'}spin (param i32) (result i32)))
            (import "host" "pid" (func ${'$'}pid (result i32)))
            (func (export "run") (param i32) (result i32)
              (i32.add (call ${'$'}spin (local.get 0)) (call ${'$'}pid))))
          (core instance ${'$'}ib (instantiate ${'$'}b (with "a" (instance ${'$'}ia)) (with "host" (instance ${'$'}hi))))
          (func (export "run") (param "n" u32) (result u32) (canon lift (core func ${'$'}ib "run"))))
    """

    private fun program(): Program = Program.component(LinkTesting.load(text, host, metered = true))

    @Test
    fun `coreインスタンスをまたぐ呼び出しと燃料切れでマシンを壊さない`() {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 2_000)
            val result = AtomicInteger(-1)
            val driver = TestDriver(ProcessKind.BLOCKING) { ctx ->
                val c = ctx.component
                assertEquals(2, c.coreInstances.size)
                assertSame(ctx.instance, c.coreInstances[0])
                pids.set(ctx.pid)
                result.set((c.call("run", args = listOf(CanonValue.I32(50_000))) as CanonValue.I32).value)
                StepResult.Exit(7)
            }
            val rt = m.spawnRt(program(), driver)
            eventually(20_000, "終わらない") {
                h.scheduler.tick()
                rt.process.state.isTerminal || m.state == MachineState.CRASHED
            }
            assertNotEquals(MachineState.CRASHED, m.state, "マシンが壊れた: ${rt.lastError}")
            assertEquals(ProcessState.EXITED, rt.process.state, "${rt.lastError}")
            assertEquals(7, rt.process.exitCode)
            assertEquals(50_000 + 2 * pids.get(), result.get())
            assertEquals(2, hostCalls.get())
            assertEquals(0, driver.configureCalls.get(), "コンポーネントのプロセスでは configure を呼ばない")
        }
    }

    @Test
    fun `スライスを使い切っても、core インスタンスをまたぐ呼び出しはその場で足すか譲るかして最後まで進む`() {
        for (contend in listOf(false, true)) {
            Harness().use { h ->
                // 1 tick の燃料 2,000 をスライス 200 に分ける（#47）。共有の燃料（WasmSharedFuel）の中でも、手持ちを足し直せること
                val m = h.machine(fuelPerTick = 2_000, fuelSlice = 200)
                if (contend) m.spawnTest(h.program(spinForever()), runDriver())
                val renewals0 = h.scheduler.fuelSliceRenewals.get()
                val yields0 = h.scheduler.fuelYields.get()
                val result = AtomicInteger(-1)
                val driver = TestDriver(ProcessKind.BLOCKING) { ctx ->
                    pids.set(ctx.pid)
                    result.set((ctx.component!!.call("run", args = listOf(CanonValue.I32(50_000))) as CanonValue.I32).value)
                    StepResult.Exit(7)
                }
                val rt = m.spawnRt(program(), driver)
                eventually(30_000, "終わらない（contend=$contend）") {
                    h.scheduler.tick()
                    rt.process.state.isTerminal || m.state == MachineState.CRASHED
                }
                assertNotEquals(MachineState.CRASHED, m.state, "マシンが壊れた: ${rt.lastError}")
                assertEquals(ProcessState.EXITED, rt.process.state, "${rt.lastError}")
                assertEquals(50_000 + 2 * pids.get(), result.get())
                if (contend) {
                    assertTrue(h.scheduler.fuelYields.get() > yields0, "譲っていない")
                } else {
                    assertTrue(h.scheduler.fuelSliceRenewals.get() > renewals0, "その場で足していない")
                }
                m.stop()
            }
        }
    }

    @Test
    fun `既定のドライバーはコンポーネントをNOEXECで断る`() {
        val e = assertThrows<FsException> { KernelDrivers.DEFAULT.create(program()) }
        assertEquals(Errno.NOEXEC, e.errno)
    }

    @Test
    fun `コンポーネントのProgramはcoreモジュールを持たない`() {
        val p = program()
        assertTrue(p.isFuelMetered)
        assertThrows<IllegalStateException> { p.module }
        val unmetered = Program.component(ComponentProgram.load(LinkTesting.runtime, LinkTesting.wat("(component)"), ComponentHost.NONE, metered = false))
        assertEquals(false, unmetered.isFuelMetered)
    }

    @Test
    fun `ホストの関数の中からプロセスのコンポーネントを呼ぶとプロセスだけが落ちる`() {
        // ホストの関数が KernelLinkEnv から同じ LinkedComponent を引いて call を呼ぶ（#691）
        val reentering = ComponentHost.of(
            LinkTesting.host(
                "test:host/kernel",
                "pid" to HostFunction(LinkTesting.fn(emptyList(), CanonType.U32)) { call, _ ->
                    val c = (call.env as KernelLinkEnv).ctx.component
                    c.call(c.function("run")!!, listOf(CanonValue.I32(1)))
                },
            ),
        )
        Harness().use { h ->
            val m = h.machine()
            val driver = TestDriver(ProcessKind.BLOCKING) { ctx ->
                ctx.component.call("run", args = listOf(CanonValue.I32(10)))
                StepResult.Exit(0)
            }
            val rt = m.spawnRt(Program.component(LinkTesting.load(text, reentering, metered = true)), driver)
            eventually(20_000, "終わらない") {
                h.scheduler.tick()
                rt.process.state.isTerminal || m.state == MachineState.CRASHED
            }
            assertNotEquals(MachineState.CRASHED, m.state, "マシンが壊れた: ${rt.lastError}")
            assertEquals(ProcessState.CRASHED, rt.process.state)
            assertTrue(rt.lastError is IllegalStateException, "${rt.lastError}")
        }
    }

    /**
     * 1 つ目の core モジュール: メモリに書き込む（RAM は書き込んだ区画で数える。#407）。[start] は 2 つ目の start 関数の本体
     * （`$mark` を呼ぶと [marks] が増える。2 つ目のインスタンスを作ったかどうかが分かる）
     */
    private fun failingLoad(start: String, firstStart: String = "") = """
        (component
          (import "test:host/kernel" (instance ${'$'}h (export "die" (func)) (export "mark" (func))))
          (alias export ${'$'}h "die" (func ${'$'}die))
          (alias export ${'$'}h "mark" (func ${'$'}mark))
          (core func ${'$'}die_lower (canon lower (func ${'$'}die)))
          (core func ${'$'}mark_lower (canon lower (func ${'$'}mark)))
          (core module ${'$'}a
            (import "host" "die" (func ${'$'}die))
            (memory (export "mem") 1)
            (data (i32.const 0) "hello")
            (func ${'$'}s (i32.store (i32.const 8192) (i32.const 1)) $firstStart)
            (start ${'$'}s))
          (core instance ${'$'}hi (export "die" (func ${'$'}die_lower)) (export "mark" (func ${'$'}mark_lower)))
          (core instance ${'$'}ia (instantiate ${'$'}a (with "host" (instance ${'$'}hi))))
          (core module ${'$'}b
            (import "host" "mark" (func ${'$'}mark))
            (func ${'$'}s $start)
            (start ${'$'}s))
          (core instance ${'$'}ib (instantiate ${'$'}b (with "host" (instance ${'$'}hi)))))
    """

    private val dies = AtomicInteger()
    private val marks = AtomicInteger()

    /** ホストの関数 `test:host/kernel.die`（呼んだプロセスを自分で kill する） */
    private val dieHost = ComponentHost.of(
        LinkTesting.host(
            "test:host/kernel",
            "die" to HostFunction(LinkTesting.fn(emptyList(), null)) { call, _ ->
                dies.incrementAndGet()
                val ctx = (call.env as KernelLinkEnv).ctx
                assertEquals(KillResult.DELIVERED, ctx.kill(ctx.pid, KillSignal.TERMINATE))
                null
            },
            "mark" to HostFunction(LinkTesting.fn(emptyList(), null)) { _, _ ->
                marks.incrementAndGet()
                null
            },
        ),
    )

    private val startTraps by lazy { Program.component(LinkTesting.load(failingLoad("unreachable"), dieHost, metered = true)) }
    private val startSpins by lazy { Program.component(LinkTesting.load(failingLoad("(loop ${'$'}l (br ${'$'}l))"), dieHost, metered = true)) }
    private val killsItself by lazy { Program.component(LinkTesting.load(failingLoad("(call ${'$'}mark)", firstStart = "(call ${'$'}die)"), dieHost, metered = true)) }

    /** 読み込みの途中で終わったプロセスが、作ったものをみな返したこと */
    private fun assertCleanedUp(m: Machine, rt: ProcessRuntime, before: Long) {
        assertNotEquals(MachineState.CRASHED, m.state, "マシンが壊れた: ${rt.lastError}")
        assertEquals(before, m.memoryAccount.usedBytes, "マシンの RAM を返す")
        assertEquals(0L, rt.memory.usedBytes, "プロセスの RAM を返す")
        lockedCheck(m) {
            assertTrue(rt.instances.isEmpty(), "作った core インスタンスを閉じる")
            assertNull(rt.component)
        }
    }

    private fun lockedCheck(m: Machine, block: () -> Unit) = m.lock.withLockValue { block() }

    @Test
    fun `2つ目のcoreインスタンスのstart関数がトラップしたら、プロセスだけがCRASHEDになり作ったものを返す`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val before = m.memoryAccount.usedBytes
            val rt = m.spawnRt(startTraps, TestDriver(ProcessKind.BLOCKING) { StepResult.Exit(0) })
            h.tickUntilTrue("終わらない") { rt.process.state.isTerminal || m.state == MachineState.CRASHED }
            assertEquals(ProcessState.CRASHED, rt.process.state, "${rt.lastError}")
            assertTrue(rt.lastError is WasmTrapException, "${rt.lastError}")
            assertCleanedUp(m, rt, before)
        }
    }

    @Test
    fun `作る途中で燃料を待っているところをkillすると、KILLEDになり作ったものを返す`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 2_000)
            val before = m.memoryAccount.usedBytes
            val rt = m.spawnRt(startSpins, TestDriver(ProcessKind.BLOCKING) { StepResult.Exit(0) })
            h.tickUntilTrue("燃料待ちにならない: ${rt.process.state}") { rt.process.state == ProcessState.WAITING_FUEL }
            assertTrue(m.memoryAccount.usedBytes > before, "1 つ目のインスタンスが書き込んだ分を数えている")
            assertEquals(KillResult.DELIVERED, m.lock.withLockValue { m.killLocked(rt.process.pid, KillSignal.TERMINATE) })
            h.tickUntilTrue("終わらない") { rt.process.state.isTerminal }
            assertEquals(ProcessState.KILLED, rt.process.state, "${rt.lastError}")
            assertCleanedUp(m, rt, before)
        }
    }

    @Test
    fun `作る途中でスライスを使い切って譲っているところをkillすると、KILLEDになり作ったものを返す`() = timeout(limit) {
        Harness().use { h ->
            // start 関数が回り続ける間、スレッドを持ったまま譲り合う（作る途中は巻き戻せない。#47）
            val m = h.machine(fuelPerTick = 2_000_000, fuelSlice = 20_000)
            val before = m.memoryAccount.usedBytes
            m.spawnTest(h.program(spinForever()), runDriver())
            val yields0 = h.scheduler.fuelYields.get()
            val rt = m.spawnRt(startSpins, TestDriver(ProcessKind.BLOCKING) { StepResult.Exit(0) })
            h.scheduler.tick()
            eventually(30_000, "譲らない") { h.scheduler.fuelYields.get() - yields0 >= 3 }
            assertTrue(m.memoryAccount.usedBytes > before, "1 つ目のインスタンスが書き込んだ分を数えている")
            assertEquals(KillResult.DELIVERED, m.lock.withLockValue { m.killLocked(rt.process.pid, KillSignal.TERMINATE) })
            h.tickUntilTrue("終わらない") { rt.process.state.isTerminal }
            assertEquals(ProcessState.KILLED, rt.process.state, "${rt.lastError}")
            assertEquals(0L, rt.memory.usedBytes, "プロセスの RAM を返す")
            lockedCheck(m) { assertTrue(rt.instances.isEmpty(), "作った core インスタンスを閉じる") }
            m.stop()
        }
    }

    @Test
    fun `インスタンスを作る間にkillされたら、KILLEDになり作ったものを返す`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val before = m.memoryAccount.usedBytes
            val rt = m.spawnRt(killsItself, TestDriver(ProcessKind.BLOCKING) { StepResult.Exit(0) })
            h.tickUntilTrue("終わらない") { rt.process.state.isTerminal || m.state == MachineState.CRASHED }
            assertEquals(1, dies.get())
            assertEquals(0, marks.get(), "kill の印を見て、2 つ目のインスタンスを作らずに止まる")
            // 1 つ目の start の中で kill した。作り終えた直後に印を見て止める（割り込みでトラップしても CRASHED にはしない）
            assertEquals(ProcessState.KILLED, rt.process.state, "${rt.lastError}")
            assertCleanedUp(m, rt, before)
        }
    }
}
