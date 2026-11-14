package pro.ikili.kibicomputers.neoforge.computer

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import pro.ikili.kibicomputers.core.device.DeviceAddress

/**
 * ブロックエンティティが持つデバイスの address（#65、ADR 0065）。最初に使うときに作り（[onAssigned] でセーブデータに書く印を付ける）、
 * セーブデータの [key] に文字列で書く。読んだ値の形が正しくなければ捨て、次に使うときに作り直す。
 *
 * スレッド: サーバーのメインスレッドから使う。
 */
internal class DeviceAddressSlot(private val key: String, private val onAssigned: () -> Unit) {
    private var value: String? = null

    /** address。まだなければ作る */
    fun get(): String = value ?: DeviceAddress.random().also {
        value = it
        onAssigned()
    }

    /** 新しい address を振り直す（同じマシンに同じ address がもうつながっていたとき。#129）。セーブデータに書く印を付ける */
    fun reassign() {
        value = DeviceAddress.random()
        onAssigned()
    }

    /** もう作ってあれば [tag] に書く */
    fun save(tag: CompoundTag) {
        value?.let { tag.putString(key, it) }
    }

    /** [tag] から読む。ない・形が正しくなければ、まだないことにする */
    fun load(tag: CompoundTag) {
        value = if (tag.contains(key, Tag.TAG_STRING.toInt())) tag.getString(key).takeIf(DeviceAddress::isValid) else null
    }
}
