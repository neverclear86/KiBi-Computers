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
 * クライアントからサーバーへの、画面を見はじめた・見るのをやめたという知らせ（#140）。
 * 見はじめると（[watch] が true）、サーバーはその tick の終わりに画面全体（キーフレーム）を送り、以後は描画命令を送る。
 * 画面はプレイヤーのいるディメンションのもの。
 */
data class ScreenWatchPayload(val pos: BlockPos, val watch: Boolean) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ScreenWatchPayload> = TYPE

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<ScreenWatchPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(KiBiComputers.MOD_ID, "screen_watch"))

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, ScreenWatchPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ScreenWatchPayload::pos,
            ByteBufCodecs.BOOL, ScreenWatchPayload::watch,
            ::ScreenWatchPayload,
        )
    }
}
