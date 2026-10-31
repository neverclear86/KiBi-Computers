package pro.ikili.kibicomputers.core.runtime

import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.I32
import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.I64
import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.code

/** ランタイムの契約のテスト（[WasmRuntimeContractTest]）で使う WASM。どのランタイムにも同じバイト列を渡す。 */
object TestModules {
    /** `add(i32, i32) -> i32` */
    val ADD: ByteArray = WasmBinary().run {
        exportFunc("add", func(listOf(I32, I32), listOf(I32), code(0x20, 0, 0x20, 1, 0x6a)))
        build()
    }

    /** import `env.twice(i32) -> i32`、export `run(x) = twice(x) + 1` */
    val HOST: ByteArray = WasmBinary().run {
        val twice = importFunc("env", "twice", listOf(I32), listOf(I32))
        exportFunc("run", func(listOf(I32), listOf(I32), code(0x20, 0, 0x10, twice, 0x41, 1, 0x6a)))
        build()
    }

    /** 1 ページのメモリ（16 番地に "hello"）。import `env.print(ptr, len)`、export `greet()`・`sum(ptr, len)`・`load(ptr)` */
    val MEMORY: ByteArray = WasmBinary().run {
        val print = importFunc("env", "print", listOf(I32, I32), emptyList())
        memory(1)
        data(16, "hello".toByteArray())
        exportFunc("greet", func(emptyList(), emptyList(), code(0x41, 16, 0x41, 5, 0x10, print)))
        val sum = code(
            0x02, 0x40,
            0x03, 0x40,
            0x20, 1, 0x45, 0x0d, 1,
            0x20, 2, 0x20, 0, 0x2d, 0, 0, 0x6a, 0x21, 2,
            0x20, 0, 0x41, 1, 0x6a, 0x21, 0,
            0x20, 1, 0x41, 1, 0x6b, 0x21, 1,
            0x0c, 0,
            0x0b,
            0x0b,
            0x20, 2,
        )
        exportFunc("sum", func(listOf(I32, I32), listOf(I32), sum, locals = listOf(I32)))
        exportFunc("load", func(listOf(I32), listOf(I32), code(0x20, 0, 0x2d, 0, 0)))
        build()
    }

    /** export `boom()`（unreachable）・`div(a, b)`（i32.div_s）・`recurse()`（終わらない再帰） */
    val TRAPS: ByteArray = WasmBinary().run {
        exportFunc("boom", func(emptyList(), emptyList(), code(0x00)))
        exportFunc("div", func(listOf(I32, I32), listOf(I32), code(0x20, 0, 0x20, 1, 0x6d)))
        // 自分自身の番号は 2（boom = 0, div = 1）
        exportFunc("recurse", func(emptyList(), emptyList(), code(0x10, 2)))
        build()
    }

    /**
     * 燃料の計測コードを挿入した形（#28）のモジュール。
     *
     * - `spin(n)`: n 回ループし、1 回ごとに燃料を 1 減らす。負になったら `kibi.fuel` を呼ぶ
     * - `charge()`: import `env.charge()` を呼ぶ（ホストの関数が燃料を課す）
     */
    val FUEL: ByteArray = WasmBinary().run {
        val fuel = importFunc(WasmFuel.IMPORT_MODULE, WasmFuel.IMPORT_NAME, listOf(I64), listOf(I64))
        val charge = importFunc("env", "charge", emptyList(), emptyList())
        val g = mutableI64Global(0, WasmFuel.EXPORT_GLOBAL)
        val spin = code(
            0x03, 0x40,
            0x23, g, 0x42, 1, 0x7d, 0x24, g,
            0x23, g, 0x42, 0, 0x53,
            0x04, 0x40, 0x23, g, 0x10, fuel, 0x24, g, 0x0b,
            0x20, 0, 0x41, 1, 0x6b, 0x22, 0, 0x0d, 0,
            0x0b,
        )
        exportFunc("spin", func(listOf(I32), emptyList(), spin))
        exportFunc("charge", func(emptyList(), emptyList(), code(0x10, charge)))
        build()
    }

    /** start 関数で import `env.started()` を呼ぶ */
    val START: ByteArray = WasmBinary().run {
        val started = importFunc("env", "started", emptyList(), emptyList())
        start(func(emptyList(), emptyList(), code(0x10, started)))
        build()
    }

    /** WASM でないバイト列 */
    val MALFORMED: ByteArray = "not a wasm module".toByteArray()

    val TWICE_TYPE: WasmFunctionType = WasmFunctionType.of(listOf(WasmValueType.I32), listOf(WasmValueType.I32))
    val PRINT_TYPE: WasmFunctionType = WasmFunctionType.of(listOf(WasmValueType.I32, WasmValueType.I32), emptyList())
}
