package pro.ikili.kibicomputers.core.kernel

/**
 * プロセスが止められた（停止の印、割り込み）。プロセスのスレッドで投げ、そのプロセスを `KILLED` で終える。
 *
 * WASM の例外ではないので、WASM の中では捕まえられない（#34 の規約）。ABI 層も捕まえない。
 */
internal class ProcessKilledException(cause: Throwable? = null) : RuntimeException("プロセスが止められた", cause)

/** プロセスの数・スレッドの数などの上限を超えた。ABI 層がエラーに直す（#45）。 */
internal class ResourceLimitException(message: String) : RuntimeException(message)

/** カーネル自身の不整合。投げる前に、そのマシンを `CRASHED` にしてある。 */
internal class KernelInconsistencyException(message: String) : IllegalStateException(message)
