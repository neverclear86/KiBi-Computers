package pro.ikili.kibicomputers.neoforge.input

import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.input.KeyboardEventCodec
import pro.ikili.kibicomputers.neoforge.computer.CaseBlock
import pro.ikili.kibicomputers.neoforge.computer.TestComputerWiring
import pro.ikili.kibicomputers.neoforge.display.ScreenBlock
import java.util.Optional

/**
 * クライアントからサーバーへの、キーボードの入力（#142）。[session] は入力のセッションの番号（[KeyboardOpenPayload] で届いたもの）、
 * [data] は [KeyboardEventCodec] の形の入力の並び。どのキーボードへの入力かは、サーバーがセッションから決める（#360）。
 */
class KeyboardInputPayload(val session: Int, val data: ByteArray) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<KeyboardInputPayload> = TYPE

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<KeyboardInputPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(KiBiComputers.MOD_ID, "keyboard_input"))

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, KeyboardInputPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, KeyboardInputPayload::session,
            ByteBufCodecs.byteArray(KeyboardEventCodec.MAX_PACKET_BYTES), KeyboardInputPayload::data,
            ::KeyboardInputPayload,
        )
    }
}

/**
 * サーバーからクライアントへの、入力 GUI を開く指示（#360）。サーバーが入力のセッション [session] を作ったときに送る。
 * [keyboard] はキーボードの位置、[screen] は右クリックした画面（キーボードを直接右クリックしたなら null）。
 */
class KeyboardOpenPayload(val session: Int, val keyboard: BlockPos, val screen: Optional<BlockPos>) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<KeyboardOpenPayload> = TYPE

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<KeyboardOpenPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(KiBiComputers.MOD_ID, "keyboard_open"))

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, KeyboardOpenPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, KeyboardOpenPayload::session,
            BlockPos.STREAM_CODEC, KeyboardOpenPayload::keyboard,
            ByteBufCodecs.optional(BlockPos.STREAM_CODEC), KeyboardOpenPayload::screen,
            ::KeyboardOpenPayload,
        )
    }
}

/**
 * 入力のセッション [session] を閉じた知らせ（#360。両方向）。クライアントは GUI を閉じたときに、
 * サーバーはセッションを閉じたとき（離れすぎた、キーボードが壊れたなど）に送る。
 */
class KeyboardClosePayload(val session: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<KeyboardClosePayload> = TYPE

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<KeyboardClosePayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(KiBiComputers.MOD_ID, "keyboard_close"))

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, KeyboardClosePayload> =
            ByteBufCodecs.VAR_INT.map(::KeyboardClosePayload, KeyboardClosePayload::session)
    }
}

/**
 * キーボードの入力の接続層（#142）。パケットの登録、画面の隣のキーボード探し、クライアントの GUI への口を持つ。
 * サーバーでの入力の受け取りは [KeyboardSessions]（#360）。
 */
object KeyboardInput {
    /** ネットワークのプロトコルの版。[KeyboardEventCodec] の形や [pro.ikili.kibicomputers.core.input.Key] の並びを変えたら上げる。 */
    private const val PROTOCOL_VERSION = "2"

    /**
     * 入力を受け付ける距離のゆとり（ブロックの数）。プレイヤーがブロックに手の届く距離に、これを足した範囲で受け付ける。
     * GUI もこの範囲を出たら閉じる。
     */
    const val REACH_SLACK: Double = 4.0

    /** クライアントの入力 GUI への口。サーバーのクラスからクライアントのクラスを読み込まないために、クライアントの初期化で入れる。 */
    interface ClientHooks {
        /**
         * サーバーが作ったセッション [session] で、キーボード [keyboard] への入力 GUI を開く。
         * [screen] は右クリックした画面（キーボードを直接右クリックしたなら null）。
         */
        fun open(session: Int, keyboard: BlockPos, screen: BlockPos?)

        /** サーバーがセッション [session] を閉じた。そのセッションの GUI が開いていれば閉じる。 */
        fun closed(session: Int)
    }

    @Volatile
    var clientHooks: ClientHooks? = null

    /** MOD のイベントバスに登録する。MOD の初期化で1回だけ呼ぶ。 */
    fun register(modBus: IEventBus) {
        modBus.addListener(::registerPayloads)
        KeyboardSessions.register()
    }

    private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        event.registrar(PROTOCOL_VERSION)
            .playToServer(KeyboardInputPayload.TYPE, KeyboardInputPayload.STREAM_CODEC, ::onInput)
            .playToClient(KeyboardOpenPayload.TYPE, KeyboardOpenPayload.STREAM_CODEC, ::onOpen)
            .playBidirectional(KeyboardClosePayload.TYPE, KeyboardClosePayload.STREAM_CODEC, ::onClose)
    }

    // メインスレッドで呼ばれる（ペイロードの処理の既定）
    private fun onInput(payload: KeyboardInputPayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        KeyboardSessions.receive(player, payload)
    }

    private fun onOpen(payload: KeyboardOpenPayload, context: IPayloadContext) {
        clientHooks?.open(payload.session, payload.keyboard, payload.screen.orElse(null))
    }

    private fun onClose(payload: KeyboardClosePayload, context: IPayloadContext) {
        if (context.flow() == PacketFlow.SERVERBOUND) {
            val player = context.player() as? ServerPlayer ?: return
            KeyboardSessions.closeFromClient(player, payload.session)
        } else {
            clientHooks?.closed(payload.session)
        }
    }

    /** [player] がキーボード [pos] に入力できる距離にいるか。 */
    fun canReach(player: Player, pos: BlockPos): Boolean =
        !player.isSpectator && player.isAlive && player.canInteractWithBlock(pos, REACH_SLACK)

    /**
     * 画面 [screenPos] につながったキーボードを探す。見つからなければ null。
     *
     * 探すのは、画面の正面の前のブロック、その1つ下（机の上に置いたキーボード）、画面の上下と左右・後ろの順。
     * 大型モニター（#138）では、結合した画面のどのブロックの隣でもよいようにする予定。
     */
    fun findKeyboard(level: Level, screenPos: BlockPos): BlockPos? {
        val state = level.getBlockState(screenPos)
        if (state.block !is ScreenBlock) return null
        val facing = state.getValue(ScreenBlock.FACING)
        val front = screenPos.relative(facing)
        val candidates = listOf(
            front,
            front.below(),
            screenPos.below(),
            screenPos.above(),
            screenPos.relative(facing.clockWise),
            screenPos.relative(facing.counterClockWise),
            screenPos.relative(facing.opposite),
        )
        return candidates.firstOrNull { level.isLoaded(it) && level.getBlockEntity(it) is KeyboardBlockEntity }
    }

    /**
     * キーボード [keyboardPos] の入力が届く画面を探す（入力 GUI に画面の中身を出すため。#348）。見つからなければ null。
     *
     * 1. キーボードの隣のケースの画面（テスト用コンピューターの仮の結線 #307 と同じ探し方。そちらが入力を受け取るので先に見る）
     * 2. [findKeyboard] でこのキーボードを見つける、近くの画面（キーボードの隣か、1つ上の段の隣）
     *
     * 正式な結線（#129）ができたら、つながったコンピューターの画面に置き換える。
     */
    fun findScreen(level: Level, keyboardPos: BlockPos): BlockPos? {
        for (direction in Direction.entries) {
            val pos = keyboardPos.relative(direction)
            if (!level.isLoaded(pos) || level.getBlockState(pos).block !is CaseBlock) continue
            TestComputerWiring.findScreen(level, pos)?.let { return it.blockPos }
        }
        val candidates = Direction.entries.map { keyboardPos.relative(it) } +
            Direction.Plane.HORIZONTAL.map { keyboardPos.above().relative(it) }
        return candidates.firstOrNull { level.isLoaded(it) && findKeyboard(level, it) == keyboardPos }
    }
}
