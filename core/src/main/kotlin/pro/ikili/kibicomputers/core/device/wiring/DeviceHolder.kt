package pro.ikili.kibicomputers.core.device.wiring

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.api.device.Device

/**
 * 位置から引けるデバイスの持ち主（画面・キーボードのブロックなど。#129、ADR 0129）。接続層のブロックエンティティが実装し、
 * [DeviceRegistry] に位置のキー [K] で載せる。マシンの結線（[MachineWiring]）は、使うたびに登録表から引き直し、持ち主の参照を
 * 持ち続けない。
 *
 * スレッド: どれもサーバーのメインスレッドから呼ぶ。
 */
@ApiStatus.Internal
public interface DeviceHolder<K : Any> {
    /** 今の address（まだなければ作る。#65） */
    public val deviceAddress: String

    /**
     * address を新しく振り直す（同じマシンに同じ address のデバイスがもうつながっていたとき。ブロックの複製など）。
     * 振り直した値はセーブデータに書く（印を付ける）
     */
    public fun reassignDeviceAddress()

    /** つなぐデバイスを作る。つなぐたびに新しく作る（満杯なら呼ばれない） */
    public fun createDevice(): Device

    /** [device] をマシンにつないだ（画面なら、その表示デバイスを映す） */
    public fun onConnected(device: Device) {}

    /**
     * [device] をマシンから外す（外す直前に呼ぶ。[device] はまだつながっている）。[removed] なら、持ち主が登録表から外れた
     * （壊れた・アンロードした）
     */
    public fun onDisconnected(device: Device, removed: Boolean) {}

    /**
     * このデバイスをつないだマシンが、続けて探す候補（画面の前のキーボードなど。決まった順）。中継で届いた候補は、
     * [relayable] なものだけつなぐ。中継は1段だけ（中継で届いたものの [relayKeys] は見ない）
     */
    public fun relayKeys(): List<K> = emptyList()

    /** 中継（ほかのデバイスの [relayKeys]）で届いたときもつなぐか（キーボードは `true`） */
    public val relayable: Boolean get() = false
}
