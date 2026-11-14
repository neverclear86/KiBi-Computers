package pro.ikili.kibicomputers.core.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceContext
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.kernel.BootConfig
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MemoryModuleStore
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.emptyModule
import pro.ikili.kibicomputers.core.kernel.event.EventFilter
import pro.ikili.kibicomputers.core.kernel.event.EventQueue
import pro.ikili.kibicomputers.core.kernel.event.EventValue
import pro.ikili.kibicomputers.core.kernel.event.KernelEvent
import pro.ikili.kibicomputers.core.kernel.event.RestoreEvents
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.spawnTest
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.tickUntilTrue
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.MachineIdIndex
import pro.ikili.kibicomputers.core.persist.MachineOwner
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.core.persist.RestoreListener
import pro.ikili.kibicomputers.core.persist.RestoreOutcome
import pro.ikili.kibicomputers.core.persist.RestoreRequest
import pro.ikili.kibicomputers.core.persist.RestoreSource
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** デバイスの接続・切断のイベント `device-attached`・`device-detached`（#67、ADR 0067） */
class DeviceEventTest {
    @TempDir
    lateinit var dir: Path

    private val limit = Duration.ofSeconds(30)

    private val a = "00000000-0000-4000-8000-00000000067a"
    private val b = "00000000-0000-4000-8000-00000000067b"

    /** 何もしないデバイス */
    private open class Plain(override val kind: String = "test:plain") : Device {
        override val methods: DeviceMethods = DeviceMethods.empty()
    }

    private fun attached(address: String, kind: String = "test:plain") =
        KernelEvent(DeviceEvents.ATTACHED, "", listOf(EventValue.Text(address), EventValue.Text(kind)))

    private fun detached(address: String, kind: String = "test:plain") =
        KernelEvent(DeviceEvents.DETACHED, "", listOf(EventValue.Text(address), EventValue.Text(kind)))

    /** マシンに、[filter] のイベントを受けるキュー（容量 [capacity]）を開く */
    private fun Machine.subscribe(filter: EventFilter = EventFilter.ALL, capacity: Int = 64): EventQueue = lock.withLock {
        kernel.events.ensureCapacity(kernel.objects)
        val q = EventQueue(filter, capacity)
        kernel.objects.add(q)
        kernel.events.subscribe(q, kernel.objects)
        q
    }

    private fun Machine.pending(q: EventQueue): List<KernelEvent> = lock.withLock { q.pendingEvents() }

    @Test
    fun `つなぐとdevice-attached、外すとdevice-detachedが、全部を受けるキューと種類を絞ったキューに1件ずつ届く`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val all = m.subscribe()
            val onlyAttached = m.subscribe(EventFilter(setOf(DeviceEvents.ATTACHED)))
            val onlyDetached = m.subscribe(EventFilter(setOf(DeviceEvents.DETACHED)))
            val other = m.subscribe(EventFilter(setOf("timer")))
            assertTrue(m.devices.attach(a, Plain()))
            assertTrue(m.devices.attach(b, Plain("test:other")))
            assertTrue(m.devices.detach(a))
            assertEquals(listOf(attached(a), attached(b, "test:other"), detached(a)), m.pending(all))
            assertEquals(listOf(attached(a), attached(b, "test:other")), m.pending(onlyAttached))
            assertEquals(listOf(detached(a)), m.pending(onlyDetached))
            assertEquals(emptyList<KernelEvent>(), m.pending(other))
        }
    }

    @Test
    fun `切断の種類は、つないだときの値を使う`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val q = m.subscribe()
            var current = "test:first"
            val dev = object : Device {
                override val kind: String get() = current
                override val methods: DeviceMethods = DeviceMethods.empty()
            }
            m.devices.attach(a, dev)
            current = "test:changed"
            m.devices.detach(a)
            assertEquals(listOf(attached(a, "test:first"), detached(a, "test:first")), m.pending(q))
        }
    }

    @Test
    fun `断ったつなぎ・失敗したつなぎ・形の誤り・つないでいない切断では出さない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(maxDevices = 1)
            val q = m.subscribe()
            assertTrue(m.devices.attach(a, Plain()))
            assertFalse(m.devices.attach(b, Plain()), "上限で断る")
            assertThrows<IllegalArgumentException> { m.devices.attach(a, Plain()) }
            assertThrows<IllegalArgumentException> { m.devices.attach("not-a-uuid", Plain()) }
            assertThrows<IllegalArgumentException> { m.devices.attach(b, Plain("Bad:Kind")) }
            assertFalse(m.devices.detach(b), "つないでいない")
            assertTrue(m.devices.detach(a))
            assertFalse(m.devices.detach(a), "2回目")
            val failing = object : Plain("test:bad") {
                override fun onAttached(context: DeviceContext): Unit = throw IllegalStateException("つなげない")
            }
            assertThrows<IllegalStateException> { m.devices.attach(b, failing) }
            assertTrue(m.devices.list().isEmpty())
            assertEquals(listOf(attached(a), detached(a)), m.pending(q))
        }
    }

    @Test
    fun `onDetachedが投げてもdevice-detachedは出て、例外は呼び手に届く`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val q = m.subscribe()
            val dev = object : Plain() {
                override fun onDetached(context: DeviceContext): Unit = throw IllegalStateException("外せない")
            }
            m.devices.attach(a, dev)
            assertThrows<IllegalStateException> { m.devices.detach(a) }
            assertTrue(m.devices.list().isEmpty())
            assertEquals(listOf(attached(a), detached(a)), m.pending(q))
        }
    }

    @Test
    fun `onAttachedの中で自分を外したデバイスは、接続も切断も出さない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val q = m.subscribe()
            val detachedInside = AtomicReference<Boolean>()
            val dev = object : Plain("test:self") {
                override fun onAttached(context: DeviceContext) {
                    detachedInside.set(m.devices.detach(context.address))
                }
            }
            assertTrue(m.devices.attach(a, dev))
            assertEquals(true, detachedInside.get())
            assertTrue(m.devices.list().isEmpty())
            assertEquals(emptyList<KernelEvent>(), m.pending(q))
            // 後で同じ address につなげば、ふつうに出る
            m.devices.attach(a, Plain())
            assertEquals(listOf(attached(a)), m.pending(q))
        }
    }

    @Test
    fun `onAttachedの中で別のデバイスをつなぐと、内側の接続が先に並ぶ`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val q = m.subscribe()
            val outer = object : Plain("test:outer") {
                override fun onAttached(context: DeviceContext) {
                    m.devices.attach(b, Plain("test:inner"))
                }
            }
            m.devices.attach(a, outer)
            assertEquals(listOf(attached(b, "test:inner"), attached(a, "test:outer")), m.pending(q))
        }
    }

    @Test
    fun `onAttachedの中で自分を外して同じaddressにつなぎ直し、その後で投げても、つなぎ直したデバイスは残る`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val q = m.subscribe()
            val inner = Plain("test:inner")
            val outer = object : Plain("test:outer") {
                override fun onAttached(context: DeviceContext) {
                    assertTrue(m.devices.detach(context.address))
                    assertTrue(m.devices.attach(context.address, inner))
                    throw IllegalStateException("つなげない")
                }
            }
            assertThrows<IllegalStateException> { m.devices.attach(a, outer) }
            // 内側のデバイスは表に残り、接続は届いていて、切断は出ていない
            assertEquals(listOf(DeviceInfo(a, "test:inner")), m.devices.list())
            assertTrue(m.devices.deviceAt(a) === inner)
            assertEquals(listOf(attached(a, "test:inner")), m.pending(q))
            // 外せば、切断が対になって届く
            assertTrue(m.devices.detach(a))
            assertEquals(listOf(attached(a, "test:inner"), detached(a, "test:inner")), m.pending(q))
        }
    }

    @Test
    fun `onAttachedの中で自分を外してから投げても、接続も切断も出ず、表は空のまま`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val q = m.subscribe()
            val outer = object : Plain("test:outer") {
                override fun onAttached(context: DeviceContext) {
                    m.devices.detach(context.address)
                    throw IllegalStateException("つなげない")
                }
            }
            assertThrows<IllegalStateException> { m.devices.attach(a, outer) }
            assertTrue(m.devices.list().isEmpty())
            assertEquals(emptyList<KernelEvent>(), m.pending(q))
        }
    }

    @Test
    fun `キューがなくても、閉じたマシンでも、つなぐ・外すは例外にならない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            assertTrue(m.devices.attach(a, Plain()))
            assertTrue(m.devices.detach(a))
            assertTrue(m.devices.attach(a, Plain()))
            m.close()
            assertTrue(m.devices.detach(a), "接続層の disconnect は close の後にも呼ばれる")
            assertTrue(m.devices.attach(b, Plain()))
            assertTrue(m.devices.detach(b))
        }
    }

    @Test
    fun `別のスレッドから同じaddressをつなぐ・外すと、接続と切断が交互に並ぶ`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            // 4 スレッド × 30 回で、成功した接続・切断は多くて 240 件（キューの上限 256 に収まる）
            val q = m.subscribe(capacity = 256)
            val threads = List(4) {
                Thread {
                    repeat(30) {
                        try {
                            if (m.devices.attach(a, Plain())) m.devices.detach(a)
                        } catch (_: IllegalArgumentException) {
                            // ほかのスレッドがつないでいる
                        }
                        m.devices.detach(a)
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            val batch = m.lock.withLock { q.take(256) }
            assertEquals(0L, batch.dropped)
            assertTrue(batch.events.isNotEmpty())
            assertEquals(0, batch.events.size % 2, "接続と切断が対になる")
            batch.events.forEachIndexed { i, e ->
                assertEquals(if (i % 2 == 0) attached(a) else detached(a), e, "$i 番目")
            }
            assertEquals(batch.events.size.toLong(), m.devices.generation, "成功した接続・切断ごとに1件")
        }
    }

    @Test
    fun `イベントを入れる時点で、一覧はもう新しい表になっている`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val q = m.subscribe()
            fun checkWhileBlocked(action: () -> Unit, expected: List<DeviceInfo>) {
                val done = CountDownLatch(1)
                m.lock.withLock {
                    Thread {
                        action()
                        done.countDown()
                    }.start()
                    // 表を入れ替えた後、イベントを入れるためのカーネルロックで止まっている
                    eventually(5_000, "表が変わらない") { m.devices.list() == expected }
                    assertFalse(done.await(100, TimeUnit.MILLISECONDS), "カーネルロックを取らずに返った")
                    assertTrue(q.pendingEvents().none { it.values.first() == EventValue.Text(a) && it.type == expectedType(expected) })
                }
                assertTrue(done.await(5, TimeUnit.SECONDS))
            }
            checkWhileBlocked({ m.devices.attach(a, Plain()) }, listOf(DeviceInfo(a, "test:plain")))
            checkWhileBlocked({ m.devices.detach(a) }, emptyList())
            assertEquals(listOf(attached(a), detached(a)), m.pending(q))
        }
    }

    private fun expectedType(expected: List<DeviceInfo>): String = if (expected.isEmpty()) DeviceEvents.DETACHED else DeviceEvents.ATTACHED

    @Test
    fun `デバイスが同じ名前のイベントを出しても、sourceがaddressなので見分けられる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val q = m.subscribe(EventFilter(setOf(DeviceEvents.ATTACHED)))
            val captured = AtomicReference<DeviceContext>()
            val dev = object : Plain() {
                override fun onAttached(context: DeviceContext) {
                    captured.set(context)
                }
            }
            m.devices.attach(a, dev)
            assertTrue(captured.get().postEvent(DeviceEvents.ATTACHED))
            val events = m.pending(q)
            assertEquals(listOf(attached(a), KernelEvent(DeviceEvents.ATTACHED, a, emptyList())), events)
            assertEquals(listOf(attached(a)), events.filter { it.source == DeviceEvents.SOURCE })
        }
    }

    @Test
    fun `画面をつなぐと、tty-resizeがdevice-attachedより先に並ぶ`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val q = m.subscribe()
            // 既定（80x25）と違う大きさ。大きさが変わったときだけ tty-resize が出る
            val screen = ScreenDevice(TextBuffer(40, 12), 2)
            m.devices.attach(SCREEN_ADDRESS, screen)
            val events = m.pending(q)
            assertEquals(listOf("tty-resize", DeviceEvents.ATTACHED), events.map { it.type })
            assertEquals(attached(SCREEN_ADDRESS, DeviceKind.SCREEN), events[1])
            m.devices.detach(SCREEN_ADDRESS)
            assertEquals(detached(SCREEN_ADDRESS, DeviceKind.SCREEN), m.pending(q).last())
        }
    }

    @Test
    fun `device-attachedを待っていた待機点型のプロセスは、接続で起きる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val handle = AtomicInteger(-1)
            val seen = AtomicReference<List<KernelEvent>>()
            val driver = TestDriver(
                ProcessKind.WAIT_POINT,
                onResume = { ctx, _ ->
                    val batch = ctx.takeEvents(handle.get())
                    if (batch.events.isEmpty()) {
                        StepResult.Wait(listOf(ctx.eventWaitKey(handle.get())))
                    } else {
                        seen.set(batch.events)
                        StepResult.Exit(0)
                    }
                },
            ) { ctx ->
                handle.set(ctx.openEventQueue(EventFilter(setOf(DeviceEvents.ATTACHED))))
                StepResult.Wait(listOf(ctx.eventWaitKey(handle.get())))
            }
            val pid = m.spawnTest(h.program(emptyModule()), driver)
            h.tickUntilTrue { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            Thread { m.devices.attach(a, Plain()) }.apply { start() }.join()
            h.tickUntilTrue { m.stateOf(pid).isTerminal }
            assertEquals(listOf(attached(a)), seen.get())
        }
    }

    @Test
    fun `復元では、restoredの後につなぎ直したデバイスのdevice-attachedが並び、消えた分のdevice-detachedは出ない`() = timeout(Duration.ofSeconds(120)) {
        val owner = MachineOwner("minecraft:overworld", 0, 64, 0)
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "root") BootVolume(TestMemoryFileSystem(), readOnly = false) else null }
            // 保存するマシン: a・b をつないでから、全部を受けるキューを開いて書く
            val saved = run {
                val m = h.machine(fuelPerTick = 10_000_000)
                val bytes = requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/prompt-wasi.wasm")) { "prompt-wasi.wasm がない" }.use { it.readBytes() }
                m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "disk:root")
                m.vfs.createDirectory(VfsPath.parse("/bin"))
                val f = m.vfs.open(VfsPath.parse("/bin/prompt"), OpenOptions(read = true, write = true, create = true, truncate = true))
                m.vfs.write(f, bytes, 0, bytes.size)
                val input = m.lock.withLock { ByteStream(4096).also { m.kernel.objects.add(it) }.id }
                val output = m.lock.withLock { ByteStream(64 * 1024).also { m.kernel.objects.add(it) }.id }
                val stdio = StdioSpec(StdioSource.Object(input, HandleRights.READ), StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed)
                val pid = m.exec("/bin/prompt", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("prompt"), stdio = stdio))
                assertTrue(m.devices.attach(a, Plain()))
                assertTrue(m.devices.attach(b, Plain()))
                m.lock.withLock {
                    m.kernel.initPid = pid
                    m.openEventQueueLocked(pid, EventFilter.ALL, 64)
                }
                eventually(60_000, "入力を待たない") {
                    h.scheduler.tick()
                    m.stateOf(pid) == ProcessState.WAITING_EVENT && m.isAtWaitPoint
                }
                val identity = store.assignNewId(owner)!!
                val written = store.write(identity, owner, m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT)!!).identity
                m.close()
                written
            }

            val m = h.machine(fuelPerTick = 10_000_000)
            val outcomes = Collections.synchronizedList(ArrayList<RestoreOutcome>())
            val errors = Collections.synchronizedList(ArrayList<Throwable>())
            val listener = RestoreListener { machine, outcome, _ ->
                try {
                    // ComputerCase.startRestore と同じく、受け手の中でつなぎ直す（b はもうない）
                    if (outcome == RestoreOutcome.RESTORED) machine.devices.attach(a, Plain())
                } catch (t: Throwable) {
                    errors += t
                }
                outcomes += outcome
            }
            val boot = BootConfig.builder().addBootDisk("none").build()
            assertTrue(m.restore(RestoreRequest(RestoreSource(store, saved.id, saved.generation), boot, listener)))
            eventually(60_000, "復元が終わらない") {
                h.scheduler.tick()
                outcomes.isNotEmpty()
            }
            errors.firstOrNull()?.let { throw AssertionError("復元の知らせの中で失敗した", it) }
            assertEquals(listOf(RestoreOutcome.RESTORED), outcomes.toList())
            val events = m.lock.withLock {
                val out = ArrayList<KernelEvent>()
                m.kernel.objects.forEachObject { obj -> if (obj is EventQueue && obj.filter.acceptsAll) out += obj.pendingEvents() }
                out
            }
            assertEquals(listOf(RestoreEvents.TYPE, DeviceEvents.ATTACHED), events.map { it.type }, "$events")
            assertEquals(attached(a), events[1])
            assertTrue(events.none { it.type == DeviceEvents.DETACHED }, "消えた b の切断は出さない")
            m.close()
        }
    }
}
