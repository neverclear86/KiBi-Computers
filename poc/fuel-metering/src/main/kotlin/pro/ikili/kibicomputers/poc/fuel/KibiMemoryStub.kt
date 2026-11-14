package pro.ikili.kibicomputers.poc.fuel

import run.endive.runtime.HostFunction
import run.endive.wasm.types.FunctionType
import run.endive.wasm.types.ValType

/**
 * 同梱の lua.wasm のアロケーター（#409）が呼ぶ独自拡張 kibi:computer/memory（#408・#73）の、いつも「足りる」と答える代わり。
 * この PoC には RAM の上限がないので、残りは最大、commit は必ず成功にする。Lua 向けの KiBi API（#94）の import のスタブも含める。
 */
fun kibiMemoryStubs(): List<HostFunction> = listOf(
    HostFunction("kibi:computer/memory@0.1.0", "remaining", FunctionType.of(listOf(), listOf(ValType.I64))) { _, _ -> longArrayOf(Long.MAX_VALUE) },
    HostFunction("kibi:computer/memory@0.1.0", "commit", FunctionType.of(listOf(ValType.I32, ValType.I32, ValType.I64), listOf(ValType.I32))) { _, _ -> longArrayOf(1) },
) + kibiLuaApiStubs()

/**
 * 同梱の lua.wasm の、Lua 向けの KiBi API（#94）が使う import の代わり。この PoC では呼ばないので、呼ばれたら落とす
 * （インスタンスを作るときに、すべての import を満たす必要があるため）
 */
fun kibiLuaApiStubs(): List<HostFunction> {
    val i32 = ValType.I32
    fun stub(module: String, name: String, params: List<ValType>, results: List<ValType> = listOf()) =
        HostFunction(module, name, FunctionType.of(params, results)) { _, _ -> throw UnsupportedOperationException("$module.$name") }
    return listOf(
        stub("kibi:kernel/events@0.1.0", "[static]event-queue.open", listOf(i32, i32, i32, i32, i32)),
        stub("kibi:kernel/events@0.1.0", "[method]event-queue.take", listOf(i32, i32, i32)),
        stub("kibi:device/bus@0.1.0", "list-devices", listOf(i32)),
        stub("kibi:device/bus@0.1.0", "[static]device.open", listOf(i32, i32, i32)),
        stub("kibi:device/bus@0.1.0", "[method]device.info", listOf(i32, i32)),
        stub("kibi:device/bus@0.1.0", "[method]device.methods", listOf(i32, i32)),
        stub("kibi:device/bus@0.1.0", "[method]device.invoke", listOf(i32, i32, i32, i32, i32, i32)),
        stub("kibi:device/bus@0.1.0", "[resource-drop]device", listOf(i32)),
        stub("kibi:computer/clock@0.1.0", "world-ticks", listOf(), listOf(ValType.I64)),
        stub("kibi:computer/power@0.1.0", "shutdown", listOf()),
        stub("kibi:computer/power@0.1.0", "reboot", listOf(i32)),
    )
}
