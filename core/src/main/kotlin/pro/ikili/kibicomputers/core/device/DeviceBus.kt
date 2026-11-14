package pro.ikili.kibicomputers.core.device

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.event.EventValue
import pro.ikili.kibicomputers.core.kernel.event.KernelEvent
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
 * - [attach]・[detach] で表を変える（どのスレッドからも呼べる。イベントを出すためにカーネルロックを短く取るので、
 *   カーネルロックを持って呼ばない）。変えるたびに [generation] が上がる（ADR 0044 の変更の知らせを置き換えた。ADR 0065）
 * - 成功した接続・切断ごとに、カーネルのイベント `device-attached`・`device-detached`（[DeviceEvents]）を1件出す（#67、ADR 0067）。
 *   表を入れ替えた後、書き込みのロックの中で出すので、同じ address の接続と切断は起きた順に並び、受け手が [list] を読むと新しい表が見える。
 *   ロックの順は「書き込みのロック → (デバイスのロック) → カーネルロック」
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

    /** 表を変えた回数（実行時だけ。イベントには載せない。ADR 0067） */
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
     * [Device.onAttached] が返った後、まだつないでいれば `device-attached` を1件出す（#67）。[Device.onAttached] の中で出たイベント
     * （画面の `tty-resize` など）はその前に並ぶ。[Device.onAttached] の中で自分を外したときは出さない。
     *
     * @return つないだら `true`。上限に達していて断ったら `false`（どちらもイベントは出さない）
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
                // つなげなかったので、表から戻す（接続を知らせていないので、切断も出さない）
                removeLocked(address)
                throw t
            }
            // onAttached の中で自分を外していなければ知らせる（書き込みのロックは再入できるので、外したなら表にない）
            if (table[address] === attached) {
                attached.announced = true
                postLocked(DeviceEvents.ATTACHED, attached)
            }
        }
        return true
    }

    /**
     * [address] のデバイスを外し、[Device.onDetached] を呼ぶ。この後、そのデバイスへの呼び出しは `detached` になる。
     * 接続を知らせたデバイスなら、`device-detached` を1件出す（#67。[Device.onDetached] が例外を投げても出し、例外はそのまま投げる）。
     *
     * @return 外したら `true`。つないでいなければ `false`（イベントは出さない）
     */
    public fun detach(address: String): Boolean = synchronized(writeLock) {
        val attached = removeLocked(address) ?: return false
        try {
            attached.device.onDetached(attached)
        } finally {
            // 外れたのは事実なので、onDetached が投げても出す（ここには post 以外を置かない。元の例外を隠さない）
            if (attached.announced) postLocked(DeviceEvents.DETACHED, attached)
        }
        true
    }

    /**
     * 接続・切断のイベント [type] を、[attached] の address とつないだときの種類で出す（[writeLock] の中で呼ぶ。ADR 0067）。
     * キューがない・止まった・閉じたマシンでは、[Machine.postEvent] が捨てるだけ
     */
    private fun postLocked(type: String, attached: AttachedDevice) {
        machine.postEvent(KernelEvent(type, DeviceEvents.SOURCE, listOf(EventValue.Text(attached.address), EventValue.Text(attached.kind))))
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
