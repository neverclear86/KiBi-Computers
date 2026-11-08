package pro.ikili.kibicomputers.api.device

/**
 * デバイスのメソッドの呼び出しの失敗（#65）。[DeviceMethodHandler.call] から投げると、プロセスに `failed(message)` として届く。
 * [DeviceArguments] の取り出しが投げるもの（引数の形の誤り）は、`bad-arguments(message)` として届く。
 *
 * @param message プロセスに渡す説明
 */
public open class DeviceException(message: String) : Exception(message)
