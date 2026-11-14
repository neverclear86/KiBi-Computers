package pro.ikili.kibicomputers.core.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceCompletion
import pro.ikili.kibicomputers.api.device.DeviceException
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.emptyModule
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.fillFuel
import pro.ikili.kibicomputers.core.kernel.hostCallModule
import pro.ikili.kibicomputers.core.kernel.host
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.runDriver
import pro.ikili.kibicomputers.core.kernel.spawnTest
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** デバイスの呼び出しの振り分け（#65、ADR 0065）: 直接 / メインスレッド / 後で返す、上限、失敗の変換、燃料 */
class DeviceDispatcherTest {
    private val limit = Duration.ofSeconds(30)
    private val address = "00000000-0000-4000-8000-0000000000d1"

    /** メソッドの表を渡して作るデバイス */
    private class TestDevice(override val methods: DeviceMethods) : Device {
        override val kind: String = "test:device"
    }

    private fun Harness.tickUntil(condition: () -> Boolean) = eventually(10_000) {
        scheduler.tick()
        Thread.sleep(1)
        condition()
    }

    private fun Machine.start(method: String, vararg args: DeviceValue): DeviceDispatcher.Started =
        devices.dispatcher.start(0, address, method, args.toList())

    private fun DeviceDispatcher.Started.result(): CallResult = DeviceDispatcher.resultOf(future)

    /** fd [fd] が指すオブジェクトの ID */
    private fun ProcessContext.objectOf(fd: Int): Int = withKernelLock { kernel.processes[pid]!!.handles[fd]!!.objectId }

    private fun values(vararg v: DeviceValue) = CallResult.Values(v.toList())

    private fun error(e: CallError) = CallResult.Error(e)

    @Test
    fun `直接のメソッドは呼んだスレッドでその場で実行し、メインスレッドのメソッドはtickの中で実行する`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val threads = Collections.synchronizedMap(HashMap<String, Thread>())
            val methods = DeviceMethods.builder()
                .direct("d") { ctx, args ->
                    threads["d"] = Thread.currentThread()
                    assertFalse(ctx.isMainThread)
                    assertEquals("d", ctx.method)
                    assertEquals(address, ctx.address)
                    assertFalse(m.lock.isHeldByCurrentThread, "カーネルロックを持たずに呼ぶ")
                    arrayOf(DeviceValue.of(args.getLong(0) * 2))
                }
                .mainThread("w") { ctx, _ ->
                    threads["w"] = Thread.currentThread()
                    assertTrue(ctx.isMainThread)
                    arrayOf(DeviceValue.of("main"))
                }
                .build()
            m.devices.attach(address, TestDevice(methods))
            val direct = m.start("d", DeviceValue.of(21L))
            assertTrue(direct.future.isDone)
            assertEquals(values(DeviceValue.of(42L)), direct.result())
            assertSame(Thread.currentThread(), threads["d"])

            val w = m.start("w")
            assertFalse(w.future.isDone, "tick まで実行しない")
            assertEquals(1, m.pendingHostCalls)
            h.scheduler.tick()
            assertEquals(values(DeviceValue.of("main")), w.result())
            assertSame(Thread.currentThread(), threads["w"], "tick を回したスレッドで実行する")
        }
    }

    @Test
    fun `外れた・ないメソッド・上限・引数の誤り・作者の失敗・ほかの例外をそれぞれの失敗にし、マシンは動き続ける`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val methods = DeviceMethods.builder()
                .direct("echo") { _, args -> Array(args.size()) { args.get(it) } }
                .direct("needs-int") { _, args -> arrayOf(DeviceValue.of(args.getLong(0))) }
                .direct("author") { _, _ -> throw DeviceException("overheated") }
                .direct("bug") { _, _ -> throw IllegalStateException("バグ") }
                .direct("null") { _, _ -> arrayOfNulls<DeviceValue>(1) as Array<DeviceValue> }
                .direct("big-result") { _, _ -> Array(33) { DeviceValue.of(1L) } }
                .build()
            assertEquals(error(CallError.Detached), m.start("echo").result())
            m.devices.attach(address, TestDevice(methods))
            assertEquals(values(DeviceValue.of(1L), DeviceValue.none()), m.start("echo", DeviceValue.of(1L), DeviceValue.none()).result())
            assertEquals(error(CallError.NoSuchMethod), m.start("nothing").result())
            assertEquals(error(CallError.BadArguments("argument #1: integer expected, got text")), m.start("needs-int", DeviceValue.of("x")).result())
            assertEquals(error(CallError.Failed("overheated")), m.start("author").result())
            assertEquals(error(CallError.INTERNAL_ERROR), m.start("bug").result())
            assertEquals(error(CallError.INTERNAL_ERROR), m.start("null").result())
            assertEquals(error(CallError.Kernel(Errno.TOOBIG)), m.start("big-result").result())
            // 引数の数・バイト数・メソッド名の長さの上限
            assertEquals(error(CallError.Kernel(Errno.TOOBIG)), m.start("echo", *Array(33) { DeviceValue.none() }).result())
            val half = DeviceValue.of(ByteArray(DeviceLimits.MAX_PAYLOAD_BYTES / 2))
            assertTrue(m.start("echo", half, half).result() is CallResult.Values, "ちょうど上限は通る")
            assertEquals(error(CallError.Kernel(Errno.TOOBIG)), m.start("echo", half, half, DeviceValue.of("x")).result())
            assertEquals(error(CallError.Kernel(Errno.NAMETOOLONG)), m.start("a".repeat(65)).result())
            m.devices.detach(address)
            assertEquals(error(CallError.Detached), m.start("echo").result())
            assertEquals(MachineState.STOPPED, m.state, "例外でマシンを止めない")
        }
    }

    @Test
    fun `メインスレッドの呼び出しは、実行する前に外れたらdetachedになる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            m.devices.attach(address, TestDevice(DeviceMethods.builder().mainThread("w") { _, _ -> arrayOf(DeviceValue.of(1L)) }.build()))
            val w = m.start("w")
            m.devices.detach(address)
            h.scheduler.tick()
            assertEquals(error(CallError.Detached), w.result())
        }
    }

    @Test
    fun `燃料は基本の費用と引数のバイト数と直接のメソッドが使った分`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val methods = DeviceMethods.builder()
                .direct("heavy") { ctx, _ ->
                    ctx.consumeFuel(500)
                    ctx.consumeFuel(-5)
                    emptyArray()
                }
                .mainThread("main") { ctx, _ ->
                    ctx.consumeFuel(500) // メインスレッドでは効かない
                    emptyArray()
                }
                .build()
            m.devices.attach(address, TestDevice(methods))
            assertEquals(DeviceLimits.CALL_FUEL + 500, m.start("heavy").fuel)
            assertEquals(DeviceLimits.CALL_FUEL + 3 + 4 + 500, m.start("heavy", DeviceValue.of("abc"), DeviceValue.of(ByteArray(4))).fuel)
            assertEquals(DeviceLimits.CALL_FUEL + 2, m.start("main", DeviceValue.of("é")).fuel)
            h.scheduler.tick()
        }
    }

    @Test
    fun `後で返すメソッドは後のtickで終わり、2回目の終わりは無視し、ホスト呼び出しの件数を使わない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(maxHostCallsPerTick = 1)
            val completions = Collections.synchronizedList(ArrayList<DeviceCompletion>())
            val methods = DeviceMethods.builder()
                .mainThread("slow") { ctx, _ ->
                    completions += ctx.defer()
                    assertThrows<IllegalStateException> { ctx.defer() }
                    emptyArray()
                }
                .mainThread("fast") { _, _ -> arrayOf(DeviceValue.of(1L)) }
                .direct("bad-defer") { ctx, _ ->
                    ctx.defer()
                    emptyArray()
                }
                .build()
            m.devices.attach(address, TestDevice(methods))
            val slow = m.start("slow")
            h.scheduler.tick()
            assertEquals(1, completions.size)
            assertFalse(slow.future.isDone, "終えるまで実行中")
            assertEquals(1, m.devices.dispatcher.runningCount)
            // 実行中の分は 1 tick の件数を使わないので、次の呼び出しは次の tick で動く
            val fast = m.start("fast")
            h.scheduler.tick()
            assertEquals(values(DeviceValue.of(1L)), fast.result())
            // どのスレッドから終えてもよい。2回目は無視
            val t = Thread { completions[0].complete(DeviceValue.of("done")) }
            t.start()
            t.join()
            completions[0].fail("late")
            completions[0].complete(DeviceValue.of("again"))
            assertEquals(values(DeviceValue.of("done")), slow.result())
            assertEquals(0, m.devices.dispatcher.runningCount)
            assertFalse(completions[0].isCancelled)
            // 直接のメソッドからは defer できない（作者の誤りは internal error）
            assertEquals(error(CallError.INTERNAL_ERROR), m.start("bad-defer").result())
        }
    }

    @Test
    fun `マシンを止めると、後で返す呼び出しはisCancelledが立ちinterruptedで終わる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val completion = AtomicReference<DeviceCompletion>()
            m.devices.attach(address, TestDevice(DeviceMethods.builder().mainThread("slow") { ctx, _ -> completion.set(ctx.defer()); emptyArray() }.build()))
            val slow = m.start("slow")
            h.scheduler.tick()
            assertFalse(completion.get().isCancelled)
            m.stop()
            assertTrue(completion.get().isCancelled)
            assertEquals(error(CallError.INTERRUPTED), slow.result())
            completion.get().complete(DeviceValue.of(1L)) // 後から終えても何もしない
            assertEquals(error(CallError.INTERRUPTED), slow.result())
        }
    }

    @Test
    fun `後で返すと決めてから投げたら、その呼び出しを失敗で終える`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            m.devices.attach(address, TestDevice(DeviceMethods.builder().mainThread("x") { ctx, _ -> ctx.defer(); throw DeviceException("no") }.build()))
            val x = m.start("x")
            h.scheduler.tick()
            assertEquals(error(CallError.Failed("no")), x.result())
            assertEquals(0, m.devices.dispatcher.runningCount)
        }
    }

    @Test
    fun `ブロッキング型のプロセスはinvokeDeviceで結果まで待ち、燃料を払う`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            m.fillFuel()
            val methods = DeviceMethods.builder()
                .direct("add") { _, args -> arrayOf(DeviceValue.of(args.getLong(0) + args.getLong(1))) }
                .mainThread("name") { _, _ -> arrayOf(DeviceValue.of("reactor")) }
                .direct("fail") { _, _ -> throw DeviceException("broken") }
                .build()
            m.devices.attach(address, TestDevice(methods))
            val out = Collections.synchronizedList(ArrayList<Any>())
            val pid = m.spawnTest(
                h.program(hostCallModule()),
                runDriver(configure = { ctx, b ->
                    b.host(ctx) { c, _, _ ->
                        out.add(c.listDevices())
                        out.add(c.invokeDevice(address, "add", listOf(DeviceValue.of(2L), DeviceValue.of(3L))))
                        out.add(c.invokeDevice(address, "name", emptyList()))
                        out.add(assertThrows<DeviceCallException> { c.invokeDevice(address, "fail", emptyList()) }.error)
                        out.add(assertThrows<DeviceCallException> { c.invokeDevice("00000000-0000-4000-8000-0000000000ff", "add", emptyList()) }.error)
                        LongArray(0)
                    }
                }),
            )
            h.tickUntil { m.stateOf(pid) == ProcessState.EXITED }
            assertEquals(
                listOf(
                    listOf(DeviceInfo(address, "test:device")),
                    listOf(DeviceValue.of(5L)),
                    listOf(DeviceValue.of("reactor")),
                    CallError.Failed("broken"),
                    CallError.Detached,
                ),
                out.toList(),
            )
        }
    }

    @Test
    fun `待機点型はメインスレッドの呼び出しをstartDeviceCallで積み、スレッドを持たずにfdを待ち、出した順に実行する`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine().also { it.fillFuel() }
            val order = Collections.synchronizedList(ArrayList<Long>())
            m.devices.attach(
                address,
                TestDevice(DeviceMethods.builder().mainThread("put") { _, args -> order += args.getLong(0); arrayOf(DeviceValue.of(args.getLong(0))) }.build()),
            )
            val fds = AtomicReference<List<Int>>()
            val results = AtomicReference<List<CallResult?>>()
            val again = AtomicReference<Errno>()
            val pid = m.spawnTest(
                h.program(emptyModule()),
                TestDriver(
                    ProcessKind.WAIT_POINT,
                    onResume = { ctx, _ ->
                        val got = fds.get().map { ctx.takeDeviceCall(it) }
                        if (got.any { it == null }) {
                            StepResult.Wait(fds.get().map { WaitKey(ctx.objectOf(it), WaitCondition.READABLE) })
                        } else {
                            results.set(got)
                            StepResult.Exit(0)
                        }
                    },
                ) { ctx ->
                    val list = (1..DeviceLimits.MAX_PENDING_CALLS).map { ctx.startDeviceCall(address, "put", listOf(DeviceValue.of(it.toLong()))) }
                    again.set(assertThrows<FsException> { ctx.startDeviceCall(address, "put", listOf(DeviceValue.of(99L))) }.errno)
                    assertEquals(null, ctx.takeDeviceCall(list[0]), "まだ終わっていない")
                    fds.set(list)
                    StepResult.Wait(list.map { WaitKey(ctx.objectOf(it), WaitCondition.READABLE) })
                },
            )
            // tick を回さない間は、待機点でスレッドを持たずに待つ
            eventually {
                m.lock.withLock {
                    val rt = m.runtime(pid)
                    rt != null && rt.process.state == ProcessState.WAITING_EVENT && rt.thread == null
                }
            }
            assertTrue(m.isAtWaitPoint)
            assertEquals(Errno.AGAIN, again.get(), "17 個目は again")
            h.tickUntil { m.stateOf(pid) == ProcessState.EXITED }
            assertEquals((1L..DeviceLimits.MAX_PENDING_CALLS).toList(), order.toList(), "出した順")
            assertEquals((1L..DeviceLimits.MAX_PENDING_CALLS).map { values(DeviceValue.of(it)) }, results.get())
        }
    }

    @Test
    fun `openDeviceはつないでいるデバイスのハンドルを開き、つないでいなければdetached`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000).also { it.fillFuel() }
            m.devices.attach(address, TestDevice(DeviceMethods.empty()))
            val handle = AtomicReference<DeviceHandle>()
            val missing = AtomicReference<CallError>()
            val pid = m.spawnTest(
                h.program(hostCallModule()),
                runDriver(configure = { ctx, b ->
                    b.host(ctx) { c, _, _ ->
                        val fd = c.openDevice(address)
                        handle.set(c.withKernelLock { c.kernel.objects[c.objectOf(fd)] as DeviceHandle })
                        missing.set(assertThrows<DeviceCallException> { c.openDevice("00000000-0000-4000-8000-0000000000ff") }.error)
                        LongArray(0)
                    }
                }),
            )
            h.tickUntil { m.stateOf(pid) == ProcessState.EXITED }
            assertEquals(DeviceHandle.Snapshot(address, "test:device"), handle.get().snapshot())
            assertEquals(CallError.Detached, missing.get())
        }
    }

    // ---- 取り消し（#532） ----

    /** 後で返すメソッド `slow` と、すぐ返すメインスレッドのメソッド `fast` を持つデバイスをつなぎ、受け取った [DeviceCompletion] の入れ物を返す。[calls] は呼ばれた回数 */
    private fun Machine.attachSlow(calls: AtomicInteger = AtomicInteger()): AtomicReference<DeviceCompletion> {
        val completion = AtomicReference<DeviceCompletion>()
        val methods = DeviceMethods.builder()
            .mainThread("slow") { ctx, _ ->
                calls.incrementAndGet()
                completion.set(ctx.defer())
                emptyArray()
            }
            .mainThread("fast") { _, _ -> emptyArray() }
            .build()
        devices.attach(address, TestDevice(methods))
        return completion
    }

    /** プロセス [pid] の fd [fd] を閉じる（カーネルロックの中で） */
    private fun ProcessContext.closeFd(machine: Machine, fd: Int) = withKernelLock {
        FdTable(kernel.processes[pid]!!, kernel.objects, machine.wakeSink).close(fd)
    }

    @Test
    fun `invokeDeviceで待っていたプロセスを止めると、後で返す呼び出しはisCancelledが立ち実行中から外れる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000).also { it.fillFuel() }
            val completion = m.attachSlow()
            val pid = m.spawnTest(
                h.program(hostCallModule()),
                runDriver(configure = { ctx, b ->
                    b.host(ctx) { c, _, _ ->
                        c.invokeDevice(address, "slow", emptyList())
                        LongArray(0)
                    }
                }),
            )
            h.tickUntil { completion.get() != null }
            assertFalse(completion.get().isCancelled)
            assertEquals(1, m.devices.dispatcher.runningCount)
            m.lock.withLock { m.killLocked(pid, KillSignal.TERMINATE) }
            h.tickUntil { m.stateOf(pid) == ProcessState.KILLED }
            assertTrue(completion.get().isCancelled)
            assertEquals(0, m.devices.dispatcher.runningCount)
            completion.get().complete(DeviceValue.of(1L)) // 後から終えても何もしない
        }
    }

    @Test
    fun `pending-callのfdを閉じると、後で返す呼び出しはisCancelledが立ち実行中から外れる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine().also { it.fillFuel() }
            val completion = m.attachSlow()
            val closed = AtomicBoolean()
            val cancelledBeforeClose = AtomicReference<Boolean>()
            val slowFd = AtomicReference<Int>()
            m.spawnTest(
                h.program(emptyModule()),
                TestDriver(
                    ProcessKind.WAIT_POINT,
                    onResume = { ctx, _ ->
                        // slow が後で返すと決めた後に fast が終わって起きる
                        cancelledBeforeClose.set(completion.get().isCancelled)
                        ctx.closeFd(m, slowFd.get())
                        closed.set(true)
                        StepResult.Exit(0)
                    },
                ) { ctx ->
                    slowFd.set(ctx.startDeviceCall(address, "slow", emptyList()))
                    val fast = ctx.startDeviceCall(address, "fast", emptyList())
                    StepResult.Wait(listOf(WaitKey(ctx.objectOf(fast), WaitCondition.READABLE)))
                },
            )
            h.tickUntil { closed.get() }
            assertEquals(false, cancelledBeforeClose.get())
            assertTrue(completion.get().isCancelled)
            assertEquals(0, m.devices.dispatcher.runningCount)
        }
    }

    @Test
    fun `pending-callを閉じずにプロセスが終わると、後で返す呼び出しはisCancelledが立ち実行中から外れる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine().also { it.fillFuel() }
            val completion = m.attachSlow()
            val cancelledAtExit = AtomicReference<Boolean>()
            val pid = m.spawnTest(
                h.program(emptyModule()),
                TestDriver(
                    ProcessKind.WAIT_POINT,
                    onResume = { _, _ ->
                        cancelledAtExit.set(completion.get().isCancelled)
                        StepResult.Exit(0)
                    },
                ) { ctx ->
                    ctx.startDeviceCall(address, "slow", emptyList())
                    val fast = ctx.startDeviceCall(address, "fast", emptyList())
                    StepResult.Wait(listOf(WaitKey(ctx.objectOf(fast), WaitCondition.READABLE)))
                },
            )
            h.tickUntil { m.stateOf(pid) == ProcessState.EXITED }
            assertEquals(false, cancelledAtExit.get())
            assertTrue(completion.get().isCancelled)
            assertEquals(0, m.devices.dispatcher.runningCount)
        }
    }

    @Test
    fun `まだ実行していないpending-callのfdを閉じると、列から外してメソッドを呼ばない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine().also { it.fillFuel() }
            val calls = AtomicInteger()
            m.attachSlow(calls)
            val pid = m.spawnTest(
                h.program(emptyModule()),
                TestDriver(ProcessKind.WAIT_POINT) { ctx ->
                    val fd = ctx.startDeviceCall(address, "slow", emptyList())
                    assertEquals(1, m.pendingHostCalls)
                    ctx.closeFd(m, fd)
                    assertEquals(0, m.pendingHostCalls, "列から外した")
                    StepResult.Exit(0)
                },
            )
            eventually { m.stateOf(pid) == ProcessState.EXITED }
            repeat(3) { h.scheduler.tick() }
            assertEquals(0, calls.get())
            assertEquals(0, m.devices.dispatcher.runningCount)
        }
    }

    @Test
    fun `取り消しがdeferより先に来ても、isCancelledが立ちinterruptedで終わる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val completion = AtomicReference<DeviceCompletion>()
            val started = AtomicReference<DeviceDispatcher.Started>()
            m.devices.attach(
                address,
                TestDevice(
                    DeviceMethods.builder().mainThread("slow") { ctx, _ ->
                        // 実行し始めてから defer するまでの間に取り消された
                        m.lock.withLock { started.get().cancelLocked() }
                        completion.set(ctx.defer())
                        emptyArray()
                    }.build(),
                ),
            )
            started.set(m.start("slow"))
            h.scheduler.tick()
            assertTrue(completion.get().isCancelled)
            assertEquals(error(CallError.INTERRUPTED), started.get().result())
            assertEquals(0, m.devices.dispatcher.runningCount)
        }
    }

    @Test
    fun `ハンドラーが投げたErrorはinternal errorになり、JVMが続けられないエラーだけそのまま伝わる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val methods = DeviceMethods.builder()
                .direct("so") { _, _ -> throw StackOverflowError() }
                .direct("link") { _, _ -> throw NoClassDefFoundError("missing/Class") }
                .direct("assert") { _, _ -> throw AssertionError("x") }
                .direct("oom") { _, _ -> throw OutOfMemoryError("test") }
                .mainThread("main-link") { _, _ -> throw LinkageError("x") }
                .mainThread("deferred-error") { ctx, _ ->
                    ctx.defer()
                    throw ExceptionInInitializerError("x")
                }
                .build()
            m.devices.attach(address, TestDevice(methods))
            assertEquals(error(CallError.INTERNAL_ERROR), m.start("so").result())
            assertEquals(error(CallError.INTERNAL_ERROR), m.start("link").result())
            assertEquals(error(CallError.INTERNAL_ERROR), m.start("assert").result())
            assertThrows<OutOfMemoryError> { m.start("oom") }
            val mainLink = m.start("main-link")
            val deferred = m.start("deferred-error")
            h.scheduler.tick()
            assertEquals(error(CallError.INTERNAL_ERROR), mainLink.result())
            assertEquals(error(CallError.INTERNAL_ERROR), deferred.result())
            assertEquals(0, m.devices.dispatcher.runningCount)
            assertEquals(MachineState.STOPPED, m.state, "Error でマシンを止めない")
        }
    }

    @Test
    fun `メインスレッドのメソッドが投げたJVMが続けられないエラーは、呼び出しをinternal errorで終えてからtickへ伝わる`() = timeout(limit) {
        // 投げた後の tick の続きは保証しない（JVM が続けられない）ので、1つずつ別のマシンで確かめる
        for (deferFirst in listOf(false, true)) {
            Harness().use { h ->
                val m = h.machine()
                val methods = DeviceMethods.builder()
                    .mainThread("oom") { ctx, _ ->
                        if (deferFirst) ctx.defer()
                        throw OutOfMemoryError("test")
                    }
                    .build()
                m.devices.attach(address, TestDevice(methods))
                val oom = m.start("oom")
                assertThrows<OutOfMemoryError> { h.scheduler.tick() }
                assertEquals(error(CallError.INTERNAL_ERROR), oom.result(), "defer=$deferFirst")
                assertEquals(0, m.devices.dispatcher.runningCount, "後で返すと決めた呼び出しを実行中に残さない")
            }
        }
    }

    @Test
    fun `メインスレッドのメソッドが投げたStackOverflowErrorは伝わらず、internal errorで終わる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val methods = DeviceMethods.builder()
                .mainThread("so") { _, _ -> throw StackOverflowError() }
                .mainThread("deferred-so") { ctx, _ ->
                    ctx.defer()
                    throw StackOverflowError()
                }
                .build()
            m.devices.attach(address, TestDevice(methods))
            val so = m.start("so")
            val deferred = m.start("deferred-so")
            h.scheduler.tick()
            assertEquals(error(CallError.INTERNAL_ERROR), so.result())
            assertEquals(error(CallError.INTERNAL_ERROR), deferred.result())
            assertEquals(0, m.devices.dispatcher.runningCount)
            assertEquals(MachineState.STOPPED, m.state, "StackOverflowError でマシンを止めない")
        }
    }

    @Test
    fun `ブロッキング型のプロセスには、直接のメソッドが投げたErrorがinternal errorとして届く`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000).also { it.fillFuel() }
            m.devices.attach(address, TestDevice(DeviceMethods.builder().direct("link") { _, _ -> throw LinkageError("x") }.build()))
            val got = AtomicReference<CallError>()
            val pid = m.spawnTest(
                h.program(hostCallModule()),
                runDriver(configure = { ctx, b ->
                    b.host(ctx) { c, _, _ ->
                        got.set(assertThrows<DeviceCallException> { c.invokeDevice(address, "link", emptyList()) }.error)
                        LongArray(0)
                    }
                }),
            )
            h.tickUntil { m.stateOf(pid) == ProcessState.EXITED }
            assertEquals(CallError.INTERNAL_ERROR, got.get())
        }
    }

    @Test
    fun `失敗の文は1024バイトで切り詰め、文字の途中では切らない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val methods = DeviceMethods.builder()
                .direct("author") { _, _ -> throw DeviceException("x".repeat(5000)) }
                .direct("wide") { _, _ -> throw DeviceException("あ".repeat(2000)) }
                .direct("short") { _, _ -> throw DeviceException("overheated") }
                .mainThread("later") { ctx, _ ->
                    ctx.defer().fail("😀".repeat(1000))
                    emptyArray()
                }
                .build()
            m.devices.attach(address, TestDevice(methods))
            assertEquals(error(CallError.Failed("x".repeat(1024))), m.start("author").result())
            // あ は 3 バイト。1024 バイトに入るのは 341 文字（1023 バイト）
            assertEquals(error(CallError.Failed("あ".repeat(341))), m.start("wide").result())
            assertEquals(error(CallError.Failed("overheated")), m.start("short").result())
            val later = m.start("later")
            h.scheduler.tick()
            // 😀 は 4 バイト（サロゲートの対）。256 個で 1024 バイト
            assertEquals(error(CallError.Failed("😀".repeat(256))), later.result())
        }
    }

    @Test
    fun `truncateMessageは上限に収まるものをそのまま返す`() {
        assertEquals("", DeviceLimits.truncateMessage(""))
        val exact = "y".repeat(DeviceLimits.MAX_ERROR_MESSAGE_BYTES)
        assertEquals(exact, DeviceLimits.truncateMessage(exact))
        assertEquals(exact, DeviceLimits.truncateMessage(exact + "z"))
        // 文字の途中（サロゲートの対の間）で切らない
        val cut = "y".repeat(DeviceLimits.MAX_ERROR_MESSAGE_BYTES - 2) + "😀"
        assertEquals("y".repeat(DeviceLimits.MAX_ERROR_MESSAGE_BYTES - 2), DeviceLimits.truncateMessage(cut))
    }
}
