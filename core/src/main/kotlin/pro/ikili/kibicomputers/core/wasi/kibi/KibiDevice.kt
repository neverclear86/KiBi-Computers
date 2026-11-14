package pro.ikili.kibicomputers.core.wasi.kibi

import pro.ikili.kibicomputers.core.device.CallError
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException

/**
 * 独自拡張 `kibi:device/bus@0.1.0`（#77。ADR 0077）の意味のうち、ABI の層（p1 の import、後の Component Model）に依らない部分。
 * 呼び出しそのものはカーネルの口（`ProcessContext` の `listDevices`・`openDevice`・`deviceInfo`・`deviceMethods`・`invokeDevice`・
 * `beginDeviceInvoke`・`startDeviceCall`・`takeDeviceCall`。#65・#66）が受け持つ。
 */
internal object KibiDevice {
    /** `list-devices`・`methods` で、呼び出しごとの燃料に加えて1件ごとに引く燃料（仮の値。確定は #251。`list-processes` の前例） */
    const val DEVICE_LIST_FUEL_PER_ENTRY: Long = 10

    /** address の長さ（小文字の UUID。ADR 0054）。ほかの長さの address は読まずに `detached` にする */
    const val ADDRESS_LENGTH: Int = 36

    /** カーネルの口が投げた [e] の `call-error`（`kernel(error-code)`） */
    fun kernelError(e: FsException): CallError = CallError.Kernel(e.errno)

    /** [errno] の `kernel(error-code)` */
    fun kernelError(errno: Errno): CallError = CallError.Kernel(errno)
}
