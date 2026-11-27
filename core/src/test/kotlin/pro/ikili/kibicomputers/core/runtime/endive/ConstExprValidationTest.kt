package pro.ikili.kibicomputers.core.runtime.endive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.wasi.kibi.WitTool
import run.endive.wasm.Parser

/**
 * グローバルの初期値・表の初期値・アクティブの要素（オフセットと項目）・データのオフセットの定数式を、読み込みで確かめる（#942）。
 *
 * Endive 1.2.0 の検証（`Validator.validateConstantExpression`）は式の結果の型しか確かめず、命令が取る値の型を確かめないので、
 * `(global (ref $SF) (struct.new $SF (i32.const -5)))`（`$SF` は funcref のフィールド 1 つの struct）が通り、`struct.get` → `table.set` →
 * `call_indirect` で、インタプリタでは `ArrayIndexOutOfBoundsException` がそのまま漏れた（コンパイルありでは「indirect call type mismatch」の
 * トラップ。`(i32.const 0)` なら関数 0 が呼べた）。パッシブ・宣言の要素は `PassiveElementValidationTest`（#893）が見る
 */
class ConstExprValidationTest {
    private val head = """
        (type ${'$'}t (func (result i32)))
        (type ${'$'}S (struct (field i32)))
        (type ${'$'}P (struct (field i8) (field (mut i16))))
        (type ${'$'}A (array i32))
        (type ${'$'}SF (struct (field funcref)))
        (type ${'$'}NN (struct (field (ref ${'$'}S))))
        (table ${'$'}tb 4 funcref)
        (table ${'$'}ts 2 (ref null ${'$'}SF))
        (memory 1)
        (func ${'$'}f (result i32) i32.const 10)
        (func (export "ci") (param i32) (result i32) (call_indirect ${'$'}tb (type ${'$'}t) (local.get 0)))
    """

    private fun module(body: String) = WitTool.wat("(module $head $body)")

    /**
     * 読み込みのすべての経路（計測あり・待機点への変換ありを含む）で、正しくない WASM として、[where] の場所を付けて断る。
     * [meteredRejectedBefore] は、計測ありの経路で、この判定より先に挿入が断るときの文面の一部（初期値の式を持つ表・グローバルの import。
     * `FuelInjector` が対応していない）。
     * そのときは、計測ありの経路でこの判定が効くことは確かめられないので、先に断る文面であることだけを確かめる
     */
    private fun assertRejectedEverywhere(name: String, bytes: ByteArray, where: String, meteredRejectedBefore: String? = null) {
        assertTrue(WitTool.validate(bytes) != null, "テストの入力が正しくない WASM になっている（$name）")
        // Endive の検証は通す（Endive の穴。直ったらここが落ちるので、判定を外す）
        val parsed = Parser.parse(bytes)
        val problem = EndiveRuntime.invalidConstExpr(parsed)
        assertTrue(problem != null && problem.startsWith(where), "$name: $problem")
        for (runtime in listOf(EndiveRuntime(false), EndiveRuntime())) {
            for ((path, load) in listOf<Pair<String, () -> Any>>(
                "load" to { runtime.load(bytes) },
                "describe" to { runtime.describe(bytes) },
                "Program.load" to { Program.load(runtime, bytes) },
            )) {
                val e = assertThrows<WasmLoadException>("$name: $path") { load() }
                assertEquals("WASM の検証に通らない: $problem", e.message, "$name: $path")
                assertNull(e.cause, "$name: $path")
            }
            for (waits in listOf(emptySet(), setOf("w.wait"))) {
                val e = assertThrows<WasmLoadException>("$name: loadMetered $waits") { Program.loadMetered(runtime, bytes, null, waits) }
                assertNull(Program.unsuspendableReason(e), "$name: loadMetered $waits: ${e.message}")
                // 挿入・変換を通ってから、最後の読み込みでこの判定が断る（挿入で番号が変わりうるので、理由までは比べない）
                val message = e.message!!
                val ok = if (meteredRejectedBefore == null) {
                    message.startsWith("WASM の検証に通らない: $where")
                } else {
                    message.contains(meteredRejectedBefore)
                }
                assertTrue(ok, "$name: loadMetered $waits: $message")
            }
        }
    }

    @Test
    fun `グローバルの初期値で取る値の型が合わない式を断る`() {
        val globals = listOf(
            // Issue の再現
            "(global (ref \$SF) (struct.new \$SF (i32.const -5)))",
            "(global i32 (i32.add (i64.const 1) (i64.const 2)))",
            "(global i64 (i64.sub (i64.const 1) (i32.const 2)))",
            "(global (ref i31) (ref.i31 (i64.const 0)))",
            "(global (ref \$S) (struct.new \$S (f32.const 1)))",
            "(global (ref \$A) (array.new \$A (i32.const 0) (i64.const 3)))",
            "(global (ref \$A) (array.new_fixed \$A 2 (i32.const 1) (f32.const 1)))",
            "(global (ref \$A) (array.new_default \$A (i64.const 3)))",
            "(global (ref \$NN) (struct.new_default \$NN))",
            "(global anyref (any.convert_extern (ref.null func)))",
        )
        for (g in globals) assertRejectedEverywhere(g, module(g), "グローバル 0 の初期値: ")
        // 2 つ目のグローバル（番号はセクションの中の番号）
        assertRejectedEverywhere(
            "2 つ目",
            module("(global i32 (i32.const 0)) (global i64 (i64.add (i32.const 1) (i64.const 2)))"),
            "グローバル 1 の初期値: ",
        )
    }

    @Test
    fun `表の初期値で取る値の型が合わない式を断る`() {
        assertRejectedEverywhere(
            "struct.new の型違い",
            module("(table 1 (ref null \$SF) (struct.new \$SF (i32.const -5)))"),
            "表 2 の初期値: ",
            TABLE_INIT_UNSUPPORTED,
        )
        assertRejectedEverywhere(
            "ref.i31 の型違い",
            module("(table 1 i31ref (ref.i31 (i64.const 0)))"),
            "表 2 の初期値: ",
            TABLE_INIT_UNSUPPORTED,
        )
    }

    @Test
    fun `表の初期値は定義したグローバルを読めない`() {
        // 仕様（と wasm-tools）では、表の初期値から見えるのは import したグローバルだけ。Endive の検証は全グローバルで通すが、
        // インスタンスを作るときに表の初期値をグローバルの初期化より前に評価するので、読み込みで断る
        val bytes = module("(global \$gd funcref (ref.func \$f)) (table 1 funcref (global.get \$gd))")
        assertRejectedEverywhere("定義したグローバル", bytes, "表 2 の初期値: ", TABLE_INIT_UNSUPPORTED)
        assertEquals(
            "WASM の検証に通らない: 表 2 の初期値: global.get が読むグローバル 0（import を数えた番号）はここでは読めない（import したものだけ）",
            assertThrows<WasmLoadException> { EndiveRuntime().load(bytes) }.message,
        )
    }

    @Test
    fun `グローバルを import していると、読めない理由の番号は import を数えた番号になる`() {
        // import が 2 つあるので、定義した 0 番のグローバルは global.get では 2 番。場所（表 0 の初期値）の番号とは別に書く
        val bytes = WitTool.wat(
            """
            (module
              (import "m" "a" (global i32))
              (import "m" "b" (global funcref))
              (func ${'$'}f)
              (global ${'$'}gd funcref (ref.func ${'$'}f))
              (table 1 funcref (global.get ${'$'}gd)))
            """,
        )
        assertRejectedEverywhere("import のあるモジュール", bytes, "表 0 の初期値: ", GLOBAL_IMPORT_UNSUPPORTED)
        assertEquals(
            "WASM の検証に通らない: 表 0 の初期値: global.get が読むグローバル 2（import を数えた番号）はここでは読めない（import したものだけ）",
            assertThrows<WasmLoadException> { EndiveRuntime().load(bytes) }.message,
        )
    }

    @Test
    fun `アクティブの要素の項目は表の型でなく要素の型で確かめる`() {
        // 項目の型（null を持てる funcref）は表の型（funcref）に合うので Endive の検証を通るが、要素の型（null を持てない func 参照）には合わない
        val bytes = module("(elem (table \$tb) (i32.const 0) (ref func) (ref.null func))")
        assertRejectedEverywhere("表の型には合うが要素の型には合わない", bytes, "要素セグメント 0（アクティブ）の項目 0: ")
        val e = assertThrows<WasmLoadException> { EndiveRuntime().load(bytes) }
        assertTrue(e.message!!.contains("が要素の型（"), e.message)
        // 要素の型が合えば通る
        EndiveRuntime(false).load(module("(elem (table \$tb) (i32.const 0) funcref (ref.null func))"))
    }

    @Test
    fun `アクティブの要素のオフセットと項目で取る値の型が合わない式を断る`() {
        assertRejectedEverywhere(
            "オフセット",
            module("(elem (table \$tb) (offset (i32.add (i64.const 0) (i64.const 0))) func \$f)"),
            "要素セグメント 0（アクティブ）のオフセット: ",
        )
        assertRejectedEverywhere(
            "式のオフセット",
            module("(elem (table \$tb) (offset (i32.mul (i32.const 0) (i64.const 0))) funcref (ref.func \$f))"),
            "要素セグメント 0（アクティブ）のオフセット: ",
        )
        assertRejectedEverywhere(
            "項目",
            module("(elem (table \$ts) (i32.const 0) (ref null \$SF) (struct.new \$SF (i32.const -5)))"),
            "要素セグメント 0（アクティブ）の項目 0: ",
        )
        // 2 つ目の項目と、2 つ目の要素セグメント
        assertRejectedEverywhere(
            "2 つ目",
            module(
                "(elem (table \$tb) (i32.const 0) func \$f) " +
                    "(elem (table \$ts) (i32.const 0) (ref null \$SF) (ref.null \$SF) (struct.new \$SF (i32.const 0)))",
            ),
            "要素セグメント 1（アクティブ）の項目 1: ",
        )
    }

    @Test
    fun `データのオフセットで取る値の型が合わない式を断る`() {
        assertRejectedEverywhere("データ", module("(data (i32.add (i64.const 0) (i64.const 0)) \"a\")"), "データセグメント 0 のオフセット: ")
        assertRejectedEverywhere(
            "2 つ目のデータ",
            module("(data (i32.const 0) \"a\") (data (i32.sub (i32.const 8) (f64.const 0)) \"b\")"),
            "データセグメント 1 のオフセット: ",
        )
    }

    @Test
    fun `文面に場所と理由を出す`() {
        // グローバル・表・オフセットの結果の型の呼び名（グローバルの型・表の要素の型・オフセットの型）は、Endive が先に同じ型の違いを断るので、
        // load からは届かない。読み込みから見える文面は、場所と、命令が取る値の型などの理由
        val e = assertThrows<WasmLoadException> { EndiveRuntime().load(module("(global i32 (i32.add (i64.const 1) (i64.const 2)))")) }
        assertTrue(e.message!!.startsWith("WASM の検証に通らない: グローバル 0 の初期値: 命令 I32_ADD が取る値の型"), e.message)
        // パッシブの要素の文面（#893）は変えない
        val p = assertThrows<WasmLoadException> { EndiveRuntime().load(module("(elem funcref (item (ref.null func) (ref.func 99)))")) }
        assertEquals("WASM の検証に通らない: 要素セグメント 0（パッシブ）の項目 0: 関数 99 がない", p.message)
    }

    @Test
    fun `後ろのグローバルを読む初期値は断る`() {
        // Endive の検証が断る（こちらの判定には届かない）
        val bytes = module("(global i32 (global.get 1)) (global i32 (i32.const 1))")
        assertTrue(WitTool.validate(bytes) != null)
        for (runtime in listOf(EndiveRuntime(false), EndiveRuntime())) {
            val e = assertThrows<WasmLoadException> { runtime.load(bytes) }
            assertTrue(e.message!!.startsWith("WASM の検証に通らない: "), e.message)
        }
    }

    @Test
    fun `正しい式は断らずに正しい値になる`() {
        val bytes = module(
            """
            (global ${'$'}p i32 (i32.const 1))
            (global ${'$'}q i32 (i32.add (global.get ${'$'}p) (i32.const 1)))
            (global ${'$'}w i64 (i64.mul (i64.const 3) (i64.sub (i64.const 5) (i64.const 1))))
            (global ${'$'}gp (ref ${'$'}P) (struct.new ${'$'}P (i32.const 1) (i32.const 2)))
            (global ${'$'}ga (ref ${'$'}A) (array.new ${'$'}A (i32.const 7) (i32.const 3)))
            (global ${'$'}gx externref (extern.convert_any (ref.i31 (i32.const 0))))
            (global ${'$'}gs (ref null ${'$'}SF) (struct.new ${'$'}SF (ref.func ${'$'}f)))
            (elem (table ${'$'}tb) (offset (i32.add (global.get ${'$'}p) (i32.const 1))) func ${'$'}f)
            (elem (table ${'$'}ts) (i32.const 0) (ref null ${'$'}SF) (struct.new ${'$'}SF (ref.func ${'$'}f)) (global.get ${'$'}gs))
            (data (i32.mul (global.get ${'$'}q) (i32.const 4)) "\2a")
            (func (export "q") (result i32) (global.get ${'$'}q))
            (func (export "w") (result i64) (global.get ${'$'}w))
            (func (export "p") (result i32) (struct.get_s ${'$'}P 0 (global.get ${'$'}gp)))
            (func (export "alen") (result i32) (array.len (global.get ${'$'}ga)))
            (func (export "a0") (result i32) (array.get ${'$'}A (global.get ${'$'}ga) (i32.const 0)))
            (func (export "mem") (result i32) (i32.load8_u (i32.const 8)))
            (func (export "sf") (result i32)
              (table.set ${'$'}tb (i32.const 3) (struct.get ${'$'}SF 0 (ref.as_non_null (table.get ${'$'}ts (i32.const 1)))))
              (call_indirect ${'$'}tb (type ${'$'}t) (i32.const 3)))
            """,
        )
        assertEquals(null, WitTool.validate(bytes))
        assertNull(EndiveRuntime.invalidConstExpr(Parser.parse(bytes)))
        for (runtime in listOf(EndiveRuntime(false), EndiveRuntime())) {
            runtime.load(bytes).instantiate(WasmInstanceConfig.builder().build()).use { inst ->
                assertEquals(2L, inst.call("q").single())
                assertEquals(12L, inst.call("w").single())
                assertEquals(1L, inst.call("p").single())
                assertEquals(3L, inst.call("alen").single())
                assertEquals(7L, inst.call("a0").single())
                assertEquals(0x2AL, inst.call("mem").single())
                assertEquals(10L, inst.call("ci", 2).single())
                assertEquals(10L, inst.call("sf").single())
            }
            Program.loadMetered(runtime, bytes, null, emptySet())
        }
    }

    @Test
    fun `正しい表の初期値は断らない`() {
        // 計測ありの経路は、初期値の式を持つ表にまだ対応していない（FuelInjector が先に断る）ので、計測なしの読み込みだけを見る
        val bytes = module(
            """
            (table ${'$'}ti 2 (ref null ${'$'}SF) (struct.new ${'$'}SF (ref.func ${'$'}f)))
            (func (export "ti") (result i32)
              (table.set ${'$'}tb (i32.const 3) (struct.get ${'$'}SF 0 (ref.as_non_null (table.get ${'$'}ti (i32.const 1)))))
              (call_indirect ${'$'}tb (type ${'$'}t) (i32.const 3)))
            """,
        )
        assertEquals(null, WitTool.validate(bytes))
        assertNull(EndiveRuntime.invalidConstExpr(Parser.parse(bytes)))
        for (runtime in listOf(EndiveRuntime(false), EndiveRuntime())) {
            runtime.load(bytes).instantiate(WasmInstanceConfig.builder().build()).use { inst ->
                assertEquals(10L, inst.call("ti").single())
            }
        }
    }

    @Test
    fun `importしたグローバルを数えた範囲で読める`() {
        // グローバルの番号は import が先（ig=0・igf=1、定義したものは 2 から）
        val bytes = WitTool.wat(
            """
            (module
              (import "m" "ig" (global ${'$'}ig i32))
              (import "m" "igf" (global ${'$'}igf funcref))
              (global ${'$'}a i32 (i32.add (global.get ${'$'}ig) (i32.const 1)))
              (global ${'$'}b i32 (i32.add (global.get ${'$'}a) (global.get ${'$'}ig)))
              (table ${'$'}tb 2 funcref (global.get ${'$'}igf))
              (elem (table ${'$'}tb) (offset (global.get ${'$'}b)) funcref (global.get ${'$'}igf))
              (memory 1)
              (data (global.get ${'$'}b) "a"))
            """,
        )
        assertEquals(null, WitTool.validate(bytes))
        assertNull(EndiveRuntime.invalidConstExpr(Parser.parse(bytes)))
        EndiveRuntime(false).load(bytes)
        EndiveRuntime().load(bytes)
    }

    /** null の底の型（`none`・`nofunc`・`noextern`・`noexn`）を取る値にする式のための型（[head] の型の前に置く） */
    private val bottomTypes = """
        (type ${'$'}S0 (struct (field i32)))
        (type ${'$'}t0 (func (result i32)))
        (type ${'$'}E (struct (field eqref) (field i32)))
        (type ${'$'}SR (struct (field structref)))
        (type ${'$'}AR (struct (field arrayref)))
        (type ${'$'}IR (struct (field i31ref)))
        (type ${'$'}AN (struct (field anyref)))
        (type ${'$'}CS (struct (field (ref null ${'$'}S0))))
        (type ${'$'}AE (array eqref))
        (type ${'$'}AS (array structref))
        (type ${'$'}AA (array arrayref))
        (type ${'$'}AI (array i31ref))
        (type ${'$'}CF (struct (field (ref null ${'$'}t0))))
        (type ${'$'}FR (struct (field funcref)))
        (type ${'$'}XR (struct (field externref)))
        (type ${'$'}NR (struct (field exnref)))
        (type ${'$'}NE (struct (field (ref eq))))
    """

    private fun bottomModule(body: String) = WitTool.wat("(module $bottomTypes $head $body)")

    @Test
    fun `null の底の型を取る値にする正しい式は断らない`() {
        // Endive 1.2.0 の ValType.matches は none を eq・struct・array・i31 の部分型として扱わない（#942 のレビュー）。
        // ref.null none は Binaryen が any の階層の null に出す標準の形なので、正しい WASM を断らないことを確かめる
        val bytes = bottomModule(
            """
            (global ${'$'}ge (ref ${'$'}E) (struct.new ${'$'}E (ref.null none) (i32.const 42)))
            (global (ref ${'$'}SR) (struct.new ${'$'}SR (ref.null none)))
            (global (ref ${'$'}AR) (struct.new ${'$'}AR (ref.null none)))
            (global (ref ${'$'}IR) (struct.new ${'$'}IR (ref.null none)))
            (global (ref ${'$'}AN) (struct.new ${'$'}AN (ref.null none)))
            (global (ref ${'$'}CS) (struct.new ${'$'}CS (ref.null none)))
            (global ${'$'}ga (ref ${'$'}AE) (array.new ${'$'}AE (ref.null none) (i32.const 2)))
            (global (ref ${'$'}AS) (array.new_fixed ${'$'}AS 1 (ref.null none)))
            (global (ref ${'$'}AA) (array.new_fixed ${'$'}AA 1 (ref.null none)))
            (global (ref ${'$'}AI) (array.new_fixed ${'$'}AI 2 (ref.null none) (ref.i31 (i32.const 1))))
            (global (ref ${'$'}CF) (struct.new ${'$'}CF (ref.null nofunc)))
            (global (ref ${'$'}FR) (struct.new ${'$'}FR (ref.null nofunc)))
            (global (ref ${'$'}XR) (struct.new ${'$'}XR (ref.null noextern)))
            (global (ref ${'$'}NR) (struct.new ${'$'}NR (ref.null noexn)))
            (table ${'$'}te 1 (ref null ${'$'}AE))
            (elem (table ${'$'}te) (i32.const 0) (ref null ${'$'}AE) (array.new_fixed ${'$'}AE 1 (ref.null none)))
            (elem eqref (ref.null none) (ref.i31 (i32.const 3)))
            (elem structref (ref.null none))
            (elem arrayref (ref.null none))
            (elem i31ref (ref.null none))
            (elem (ref null ${'$'}E) (struct.new ${'$'}E (ref.null none) (i32.const 1)))
            (func (export "v") (result i32) (struct.get ${'$'}E 1 (global.get ${'$'}ge)))
            (func (export "f0") (result i32) (ref.is_null (struct.get ${'$'}E 0 (global.get ${'$'}ge))))
            (func (export "alen") (result i32) (array.len (global.get ${'$'}ga)))
            (func (export "te") (result i32)
              (ref.is_null (array.get ${'$'}AE (ref.as_non_null (table.get ${'$'}te (i32.const 0))) (i32.const 0))))
            """,
        )
        assertEquals(null, WitTool.validate(bytes))
        assertNull(EndiveRuntime.invalidConstExpr(Parser.parse(bytes)))
        for (runtime in listOf(EndiveRuntime(false), EndiveRuntime())) {
            runtime.load(bytes).instantiate(WasmInstanceConfig.builder().build()).use { inst ->
                assertEquals(42L, inst.call("v").single())
                assertEquals(1L, inst.call("f0").single())
                assertEquals(2L, inst.call("alen").single())
                assertEquals(1L, inst.call("te").single())
            }
            Program.loadMetered(runtime, bytes, null, emptySet())
            Program.loadMetered(runtime, bytes, null, setOf("w.wait"))
        }
        // 表の初期値（計測ありの経路は、初期値の式を持つ表にまだ対応していないので、計測なしだけ）
        val table = bottomModule("(table 1 (ref null ${'$'}E) (struct.new ${'$'}E (ref.null none) (i32.const 7)))")
        assertEquals(null, WitTool.validate(table))
        assertNull(EndiveRuntime.invalidConstExpr(Parser.parse(table)))
        EndiveRuntime(false).load(table)
        EndiveRuntime().load(table)
    }

    @Test
    fun `null の底の型でも階層か null の可否が違えば断る`() {
        val globals = listOf(
            // none は func の階層にない
            "(global (ref \$FR) (struct.new \$FR (ref.null none)))",
            // nofunc は any の階層にない
            "(global (ref \$E) (struct.new \$E (ref.null nofunc) (i32.const 0)))",
            // noextern は any の階層にない
            "(global (ref \$AN) (struct.new \$AN (ref.null noextern)))",
            // null を持てないフィールドに null
            "(global (ref \$NE) (struct.new \$NE (ref.null none)))",
        )
        for (g in globals) assertRejectedEverywhere(g, bottomModule(g), "グローバル 0 の初期値: ")
        assertRejectedEverywhere(
            "パッシブの要素の階層違い",
            bottomModule("(elem funcref (ref.null none))"),
            "要素セグメント 0（パッシブ）の項目 0: ",
        )
    }

    private companion object {
        /** 計測ありの経路で、初期値の式を持つ表を `FuelInjector` が先に断る文面の一部 */
        const val TABLE_INIT_UNSUPPORTED = "初期値の式を持つテーブルには対応していない"

        /** 計測ありの経路で、グローバルを import するモジュールを `FuelInjector` が先に断る文面の一部 */
        const val GLOBAL_IMPORT_UNSUPPORTED = "グローバルの import には対応していない"
    }
}
