package pro.ikili.kibicomputers.core.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceContext
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineConfig
import pro.ikili.kibicomputers.core.kernel.emptyModule
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.fillFuel
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.spawnTest
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 接続数の上限と、開いたデバイス（fd）からの info・methods・invoke・start（#66、ADR 0066） */
class DeviceLimitTest {
    private val limit = Duration.ofSeconds(30)

    private fun address(i: Int) = "00000000-0000-4000-8000-%012x".format(0x600 + i)

    private val a = address(1)

    /** メソッドの表を渡して作るデバイス */
    private class TestDevice(
        override val methods: DeviceMethods = DeviceMethods.empty(),
        override val kind: String = "test:device",
    ) : Device

    /** 直接のメソッド `d` とメインスレッドのメソッド `w` の表 */
    private val dw: DeviceMethods = DeviceMethods.builder()
        .direct("d") { _, _ -> arrayOf(DeviceValue.of(42L)) }
        .mainThread("w") { _, _ -> arrayOf(DeviceValue.of("main")) }
        .build()

    private fun Harness.tickUntil(condition: () -> Boolean) = eventually(10_000) {
        scheduler.tick()
        Thread.sleep(1)
        condition()
    }

    /** ブロッキング型のプロセスで [body] を実行し、EXITED まで tick を回す。[body] が投げたものは、テストのスレッドで投げ直す */
    private fun Harness.runProcess(m: Machine, body: (ProcessContext) -> Unit) {
        val failure = AtomicReference<Throwable>()
        val pid = m.spawnTest(
            program(emptyModule()),
            TestDriver(ProcessKind.BLOCKING) { ctx ->
                try {
                    body(ctx)
                } catch (t: Throwable) {
                    failure.set(t)
                }
                StepResult.Exit(0)
            },
        )
        tickUntil { m.stateOf(pid) == ProcessState.EXITED }
        failure.get()?.let { throw it }
    }

    // ---- 上限 ----

    @Test
    fun `設定の既定は16で、1未満は受け付けない`() {
        assertEquals(16, MachineConfig.DEFAULT.maxDevices)
        assertEquals(3, MachineConfig.builder().maxDevices(3).build().maxDevices)
        assertThrows<IllegalArgumentException> { MachineConfig.builder().maxDevices(0) }
    }

    @Test
    fun `上限ちょうどまでつながり、1つ超えると表も世代も最初の画面も変えずにfalse`() = timeout(limit) {
        Harness().use { h ->
            val bus = h.machine(maxDevices = 2).devices
            assertEquals(2, bus.limit)
            assertEquals(0, bus.size)
            assertTrue(bus.attach(a, TestDevice()))
            assertTrue(bus.attach(address(2), TestDevice()))
            assertEquals(2, bus.size)
            val generation = bus.generation
            val listing = bus.list()
            var attachedCalled = false
            val refused = object : Device {
                override val kind = "test:refused"
                override val methods = DeviceMethods.empty()

                override fun onAttached(context: DeviceContext) {
                    attachedCalled = true
                }
            }
            assertFalse(bus.attach(address(3), refused))
            assertFalse(bus.attach(SCREEN_ADDRESS, ScreenDevice(TextBuffer(20, 3), 2)))
            assertFalse(attachedCalled, "断ったデバイスの onAttached は呼ばない")
            assertEquals(2, bus.size)
            assertEquals(generation, bus.generation)
            assertSame(listing, bus.list())
            assertNull(bus.primaryScreen)
            assertNull(bus.deviceAt(address(3)))
            // 満杯でも、address の重複はプログラムの誤り（上限とは分ける）
            assertThrows<IllegalArgumentException> { bus.attach(a, TestDevice()) }
        }
    }

    @Test
    fun `外して空いたらまたつなげるが、自分ではつなぎ直さない`() = timeout(limit) {
        Harness().use { h ->
            val bus = h.machine(maxDevices = 1).devices
            assertTrue(bus.attach(a, TestDevice()))
            assertFalse(bus.attach(address(2), TestDevice()))
            assertTrue(bus.detach(a))
            assertEquals(0, bus.size)
            assertNull(bus.deviceAt(address(2)), "断ったものは空いても勝手につながない")
            assertTrue(bus.attach(address(2), TestDevice()))
            assertEquals(listOf(address(2)), bus.list().map { it.address })
        }
    }

    @Test
    fun `onAttachedが投げた後は数が戻り、空いた枠につなげる`() = timeout(limit) {
        Harness().use { h ->
            val bus = h.machine(maxDevices = 1).devices
            val bad = object : Device {
                override val kind = "test:bad"
                override val methods = DeviceMethods.empty()

                override fun onAttached(context: DeviceContext): Unit = throw IllegalStateException("つなげない")
            }
            assertThrows<IllegalStateException> { bus.attach(a, bad) }
            assertEquals(0, bus.size)
            assertTrue(bus.list().isEmpty())
            assertTrue(bus.attach(address(2), TestDevice()))
        }
    }

    @Test
    fun `並んだattachでも上限を越えない`() = timeout(limit) {
        Harness().use { h ->
            val max = 5
            val bus = h.machine(maxDevices = max).devices
            val start = CountDownLatch(1)
            val accepted = AtomicInteger()
            val threads = List(16) { i ->
                Thread {
                    start.await()
                    if (bus.attach(address(10 + i), TestDevice())) accepted.incrementAndGet()
                }
            }
            threads.forEach { it.start() }
            start.countDown()
            threads.forEach { it.join() }
            assertEquals(max, accepted.get())
            assertEquals(max, bus.size)
            assertEquals(max, bus.list().size)
            assertEquals(max.toLong(), bus.generation)
        }
    }

    @Test
    fun `一覧は表を変えるまで同じインスタンスで、変えられない`() = timeout(limit) {
        Harness().use { h ->
            val bus = h.machine().devices
            assertTrue(bus.attach(a, TestDevice()))
            val first = bus.list()
            assertSame(first, bus.list())
            assertThrows<UnsupportedOperationException> { (first as MutableList<DeviceInfo>).clear() }
            assertTrue(bus.attach(address(2), TestDevice()))
            val second = bus.list()
            assertEquals(listOf(a), first.map { it.address }, "前の一覧は変わらない")
            assertEquals(listOf(a, address(2)), second.map { it.address })
        }
    }

    // ---- 開いたデバイス（fd）の口 ----

    @Test
    fun `fdからinfo・methods・invoke・startを呼べ、methodsはmainThreadの印を持つ`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000).also { it.fillFuel() }
            assertTrue(m.devices.attach(a, TestDevice(dw)))
            val got = AtomicReference<List<Any?>>()
            h.runProcess(m) { c ->
                val fd = c.openDevice(a)
                val call = c.startDeviceCall(fd, "d", emptyList())
                got.set(
                    listOf(
                        c.deviceInfo(fd),
                        c.deviceMethods(fd),
                        c.invokeDevice(fd, "d", emptyList()),
                        c.invokeDevice(fd, "w", emptyList()),
                        c.takeDeviceCall(call),
                    ),
                )
            }
            assertEquals(
                listOf(
                    DeviceInfo(a, "test:device"),
                    listOf(MethodInfo("d", mainThread = false), MethodInfo("w", mainThread = true)),
                    listOf(DeviceValue.of(42L)),
                    listOf(DeviceValue.of("main")),
                    CallResult.Values(listOf(DeviceValue.of(42L))),
                ),
                got.get(),
            )
        }
    }

    @Test
    fun `deviceでないfdはBADF`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000).also { it.fillFuel() }
            assertTrue(m.devices.attach(a, TestDevice(dw)))
            val errors = AtomicReference<List<Errno>>()
            h.runProcess(m) { c ->
                // pending-call の fd も、開いていない fd も device ではない
                val call = c.startDeviceCall(a, "d", emptyList())
                val list = ArrayList<Errno>()
                for (fd in listOf(call, 999)) {
                    list += assertThrows<FsException> { c.deviceInfo(fd) }.errno
                    list += assertThrows<FsException> { c.deviceMethods(fd) }.errno
                    list += assertThrows<FsException> { c.invokeDevice(fd, "d", emptyList()) }.errno
                    list += assertThrows<FsException> { c.startDeviceCall(fd, "d", emptyList()) }.errno
                }
                errors.set(list)
            }
            assertEquals(List(8) { Errno.BADF }, errors.get())
        }
    }

    @Test
    fun `外れた後はinfoを返し、methodsは空、invokeとstartはdetached、同じ種類をつなぎ直すとまた呼べる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000).also { it.fillFuel() }
            assertTrue(m.devices.attach(a, TestDevice(dw)))
            val got = AtomicReference<List<Any?>>()
            h.runProcess(m) { c ->
                val fd = c.openDevice(a)
                m.devices.detach(a)
                val detached = listOf(
                    c.deviceInfo(fd),
                    c.deviceMethods(fd),
                    assertThrows<DeviceCallException> { c.invokeDevice(fd, "d", emptyList()) }.error,
                    c.takeDeviceCall(c.startDeviceCall(fd, "d", emptyList())),
                )
                // address で引き直すので、同じ種類のデバイスをつなぎ直せば同じハンドルで呼べる
                assertTrue(m.devices.attach(a, TestDevice(dw)))
                got.set(detached + listOf(c.invokeDevice(fd, "d", emptyList())))
            }
            assertEquals(
                listOf(
                    DeviceInfo(a, "test:device"),
                    emptyList<MethodInfo>(),
                    CallError.Detached,
                    CallResult.Error(CallError.Detached),
                    listOf(DeviceValue.of(42L)),
                ),
                got.get(),
            )
        }
    }

    @Test
    fun `引いた後に外れたデバイスは、直接のメソッドでもdetachedで、ハンドラーを呼ばない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val called = AtomicInteger()
            val methods = DeviceMethods.builder().direct("d") { _, _ -> called.incrementAndGet(); arrayOf(DeviceValue.of(1L)) }.build()
            assertTrue(m.devices.attach(a, TestDevice(methods)))
            val device = m.devices.dispatcher.resolve(a, "test:device")
            assertNotNull(device)
            assertTrue(m.devices.detach(a))
            // resolve で引いた後に外れた（device != null && !device.attached）
            val started = m.devices.dispatcher.start(0, device, "d", emptyList())
            val error = assertThrows<ExecutionException> { started.future.get() }.cause
            assertEquals(CallError.Detached, (error as DeviceCallException).error)
            assertEquals(0, called.get(), "外れたデバイスのハンドラーは呼ばない")
        }
    }

    @Test
    fun `同じaddressに違う種類がつながっていれば、外れているのと同じ`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000).also { it.fillFuel() }
            assertTrue(m.devices.attach(a, TestDevice(dw)))
            val called = AtomicInteger()
            val other = DeviceMethods.builder().direct("d") { _, _ -> called.incrementAndGet(); arrayOf(DeviceValue.of(0L)) }.build()
            val got = AtomicReference<List<Any?>>()
            h.runProcess(m) { c ->
                val fd = c.openDevice(a)
                m.devices.detach(a)
                assertTrue(m.devices.attach(a, TestDevice(other, kind = "test:other")))
                got.set(
                    listOf(
                        c.deviceInfo(fd),
                        c.deviceMethods(fd),
                        assertThrows<DeviceCallException> { c.invokeDevice(fd, "d", emptyList()) }.error,
                        c.takeDeviceCall(c.startDeviceCall(fd, "d", emptyList())),
                        // address で呼ぶ口は種類を見ない（今までどおり）
                        c.invokeDevice(a, "d", emptyList()),
                    ),
                )
            }
            assertEquals(
                listOf(
                    DeviceInfo(a, "test:device"),
                    emptyList<MethodInfo>(),
                    CallError.Detached,
                    CallResult.Error(CallError.Detached),
                    listOf(DeviceValue.of(0L)),
                ),
                got.get(),
            )
            assertEquals(1, called.get(), "違う種類のデバイスは fd からは呼ばない")
        }
    }

    @Test
    fun `info・methodsはプロセスのスレッドでなくても呼べる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000).also { it.fillFuel() }
            assertTrue(m.devices.attach(a, TestDevice(dw)))
            val got = AtomicReference<List<Any?>>()
            h.runProcess(m) { c ->
                val fd = c.openDevice(a)
                val seen = AtomicReference<List<Any?>>()
                val t = Thread { seen.set(listOf(c.deviceInfo(fd), c.deviceMethods(fd))) }
                t.start()
                t.join(5_000)
                got.set(seen.get())
            }
            assertEquals(listOf(DeviceInfo(a, "test:device"), listOf(MethodInfo("d", false), MethodInfo("w", true))), got.get())
        }
    }

    // ---- 戻したハンドル ----
    // 保存 → 上限の小さいマシンへの復元 → 回し直し、の実際の経路は DeviceLimitRestoreTest。ここは戻したハンドルの口（fd）からの呼び出しを確かめる

    @Test
    fun `戻したハンドルと積んだ呼び出しが、つながっていないaddressを指すと、fdからの呼び出しはdetached`() = timeout(limit) {
        Harness().use { h ->
            // 上限1で、2つ目（b）は断られる（保存から戻した経路は DeviceLimitRestoreTest）
            val m = h.machine(fuelPerTick = 1_000_000, maxDevices = 1).also { it.fillFuel() }
            val b = address(2)
            assertTrue(m.devices.attach(a, TestDevice(dw)))
            assertFalse(m.devices.attach(b, TestDevice(dw)))
            val got = AtomicReference<List<Any?>>()
            h.runProcess(m) { c ->
                // 保存から戻したハンドルと、まだ実行していない呼び出し（address と種類だけを持つ）
                val fd = c.withKernelLock {
                    FdTable(c.kernel.processes[c.pid]!!, c.kernel.objects, m.wakeSink)
                        .open(DeviceHandle.restore(DeviceHandle.Snapshot(b, "test:device")), HandleRights.READ)
                }
                val (callFd, obj) = c.withKernelLock {
                    m.openPendingDeviceCallLocked(c.pid, PendingDeviceCall.restore(PendingDeviceCall.Snapshot.Queued(b, "d", emptyList())).state)
                }
                m.dispatchPendingDeviceCall(c.pid, obj)
                got.set(
                    listOf(
                        c.deviceInfo(fd),
                        c.deviceMethods(fd),
                        assertThrows<DeviceCallException> { c.invokeDevice(fd, "d", emptyList()) }.error,
                        c.takeDeviceCall(callFd),
                    ),
                )
            }
            assertEquals(
                listOf(DeviceInfo(b, "test:device"), emptyList<MethodInfo>(), CallError.Detached, CallResult.Error(CallError.Detached)),
                got.get(),
            )
        }
    }
}
