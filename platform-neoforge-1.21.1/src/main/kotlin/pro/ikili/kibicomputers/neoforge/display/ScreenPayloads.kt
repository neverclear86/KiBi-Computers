package pro.ikili.kibicomputers.neoforge.display

import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import pro.ikili.kibicomputers.core.KiBiComputers

/**
 * サーバーからクライアントへの、画面の中身の同期のフラグメント（#140）。
 * [data] は [pro.ikili.kibicomputers.core.display.ScreenSyncSender] が作ったフラグメント。
 */
class ScreenUpdatePayload(val dimension: ResourceKey<Level>, val pos: BlockPos, val data: ByteArray) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ScreenUpdatePayload> = TYPE

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<ScreenUpdatePayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(KiBiComputers.MOD_ID, "screen_update"))

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, ScreenUpdatePayload> = StreamCodec.composite(
            ResourceKey.streamCodec(Registries.DIMENSION), ScreenUpdatePayload::dimension,
            BlockPos.STREAM_CODEC, ScreenUpdatePayload::pos,
            ByteBufCodecs.BYTE_ARRAY, ScreenUpdatePayload::data,
            ::ScreenUpdatePayload,
        )
    }
}

/**
 * クライアントからサーバーへの、「画面全体を送り直してほしい」というお願い（#140、#359）。
 * 受け取ったデータが壊れていたときに送る。画面はプレイヤーのいるディメンションのもの。
 *
 * 誰に画面を送るかはサーバーが距離から決めるので、これはお願いにすぎない。サーバーは、その画面を見ている人の
 * お願いだけを受け付け、送り直しはプレイヤーと画面の組ごとに一定の間隔に1回までにする。
 */
data class ScreenResyncPayload(val pos: BlockPos) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ScreenResyncPayload> = TYPE

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<ScreenResyncPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(KiBiComputers.MOD_ID, "screen_resync"))

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, ScreenResyncPayload> =
            BlockPos.STREAM_CODEC.map(::ScreenResyncPayload, ScreenResyncPayload::pos)
    }
}
