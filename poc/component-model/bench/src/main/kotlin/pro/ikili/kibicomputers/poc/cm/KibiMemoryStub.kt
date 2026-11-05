package pro.ikili.kibicomputers.poc.cm

import run.endive.runtime.HostFunction
import run.endive.wasm.types.FunctionType
import run.endive.wasm.types.ValType

/**
 * 同梱の lua.wasm のアロケーター（#409）が呼ぶ独自拡張 kibi:memory（#408）の、いつも「足りる」と答える代わり（#415）。
 * この PoC には RAM の上限がないので、残りは最大、commit は必ず成功にする。ほかの PoC（fuel-metering など）と同じ形。
 */
object KibiMemoryStub {
    /** A（p1 を直接動かす）で足すホスト関数 */
    fun hostFunctions(): List<HostFunction> = listOf(
        HostFunction("kibi", "memory_remaining", FunctionType.of(listOf(), listOf(ValType.I64))) { _, _ -> longArrayOf(Long.MAX_VALUE) },
        HostFunction("kibi", "memory_commit", FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I64), listOf(ValType.I32))) { _, _ -> longArrayOf(1) },
    )

    /**
     * B（アダプタで包む）で、`kibi` モジュールの import を満たすアダプタの WAT。
     * `component new --adapt kibi=...` に渡すと、コンポーネントの中でこのコアモジュールが import を満たすので、
     * コンポーネントの外（p2 のホスト）には何も増えない。
     */
    const val ADAPTER_WAT: String = """
        (module
          (func (export "memory_remaining") (result i64) (i64.const 0x7fffffffffffffff))
          (func (export "memory_commit") (param i32 i32 i64) (result i32) (i32.const 1)))
    """
}
