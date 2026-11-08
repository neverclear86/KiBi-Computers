package pro.ikili.kibicomputers.core.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import java.time.Duration
import kotlin.concurrent.withLock
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 待たずに積んだ呼び出し（#65）の保存の写しと、戻したときの積み直し */
class PendingDeviceCallTest {
    private val limit = Duration.ofSeconds(30)
    private val address = "00000000-0000-4000-8000-0000000000e1"
    private val args = listOf(DeviceValue.of(1L), DeviceValue.of("a"))

    @Test
    fun `写しは、未実行なら名前と引数、終わったら結果、実行中ならinterrupted`() {
        val call = PendingDeviceCall(PendingDeviceCall.State.Queued(address, "m", args))
        assertEquals(PendingDeviceCall.Snapshot.Queued(address, "m", args), call.snapshot())
        assertFalse(call.isReady(WaitCondition.READABLE))
        assertTrue(call.canWait(WaitCondition.READABLE))
        assertFalse(call.canWait(WaitCondition.WRITABLE))
        call.markRunning()
        assertEquals(PendingDeviceCall.Snapshot.Done(CallResult.Error(CallError.INTERRUPTED)), call.snapshot())
        assertEquals(null, call.result)
        val done = CallResult.Values(listOf(DeviceValue.of(2L)))
        assertTrue(call.finish(done))
        assertFalse(call.finish(CallResult.Error(CallError.Detached)), "2回目は変えない")
        call.markRunning() // 終わった後は戻らない
        assertTrue(call.isReady(WaitCondition.READABLE))
        assertEquals(PendingDeviceCall.Snapshot.Done(done), call.snapshot())
        assertEquals(done, call.result)
        // 写しから戻すと同じ状態になる
        assertEquals(done, PendingDeviceCall.restore(call.snapshot()).result)
        assertEquals(DeviceHandle.Snapshot(address, "test:x"), DeviceHandle.restore(DeviceHandle.Snapshot(address, "test:x")).snapshot())
    }

    @Test
    fun `未実行の写しを戻すと、積み直して実行し、終わると読めるようになる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val seen = ArrayList<List<DeviceValue>>()
            m.devices.attach(
                address,
                object : Device {
                    override val kind = "test:x"
                    override val methods = DeviceMethods.builder().mainThread("m") { _, a -> seen += List(a.size()) { a.get(it) }; arrayOf(DeviceValue.of(9L)) }.build()
                },
            )
            val restored = PendingDeviceCall.restore(PendingDeviceCall.Snapshot.Queued(address, "m", args))
            m.lock.withLock { m.kernel.objects.add(restored) }
            assertEquals(DeviceLimits.CALL_FUEL + 1, m.dispatchPendingDeviceCall(0, restored))
            assertFalse(m.lock.withLock { restored.isReady(WaitCondition.READABLE) })
            h.scheduler.tick()
            assertEquals(listOf(args), seen)
            assertEquals(CallResult.Values(listOf(DeviceValue.of(9L))), m.lock.withLock { restored.result })
            // もう実行に回したものは、もう一度回さない
            assertEquals(0, m.dispatchPendingDeviceCall(0, restored))
        }
    }
}
