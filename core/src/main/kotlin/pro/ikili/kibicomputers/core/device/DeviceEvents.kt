package pro.ikili.kibicomputers.core.device

/**
 * デバイスの接続・切断のイベント（#67、ADR 0067）。[DeviceBus] が、成功した [DeviceBus.attach]・[DeviceBus.detach] ごとに1件出す（同報）。
 *
 * 形は `event { kind: "device-attached" | "device-detached", source: "", values: [text(address), text(種類)] }`。
 * 種類はつないだときに検査した値（`AttachedDevice.kind`）で、切断でも同じ値を載せる。表の世代（[DeviceBus.generation]）は載せない。
 *
 * - 出さない: 上限で断った `attach`、`onAttached` の例外、形の誤り・address の重複、つないでいない `detach`。
 *   `onAttached` の中で自分を外したデバイスは、接続も切断も出さない（知らせていないものの切断は出さない）
 * - 同じ address の接続と切断は、起きた順に並ぶ（[DeviceBus] の書き込みのロックの中で出す）
 * - デバイスも同じ種類名を出せる（source がアドレス）ので、受け手は source が空かどうかで見分ける（ADR 0087 と同じ）
 */
internal object DeviceEvents {
    /** 接続の種類の名前 */
    const val ATTACHED: String = "device-attached"

    /** 切断の種類の名前 */
    const val DETACHED: String = "device-detached"

    /** 出したもの（カーネル） */
    const val SOURCE: String = ""
}
