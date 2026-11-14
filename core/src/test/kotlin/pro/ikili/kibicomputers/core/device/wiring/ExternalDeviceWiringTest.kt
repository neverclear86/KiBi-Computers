package pro.ikili.kibicomputers.core.device.wiring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceContext
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.core.device.DeviceAddress
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 他 MOD のデバイスの結線（#153、ADR 0153）: 実体の単位の排他（[DeviceHolder.exclusionKey]）、他 MOD のコードが投げたときの受け方、
 * 投げた登録を同じ世代の間は試さないこと（知らせの連鎖が止まる）
 */
class ExternalDeviceWiringTest {
    private val limit = Duration.ofSeconds(30)

    /** 他 MOD のデバイス役。[attachFailures] 回まで onAttached で投げる（負なら毎回） */
    private class ForeignDevice(
        override val kind: String = "othermod:reactor",
        var attachFailures: Int = 0,
        val detachThrows: Boolean = false,
    ) : Device {
        val log = ArrayList<String>()
        var attachCalls = 0

        override val methods: DeviceMethods get() = DeviceMethods.empty()

        override fun onAttached(context: DeviceContext) {
            attachCalls++
            if (attachFailures != 0) {
                if (attachFailures > 0) attachFailures--
                throw IllegalStateException("boom")
            }
            log += "attached"
        }

        override fun onDetached(context: DeviceContext) {
            log += "detached"
            if (detachThrows) throw IllegalStateException("detach boom")
        }
    }

    /** address の置き場所の役。[readThrows] なら読むたびに投げる */
    private class Addresses(val readThrows: Boolean = false) : ExternalDeviceHolder.AddressStore {
        var value = DeviceAddress.random()
        var reads = 0

        override fun read(): String {
            reads++
            if (readThrows) throw IllegalStateException("attachment boom")
            return value
        }

        override fun reassign() {
            value = DeviceAddress.random()
        }
    }

    private class TestHost(var neighbors: List<String>) : MachineWiring.Host<String> {
        var scheduled = 0
        val refused = ArrayList<DeviceHolder<String>>()
        val attachFailed = ArrayList<Pair<String, String?>>()
        val detachFailed = ArrayList<String>()

        override fun neighborKeys(): List<String> = neighbors

        override fun scheduleRewire() {
            scheduled++
        }

        override fun onRefused(machine: Machine, holder: DeviceHolder<String>) {
            refused += holder
        }

        override fun onAttachFailed(entry: DeviceRegistry.Entry<String>, address: String?, error: Throwable) {
            attachFailed += entry.key to address
        }

        override fun onDetachFailed(entry: DeviceRegistry.Entry<String>, address: String, error: Throwable) {
            detachFailed += entry.key
        }
    }

    private class Plain(val name: String) : DeviceHolder<String> {
        private var address = DeviceAddress.random()
        override val deviceAddress: String get() = address
        override fun reassignDeviceAddress() {
            address = DeviceAddress.random()
        }
        override fun createDevice(): Device = object : Device {
            override val kind: String = "test:$name"
            override val methods: DeviceMethods = DeviceMethods.empty()
        }
    }

    private fun adapter(key: String, device: Device, addresses: Addresses = Addresses()) = ExternalDeviceHolder(key, device, addresses)

    private fun kinds(m: Machine): List<String> = m.devices.list().map { it.kind }

    /**
     * 知らせが来なくなるまで、頼まれた結線のつなぎ直しを回す（接続層の `DeviceWiring.flush` の代わり）。回した回数を返す。
     * 止まらなければ [rounds] で打ち切って失敗にする
     */
    private fun settle(vararg pairs: Pair<MachineWiring<String>, TestHost>, rounds: Int = 50): Int {
        var count = 0
        repeat(rounds) {
            val due = pairs.filter { it.second.scheduled > 0 }
            if (due.isEmpty()) return count
            for ((w, h) in due) {
                h.scheduled = 0
                count++
                w.rewire()
            }
        }
        throw AssertionError("つなぎ直しの知らせが止まらない")
    }

    @Test
    fun `同じ実体を2つの結線が見張っても、つながるのは1台で、手放したらもう一方がつなぐ`() = timeout(limit) {
        Harness().use { h ->
            val registry = DeviceRegistry<String>()
            val d = ForeignDevice()
            registry.register("p1", adapter("p1", d))
            registry.register("p2", adapter("p2", d))
            val hostA = TestHost(listOf("p1"))
            val hostB = TestHost(listOf("p2"))
            val a = MachineWiring(registry, hostA)
            val b = MachineWiring(registry, hostB)
            val ma = h.machine()
            val mb = h.machine()
            a.attach(ma)
            b.attach(mb)
            assertEquals(listOf("othermod:reactor"), kinds(ma))
            assertEquals(emptyList<String>(), kinds(mb), "同じ実体のほかの位置からはつながない")
            a.disconnect()
            assertTrue(hostB.scheduled > 0, "同じ実体のほかの位置を見張る結線に知らせる")
            b.rewire()
            assertEquals(listOf("othermod:reactor"), kinds(mb))
            assertEquals(listOf("attached", "detached", "attached"), d.log, "onAttached と onDetached が交互に並ぶ")
            b.disconnect()
            assertEquals(listOf("attached", "detached", "attached", "detached"), d.log)
        }
    }

    @Test
    fun `1つの結線が同じ実体の2つのキーに触れても1回だけつなぎ、候補から外れるともう一方からつなぐ`() = timeout(limit) {
        Harness().use { h ->
            val registry = DeviceRegistry<String>()
            val d = ForeignDevice()
            val h1 = adapter("p1", d)
            val h2 = adapter("p2", d)
            registry.register("p1", h1)
            registry.register("p2", h2)
            val host = TestHost(listOf("p1", "p2"))
            val w = MachineWiring(registry, host)
            val m = h.machine()
            w.attach(m)
            assertEquals(listOf("othermod:reactor"), kinds(m))
            assertEquals(listOf(h1), w.connected, "決まった順で最初の位置からつなぐ")
            host.neighbors = listOf("p2")
            w.rewire()
            assertEquals(listOf(h2), w.connected, "同じ rewire で、残った位置からつなぎ直す")
            assertEquals(listOf("attached", "detached", "attached"), d.log)
            w.disconnect()
        }
    }

    @Test
    fun `満杯で断った結線は持たず、空いても同じ実体をほかが持つ間はつながず、手放されたらつなぐ`() = timeout(limit) {
        Harness().use { h ->
            val registry = DeviceRegistry<String>()
            val d = ForeignDevice()
            val ex = registry.register("x", Plain("x"))
            registry.register("p1", adapter("p1", d))
            registry.register("p2", adapter("p2", d))
            val hostA = TestHost(listOf("x", "p1"))
            val hostB = TestHost(listOf("p2"))
            val a = MachineWiring(registry, hostA)
            val b = MachineWiring(registry, hostB)
            val ma = h.machine(maxDevices = 1)
            val mb = h.machine()
            a.attach(ma)
            assertEquals(listOf("test:x"), kinds(ma))
            assertEquals(1, hostA.refused.size, "満杯なので断る")
            b.attach(mb)
            assertEquals(listOf("othermod:reactor"), kinds(mb), "A が断ったので B がつながる")
            registry.unregister(ex)
            a.rewire()
            assertEquals(emptyList<String>(), kinds(ma), "B が持つ間は、空きができてもつながない")
            assertEquals(0, a.failedCount, "断ったものは覚えない")
            hostA.scheduled = 0
            b.disconnect()
            assertTrue(hostA.scheduled > 0)
            a.rewire()
            assertEquals(listOf("othermod:reactor"), kinds(ma), "B が手放したら A がつなぐ")
            a.disconnect()
            assertEquals(listOf("attached", "detached", "attached", "detached"), d.log)
        }
    }

    @Test
    fun `1回目だけ投げたら、もう一方がつなぎ、同じ世代では試さず、電源を入れ直すとつなぐ`() = timeout(limit) {
        Harness().use { h ->
            val registry = DeviceRegistry<String>()
            val d = ForeignDevice(attachFailures = 1)
            val hostA = TestHost(listOf("p1"))
            val hostB = TestHost(listOf("p2"))
            val a = MachineWiring(registry, hostA)
            val b = MachineWiring(registry, hostB)
            val ma = h.machine()
            val mb = h.machine()
            // 両方が見張っているところに、同じ実体を2つの位置で出す
            a.attach(ma)
            b.attach(mb)
            registry.register("p1", adapter("p1", d))
            registry.register("p2", adapter("p2", d))
            hostB.scheduled = 0
            a.rewire()
            assertEquals(emptyList<String>(), kinds(ma), "投げたらつながない")
            assertEquals(listOf("p1" to registry["p1"]!!.holder.deviceAddress), hostA.attachFailed, "読めた address をそのまま渡す")
            assertEquals(1, a.failedCount)
            assertTrue(hostB.scheduled > 0, "投げて手放したら、同じ実体を見張る B に知らせる")
            b.rewire()
            assertEquals(listOf("othermod:reactor"), kinds(mb), "B はつながる")
            hostA.scheduled = 0
            b.disconnect()
            assertTrue(hostA.scheduled > 0, "手放したら A に知らせる")
            a.rewire()
            assertEquals(emptyList<String>(), kinds(ma), "同じ世代では試さない")
            assertEquals(2, d.attachCalls, "A が1回、B が1回")
            a.disconnect()
            a.attach(ma)
            assertEquals(listOf("othermod:reactor"), kinds(ma), "電源を入れ直すと試す")
            a.disconnect()
            assertEquals(listOf("attached", "detached", "attached", "detached"), d.log)
        }
    }

    @Test
    fun `いつも投げる持ち主を2つの結線が見張っても、知らせの連鎖は止まり、試すのは結線ごとに1回`() = timeout(limit) {
        Harness().use { h ->
            val registry = DeviceRegistry<String>()
            val d = ForeignDevice(attachFailures = -1)
            registry.register("p", adapter("p", d))
            val hostA = TestHost(listOf("p"))
            val hostB = TestHost(listOf("p"))
            val a = MachineWiring(registry, hostA)
            val b = MachineWiring(registry, hostB)
            a.attach(h.machine())
            b.attach(h.machine())
            settle(a to hostA, b to hostB)
            assertEquals(2, d.attachCalls, "結線ごとに1回だけ試す")
            assertNull(registry["p"]!!.owner)
            a.disconnect()
            b.disconnect()
        }
    }

    @Test
    fun `種類の形が誤っている持ち主でも、同じ実体の2つのキーで止まる`() = timeout(limit) {
        Harness().use { h ->
            val registry = DeviceRegistry<String>()
            val d = ForeignDevice(kind = "MyMod:Reactor")
            registry.register("p1", adapter("p1", d))
            registry.register("p2", adapter("p2", d))
            val hostA = TestHost(listOf("p1", "p2"))
            val hostB = TestHost(listOf("p2", "p1"))
            val a = MachineWiring(registry, hostA)
            val b = MachineWiring(registry, hostB)
            a.attach(h.machine())
            b.attach(h.machine())
            settle(a to hostA, b to hostB)
            assertEquals(2, a.failedCount)
            assertEquals(2, b.failedCount)
            assertEquals(4, hostA.attachFailed.size + hostB.attachFailed.size, "(結線, 登録) の組ごとに1回")
            assertEquals(0, d.attachCalls, "種類の形の誤りは onAttached の前に断る")
            a.disconnect()
            b.disconnect()
            assertEquals(0, a.failedCount, "disconnect で忘れる")
        }
    }

    @Test
    fun `新しい登録にすると、もう1回だけ試す`() = timeout(limit) {
        Harness().use { h ->
            val registry = DeviceRegistry<String>()
            val d = ForeignDevice(attachFailures = -1)
            registry.register("p", adapter("p", d))
            val host = TestHost(listOf("p"))
            val w = MachineWiring(registry, host)
            w.attach(h.machine())
            assertEquals(1, d.attachCalls)
            w.rewire()
            assertEquals(1, d.attachCalls)
            registry.register("p", adapter("p", d))
            settle(w to host)
            assertEquals(2, d.attachCalls, "新しい登録なので1回試す")
            w.rewire()
            assertEquals(2, d.attachCalls)
            // 候補から外れたら忘れる
            host.neighbors = emptyList()
            w.rewire()
            assertEquals(0, w.failedCount)
            w.disconnect()
        }
    }

    @Test
    fun `addressを読むたびに投げる持ち主でも、rewireの外に出ず、残りの候補がつながり、見張りが更新される`() = timeout(limit) {
        Harness().use { h ->
            val registry = DeviceRegistry<String>()
            val bad = adapter("bad", ForeignDevice(), Addresses(readThrows = true))
            registry.register("bad", bad)
            registry.register("good", Plain("good"))
            val host = TestHost(listOf("bad", "good", "later"))
            val w = MachineWiring(registry, host)
            val m = h.machine()
            w.attach(m)
            assertEquals(listOf("test:good"), kinds(m))
            assertEquals(listOf("bad" to null), host.attachFailed, "読む前に投げたら address は null")
            assertEquals(3, registry.watchedKeys, "見張りは更新される")
            host.scheduled = 0
            registry.register("later", Plain("later"))
            assertEquals(1, host.scheduled)
            w.rewire()
            assertEquals(listOf("test:good", "test:later"), kinds(m))
            w.disconnect()
        }
    }

    @Test
    fun `onDetachedが投げても、外すのと手放すのを済ませ、残りも外し続ける`() = timeout(limit) {
        Harness().use { h ->
            val registry = DeviceRegistry<String>()
            val d = ForeignDevice(detachThrows = true)
            registry.register("p", adapter("p", d))
            registry.register("q", Plain("q"))
            val host = TestHost(listOf("q", "p"))
            val w = MachineWiring(registry, host)
            val m = h.machine()
            w.attach(m)
            assertEquals(2, m.devices.size)
            w.disconnect()
            assertEquals(0, m.devices.size, "どちらも外れる")
            assertEquals(listOf("p"), host.detachFailed)
            assertNull(registry["p"]!!.owner)
            assertNull(registry["q"]!!.owner)
        }
    }

    @Test
    fun `内蔵に置き換わったら、つないでいてもいなくても、同じ実体のほかの位置からつながる`() = timeout(limit) {
        Harness().use { h ->
            val registry = DeviceRegistry<String>()
            val d = ForeignDevice()
            registry.register("p1", adapter("p1", d))
            registry.register("p2", adapter("p2", d))
            val hostA = TestHost(listOf("p1"))
            val hostB = TestHost(listOf("p2"))
            val a = MachineWiring(registry, hostA)
            val b = MachineWiring(registry, hostB)
            val ma = h.machine()
            val mb = h.machine()
            a.attach(ma)
            b.attach(mb)
            assertEquals(emptyList<String>(), kinds(mb))
            // A がつないでいた位置が内蔵に置き換わる
            registry.register("p1", Plain("screen"))
            assertTrue(hostB.scheduled > 0)
            settle(a to hostA, b to hostB)
            assertEquals(listOf("test:screen"), kinds(ma))
            assertEquals(listOf("othermod:reactor"), kinds(mb), "B がつながる")
            // つないでいなかった位置が置き換わっても、つないでいるほうは変わらない
            registry.register("p3", adapter("p3", d))
            registry.register("p3", Plain("other"))
            assertEquals(listOf("othermod:reactor"), kinds(mb))
            assertEquals(listOf("attached", "detached", "attached"), d.log)
            a.disconnect()
            b.disconnect()
            registry.unregister(registry["p2"]!!)
            assertEquals(0, registry.groupCount, "組の外し忘れがない")
        }
    }

    @Test
    fun `exclusionKeyがnullなら今までどおり位置ごとの排他`() = timeout(limit) {
        Harness().use { h ->
            val registry = DeviceRegistry<String>()
            registry.register("p1", Plain("a"))
            registry.register("p2", Plain("b"))
            val w = MachineWiring(registry, TestHost(listOf("p1", "p2")))
            val m = h.machine()
            w.attach(m)
            assertEquals(listOf("test:a", "test:b"), kinds(m))
            assertEquals(0, registry.groupCount)
            assertSame(w, registry["p1"]!!.owner)
            w.disconnect()
        }
    }
}
