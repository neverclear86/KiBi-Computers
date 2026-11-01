package pro.ikili.kibicomputers.neoforge.display

import com.mojang.logging.LogUtils
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import pro.ikili.kibicomputers.core.display.ScreenSyncHost
import pro.ikili.kibicomputers.core.display.ScreenSyncTraffic
import pro.ikili.kibicomputers.core.display.ScreenWatchers
import java.util.Collections
import java.util.IdentityHashMap

/**
 * 画面の同期（#140、ADR 0031）の接続層。パケットの登録と、tick の終わりの送信を行う。
 *
 * 同期の中身（描画命令の記録・キーフレーム・送信量の上限）は `core` の
 * [pro.ikili.kibicomputers.core.display.ScreenSyncSender] / [pro.ikili.kibicomputers.core.display.ScreenSyncReceiver] が、
 * 誰に送るか（見ている人の集合）は [ScreenWatchers] が行う（#359）。
 *
 * 流れ:
 * 1. サーバーは、tick の終わり（[ServerTickEvent.Post]）に、画面ごとに、プレイヤーとの距離から見ている人を決め直す
 *    （[ScreenWatchers]。見はじめる距離とやめる距離に差を持たせる）
 * 2. 見ている人がいる画面だけ、その tick の分を送る。新しく見に来た人には画面全体（キーフレーム）を送る
 * 3. クライアントは受け取った分を画面バッファに再生する。壊れたデータを受け取ったら、[ScreenResyncPayload] で
 *    送り直しをお願いする。サーバーは、送り直しをプレイヤーと画面の組ごとに一定の間隔に1回までにする
 *
 * 見ている人と送信量の勘定は `ServerPlayer` のオブジェクトで数える。ログアウトしたとき、リスポーンで
 * オブジェクトが替わったとき（[PlayerEvent.Clone]）、ディメンションを移ったときに外す（#382）。
 */
object ScreenSync {
    private val LOGGER = LogUtils.getLogger()

    /** ネットワークのプロトコルの版。描画命令やパケットの形式を変えたら上げる。 */
    private const val PROTOCOL_VERSION = "3"

    /** サーバーで読み込まれている画面。 */
    internal val serverScreens: MutableSet<ScreenBlockEntity> = Collections.newSetFromMap(IdentityHashMap())

    /** プレイヤーごとの、画面の同期の送信量の勘定（#359）。プレイヤーごとの予算（#352）はこの上に作る。 */
    val traffic: ScreenSyncTraffic<ServerPlayer> = ScreenSyncTraffic()

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
        NeoForge.EVENT_BUS.addListener(::onLogout)
        NeoForge.EVENT_BUS.addListener(::onClone)
        NeoForge.EVENT_BUS.addListener(::onChangedDimension)
    }

    private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar(PROTOCOL_VERSION)
        registrar.playToClient(ScreenUpdatePayload.TYPE, ScreenUpdatePayload.STREAM_CODEC, ::onUpdate)
        registrar.playToServer(ScreenResyncPayload.TYPE, ScreenResyncPayload.STREAM_CODEC, ::onResync)
    }

    private fun onServerTick(event: ServerTickEvent.Post) {
        if (serverScreens.isEmpty()) return
        // 送る間に画面が消えることはないが、念のため写してから回す
        for (screen in serverScreens.toTypedArray()) screen.syncTick()
    }

    private fun onLogout(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        forget(player)
    }

    // リスポーン（End からの帰還も含む）では ServerPlayer が新しいオブジェクトに替わり、古い方のログアウトは来ない。
    // 古い方への参照を残さないよう、ここで外す。新しい方は、画面のチャンクが届いてから見はじめ、画面全体を受け取る
    private fun onClone(event: PlayerEvent.Clone) {
        val original = event.original as? ServerPlayer ?: return
        if (original === event.entity) return
        forget(original)
    }

    // ディメンションを移ると、クライアントのレベルは作り直される。元のディメンションの画面の見ている人から外し、
    // 戻ってきたら間隔を待たずに画面全体を送れるようにする。勘定は同じプレイヤーのものなので残す
    private fun onChangedDimension(event: PlayerEvent.PlayerChangedDimensionEvent) {
        val player = event.entity as? ServerPlayer ?: return
        for (screen in serverScreens) {
            if (screen.level?.dimension() == event.from) screen.forget(player)
        }
    }

    /** [player] を、すべての画面の見ている人と、送信量の勘定から外す（ログアウトしたとき、リスポーンで替わった古いオブジェクト）。 */
    internal fun forget(player: ServerPlayer) {
        for (screen in serverScreens) screen.forget(player)
        traffic.forget(player)
    }

    // サーバー: クライアントが送り直しをお願いした（メインスレッドで呼ばれる）
    private fun onResync(payload: ScreenResyncPayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        requestResync(player, payload)
    }

    /**
     * [player] からの送り直しのお願い [payload] を処理する。読み込まれていて、[player] が見ている画面だけを受け付ける
     * （見ているかは、サーバーが距離から決めたもの）。
     */
    fun requestResync(player: ServerPlayer, payload: ScreenResyncPayload) {
        handleResync(player, payload)
    }

    /** [requestResync] で、お願いをどこで捨てたか、画面に渡したか。GameTest で確かめるためのもの。 */
    internal enum class ResyncOutcome {
        /** 見るのをやめる距離より遠いので、ブロックエンティティを探さずに捨てた。 */
        TOO_FAR,

        /** 読み込まれていない（またはワールドの高さの外の）座標なので、捨てた。 */
        NOT_LOADED,

        /** その座標に画面がないので、捨てた。 */
        NO_SCREEN,

        /** 画面に渡した（見ていない人のお願いは、画面が無視する）。 */
        FORWARDED,
    }

    internal fun handleResync(player: ServerPlayer, payload: ScreenResyncPayload): ResyncOutcome {
        val level = player.serverLevel()
        val pos = payload.pos
        // 遠すぎるものは、ブロックエンティティを探すまでもなく捨てる
        val stop = ScreenWatchers.DEFAULT_START_DISTANCE + ScreenWatchers.DEFAULT_HYSTERESIS
        if (Vec3.atCenterOf(pos).distanceToSqr(player.eyePosition) > stop * stop) return ResyncOutcome.TOO_FAR
        if (!level.isLoaded(pos)) return ResyncOutcome.NOT_LOADED
        val screen = level.getBlockEntity(pos) as? ScreenBlockEntity ?: return ResyncOutcome.NO_SCREEN
        screen.requestResync(player)
        return ResyncOutcome.FORWARDED
    }

    /**
     * 1つの画面の [ScreenWatchers] に、プレイヤーの位置を教え、フラグメントを送る口。
     * 画面のチャンクがプレイヤーに届いていない（追跡していない、送る順番を待っている）間は、見られないものとする。
     */
    internal class Host(private val screen: ScreenBlockEntity) : ScreenSyncHost<ServerPlayer> {
        private val center = Vec3.atCenterOf(screen.blockPos)
        private val chunk = ChunkPos(screen.blockPos)

        override fun candidates(): Iterable<ServerPlayer> = (screen.level as? ServerLevel)?.players() ?: emptyList()

        override fun distanceSquared(player: ServerPlayer): Double {
            // isRemoved: リスポーンで替わった古いオブジェクトなど（念のため。ふつうは onClone で外れている）
            if (player.hasDisconnected() || player.isRemoved || player.level() !== screen.level) {
                return Double.POSITIVE_INFINITY
            }
            if (!player.chunkTrackingView.contains(chunk) || player.connection.chunkSender.isPending(chunk.toLong())) {
                return Double.POSITIVE_INFINITY
            }
            return center.distanceToSqr(player.eyePosition)
        }

        override fun send(player: ServerPlayer, fragment: ByteArray) {
            val dimension = screen.level?.dimension() ?: return
            transport.send(player, ScreenUpdatePayload(dimension, screen.blockPos, fragment))
        }
    }

    // クライアント: 画面の中身のフラグメントを受け取った（メインスレッドで呼ばれる）
    private fun onUpdate(payload: ScreenUpdatePayload, context: IPayloadContext) {
        val level = context.player().level()
        if (!level.isClientSide || level.dimension() != payload.dimension || !level.isLoaded(payload.pos)) return
        val screen = level.getBlockEntity(payload.pos) as? ScreenBlockEntity ?: return
        if (!screen.receiveSync(payload.data)) {
            LOGGER.debug("Malformed screen sync data at {}; requesting a keyframe", payload.pos)
            context.reply(ScreenResyncPayload(payload.pos))
        }
    }
}
