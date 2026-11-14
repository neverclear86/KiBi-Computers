package pro.ikili.kibicomputers.neoforge.computer

import com.mojang.serialization.Codec
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.attachment.AttachmentType
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import net.neoforged.neoforge.registries.NeoForgeRegistries
import pro.ikili.kibicomputers.core.KiBiComputers

/** KiBi が他 MOD のブロックエンティティに付けるデータ添付（#153、ADR 0153）。 */
internal object DeviceAttachments {
    private val TYPES = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, KiBiComputers.MOD_ID)

    /**
     * 他 MOD のデバイス（能力 `kibicomputers:device`）の address（`kibicomputers:device_address`。小文字の UUID）。
     * 能力を出すブロックエンティティにだけ、最初につなぐときに付ける。ブロックエンティティの NBT（`neoforge:attachments`）に書かれる。
     * 形が正しくない値は捨てて振り直す
     */
    val DEVICE_ADDRESS: DeferredHolder<AttachmentType<*>, AttachmentType<String>> =
        TYPES.register("device_address") { -> AttachmentType.builder { -> "" }.serialize(Codec.STRING).build() }

    /** MOD のイベントバスに登録する。MOD の初期化で1回だけ呼ぶ。 */
    fun register(modBus: IEventBus) {
        TYPES.register(modBus)
    }
}
