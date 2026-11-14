package pro.ikili.kibicomputers.core.device.wiring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.core.device.DeviceAddress
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 位置から引くデバイスの結線（#129、ADR 0129） */
class MachineWiringTest {
    private val limit = Duration.ofSeconds(30)

    private class TestDevice(override val kind: String) : Device {
        override val methods: DeviceMethods get() = DeviceMethods.empty()
    }

    /** 記録する持ち主。[relays] は中継の候補 */
    private class Holder(
        val name: String,
        val relays: List<String> = emptyList(),
        override val relayable: Boolean = false,
        var address: String = DeviceAddress.random(),
    ) : DeviceHolder<String> {
        val log = ArrayList<String>()
        var created = 0

        override val deviceAddress: String get() = address

        override fun reassignDeviceAddress() {
            address = DeviceAddress.random()
            log += "reassigned"
        }

        override fun createDevice(): Device {
            created++
            return TestDevice("test:$name")
        }

        override fun onConnected(device: Device) {
            log += "connected"
        }

        override fun onDisconnected(device: Device, removed: Boolean) {
            log += if (removed) "removed" else "disconnected"
        }

        override fun relayKeys(): List<String> = relays
    }

    /** 隣を [neighbors] とする口。つなぎ直しの予約を数える */
    private class TestHost(val neighbors: List<String>) : MachineWiring.Host<String> {
        var scheduled = 0
        val refused = ArrayList<String>()

        override fun neighborKeys(): List<String> = neighbors

        override fun scheduleRewire() {
            scheduled++
        }

        override fun onRefused(machine: Machine, holder: DeviceHolder<String>) {
            refused += (holder as Holder).name
        }
    }

    private fun kinds(m: Machine): List<String> = m.devices.list().map { it.kind }

    @Test
    fun `隣の候補を決まった順につなぎ、中継のキーボードもつなぐ`() = timeout(limit) {
        Harness().use { h ->
            val registry = DeviceRegistry<String>()
            val screen = Holder("screen", relays = listOf("front", "far"))
            val keyboard = Holder("keyboard", relayable = true)
            val other = Holder("other")
            registry.register("up", screen)
            registry.register("front", keyboard)
            // 中継できないものは、中継の候補にあってもつながない
            registry.register("far", other)
            val host = TestHost(listOf("down", "up"))
            val wiring = MachineWiring(registry, host)
            val m = h.machine()
            wiring.attach(m)
            assertEquals(listOf("test:screen", "test:keyboard"), kinds(m))
            assertSame(wiring, registry["up"]!!.owner)
            assertNull(registry["far"]!!.owner)
            assertEquals(listOf(screen, keyboard), wiring.connected)
            wiring.disconnect()
            assertEquals(emptyList<String>(), kinds(m))
            assertNull(registry["up"]!!.owner)
            assertEquals(0, registry.watchedKeys, "外したら見張りもやめる")
        }
    }

    @Test
    fun `ほかの結線が持っているデバイスはつながず、手放されたら知らされる`() = timeout(limit) {
        Harness().use { h ->
            val registry = DeviceRegistry<String>()
            val keyboard = Holder("keyboard", relayable = true)
            registry.register("k", keyboard)
            val hostA = TestHost(listOf("k"))
            val hostB = TestHost(listOf("k"))
            val a = MachineWiring(registry, hostA)
            val b = MachineWiring(registry, hostB)
            val ma = h.machine()
            val mb = h.machine()
            a.attach(ma)
            b.attach(mb)
            assertEquals(listOf("test:keyboard"), kinds(ma))
            assertEquals(emptyList<String>(), kinds(mb), "2台目は取らない")
            assertEquals(0, hostB.scheduled)
            a.disconnect()
            assertEquals(1, hostB.scheduled, "手放されたら、見張っている結線に知らせる")
            assertEquals(0, hostA.scheduled, "手放した本人には知らせない")
            b.rewire()
            assertEquals(listOf("test:keyboard"), kinds(mb))
            assertSame(b, registry["k"]!!.owner)
            b.disconnect()
        }
    }

    @Test
    fun `登録が外れたらすぐ外し、載ったら知らせて新しい世代でつなぐ`() = timeout(limit) {
        Harness().use { h ->
            val registry = DeviceRegistry<String>()
            val host = TestHost(listOf("s"))
            val wiring = MachineWiring(registry, host)
            val m = h.machine()
            wiring.attach(m)
            assertEquals(0, m.devices.size)
            val first = Holder("screen")
            val e1 = registry.register("s", first)
            assertEquals(1, host.scheduled, "見張っているキーに載ったら知らせる（すぐにはつながない）")
            assertEquals(0, m.devices.size)
            wiring.rewire()
            assertEquals(1, m.devices.size)
            registry.unregister(e1)
            assertEquals(0, m.devices.size, "外れたらすぐに外す")
            assertEquals(listOf("connected", "removed"), first.log)
            assertFalse(e1.registered)
            assertEquals(2, host.scheduled, "外れたら見直しを頼む")
            // 壊して置き直したもの（新しい持ち主）は新しい世代
            val second = Holder("screen")
            val e2 = registry.register("s", second)
            assertTrue(e2.generation > e1.generation)
            wiring.rewire()
            assertSame(e2, wiring.entryOf(second))
            // 古い登録を外しても、今の登録には響かない
            registry.unregister(e1)
            assertEquals(1, m.devices.size)
            wiring.disconnect()
        }
    }

    @Test
    fun `満杯ならデバイスを作らずに断り、自分のデバイスが外れたら見直す`() = timeout(limit) {
        Harness().use { h ->
            val registry = DeviceRegistry<String>()
            val a = Holder("a")
            val b = Holder("b")
            val ea = registry.register("a", a)
            registry.register("b", b)
            val host = TestHost(listOf("a", "b"))
            val wiring = MachineWiring(registry, host)
            val m = h.machine(maxDevices = 1)
            wiring.attach(m)
            assertEquals(listOf("test:a"), kinds(m))
            assertEquals(listOf("b"), host.refused)
            assertEquals(0, b.created, "断ったものはデバイスを作らない")
            assertNull(registry["b"]!!.owner, "断ったものは持たない")
            registry.unregister(ea)
            assertEquals(1, host.scheduled)
            wiring.rewire()
            assertEquals(listOf("test:b"), kinds(m))
            wiring.disconnect()
        }
    }

    @Test
    fun `同じaddressがもうつながっていたら後から来たほうを振り直す`() = timeout(limit) {
        Harness().use { h ->
            val registry = DeviceRegistry<String>()
            val shared = DeviceAddress.random()
            val original = Holder("original", address = shared)
            val copy = Holder("copy", address = shared)
            registry.register("1", original)
            registry.register("2", copy)
            val wiring = MachineWiring(registry, TestHost(listOf("1", "2")))
            val m = h.machine()
            wiring.attach(m)
            assertEquals(listOf("test:original", "test:copy"), kinds(m))
            assertEquals(shared, original.address, "先につないだほうは変えない")
            assertNotEquals(shared, copy.address)
            assertEquals(listOf("reassigned", "connected"), copy.log)
            assertEquals(listOf(shared, copy.address), m.devices.list().map { it.address })
            wiring.disconnect()
        }
    }

    @Test
    fun `中継していた画面が外れたら、中継のキーボードも手放す`() = timeout(limit) {
        Harness().use { h ->
            val registry = DeviceRegistry<String>()
            val screen = Holder("screen", relays = listOf("front"))
            val keyboard = Holder("keyboard", relayable = true)
            val es = registry.register("up", screen)
            registry.register("front", keyboard)
            val wiring = MachineWiring(registry, TestHost(listOf("up")))
            val m = h.machine()
            wiring.attach(m)
            assertEquals(listOf("test:screen", "test:keyboard"), kinds(m))
            registry.unregister(es)
            assertEquals(emptyList<String>(), kinds(m), "中継のキーボードもすぐに外す")
            assertNull(registry["front"]!!.owner)
            assertEquals(listOf("connected", "disconnected"), keyboard.log)
            wiring.disconnect()
        }
    }

    @Test
    fun `同じ位置に新しい持ち主が先に載ったら、古い登録の持ち主を外し、後からの外れは何もせず、新しい世代でつなぎ直す`() = timeout(limit) {
        Harness().use { h ->
            val registry = DeviceRegistry<String>()
            val host = TestHost(listOf("s"))
            val wiring = MachineWiring(registry, host)
            val old = Holder("old")
            val e1 = registry.register("s", old)
            val m = h.machine()
            wiring.attach(m)
            assertEquals(listOf("test:old"), kinds(m))
            assertSame(wiring, e1.owner)
            val scheduledBefore = host.scheduled
            // チャンクの差し替え: 新しい持ち主が先に載り（setLevel）、古い持ち主の外れ（setRemoved）が後に来る
            val fresh = Holder("fresh")
            val e2 = registry.register("s", fresh)
            assertEquals(emptyList<String>(), kinds(m), "古い登録の持ち主の結線は、すぐに外れる")
            assertEquals(listOf("connected", "removed"), old.log)
            assertFalse(e1.registered)
            assertNull(e1.owner)
            assertTrue(e2.generation > e1.generation)
            assertTrue(host.scheduled > scheduledBefore, "外れたら見直しを頼む")
            assertNull(wiring.entryOf(old))
            // 古い持ち主の後からの外れは、何もしない（新しい登録にも、結線にも響かない）
            registry.unregister(e1)
            assertEquals(listOf("connected", "removed"), old.log)
            assertSame(e2, registry["s"])
            assertTrue(e2.registered)
            assertNull(e2.owner)
            // 新しい世代でつなぎ直す
            wiring.rewire()
            assertEquals(listOf("test:fresh"), kinds(m))
            assertSame(e2, wiring.entryOf(fresh))
            assertSame(wiring, e2.owner)
            assertEquals(listOf("connected"), fresh.log)
            wiring.disconnect()
            assertEquals(0, registry.watchedKeys)
        }
    }

    @Test
    fun `止まったマシンのdisconnectとデバイスのアンロードが重なっても投げない`() = timeout(limit) {
        Harness().use { h ->
            val registry = DeviceRegistry<String>()
            val screen = Holder("screen")
            val es = registry.register("s", screen)
            val wiring = MachineWiring(registry, TestHost(listOf("s")))
            val m = h.machine()
            wiring.attach(m)
            m.close()
            registry.unregister(es)
            wiring.disconnect()
            wiring.disconnect()
            assertNull(wiring.machine)
        }
    }
}
