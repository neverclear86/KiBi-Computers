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
import run.endive.wasm.InvalidException
import run.endive.wasm.Parser

/**
 * パッシブ・宣言の要素セグメントの項目の式を、読み込みで確かめる（#893）。
 *
 * Endive 1.2.0 の検証は、アクティブの要素の式しか定数式として確かめないので、正しくない式が `table.init`・`array.new_elem` まで届いていた
 * （funcref の要素に `v128.const` の項目を置くと、下位 32 ビットが関数の番号として表に入り、インタプリタでは負の番号で
 * `ArrayIndexOutOfBoundsException` がそのまま出た）。`wasm-tools parse` は式の型を確かめないので、正しくない式も WAT で書ける。
 * 式の先頭の `ref.func` の範囲（flag 1・3 の番号の並びを含む）は Endive の検証がもう断るので、この判定の範囲の分岐は先頭でない `ref.func` で確かめる
 */
class PassiveElementValidationTest {
    private val head = """
        (type ${'$'}t (func (result i32)))
        (type ${'$'}S (struct (field i32)))
        (type ${'$'}P (struct (field i8) (field (mut i16))))
        (type ${'$'}A (array i32))
        (type ${'$'}SF (struct (field funcref)))
        (type ${'$'}FS (struct (field i32) (field funcref)))
        (type ${'$'}NN (struct (field (ref ${'$'}S))))
        (type ${'$'}AN (array (ref ${'$'}S)))
        (global ${'$'}gi i32 (i32.const 0))
        (global ${'$'}gm (mut funcref) (ref.null func))
        (global ${'$'}gf funcref (ref.func ${'$'}f))
        (table ${'$'}tb 4 funcref)
        (func ${'$'}f (result i32) i32.const 10)
        (func (export "ci") (param i32) (result i32) (call_indirect ${'$'}tb (type ${'$'}t) (local.get 0)))
    """

    private fun module(elem: String) = WitTool.wat("(module $head $elem)")

    /** 読み込みのすべての経路で、正しくない WASM として断る */
    private fun assertRejectedEverywhere(name: String, bytes: ByteArray, plainContains: String) {
        assertTrue(WitTool.validate(bytes) != null, "テストの入力が正しくない WASM になっている（$name）")
        // Endive の検証は通す（Endive の穴。直ったらここが落ちるので、判定を外す）
        Parser.parse(bytes)
        assertTrue(EndiveRuntime.invalidPassiveElementInitializer(Parser.parse(bytes)) != null, name)
        for (runtime in listOf(EndiveRuntime(false), EndiveRuntime())) {
            for ((path, load) in listOf<Pair<String, () -> Any>>(
                "load" to { runtime.load(bytes) },
                "describe" to { runtime.describe(bytes) },
                "Program.load" to { Program.load(runtime, bytes) },
            )) {
                val e = assertThrows<WasmLoadException>("$name: $path") { load() }
                assertTrue(e.message!!.startsWith("WASM の検証に通らない: "), "$name: $path: ${e.message}")
                assertTrue(e.message!!.contains(plainContains), "$name: $path: ${e.message}")
                assertTrue(!e.message!!.contains("#706"), "$name: $path: ${e.message}")
            }
            for (waits in listOf(emptySet(), setOf("w.wait"))) {
                val e = assertThrows<WasmLoadException>("$name: loadMetered $waits") { Program.loadMetered(runtime, bytes, null, waits) }
                assertNull(Program.unsuspendableReason(e), "$name: loadMetered $waits: ${e.message}")
            }
        }
    }

    /**
     * Endive 自身が断る形（式の先頭の `ref.func` の範囲。Endive の `Validator` のコンストラクターが確かめる）。
     * Endive の `Parser.parse` が断るので、[EndiveRuntime.invalidPassiveElementInitializer] には届かない
     */
    private fun assertRejectedByEndive(name: String, bytes: ByteArray) {
        assertTrue(WitTool.validate(bytes) != null, "テストの入力が正しくない WASM になっている（$name）")
        assertThrows<InvalidException>(name) { Parser.parse(bytes) }
        for (runtime in listOf(EndiveRuntime(false), EndiveRuntime())) {
            for ((path, load) in listOf<Pair<String, () -> Any>>(
                "load" to { runtime.load(bytes) },
                "describe" to { runtime.describe(bytes) },
                "Program.load" to { Program.load(runtime, bytes) },
            )) {
                val e = assertThrows<WasmLoadException>("$name: $path") { load() }
                assertTrue(e.message!!.startsWith("WASM の検証に通らない: "), "$name: $path: ${e.message}")
            }
            for (waits in listOf(emptySet(), setOf("w.wait"))) {
                val e = assertThrows<WasmLoadException>("$name: loadMetered $waits") { Program.loadMetered(runtime, bytes, null, waits) }
                assertNull(Program.unsuspendableReason(e), "$name: loadMetered $waits: ${e.message}")
            }
        }
    }

    @Test
    fun `正しくない式を持つパッシブ・宣言の要素セグメントを断る`() {
        val items = mapOf(
            "定数式に使えない命令（drop）" to "(item (i32.const 0) (drop) (ref.null func))",
            "定数式に使えない命令（i8x16.swizzle）" to "(item (v128.const i64x2 0 0) (v128.const i64x2 0 0) (i8x16.swizzle))",
            "funcref に v128.const" to "(item (v128.const i32x4 0 0 0 0))",
            "funcref に負の番号になる v128.const" to "(item (v128.const i32x4 -5 0 0 0))",
            "funcref に i32.const" to "(item (i32.const 0))",
            "funcref に ref.null extern" to "(item (ref.null extern))",
            "funcref に i32 のグローバル" to "(item (global.get ${'$'}gi))",
            "可変のグローバル" to "(item (global.get ${'$'}gm))",
            // 先頭の ref.func は Endive が断るので、先頭でない ref.func で範囲の判定を確かめる
            "先頭でない範囲の外の ref.func" to "(item (ref.null func) (ref.func 99))",
            "先頭でない範囲の外の ref.func（u32 の最大）" to "(item (ref.null func) (ref.func 4294967295))",
            "空の式" to "(item)",
            "値が 2 つ残る式" to "(item (ref.null func) (ref.null func))",
            "スタックが足りない" to "(item (i32.add) (ref.null func))",
        )
        for ((name, item) in items) {
            // パッシブ（flag 5）・宣言（flag 7）
            assertRejectedEverywhere("パッシブ: $name", module("(elem funcref $item)"), "（パッシブ）の項目 0")
            assertRejectedEverywhere("宣言: $name", module("(elem declare funcref $item)"), "（宣言）の項目 0")
        }
        // 要素の型と値の型の組み合わせ
        assertRejectedEverywhere("externref に ref.func", module("(elem externref (ref.func ${'$'}f))"), "（パッシブ）の項目 0")
        assertRejectedEverywhere("null を持てない型に ref.null", module("(elem (ref func) (ref.null func))"), "（パッシブ）の項目 0")
        // 2 つ目の項目と、2 つ目の要素セグメント
        assertRejectedEverywhere(
            "2 つ目",
            module("(elem funcref (ref.func ${'$'}f)) (elem funcref (ref.null func) (i32.const 1))"),
            "要素セグメント 1（パッシブ）の項目 1",
        )
    }

    @Test
    fun `先頭でない範囲の外のref_funcは関数の番号を文面に出して断る`() {
        for (idx in listOf("99", "4294967295")) {
            val e = assertThrows<WasmLoadException> { EndiveRuntime().load(module("(elem funcref (item (ref.null func) (ref.func $idx)))")) }
            assertEquals("WASM の検証に通らない: 要素セグメント 0（パッシブ）の項目 0: 関数 $idx がない", e.message)
        }
    }

    @Test
    fun `範囲の外の関数の番号は Endive が断る`() {
        // 関数は 2 つ（0・1）。2^32 - 1 は Int にすると負になる
        for (idx in listOf("2", "99", "2147483648", "4294967295")) {
            assertRejectedByEndive("パッシブ $idx", module("(elem func ${'$'}f $idx)"))
            assertRejectedByEndive("宣言 $idx", module("(elem declare func $idx)"))
            // 式の先頭の ref.func も同じ（flag 5・7）
            assertRejectedByEndive("パッシブの式 $idx", module("(elem funcref (ref.func $idx))"))
            assertRejectedByEndive("宣言の式 $idx", module("(elem declare funcref (ref.func $idx))"))
        }
    }

    @Test
    fun `取る値の型が合わない式を断る`() {
        // 要素の型 to 項目。どれも wasm-tools が type mismatch で断り、Endive は通す
        val cases = listOf(
            "(ref null \$SF)" to "(struct.new \$SF (i32.const -5))",
            "(ref \$S)" to "(struct.new \$S (i64.const 1))",
            "(ref \$S)" to "(struct.new \$S (ref.null func))",
            "(ref \$S)" to "(struct.new \$S)",
            "(ref \$P)" to "(struct.new \$P (i64.const 1) (i32.const 2))",
            "(ref \$FS)" to "(struct.new \$FS (ref.null func) (i32.const 0))",
            "(ref \$S)" to "(struct.new_default \$P)",
            "(ref \$A)" to "(array.new \$A (i64.const 0) (i32.const 1))",
            "(ref \$A)" to "(array.new \$A (i32.const 0) (i64.const 1))",
            "(ref \$A)" to "(array.new_fixed \$A 2 (i32.const 1) (f32.const 1))",
            "(ref \$A)" to "(array.new_default \$A (i64.const 3))",
            "(ref i31)" to "(ref.i31 (i64.const 1))",
            "(ref i31)" to "(ref.i31 (i32.add (i64.const 1) (i64.const 2)))",
            "(ref struct)" to "(ref.i31 (i32.const 0))",
            // 既定値で作れない（null を持てない参照のフィールド・要素）
            "(ref \$NN)" to "(struct.new_default \$NN)",
            "(ref \$AN)" to "(array.new_default \$AN (i32.const 1))",
            // 変換の結果が null を持てるかは、取った値に従う
            "(ref any)" to "(any.convert_extern (ref.null extern))",
            "(ref extern)" to "(extern.convert_any (ref.null any))",
            "(ref any)" to "(any.convert_extern (extern.convert_any (ref.null any)))",
            // 変換が取る値の型
            "anyref" to "(any.convert_extern (ref.null func))",
            "externref" to "(extern.convert_any (ref.null extern))",
        )
        for ((type, item) in cases) {
            assertRejectedEverywhere("パッシブ: $type $item", module("(elem $type $item)"), "（パッシブ）の項目 0")
            assertRejectedEverywhere("宣言: $type $item", module("(elem declare $type $item)"), "（宣言）の項目 0")
        }
    }

    @Test
    fun `範囲の外の型の番号のref_nullは場所を付けて断る`() {
        for (idx in listOf("99", "1000")) {
            val bytes = module("(elem funcref (item (ref.null $idx)))")
            val e = assertThrows<WasmLoadException> { EndiveRuntime().load(bytes) }
            assertEquals("WASM の検証に通らない: 要素セグメント 0（パッシブ）の項目 0: ヒープ型 $idx がない", e.message)
        }
        assertRejectedEverywhere("ref.null 99", module("(elem funcref (item (ref.null 99)))"), "（パッシブ）の項目 0: ヒープ型 99 がない")
    }

    /** import を先に数える番号（関数・グローバル）と、import したグローバルの可変の判定 */
    private val headWithImports = """
        (type ${'$'}t (func (result i32)))
        (type ${'$'}u (func (param i32)))
        (import "m" "if" (func ${'$'}if (type ${'$'}u)))
        (import "m" "ig" (global ${'$'}ig i32))
        (import "m" "igf" (global ${'$'}igf funcref))
        (import "m" "igm" (global ${'$'}igm (mut funcref)))
        (import "m" "ige" (global ${'$'}ige (ref extern)))
        (global ${'$'}gf funcref (ref.func ${'$'}f))
        (func ${'$'}f (type ${'$'}t) i32.const 10)
    """

    private fun moduleWithImports(elem: String) = WitTool.wat("(module $headWithImports $elem)")

    @Test
    fun `importを含めた番号で関数とグローバルの型を引く`() {
        // 関数は if=0・f=1、グローバルは ig=0・igf=1・igm=2・ige=3・gf=4（import が先）
        val accepted = listOf(
            "(elem (ref \$u) (ref.func \$if))",
            "(elem (ref \$t) (ref.func \$f))",
            "(elem (ref null \$t) (ref.null \$t) (ref.func 1))",
            "(elem funcref (global.get \$gf))",
            "(elem funcref (ref.null func) (global.get 4))",
            "(elem (ref any) (any.convert_extern (global.get \$ige)))",
            "(elem (ref extern) (global.get \$ige))",
        )
        for (elem in accepted) {
            val bytes = moduleWithImports(elem)
            assertEquals(null, WitTool.validate(bytes), elem)
            assertNull(EndiveRuntime.invalidPassiveElementInitializer(Parser.parse(bytes)), elem)
            EndiveRuntime(false).load(bytes)
            EndiveRuntime().load(bytes)
        }
        val rejected = listOf(
            "(elem (ref \$t) (ref.func \$if))" to "値の型",
            "(elem (ref \$u) (ref.func \$f))" to "値の型",
            "(elem funcref (global.get \$ig))" to "値の型",
            "(elem funcref (global.get \$igm))" to "可変のグローバル",
            "(elem funcref (ref.null func) (global.get 5))" to "グローバルがない",
            "(elem externref (global.get \$igf))" to "値の型",
            "(elem anyref (any.convert_extern (global.get \$igf)))" to "値の型",
            "(elem (ref any) (any.convert_extern (ref.null extern)))" to "値の型",
        )
        for ((elem, reason) in rejected) assertRejectedEverywhere("import: $elem", moduleWithImports(elem), reason)
    }

    @Test
    fun `正しい式は断らない`() {
        val elems = listOf(
            "(elem funcref (ref.func ${'$'}f) (ref.null func) (global.get ${'$'}gf))",
            "(elem func ${'$'}f 1)",
            "(elem declare func ${'$'}f)",
            "(elem declare funcref (ref.func ${'$'}f))",
            "(elem (ref ${'$'}t) (ref.func ${'$'}f))",
            "(elem (ref null ${'$'}t) (ref.null ${'$'}t) (ref.func ${'$'}f))",
            "(elem externref (ref.null extern) (extern.convert_any (ref.null any)))",
            "(elem anyref (any.convert_extern (ref.null extern)) (ref.i31 (i32.add (i32.const 1) (i32.const 2))))",
            "(elem (ref i31) (ref.i31 (i32.const 1)))",
            "(elem (ref ${'$'}S) (struct.new_default ${'$'}S) (struct.new ${'$'}S (i32.mul (global.get ${'$'}gi) (i32.const 3))))",
            "(elem (ref ${'$'}P) (struct.new ${'$'}P (i32.const 1) (i32.const 2)))",
            "(elem (ref ${'$'}A) (array.new_fixed ${'$'}A 2 (i32.const 1) (i32.const 2)) (array.new_default ${'$'}A (i32.const 3)) (array.new ${'$'}A (i32.const 0) (i32.const 1)))",
            "(elem structref (ref.null struct) (struct.new_default ${'$'}S))",
            "(elem eqref (ref.i31 (i32.const 0)) (array.new_fixed ${'$'}A 0))",
            // 変換の結果が null を持てるかは、取った値に従う
            "(elem (ref extern) (extern.convert_any (ref.i31 (i32.const 0))))",
            "(elem (ref any) (any.convert_extern (extern.convert_any (ref.i31 (i32.const 0)))))",
            "(elem (ref null any) (any.convert_extern (extern.convert_any (ref.null any))))",
            // 取る値の型が合うもの（フィールド・要素の型、i8・i16 は i32、null を持てない参照）
            "(elem (ref ${'$'}FS) (struct.new ${'$'}FS (i32.const 1) (ref.func ${'$'}f)))",
            "(elem (ref null ${'$'}SF) (struct.new ${'$'}SF (ref.null func)))",
            "(elem (ref ${'$'}NN) (struct.new ${'$'}NN (struct.new_default ${'$'}S)))",
            "(elem (ref ${'$'}AN) (array.new_fixed ${'$'}AN 1 (struct.new_default ${'$'}S)) (array.new ${'$'}AN (struct.new_default ${'$'}S) (i32.const 2)))",
            "(elem (ref ${'$'}A) (array.new_default ${'$'}A (i32.add (i32.const 1) (i32.const 1))))",
        )
        for (elem in elems) {
            val bytes = module(elem)
            assertEquals(null, WitTool.validate(bytes), elem)
            assertNull(EndiveRuntime.invalidPassiveElementInitializer(Parser.parse(bytes)), elem)
            EndiveRuntime(false).load(bytes)
            EndiveRuntime().load(bytes)
        }
    }

    @Test
    fun `パッシブの要素の関数をtable_initで置いて呼べる`() {
        // 判定を足しても、正しい形はこれまでどおり動く
        val bytes = WitTool.wat(
            """
            (module $head
              (elem ${'$'}e funcref (ref.func ${'$'}f) (global.get ${'$'}gf))
              (func (export "init") (table.init ${'$'}tb ${'$'}e (i32.const 0) (i32.const 0) (i32.const 2))))
            """,
        )
        for (runtime in listOf(EndiveRuntime(false), EndiveRuntime())) {
            runtime.load(bytes).instantiate(WasmInstanceConfig.builder().build()).use { inst ->
                inst.call("init")
                assertEquals(10L, inst.call("ci", 0).single())
                assertEquals(10L, inst.call("ci", 1).single())
            }
        }
    }
}
