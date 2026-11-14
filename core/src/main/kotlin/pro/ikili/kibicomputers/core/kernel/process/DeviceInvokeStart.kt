package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.device.CallResult
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey

/** [ProcessContext.beginDeviceInvoke] の結果（#77、ADR 0077） */
internal sealed interface DeviceInvokeStart {
    /** その場で終わった（直接のメソッドなど）。fd も `pending-call` も作っていない */
    class Done(val result: CallResult) : DeviceInvokeStart

    /** メインスレッドのメソッドを積んだ。[callFd] は呼んだプロセスの fd 表に入れた `pending-call`（読む権利だけ）、[key] はその `READABLE` */
    class Waiting(val callFd: Int, val key: WaitKey) : DeviceInvokeStart
}
