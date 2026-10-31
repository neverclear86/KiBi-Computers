package pro.ikili.kibicomputers.neoforge.display

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import pro.ikili.kibicomputers.core.display.PixelBuffer
import pro.ikili.kibicomputers.core.display.ScreenSyncReceiver
import pro.ikili.kibicomputers.core.display.ScreenSyncSender
import pro.ikili.kibicomputers.core.display.ScreenSyncTarget
import pro.ikili.kibicomputers.core.display.ScreenTier
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
        sender?.pixels = p
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

    // サーバー: 同期の送り手と、見ているプレイヤー
    private var sender: ScreenSyncSender? = null
    private val watchers = LinkedHashSet<ServerPlayer>()
    private val newWatchers = LinkedHashSet<ServerPlayer>()

    // クライアント: 同期の受け手
    private val receiver by lazy { ScreenSyncReceiver() }
    private val target = object : ScreenSyncTarget {
        override val text: TextBuffer
            get() = this@ScreenBlockEntity.text

        override fun pixels(): PixelBuffer? = this@ScreenBlockEntity.pixels

        override fun pixelsIfCreated(): PixelBuffer? = if (hasPixels) this@ScreenBlockEntity.pixels else null
    }

    /** クライアントの、見ている（同期を受けている）か。`client/ClientScreenWatcher` が使う。 */
    var clientWatching: Boolean = false

    /** サーバーで、この画面を見ているプレイヤーの数（新しく見に来て、まだ送っていない人も含む）。 */
    val watcherCount: Int
        get() = watchers.size + newWatchers.size

    override fun onLoad() {
        super.onLoad()
        val level = level ?: return
        if (level.isClientSide) ScreenSync.clientScreens.add(this) else ScreenSync.serverScreens.add(this)
    }

    override fun setRemoved() {
        super.setRemoved()
        ScreenSync.serverScreens.remove(this)
        ScreenSync.clientScreens.remove(this)
        watchers.clear()
        newWatchers.clear()
        renderCache?.close()
        renderCache = null
    }

    /** サーバー: [player] が見はじめた。この tick の終わりに画面全体を送る。もう見ていれば送り直す。 */
    fun watch(player: ServerPlayer) {
        watchers.remove(player)
        newWatchers.add(player)
    }

    /** サーバー: [player] が見るのをやめた。 */
    fun unwatch(player: ServerPlayer) {
        watchers.remove(player)
        newWatchers.remove(player)
    }

    /** サーバー: tick の終わりに呼ぶ。見ている人がいれば、その tick の分を送る。 */
    fun syncTick(transport: ScreenSync.Transport) {
        if (watchers.isEmpty() && newWatchers.isEmpty() && sender?.isRecording != true) return
        val chunk = ChunkPos(blockPos)
        val gone = { p: ServerPlayer -> p.hasDisconnected() || p.level() !== level || !p.chunkTrackingView.contains(chunk) }
        watchers.removeIf { gone(it) }
        newWatchers.removeIf { gone(it) }
        val sender = sender ?: ScreenSyncSender(text).also {
            it.pixels = if (hasPixels) pixels else null
            sender = it
        }
        val flush = sender.flush(watchers.isNotEmpty(), newWatchers.isNotEmpty())
        val dimension = level?.dimension()
        if (!flush.isEmpty && dimension != null) {
            for (fragment in flush.forWatchers) {
                val payload = ScreenUpdatePayload(dimension, blockPos, fragment)
                for (p in watchers) transport.send(p, payload)
            }
            for (fragment in flush.forNewWatchers) {
                val payload = ScreenUpdatePayload(dimension, blockPos, fragment)
                for (p in newWatchers) transport.send(p, payload)
            }
        }
        watchers.addAll(newWatchers)
        newWatchers.clear()
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

    /** クライアント: 見るのをやめたときに、受け取り途中のフラグメントを捨てる。 */
    fun resetSync() {
        receiver.reset()
    }
}
