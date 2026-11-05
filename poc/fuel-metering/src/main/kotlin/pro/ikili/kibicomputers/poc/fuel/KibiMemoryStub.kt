package pro.ikili.kibicomputers.poc.fuel

import run.endive.runtime.HostFunction
import run.endive.wasm.types.FunctionType
import run.endive.wasm.types.ValType

/**
 * 同梱の lua.wasm のアロケーター（#409）が呼ぶ独自拡張 kibi:memory（#408）の、いつも「足りる」と答える代わり。
 * この PoC には RAM の上限がないので、残りは最大、commit は必ず成功にする。
 */
fun kibiMemoryStubs(): List<HostFunction> = listOf(
    HostFunction("kibi", "memory_remaining", FunctionType.of(listOf(), listOf(ValType.I64))) { _, _ -> longArrayOf(Long.MAX_VALUE) },
    HostFunction("kibi", "memory_commit", FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I64), listOf(ValType.I32))) { _, _ -> longArrayOf(1) },
)
