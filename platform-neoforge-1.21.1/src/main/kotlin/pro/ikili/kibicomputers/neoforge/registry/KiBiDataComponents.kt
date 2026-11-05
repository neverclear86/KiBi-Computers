package pro.ikili.kibicomputers.neoforge.registry

import com.mojang.serialization.Codec
import net.minecraft.core.component.DataComponentType
import net.minecraft.network.codec.ByteBufCodecs
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import pro.ikili.kibicomputers.core.KiBiComputers

/** アイテムのデータコンポーネントの登録。 */
object KiBiDataComponents {
    private val COMPONENTS = DeferredRegister.createDataComponents(KiBiComputers.MOD_ID)

    /**
     * 内蔵ディスクの識別子（`kibicomputers:disk_id`。#124、ADR 0124）。UUID の小文字の文字列。
     * なければ未割り当て（電源を入れたときに付ける）。セーブデータに書き、説明欄に出すためクライアントにも送る
     */
    val DISK_ID: DeferredHolder<DataComponentType<*>, DataComponentType<String>> =
        COMPONENTS.registerComponentType("disk_id") { builder ->
            builder.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8)
        }

    /** MOD のイベントバスに登録する。MOD の初期化で1回だけ呼ぶ。 */
    fun register(modBus: IEventBus) {
        COMPONENTS.register(modBus)
    }
}
