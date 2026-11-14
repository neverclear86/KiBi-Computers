package pro.ikili.kibicomputers.core.device.wiring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.core.device.DeviceAddress
import pro.ikili.kibicomputers.core.kernel.Harness
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 他 MOD のデバイスのアダプターの出し入れ（#153、ADR 0153） */
class ExternalDeviceSlotsTest {
    private val limit = Duration.ofSeconds(30)

    private class Foreign : Device {
        override val kind: String = "othermod:thing"
        override val methods: DeviceMethods = DeviceMethods.empty()
    }

    private class Addresses : ExternalDeviceHolder.AddressStore {
        val value = DeviceAddress.random()
        override fun read(): String = value
        override fun reassign() = throw IllegalStateException("no storage")
    }

    private class BuiltIn : DeviceHolder<String> {
        override val deviceAddress: String = DeviceAddress.random()
        override fun reassignDeviceAddress() {}
        override fun createDevice(): Device = object : Device {
            override val kind: String = "test:screen"
            override val methods: DeviceMethods = DeviceMethods.empty()
        }
    }

    private class Host(val neighbors: List<String>) : MachineWiring.Host<String> {
        override fun neighborKeys(): List<String> = neighbors
        override fun scheduleRewire() {}
    }

    private val factory = ExternalDeviceSlots.Factory<String> { key, device -> ExternalDeviceHolder(key, device, Addresses()) }

    @Test
    fun `見張る結線を数え、最後の結線が去ったら外す`() {
        val registry = DeviceRegistry<String>()
        val slots = ExternalDeviceSlots(registry)
        val d = Foreign()
        val a = Any()
        val b = Any()
        slots.offer("p", a, d, factory)
        val entry = registry["p"]!!
        slots.offer("p", b, d, factory)
        slots.offer("p", b, d, factory)
        assertEquals(2, slots.watcherCount("p"), "同じ結線は1回だけ数える")
        assertSame(entry, registry["p"], "同じ実体なら数えるだけ")
        slots.release("p", a)
        assertSame(entry, registry["p"])
        slots.release("p", a)
        assertEquals(1, slots.watcherCount("p"))
        slots.release("p", b)
        assertNull(registry["p"], "最後の結線が去ったら外す")
        assertEquals(0, slots.size)
        assertEquals(0, registry.groupCount)
        assertTrue((entry.holder as ExternalDeviceHolder).retired)
    }

    @Test
    fun `違う実体なら新しい世代に載せ直す`() {
        val registry = DeviceRegistry<String>()
        val slots = ExternalDeviceSlots(registry)
        val a = Any()
        slots.offer("p", a, Foreign(), factory)
        val first = registry["p"]!!
        slots.offer("p", a, Foreign(), factory)
        val second = registry["p"]!!
        assertNotSame(first, second)
        assertTrue(second.generation > first.generation)
        assertFalse(first.registered)
        assertTrue((first.holder as ExternalDeviceHolder).retired)
        assertEquals(1, slots.watcherCount("p"))
        assertEquals(1, registry.groupCount, "前の実体の組は残らない")
    }

    @Test
    fun `内蔵を優先し、置き換えの後にreleaseしても内蔵の登録は残る`() {
        val registry = DeviceRegistry<String>()
        val slots = ExternalDeviceSlots(registry)
        val screen = BuiltIn()
        val builtIn = registry.register("s", screen)
        val a = Any()
        slots.offer("s", a, Foreign(), factory)
        assertSame(builtIn, registry["s"], "内蔵が載っている位置には載せない")
        slots.release("s", a)
        assertSame(builtIn, registry["s"])
        // 先にアダプターが載り、後から内蔵が置き換えた
        slots.offer("t", a, Foreign(), factory)
        val other = registry.register("t", BuiltIn())
        slots.release("t", a)
        assertSame(other, registry["t"], "置き換えの後に release しても、内蔵の登録は残る")
        assertEquals(0, registry.groupCount)
    }

    @Test
    fun `同じ実体を2つのキーで受けても、つながるのは1台`() = timeout(limit) {
        Harness().use { h ->
            val registry = DeviceRegistry<String>()
            val slots = ExternalDeviceSlots(registry)
            val d = Foreign()
            val a = MachineWiring(registry, Host(listOf("p1")))
            val b = MachineWiring(registry, Host(listOf("p2")))
            slots.offer("p1", a, d, factory)
            slots.offer("p2", b, d, factory)
            val ma = h.machine()
            val mb = h.machine()
            a.attach(ma)
            b.attach(mb)
            assertEquals(1, ma.devices.size + mb.devices.size)
            assertEquals(1, registry.groupCount)
            // address はつないだ位置のもの
            assertEquals(slots.holderAt("p1")!!.deviceAddress, ma.devices.list().single().address)
            a.disconnect()
            b.disconnect()
        }
    }
}
