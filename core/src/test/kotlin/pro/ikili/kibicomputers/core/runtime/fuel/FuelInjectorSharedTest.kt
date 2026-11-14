package pro.ikili.kibicomputers.core.runtime.fuel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.runtime.WasmFuel
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmSharedFuel
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.wasi.kibi.WitTool

/**
 * 燃料を共有する形の挿入（`FuelInjector.inject(sharedGlobal = true)`。#263・#692）。
 *
 * 燃料のグローバルの import を、グローバルの import の最後に足すので、定義済みのグローバルの番号が 1 つずれる。
 * 番号がずれても型が合えば読めてしまうので、挿入した後のバイト列は (a) wasm-tools の検証、(b) Endive の読み込み、
 * (c) 実際に動かして、正しいグローバル・オフセットに当たっていること、の 3 つで確かめる。
 * 値は、ずれたら結果が必ず変わる組み合わせにしている（import と定義済みのグローバルで値を変える）
 */
class FuelInjectorSharedTest {
    private val runtime = EndiveRuntime()

    /** [consumer] に import を渡す側（計測しない。グローバルを定義して export するだけ） */
    private val provider = """
        (module
          (global (export "base") i32 (i32.const 256))
          (global (export "off") i32 (i32.const 2))
          (global (export "fr") funcref (ref.func ${'$'}p))
          (global (export "m") (mut i32) (i32.const 5))
          (func ${'$'}p (result i32) i32.const 99)
          (func (export "getm") (result i32) global.get 3))
    """

    /** グローバルの import が 4 つある（`importedGlobals > 0`）。定義済みのグローバルは 4・5・6 番から 5・6・7 番にずれる */
    private val consumer = """
        (module
          (import "env" "base" (global ${'$'}base i32))
          (import "env" "off" (global ${'$'}off i32))
          (import "env" "fr" (global ${'$'}fr funcref))
          (import "env" "m" (global ${'$'}m (mut i32)))
          (memory 1)
          (table 8 funcref)
          (type ${'$'}t (func (result i32)))
          (global ${'$'}g (mut i32) (i32.const 7))
          ;; 初期値が import したグローバルを指す（MVP の形。番号は変わらない）
          (global ${'$'}h i32 (global.get ${'$'}base))
          (global ${'$'}k (mut i32) (i32.const 1000))
          (func ${'$'}f0 (result i32) i32.const 10)
          (func ${'$'}f1 (result i32) i32.const 11)
          (func ${'$'}f2 (result i32) i32.const 12)
          ;; アクティブのデータ（flags 0）。オフセットは import したグローバル
          (data (global.get ${'$'}base) "AB")
          ;; 要素（flags 0: オフセットの式と関数の番号の列）。オフセットは import したグローバル
          (elem (global.get ${'$'}off) func ${'$'}f1)
          ;; 要素（flags 4: 式の列）。ref.func と、import したグローバルの global.get
          (elem (i32.const 4) funcref (ref.func ${'$'}f2) (global.get ${'$'}fr))
          ;; 要素（flags 5: パッシブの式の列）。Endive が table.init でパッシブの式の global.get を正しく置かないので、動かさずに
          ;; 挿入した後のバイト列を wasm-tools で読み戻して確かめる（挿入しないモジュールでも違う関数になる。#706）
          (elem ${'$'}pe funcref (ref.func ${'$'}f0) (global.get ${'$'}fr))
          (export "gx" (global ${'$'}g))
          (export "kx" (global ${'$'}k))
          (func (export "g") (result i32) global.get ${'$'}g)
          (func (export "setg") (global.set ${'$'}g (i32.const 42)))
          (func (export "h") (result i32) global.get ${'$'}h)
          (func (export "k") (result i32) global.get ${'$'}k)
          (func (export "m") (result i32) global.get ${'$'}m)
          (func (export "setm") (global.set ${'$'}m (i32.const 6)))
          (func (export "mem") (param i32) (result i32) (i32.load16_u (local.get 0)))
          (func (export "ci") (param i32) (result i32) (call_indirect (type ${'$'}t) (local.get 0)))
          (func (export "init") (table.init ${'$'}pe (i32.const 6) (i32.const 0) (i32.const 2)))
          (func (export "drop") (elem.drop ${'$'}pe)))
    """

    /** [consumer] の export したグローバルを import して読む（これも共有の形で挿入する） */
    private val reader = """
        (module
          (import "c" "gx" (global (mut i32)))
          (import "c" "kx" (global (mut i32)))
          (func (export "gx") (result i32) global.get 0)
          (func (export "kx") (result i32) global.get 1))
    """

    /** 挿入して、wasm-tools の検証と Endive の読み込みを確かめる */
    private fun injectShared(wat: String): WasmModule {
        val out = FuelInjector.inject(WitTool.wat(wat), sharedGlobal = true).bytes
        assertNull(WitTool.validate(out), "挿入した後のバイト列が正しくない")
        val m = runtime.load(out)
        assertTrue(m.isFuelMetered)
        assertTrue(m.sharesFuel)
        assertTrue(m.exports.none { it.name == WasmFuel.EXPORT_GLOBAL }, "燃料の export は外に見せない")
        assertTrue(m.imports.none { it.module == WasmFuel.IMPORT_MODULE }, "燃料の import は外に見せない")
        return m
    }

    private fun config(fuel: WasmSharedFuel, vararg imports: Pair<String, Pair<WasmInstance, String>>): WasmInstanceConfig {
        val b = WasmInstanceConfig.builder().sharedFuel(fuel)
        for ((module, from) in imports) {
            val (module0, name) = module.split('.').let { it[0] to it[1] }
            b.importExtern(module0, name, requireNotNull(from.first.extern(from.second)))
        }
        return b.build()
    }

    @Test
    fun `グローバルのimportがあるとき、本体・export・初期値・データ・要素の番号を付け替える`() {
        val consumerModule = injectShared(consumer)
        val readerModule = injectShared(reader)
        val fuel = WasmSharedFuel(1_000_000)
        runtime.load(WitTool.wat(provider)).instantiate(WasmInstanceConfig.builder().build()).use { p ->
            val env = arrayOf("base", "off", "fr", "m").map { "env.$it" to (p to it) }.toTypedArray()
            consumerModule.instantiate(config(fuel, *env)).use { c ->
                fun call(name: String, vararg args: Long) = c.call(name, *args).single()
                // 関数の本体の global.get（定義済みは +1、import はそのまま）
                assertEquals(7, call("g"))
                assertEquals(1000, call("k"))
                assertEquals(5, call("m"))
                // グローバルの初期値が import を指す
                assertEquals(256, call("h"))
                // データのオフセット（import したグローバル）
                assertEquals(0x4241, call("mem", 256))
                // 要素のオフセットと式
                assertEquals(11, call("ci", 2))
                assertEquals(12, call("ci", 4))
                assertEquals(99, call("ci", 5))
                // global.set（import した可変のグローバルは、渡した側から見える）
                c.call("setm")
                assertEquals(6, p.call("getm").single())
                // export（種類 3）の番号: 別のインスタンスから import して読む
                readerModule.instantiate(config(fuel, "c.gx" to (c to "gx"), "c.kx" to (c to "kx"))).use { r ->
                    assertEquals(7, r.call("gx").single())
                    assertEquals(1000, r.call("kx").single())
                    c.call("setg")
                    assertEquals(42, r.call("gx").single())
                    // 燃料は共有している
                    assertTrue(c.fuel < 1_000_000, "燃料が減っていない: ${c.fuel}")
                    assertEquals(c.fuel, r.fuel)
                }
            }
        }
    }

    @Test
    fun `パッシブの要素の式の番号を付け替える`() {
        // 関数は燃料の import の分だけ +1（$f0 は 0 → 1）、import したグローバル $fr（2 番）はそのまま
        val out = FuelInjector.inject(WitTool.wat(consumer), sharedGlobal = true).bytes
        val r = WitTool.run(listOf("print", "${WitTool.WORK}/m.wasm"), mapOf("m.wasm" to out))
        assertEquals(0, r.exitCode, r.stderr)
        val passive = r.stdout.lines().filter { it.trim().startsWith("(elem") && "funcref (ref.func" in it && "i32.const" !in it }
        assertEquals(listOf("(ref.func 1) (global.get 2))"), passive.map { it.substringAfter("funcref ").trim() }, r.stdout)
    }

    @Test
    fun `グローバルのimportがないとき、燃料のimportが0番になり、定義済みのグローバルはみなずれる`() {
        val m = injectShared(
            """
            (module
              (global ${'$'}a (mut i32) (i32.const 3))
              (global ${'$'}b i32 (i32.const 9))
              (export "b" (global ${'$'}b))
              (func (export "a") (result i32) global.get 0)
              (func (export "sum") (result i32) (i32.add (global.get ${'$'}a) (global.get ${'$'}b)))
              (func (export "seta") (global.set ${'$'}a (i32.const 30))))
            """,
        )
        val readB = injectShared("""(module (import "c" "b" (global i32)) (func (export "b") (result i32) global.get 0))""")
        val fuel = WasmSharedFuel(1_000_000)
        m.instantiate(config(fuel)).use { c ->
            assertEquals(3, c.call("a").single())
            assertEquals(12, c.call("sum").single())
            c.call("seta")
            assertEquals(39, c.call("sum").single())
            readB.instantiate(config(fuel, "c.b" to (c to "b"))).use { r -> assertEquals(9, r.call("b").single()) }
        }
    }

    @Test
    fun `グローバルの初期値が前に定義したグローバルを指す形も付け替える`() {
        // extended-const・GC の形（定義済みのグローバルを定数式で指す）。番号は +1 にずれる
        val m = injectShared(
            """
            (module
              (import "env" "base" (global ${'$'}base i32))
              (global ${'$'}a i32 (i32.const 40))
              (global ${'$'}b i32 (i32.add (global.get ${'$'}a) (global.get ${'$'}base)))
              (memory 1)
              (data (offset (i32.add (global.get ${'$'}a) (i32.const 8))) "Z")
              (func (export "b") (result i32) global.get ${'$'}b)
              (func (export "z") (result i32) (i32.load8_u (i32.const 48))))
            """,
        )
        runtime.load(WitTool.wat(provider)).instantiate(WasmInstanceConfig.builder().build()).use { p ->
            m.instantiate(config(WasmSharedFuel(1_000_000), "env.base" to (p to "base"))).use { c ->
                assertEquals(296, c.call("b").single())
                assertEquals('Z'.code.toLong(), c.call("z").single())
            }
        }
    }

    @Test
    fun `共有しない形ではグローバルのimportを断る`() {
        val e = assertThrows<WasmLoadException> { FuelInjector.inject(WitTool.wat(consumer)) }
        assertTrue(e.message!!.contains("グローバルの import"), e.message)
    }
}
