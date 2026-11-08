package pro.ikili.kibicomputers.core.device

import pro.ikili.kibicomputers.api.device.DeviceException
import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.kernel.vfs.Errno

/** デバイスの呼び出しの失敗（#65。WIT の `kibi:device/types` の `call-error`）。保存の対象（待っている呼び出しの結果）。 */
internal sealed class CallError {
    /** カーネルの失敗（上限など。`kernel(error-code)`） */
    data class Kernel(val errno: Errno) : CallError()

    /** デバイスが外れた（`detached`） */
    data object Detached : CallError()

    /** そのメソッドがない（`no-such-method`） */
    data object NoSuchMethod : CallError()

    /** 引数の形が違う（`bad-arguments`） */
    data class BadArguments(val message: String) : CallError()

    /** デバイスが返した失敗（`failed`） */
    data class Failed(val message: String) : CallError()

    companion object {
        /** 実行中に止められた・保存して戻した呼び出しの失敗 */
        val INTERRUPTED: CallError = Failed("interrupted")

        /** 作者の例外でない例外 */
        val INTERNAL_ERROR: CallError = Failed("internal error")
    }
}

/** [CallError] で終わった呼び出し。呼び出しの future の失敗に使う */
internal class DeviceCallException(val error: CallError) : Exception(error.toString(), null, false, false)

/** 呼び出しの結果（値の並びか、失敗）。保存の対象 */
internal sealed class CallResult {
    /** 成功。結果の値の並び */
    data class Values(val values: List<DeviceValue>) : CallResult()

    /** 失敗 */
    data class Error(val error: CallError) : CallResult()
}

/**
 * [pro.ikili.kibicomputers.api.device.DeviceArguments] の取り出しが投げる失敗（引数の形の誤り）。
 * カーネルが `bad-arguments(message)` に変える
 */
internal class BadArgumentsException(message: String) : DeviceException(message)
