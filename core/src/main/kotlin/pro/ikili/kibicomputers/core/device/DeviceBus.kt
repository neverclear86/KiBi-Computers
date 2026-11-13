package pro.ikili.kibicomputers.core.device

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.core.kernel.Machine
import java.util.concurrent.atomic.AtomicLong

/** つないでいるデバイス1つの情報（WIT の `device-info`）。 */
@ApiStatus.Internal
public data class DeviceInfo(
    /** address（小文字の UUID） */
    public val address: String,
    /** 種類（`<名前空間>:<名前>`） */
    public val kind: String,
)

/**
 * マシン1台につないでいるデバイスの表（#65、ADR 0065）。`Machine.devices` で使う。address → つないだデバイス。
 *
 * - [attach]・[detach] で表を変える（どのスレッドからも呼べる。待たない）。変えるたびに [generation] が上がる
 *   （ADR 0044 の変更の知らせを置き換えた。ADR 0065）
 * - 表は変えるときだけ作り直す変わらない表（copy-on-write）で、呼び出し（プロセスのスレッド）はロックなしで読む。
 *   デバイスは tick を持たないので、tick ごとのコストは台数とデバイス数に比例しない
 * - 画面（[ScreenDevice]）を最初につないだものが [primaryScreen]。カーネルのコンソールの出力はそこへ行く
 * - つなげる数は [limit]（`MachineConfig.maxDevices`。CPU で決まる。#66、ADR 0066）まで。満杯なら [attach] は断る（`false`）。
 *   外して空いても自分ではつなぎ直さない（つなぎ直すのは接続層。#129）
 *
 * 組み込みの [ScreenDevice]・[KeyboardDevice] はマシンのコンソールにつながるので、メインスレッドからつなぐ・外す。
 */
@ApiStatus.Internal
public class DeviceBus internal constructor(internal val machine: Machine) {
    private val writeLock = Any()

    @Volatile
    private var table: Map<String, AttachedDevice> = emptyMap()

    /** [table] の一覧（[list] が返す。表を作り直すときに一緒に作る） */
    @Volatile
    private var listing: List<DeviceInfo> = emptyList()

    @Volatile
    private var primaryScreenValue: ScreenDevice? = null

    private val generationCounter = AtomicLong()

    /** 呼び出しの振り分け */
    internal val dispatcher: DeviceDispatcher = DeviceDispatcher(this)

    /** 表を変えた回数（実行時だけ。#67 が接続・切断のイベントにする） */
    public val generation: Long get() = generationCounter.get()

    /** 最初につないだ画面。なければ `null`（カーネルのコンソールの出力先。なければ `MachineHost.onConsoleOutput`） */
    public val primaryScreen: ScreenDevice? get() = primaryScreenValue

    /** つなげるデバイスの数の上限（`MachineConfig.maxDevices`。動いている間は変わらない。ロックなしで読める） */
    public val limit: Int get() = machine.config.maxDevices

    /** 今つないでいるデバイスの数（ロックなしで読める） */
    public val size: Int get() = table.size

    /**
     * [device] を [address] でつなぐ。種類とメソッドの表をここで1回だけ読み、[Device.onAttached] を呼ぶ。
     * もう [limit] 個つないでいたら、何もせずに `false` を返す（表も [generation] も変えない。#66）。
     *
     * @return つないだら `true`。上限に達していて断ったら `false`
     * @throws IllegalArgumentException address の形が違う・もうその address でつないでいる・種類の形が違う
     */
    public fun attach(address: String, device: Device): Boolean {
        DeviceAddress.parse(address)
        val kind = device.kind
        require(DeviceKind.isValid(kind)) { "デバイスの種類は <名前空間>:<名前>（${DeviceKind.MAX_BYTES} バイトまで）: $kind" }
        val methods = device.methods
        synchronized(writeLock) {
            require(address !in table) { "address $address のデバイスはもうつないである" }
            // 数えてから入れるまでを同じロックの中で行う（並んだ attach でも上限を越えない）
            if (table.size >= limit) return false
            val attached = AttachedDevice(this, address, kind, device, methods)
            setTableLocked(LinkedHashMap(table).also { it[address] = attached })
            try {
                device.onAttached(attached)
            } catch (t: Throwable) {
                // つなげなかったので、表から戻す
                removeLocked(address)
                throw t
            }
        }
        return true
    }

    /**
     * [address] のデバイスを外し、[Device.onDetached] を呼ぶ。この後、そのデバイスへの呼び出しは `detached` になる。
     *
     * @return 外したら `true`。つないでいなければ `false`
     */
    public fun detach(address: String): Boolean = synchronized(writeLock) {
        val attached = removeLocked(address) ?: return false
        attached.device.onDetached(attached)
        true
    }

    /**
     * つないでいるデバイスの一覧（つないだ順。待たない。割り当てない）。表を変えるまで同じ変わらないリストを返す。
     * 並びは復元やつなぎ直しで変わりうる（ゲストは並びに頼れない）
     */
    public fun list(): List<DeviceInfo> = listing

    /** [address] でつないでいるデバイス。なければ `null`（ロックなしで読む） */
    internal fun find(address: String): AttachedDevice? = table[address]

    /** [address] でつないでいるデバイス。なければ `null` */
    public fun deviceAt(address: String): Device? = table[address]?.device

    private fun removeLocked(address: String): AttachedDevice? {
        val attached = table[address] ?: return null
        attached.attached = false
        setTableLocked(LinkedHashMap(table).also { it.remove(address) })
        return attached
    }

    /** 表を [next] にし、一覧・最初の画面・[generation] を合わせる（[writeLock] の中で呼ぶ） */
    private fun setTableLocked(next: Map<String, AttachedDevice>) {
        table = next
        listing = java.util.Collections.unmodifiableList(next.values.map { it.info })
        recomputePrimaryScreen()
        generationCounter.incrementAndGet()
    }

    private fun recomputePrimaryScreen() {
        primaryScreenValue = table.values.firstNotNullOfOrNull { it.device as? ScreenDevice }
    }

    override fun toString(): String = "DeviceBus(${table.keys})"
}
