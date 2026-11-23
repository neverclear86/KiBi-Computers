package pro.ikili.kibicomputers.neoforge.display

import com.mojang.logging.LogUtils
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.level.ChunkWatchEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import pro.ikili.kibicomputers.core.config.ConfigValues
import pro.ikili.kibicomputers.core.config.KiBiConfig
import pro.ikili.kibicomputers.core.display.ScreenSyncHost
import pro.ikili.kibicomputers.core.display.ScreenSyncTraffic
import pro.ikili.kibicomputers.core.display.ScreenWatchers
import pro.ikili.kibicomputers.core.kernel.sched.FairShareGroup
import pro.ikili.kibicomputers.neoforge.config.KiBiConfigs
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
 * 2. 見ている人がいる画面だけ、その tick の分を [traffic] に積む。新しく見に来た人には画面全体（キーフレーム）を積む。
 *    全部の画面の後で [ScreenSyncTraffic.endTick] が、プレイヤーごと・サーバー全体の送信量の予算の中で、
 *    近い画面・変化の少ない画面から送る（#352）。送れなかった画面は、次にキーフレームを送る
 * 3. クライアントは受け取った分を画面バッファに再生する。壊れたデータを受け取ったら、[ScreenResyncPayload] で
 *    送り直しをお願いする。サーバーは、送り直しをプレイヤーと画面の組ごとに一定の間隔に1回までにする
 *
 * 見ている人と送信量の勘定は `ServerPlayer` のオブジェクトで数える。ログアウトしたとき、リスポーンで
 * オブジェクトが替わったとき（[PlayerEvent.Clone]）、ディメンションを移ったときに外す（#382）。
 * プレイヤーごとの「送ったチャンク」の記録（[isChunkSent]、#655）も、同じところで外す。
 */
object ScreenSync {
    private val LOGGER = LogUtils.getLogger()

    /** ネットワークのプロトコルの版。描画命令やパケットの形式を変えたら上げる。 */
    private const val PROTOCOL_VERSION = "3"

    /** サーバーで読み込まれている画面。 */
    internal val serverScreens: MutableSet<ScreenBlockEntity> = Collections.newSetFromMap(IdentityHashMap())

    /**
     * 画面の同期の、プレイヤーごとの送信量の勘定と、プレイヤーごと・サーバー全体の予算（#359、#352）。
     * 予算はコンフィグ（#202）の値。読み直して予算が変わったら、tick の始めに作り直す（プレイヤーごとの勘定は空に戻る）
     */
    @Volatile
    var traffic: ScreenSyncTraffic<ServerPlayer> = ScreenSyncTraffic()
        private set

    /** [traffic] を作ったコンフィグの写し */
    private var trafficConfig: ConfigValues? = null

    /** コンフィグの予算が変わっていたら、[traffic] を作り直す。メインスレッドから呼ぶ */
    internal fun applyConfigIfChanged() {
        val values = KiBiConfigs.server
        if (values === trafficConfig) return
        trafficConfig = values
        val current = traffic
        if (current.playerBytesPerSecond != values[KiBiConfig.SCREEN_PLAYER_BYTES_PER_SECOND] ||
            current.serverBytesPerSecond != values[KiBiConfig.SCREEN_SERVER_BYTES_PER_SECOND] ||
            current.groupBytesPerSecond != values[KiBiConfig.SCREEN_OWNER_BYTES_PER_SECOND]
        ) {
            traffic = KiBiConfig.screenSyncTraffic(values)
        }
    }

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
        // ChunkWatchEvent は abstract なので、具体クラスごとに登録する
        NeoForge.EVENT_BUS.addListener(::onChunkWatch)
        NeoForge.EVENT_BUS.addListener(::onChunkSent)
        NeoForge.EVENT_BUS.addListener(::onChunkUnWatch)
    }

    private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar(PROTOCOL_VERSION)
        registrar.playToClient(ScreenUpdatePayload.TYPE, ScreenUpdatePayload.STREAM_CODEC, ::onUpdate)
        registrar.playToServer(ScreenResyncPayload.TYPE, ScreenResyncPayload.STREAM_CODEC, ::onResync)
    }

    private fun onServerTick(event: ServerTickEvent.Post) {
        applyConfigIfChanged()
        // 送る間に画面が消えることはないが、念のため写してから回す
        if (serverScreens.isNotEmpty()) for (screen in serverScreens.toTypedArray()) screen.syncTick()
        // 積んだ分を予算の中で送り、予算を増やす（画面がなくても予算は戻す）
        traffic.endTick()
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
        // 元のディメンションで送ったチャンクの記録も捨てる。新しいディメンションの Sent は、この後の "send chunks" で出る
        sentChunks.remove(player)
    }

    /** [player] を、すべての画面の見ている人と、送信量の勘定から外す（ログアウトしたとき、リスポーンで替わった古いオブジェクト）。 */
    internal fun forget(player: ServerPlayer) {
        for (screen in serverScreens) screen.forget(player)
        traffic.forget(player)
        sentChunks.remove(player)
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
     * 画面のチャンクがプレイヤーに届いていない（まだ送っていない、送り直しの順番を待っている、追跡から外れた）間は、
     * 見られないものとする（[isChunkSent]）。
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
            val level = screen.level as? ServerLevel ?: return Double.POSITIVE_INFINITY
            if (!isChunkSent(level, player, chunk)) return Double.POSITIVE_INFINITY
            return center.distanceToSqr(player.eyePosition)
        }

        override fun send(player: ServerPlayer, fragment: ByteArray) {
            val dimension = screen.level?.dimension() ?: return
            transport.send(player, ScreenUpdatePayload(dimension, screen.blockPos, fragment))
        }

        // 映している側（つながったケースのマシン）の公平の組（#738）。つながっていなければ持ち主なし
        override fun fairShareGroup(): FairShareGroup = screen.wiredTo?.connectedMachine?.fairShareGroup ?: FairShareGroup.NONE
    }

    /** 1人のプレイヤーに送ったチャンク（[level] のもの）の座標（[ChunkPos.toLong]）。 */
    private class SentChunks(val level: ServerLevel) {
        val chunks = LongOpenHashSet()
    }

    /**
     * プレイヤーごとの、送ったチャンクの記録（#655）。メインスレッドから読み書きする（チャンクのイベントも、画面の tick も
     * メインスレッドで動く）。
     *
     * 画面のあるチャンクに絞らず、送ったチャンクをすべて記録する。送った後のチャンクに画面を置いたとき（いちばんよくある置き方）、
     * Sent はもう出た後なので、絞ると次にチャンクを送り直すまで誰もその画面を見なくなる。コストは台数によらず、
     * プレイヤー数 × 見える範囲に比例する（描画距離 12 で 625 件 ≒ 8 KB/人、32 で 4225 件 ≒ 64 KB/人）。
     */
    private val sentChunks = IdentityHashMap<ServerPlayer, SentChunks>()

    // チャンクを送る順番待ちに入れた（送り直しも含む）。届くまでは見られないものとする。
    // 入れ物は作らない（ログアウトの後にも出るので、作ると消したプレイヤーの分が残る）
    private fun onChunkWatch(event: ChunkWatchEvent.Watch) {
        sentChunks[event.player]?.chunks?.remove(event.pos.toLong())
    }

    // 追跡から外した（順番待ちから外したか、クライアントに忘れさせた）。送る前に外れたチャンクにも出る
    private fun onChunkUnWatch(event: ChunkWatchEvent.UnWatch) {
        sentChunks[event.player]?.chunks?.remove(event.pos.toLong())
    }

    // チャンクのパケットを接続に積んだ。この後に積む画面のパケットは、チャンクより先には届かない
    private fun onChunkSent(event: ChunkWatchEvent.Sent) {
        val player = event.player
        // リスポーンで替わった古いオブジェクト（接続の player が替わる前の Sent）など。記録を作ると残るので無視する
        if (player.isRemoved || player.hasDisconnected()) return
        val level = event.level
        var record = sentChunks[player]
        if (record == null || record.level !== level) {
            record = SentChunks(level)
            sentChunks[player] = record
        }
        record.chunks.add(event.pos.toLong())
        // 送り直したチャンクでは、クライアントが画面のブロックエンティティを作り直し、画面が空になる。順番待ちから送るまでが
        // 同じ tick に収まると、tick の終わりの距離の確かめでは見ている人から外れないので、ここで外す。次に見はじめる人を
        // 探す tick に、間隔を待たずにキーフレームを送る。画面を全部なめず、そのチャンクのブロックエンティティだけを見る
        if (serverScreens.isEmpty()) return
        for (be in event.chunk.blockEntities.values) {
            if (be is ScreenBlockEntity) be.forget(player)
        }
    }

    /**
     * [level] の [chunk] を、もう [player] に送ったか。メインスレッドから呼ぶ。
     *
     * NeoForge のチャンクのイベントで、プレイヤーごとに送った事実を記録している（#655）:
     * - [ChunkWatchEvent.Sent]: チャンクのパケットを接続に積んだ（`PlayerChunkSender.sendNextChunks`。tick の "send chunks" の段で、
     *   [ServerTickEvent.Post] より前）。記録に足す
     * - [ChunkWatchEvent.Watch]: 送る順番待ちに入れた（送り直しも含む）。記録から外す
     * - [ChunkWatchEvent.UnWatch]: 追跡から外した。記録から外す
     *
     * サーバーはチャンクを、追跡の範囲に入っただけでは送らず、送れる状態になってから送る順番待ちに入れる。画面のブロックエンティティは
     * その前の段（FULL）で読み込まれるので、まだ送っていない間に画面を送ると、クライアントはチャンクを持っていないので捨て、
     * サーバーは送れたものとして差分しか送らなくなる（#636）。以前（#654）は「追跡の範囲・順番待ちでない・送れる状態」から推し量って
     * いたが、`ChunkMap.waitForLightBeforeSending` で送る前の待ちを足したチャンク（End の出口ポータルの周りなど）でずれた。
     */
    internal fun isChunkSent(level: ServerLevel, player: ServerPlayer, chunk: ChunkPos): Boolean {
        val record = sentChunks[player] ?: return false
        return record.level === level && record.chunks.contains(chunk.toLong())
    }

    /** [player] の送ったチャンクの記録の件数。記録がなければ -1。GameTest で、記録が残らないことを確かめるためのもの。 */
    internal fun sentChunkCount(player: ServerPlayer): Int = sentChunks[player]?.chunks?.size ?: -1

    // クライアント: 画面の中身のフラグメントを受け取った（メインスレッドで呼ばれる）
    private fun onUpdate(payload: ScreenUpdatePayload, context: IPayloadContext) {
        val level = context.player().level()
        if (!level.isClientSide) return
        if (level.dimension() != payload.dimension) {
            // ディメンションを移った直後に、元のディメンションの画面の分が届くことがある（ふつうに起きる）
            LOGGER.debug(
                "Dropped screen sync data at {}: it is for {} but the client is in {}",
                payload.pos,
                payload.dimension.location(),
                level.dimension().location(),
            )
            return
        }
        if (!level.isLoaded(payload.pos)) {
            // サーバーは送れたものとして扱うので、ここで捨てると次のキーフレームまで画面が古いままになる（#636）
            LOGGER.debug("Dropped screen sync data at {}: the chunk is not loaded on the client", payload.pos)
            return
        }
        val screen = level.getBlockEntity(payload.pos) as? ScreenBlockEntity ?: run {
            LOGGER.debug("Dropped screen sync data at {}: no screen block entity on the client", payload.pos)
            return
        }
        if (!screen.receiveSync(payload.data)) {
            LOGGER.debug("Malformed screen sync data at {}; requesting a keyframe", payload.pos)
            context.reply(ScreenResyncPayload(payload.pos))
        }
    }
}
