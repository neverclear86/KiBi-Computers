package pro.ikili.kibicomputers.core.device.wiring

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.core.machine.isFatal

/**
 * 他 MOD のコード（デバイス・能力の provider・ブロックエンティティ）を呼んで受けた [t] が、JVM が続けられないもの
 * （`VirtualMachineError`。`StackOverflowError` を除く）なら投げ直す（#153、ADR 0153）。それ以外は呼び手が受けてログに出す
 */
@ApiStatus.Internal
public fun rethrowIfFatal(t: Throwable) {
    if (isFatal(t)) throw t
}
