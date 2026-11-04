package pro.ikili.kibicomputers.core.wasi.p1

import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import pro.ikili.kibicomputers.core.wasi.kibi.KibiMemory

/**
 * 独自拡張（`kibi:*` の WIT）を p1 のプロセスに import として出す層（AGENTS.md の方針。#408）。モジュール名は [MODULE]。
 *
 * - `memory_remaining() -> i64`: `kibi:memory` の `remaining`（u64 をそのまま i64 にしたもの）
 * - `memory_commit(ptr: i32, len: i32, keep: i64) -> i32`: `kibi:memory` の `commit`。`ptr`・`len` は符号なし 32 ビット、
 *   `keep` は符号なし 64 ビット。成功なら 1、失敗なら 0
 *
 * [MODULE] のほかの名前（燃料の `fuel`、待機点への変換の `async_*` などカーネルの予約名）は、ここに出さない（[WasiP1.checkImports] が断る）。
 * どれも待たないので、待機点への変換で止まりうる関数（[WasiP1.WAIT_IMPORTS]）には入れない。
 * 燃料: 呼び出しごとに [WasiP1Fd.CALL_FUEL]（仮の値。確定は #251）。
 */
internal object WasiP1Kibi {
    /** 独自拡張の import のモジュール名 */
    const val MODULE: String = "kibi"

    private val I32 = WasmValueType.I32
    private val I64 = WasmValueType.I64

    /** 関数1つ: 名前、型、本体 */
    private class Fn(val name: String, val type: WasmFunctionType, val body: (ProcessContext, WasmInstance, LongArray) -> LongArray)

    private val FUNCTIONS: List<Fn> = listOf(
        Fn("memory_remaining", WasmFunctionType.of(emptyList(), listOf(I64))) { ctx, _, _ ->
            longArrayOf(KibiMemory.remaining(ctx))
        },
        Fn("memory_commit", WasmFunctionType.of(listOf(I32, I32, I64), listOf(I32))) { ctx, caller, a ->
            val memory = caller.memory
            val ok = memory != null && KibiMemory.commit(ctx, memory, a[0].toInt(), a[1].toInt(), a[2])
            longArrayOf(if (ok) 1L else 0L)
        },
    )

    /** 出している関数 [name] の型（なければ `null`） */
    fun typeOf(name: String): WasmFunctionType? = FUNCTIONS.firstOrNull { it.name == name }?.type

    /** [MODULE] の関数を [builder] に登録する（`ProcessDriver.configure` の中から。[WasiP1.register] が呼ぶ） */
    fun register(ctx: ProcessContext, builder: WasmInstanceConfig.Builder) {
        for (fn in FUNCTIONS) {
            builder.hostFunction(
                MODULE,
                fn.name,
                fn.type,
                ctx.hostFunction { c, caller, args ->
                    c.chargeFuel(WasiP1Fd.CALL_FUEL)
                    fn.body(c, caller, args)
                },
            )
        }
    }
}
