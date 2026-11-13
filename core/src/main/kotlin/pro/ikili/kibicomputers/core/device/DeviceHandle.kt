package pro.ikili.kibicomputers.core.device

import pro.ikili.kibicomputers.core.kernel.obj.KernelObject

/**
 * プロセスが開いたデバイス（#65。WIT の `device` resource、`/dev/device/<address>` #54）。カーネルオブジェクト。
 * address と種類だけを持つ（デバイスの中身は持ち主が保存する）。外れた後もハンドルは残り、呼び出しは `detached` になる。
 */
internal class DeviceHandle(val address: String, val kind: String) : KernelObject() {
    /** `info` が返す値（変わらないので1回だけ作る） */
    val info: DeviceInfo = DeviceInfo(address, kind)

    /** 保存の写し（中身の形だけ。バイトの形は #81・#82） */
    data class Snapshot(val address: String, val kind: String)

    /** 保存の写しを取る */
    fun snapshot(): Snapshot = Snapshot(address, kind)

    override fun copyForSnapshot(): KernelObject = DeviceHandle(address, kind)

    override fun toString(): String = "DeviceHandle($id, $address, $kind)"

    companion object {
        /** 写しから戻す */
        fun restore(snapshot: Snapshot): DeviceHandle = DeviceHandle(snapshot.address, snapshot.kind)
    }
}
