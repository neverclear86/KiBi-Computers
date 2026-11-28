package pro.ikili.kibicomputers.core.runtime.asyncify

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.runtime.WasmAsyncStack
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.wasi.kibi.WitTool

/**
 * `call_indirect`・`call_ref` を、型だけでなく参照が取られうる関数で絞る解析（#338。ADR 0329 の「#338 での改善」）。
 * 型が同じ止まりうる関数があっても、その関数が要素セグメント・`ref.func`・export のどれにも出なければ、間接呼び出しからは呼ばれない
 */
class AsyncifyIndirectTest {
    private fun exports(wasm: ByteArray): Set<String> = Asyncify.prepare(wasm).analyze(setOf("w.wait"), emptySet()).suspendableExports

    /**
     * 止まりうる関数 `$f`（`() -> i32`）と、同じ型の止まらない `$g` を持つモジュール。`run` は `call_indirect (type $v_i)` で表の 0 番を呼ぶ。
     * [refs] は `$f` の参照を取る（かもしれない）ものの宣言
     */
    private fun module(refs: String, table: String = "(elem (i32.const 0) \$g)") = WitTool.wat(
        """
        (module
          (type ${'$'}v_i (func (result i32)))
          (import "w" "wait" (func ${'$'}wait (result i32)))
          (table 2 funcref)
          $table
          $refs
          (func ${'$'}f (type ${'$'}v_i) (call ${'$'}wait))
          (func ${'$'}g (type ${'$'}v_i) (i32.const 7))
          (func (export "run") (result i32)
            (call_indirect (type ${'$'}v_i) (i32.const 0))))
        """.trimIndent(),
    )

    @Test
    fun `型が同じでも、参照の取られない止まりうる関数があるだけでは、call_indirectは止まりうるとみなさない`() {
        val wasm = module("")
        assertEquals(emptySet<String>(), exports(wasm))
        // 変換するのは $f だけ（run は番号の付け替えだけ）
        assertEquals(1, Asyncify.transform(wasm, setOf("w.wait")).instrumented)
        // 前の解析（型だけ）では run も変換していた
        assertEquals(2, Asyncify.transform(wasm, setOf("w.wait"), typeOnlyIndirect = true).instrumented)
        // コンポーネントの全体の不動点に渡す型（Analysis.suspendTypes）は、今までどおり止まりうる関数すべての型（参照の取られない $f も含む）
        val types = Asyncify.prepare(wasm).analyze(setOf("w.wait"), emptySet()).suspendTypes
        assertTrue(Asyncify.Signature(emptyList(), listOf(0x7F)) in types, types.toString())
        // 変換したものを動かすと、表の $g を呼ぶ
        assertEquals(7L, run(Asyncify.transform(wasm, setOf("w.wait")).bytes))
    }

    @Test
    fun `参照が取られうる止まりうる関数は、要素セグメントのどの形・グローバル・exportでも、call_indirectを止まりうるとみなす`() {
        val forms = linkedMapOf(
            "能動の要素（関数の番号の並び）" to "(elem (i32.const 1) \$f)",
            "能動の要素（式）" to "(elem (i32.const 1) funcref (ref.func \$f))",
            "パッシブの要素（関数の番号の並び）" to "(elem func \$f)",
            "パッシブの要素（式）" to "(elem funcref (ref.func \$f))",
            "宣言の要素" to "(elem declare func \$f)",
            "グローバルの初期値" to "(global funcref (ref.func \$f))",
            "export" to "(export \"f\" (func \$f))",
        )
        for ((name, refs) in forms) {
            val wasm = module(refs)
            assertTrue("run" in exports(wasm), name)
            assertEquals(2, Asyncify.transform(wasm, setOf("w.wait")).instrumented, name)
        }
        // 止まりうる import そのものを表に入れたときも
        val direct = module("", table = "(elem (i32.const 0) \$wait)")
        assertEquals(setOf("run"), exports(direct))
    }

    /** 初期値の式を持つテーブル（関数参照の提案）は、式の `ref.func` を追わず、型だけで絞る前の判定に戻る */
    @Test
    fun `初期値の式を持つテーブルがあれば、型だけで判定する`() {
        val wasm = module("(table 1 funcref (ref.func \$f))")
        assertEquals(setOf("run"), exports(wasm))
    }

    /** 止まりうる関数の、参照が取られない呼び出しの鎖の先に、参照が取られる関数がある形。鎖の途中の型で絞らない（参照が取られるのは鎖の先頭） */
    @Test
    fun `参照の取られる関数が、参照の取られない止まりうる関数を呼ぶなら、その型のcall_indirectは止まりうる`() {
        val wasm = WitTool.wat(
            """
            (module
              (type ${'$'}v_i (func (result i32)))
              (type ${'$'}i_i (func (param i32) (result i32)))
              (import "w" "wait" (func ${'$'}wait (result i32)))
              (table 1 funcref)
              (elem (i32.const 0) ${'$'}h)
              (func ${'$'}f (type ${'$'}v_i) (call ${'$'}wait))
              (func ${'$'}h (type ${'$'}i_i) (call ${'$'}f))
              (func (export "a") (result i32) (call_indirect (type ${'$'}v_i) (i32.const 0)))
              ;; b は export するので参照が取られうる。型を a と分けておく（同じ () -> i32 だと、止まりうる b の型で a も止まりうる）
              (func (export "b") (type ${'$'}i_i) (call_indirect (type ${'$'}i_i) (local.get 0) (i32.const 0))))
            """.trimIndent(),
        )
        assertEquals(setOf("b"), exports(wasm))
    }

    /**
     * 呼び出しの鎖を、呼ばれる側ほど後ろに並べたモジュール（前の解析は、1 回の走査で 1 段ずつしか進まず、関数の数の回だけなめ直した）。
     * 作業リストの解析でも、答え（鎖のすべてが止まりうる）は同じ
     */
    @Test
    fun `逆順の長い呼び出しの鎖も、すべて止まりうる`() {
        val n = 200
        val funcs = (0 until n).joinToString("\n") { i ->
            val callee = if (i == n - 1) "\$wait" else "\$f${i + 1}"
            "  (func \$f$i (result i32) (call $callee))"
        }
        val wasm = WitTool.wat("(module\n  (import \"w\" \"wait\" (func \$wait (result i32)))\n$funcs\n  (export \"run\" (func \$f0)))")
        assertEquals(setOf("run"), exports(wasm))
        assertEquals(n, Asyncify.transform(wasm, setOf("w.wait")).instrumented)
        // 本体をなめる間、関数ごとに checkpoint を呼ぶ（時間の上限。#40）。解析と書き換えで、関数ごとに少なくとも 2 回
        var calls = 0
        Asyncify.transform(wasm, setOf("w.wait")) { calls++ }
        assertTrue(calls >= 2 * n, "checkpoint は $calls 回")
    }

    /** 変換した [bytes] の `run` を、巻き戻さずに呼ぶ（`w.wait` は呼ばれないはず） */
    private fun run(bytes: ByteArray): Long {
        assertNull(WitTool.validate(bytes))
        val noStack = object : WasmAsyncStack {
            override fun push(value: Long) = throw AssertionError("巻き戻さない")

            override fun pop(): Long = throw AssertionError("巻き戻さない")
        }
        val config = WasmInstanceConfig.builder()
            .hostFunction("w", "wait", WasmFunctionType.of(emptyList(), listOf(WasmValueType.I32))) { _, _ -> throw AssertionError("呼ばれない") }
            .asyncStack(noStack)
            .build()
        return EndiveRuntime().load(bytes).instantiate(config).use { it.call("run")[0] }
    }
}
