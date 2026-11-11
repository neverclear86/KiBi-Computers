package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * ホスト関数の中から呼んだゲスト（`callGuest`。`cabi_realloc`）の中で、止まって待つ口を使うとトラップになる（#430。ADR 0073）。
 * ABI の層が入口で弾き忘れても、IllegalStateException ではなくトラップで CRASHED になることを確かめる
 */
class GuestCallGuardTest {
    private val limit = Duration.ofSeconds(30)

    /** [kind] のプロセスで、`callGuest` の中から [body] を呼ぶ。トラップで CRASHED になり、[body] の後に進まないこと */
    private fun assertTraps(kind: ProcessKind, name: String, body: (ProcessContext, Int) -> Unit) = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val never = m.lock.withLockValue { m.kernel.objects.add(ByteStream(1)) }
            val passed = AtomicBoolean(false)
            val pid = m.spawnTest(
                h.program(emptyModule()),
                TestDriver(kind) { ctx ->
                    ctx.callGuest { body(ctx, never) }
                    passed.set(true)
                    StepResult.Exit(0)
                },
            )
            h.tickUntilTrue { m.stateOf(pid).isTerminal }
            assertEquals(ProcessState.CRASHED, m.stateOf(pid))
            assertFalse(passed.get())
            val e = m.rt(pid).lastError
            assertTrue(e is WasmTrapException && "止まりうるホストの処理 $name を cabi_realloc の中で呼んだ" in e.message!!, "$e")
        }
    }

    @Test
    fun `ブロッキング型でcallGuestの中からblockを呼ぶとトラップ`() =
        assertTraps(ProcessKind.BLOCKING, "block") { ctx, never -> ctx.block(listOf(WaitKey(never, WaitCondition.READABLE))) }

    @Test
    fun `ブロッキング型でcallGuestの中からcallOnMainThreadを呼ぶとトラップ`() =
        assertTraps(ProcessKind.BLOCKING, "callOnMainThread") { ctx, _ -> ctx.callOnMainThread { Unit } }

    @Test
    fun `待機点型でcallGuestの中からcallOnMainThreadを呼んでも、IllegalStateExceptionではなくトラップ`() =
        assertTraps(ProcessKind.WAIT_POINT, "callOnMainThread") { ctx, _ -> ctx.callOnMainThread { Unit } }

    @Test
    fun `待機点型でcallGuestの中からblockを呼んでも、IllegalStateExceptionではなくトラップ`() =
        assertTraps(ProcessKind.WAIT_POINT, "block") { ctx, never -> ctx.block(listOf(WaitKey(never, WaitCondition.READABLE))) }

    @Test
    fun `callGuestを抜けた後はメインスレッドの処理を待てる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val pid = m.spawnTest(
                h.program(emptyModule()),
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    assertEquals(7, ctx.callGuest { 7 })
                    assertFalse(ctx.inGuestCall)
                    ctx.callOnMainThread { Unit }
                    StepResult.Exit(0)
                },
            )
            h.tickUntilTrue { m.stateOf(pid).isTerminal }
            assertEquals(ProcessState.EXITED, m.stateOf(pid), "${m.rt(pid).lastError}")
        }
    }
}
