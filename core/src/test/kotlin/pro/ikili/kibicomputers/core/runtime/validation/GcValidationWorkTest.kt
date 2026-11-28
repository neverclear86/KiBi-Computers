package pro.ikili.kibicomputers.core.runtime.validation

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.wasi.kibi.WitTool

/**
 * GC の命令の検証の手間の上限（#1009）: struct のフィールドの数、`struct.new`・`struct.new_default`・`array.new_fixed` の手間の合計、
 * `array.new_fixed` 1 つの要素の数。どれも Endive の parse に入る前の [ModulePrescan] で断る
 */
class GcValidationWorkTest {
    private val runtime = EndiveRuntime()

    /** `%` を `$` にして WAT を WASM にする */
    private fun wat(source: String): ByteArray = WitTool.wat(source.replace('%', '$'))

    private fun rejects(wasm: ByteArray, limits: ModuleLimits): WasmLoadException =
        assertThrows<WasmLoadException> { ModulePrescan.check(wasm, limits, null) }

    private fun assertMessage(e: WasmLoadException, vararg parts: String) {
        val message = e.message!!
        assertTrue(message.startsWith("WASM の構造が上限を超える、または壊れている: "), message)
        for (part in parts) assertTrue(message.contains(part), message)
    }

    /** 10 フィールドの struct を、関数の本体で [count] 回 `struct.new_default`（[reachable] でなければ `unreachable` の後で）作る */
    private fun newDefault(count: Int, reachable: Boolean = true): ByteArray = wat(
        """
        (module
          (type %s (struct ${"(field i32) ".repeat(10)}))
          (func (export "f")
            ${if (reachable) "" else "unreachable"}
            ${"(drop (struct.new_default %s)) ".repeat(count)}))
        """.trimIndent(),
    )

    @Test
    fun `struct_new_defaultはフィールドの数の合計が上限ちょうどなら通り、超えれば断る`() {
        val limits = ModuleLimits(maxGcValidationWork = 30)
        for (reachable in listOf(true, false)) {
            assertDoesNotThrow { ModulePrescan.check(newDefault(3, reachable), limits, null) }
            assertMessage(rejects(newDefault(4, reachable), limits), "検証の手間", "上限 30")
        }
        // 既定の上限では通り、読み込める
        assertDoesNotThrow { Program.loadMetered(runtime, newDefault(4), ModuleLimits.DEFAULT) }
    }

    @Test
    fun `struct_newもフィールドの数で数える`() {
        val one = "(drop (struct.new %p (i32.const 1) (i64.const 2) (f32.const 3))) "
        fun module(count: Int) = wat(
            """
            (module
              (type %p (struct (field i32) (field (mut i64)) (field f32)))
              (func (export "f") ${one.repeat(count)}))
            """.trimIndent(),
        )
        val limits = ModuleLimits(maxGcValidationWork = 9)
        assertDoesNotThrow { ModulePrescan.check(module(3), limits, null) }
        assertMessage(rejects(module(4), limits), "検証の手間")
    }

    @Test
    fun `array_new_fixedは要素の数で数え、1つの要素の数にも上限がある`() {
        fun module(vararg lengths: Int) = wat(
            """
            (module
              (type %a (array (mut i32)))
              (func (export "f")
                unreachable
                ${lengths.joinToString(" ") { "(drop (array.new_fixed %a $it))" }}))
            """.trimIndent(),
        )
        val limits = ModuleLimits(maxArrayNewFixed = 5, maxGcValidationWork = 8)
        assertDoesNotThrow { ModulePrescan.check(module(5, 3), limits, null) }
        assertMessage(rejects(module(6), limits), "array.new_fixed の要素の数 6", "上限 5")
        assertMessage(rejects(module(5, 4), limits), "検証の手間", "上限 8")
    }

    @Test
    fun `定数式のstruct_newとarray_new_fixedも数える`() {
        fun module(fields: Int) = wat(
            """
            (module
              (type %s (struct ${"(field i32) ".repeat(fields)}))
              (type %a (array i32))
              (global (ref %s) (struct.new_default %s))
              (global (ref %a) (array.new_fixed %a 2 (i32.const 1) (i32.const 2))))
            """.trimIndent(),
        )
        val limits = ModuleLimits(maxGcValidationWork = 6)
        assertDoesNotThrow { ModulePrescan.check(module(4), limits, null) }
        assertMessage(rejects(module(5), limits), "検証の手間")
    }

    @Test
    fun `型の番号はrecグループの中の型を1つずつ数える`() {
        // %big は平らな番号で 2（rec グループの番号なら 1 で、1 フィールドの %one と取り違える）
        val wasm = wat(
            """
            (module
              (type %f (func))
              (rec (type %one (struct (field i32))) (type %big (struct (field i32) (field i32) (field i32) (field i32))))
              (func (export "f") (drop (struct.new_default %big))))
            """.trimIndent(),
        )
        assertDoesNotThrow { ModulePrescan.check(wasm, ModuleLimits(maxGcValidationWork = 4), null) }
        assertMessage(rejects(wasm, ModuleLimits(maxGcValidationWork = 3)), "検証の手間")
        // 型の数の上限は、rec グループの中の型も数える（グループは 2 つ、型は 3 つ）
        assertDoesNotThrow { ModulePrescan.check(wasm, ModuleLimits(maxTypes = 3), null) }
        assertMessage(rejects(wasm, ModuleLimits(maxTypes = 2)), "型の数", "上限 2")
    }

    @Test
    fun `structの型のフィールドの数は、型1つと全部の合計の上限で断る`() {
        val wasm = wat(
            """
            (module
              (type %a (struct ${"(field i32) ".repeat(4)}))
              (type %b (sub (struct ${"(field (mut i64)) ".repeat(3)}))))
            """.trimIndent(),
        )
        assertDoesNotThrow { ModulePrescan.check(wasm, ModuleLimits(maxStructFields = 4, maxTotalStructFields = 7), null) }
        assertMessage(rejects(wasm, ModuleLimits(maxStructFields = 3)), "struct の型 0 のフィールドの数 4", "上限 3")
        assertMessage(rejects(wasm, ModuleLimits(maxTotalStructFields = 6)), "フィールドの数の合計", "上限 6")
    }

    @Test
    fun `知らない形の型は断る`() {
        // 型のセクション: 型 1 つ、形 0x5D
        val wasm = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, 1, 2, 1, 0x5D)
        assertMessage(rejects(wasm, ModuleLimits.DEFAULT), "知らない型の形 0x5d")
    }

    @Test
    fun `燃料なしの読み込みでもEndiveに渡す前に断る`() {
        val limits = ModuleLimits(maxGcValidationWork = 30)
        assertMessage(assertThrows<WasmLoadException> { Program.load(runtime, newDefault(4), limits) }, "検証の手間")
        assertMessage(assertThrows<WasmLoadException> { Program.loadMetered(runtime, newDefault(4), limits) }, "検証の手間")
        assertDoesNotThrow { Program.load(runtime, newDefault(3), limits) }
        assertDoesNotThrow { Program.loadMetered(runtime, newDefault(3), limits) }
    }

    @Test
    fun `命令を読めないGCのモジュールは、構造の確認では通し、ランタイムの読み込みで断る`() {
        val wasm = newDefault(1)
        // struct.new_default（0xFB 0x01 と型の番号 0）の 2 バイト目を、知らない GC の命令（0x7F）にする
        val at = (0 until wasm.size - 2).single { wasm[it] == 0xFB.toByte() && wasm[it + 1] == 0x01.toByte() && wasm[it + 2] == 0x00.toByte() }
        val broken = wasm.copyOf().also { it[at + 1] = 0x7F }
        assertDoesNotThrow { ModulePrescan.check(broken, ModuleLimits(maxGcValidationWork = 0), null) }
        assertThrows<WasmLoadException> { Program.load(runtime, broken, ModuleLimits.DEFAULT) }
    }
}
