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

/** コンポーネントのプロセス（#263）: カーネルの中でリンクし、core インスタンスをまたいで動かす */
class ComponentProcessTest {
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
}
