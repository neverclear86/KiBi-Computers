package pro.ikili.kibicomputers.core.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.kernel.event.EventFilter
import pro.ikili.kibicomputers.core.kernel.event.EventQueue
import pro.ikili.kibicomputers.core.kernel.event.EventValue
import pro.ikili.kibicomputers.core.kernel.event.KernelEvent
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.Collections
import kotlin.concurrent.withLock
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** マシンにつなぐデバイスの表（#65、ADR 0065） */
class DeviceBusTest {
    private val limit = Duration.ofSeconds(30)

    private val a = "00000000-0000-4000-8000-00000000000a"
    private val b = "00000000-0000-4000-8000-00000000000b"

    /** つなぐ・外すの呼ばれ方を記録するデバイス */
    private class Recording(override val kind: String = "test:recording") : Device {
        val log: MutableList<String> = Collections.synchronizedList(ArrayList())
        var context: DeviceContext? = null
        var methodsRead = 0

        override val methods: DeviceMethods
            get() {
                methodsRead++
                return DeviceMethods.empty()
            }

        override fun onAttached(context: DeviceContext) {
            this.context = context
            log += "attached:${context.address}"
        }

        override fun onDetached(context: DeviceContext) {
            log += "detached:${context.address}"
        }
    }

    /** マシンのイベントを全部受けるキュー */
    private fun Machine.subscribeAll(): EventQueue = lock.withLock {
        kernel.events.ensureCapacity(kernel.objects)
        val q = EventQueue(EventFilter.ALL, 16)
        kernel.objects.add(q)
        kernel.events.subscribe(q, kernel.objects)
        q
    }

    @Test
    fun `つなぐと一覧に出て、外すと消え、onAttachedとonDetachedが呼ばれ、世代が上がる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val bus = m.devices
            val dev = Recording()
            assertEquals(0, bus.generation)
            bus.attach(a, dev)
            assertEquals(listOf(DeviceInfo(a, "test:recording")), bus.list())
            assertEquals(1, dev.methodsRead, "メソッドの表はつないだときに1回だけ読む")
            assertSame(dev, bus.deviceAt(a))
            assertEquals(1, bus.generation)
            bus.attach(b, Recording("test:other"))
            assertEquals(listOf(a, b), bus.list().map { it.address }, "つないだ順")
            assertTrue(bus.detach(a))
            assertFalse(bus.detach(a), "2回目は何もしない")
            assertEquals(listOf(b), bus.list().map { it.address })
            assertEquals(listOf("attached:$a", "detached:$a"), dev.log.toList())
            assertEquals(3, bus.generation)
            assertNull(bus.deviceAt(a))
        }
    }

    @Test
    fun `addressの重複と、addressと種類の形の誤りはつながない`() = timeout(limit) {
        Harness().use { h ->
            val bus = h.machine().devices
            bus.attach(a, Recording())
            assertThrows<IllegalArgumentException> { bus.attach(a, Recording()) }
            assertThrows<IllegalArgumentException> { bus.attach(a.uppercase().replace('A', 'B'), Recording()) }
            assertThrows<IllegalArgumentException> { bus.attach("not-a-uuid", Recording()) }
            assertThrows<IllegalArgumentException> { bus.attach(b, Recording("Bad:Kind")) }
            assertThrows<IllegalArgumentException> { bus.attach(b, Recording("nokind")) }
            assertEquals(listOf(a), bus.list().map { it.address })
            assertEquals(1, bus.generation)
        }
    }

    @Test
    fun `onAttachedが投げたら、つながずに例外を返す`() = timeout(limit) {
        Harness().use { h ->
            val bus = h.machine().devices
            val dev = object : Device {
                override val kind = "test:bad"
                override val methods = DeviceMethods.empty()

                override fun onAttached(context: DeviceContext): Unit = throw IllegalStateException("つなげない")
            }
            assertThrows<IllegalStateException> { bus.attach(a, dev) }
            assertTrue(bus.list().isEmpty())
            bus.attach(a, Recording())
        }
    }

    @Test
    fun `一覧はほかのスレッドがカーネルロックを持っていても読める`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            m.devices.attach(a, Recording())
            m.lock.withLock {
                val seen = arrayOfNulls<List<DeviceInfo>>(1)
                val t = Thread { seen[0] = m.devices.list() }
                t.start()
                t.join(5_000)
                assertEquals(listOf(DeviceInfo(a, "test:recording")), seen[0])
            }
        }
    }

    @Test
    fun `つなぐ・外すはどのスレッドからでもでき、世代はその回数だけ上がる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val threads = List(4) { i ->
                Thread {
                    val address = "00000000-0000-4000-8000-00000000010$i"
                    repeat(50) {
                        m.devices.attach(address, Recording())
                        m.devices.detach(address)
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            assertEquals(400, m.devices.generation)
            assertTrue(m.devices.list().isEmpty())
        }
    }

    @Test
    fun `postEventは送り元をaddressにしてイベントキューに届け、noneは載せず、外した後はfalse`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val q = m.subscribeAll()
            val dev = Recording()
            m.devices.attach(a, dev)
            val ctx = dev.context!!
            assertTrue(ctx.postEvent("heat", DeviceValue.of(5L), DeviceValue.none(), DeviceValue.of("hot"), DeviceValue.of(true)))
            val batch = m.lock.withLock { q.take(10) }
            assertEquals(
                listOf(KernelEvent("heat", a, listOf(EventValue.Integer(5), EventValue.Text("hot"), EventValue.Boolean(true)))),
                batch.events,
            )
            // 上限を超えるもの（名前が長い・空、値が多い）は捨てて false
            assertFalse(ctx.postEvent("x".repeat(65)))
            assertFalse(ctx.postEvent(""))
            assertFalse(ctx.postEvent("many", *Array(33) { DeviceValue.of(1L) }))
            m.devices.detach(a)
            assertFalse(ctx.postEvent("heat"))
        }
    }

    @Test
    fun `画面のデバイスがあればコンソールの出力はそこへ行き、なければ接続層へ行く`() = timeout(limit) {
        Harness().use { h ->
            val delivered = ByteArrayOutputStream()
            val m = h.machine(
                host = object : MachineHost {
                    override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

                    override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
                        delivered.write(bytes)
                    }
                },
            )
            val screen = ScreenDevice(TextBuffer(20, 3), 2)
            assertNull(m.devices.primaryScreen)
            m.writeConsole("one".toByteArray())
            h.scheduler.tick()
            assertEquals("one", delivered.toString(Charsets.UTF_8))
            m.devices.attach(a, Recording())
            m.devices.attach(SCREEN_ADDRESS, screen)
            assertSame(screen, m.devices.primaryScreen)
            assertSame(m, screen.machine)
            assertEquals(20 to 3, m.consoleSize.columns to m.consoleSize.rows, "画面の大きさに合わせる")
            m.writeConsole("two".toByteArray())
            h.scheduler.tick()
            assertEquals("one", delivered.toString(Charsets.UTF_8), "画面があれば接続層には来ない")
            assertEquals('t'.code, screen.screen.getCodePoint(0, 0))
            m.devices.detach(SCREEN_ADDRESS)
            assertNull(m.devices.primaryScreen)
            assertNull(screen.machine)
            m.writeConsole("3".toByteArray())
            h.scheduler.tick()
            assertEquals("one3", delivered.toString(Charsets.UTF_8))
        }
    }
}
