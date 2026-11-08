package pro.ikili.kibicomputers.core.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.kernel.Harness
import java.time.Duration
import java.util.Collections
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** スピーカーのデバイス（#65、ADR 0065）: 丸め、配っていない分の上限、tick の中で鳴らす */
class SpeakerDeviceTest {
    private val limit = Duration.ofSeconds(30)

    @Test
    fun `beepは値を丸めて、次のtickの中でメインスレッドから鳴らす`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val log = Collections.synchronizedList(ArrayList<String>())
            val main = Thread.currentThread()
            val speaker = SpeakerDevice { f, d -> log += "${Thread.currentThread() === main}:$f/$d" }
            assertFalse(speaker.beep(440, 100), "つないでいなければ鳴らさない")
            m.devices.attach(SPEAKER_ADDRESS, speaker)
            assertEquals(DeviceKind.SPEAKER, speaker.kind)
            val r = m.devices.dispatcher.start(0, SPEAKER_ADDRESS, "beep", listOf(DeviceValue.of(440L), DeviceValue.of(100L)))
            assertEquals(CallResult.Values(listOf(DeviceValue.of(true))), DeviceDispatcher.resultOf(r.future))
            assertTrue(speaker.beep(1, 1_000_000))
            assertTrue(speaker.beep(Int.MAX_VALUE, 0))
            val big = m.devices.dispatcher.start(0, SPEAKER_ADDRESS, "beep", listOf(DeviceValue.of(Long.MAX_VALUE), DeviceValue.of(Long.MIN_VALUE)))
            assertEquals(CallResult.Values(listOf(DeviceValue.of(true))), DeviceDispatcher.resultOf(big.future))
            assertTrue(log.isEmpty(), "tick まで鳴らさない")
            h.scheduler.tick()
            assertEquals(
                listOf(
                    "true:440/100",
                    "true:${SpeakerDevice.MIN_FREQUENCY_HZ}/${SpeakerDevice.MAX_DURATION_MILLIS}",
                    "true:${SpeakerDevice.MAX_FREQUENCY_HZ}/1",
                    "true:${SpeakerDevice.MAX_FREQUENCY_HZ}/1",
                ),
                log.toList(),
            )
            m.devices.detach(SPEAKER_ADDRESS)
            assertFalse(speaker.beep(440, 1))
        }
    }

    @Test
    fun `鳴らしていないビープが上限あるなら捨てて、鳴らした後はまた積める`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            var played = 0
            val speaker = SpeakerDevice { _, _ -> played++ }
            m.devices.attach(SPEAKER_ADDRESS, speaker)
            repeat(SpeakerDevice.MAX_PENDING) { assertTrue(speaker.beep(440, 10)) }
            assertFalse(speaker.beep(440, 10))
            assertEquals(1, speaker.droppedBeeps.get())
            h.scheduler.tick()
            assertEquals(SpeakerDevice.MAX_PENDING, played)
            assertTrue(speaker.beep(440, 10))
        }
    }

    @Test
    fun `受け手が投げても、後の通知は配られる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val log = Collections.synchronizedList(ArrayList<String>())
            m.devices.attach(SPEAKER_ADDRESS, SpeakerDevice { _, _ -> throw IllegalStateException("接続層のバグ") })
            val ok = SpeakerDevice { f, _ -> log += "ok:$f" }
            m.devices.attach("00000000-0000-4000-8000-000000000004", ok)
            (m.devices.deviceAt(SPEAKER_ADDRESS) as SpeakerDevice).beep(100, 1)
            ok.beep(200, 1)
            h.scheduler.tick()
            assertEquals(listOf("ok:200"), log.toList())
        }
    }
}
