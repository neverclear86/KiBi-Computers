package pro.ikili.kibicomputers.poc.cm

import run.endive.runtime.HostFunction
import run.endive.wasm.types.FunctionType
import run.endive.wasm.types.ValType

/**
 * 同梱の lua.wasm のアロケーター（#409）が呼ぶ独自拡張 kibi:computer/memory（#408・#73）の、いつも「足りる」と答える代わり（#415）。
 * この PoC には RAM の上限がないので、残りは最大、commit は必ず成功にする。ほかの PoC（fuel-metering など）と同じ形。
 */
object KibiMemoryStub {
    /** p1 向けの import のモジュール名（#73。ADR 0073） */
    const val MODULE: String = "kibi:computer/memory@0.1.0"

    /** A（p1 を直接動かす）で足すホスト関数 */
    fun hostFunctions(): List<HostFunction> = listOf(
        HostFunction(MODULE, "remaining", FunctionType.of(listOf(), listOf(ValType.I64))) { _, _ -> longArrayOf(Long.MAX_VALUE) },
        HostFunction(MODULE, "commit", FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I64), listOf(ValType.I32))) { _, _ -> longArrayOf(1) },
    )

    /**
     * B（アダプタで包む）で、[MODULE] モジュールの import を満たすアダプタの WAT。
     * `component new --adapt kibi:computer/memory@0.1.0=...` に渡すと、コンポーネントの中でこのコアモジュールが import を満たすので、
     * コンポーネントの外（p2 のホスト）には何も増えない。
     */
    const val ADAPTER_WAT: String = """
        (module
          (func (export "remaining") (result i64) (i64.const 0x7fffffffffffffff))
          (func (export "commit") (param i32 i32 i64) (result i32) (i32.const 1)))
    """
}
