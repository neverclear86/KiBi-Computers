package pro.ikili.kibicomputers.neoforge.input

import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import pro.ikili.kibicomputers.core.input.KeyboardInputBudget
import pro.ikili.kibicomputers.core.input.KeyboardSession
import java.util.Optional
import java.util.UUID

/**
 * キーボードへの入力のセッション（#360）を、サーバーで管理する。サーバーのメインスレッドからだけ使う。
 *
 * - 入力 GUI はサーバーが開く。キーボードか画面の右クリックがサーバーに届いたら（保護 MOD が右クリックのイベントを
 *   取り消したなら届かない）、距離を確かめてセッションを作り、クライアントに開かせる（[open]）。持ち主と権限は #131
 * - 入力のパケットは、そのプレイヤーの開いているセッションからだけ受け付ける（[receive]）。セッションは1人に1つ
 * - 距離を外れた・キーボードが壊れた・別のディメンションに移った・ログアウトした、などで閉じる（tick ごとに見る。
 *   開いているセッションは GUI を開いている人の数だけなので、待っているだけのコンピューターには負荷がかからない）
 * - 閉じるときに、押したままのキーの離しを合成してキーボードに配る（#326 で使う）
 * - 受け取る量の上限（[KeyboardInputBudget]）は、プレイヤーとキーボードの組ごと。開き直しても戻らないように、
 *   使い切っていない分はログアウトまで覚えておく（満タンに戻ったものは、次に開くときに捨てる）
 */
object KeyboardSessions {
    private class Open(
        val player: ServerPlayer,
        val level: ServerLevel,
        val pos: BlockPos,
        val keyboard: KeyboardBlockEntity,
        val session: KeyboardSession,
    )

    /** プレイヤーの UUID から、開いているセッション */
    private val sessions = HashMap<UUID, Open>()

    /** プレイヤーの UUID から、キーボードごとの受け取る量の上限 */
    private val budgets = HashMap<UUID, HashMap<GlobalPos, KeyboardInputBudget>>()

    private var nextId = 1

    internal fun register() {
        NeoForge.EVENT_BUS.addListener<ServerTickEvent.Post> { tick() }
        NeoForge.EVENT_BUS.addListener<PlayerEvent.PlayerLoggedOutEvent> { event ->
            (event.entity as? ServerPlayer)?.let(::loggedOut)
        }
        NeoForge.EVENT_BUS.addListener<ServerStoppedEvent> {
            sessions.clear()
            budgets.clear()
        }
    }

    /**
     * [player] に、キーボード [keyboardPos] への入力のセッションを開き、クライアントに入力 GUI を開かせる。
     * 前のセッションがあれば閉じる。[screenPos] は右クリックした画面（GUI に中身を出すため。なければ null）。
     *
     * @return 開いたセッション。届く距離にない、キーボードがないなら null
     */
    fun open(player: ServerPlayer, keyboardPos: BlockPos, screenPos: BlockPos?): KeyboardSession? {
        close(player.uuid, notifyClient = true)
        val level = player.serverLevel()
        if (!level.isLoaded(keyboardPos) || !KeyboardInput.canReach(player, keyboardPos)) return null
        val keyboard = level.getBlockEntity(keyboardPos) as? KeyboardBlockEntity ?: return null
        val tick = tickOf(player)
        val mine = budgets.getOrPut(player.uuid) { HashMap() }
        mine.values.removeIf { it.available(tick) >= it.capacity }
        val budget = mine.getOrPut(GlobalPos.of(level.dimension(), keyboardPos)) { KeyboardInputBudget() }
        val session = KeyboardSession(nextId++, player.uuid, budget)
        if (nextId <= 0) nextId = 1
        sessions[player.uuid] = Open(player, level, keyboardPos.immutable(), keyboard, session)
        send(player, KeyboardOpenPayload(session.id, keyboardPos, Optional.ofNullable(screenPos)))
        return session
    }

    /** [player] の開いているセッション。なければ null。 */
    fun sessionOf(player: ServerPlayer): KeyboardSession? = sessions[player.uuid]?.session

    /**
     * [player] から届いた [payload] を、セッションのキーボードに渡す。セッションがない・番号が違う・届く距離にない、
     * 量の上限を超えた、形が崩れている、などのときは捨てる（距離を外れたなら、セッションも閉じる）。
     *
     * @return 配った入力の数。捨てたなら -1
     */
    fun receive(player: ServerPlayer, payload: KeyboardInputPayload): Int {
        val open = sessions[player.uuid] ?: return -1
        if (open.session.id != payload.session || open.player !== player) return -1
        if (!isValid(open)) {
            close(player.uuid, notifyClient = true)
            return -1
        }
        val events = open.session.receive(payload.data, tickOf(player)) ?: return -1
        open.keyboard.deliver(player.uuid, events)
        return events.size
    }

    /** クライアントが GUI を閉じた。[session] が開いているものなら閉じる（古い番号なら何もしない）。 */
    fun closeFromClient(player: ServerPlayer, session: Int) {
        if (sessions[player.uuid]?.session?.id == session) close(player.uuid, notifyClient = false)
    }

    /** ログアウトした。セッションを閉じ、受け取る量の上限も捨てる。 */
    internal fun loggedOut(player: ServerPlayer) {
        close(player.uuid, notifyClient = false)
        budgets.remove(player.uuid)
    }

    /** 開いているセッションのうち、続けられないもの（[isValid]）を閉じる。サーバーの tick ごとに呼ぶ。 */
    internal fun tick() {
        if (sessions.isEmpty()) return
        val stale = sessions.entries.filter { !isValid(it.value) }.map { it.key }
        for (id in stale) close(id, notifyClient = true)
    }

    private fun isValid(open: Open): Boolean {
        val player = open.player
        if (player.isRemoved || player.serverLevel() !== open.level || open.keyboard.isRemoved) return false
        if (!open.level.isLoaded(open.pos) || open.level.getBlockEntity(open.pos) !== open.keyboard) return false
        return KeyboardInput.canReach(player, open.pos)
    }

    private fun close(playerId: UUID, notifyClient: Boolean) {
        val open = sessions.remove(playerId) ?: return
        val ups = open.session.close()
        if (!open.keyboard.isRemoved) open.keyboard.deliver(playerId, ups)
        if (notifyClient && !open.player.hasDisconnected()) send(open.player, KeyboardClosePayload(open.session.id))
    }

    private fun tickOf(player: ServerPlayer): Long = player.server.tickCount.toLong()

    // GameTest の偽のプレイヤーは、MOD のチャンネルを持たない
    private fun send(player: ServerPlayer, payload: CustomPacketPayload) {
        if (player.connection?.hasChannel(payload) == true) PacketDistributor.sendToPlayer(player, payload)
    }
}
