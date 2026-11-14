package pro.ikili.kibicomputers.core.device.wiring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceArguments
import pro.ikili.kibicomputers.api.device.DeviceCallContext
import pro.ikili.kibicomputers.api.device.DeviceCompletion
import pro.ikili.kibicomputers.api.device.DeviceException
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.device.CallError
import pro.ikili.kibicomputers.core.device.CallResult
import pro.ikili.kibicomputers.core.device.DeviceAddress
import pro.ikili.kibicomputers.core.device.DeviceDispatcher
import pro.ikili.kibicomputers.core.kernel.Harness
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 他 MOD のデバイスの包み（#153、ADR 0153） */
class GuardedDeviceTest {
    private class Counting : Device {
        var kindReads = 0
        var methodReads = 0
        private val table = DeviceMethods.builder()
            .direct("ping") { _, _ -> arrayOf(DeviceValue.of(1L)) }
            .mainThread("set") { _, _ -> arrayOf() }
            .build()

        override val kind: String get() = "othermod:thing".also { kindReads++ }
        override val methods: DeviceMethods get() = table.also { methodReads++ }
    }

    private object Ctx : DeviceCallContext {
        override val address: String = "00000000-0000-0000-0000-000000000000"
        override val method: String = "ping"
        override val isMainThread: Boolean = false
        override fun consumeFuel(amount: Long) {}
        override fun defer(): DeviceCompletion = throw UnsupportedOperationException()
    }

    @Test
    fun `種類とメソッドの表は作るときに1回だけ読み、revokeの後の呼び出しはfailedになる`() {
        val inner = Counting()
        val g = GuardedDevice(inner)
        repeat(3) {
            assertEquals("othermod:thing", g.kind)
            g.methods
        }
        assertEquals(1, inner.kindReads)
        assertEquals(1, inner.methodReads)
        assertEquals(listOf("ping", "set"), g.methods.names)
        val ping = g.methods.find("ping")!!
        assertTrue(!ping.mainThread)
        assertTrue(g.methods.find("set")!!.mainThread)
        val args = DeviceArguments(emptyList())
        assertEquals(listOf(DeviceValue.of(1L)), ping.handler.call(Ctx, args).toList())
        g.revoke()
        val e = assertThrows(DeviceException::class.java) { ping.handler.call(Ctx, args) }
        assertEquals(GuardedDevice.UNAVAILABLE, e.message)
    }

    @Test
    fun `種類やメソッドの表を読むときに投げたら、作る側に伝わる`() {
        val bad = object : Device {
            override val kind: String get() = throw IllegalStateException("kind boom")
            override val methods: DeviceMethods = DeviceMethods.empty()
        }
        assertThrows(IllegalStateException::class.java) { GuardedDevice(bad) }
    }

    @Test
    fun `つないだ包みで、直接・メインスレッド・後で返すの呼び出しが動き、revokeの後はfailedになる`() = timeout(Duration.ofSeconds(30)) {
        Harness().use { h ->
            val m = h.machine()
            var later: DeviceCompletion? = null
            val inner = object : Device {
                override val kind: String = "othermod:thing"
                override val methods: DeviceMethods = DeviceMethods.builder()
                    .direct("ping") { _, _ -> arrayOf(DeviceValue.of("pong")) }
                    .mainThread("heat") { ctx, _ -> arrayOf(DeviceValue.of(if (ctx.isMainThread) 1L else 0L)) }
                    .mainThread("slow") { ctx, _ ->
                        later = ctx.defer()
                        arrayOf()
                    }
                    .build()
            }
            val g = GuardedDevice(inner)
            val address = DeviceAddress.random()
            m.devices.attach(address, g)
            fun call(method: String) = m.devices.dispatcher.start(0, address, method, emptyList())
            fun DeviceDispatcher.Started.result(): CallResult = DeviceDispatcher.resultOf(future)
            assertEquals(CallResult.Values(listOf(DeviceValue.of("pong"))), call("ping").result())
            val heat = call("heat")
            h.scheduler.tick()
            assertEquals(CallResult.Values(listOf(DeviceValue.of(1L))), heat.result())
            val slow = call("slow")
            h.scheduler.tick()
            assertTrue(!slow.future.isDone, "後で返す")
            later!!.complete(DeviceValue.of(7L))
            assertEquals(CallResult.Values(listOf(DeviceValue.of(7L))), slow.result())
            g.revoke()
            assertEquals(CallResult.Error(CallError.Failed(GuardedDevice.UNAVAILABLE)), call("ping").result())
            m.devices.detach(address)
            assertEquals(CallResult.Error(CallError.Detached), call("ping").result())
        }
    }
}
