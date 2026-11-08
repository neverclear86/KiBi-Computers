package pro.ikili.kibicomputers.api.device

/**
 * デバイスのメソッド1つの処理（#65）。[DeviceMethods.Builder] で名前を付けて登録する。
 *
 * - `mainThread` で登録したものは、メインスレッドで呼ばれる（ワールドに触ってよい）
 * - `direct` で登録したものは、呼んだプロセスのスレッドで呼ばれる。スレッドの安全は作者が受け持つ
 *
 * 引数の形が違うときは、[DeviceArguments] の取り出しが投げる [DeviceException] をそのまま投げてよい（プロセスには
 * `bad-arguments` として届く）。作者が投げた [DeviceException] はプロセスに `failed(message)` として届く。
 * それ以外の例外は `failed("internal error")` になり、ログに出る（コンピューターは止めない）。
 */
public fun interface DeviceMethodHandler {
    /**
     * メソッドを実行し、結果の値の並びを返す。[DeviceCallContext.defer] で後から終えるときは、ここでは空の配列を返す
     * （返した値は使わない）
     *
     * @throws DeviceException 呼び出しの失敗
     */
    @Throws(DeviceException::class)
    public fun call(context: DeviceCallContext, args: DeviceArguments): Array<DeviceValue>
}
