package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.kernel.ProcessKilledException
import pro.ikili.kibicomputers.core.kernel.ResourceLimitException
import pro.ikili.kibicomputers.core.runtime.WasmLinkException
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmMemoryAccessException
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind

/**
 * プロセスが例外で終わった理由を、コンソールに出す短い英語にする（#41）。
 *
 * - WASM のトラップは種類ごとの決まった文（例外のメッセージはランタイムの実装ごとに違うので使わない）
 * - JVM の例外（ホストの関数・ドライバーの不具合など）は `internal error (クラス名)`。メッセージは出さない（中身をプレイヤーに見せない）
 */
internal fun crashReason(t: Throwable): String = when (t) {
    is WasmTrapException -> trapReason(t.kind)
    is WasmLoadException -> "invalid module"
    is WasmLinkException -> "cannot instantiate module"
    is WasmMemoryAccessException -> "host call accessed memory out of bounds"
    is ResourceLimitException -> "resource limit exceeded"
    is ProcessKilledException -> "killed"
    is StackOverflowError -> trapReason(WasmTrapKind.STACK_OVERFLOW)
    is OutOfMemoryError -> "out of host memory"
    else -> "internal error (${t.javaClass.simpleName.ifEmpty { t.javaClass.name }})"
}

/** トラップの種類ごとの文（WebAssembly の仕様のテストの文に寄せる） */
internal fun trapReason(kind: WasmTrapKind): String = when (kind) {
    WasmTrapKind.UNREACHABLE -> "unreachable executed"
    WasmTrapKind.MEMORY_OUT_OF_BOUNDS -> "out of bounds memory access"
    WasmTrapKind.TABLE_OUT_OF_BOUNDS -> "out of bounds table access"
    WasmTrapKind.INTEGER_DIVIDE_BY_ZERO -> "integer divide by zero"
    WasmTrapKind.INTEGER_OVERFLOW -> "integer overflow"
    WasmTrapKind.INVALID_CONVERSION -> "invalid conversion to integer"
    WasmTrapKind.STACK_OVERFLOW -> "call stack exhausted"
    WasmTrapKind.INDIRECT_CALL -> "indirect call failed"
    WasmTrapKind.NULL_REFERENCE -> "null reference"
    WasmTrapKind.FUEL_EXHAUSTED -> "out of fuel"
    WasmTrapKind.UNCAUGHT_EXCEPTION -> "uncaught exception"
    WasmTrapKind.INTERRUPTED -> "interrupted"
    WasmTrapKind.OTHER -> "trap"
}
