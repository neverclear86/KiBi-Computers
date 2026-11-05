package pro.ikili.kibicomputers.core.wasi.p1

import pro.ikili.kibicomputers.core.kernel.event.EventLimits
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmMemoryAccessException
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import pro.ikili.kibicomputers.core.wasi.kibi.KibiErrors
import pro.ikili.kibicomputers.core.wasi.kibi.KibiEvents
import pro.ikili.kibicomputers.core.wasi.kibi.KibiMemory

/**
 * 独自拡張（`kibi:*` の WIT。`core/src/main/wit/`）を p1 のプロセスに import として出す層（#408・#73。ADR 0073）。
 *
 * import は WIT から Canonical ABI の core の形そのままに導く（Q2-B）: モジュール名は `<ns>:<pkg>/<interface>@<版>`、
 * 関数名は wit-bindgen と同じ（`[static]`・`[method]`・`[resource-drop]`）。可変長の結果はゲストの `cabi_realloc` で場所を取る。
 * resource のハンドルはカーネルの fd 番号（Q3-A）。版は完全一致だけを受け付ける（Q5-A）。
 *
 * | モジュール | 名前 | core の型 |
 * | --- | --- | --- |
 * | [EVENTS] | `[static]event-queue.open` | `(kinds_ptr, kinds_len, cap_is_some, cap, ret_ptr: i32) -> ()` |
 * | [EVENTS] | `[method]event-queue.take` | `(self, max, ret_ptr: i32) -> ()`（`cabi_realloc` が要る） |
 * | [EVENTS] | `[resource-drop]event-queue` | `(self: i32) -> ()` |
 * | [MEMORY] | `remaining` | `() -> i64` |
 * | [MEMORY] | `commit` | `(ptr, len: i32, keep: i64) -> i32` |
 *
 * どれも待たないので、待機点への変換で止まりうる関数（[WasiP1.WAIT_IMPORTS]）には入れない。
 * 燃料（仮の値。確定は #251）: 呼び出しごとに [WasiP1Fd.CALL_FUEL]、`take` は加えて渡したイベントの数。
 */
internal object WasiP1Kibi {
    /** `kibi:kernel/events` のモジュール名 */
    const val EVENTS: String = "kibi:kernel/events@0.1.0"

    /** `kibi:computer/memory` のモジュール名 */
    const val MEMORY: String = "kibi:computer/memory@0.1.0"

    /** 独自拡張のモジュール名の始まり（これで始まるモジュールは [typeOf] の表で確かめる） */
    const val PREFIX: String = "kibi:"

    /** `cabi_realloc` の export の名前と型 */
    const val REALLOC: String = "cabi_realloc"
    val REALLOC_TYPE: WasmFunctionType = WasmFunctionType.of(
        listOf(WasmValueType.I32, WasmValueType.I32, WasmValueType.I32, WasmValueType.I32),
        listOf(WasmValueType.I32),
    )

    private val I32 = WasmValueType.I32
    private val I64 = WasmValueType.I64

    /** 関数1つ: モジュール名、名前、型、`cabi_realloc` が要るか、本体 */
    private class Fn(
        val module: String,
        val name: String,
        val type: WasmFunctionType,
        val needsRealloc: Boolean,
        val body: (ProcessContext, WasmInstance, LongArray) -> LongArray,
    )

    private val NONE = LongArray(0)

    private fun Long.i(): Int = toInt()

    private fun memoryOf(caller: WasmInstance): WasmMemory =
        caller.memory ?: throw WasmTrapException(WasmTrapKind.MEMORY_OUT_OF_BOUNDS, "線形メモリを export していない")

    private val FUNCTIONS: List<Fn> = listOf(
        Fn(EVENTS, "[static]event-queue.open", WasmFunctionType.of(listOf(I32, I32, I32, I32, I32), emptyList()), false) { ctx, caller, a ->
            val memory = memoryOf(caller)
            val fd = try {
                val kinds = P1Canonical.liftStringList(memory, a[0].i(), a[1].i(), EventLimits.MAX_FILTER_TYPES, 1..EventLimits.MAX_TYPE_BYTES)
                val capacity = P1Canonical.liftOptionU32(a[2].i(), a[3].i())
                KibiEvents.open(ctx, kinds, capacity)
            } catch (e: FsException) {
                P1Canonical.lowerHandleResult(memory, a[4].i(), null, KibiErrors.code(e))
                return@Fn NONE
            }
            P1Canonical.lowerHandleResult(memory, a[4].i(), fd, 0)
            NONE
        },
        Fn(EVENTS, "[method]event-queue.take", WasmFunctionType.of(listOf(I32, I32, I32), emptyList()), true) { ctx, caller, a ->
            val memory = memoryOf(caller)
            val batch = try {
                KibiEvents.take(ctx, a[0].i(), a[1] and 0xffff_ffffL)
            } catch (e: FsException) {
                P1Canonical.lowerBatchResult(memory, realloc(ctx, caller), a[2].i(), null, KibiErrors.code(e))
                return@Fn NONE
            }
            ctx.chargeFuel(batch.events.size.toLong())
            P1Canonical.lowerBatchResult(memory, realloc(ctx, caller), a[2].i(), batch, 0)
            NONE
        },
        Fn(EVENTS, "[resource-drop]event-queue", WasmFunctionType.of(listOf(I32), emptyList()), false) { ctx, _, a ->
            try {
                KibiEvents.drop(ctx, a[0].i())
            } catch (e: FsException) {
                // Component Model と同じく、不正なハンドルの drop はトラップ
                throw WasmTrapException(WasmTrapKind.OTHER, "[resource-drop]event-queue: 不正なハンドル ${a[0].i()}（${e.errno}）")
            }
            NONE
        },
        Fn(MEMORY, "remaining", WasmFunctionType.of(emptyList(), listOf(I64)), false) { ctx, _, _ ->
            longArrayOf(KibiMemory.remaining(ctx))
        },
        Fn(MEMORY, "commit", WasmFunctionType.of(listOf(I32, I32, I64), listOf(I32)), false) { ctx, caller, a ->
            val memory = caller.memory
            val ok = memory != null && KibiMemory.commit(ctx, memory, a[0].toInt(), a[1].toInt(), a[2])
            longArrayOf(if (ok) 1L else 0L)
        },
    )

    /** ゲストの `cabi_realloc(0, 0, align, size)` を、ゲストを呼んでいる印を立てて呼ぶ */
    private fun realloc(ctx: ProcessContext, caller: WasmInstance) = P1Canonical.Realloc { align, size ->
        ctx.callGuest { caller.call(REALLOC, 0L, 0L, align.toLong(), size.toLong())[0].toInt() }
    }

    /** 出しているモジュール名 */
    val modules: Set<String> = FUNCTIONS.map { it.module }.toSet()

    /** 出している関数 [module].[name] の型（なければ `null`） */
    fun typeOf(module: String, name: String): WasmFunctionType? = find(module, name)?.type

    /** [module].[name] が `cabi_realloc` を要するか（出していない関数なら `false`） */
    fun needsRealloc(module: String, name: String): Boolean = find(module, name)?.needsRealloc == true

    /** 出している関数の (モジュール名, 名前) の一覧（`KibiWitTableTest` が WIT と突き合わせる） */
    val functions: List<Pair<String, String>> get() = FUNCTIONS.map { it.module to it.name }

    private fun find(module: String, name: String): Fn? = FUNCTIONS.firstOrNull { it.module == module && it.name == name }

    /** 独自拡張の関数を [builder] に登録する（`ProcessDriver.configure` の中から。[WasiP1.register] が呼ぶ） */
    fun register(ctx: ProcessContext, builder: WasmInstanceConfig.Builder) {
        for (fn in FUNCTIONS) {
            builder.hostFunction(
                fn.module,
                fn.name,
                fn.type,
                ctx.hostFunction { c, caller, args ->
                    c.chargeFuel(WasiP1Fd.CALL_FUEL)
                    try {
                        fn.body(c, caller, args)
                    } catch (e: WasmMemoryAccessException) {
                        // 結果の書き先は確かめてから書くので、ここに来るのはゲストが realloc の中でメモリを縮めたときなど
                        throw WasmTrapException(WasmTrapKind.MEMORY_OUT_OF_BOUNDS, "${fn.module}.${fn.name}: 線形メモリの外に書こうとした", e)
                    }
                },
            )
        }
    }
}
