package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.kernel.event.EventFilter
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.process.ChildSelector
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * ホスト関数の中から呼んだゲスト（`callGuest`。`cabi_realloc`）の中で、止まって待つ口を使うとトラップになる（#430。ADR 0073）。
 * ABI の層が入口で弾き忘れても、IllegalStateException ではなくトラップで CRASHED になることを確かめる
 */
class GuestCallGuardTest {
    private val limit = Duration.ofSeconds(30)

    private val address = "00000000-0000-4000-8000-0000000000e1"

    /** メソッドの表を渡して作るデバイス */
    private class TestDevice(override val methods: DeviceMethods) : Device {
        override val kind: String = "test:device"
    }

    /** 直接のメソッド `d` とメインスレッドのメソッド `w` を持つデバイスをつなぐ */
    private fun Machine.attachDevice() = devices.attach(
        address,
        TestDevice(
            DeviceMethods.builder()
                .direct("d") { _, _ -> arrayOf(DeviceValue.of(42L)) }
                .mainThread("w") { _, _ -> arrayOf(DeviceValue.of("main")) }
                .build(),
        ),
    )

    /** [assertTraps] の [prepare] に渡すもの: 止まったままの ByteStream の ID と、プロセスのプログラム */
    private class Setup(val never: Int, val program: Program)

    /**
     * [kind] のプロセスで、`callGuest` の外で [prepare] を呼んでから、`callGuest` の中から [body] を呼ぶ。
     * トラップで CRASHED になり、[body] の後に進まないこと。トラップの文言に出る口の名前は [name]
     */
    private fun <T> assertTraps(
        kind: ProcessKind,
        name: String,
        prepare: (ProcessContext, Setup) -> T,
        body: (ProcessContext, T) -> Unit,
    ) = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            m.attachDevice()
            val never = m.lock.withLockValue { m.kernel.objects.add(ByteStream(1)) }
            val program = h.program(emptyModule())
            val passed = AtomicBoolean(false)
            val pid = m.spawnTest(
                program,
                TestDriver(kind) { ctx ->
                    val prepared = prepare(ctx, Setup(never, program))
                    ctx.callGuest { body(ctx, prepared) }
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

    /** [assertTraps] の、前もって用意するものが要らない形（[body] には止まったままの ByteStream の ID を渡す） */
    private fun assertTraps(kind: ProcessKind, name: String, body: (ProcessContext, Int) -> Unit) =
        assertTraps(kind, name, { _, setup -> setup.never }, body)

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

    @Test
    fun `callGuestの中からexecを呼ぶとトラップ`() =
        assertTraps(ProcessKind.BLOCKING, "exec") { ctx, _ -> ctx.exec("/nope", KernelDrivers.DEFAULT) }

    @Test
    fun `callGuestの中からexecWithHandleを呼ぶとトラップ`() =
        assertTraps(ProcessKind.BLOCKING, "exec") { ctx, _ -> ctx.execWithHandle("/nope", KernelDrivers.DEFAULT) }

    @Test
    fun `callGuestの中からawaitHostを呼ぶとトラップ`() =
        assertTraps(ProcessKind.BLOCKING, "awaitHost") { ctx, _ -> ctx.awaitHost(CompletableFuture<Unit>()) }

    @Test
    fun `ブロッキング型でcallGuestの中からメインスレッドのメソッドをinvokeDeviceで呼ぶとトラップ`() =
        assertTraps(ProcessKind.BLOCKING, "invokeDevice") { ctx, _ -> ctx.invokeDevice(address, "w", emptyList()) }

    @Test
    fun `待機点型でcallGuestの中からメインスレッドのメソッドをinvokeDeviceで呼んでも、IllegalStateExceptionではなくトラップ`() =
        assertTraps(ProcessKind.WAIT_POINT, "invokeDevice") { ctx, _ -> ctx.invokeDevice(address, "w", emptyList()) }

    @Test
    fun `callGuestの中から、まだ走っている子をwaitChildで待つとトラップ`() = assertTraps(
        ProcessKind.BLOCKING,
        "block",
        prepare = { ctx, setup ->
            // 止まったままの ByteStream を待ち続ける子（マシンを止めるまで終わらない）
            ctx.spawn(
                setup.program,
                TestDriver(ProcessKind.BLOCKING) { c ->
                    c.block(listOf(WaitKey(setup.never, WaitCondition.READABLE)))
                    StepResult.Exit(0)
                },
            )
        },
    ) { ctx, child -> ctx.waitChild(ChildSelector.Pid(child)) }

    @Test
    fun `callGuestの中から、空のキューをawaitEventsで待つとトラップ`() = assertTraps(
        ProcessKind.BLOCKING,
        "block",
        prepare = { ctx, _ -> ctx.openEventQueue(EventFilter(setOf("test"))) },
    ) { ctx, q -> ctx.awaitEvents(q) }

    @Test
    fun `callGuestの中からsleepを呼ぶとトラップ`() =
        assertTraps(ProcessKind.BLOCKING, "block") { ctx, _ -> ctx.sleep(60_000_000_000L) }

    @Test
    fun `callGuestの中でも、直接のメソッドはinvokeDeviceで呼べる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            m.attachDevice()
            val got = AtomicReference<List<DeviceValue>>()
            val pid = m.spawnTest(
                h.program(emptyModule()),
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    got.set(ctx.callGuest { ctx.invokeDevice(address, "d", emptyList()) })
                    StepResult.Exit(0)
                },
            )
            h.tickUntilTrue { m.stateOf(pid).isTerminal }
            assertEquals(ProcessState.EXITED, m.stateOf(pid), "${m.rt(pid).lastError}")
            assertEquals(listOf(DeviceValue.of(42L)), got.get())
        }
    }
}
