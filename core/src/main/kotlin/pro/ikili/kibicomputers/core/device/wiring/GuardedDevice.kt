package pro.ikili.kibicomputers.core.device.wiring

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceContext
import pro.ikili.kibicomputers.api.device.DeviceMethods

/**
 * 他 MOD のデバイス [inner] の包み（#153、ADR 0153）。つなぐたびに1つ作る（[ExternalDeviceHolder.createDevice]）。
 *
 * - [inner] の `kind`・`methods` は、作るときに1回だけ読む（ここで投げたら、作る側が受ける）
 * - [revoke] の後（能力が消えた・別の実体に替わった後、マシンから外れるまでの間）の呼び出しは、`failed("device unavailable")`
 *   になる。外れた後の呼び出しは、今までどおり `DeviceBus` が `detached` にする
 * - [onAttached]・[onDetached] は [inner] にそのまま渡す（投げたものは、つなぐ・外す側が受ける）
 *
 * スレッド: 作る・[revoke] はメインスレッド。メソッドの呼び出しは、メソッドの種類のスレッドで（[revoke] の印は volatile で読む）。
 */
@ApiStatus.Internal
public class GuardedDevice(
    /** 他 MOD のデバイス（能力が返した生のもの） */
    public val inner: Device,
) : Device {
    @Volatile
    private var revoked = false

    override val kind: String = inner.kind

    override val methods: DeviceMethods = inner.methods.guarded(UNAVAILABLE) { revoked }

    /** 呼び出しを止めたか */
    public val isRevoked: Boolean get() = revoked

    /** これからの呼び出しを `failed` にする（もう一度呼んでもよい） */
    public fun revoke() {
        revoked = true
    }

    override fun onAttached(context: DeviceContext) {
        inner.onAttached(context)
    }

    override fun onDetached(context: DeviceContext) {
        inner.onDetached(context)
    }

    override fun toString(): String = "GuardedDevice(${inner.javaClass.name}, revoked=$revoked)"

    public companion object {
        /** [revoke] の後の呼び出しの `failed` の説明 */
        public const val UNAVAILABLE: String = "device unavailable"
    }
}
