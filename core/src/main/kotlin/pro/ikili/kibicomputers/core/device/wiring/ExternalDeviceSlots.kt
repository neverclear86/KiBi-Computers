package pro.ikili.kibicomputers.core.device.wiring

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.api.device.Device

/**
 * 他 MOD のデバイスのアダプター（[ExternalDeviceHolder]）を、位置ごとに登録表に出し入れする（#153、ADR 0153）。
 * 位置ごとに、そこを見張っている結線（[offer] した者）を数えるだけで、排他は登録表（実体の組）が受け持つ。
 *
 * - 結線は、隣の位置に能力のデバイスを見つけたら [offer]、なくなったら [release] する（どちらも、結線が前に見たものと
 *   変わったときだけ呼ぶ。同じ実体なら何もしない）
 * - 位置に1つ目の結線が来たらアダプターを載せ、最後の結線が去ったら外す（外し忘れで登録表が増え続けないように）
 * - 違う実体を出されたら、新しいアダプター（新しい世代）に載せ直す（前の実体はマシンから外れ、新しい実体がつながる）
 * - 内蔵のデバイス（画面・キーボード）が載っている位置には載せない（内蔵を優先する）。内蔵の登録は [release] でも外さない
 *
 * 他 MOD のコードは呼ばない（載せ直しで外れるデバイスの `onDetached` は、[MachineWiring] が受ける）。
 * スレッド: サーバーのメインスレッドからだけ使う。
 */
@ApiStatus.Internal
public class ExternalDeviceSlots<K : Any>(private val registry: DeviceRegistry<K>) {
    /** アダプターの作り方（接続層: address の置き場所を決める） */
    public fun interface Factory<K : Any> {
        /** 位置 [key] の [device] のアダプターを作る */
        public fun create(key: K, device: Device): ExternalDeviceHolder<K>
    }

    private class Slot<K : Any>(var holder: ExternalDeviceHolder<K>) {
        val watchers = ArrayList<Any>(1)
        var entry: DeviceRegistry.Entry<K>? = null
    }

    private val slots = HashMap<K, Slot<K>>()

    /** 見張られている位置の数（テスト・負荷試験用） */
    public val size: Int get() = slots.size

    /** [key] の位置の今のアダプター。なければ `null`（テスト用） */
    public fun holderAt(key: K): ExternalDeviceHolder<K>? = slots[key]?.holder

    /** [key] の位置を見張っている結線の数（テスト用） */
    public fun watcherCount(key: K): Int = slots[key]?.watchers?.size ?: 0

    /**
     * [watcher] が、位置 [key] に [device] を見つけた。初めての位置ならアダプターを作って載せる。[device] がその位置の今の実体と
     * 違えば、新しいアダプターに載せ直す。同じなら数えるだけ
     */
    public fun offer(key: K, watcher: Any, device: Device, factory: Factory<K>) {
        val slot = slots[key]
        if (slot == null) {
            val s = Slot(factory.create(key, device))
            s.watchers += watcher
            slots[key] = s
            place(key, s, previous = null)
            return
        }
        if (slot.watchers.none { it === watcher }) slot.watchers += watcher
        if (slot.holder.device !== device) {
            slot.holder.retire()
            val previous = slot.entry
            slot.holder = factory.create(key, device)
            place(key, slot, previous)
        } else if (slot.entry?.registered != true) {
            // 内蔵が去った後に、別の結線から同じ実体が届いた
            place(key, slot, previous = slot.entry)
        }
    }

    /** [watcher] が、位置 [key] を見なくなった（能力が消えた・結線が止まった）。最後の結線ならアダプターを外す */
    public fun release(key: K, watcher: Any) {
        val slot = slots[key] ?: return
        if (!slot.watchers.removeIf { it === watcher }) return
        if (slot.watchers.isNotEmpty()) return
        slots.remove(key)
        slot.holder.retire()
        // 今の登録が自分のものでなければ（内蔵に置き換わった）、何もしない
        slot.entry?.let(registry::unregister)
    }

    /** すべて忘れる（サーバーが止まったとき。登録表も一緒に消すので、外す知らせはしない） */
    public fun clear() {
        for (s in slots.values) s.holder.retire()
        slots.clear()
    }

    private fun place(key: K, slot: Slot<K>, previous: DeviceRegistry.Entry<K>?) {
        val current = registry[key]
        if (current != null && current !== previous) {
            // 内蔵が載っている。内蔵を優先する
            slot.entry = null
            return
        }
        slot.entry = registry.register(key, slot.holder)
    }
}
