package pro.ikili.kibicomputers.api.device

/**
 * 後で返す呼び出しの結果（#65。[DeviceCallContext.defer]）。どのスレッドから呼んでもよい。
 * 最初の [complete] か [fail] だけが効き、2回目以降は何もしない。
 */
public interface DeviceCompletion {
    /** 結果 [values] で終える */
    public fun complete(vararg values: DeviceValue)

    /** 失敗 [message] で終える（プロセスには `failed(message)` として届く） */
    public fun fail(message: String)

    /**
     * 結果がもう要らない（コンピューターを止めた・待っていたプロセスが終わったなど）なら `true`。
     * 長い処理は、途中でこれを見てやめてよい（終えなくてよい）
     */
    public val isCancelled: Boolean
}
