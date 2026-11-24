package pro.ikili.kibicomputers.core.runtime.endive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmSharedFuel
import pro.ikili.kibicomputers.core.runtime.fuel.FuelInjector
import pro.ikili.kibicomputers.core.wasi.kibi.WitTool
import run.endive.wasm.Parser

/**
 * ほかのインスタンスの関数の参照を、自分の関数の番号として置いてしまう定数式を、読み込みで断る（#706）。
 *
 * Endive 1.2.0 は、import した funcref のグローバルの値（渡した側での関数の番号）を、パッシブの要素の `table.init` と、
 * 定義したグローバルの初期値では、自分のインスタンスの番号として扱う。確かめたときは、どちらも渡した側の 1 番（99 を返す）ではなく、
 * 受け取った側の 1 番（11 を返す）が呼ばれた（インタプリタ・コンパイルの両方）。
 * 渡す側と受け取る側で、同じ番号の関数の値をずらしておく（0 番どうしだと、たまたま合うことがある）
 */
class ForeignFuncRefTest {
    /** 関数の 1 番が 99 を返す。0 番は 1 を返す */
    private val provider = """
        (module
          (global (export "fr") funcref (ref.func ${'$'}p))
          (global (export "n") i32 (i32.const 3))
          (global (export "ex") externref (ref.null extern))
          (func ${'$'}q (result i32) i32.const 1)
          (func ${'$'}p (result i32) i32.const 99))
    """

    /** 受け取る側の土台。関数の 0 番は 10、1 番は 11 を返す（[body] に要素・グローバルを足す） */
    private fun consumer(body: String) = """
        (module
          (import "env" "fr" (global ${'$'}fr funcref))
          (import "env" "n" (global ${'$'}n i32))
          (import "env" "ex" (global ${'$'}ex externref))
          (table ${'$'}tb 8 funcref)
          (type ${'$'}t (func (result i32)))
          (func ${'$'}f0 (result i32) i32.const 10)
          (func ${'$'}f1 (result i32) i32.const 11)
          (func (export "ci") (param i32) (result i32) (call_indirect ${'$'}tb (type ${'$'}t) (local.get 0)))
          $body)
    """

    /** 計測なし（`plain`）か、燃料を共有する形で挿入したもの（`shared`）を読み込む */
    private fun load(form: String, wat: String, runtime: EndiveRuntime = EndiveRuntime()): WasmModule {
        val bytes = WitTool.wat(wat)
        return when (form) {
            "plain" -> runtime.load(bytes)
            "shared" -> runtime.load(FuelInjector.inject(bytes, sharedGlobal = true).bytes)
            else -> error(form)
        }
    }

    private fun assertRejected(form: String, wat: String, where: String) {
        val e = assertThrows<WasmLoadException> { load(form, wat) }
        assertTrue(e.message!!.contains("#706") && e.message!!.contains(where), e.message)
    }

    @ParameterizedTest
    @ValueSource(strings = ["plain", "shared"])
    fun `パッシブの要素の式が、importしたfuncrefのグローバルを指すと断る`(form: String) {
        // Issue の形（flags 5）
        assertRejected(
            form,
            consumer(
                """
                (elem ${'$'}pe funcref (ref.func ${'$'}f0) (global.get ${'$'}fr))
                (func (export "init") (table.init ${'$'}tb ${'$'}pe (i32.const 6) (i32.const 0) (i32.const 2)))
                """,
            ),
            "要素セグメント 0（パッシブ）",
        )
        // 関数の型の参照の要素も、番号で持つので同じ
        assertRejected(
            form,
            """
            (module
              (type ${'$'}t (func (result i32)))
              (import "env" "fr" (global ${'$'}fr (ref null ${'$'}t)))
              (table 2 (ref null ${'$'}t))
              (elem declare func ${'$'}f)
              (elem (table 0) (i32.const 0) (ref null ${'$'}t) (ref.func ${'$'}f))
              (elem ${'$'}pe (ref null ${'$'}t) (global.get ${'$'}fr))
              (func ${'$'}f (result i32) i32.const 1))
            """,
            "要素セグメント 2（パッシブ）",
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["plain", "shared"])
    fun `funcrefの定義したグローバルの初期値が、importしたグローバルを指すと断る`(form: String) {
        // 要素で使わなくても断る（グローバルを要素の式で使うと、アクティブ・パッシブのどちらも違う関数になる）
        assertRejected(form, consumer("(global ${'$'}g funcref (global.get ${'$'}fr))"), "グローバル 0 の初期値")
        assertRejected(
            form,
            consumer(
                """
                (global ${'$'}g funcref (global.get ${'$'}fr))
                (elem (table ${'$'}tb) (i32.const 0) funcref (global.get ${'$'}g))
                """,
            ),
            "グローバル 0 の初期値",
        )
    }

    /** GC の型を持つモジュールを、読み込みの経路（#891 で別の理由で断られうる）を通さずに、判定だけにかける */
    private fun gcDetect(wat: String): String? = EndiveRuntime.foreignFuncRefInitializer(Parser.parse(WitTool.wat(wat)))

    @org.junit.jupiter.api.Test
    fun `GCのオブジェクトの中に、importしたfuncrefのグローバルを入れる定数式を断る`() {
        val head = """
            (type ${'$'}ft (func (result i32)))
            (type ${'$'}S (struct (field (ref null ${'$'}ft))))
            (type ${'$'}A (array (mut (ref null ${'$'}ft))))
            (import "env" "fr" (global ${'$'}fr (ref null ${'$'}ft)))
        """
        // struct.new
        val s = gcDetect("(module $head (global (ref ${'$'}S) (struct.new ${'$'}S (global.get ${'$'}fr))))")
        assertTrue(s != null && s.contains("グローバル 0") && s.contains("GC のオブジェクト"), s)
        // array.new_fixed・array.new
        val a = gcDetect("(module $head (global (ref ${'$'}A) (array.new_fixed ${'$'}A 2 (global.get ${'$'}fr) (global.get ${'$'}fr))))")
        assertTrue(a != null && a.contains("グローバル 0"), a)
        val n = gcDetect("(module $head (global (ref ${'$'}A) (array.new ${'$'}A (global.get ${'$'}fr) (i32.const 2))))")
        assertTrue(n != null && n.contains("グローバル 0"), n)
        // GC の型のパッシブの要素の式
        val e = gcDetect("(module $head (elem ${'$'}pe (ref ${'$'}S) (struct.new ${'$'}S (global.get ${'$'}fr))))")
        assertTrue(e != null && e.contains("要素セグメント 0"), e)
        // アクティブの要素の式も、式の中でオブジェクトに入れるので同じ
        val ae = gcDetect(
            "(module $head (table ${'$'}t 1 (ref null ${'$'}S)) (elem (table ${'$'}t) (i32.const 0) (ref ${'$'}S) (struct.new ${'$'}S (global.get ${'$'}fr))))",
        )
        assertTrue(ae != null && ae.contains("要素セグメント 0"), ae)
        // 表の初期値の式も、Instance のコンストラクターが同じ computeConstant で作って置くので同じ
        val t = gcDetect("(module $head (table 1 (ref null ${'$'}S) (struct.new ${'$'}S (global.get ${'$'}fr))))")
        assertTrue(t != null && t.contains("表 0 の初期値"), t)
    }

    @org.junit.jupiter.api.Test
    fun `GCのオブジェクトを作っても、importした関数の参照を入れなければ断らない`() {
        val head = """
            (type ${'$'}ft (func (result i32)))
            (type ${'$'}S (struct (field (ref null ${'$'}ft)) (field i32)))
            (type ${'$'}A (array (mut (ref null ${'$'}ft))))
            (import "env" "fr" (global ${'$'}fr (ref null ${'$'}ft)))
            (import "env" "n" (global ${'$'}n i32))
            (func ${'$'}f (result i32) i32.const 1)
            (elem declare func ${'$'}f)
        """
        // 自分の ref.func・import した i32・既定値の new は、番号を運ばない
        assertNull(gcDetect("(module $head (global (ref ${'$'}S) (struct.new ${'$'}S (ref.func ${'$'}f) (global.get ${'$'}n))))"))
        assertNull(gcDetect("(module $head (global (ref ${'$'}S) (struct.new_default ${'$'}S)))"))
        assertNull(gcDetect("(module $head (global (ref ${'$'}A) (array.new_default ${'$'}A (i32.const 2))))"))
        // import した funcref のグローバルがあっても、オブジェクトに入れなければ断らない
        assertNull(gcDetect("(module $head (global ${'$'}g (ref null ${'$'}S) (ref.null ${'$'}S)))"))
        // 表の初期値の式で、自分の ref.func を入れるオブジェクトも断らない
        assertNull(gcDetect("(module $head (table 1 (ref null ${'$'}S) (struct.new ${'$'}S (ref.func ${'$'}f) (global.get ${'$'}n))))"))
    }

    @org.junit.jupiter.api.Test
    fun `表の初期値の式が、importしたfuncrefのグローバルを指すのは断らない`() {
        // 表の初期値は、アクティブの要素と同じく持ち主のインスタンスを引いて置く（Endive 1.2.0 の Instance のコンストラクター）。
        // 燃料を共有する形の挿入は、初期値の式を持つ表をそもそも断るので、計測なしの形だけ（下で、断る理由が #706 でないことも確かめる）
        val wat = """
            (module
              (import "env" "fr" (global ${'$'}fr funcref))
              (table ${'$'}tb 2 funcref (global.get ${'$'}fr))
              (type ${'$'}t (func (result i32)))
              (func ${'$'}f0 (result i32) i32.const 10)
              (func ${'$'}f1 (result i32) i32.const 11)
              (func (export "ci") (param i32) (result i32) (call_indirect ${'$'}tb (type ${'$'}t) (local.get 0))))
        """
        val module = load("plain", wat)
        val runtime = EndiveRuntime()
        runtime.load(WitTool.wat(provider)).instantiate(WasmInstanceConfig.builder().build()).use { p ->
            val b = WasmInstanceConfig.builder()
            b.importExtern("env", "fr", requireNotNull(p.extern("fr")))
            module.instantiate(b.build()).use { c ->
                assertEquals(99, c.call("ci", 0).single())
                assertEquals(99, c.call("ci", 1).single())
            }
        }
        val e = assertThrows<WasmLoadException> { load("shared", wat) }
        assertTrue(!e.message!!.contains("#706"), e.message)
    }

    @ParameterizedTest
    @ValueSource(strings = ["plain", "shared"])
    fun `宣言の要素の式が、importしたfuncrefのグローバルを指すのは断らない`(form: String) {
        // 宣言の要素は table.init で使えないので、値が置かれることはない
        load(form, consumer("(elem declare funcref (global.get ${'$'}fr))"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["plain", "shared"])
    fun `正しく動く形は断らない`(form: String) {
        val module = load(
            form,
            consumer(
                """
                ;; アクティブの要素の import したグローバル（持ち主のインスタンスを引くので正しい）
                (elem (table ${'$'}tb) (i32.const 0) funcref (global.get ${'$'}fr))
                ;; パッシブの要素の ref.func・ref.null
                (elem ${'$'}pe funcref (ref.func ${'$'}f1) (ref.null func))
                ;; 定義したグローバルの、ref.func の初期値と、import した i32 を指す初期値
                (global ${'$'}g funcref (ref.func ${'$'}f0))
                (global ${'$'}k i32 (global.get ${'$'}n))
                (elem ${'$'}pg funcref (global.get ${'$'}g))
                ;; externref の表のパッシブの要素は、参照ごと置くので正しい
                (table ${'$'}xt 2 externref)
                (elem ${'$'}px externref (global.get ${'$'}ex))
                (func (export "init")
                  (table.init ${'$'}tb ${'$'}pe (i32.const 2) (i32.const 0) (i32.const 1))
                  (table.init ${'$'}tb ${'$'}pg (i32.const 3) (i32.const 0) (i32.const 1))
                  (table.init ${'$'}xt ${'$'}px (i32.const 0) (i32.const 0) (i32.const 1)))
                (func (export "k") (result i32) global.get ${'$'}k)
                """,
            ),
        )
        val runtime = EndiveRuntime()
        runtime.load(WitTool.wat(provider)).instantiate(WasmInstanceConfig.builder().build()).use { p ->
            val b = WasmInstanceConfig.builder()
            if (form == "shared") b.sharedFuel(WasmSharedFuel(1_000_000))
            for (name in listOf("fr", "n", "ex")) b.importExtern("env", name, requireNotNull(p.extern(name)))
            module.instantiate(b.build()).use { c ->
                assertEquals(99, c.call("ci", 0).single())
                c.call("init")
                assertEquals(11, c.call("ci", 2).single())
                assertEquals(10, c.call("ci", 3).single())
                assertEquals(3, c.call("k").single())
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["plain", "shared"])
    fun `関数の本体で参照を運ぶ形は、定数式ではないので読み込みでは断らない`(form: String) {
        // 同じく違う関数になる（Endive 1.2.0）が、命令の流れを追わないと分からないので、ここでは見ない（#932）
        load(form, consumer("(func (export \"set\") (table.set ${'$'}tb (i32.const 3) (global.get ${'$'}fr)))"))
    }

    @org.junit.jupiter.api.Test
    fun `グローバルのimportがなければ、判定は見ない`() {
        val m = Parser.parse(WitTool.wat("(module (table 1 funcref) (elem ${'$'}pe funcref (ref.func 0)) (func))"))
        assertNull(EndiveRuntime.foreignFuncRefInitializer(m))
    }
}
