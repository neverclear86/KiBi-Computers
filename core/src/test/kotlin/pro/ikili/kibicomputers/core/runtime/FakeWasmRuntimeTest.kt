package pro.ikili.kibicomputers.core.runtime

import pro.ikili.kibicomputers.core.runtime.WasmValueType.I32

/** ダミーのランタイムが、Endive と同じ契約を満たすことを確かめる（#34 の「両方を差し込める」）。 */
class FakeWasmRuntimeTest : WasmRuntimeContractTest() {
    private val i32 = WasmFunctionType.of(listOf(I32), listOf(I32))
    private val i32i32 = WasmFunctionType.of(listOf(I32, I32), listOf(I32))
    private val unitI32 = WasmFunctionType.of(listOf(I32), emptyList())

    override val runtime: WasmRuntime = FakeWasmRuntime()
        .define(TestModules.ADD, FakeModule.builder().export("add", i32i32) { _, a -> longArrayOf((a[0].toInt() + a[1].toInt()).toLong()) }.build())
        .define(
            TestModules.HOST,
            FakeModule.builder()
                .import("env", "twice", TestModules.TWICE_TYPE)
                .export("run", i32) { c, a -> longArrayOf(c.callImport("env", "twice", a[0])[0] + 1) }
                .build(),
        )
        .define(
            TestModules.MEMORY,
            FakeModule.builder()
                .import("env", "print", TestModules.PRINT_TYPE)
                .memory(1)
                .data(16, "hello".toByteArray())
                .export("greet", WasmFunctionType.EMPTY) { c, _ -> c.callImport("env", "print", 16, 5) }
                .export("sum", i32i32) { c, a ->
                    var sum = 0
                    for (i in 0 until a[1].toInt()) sum += c.loadU8(a[0].toInt() + i)
                    longArrayOf(sum.toLong())
                }
                .export("load", i32) { c, a -> longArrayOf(c.loadU8(a[0].toInt()).toLong()) }
                .build(),
        )
        .define(
            TestModules.TRAPS,
            FakeModule.builder()
                .export("boom", WasmFunctionType.EMPTY) { c, _ -> c.trap(WasmTrapKind.UNREACHABLE) }
                .export("div", i32i32) { c, a ->
                    val x = a[0].toInt()
                    val y = a[1].toInt()
                    if (y == 0) c.trap(WasmTrapKind.INTEGER_DIVIDE_BY_ZERO)
                    if (x == Int.MIN_VALUE && y == -1) c.trap(WasmTrapKind.INTEGER_OVERFLOW)
                    longArrayOf((x / y).toLong())
                }
                .export("recurse", WasmFunctionType.EMPTY) { c, _ -> c.trap(WasmTrapKind.STACK_OVERFLOW) }
                .build(),
        )
        .define(
            TestModules.FUEL,
            FakeModule.builder()
                .fuelMetered()
                .import("env", "charge", WasmFunctionType.EMPTY)
                .export("spin", unitI32) { c, a ->
                    repeat(a[0].toInt()) { c.step() }
                    LongArray(0)
                }
                .export("charge", WasmFunctionType.EMPTY) { c, _ -> c.callImport("env", "charge") }
                .build(),
        )
        .define(
            TestModules.START,
            FakeModule.builder()
                .import("env", "started", WasmFunctionType.EMPTY)
                .start { c, _ -> c.callImport("env", "started") }
                .build(),
        )
        .define(
            TestModules.START_TRAP,
            FakeModule.builder()
                .start { c, _ -> c.trap(WasmTrapKind.UNREACHABLE) }
                .build(),
        )
}
