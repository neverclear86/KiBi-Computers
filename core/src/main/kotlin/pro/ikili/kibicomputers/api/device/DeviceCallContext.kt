package pro.ikili.kibicomputers.api.device

/** メソッドの呼び出し1回の情報（#65）。[DeviceMethodHandler.call] に渡す。呼び出しの間だけ使う。 */
public interface DeviceCallContext {
    /** 呼ばれたデバイスの address */
    public val address: String

    /** 呼ばれたメソッドの名前 */
    public val method: String

    /** メインスレッドで呼ばれている（`mainThread` で登録したメソッド）なら `true` */
    public val isMainThread: Boolean

    /**
     * 呼んだプロセスの燃料を、呼び出しの基本の費用に加えて [amount] だけ使う（重い処理の分）。
     * `direct` のメソッドでだけ効く（メインスレッドの処理はプロセスの燃料で払わない）。負の値は 0 とみなす
     */
    public fun consumeFuel(amount: Long)

    /**
     * 結果を後で返す（数 tick かかる処理など）。返した [DeviceCompletion] でいつか終える。
     * 終えるまでプロセスは待つ（[DeviceMethodHandler.call] の戻り値は使わない）。1回の呼び出しで1回だけ呼べる
     *
     * @throws IllegalStateException `direct` のメソッドから呼んだ、または2回目
     */
    public fun defer(): DeviceCompletion
}
