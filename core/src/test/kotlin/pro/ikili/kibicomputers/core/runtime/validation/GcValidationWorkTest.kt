package pro.ikili.kibicomputers.core.runtime.validation

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.component.ComponentLimits
import pro.ikili.kibicomputers.core.runtime.component.ComponentLoader
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
    fun `要素セグメントの式のstruct_newとarray_new_fixedも数える`() {
        fun module(fields: Int) = wat(
            """
            (module
              (type %s (struct ${"(field i32) ".repeat(fields)}))
              (table 2 (ref null %s))
              (elem (table 0) (offset (i32.const 0)) (ref null %s) (item (struct.new_default %s)) (item (struct.new_default %s))))
            """.trimIndent(),
        )
        // 要素の式 2 つと、テーブルの初期値（式を書かないテーブルにも Endive が ref.null %s を補う）の 3 回
        val limits = ModuleLimits(maxGcValidationWork = 9)
        assertDoesNotThrow { ModulePrescan.check(module(3), limits, null) }
        assertMessage(rejects(module(4), limits), "検証の手間")
        // 要素の式が array.new_fixed のとき（1 つの要素の数の上限も、要素の式に効く）
        val arrays = wat(
            """
            (module
              (type %a (array i32))
              (table 1 (ref null %a))
              (elem (table 0) (offset (i32.const 0)) (ref null %a) (item (array.new_fixed %a 3 (i32.const 1) (i32.const 2) (i32.const 3)))))
            """.trimIndent(),
        )
        assertDoesNotThrow { ModulePrescan.check(arrays, ModuleLimits(maxArrayNewFixed = 3, maxGcValidationWork = 3), null) }
        assertMessage(rejects(arrays, ModuleLimits(maxArrayNewFixed = 2)), "array.new_fixed の要素の数 3", "上限 2")
        assertMessage(rejects(arrays, ModuleLimits(maxGcValidationWork = 2)), "検証の手間")
    }

    @Test
    fun `要素とデータのoffsetの式とテーブルの初期値も数える`() {
        // Endive の検証は、offset の式も初期値の式と同じに確かめる（型が合わなくても、手間は断る前にかかる）
        val twice = "(struct.new_default %s) (struct.new_default %s) (i32.const 0)"
        val elem = wat(
            """
            (module
              (type %s (struct ${"(field i32) ".repeat(5)}))
              (table 1 funcref)
              (elem (table 0) (offset $twice) func))
            """.trimIndent(),
        )
        val data = wat(
            """
            (module
              (type %s (struct ${"(field i32) ".repeat(5)}))
              (memory 1)
              (data (offset $twice) "x"))
            """.trimIndent(),
        )
        for (wasm in listOf(elem, data)) {
            assertDoesNotThrow { ModulePrescan.check(wasm, ModuleLimits(maxGcValidationWork = 10), null) }
            assertMessage(rejects(wasm, ModuleLimits(maxGcValidationWork = 9)), "検証の手間", "上限 9")
        }
        // 式を書かないテーブルの初期値（Endive が補う ref.null %s）
        val table = wat(
            """
            (module
              (type %s (struct ${"(field i32) ".repeat(5)}))
              (table 1 (ref null %s)) (table 1 (ref null %s)))
            """.trimIndent(),
        )
        assertDoesNotThrow { ModulePrescan.check(table, ModuleLimits(maxGcValidationWork = 10), null) }
        assertMessage(rejects(table, ModuleLimits(maxGcValidationWork = 9)), "検証の手間")
    }

    @Test
    fun `具体的なstructの型を使う命令は、その型のフィールドの数で数える`() {
        // %s は 5 フィールド、%t は 1 フィールド。ref.as_non_null などはスタックの型が分からないので、いちばん大きな %s の 5 で数える
        val cases = listOf(
            "(drop (ref.null %s))" to 5,
            "(drop (struct.get %s 0 (local.get 0)))" to 5,
            "(drop (struct.get_s %s 4 (local.get 0)))" to 5,
            "(struct.set %s 0 (local.get 0) (i32.const 1))" to 5,
            "(drop (ref.test (ref %s) (local.get 0)))" to 5,
            "(drop (ref.test (ref null %s) (local.get 0)))" to 5,
            "(drop (ref.cast (ref %s) (local.get 0)))" to 5,
            "(drop (ref.cast (ref null %s) (local.get 0)))" to 5,
            "(drop (select (result (ref null %s)) (local.get 0) (local.get 0) (i32.const 1)))" to 5,
            "(drop (block (result (ref %s)) (drop (br_on_cast 0 (ref null %s) (ref %s) (local.get 0))) unreachable))" to 15,
            "(drop (block (result (ref null %s)) (drop (br_on_cast_fail 0 (ref null %s) (ref %s) (local.get 0))) unreachable))" to 15,
            "(drop (ref.as_non_null (local.get 1)))" to 5,
            "(drop (block (br_on_null 0 (local.get 1))))" to 5,
            "(drop (block (result (ref %t)) (br_on_non_null 0 (local.get 1)) unreachable))" to 5,
            // array の型と抽象の型は数えない
            "(drop (ref.null %a)) (drop (ref.null any)) (drop (ref.test (ref %a) (local.get 2)))" to 0,
        )
        for ((instruction, units) in cases) {
            val wasm = wat(
                """
                (module
                  (type %s (struct ${"(field (mut i32)) ".repeat(4)}(field i8)))
                  (type %t (struct (field i32)))
                  (type %a (array i32))
                  (func (export "f") (param (ref null %s) (ref null %t) (ref null %a))
                    unreachable
                    $instruction $instruction))
                """.trimIndent(),
            )
            assertDoesNotThrow("$instruction は ${2 * units}") { ModulePrescan.check(wasm, ModuleLimits(maxGcValidationWork = 2L * units), null) }
            if (units > 0) assertMessage(rejects(wasm, ModuleLimits(maxGcValidationWork = 2L * units - 1)), "検証の手間")
        }
    }

    @Test
    fun `具体的なstructの型を使う命令の正しいモジュールは、既定の上限で読み込める`() {
        val wasm = wat(
            """
            (module
              (type %s (struct (field (mut i32)) (field i32)))
              (func (export "f") (param (ref null %s)) (result i32)
                (struct.set %s 0 (ref.cast (ref %s) (local.get 0)) (i32.const 1))
                (drop (ref.test (ref %s) (local.get 0)))
                (drop (ref.as_non_null (local.get 0)))
                (drop (ref.null %s))
                (struct.get %s 1 (local.get 0))))
            """.trimIndent(),
        )
        assertDoesNotThrow { Program.loadMetered(runtime, wasm, ModuleLimits.DEFAULT) }
        assertMessage(assertThrows<WasmLoadException> { Program.loadMetered(runtime, wasm, ModuleLimits(maxGcValidationWork = 9)) }, "検証の手間")
    }

    /** 型のセクション（1 フィールドの struct）と、[table] を中身にしたテーブルのセクションだけのモジュール */
    private fun tableModule(vararg table: Int): ByteArray {
        val header = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)
        val types = byteArrayOf(1, 0x5F, 1, 0x7F, 0x00)
        val tables = ByteArray(table.size) { table[it].toByte() }
        return header + byteArrayOf(1, types.size.toByte()) + types + byteArrayOf(4, tables.size.toByte()) + tables
    }

    @Test
    fun `初期値の式を持つテーブルは、先頭の最短でない符号化もEndiveと同じに読んで断る`() {
        // テーブル 1 つ、funcref、最小 0、最大なし
        assertDoesNotThrow { ModulePrescan.check(tableModule(1, 0x70, 0x00, 0x00), ModuleLimits.DEFAULT, null) }
        // 0x40 0x00 funcref 最小 0 (ref.null func)。0x40 を最短でない 0xC0 0x00 で書いても、Endive は初期値の式つきとして読む
        val expr = intArrayOf(0x00, 0x70, 0x00, 0x00, 0xD0, 0x70, 0x0B)
        assertMessage(rejects(tableModule(1, 0x40, *expr), ModuleLimits.DEFAULT), "初期値の式を持つテーブル")
        assertMessage(rejects(tableModule(1, 0xC0, 0x00, *expr), ModuleLimits.DEFAULT), "初期値の式を持つテーブル")
        // 型のコードの最短でない符号化（funcref を 0xF0 0x00）は、ふつうのテーブルとして読む
        assertDoesNotThrow { ModulePrescan.check(tableModule(1, 0xF0, 0x00, 0x00, 0x00), ModuleLimits.DEFAULT, null) }
    }

    @Test
    fun `テーブルのセクションに読み残しがあれば断る`() {
        assertMessage(rejects(tableModule(1, 0x70, 0x00, 0x00, 0x00), ModuleLimits.DEFAULT), "テーブルのセクションの大きさに合わない", "1 バイトが読み残される")
    }

    @Test
    fun `コンポーネントの中のcoreモジュールにもGCの検証の手間の上限が効く`() {
        val component = wat(
            """
            (component
              (core module %m
                (type %s (struct ${"(field i32) ".repeat(10)}))
                (func (export "f") ${"(drop (struct.new_default %s)) ".repeat(4)})))
            """.trimIndent(),
        )
        // コンポーネントの検証は、中の core モジュールの GC を（今は）断る。構造の確認（ModulePrescan）はその前なので、予算の側が先に効く
        val passed = assertThrows<WasmLoadException> {
            ComponentLoader.load(component, ComponentLimits(moduleLimits = ModuleLimits(maxGcValidationWork = 40)))
        }
        assertTrue(!passed.message!!.contains("検証の手間"), passed.message)
        val e = assertThrows<WasmLoadException> {
            ComponentLoader.load(component, ComponentLimits(moduleLimits = ModuleLimits(maxGcValidationWork = 39)))
        }
        assertMessage(e, "検証の手間", "上限 39")
        val tooWide = assertThrows<WasmLoadException> {
            ComponentLoader.load(component, ComponentLimits(moduleLimits = ModuleLimits(maxStructFields = 9)))
        }
        assertMessage(tooWide, "フィールドの数 10", "上限 9")
    }

    /**
     * 型のセクションを、Endive が受け付ける最短でない符号化で書いたモジュール: struct の型の形 `0x5F` を `0xDF 0x00`、フィールドの型 i32 と
     * 関数の型の引数の i32 を `0xFF 0x00` で書く（10 フィールド）。本体は `struct.new_default` を [count] 回。[trailing] は型のセクションの末尾に足す 0 のバイト数
     */
    private fun nonMinimalTypes(count: Int, trailing: Int = 0): ByteArray {
        val fields = 10
        val struct = byteArrayOf(0xDF.toByte(), 0x00, fields.toByte()) + ByteArray(fields * 3) { if (it % 3 == 0) 0xFF.toByte() else 0 }
        val func = byteArrayOf(0x60, 0x01, 0xFF.toByte(), 0x00, 0x00)
        val types = byteArrayOf(2) + struct + func + ByteArray(trailing)
        val newDefaults = (0 until count).flatMap { listOf(0xFB.toByte(), 0x01, 0x00, 0x1A) }.toByteArray()
        val body = byteArrayOf(0x00) + newDefaults + byteArrayOf(0x0B)
        val code = byteArrayOf(1, body.size.toByte()) + body
        fun section(id: Int, content: ByteArray) = byteArrayOf(id.toByte(), content.size.toByte()) + content
        val header = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)
        return header + section(1, types) + section(3, byteArrayOf(1, 1)) + section(10, code)
    }

    @Test
    fun `型のセクションの最短でない符号化を、Endiveと同じに読んで上限を当てる`() {
        // Endive はこの読み方で通す（struct の型は 10 フィールド）
        assertDoesNotThrow { Program.loadMetered(runtime, nonMinimalTypes(3), ModuleLimits.DEFAULT) }
        // 型の形・フィールドの型・関数の型の引数のどれも、読み間違えない（読み間違えると、型の並びがずれて上限が効かない）
        assertDoesNotThrow { ModulePrescan.check(nonMinimalTypes(3), ModuleLimits(maxGcValidationWork = 30), null) }
        assertMessage(rejects(nonMinimalTypes(4), ModuleLimits(maxGcValidationWork = 30)), "検証の手間", "上限 30")
        assertMessage(rejects(nonMinimalTypes(1), ModuleLimits(maxStructFields = 9)), "フィールドの数 10", "上限 9")
        assertMessage(
            assertThrows<WasmLoadException> { Program.loadMetered(runtime, nonMinimalTypes(4), ModuleLimits(maxGcValidationWork = 30)) },
            "検証の手間",
        )
    }

    @Test
    fun `型のセクションに読み残しがあれば断る`() {
        assertDoesNotThrow { ModulePrescan.check(nonMinimalTypes(1), ModuleLimits.DEFAULT, null) }
        assertMessage(rejects(nonMinimalTypes(1, trailing = 1), ModuleLimits.DEFAULT), "型のセクションの大きさに合わない", "1 バイトが読み残される")
    }

    @Test
    fun `型のセクションが2つあれば断る`() {
        val one = nonMinimalTypes(1)
        // ヘッダーのあとの型のセクション（id 1）を、もう 1 つ続ける
        val sectionLength = 2 + one[9].toInt()
        val typeSection = one.copyOfRange(8, 8 + sectionLength)
        val twice = one.copyOfRange(0, 8 + sectionLength) + typeSection + one.copyOfRange(8 + sectionLength, one.size)
        assertMessage(rejects(twice, ModuleLimits.DEFAULT), "型のセクションが 2 つある")
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
