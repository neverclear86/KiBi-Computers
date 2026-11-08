package pro.ikili.kibicomputers.core.device

import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceContext
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.event.EventValue
import pro.ikili.kibicomputers.core.kernel.event.KernelEvent

/**
 * [DeviceBus] につないだデバイス1つ（#65）。つないだときに読んだ種類とメソッドの表を持ち、[DeviceContext] としてデバイスに渡す。
 * 変わらない値と、外れたかの印だけを持つ（どのスレッドから読んでもよい）。
 */
internal class AttachedDevice(
    val bus: DeviceBus,
    override val address: String,
    val kind: String,
    val device: Device,
    val methods: DeviceMethods,
) : DeviceContext {
    /** つないだマシン */
    val machine: Machine get() = bus.machine

    /** まだつないでいる（外したら `false`。戻らない） */
    @Volatile
    var attached: Boolean = true

    override fun postEvent(kind: String, vararg values: DeviceValue): Boolean {
        if (!attached) return false
        if (DeviceLimits.utf8Length(kind) > DeviceLimits.MAX_EVENT_BYTES) return false
        val event = try {
            KernelEvent(kind, address, values.mapNotNull { toEventValue(it) })
        } catch (e: IllegalArgumentException) {
            // 名前が空・値が多すぎる・大きすぎる（EventLimits）
            return false
        }
        bus.machine.postEvent(event)
        return true
    }

    override fun toString(): String = "AttachedDevice($address, $kind)"

    companion object {
        /** [value] をイベントの値にする（同じ種類のまま写す）。`none` はイベントに載らないので `null` */
        fun toEventValue(value: DeviceValue): EventValue? = when (value.type) {
            DeviceValue.Type.NONE -> null
            DeviceValue.Type.INTEGER -> EventValue.Integer(value.asLong())
            DeviceValue.Type.FLOAT -> EventValue.Float(value.asDouble())
            DeviceValue.Type.BOOLEAN -> EventValue.Boolean(value.asBoolean())
            DeviceValue.Type.TEXT -> EventValue.Text(value.asText())
            DeviceValue.Type.BYTES -> EventValue.Bytes(value.bytesUnsafe())
        }
    }
}
