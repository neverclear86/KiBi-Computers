package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.process.CORE_INSTANCE_OVERHEAD_BYTES
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
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertNull
import pro.ikili.kibicomputers.core.kernel.process.KillResult
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.kernel.process.crashReason
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
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
            // 終了コードは片付け（finishLocked）が状態の後に書くので、マシンのロックの中で読む（#1031）
            assertEquals(7, lockedCheck(m) { rt.process.exitCode })
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
    fun `既定のドライバーはwasi-cli-runを持たないコンポーネントをNOEXECで断る`() {
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
        // 終わりの片付け（finishLocked）はマシンのロックの中で、状態を変えてから進むので、勘定もロックの中で読む（#1031）
        lockedCheck(m) {
            assertEquals(before, m.memoryAccount.usedBytes, "マシンの RAM を返す")
            assertEquals(0L, rt.memory.usedBytes, "プロセスの RAM を返す")
            assertTrue(rt.instances.isEmpty(), "作った core インスタンスを閉じる")
            assertNull(rt.component)
        }
    }

    private fun <T> lockedCheck(m: Machine, block: () -> T): T = m.lock.withLockValue { block() }

    /**
     * 作っている途中のプロセスが、core インスタンスの固定費（[pro.ikili.kibicomputers.core.kernel.process.CORE_INSTANCE_OVERHEAD_BYTES]。
     * 仮の値で、確定は #251）に加えて、1 つ目のインスタンスが書き込んだ分も RAM に数えていること
     */
    private fun assertWrittenCounted(m: Machine, rt: ProcessRuntime, before: Long) {
        val overhead = m.lock.withLockValue { rt.coreInstanceOverheadBytes }
        assertTrue(overhead > 0, "固定費を数えていない")
        assertTrue(m.memoryAccount.usedBytes > before + overhead, "固定費のほかに、1 つ目のインスタンスが書き込んだ分を数えている")
    }

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
            assertWrittenCounted(m, rt, before)
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
            assertWrittenCounted(m, rt, before)
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

    // ---- 燃料（#270） ----

    /** 2 つの core インスタンスの start 関数が、それぞれ [na]・[nb] 回まわる（計測あり） */
    private fun twoStarts(na: Int, nb: Int): Program {
        fun module(name: String, n: Int) = """
          (core module ${'$'}$name
            (func ${'$'}s
              (local ${'$'}i i32)
              (loop ${'$'}l
                (local.set ${'$'}i (i32.add (local.get ${'$'}i) (i32.const 1)))
                (br_if ${'$'}l (i32.lt_u (local.get ${'$'}i) (i32.const $n)))))
            (start ${'$'}s))
        """
        val text = """
            (component
              ${module("a", na)}
              ${module("b", nb)}
              (core instance (instantiate ${'$'}a))
              (core instance (instantiate ${'$'}b)))
        """
        return Program.component(LinkTesting.load(text, ComponentHost.NONE, metered = true))
    }

    /** [program] を 1 tick 分の配分の中で最後まで動かし、終わった後のマシンの燃料（[KernelState.fuel]）を返す */
    private fun fuelLeftAfter(program: Program): Long = Harness().use { h ->
        val m = h.machine(fuelPerTick = 10_000_000)
        val rt = m.spawnRt(program, TestDriver(ProcessKind.BLOCKING) { StepResult.Exit(0) })
        h.scheduler.tick()
        // 補充させないよう、tick を進めずに待つ
        eventually(20_000, "終わらない") { rt.process.state.isTerminal }
        assertEquals(ProcessState.EXITED, rt.process.state, "${rt.lastError}")
        m.lock.withLockValue { m.kernel.fuel }
    }

    @Test
    fun `どのcoreインスタンスのstart関数の消費も、マシンの1つの配分から引かれる`() = timeout(limit) {
        val base = fuelLeftAfter(twoStarts(1, 1))
        val onlyA = fuelLeftAfter(twoStarts(100_000, 1))
        val onlyB = fuelLeftAfter(twoStarts(1, 100_000))
        val both = fuelLeftAfter(twoStarts(100_000, 100_000))
        assertTrue(base - onlyA > 100_000, "a の start 関数の分が引かれていない: ${base - onlyA}")
        assertTrue(base - onlyB > 100_000, "b の start 関数の分が引かれていない: ${base - onlyB}")
        // 保存則: a と b の消費の和が、両方を回したときの消費になる（どちらの分も漏れず、二重にも引かない）
        assertEquals((base - onlyA) + (base - onlyB), base - both)
    }

    @Test
    fun `コンポーネントのプロセスはctxのinstantiateを呼べず、固定費を払わずにcoreインスタンスを足せない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val inner = h.program(emptyModule())
            val error = AtomicReference<Throwable?>()
            val counted = AtomicReference<Pair<Int, Long>>()
            val driver = TestDriver(ProcessKind.BLOCKING) { ctx ->
                val rt = m.rt(ctx.pid)
                val state = { m.lock.withLockValue { rt.instances.size to rt.coreInstanceOverheadBytes } }
                val before = state()
                error.set(runCatching { ctx.instantiate(inner.module) { } }.exceptionOrNull())
                counted.set(before)
                assertEquals(before, state(), "断られたら、core インスタンスも固定費も増えない")
                StepResult.Exit(0)
            }
            val rt = m.spawnRt(program(), driver)
            h.tickUntilTrue("終わらない") { rt.process.state.isTerminal || m.state == MachineState.CRASHED }
            assertEquals(ProcessState.EXITED, rt.process.state, "${rt.lastError}")
            val e = error.get()
            assertTrue(e is IllegalStateException && e.message!!.contains("コンポーネントのプロセスは instantiate を呼べない"), "$e")
            assertEquals(2, counted.get().first, "プログラムの 2 つの core インスタンスだけ")
            assertEquals(2 * CORE_INSTANCE_OVERHEAD_BYTES, counted.get().second, "固定費もその 2 つ分だけ")
        }
    }

    // ---- 深い相互再帰（#705） ----

    /**
     * 親の `f`（`n` が 0 なら 100、そうでなければ子の `g` を `n - 1` で呼んで 1 足す）と、子の `g`（親の `f` を呼ぶだけ）の相互再帰。
     * 仕様は同期の再入をトラップにしない（ADR 0265）ので、深さに上限はなく、Java のスタックが尽きるまで続く
     */
    private val mutualRecursion = """
        (component
          (core module ${'$'}M1
            (type ${'$'}ft (func (param i32) (result i32)))
            (table (export "tbl") 1 1 funcref)
            (func (export "f") (param i32) (result i32)
              (if (result i32) (i32.eqz (local.get 0))
                (then (i32.const 100))
                (else (i32.add
                        (call_indirect (type ${'$'}ft) (i32.sub (local.get 0) (i32.const 1)) (i32.const 0))
                        (i32.const 1))))))
          (core instance ${'$'}m1 (instantiate ${'$'}M1))
          (func ${'$'}f (export "run") (param "n" u32) (result u32) (canon lift (core func ${'$'}m1 "f")))
          (component ${'$'}B
            (import "f" (func ${'$'}f (param "n" u32) (result u32)))
            (core func ${'$'}fl (canon lower (func ${'$'}f)))
            (core module ${'$'}MB
              (import "" "f" (func ${'$'}f (param i32) (result i32)))
              (func (export "g") (param i32) (result i32) (call ${'$'}f (local.get 0))))
            (core instance ${'$'}mb (instantiate ${'$'}MB (with "" (instance (export "f" (func ${'$'}fl))))))
            (func (export "g") (param "n" u32) (result u32) (canon lift (core func ${'$'}mb "g"))))
          (instance ${'$'}b (instantiate ${'$'}B (with "f" (func ${'$'}f))))
          (core func ${'$'}gl (canon lower (func ${'$'}b "g")))
          (core module ${'$'}M2
            (import "" "tbl" (table 1 1 funcref))
            (import "" "g" (func ${'$'}g (param i32) (result i32)))
            (elem (i32.const 0) func ${'$'}g))
          (core instance ${'$'}m2 (instantiate ${'$'}M2 (with "" (instance
            (export "tbl" (table ${'$'}m1 "tbl"))
            (export "g" (func ${'$'}gl)))))))
    """

    /** 相互再帰を [depth] で呼ぶプロセス。step を動かしたスレッドを [thread] に残す */
    private fun recursing(
        depth: Int,
        result: AtomicInteger,
        kind: ProcessKind,
        thread: AtomicReference<Thread>? = null,
    ): Pair<Program, TestDriver> {
        val program = Program.component(LinkTesting.load(mutualRecursion, ComponentHost.NONE, metered = true))
        val driver = TestDriver(kind) { ctx ->
            thread?.set(Thread.currentThread())
            result.set((ctx.component!!.call("run", args = listOf(CanonValue.I32(depth))) as CanonValue.I32).value)
            StepResult.Exit(0)
        }
        return program to driver
    }

    /**
     * 浅い再帰が最後まで進み、深い再帰がそのプロセスだけスタック切れで CRASHED になり、ほかのプロセスが動き続けることを確かめる。
     * 深い再帰の step を動かしたスレッドを返す。
     *
     * [spin] を渡したときは、その spin を「ほかのプロセス」に使い、新しく作らない（繰り返すテストで、終わらない spin が
     * 台にたまって犠牲のプロセスに CPU が回るのが遅くならないように。#993）
     */
    private fun Harness.deepRecursionCrashesOnlyItself(
        m: Machine,
        kind: ProcessKind,
        spin: AtomicInteger? = null,
    ): Thread {
        val progress = spin ?: AtomicInteger().also { p ->
            m.spawnTest(program(spinForever(p)), runDriver())
            tickUntilTrue("spin が動かない") { p.get() > 0 }
        }

        val shallow = AtomicInteger(-1)
        val (shallowProgram, shallowDriver) = recursing(50, shallow, kind)
        val ok = m.spawnRt(shallowProgram, shallowDriver)
        tickUntilTrue("浅い再帰が終わらない") { ok.process.state.isTerminal || m.state == MachineState.CRASHED }
        assertEquals(ProcessState.EXITED, ok.process.state, "${ok.lastError}")
        assertEquals(150, shallow.get())

        val deep = AtomicInteger(-1)
        val deepThread = AtomicReference<Thread>()
        val (deepProgram, deepDriver) = recursing(Int.MAX_VALUE, deep, kind, deepThread)
        val bad = m.spawnRt(deepProgram, deepDriver)
        assertEquals(kind, bad.process.kind)
        tickUntilTrue("深い再帰が終わらない") { bad.process.state.isTerminal || m.state == MachineState.CRASHED }
        assertNotEquals(MachineState.CRASHED, m.state, "マシンが壊れた: ${bad.lastError}")
        assertEquals(ProcessState.CRASHED, bad.process.state, "${bad.lastError}")
        // スタックが尽きた場所が Endive の中なら STACK_OVERFLOW のトラップ、リンカーの中なら StackOverflowError のまま上がる。どちらでもよい
        val e = bad.lastError
        assertTrue(e is StackOverflowError || (e is WasmTrapException && e.kind == WasmTrapKind.STACK_OVERFLOW), "$e")
        assertEquals("call stack exhausted", crashReason(e!!), "コンソールに出す理由は、どちらでも同じ")
        assertEquals(-1, deep.get())

        // ほかのプロセスは動き続ける
        val before = progress.get()
        tickUntilTrue("ほかのプロセスが止まった") { progress.get() > before + 2 }
        assertEquals(MachineState.RUNNING, m.state)
        return deepThread.get()!!
    }

    @Test
    fun `BLOCKINGのコンポーネントで深すぎる相互再帰は、そのプロセスだけがスタック切れでCRASHEDになり、ほかのプロセスは動き続ける`() = timeout(limit) {
        Harness().use { h ->
            // 燃料は十分に配る（スタックが先に尽きる）。共有の燃料と KernelLinkEnv の計測の下で動かす
            val m = h.machine(fuelPerTick = 50_000_000)
            h.deepRecursionCrashesOnlyItself(m, ProcessKind.BLOCKING)
            // BLOCKING は spawn のたびに専用のスレッドを作る。同じ種類の次のプロセスも、続けて最後まで動く
            val again = AtomicInteger(-1)
            val (againProgram, againDriver) = recursing(50, again, ProcessKind.BLOCKING)
            val next = m.spawnRt(againProgram, againDriver)
            h.tickUntilTrue("次のプロセスが終わらない") { next.process.state.isTerminal || m.state == MachineState.CRASHED }
            assertEquals(ProcessState.EXITED, next.process.state, "${next.lastError}")
            assertEquals(150, again.get())
            m.stop()
        }
    }

    @Test
    fun `WAIT_POINTのコンポーネントがプールのワーカーでスタックを使い切っても、そのワーカーは生きていて次のstepを最後まで動かす`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 50_000_000)
            val worker = h.deepRecursionCrashesOnlyItself(m, ProcessKind.WAIT_POINT)
            // 動かしたのは、待機点への変換をかけたプログラム（ADR 0757）
            assertTrue(recursing(50, AtomicInteger(), ProcessKind.WAIT_POINT).first.component!!.waitPoints)
            // 深い再帰の step は WorkerPool のワーカー（専用のスレッドでない）で動いた
            assertTrue(worker.name.startsWith("KiBi-worker-"), worker.name)
            assertTrue(worker.isAlive, "スタック切れでワーカーのスレッドが死んだ")
            // 同じワーカーに次の step が回るまで、同じ種類のプロセスを続けて動かす。どれも最後まで進む
            var reused = false
            for (i in 0 until 50) {
                val again = AtomicInteger(-1)
                val ran = AtomicReference<Thread>()
                val (againProgram, againDriver) = recursing(50, again, ProcessKind.WAIT_POINT, ran)
                val next = m.spawnRt(againProgram, againDriver)
                h.tickUntilTrue("次のプロセスが終わらない") { next.process.state.isTerminal || m.state == MachineState.CRASHED }
                assertEquals(ProcessState.EXITED, next.process.state, "${next.lastError}")
                assertEquals(150, again.get())
                if (ran.get() === worker) {
                    reused = true
                    break
                }
            }
            assertTrue(reused, "スタックを使い切ったワーカーに、次の step が回らなかった")
            assertTrue(worker.isAlive)
            m.stop()
        }
    }

    @Test
    fun `深すぎる相互再帰を専用スレッドとワーカーで繰り返しても、マシンのロック・CPU・許可は壊れない`() = timeout(Duration.ofSeconds(120)) {
        // 尽きる位置は実行ごと・JIT の前後で変わるので、何度も繰り返す（#828）。毎回の後に、テストのスレッドからマシンのロックが取れる
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 50_000_000)
            // spin は 1 つだけ作って使い回す（#993）。毎回作ると終わらない spin が 12 個たまり、i 回目の犠牲のプロセスが
            // i 個の spin のスライスの後ろで待つので、合計が回数の 2 乗で延びていた
            val spin = AtomicInteger()
            m.spawnTest(h.program(spinForever(spin)), runDriver())
            h.tickUntilTrue("spin が動かない") { spin.get() > 0 }
            for (i in 0 until 12) {
                h.deepRecursionCrashesOnlyItself(m, if (i % 2 == 0) ProcessKind.BLOCKING else ProcessKind.WAIT_POINT, spin)
                assertTrue(m.lock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS), "$i 回目の後にマシンのロックが取れない: ${m.lock}")
                m.lock.unlock()
            }
            m.stop()
            h.tickUntilTrue("止まらない") { m.state == MachineState.STOPPED }
            eventually { m.cpu.currentOwner == null && m.cpu.waiting == 0 }
            eventually(message = "許可が戻らない") { h.scheduler.permits.availablePermits() == h.scheduler.config.maxConcurrentMachines }
        }
    }
}
