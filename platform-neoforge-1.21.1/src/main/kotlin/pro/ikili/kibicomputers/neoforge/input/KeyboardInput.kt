package pro.ikili.kibicomputers.neoforge.input

import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
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

/**
 * クライアントからサーバーへの、キーボードの入力（#142）。[pos] はキーボードの位置（プレイヤーのいるディメンション）、
 * [data] は [KeyboardEventCodec] の形の入力の並び。
 */
class KeyboardInputPayload(val pos: BlockPos, val data: ByteArray) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<KeyboardInputPayload> = TYPE

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<KeyboardInputPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(KiBiComputers.MOD_ID, "keyboard_input"))

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, KeyboardInputPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, KeyboardInputPayload::pos,
            ByteBufCodecs.byteArray(KeyboardEventCodec.MAX_PACKET_BYTES), KeyboardInputPayload::data,
            ::KeyboardInputPayload,
        )
    }
}

/**
 * キーボードの入力の接続層（#142）。パケットの登録とサーバーでの受け取り、画面の隣のキーボード探し、
 * クライアントで入力 GUI を開く口を持つ。
 */
object KeyboardInput {
    /** ネットワークのプロトコルの版。[KeyboardEventCodec] の形や [pro.ikili.kibicomputers.core.input.Key] の並びを変えたら上げる。 */
    private const val PROTOCOL_VERSION = "1"

    /**
     * 入力を受け付ける距離のゆとり（ブロックの数）。プレイヤーがブロックに手の届く距離に、これを足した範囲で受け付ける。
     * GUI もこの範囲を出たら閉じる。
     */
    const val REACH_SLACK: Double = 4.0

    /** クライアントで入力 GUI を開く口。サーバーのクラスからクライアントのクラスを読み込まないために、クライアントの初期化で入れる。 */
    fun interface ClientOpener {
        /** キーボード [keyboard] への入力 GUI を開く。[screen] は右クリックした画面（キーボードを直接右クリックしたなら null）。 */
        fun open(keyboard: BlockPos, screen: BlockPos?)
    }

    @Volatile
    var clientOpener: ClientOpener? = null

    /** MOD のイベントバスに登録する。MOD の初期化で1回だけ呼ぶ。 */
    fun register(modBus: IEventBus) {
        modBus.addListener(::registerPayloads)
    }

    private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        event.registrar(PROTOCOL_VERSION)
            .playToServer(KeyboardInputPayload.TYPE, KeyboardInputPayload.STREAM_CODEC, ::onInput)
    }

    /** クライアントで、入力 GUI を開く。 */
    fun openOnClient(keyboard: BlockPos, screen: BlockPos?) {
        clientOpener?.open(keyboard, screen)
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

    // サーバー: メインスレッドで呼ばれる
    private fun onInput(payload: KeyboardInputPayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        receive(player, payload)
    }

    /**
     * [player] から届いた [payload] を、キーボードに渡す。届く距離にない、キーボードがない、などのときは捨てる。
     *
     * @return 配った入力の数。捨てたなら -1
     */
    fun receive(player: ServerPlayer, payload: KeyboardInputPayload): Int {
        val level = player.serverLevel()
        val pos = payload.pos
        if (!level.isLoaded(pos) || !canReach(player, pos)) return -1
        val keyboard = level.getBlockEntity(pos) as? KeyboardBlockEntity ?: return -1
        return keyboard.receive(payload.data, level.gameTime)
    }
}
