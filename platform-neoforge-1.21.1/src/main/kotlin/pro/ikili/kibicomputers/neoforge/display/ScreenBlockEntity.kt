package pro.ikili.kibicomputers.neoforge.display

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import pro.ikili.kibicomputers.core.display.PixelBuffer
import pro.ikili.kibicomputers.core.display.ScreenSyncReceiver
import pro.ikili.kibicomputers.core.display.ScreenSyncSender
import pro.ikili.kibicomputers.core.display.ScreenSyncTarget
import pro.ikili.kibicomputers.core.display.ScreenTier
import pro.ikili.kibicomputers.core.display.ScreenWatchers
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks

/**
 * 画面ブロックのブロックエンティティ。ティアの最大の大きさの画面バッファ（`core`）を持つ。
 *
 * サーバーの画面バッファに描いた内容は、見ているクライアントに同期する（#140、[ScreenSync]）。
 * 画面の中身はコンピューターが描き直すものとして、今はセーブデータに書かない
 * （保存するかどうかと形式は、コンピューターとつなぐときに決める）。
 */
class ScreenBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(KiBiBlocks.SCREEN_BLOCK_ENTITY.get(), pos, state) {

    /** この画面のティア。ブロックから決まる。 */
    val tier: ScreenTier = (state.block as ScreenBlock).tier

    /** 文字画面のバッファ。 */
    val text: TextBuffer = tier.createTextBuffer()

    private val lazyPixels = lazy {
        val p = if (tier.supportsPixels) tier.createPixelBuffer() else null
        // 作った tick の描画から記録する
        sender.pixels = p
        p
    }

    /** ピクセル画面のバッファ。ピクセル描画できないティアでは null。大きいので最初に使うときに作る。 */
    val pixels: PixelBuffer? by lazyPixels

    /** ピクセル画面のバッファをもう作ったか。まだなら描画（#139）はピクセルの層を描かない。 */
    val hasPixels: Boolean
        get() = lazyPixels.isInitialized() && lazyPixels.value != null

    /**
     * クライアントの描画が持つ資源（ピクセル画面のテクスチャなど）。サーバーでは常に null。
     * ブロックエンティティが消えるときに閉じる。
     */
    var renderCache: AutoCloseable? = null

    // サーバー: 同期の送り手と、見ているプレイヤー（誰が見ているかは距離からサーバーが決める。#359）
    private val sender = ScreenSyncSender(text)
    private val watchers = ScreenWatchers<ServerPlayer>()
    private val host by lazy { ScreenSync.Host(this) }

    // クライアント: 同期の受け手
    private val receiver by lazy { ScreenSyncReceiver() }
    private val target = object : ScreenSyncTarget {
        override val text: TextBuffer
            get() = this@ScreenBlockEntity.text

        override fun pixels(): PixelBuffer? = this@ScreenBlockEntity.pixels

        override fun pixelsIfCreated(): PixelBuffer? = if (hasPixels) this@ScreenBlockEntity.pixels else null
    }

    // サーバー: 画面が外れた（ブロックエンティティが消えた）ときに知らせる先（#397）。たいていは0〜1個なので、要るまで作らない
    private var removalListeners: ArrayList<Runnable>? = null

    /**
     * サーバー: 画面が外れた（壊された・チャンクが読み込まれなくなったなどで、ブロックエンティティが消えた）ときに [listener] を呼ぶ（#397）。
     * 呼ぶのは1回だけで、呼んだ後は登録を外す。メインスレッドから使う
     */
    internal fun addRemovalListener(listener: Runnable) {
        (removalListeners ?: ArrayList<Runnable>(1).also { removalListeners = it }).add(listener)
    }

    /** サーバー: [addRemovalListener] で登録した [listener] を外す。登録していなければ何もしない */
    internal fun removeRemovalListener(listener: Runnable) {
        val ls = removalListeners ?: return
        ls.remove(listener)
        if (ls.isEmpty()) removalListeners = null
    }

    /** サーバーで、この画面を見ているプレイヤーの数（新しく見に来て、まだ送っていない人も含む）。 */
    val watcherCount: Int
        get() = watchers.count

    /** サーバーで、[player] がこの画面を見ているか。 */
    fun isWatchedBy(player: ServerPlayer): Boolean = watchers.isWatching(player)

    override fun onLoad() {
        super.onLoad()
        val level = level ?: return
        if (!level.isClientSide) ScreenSync.serverScreens.add(this)
    }

    override fun setRemoved() {
        super.setRemoved()
        ScreenSync.serverScreens.remove(this)
        watchers.clear()
        renderCache?.close()
        renderCache = null
        // isRemoved が真になってから知らせる。知らせた先が登録を外しても困らないように、先に手放す
        val listeners = removalListeners ?: return
        removalListeners = null
        for (l in listeners) l.run()
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
        watchers.tick(level.gameTime, sender, host, ScreenSync.traffic)
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
}
