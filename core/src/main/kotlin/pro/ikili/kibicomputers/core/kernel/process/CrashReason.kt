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

/**
 * RAM の上限を超えた書き込みでトラップしたときの理由（#407）。例: `out of memory (RAM limit 128 KiB)`。
 * [limitBytes] はプロセスが数えられる上限（プロセスの上限とマシンの RAM のうち小さい方）
 */
internal fun outOfMemoryReason(limitBytes: Long): String = "out of memory (RAM limit ${formatBytes(limitBytes)})"

/**
 * `memory.grow`（#407）や `kibi:computer/memory` の `commit`（#408）を RAM の上限で断った後に、プロセスが異常終了したときに、理由に添える文。
 * どちらも断っていなければ `null`。例:
 * - grow だけ: `memory.grow was refused: out of memory, RAM limit 128 KiB`
 * - commit だけ: `kibi:memory commit was refused: out of memory, RAM limit 128 KiB`
 * - 両方: `memory.grow and kibi:memory commit were refused: out of memory, RAM limit 128 KiB`
 */
internal fun memoryRefusedNote(growRefused: Boolean, commitRefused: Boolean, limitBytes: Long): String? {
    val what = when {
        growRefused && commitRefused -> "memory.grow and kibi:memory commit were refused"
        growRefused -> "memory.grow was refused"
        commitRefused -> "kibi:memory commit was refused"
        else -> return null
    }
    return "$what: out of memory, RAM limit ${formatBytes(limitBytes)}"
}

/** バイト数を、1 MiB の倍数なら MiB、1 KiB の倍数なら KiB、そうでなければバイトで書く */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= MIB && bytes % MIB == 0L -> "${bytes / MIB} MiB"
    bytes % KIB == 0L -> "${bytes / KIB} KiB"
    else -> "$bytes bytes"
}

private const val KIB = 1024L
private const val MIB = 1024L * 1024

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
    WasmTrapKind.OUT_OF_MEMORY -> "out of memory"
    WasmTrapKind.UNCAUGHT_EXCEPTION -> "uncaught exception"
    WasmTrapKind.INTERRUPTED -> "interrupted"
    WasmTrapKind.OTHER -> "trap"
}
