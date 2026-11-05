package pro.ikili.kibicomputers.poc.oslanguage

import run.endive.wasm.Parser

/** モジュールの形（#32）。関数の数、SIMD（v128）の命令を含む関数の数、最初の線形メモリのページ数 */
internal class ModuleShape(val functions: Int, val simdFunctions: Int, val initialPages: Int) {
    companion object {
        private val SIMD = Regex("V128|[IF](8|16|32|64)X(2|4|8|16)")

        fun of(bytes: ByteArray): ModuleShape {
            val module = Parser.parse(bytes)
            val code = module.codeSection()
            var simd = 0
            for (i in 0 until code.functionBodyCount()) {
                if (code.getFunctionBody(i).instructions().any { SIMD.containsMatchIn(it.opcode().name) }) simd++
            }
            val pages = module.memorySection().map { it.getMemory(0).limits().initialPages().toInt() }.orElse(0)
            return ModuleShape(code.functionBodyCount(), simd, pages)
        }
    }
}
