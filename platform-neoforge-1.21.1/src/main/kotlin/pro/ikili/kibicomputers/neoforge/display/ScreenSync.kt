package pro.ikili.kibicomputers.neoforge.display

import com.mojang.logging.LogUtils
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.Collections
import java.util.IdentityHashMap

/**
 * 画面の同期（#140、ADR 0031）の接続層。パケットの登録と、tick の終わりの送信を行う。
 *
 * 同期の中身（描画命令の記録・キーフレーム・送信量の上限）は `core` の
 * [pro.ikili.kibicomputers.core.display.ScreenSyncSender] / [pro.ikili.kibicomputers.core.display.ScreenSyncReceiver] が行う。
 *
 * 流れ:
 * 1. クライアントは、読み込んだ画面に近づくと [ScreenWatchPayload]（見はじめた）を送る（`client/ClientScreenWatcher`）
 * 2. サーバーは、tick の終わり（[ServerTickEvent.Post]）に、見ている人がいる画面だけ、その tick の分を送る。
 *    新しく見に来た人には画面全体（キーフレーム）を送る
 * 3. クライアントは受け取った分を画面バッファに再生する。壊れたデータを受け取ったら、見はじめたと送り直してキーフレームをもらう
 */
object ScreenSync {
    private val LOGGER = LogUtils.getLogger()

    /** ネットワークのプロトコルの版。描画命令の形式を変えたら上げる。 */
    private const val PROTOCOL_VERSION = "1"

    /** サーバーで読み込まれている画面。 */
    internal val serverScreens: MutableSet<ScreenBlockEntity> = Collections.newSetFromMap(IdentityHashMap())

    /** クライアントで読み込まれている画面。 */
    val clientScreens: MutableSet<ScreenBlockEntity> = Collections.newSetFromMap(IdentityHashMap())

    /** プレイヤーへの送り方。GameTest では、偽物のプレイヤーに送れないので差し替える。 */
    fun interface Transport {
        fun send(player: ServerPlayer, payload: ScreenUpdatePayload)
    }

    /** 本物の送り方。MOD のない（チャンネルを持たない）クライアントには送らない。 */
    val NETWORK: Transport = Transport { player, payload ->
        if (player.connection.hasChannel(payload)) PacketDistributor.sendToPlayer(player, payload)
    }

    @Volatile
    var transport: Transport = NETWORK

    /** MOD のイベントバスと NeoForge のイベントバスに登録する。MOD の初期化で1回だけ呼ぶ。 */
    fun register(modBus: IEventBus) {
        modBus.addListener(::registerPayloads)
        NeoForge.EVENT_BUS.addListener(::onServerTick)
    }

    private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar(PROTOCOL_VERSION)
        registrar.playToClient(ScreenUpdatePayload.TYPE, ScreenUpdatePayload.STREAM_CODEC, ::onUpdate)
        registrar.playToServer(ScreenWatchPayload.TYPE, ScreenWatchPayload.STREAM_CODEC, ::onWatch)
    }

    private fun onServerTick(event: ServerTickEvent.Post) {
        if (serverScreens.isEmpty()) return
        val transport = transport
        // 送る間に画面が消えることはないが、念のため写してから回す
        for (screen in serverScreens.toTypedArray()) screen.syncTick(transport)
    }

    // サーバー: クライアントが画面を見はじめた・やめた（メインスレッドで呼ばれる）
    internal fun onWatch(payload: ScreenWatchPayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        watch(player, payload)
    }

    /** [player] からの [payload] を処理する。プレイヤーが読み込んでいるチャンクの画面だけを受け付ける。 */
    fun watch(player: ServerPlayer, payload: ScreenWatchPayload) {
        val level = player.serverLevel()
        val pos = payload.pos
        if (!payload.watch) {
            // 見るのをやめるときは、チャンクが消えていても構わない
            if (level.isLoaded(pos)) (level.getBlockEntity(pos) as? ScreenBlockEntity)?.unwatch(player)
            return
        }
        if (!level.isLoaded(pos) || !player.chunkTrackingView.contains(ChunkPos(pos))) return
        (level.getBlockEntity(pos) as? ScreenBlockEntity)?.watch(player)
    }

    // クライアント: 画面の中身のフラグメントを受け取った（メインスレッドで呼ばれる）
    private fun onUpdate(payload: ScreenUpdatePayload, context: IPayloadContext) {
        val level = context.player().level()
        if (!level.isClientSide || level.dimension() != payload.dimension || !level.isLoaded(payload.pos)) return
        val screen = level.getBlockEntity(payload.pos) as? ScreenBlockEntity ?: return
        if (!screen.receiveSync(payload.data)) {
            LOGGER.debug("Malformed screen sync data at {}; requesting a keyframe", payload.pos)
            context.reply(ScreenWatchPayload(payload.pos, true))
        }
    }
}
