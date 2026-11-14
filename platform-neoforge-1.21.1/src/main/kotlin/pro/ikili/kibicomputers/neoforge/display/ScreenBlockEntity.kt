package pro.ikili.kibicomputers.neoforge.display

import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.core.device.ScreenDevice
import pro.ikili.kibicomputers.core.device.wiring.DeviceHolder
import pro.ikili.kibicomputers.core.device.wiring.DeviceRegistry
import pro.ikili.kibicomputers.core.display.DisplayDevice
import pro.ikili.kibicomputers.core.display.PixelBuffer
import pro.ikili.kibicomputers.core.display.ScreenSyncReceiver
import pro.ikili.kibicomputers.core.display.ScreenSyncSender
import pro.ikili.kibicomputers.core.display.ScreenSyncTarget
import pro.ikili.kibicomputers.core.display.ScreenTier
import pro.ikili.kibicomputers.core.display.ScreenWatchers
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.neoforge.computer.CaseWiring
import pro.ikili.kibicomputers.neoforge.computer.DeviceAddressSlot
import pro.ikili.kibicomputers.neoforge.computer.DeviceWiring
import pro.ikili.kibicomputers.neoforge.input.KeyboardInput
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks

/**
 * 画面ブロックのブロックエンティティ。表示デバイス（[DisplayDevice]。`core`）を「映す」（#355）。
 *
 * - サーバー: 映す表示デバイスは、マシンの側（ケースの結線 [CaseWiring]。#129）が持ち、つないだときに [mirror] で入れる。入れるまでは、ティアの最大の大きさの
 *   空の表示デバイスを映す。中身はプロセスのスレッドが書くので、tick の終わりに表示デバイスのロックを持って、同期の差分を取り出して
 *   見ているクライアントに送る（#140、[ScreenSync]）
 * - クライアント: 同期で受け取った内容を、自分の表示デバイスに再生して描く
 *
 * 画面の中身はコンピューターが描き直すものとして、今はセーブデータに書かない（保存は表示デバイスの側で #81 が扱う）。
 * セーブデータに書くのは、画面のデバイスの address（[deviceAddress]。#65）だけ。
 *
 * サーバーで読み込まれたら、デバイスの登録表（[DeviceWiring.registry]。#129）に載り、壊れた・アンロードしたら外れる
 * （つないでいたマシンからは、すぐに外れる。取っておいた出力は捨てる。#397・#381）。画面の前・下・横・後ろのキーボードは、
 * この画面をつないだマシンが続けてつなぐ（[relayKeys]）。
 */
class ScreenBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(KiBiBlocks.SCREEN_BLOCK_ENTITY.get(), pos, state), DeviceHolder<GlobalPos> {

    /** この画面のティア。ブロックから決まる。 */
    val tier: ScreenTier = (state.block as ScreenBlock).tier

    /**
     * 映している表示デバイス。サーバーでは [mirror] で替わる。中身を読み書きするときは、そのロックを持つ
     * （メインスレッドからだけ使う、マシンにつないでいない表示デバイスなら、持たなくてよい）
     */
    var display: DisplayDevice = DisplayDevice.forTier(tier)
        private set

    /** 文字画面のバッファ（[display] のもの）。 */
    val text: TextBuffer
        get() = display.text

    /** ピクセル画面のバッファ（[display] のもの）。ピクセル描画できないティアでは null。大きいので最初に使うときに作る。 */
    val pixels: PixelBuffer?
        get() = display.pixels()

    /** ピクセル画面のバッファをもう作ったか。まだなら描画（#139）はピクセルの層を描かない。 */
    val hasPixels: Boolean
        get() = display.hasPixels

    /**
     * クライアントの描画が持つ資源（ピクセル画面のテクスチャなど）。サーバーでは常に null。
     * ブロックエンティティが消えるときに閉じる。
     */
    var renderCache: AutoCloseable? = null

    /** 画面のデバイス（`kibicomputers:screen`）の address（#65）。最初に使うときに作り、セーブデータの [DEVICE_ADDRESS_TAG] に書く */
    override val deviceAddress: String get() = addressSlot.get()

    private val addressSlot = DeviceAddressSlot(DEVICE_ADDRESS_TAG) { setChanged() }

    // サーバー: 同期の送り手（映す表示デバイスごとに作り直す）と、見ているプレイヤー（誰が見ているかは距離からサーバーが決める。#359）
    private var sender = ScreenSyncSender(display.text)
    private val watchers = ScreenWatchers<ServerPlayer>()
    private val host by lazy { ScreenSync.Host(this) }

    // サーバー: 表示デバイスのロックが取れずに、同期を飛ばした tick の数
    private var busyTicks = 0

    // クライアント: 同期の受け手
    private val receiver by lazy { ScreenSyncReceiver() }
    private val target = object : ScreenSyncTarget {
        override val text: TextBuffer
            get() = this@ScreenBlockEntity.text

        override fun pixels(): PixelBuffer? = display.pixels()

        override fun pixelsIfCreated(): PixelBuffer? = display.pixelsIfCreated()
    }

    /**
     * サーバー: [device] を映す（#355）。見ている人には、次の tick の終わりに画面全体を送り直す。
     * 色数と大きさは、この画面のティアに合ったものを渡す。メインスレッドから呼ぶ
     */
    fun mirror(device: DisplayDevice) {
        if (device === display) return
        // 前の表示デバイスに付けた記録を外す（見ている人がいないとき（flush で）と同じ）
        display.lock.lock()
        try {
            sender.flush(hasWatchers = false, hasNewWatchers = false)
        } finally {
            display.lock.unlock()
        }
        display = device
        sender = ScreenSyncSender(device.text)
    }

    // サーバー: デバイスの登録表の登録（#129）。載っていなければ null
    private var registration: DeviceRegistry.Entry<GlobalPos>? = null

    /** サーバー: この画面をつないでいるケースの結線。いなければ null */
    internal val wiredTo: CaseWiring? get() = registration?.owner?.host as? CaseWiring

    /** サーバー: デバイスの登録表に載っているときの世代（テスト用）。載っていなければ null */
    val registrationGeneration: Long? get() = registration?.generation

    override fun reassignDeviceAddress() = addressSlot.reassign()

    // 表示デバイスはマシンの側が持ち、つなぐたびに新しく作る（#355）
    override fun createDevice(): Device = ScreenDevice(DisplayDevice.forTier(tier))

    override fun onConnected(device: Device) {
        mirror((device as ScreenDevice).display)
    }

    // 画面は最後の表示デバイスを映したままにし、中身を残す。外れた（壊れた・アンロードした）なら、書ききれずに取っておいた出力を捨てる（#381・#397）
    override fun onDisconnected(device: Device, removed: Boolean) {
        if (removed) (device as ScreenDevice).discardOutput()
    }

    override fun relayKeys(): List<GlobalPos> {
        val level = level ?: return emptyList()
        return KeyboardInput.keyboardSpots(blockPos, blockState.getValue(ScreenBlock.FACING)).map { DeviceWiring.keyOf(level, it) }
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

    private fun unregister() {
        val r = registration ?: return
        registration = null
        DeviceWiring.registry.unregister(r)
    }

    /** サーバーで、この画面を見ているプレイヤーの数（新しく見に来て、まだ送っていない人も含む）。 */
    val watcherCount: Int
        get() = watchers.count

    /** サーバーで、[player] がこの画面を見ているか。 */
    fun isWatchedBy(player: ServerPlayer): Boolean = watchers.isWatching(player)

    /** サーバーで、[player] への同期が送信量の予算で止められていて、画面全体を送れるのを待っているか（#352）。 */
    fun isThrottled(player: ServerPlayer): Boolean = watchers.isThrottled(player)

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        addressSlot.save(tag)
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        addressSlot.load(tag)
    }

    override fun onLoad() {
        super.onLoad()
        val level = level ?: return
        if (level.isClientSide) return
        ScreenSync.serverScreens.add(this)
        register()
    }

    override fun onChunkUnloaded() {
        super.onChunkUnloaded()
        unregister()
    }

    override fun setRemoved() {
        super.setRemoved()
        ScreenSync.serverScreens.remove(this)
        watchers.clear()
        renderCache?.close()
        renderCache = null
        // isRemoved が真になってから外す（つないでいたマシンからすぐに外れる。#397）
        unregister()
    }

    /**
     * サーバー: [player] からの「画面全体を送り直してほしい」というお願い。見ている人のものだけ受け付け、
     * 送り直しはプレイヤーと画面の組ごとに一定の間隔に1回までにする（[ScreenWatchers.requestResync]）。
     */
    fun requestResync(player: ServerPlayer) {
        val level = level ?: return
        watchers.requestResync(player, level.gameTime)
    }

    /** サーバー: [player] を見ている人から外す（ログアウト、リスポーン、ディメンションの移動のとき）。 */
    fun forget(player: ServerPlayer) {
        watchers.remove(player)
    }

    /** サーバー: tick の終わりに呼ぶ。見ている人を距離から決め直し、その tick の分を送る。 */
    fun syncTick() {
        val level = level as? ServerLevel ?: return
        // 誰も見ておらず、見はじめる人を探す tick でもなければ、表示デバイスのロックを取らない。
        // 出力し続けている画面のロックを、メインスレッドが毎 tick 奪い合わないようにする（#351）
        if (!watchers.needsTick(level.gameTime, sender)) {
            busyTicks = 0
            return
        }
        // プロセスのスレッドが書いている最中の表示デバイスから、差分を取り出す（#355）
        val d = display
        if (!d.lock.tryLock()) {
            // プロセスのスレッドが書いている最中なら、この tick は待たずに飛ばす（続けて飛ばしたら、待って取る）
            if (++busyTicks < MAX_BUSY_TICKS) return
            d.lock.lock()
        }
        busyTicks = 0
        try {
            sender.pixels = d.pixelsIfCreated()
            watchers.tick(level.gameTime, sender, host, ScreenSync.traffic)
        } finally {
            d.lock.unlock()
        }
    }

    /**
     * クライアント: 同期のフラグメントを受け取って画面バッファに再生する。
     *
     * @return 正しく受け取れたか。false なら画面が途中まで書き換わっているので、キーフレームを送り直してもらう
     */
    fun receiveSync(fragment: ByteArray): Boolean = try {
        receiver.receive(fragment, target)
        true
    } catch (e: IllegalArgumentException) {
        false
    }

    companion object {
        /** セーブデータの、画面のデバイスの address のキー（#65） */
        const val DEVICE_ADDRESS_TAG: String = "DeviceAddress"

        /** 表示デバイスのロックが取れないときに、同期を飛ばしてよい tick の数（これを超えたら待って取る） */
        private const val MAX_BUSY_TICKS = 4
    }
}
