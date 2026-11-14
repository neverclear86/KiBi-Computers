package pro.ikili.kibicomputers.neoforge.input

import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.core.device.KeyboardDevice
import pro.ikili.kibicomputers.core.device.wiring.DeviceHolder
import pro.ikili.kibicomputers.core.device.wiring.DeviceRegistry
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.input.KeyboardEventCodec
import pro.ikili.kibicomputers.core.input.KeyboardListener
import pro.ikili.kibicomputers.neoforge.computer.CaseWiring
import pro.ikili.kibicomputers.neoforge.computer.DeviceAddressSlot
import pro.ikili.kibicomputers.neoforge.computer.DeviceWiring
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.util.UUID

/**
 * キーボードブロックのブロックエンティティ（サーバー側で使う）。入力のセッション（[KeyboardSessions]）から届いた入力を、
 * つながった相手（[KeyboardListener]）に配る。
 *
 * コンピューターへの結線は、隣接デバイスの接続（#129、ADR 0129）で行う。サーバーで読み込まれたら、デバイスの登録表
 * （[DeviceWiring.registry]）に載り、壊れた・アンロードしたら外れる。ケースの隣か、ケースにつないだ画面の前などにあると
 * （[relayable]）、そのケースのマシンにつながり、相手はそのマシンのキーボードのデバイス（[KeyboardDevice]）になる。
 * つながるマシンは1台だけで、ほかのケースは、手放されるまでつながない（他人のキーボードの隣に置いたケースに、打ったものが
 * 漏れないように。#360）。マシンから外れるときは、開いている入力のセッションを閉じる（押したままのキーの離しは、外れる前の
 * マシンに届く。持ち主が替わった後に、打ったものが別のマシンに届かないように）。
 *
 * つながる相手は1つだけ（#360）。先に [claim] した相手が、[release] するまで持つ。2つ目からは断る。
 *
 * セーブデータに書くのは、キーボードのデバイスの address（[deviceAddress]。#65）だけ。
 */
class KeyboardBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(KiBiBlocks.KEYBOARD_BLOCK_ENTITY.get(), pos, state), DeviceHolder<GlobalPos> {

    /** キーボードのデバイス（`kibicomputers:keyboard`）の address（#65）。最初に使うときに作り、セーブデータの [DEVICE_ADDRESS_TAG] に書く */
    override val deviceAddress: String get() = addressSlot.get()

    private val addressSlot = DeviceAddressSlot(DEVICE_ADDRESS_TAG) { setChanged() }

    /** つながった相手。いなければ null */
    var owner: KeyboardListener? = null
        private set

    /**
     * 入力を受け取る相手を [listener] にする。サーバーのメインスレッドで呼ぶ。
     *
     * @return つないだ（すでに [listener] がつながっていたときも true）。ほかの相手がつながっていれば false
     */
    fun claim(listener: KeyboardListener): Boolean {
        val current = owner
        if (current != null) return current === listener
        owner = listener
        return true
    }

    /** [listener] がつながっていれば外す。 */
    fun release(listener: KeyboardListener) {
        if (owner === listener) owner = null
    }

    /** [typist] が打った [events] を、つながった相手に配る。相手がいなければ捨てる。 */
    fun deliver(typist: UUID, events: List<KeyboardEvent>) {
        val listener = owner ?: return
        for (event in events) listener.onKeyboardEvent(typist, event)
    }

    /**
     * [KeyboardEventCodec] の形の [data] を読んで、[typist] が打ったものとして配る。量の上限は見ない
     * （プレイヤーからの入力は [KeyboardSessions] を通す。これはテストやサーバー内から入力するとき用）。
     *
     * @return 配った入力の数。形が崩れていれば -1（何も配らない）
     */
    fun receive(typist: UUID, data: ByteArray): Int {
        val events = try {
            KeyboardEventCodec.decode(data)
        } catch (e: IllegalArgumentException) {
            return -1
        }
        deliver(typist, events)
        return events.size
    }

    // サーバー: デバイスの登録表の登録（#129）。載っていなければ null
    private var registration: DeviceRegistry.Entry<GlobalPos>? = null

    // マシンにつないでいる間、入力をキーボードのデバイスに渡す相手
    private class DeviceInput(val device: KeyboardDevice) : KeyboardListener {
        override fun onKeyboardEvent(typist: UUID, event: KeyboardEvent) {
            device.input(event)
        }
    }

    /** サーバー: このキーボードをつないでいるケースの結線。いなければ null */
    internal val wiredTo: CaseWiring? get() = registration?.owner?.host as? CaseWiring

    override val relayable: Boolean get() = true

    override fun reassignDeviceAddress() = addressSlot.reassign()

    override fun createDevice(): Device = KeyboardDevice()

    override fun onConnected(device: Device) {
        owner = DeviceInput(device as KeyboardDevice)
    }

    override fun onDisconnected(device: Device, removed: Boolean) {
        // 押したままのキーの離しを、外す前のマシンに届けてから外す
        KeyboardSessions.closeFor(this)
        if ((owner as? DeviceInput)?.device === device) owner = null
    }

    /**
     * サーバー: デバイスの登録表に載る（#129）。ブロックを置いたときは `setLevel` の時点（`onLoad` は NeoForge が tick の途中まで遅らせる）、
     * チャンクを読み込んだときは `onLoad` で載る。もう載っていれば何もしない。つなぐのは tick の終わり（置いたブロックのデータを読み込んだ後。#535）
     */
    private fun register() {
        val level = level as? ServerLevel ?: return
        if (registration != null || isRemoved) return
        registration = DeviceWiring.registry.register(DeviceWiring.keyOf(level, blockPos), this)
    }

    override fun setLevel(level: Level) {
        super.setLevel(level)
        register()
    }

    override fun onLoad() {
        super.onLoad()
        register()
    }

    private fun unregister() {
        val r = registration ?: return
        registration = null
        DeviceWiring.registry.unregister(r)
    }

    override fun onChunkUnloaded() {
        super.onChunkUnloaded()
        unregister()
    }

    override fun setRemoved() {
        super.setRemoved()
        unregister()
        owner = null
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        addressSlot.save(tag)
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        addressSlot.load(tag)
    }

    companion object {
        /** セーブデータの、キーボードのデバイスの address のキー（#65） */
        const val DEVICE_ADDRESS_TAG: String = "DeviceAddress"
    }
}
