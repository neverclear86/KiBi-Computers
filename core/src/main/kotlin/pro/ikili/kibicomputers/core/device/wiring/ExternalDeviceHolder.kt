package pro.ikili.kibicomputers.core.device.wiring

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.api.device.Device

/**
 * 他 MOD のブロックが能力で出したデバイス（[device]）を、位置 [key] の持ち主として登録表に載せるアダプター（#153、ADR 0153）。
 * 位置ごとに1つ作る（[ExternalDeviceSlots] が出し入れする）。
 *
 * - つないでよいのは、その位置を [ExternalDeviceSlots.offer] した結線だけ（[isOfferedTo]）。位置ごとに1つのアダプターを、
 *   面ごとの出し分けとは関係なく、隣のほかのケースがつなぐのを防ぐ
 * - 排他の単位は実体（[exclusionKey] は [device]）。同じ実体を複数の位置で出しても、つながるマシンは1台だけ
 * - address は [addresses] が持つ（接続層: ブロックエンティティがあればそのデータ添付、なければ座標から決めたもの）。
 *   読み書きは他 MOD のブロックエンティティに触るので、投げうる（[MachineWiring] が受ける）
 * - つなぐたびに [GuardedDevice] で包む。[retire]（能力が消えた・替わった）の後の呼び出しは `failed` になる
 * - [toString] は位置と実体のクラス名だけで作る（他 MOD のコードを呼ばない。ログに使う）
 *
 * スレッド: メインスレッドから使う。
 */
@ApiStatus.Internal
public class ExternalDeviceHolder<K : Any>(
    /** 位置のキー */
    public val key: K,
    /** 能力が返したデバイス（生のもの） */
    public val device: Device,
    private val addresses: AddressStore,
) : DeviceHolder<K> {
    /** address の置き場所（接続層が実装する） */
    public interface AddressStore {
        /** 今の address（なければ作って書く）。投げうる */
        public fun read(): String

        /** 新しい address を振り直して書く。振り直せない（保存先がない）なら投げる */
        public fun reassign()
    }

    private var current: GuardedDevice? = null

    /** 退いた（能力が消えた・替わった）か */
    public var retired: Boolean = false
        private set

    override val exclusionKey: Any get() = device

    /**
     * この位置を [ExternalDeviceSlots.offer] した結線（[MachineWiring.host]）。[ExternalDeviceSlots] が、位置の見張りの表をそのまま
     * 渡す（同じ表を共有するので、出し入れが自動で映る）。`null`（スロットを通さずに作った）なら、制限しない
     */
    internal var offeredTo: List<Any>? = null

    override fun isOfferedTo(host: MachineWiring.Host<K>): Boolean = offeredTo?.any { it === host } ?: true

    override val deviceAddress: String get() = addresses.read()

    override fun reassignDeviceAddress(): Unit = addresses.reassign()

    override fun createDevice(): Device = GuardedDevice(device).also {
        if (retired) it.revoke()
        current = it
    }

    override fun onDisconnected(device: Device, removed: Boolean) {
        (device as? GuardedDevice)?.revoke()
        if (current === device) current = null
    }

    /** 能力が消えた・別の実体に替わった。つないでいるデバイスへのこれからの呼び出しを `failed` にする */
    public fun retire() {
        retired = true
        current?.revoke()
    }

    override fun toString(): String = "external device at $key (${device.javaClass.name})"
}
