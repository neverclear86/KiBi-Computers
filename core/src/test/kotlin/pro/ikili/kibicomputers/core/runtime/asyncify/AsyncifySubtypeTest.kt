package pro.ikili.kibicomputers.core.runtime.asyncify

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.runtime.WasmAsyncStack
import pro.ikili.kibicomputers.core.runtime.WasmAsyncState
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.wasi.kibi.WitTool

/**
 * 関数の型の部分型（GC・関数参照の提案の `sub`）を使う間接呼び出しを、止まりうるとみなす（#937）。
 * Endive の `call_indirect` は、同じインスタンスの関数なら宣言した部分型を、ほかのインスタンスの関数なら構造の部分型を受け付ける。
 * 止まりうる関数が、型の違う（上位の型の）間接呼び出しから呼ばれても、呼んだ側を書き換えて巻き戻せること
 */
class AsyncifySubtypeTest {
    private class Stack : WasmAsyncStack {
        val values = ArrayList<Long>()

        override fun push(value: Long) {
            values += value
        }

        override fun pop(): Long = values.removeAt(values.size - 1)
    }

    /**
     * `w.wait`（`() -> i32`。1 回目は巻き戻し、巻き直しと 2 回目からは 41 を返す）を付けて、変換した `run` を呼び、巻き直して結果を返す。
     * 変換の出力は `wasm-tools validate` に通ること
     */
    private fun runWithWait(wasm: ByteArray, debug: AsyncifyDebug): Long {
        val bytes = Asyncify.transform(wasm, setOf("w.wait"), debug = debug).bytes
        assertNull(WitTool.validate(bytes))
        val module = EndiveRuntime(compile = false).load(bytes)
        val stack = Stack()
        var unwound = false
        val config = WasmInstanceConfig.builder()
            .hostFunction("w", "wait", WasmFunctionType.of(emptyList(), listOf(WasmValueType.I32))) { instance, _ ->
                if (unwound && instance.asyncState == WasmAsyncState.NORMAL) {
                    longArrayOf(41)
                } else if (instance.asyncState == WasmAsyncState.REWINDING) {
                    assertTrue(stack.values.isEmpty())
                    instance.asyncState = WasmAsyncState.NORMAL
                    longArrayOf(41)
                } else {
                    unwound = true
                    instance.asyncState = WasmAsyncState.UNWINDING
                    longArrayOf(0)
                }
            }
            .asyncStack(stack)
            .build()
        return module.instantiate(config).use { instance ->
            instance.call("run")
            assertEquals(WasmAsyncState.UNWINDING, instance.asyncState)
            instance.asyncState = WasmAsyncState.REWINDING
            instance.call("run")[0].also { assertTrue(stack.values.isEmpty()) }
        }
    }

    private fun exports(wasm: ByteArray, waits: Set<String>, external: Set<Asyncify.Signature> = emptySet()): Set<String> =
        Asyncify.prepare(wasm).analyze(waits, external).suspendableExports

    /**
     * Issue の例: 止まりうる関数 `$f` の型 `$s (param anyref)` は、`$t (param eqref)` の部分型。`run` は `call_indirect (type $t)` で `$f` を呼ぶ。
     * `run` は呼ぶ前にグローバルを 1 つ増やすので、`run` を書き換えないと、巻き直しでもう 1 回増やして結果が 1 ずれる
     */
    @Test
    fun `宣言した部分型の関数をcall_indirectで呼んでも、巻き戻して巻き直せる`() {
        val wasm = WitTool.wat(
            """
            (module
              (type ${'$'}t (sub (func (param eqref) (result i32))))
              (type ${'$'}s (sub ${'$'}t (func (param anyref) (result i32))))
              (import "w" "wait" (func ${'$'}wait (result i32)))
              (global ${'$'}g (mut i32) (i32.const 0))
              (table 1 funcref)
              (elem (i32.const 0) ${'$'}f)
              (func ${'$'}f (type ${'$'}s)
                (i32.add (call ${'$'}wait) (i32.const 1)))
              (func (export "run") (result i32)
                (global.set ${'$'}g (i32.add (global.get ${'$'}g) (i32.const 100)))
                (i32.add
                  (call_indirect (type ${'$'}t) (ref.null eq) (i32.const 0))
                  (global.get ${'$'}g))))
            """.trimIndent(),
        )
        assertEquals(setOf("run"), exports(wasm, setOf("w.wait")))
        for (debug in AsyncifyDebug.entries) assertEquals(142L, runWithWait(wasm, debug), "$debug")
    }

    /** 上位の型が 2 段（`$u <: $s <: $t`）で、結果も共変（`$t (result anyref)`、`$u (result i31ref)`）のもの */
    @Test
    fun `上位の型を推移的にたどり、結果の部分型でも止まりうるとみなす`() {
        val wasm = WitTool.wat(
            """
            (module
              (type ${'$'}t (sub (func (param eqref) (result anyref))))
              (type ${'$'}s (sub ${'$'}t (func (param anyref) (result eqref))))
              (type ${'$'}u (sub ${'$'}s (func (param anyref) (result i31ref))))
              (import "w" "wait" (func ${'$'}wait (result i32)))
              (global ${'$'}g (mut i32) (i32.const 0))
              (table 1 funcref)
              (elem (i32.const 0) ${'$'}f)
              (func ${'$'}f (type ${'$'}u)
                (global.set ${'$'}g (i32.add (global.get ${'$'}g) (call ${'$'}wait)))
                (ref.null i31))
              (func (export "run") (result i32)
                (global.set ${'$'}g (i32.add (global.get ${'$'}g) (i32.const 100)))
                (i32.add
                  (ref.is_null (call_indirect (type ${'$'}t) (ref.null eq) (i32.const 0)))
                  (global.get ${'$'}g))))
            """.trimIndent(),
        )
        assertEquals(setOf("run"), exports(wasm, setOf("w.wait")))
        for (debug in AsyncifyDebug.entries) assertEquals(142L, runWithWait(wasm, debug), "$debug")
    }

    /**
     * 止まりうる import 自身の型が部分型で、表に入っているもの（表には自分のインスタンスの関数として入り、宣言した import の型で照合される）と、
     * `call_ref`（参照の静的な型の部分型の関数を呼ぶ）。どちらも解析だけで確かめる
     */
    @Test
    fun `部分型の止まりうるimportへのcall_indirectと、部分型の関数へのcall_refも止まりうる`() {
        val viaImport = WitTool.wat(
            """
            (module
              (type ${'$'}t (sub (func (param eqref))))
              (type ${'$'}s (sub ${'$'}t (func (param anyref))))
              (import "w" "wait" (func ${'$'}wait (type ${'$'}s)))
              (table 1 funcref)
              (elem (i32.const 0) ${'$'}wait)
              (func (export "run")
                (call_indirect (type ${'$'}t) (ref.null eq) (i32.const 0))))
            """.trimIndent(),
        )
        assertEquals(setOf("run"), exports(viaImport, setOf("w.wait")))

        val viaRef = WitTool.wat(
            """
            (module
              (type ${'$'}t (sub (func (param eqref))))
              (type ${'$'}s (sub ${'$'}t (func (param anyref))))
              (import "w" "wait" (func ${'$'}wait (result i32)))
              (elem declare func ${'$'}f)
              (func ${'$'}f (type ${'$'}s) (drop (call ${'$'}wait)))
              (func (export "run")
                (call_ref ${'$'}t (ref.null eq) (ref.func ${'$'}f))))
            """.trimIndent(),
        )
        assertEquals(setOf("run"), exports(viaRef, setOf("w.wait")))
    }

    /** 止まりうる関数への末尾呼び出しは断る（ADR 0329）。上位の型の `return_call_indirect` も、止まりうる関数への末尾呼び出しになる */
    @Test
    fun `上位の型のreturn_call_indirectは、止まりうる関数への末尾呼び出しとして断る`() {
        val wasm = WitTool.wat(
            """
            (module
              (type ${'$'}t (sub (func (param eqref) (result i32))))
              (type ${'$'}s (sub ${'$'}t (func (param anyref) (result i32))))
              (import "w" "wait" (func ${'$'}wait (result i32)))
              (table 1 funcref)
              (elem (i32.const 0) ${'$'}f)
              (func ${'$'}f (type ${'$'}s) (call ${'$'}wait))
              (func (export "run") (result i32)
                (return_call_indirect (type ${'$'}t) (ref.null eq) (i32.const 0))))
            """.trimIndent(),
        )
        assertThrows<AsyncifyUnsupported> { Asyncify.transform(wasm, setOf("w.wait")) }
    }

    /**
     * `sub` のない型は今までどおり: 並びが違えば、止まりうる関数の型と同じ数の参照を持っていても止まりうるとみなさない
     * （funcref と externref の入れ替え。出力も変わらない）
     */
    @Test
    fun `部分型の宣言がなければ、並びの違う型のcall_indirectは止まりうるとみなさない`() {
        val wasm = WitTool.wat(
            """
            (module
              (type ${'$'}a (func (param funcref externref) (result i32)))
              (type ${'$'}b (func (param externref funcref) (result i32)))
              (import "w" "wait" (func ${'$'}wait (result i32)))
              (table 1 funcref)
              (elem (i32.const 0) ${'$'}f)
              (func ${'$'}f (type ${'$'}a) (call ${'$'}wait))
              (func (export "run") (result i32)
                (call_indirect (type ${'$'}b) (ref.null extern) (ref.null func) (i32.const 0))))
            """.trimIndent(),
        )
        assertEquals(emptySet<String>(), exports(wasm, setOf("w.wait")))
        val r = Asyncify.transform(wasm, setOf("w.wait"))
        assertEquals(1, r.instrumented)
    }

    /**
     * 外の型（ほかのモジュールの止まりうる関数の型。#757）は、構造で合いうるなら当てる（ほかのインスタンスの関数は、Endive が
     * `sub` の宣言なしに構造の部分型で照合する）。参照の位置は同じ階層（any・func・extern・exn）なら合う。追えない参照型は、どの参照にも合う
     */
    @Test
    fun `外の型は、参照の階層が同じなら構造で合いうるとみなす`() {
        fun table(param: String) = WitTool.wat(
            """
            (module
              (type ${'$'}t (func (param (ref null $param)) (result i32)))
              (table (export "t") 1 funcref)
              (func (export "run") (result i32)
                (call_indirect (type ${'$'}t) (ref.null $param) (i32.const 0))))
            """.trimIndent(),
        )
        val anyParam = setOf(Asyncify.Signature(listOf(0x6E), listOf(0x7F)))
        // 外の関数が (param anyref)、呼び出しが (param eqref)・(param i31ref)・(param none)。どれも any の階層
        for (p in listOf("eq", "i31", "none", "any")) assertEquals(setOf("run"), exports(table(p), emptySet(), anyParam), p)
        // 階層が違えば合わない
        for (p in listOf("extern", "func")) assertEquals(emptySet<String>(), exports(table(p), emptySet(), anyParam), p)
        // 追えない参照型（-1）は、どの参照の位置にも合う。数値の位置には合わない
        val untyped = setOf(Asyncify.Signature(listOf(-1), listOf(0x7F)))
        for (p in listOf("extern", "func", "eq")) assertEquals(setOf("run"), exports(table(p), emptySet(), untyped), p)
        val numeric = WitTool.wat(
            """
            (module
              (type ${'$'}t (func (param i32) (result i32)))
              (table (export "t") 1 funcref)
              (func (export "run") (result i32)
                (call_indirect (type ${'$'}t) (i32.const 1) (i32.const 0))))
            """.trimIndent(),
        )
        assertEquals(emptySet<String>(), exports(numeric, emptySet(), untyped))
    }
}
