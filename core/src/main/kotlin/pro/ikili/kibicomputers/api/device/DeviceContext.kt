package pro.ikili.kibicomputers.api.device

/** つないでいる間のデバイスとコンピューターのつながり（#65。[Device.onAttached] で渡す）。どのスレッドから使ってもよい。 */
public interface DeviceContext {
    /** このつながりでのデバイスの address */
    public val address: String

    /**
     * コンピューターにイベント [kind] を送る（値は [values]。待たない）。送り元はこのデバイスの address になる。
     * [DeviceValue.none] の値はイベントに載らない（飛ばす）。
     *
     * @param kind イベントの名前（1〜64 バイト）
     * @return 送ったら `true`。外れた後、名前や値が上限を超えた（名前 64 バイト・値 32 個・文字列とバイト列の合計 4 KiB）なら
     *   捨てて `false`
     */
    public fun postEvent(kind: String, vararg values: DeviceValue): Boolean
}
