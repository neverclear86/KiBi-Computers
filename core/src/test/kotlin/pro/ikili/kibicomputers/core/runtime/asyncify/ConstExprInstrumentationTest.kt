package pro.ikili.kibicomputers.core.runtime.asyncify

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.runtime.Instrumentation
import pro.ikili.kibicomputers.core.runtime.WasmAsyncStack
import pro.ikili.kibicomputers.core.runtime.WasmAsyncState
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmSharedFuel
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.fuel.FuelInjector
import pro.ikili.kibicomputers.core.wasi.kibi.WitTool
import java.io.ByteArrayOutputStream

/**
 * グローバル・要素セグメントの初期値の式（定数式）の `v128.const` と GC の提案の命令を、燃料の計測コードの挿入（`FuelInjector`）と
 * 待機点への変換（[Asyncify]）がそのまま写すこと（#851）。止まりうる関数の中の v128 は、今までどおり断る（ADR 0316）
 */
class ConstExprInstrumentationTest {
    private val runtime = EndiveRuntime()

    /**
     * v128 のグローバル（不変・可変・ほかのグローバルを読む初期値）を持ち、止まりうる `run`（v128 を使わない）と、
     * 止まらない `lanes`（v128 のグローバルの lane を読む）を持つモジュール
     */
    private val v128Wat = """
        (module
          (import "w" "wait" (func ${'$'}wait (result i32)))
          (global ${'$'}v v128 (v128.const i32x4 11 22 33 44))
          (global ${'$'}w v128 (global.get ${'$'}v))
          (global ${'$'}m (mut v128) (v128.const i32x4 100 200 300 400))
          (global ${'$'}n i32 (i32.const 7))
          (func (export "lanes") (result i32)
            (i32.add (i32x4.extract_lane 1 (global.get ${'$'}w)) (i32x4.extract_lane 3 (global.get ${'$'}m))))
          (func (export "run") (result i32)
            (i32.add (i32.add (call ${'$'}wait) (call ${'$'}wait)) (global.get ${'$'}n))))
    """.trimIndent()

    private val v128Bytes by lazy { WitTool.wat(v128Wat) }

    /** `lanes` の答え（22 + 400） */
    private val lanes = 422L

    /** 1つ目の `v128.const` の、サブの opcode（12）の位置。グローバルのセクションの中にある */
    private fun v128ConstAt(bytes: ByteArray): Int {
        val (start, end) = section(bytes, 6)
        for (i in start until end - 1) if (bytes[i] == 0xFD.toByte() && bytes[i + 1] == 0x0C.toByte()) return i + 1
        error("v128.const がない")
    }

    /** セクション [id] の中身の範囲 */
    private fun section(bytes: ByteArray, id: Int): Pair<Int, Int> {
        var pos = 8
        while (pos < bytes.size) {
            val sid = bytes[pos++].toInt() and 0xFF
            var size = 0
            var shift = 0
            while (true) {
                val b = bytes[pos++].toInt() and 0xFF
                size = size or ((b and 0x7F) shl shift)
                shift += 7
                if (b and 0x80 == 0) break
            }
            if (sid == id) return pos to pos + size
            pos += size
        }
        error("セクション $id がない")
    }

    /** 1つ目の `v128.const` のサブの opcode を、正規でない LEB（`0x8C 0x00`）にしたもの（セクションの大きさを直す） */
    private fun nonCanonical(bytes: ByteArray): ByteArray {
        val at = v128ConstAt(bytes)
        val (start, end) = section(bytes, 6)
        val body = ByteArrayOutputStream().apply {
            write(bytes, start, at - start)
            write(0x8C)
            write(0x00)
            write(bytes, at + 1, end - at - 1)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            // セクションの頭（id と大きさ）は start の前にある。id の位置を探し直す
            var head = start - 1
            while (bytes[head - 1].toInt() and 0x80 != 0 || bytes[head].toInt() and 0xFF != 6) head--
            write(bytes, 0, head)
            write(6)
            var n = body.size
            do {
                var b = n and 0x7F
                n = n ushr 7
                if (n != 0) b = b or 0x80
                write(b)
            } while (n != 0)
            write(body)
            write(bytes, end, bytes.size - end)
        }.toByteArray()
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean =
        (0..haystack.size - needle.size).any { i -> needle.indices.all { haystack[i + it] == needle[it] } }

    private class Stack : WasmAsyncStack {
        val values = ArrayList<Long>()

        override fun push(value: Long) {
            values += value
        }

        override fun pop(): Long = values.removeAt(values.size - 1)
    }

    /**
     * 変換したモジュールの `run` を、`w.wait` で毎回巻き戻して巻き直しながら最後まで動かし、`run` と `lanes` の結果を返す。
     * `w.wait` は 1, 2, … を返す。[sharedFuel] なら共有の燃料で動かす
     */
    private fun drive(module: WasmModule, sharedFuel: Boolean = false): Pair<Long, Long> {
        var next = 0L
        var unwinds = 0
        val stack = Stack()
        val builder = WasmInstanceConfig.builder()
            .hostFunction("w", "wait", WasmFunctionType.of(emptyList(), listOf(WasmValueType.I32))) { instance, _ ->
                if (instance.asyncState == WasmAsyncState.REWINDING) {
                    instance.asyncState = WasmAsyncState.NORMAL
                    longArrayOf(++next)
                } else {
                    unwinds++
                    instance.asyncState = WasmAsyncState.UNWINDING
                    longArrayOf(0)
                }
            }
            .asyncStack(stack)
        if (sharedFuel) builder.sharedFuel(WasmSharedFuel(1L shl 40)) else if (module.isFuelMetered) builder.fuel(1L shl 40, null)
        return module.instantiate(builder.build()).use { instance ->
            var result = instance.call("run")
            while (instance.asyncState == WasmAsyncState.UNWINDING) {
                instance.asyncState = WasmAsyncState.REWINDING
                result = instance.call("run")
            }
            assertEquals(2, unwinds, "巻き戻していない")
            assertTrue(stack.values.isEmpty())
            result.single() to instance.call("lanes").single()
        }
    }

    /** 止めずに動かす（変換していないモジュール）。`w.wait` は 1, 2, … を返す */
    private fun plain(module: WasmModule, sharedFuel: Boolean = false): Pair<Long, Long> {
        var next = 0L
        val builder = WasmInstanceConfig.builder()
            .hostFunction("w", "wait", WasmFunctionType.of(emptyList(), listOf(WasmValueType.I32))) { _, _ -> longArrayOf(++next) }
            .asyncStack(Stack())
        if (sharedFuel) builder.sharedFuel(WasmSharedFuel(1L shl 40)) else if (module.isFuelMetered) builder.fuel(1L shl 40, null)
        return module.instantiate(builder.build()).use { it.call("run").single() to it.call("lanes").single() }
    }

    @Test
    fun `燃料の計測コードの挿入は、v128の定数式をそのまま写し、共有の形でも共有でない形でもlaneの値が正しい`() {
        for (shared in listOf(false, true)) {
            val out = FuelInjector.inject(v128Bytes, sharedGlobal = shared).bytes
            assertNull(WitTool.validate(out), "shared=$shared")
            // 即値の 16 バイトを含めて、そのまま写している
            val at = v128ConstAt(v128Bytes)
            assertTrue(contains(out, v128Bytes.copyOfRange(at - 1, at + 17)), "shared=$shared")
            assertEquals(10L to lanes, plain(runtime.load(out), sharedFuel = shared), "shared=$shared")
        }
    }

    @Test
    fun `正規でないLEBのサブのopcodeも、そのまま写す`() {
        val odd = nonCanonical(v128Bytes)
        assertNull(WitTool.validate(odd), "テストの入力が正しくない")
        val at = v128ConstAt(v128Bytes)
        val expected = byteArrayOf(0xFD.toByte(), 0x8C.toByte(), 0x00) + v128Bytes.copyOfRange(at + 1, at + 17)
        for (shared in listOf(false, true)) {
            val out = FuelInjector.inject(odd, sharedGlobal = shared).bytes
            assertNull(WitTool.validate(out), "shared=$shared")
            assertTrue(contains(out, expected), "shared=$shared")
        }
        val transformed = Asyncify.transform(odd, setOf("w.wait")).bytes
        assertNull(WitTool.validate(transformed))
        assertTrue(contains(transformed, expected))
        assertEquals(10L to lanes, drive(runtime.load(Instrumentation.apply(odd, setOf("w.wait")))))
    }

    @Test
    fun `止まりうる関数がv128を使わなければ、v128のグローバルがあっても変換でき、巻き戻しと巻き直しで正しく動く`() {
        val r = Asyncify.transform(v128Bytes, setOf("w.wait"))
        assertNull(WitTool.validate(r.bytes))
        assertEquals(1, r.instrumented)
        assertEquals(10L to lanes, drive(runtime.load(r.bytes)))
        // 計測あり（挿入 → 変換）。燃料切れも待ち点にする段1で通る
        val applied = Instrumentation.applyDetailed(v128Bytes, setOf("w.wait"))
        assertTrue(applied.fuelYield, "${applied.fuelYieldRejection}")
        assertNull(WitTool.validate(applied.bytes))
        assertEquals(10L to lanes, drive(runtime.load(applied.bytes)))
        // 共有の燃料の形（コンポーネントの core モジュール）
        val shared = Asyncify.transform(FuelInjector.inject(v128Bytes, sharedGlobal = true).bytes, setOf("w.wait")).bytes
        assertNull(WitTool.validate(shared))
        assertEquals(10L to lanes, drive(runtime.load(shared), sharedFuel = true))
    }

    @Test
    fun `止まりうる関数がなければ、v128のグローバルがあっても断らない`() {
        val r = Asyncify.transform(v128Bytes, emptySet())
        assertEquals(0, r.instrumented)
        assertNull(WitTool.validate(r.bytes))
        assertEquals(10L to lanes, plain(runtime.load(r.bytes)))
        val p = Program.loadMetered(runtime, v128Bytes, null, setOf("other.wait"))
        assertTrue(p.module.isAsyncified)
    }

    @Test
    fun `同じ入力からは、挿入と変換の後のバイト列が同じ`() {
        assertArrayEquals(Instrumentation.apply(v128Bytes, setOf("w.wait")), Instrumentation.apply(v128Bytes.copyOf(), setOf("w.wait")))
        assertArrayEquals(
            FuelInjector.inject(v128Bytes, sharedGlobal = true).bytes,
            FuelInjector.inject(v128Bytes.copyOf(), sharedGlobal = true).bytes,
        )
    }

    @Test
    fun `定数式に使えない0xFDの命令は、WASMとして読めないものとして断る`() {
        // v128.const（12）を i8x16.shuffle（13。即値の長さは同じ 16 バイト）に書き換える
        val bad = v128Bytes.copyOf().also { it[v128ConstAt(it)] = 0x0D }
        val fuel = assertThrows<WasmLoadException> { FuelInjector.inject(bad) }
        assertTrue(fuel.cause !is AsyncifyUnsupported, "${fuel.cause}")
        val async = assertThrows<WasmLoadException> { Asyncify.transform(bad, setOf("w.wait")) }
        assertTrue(async.cause !is AsyncifyUnsupported, "${async.cause}")
        val loaded = assertThrows<WasmLoadException> { Program.loadMetered(runtime, bad, null, setOf("w.wait")) }
        assertNull(Program.unsuspendableReason(loaded), loaded.message)
        // 即値の途中で切れたもの
        val cut = WitTool.wat("(module (global v128 (v128.const i64x2 0 0)))")
        val at = v128ConstAt(cut)
        val (start, end) = section(cut, 6)
        check(end == cut.size)
        val truncated = cut.copyOf(at + 5)
        // セクションの大きさを、切った後に合わせる（1 バイトの LEB）
        truncated[start - 1] = (truncated.size - start).toByte()
        assertThrows<WasmLoadException> { FuelInjector.inject(truncated) }
    }

    private fun assertUnsupportedV128(wat: String) {
        val bytes = WitTool.wat(wat)
        val e = assertThrows<AsyncifyUnsupported> { Asyncify.transform(bytes, setOf("w.wait")) }
        assertTrue(e.message!!.contains("v128"), e.message)
        // 計測あり（2段とも断られる）でも、変換できない作りとして断る
        val loaded = assertThrows<WasmLoadException> { Program.loadMetered(runtime, bytes, null, setOf("w.wait")) }
        assertTrue(Program.unsuspendableReason(loaded)?.contains("v128") == true, loaded.message)
    }

    @Test
    fun `止まりうる関数がv128を結果に持つと、正しくないモジュールを出さずに断る`() {
        assertUnsupportedV128(
            """
            (module
              (import "w" "wait" (func ${'$'}wait (result i32)))
              (global ${'$'}v v128 (v128.const i32x4 1 2 3 4))
              (func ${'$'}f (export "f") (result v128)
                (drop (call ${'$'}wait))
                (global.get ${'$'}v)))
            """.trimIndent(),
        )
    }

    @Test
    fun `待ち点を含むブロックがv128を結果に持つと断る`() {
        assertUnsupportedV128(
            """
            (module
              (import "w" "wait" (func ${'$'}wait (result i32)))
              (global ${'$'}v v128 (v128.const i32x4 1 2 3 4))
              (global ${'$'}m (mut v128) (v128.const i32x4 0 0 0 0))
              (func (export "run")
                (global.set ${'$'}m (block (result v128) (drop (call ${'$'}wait)) (global.get ${'$'}v)))))
            """.trimIndent(),
        )
    }

    @Test
    fun `v128の値を持ったまま待つと断る`() {
        assertUnsupportedV128(
            """
            (module
              (import "w" "wait" (func ${'$'}wait (result i32)))
              (global ${'$'}v v128 (v128.const i32x4 1 2 3 4))
              (global ${'$'}m (mut v128) (v128.const i32x4 0 0 0 0))
              (func (export "run")
                (global.get ${'$'}v)
                (drop (call ${'$'}wait))
                (global.set ${'$'}m)))
            """.trimIndent(),
        )
    }

    /** GC の提案の定数式の命令（0xFB の 8 つ）を、グローバルと要素セグメントの初期値に持つモジュール */
    private val gcWat = """
        (module
          (type ${'$'}s (struct (field i32)))
          (type ${'$'}a (array i32))
          (global ${'$'}i (ref i31) (ref.i31 (i32.const 5)))
          (global ${'$'}st (ref ${'$'}s) (struct.new ${'$'}s (i32.const 1)))
          (global ${'$'}sd (ref ${'$'}s) (struct.new_default ${'$'}s))
          (global ${'$'}ar (ref ${'$'}a) (array.new ${'$'}a (i32.const 1) (i32.const 3)))
          (global ${'$'}ad (ref ${'$'}a) (array.new_default ${'$'}a (i32.const 2)))
          (global ${'$'}af (ref ${'$'}a) (array.new_fixed ${'$'}a 2 (i32.const 1) (i32.const 2)))
          (global ${'$'}ex externref (extern.convert_any (ref.null any)))
          (global ${'$'}an anyref (any.convert_extern (ref.null extern)))
          (elem ${'$'}e i31ref (item (ref.i31 (i32.const 9))))
          (func (export "f") (result i32)
            (i32.add (i31.get_s (global.get ${'$'}i)) (array.len (global.get ${'$'}af)))))
    """.trimIndent()

    /**
     * 型の定義（struct・array）を持たない GC の定数式（`ref.i31`・`any.convert_extern`・`extern.convert_any`）のモジュール。
     * 待機点への変換は、struct・array の型の定義を持つモジュールを、止まりうる関数がなくても別の理由で断る（#851 の範囲の外）
     */
    private val gcLiteWat = """
        (module
          (global ${'$'}i (ref i31) (ref.i31 (i32.const 5)))
          (global ${'$'}ex externref (extern.convert_any (ref.null any)))
          (global ${'$'}an anyref (any.convert_extern (ref.null extern)))
          (elem ${'$'}e i31ref (item (ref.i31 (i32.const 9))))
          (func (export "f") (result i32) (i31.get_s (global.get ${'$'}i))))
    """.trimIndent()

    /** グローバルのセクションの中身（数の 1 バイトの後） */
    private fun globals(bytes: ByteArray): ByteArray = section(bytes, 6).let { (gs, ge) -> bytes.copyOfRange(gs + 1, ge) }

    @Test
    fun `燃料の計測コードの挿入は、GCの提案の定数式の命令もそのまま写す`() {
        val bytes = WitTool.wat(gcWat)
        for (shared in listOf(false, true)) {
            val out = FuelInjector.inject(bytes, sharedGlobal = shared).bytes
            assertNull(WitTool.validate(out), "shared=$shared")
            assertTrue(contains(out, globals(bytes)), "shared=$shared")
        }
    }

    @Test
    fun `待機点への変換は、GCの提案の定数式の命令もそのまま写す`() {
        val bytes = WitTool.wat(gcLiteWat)
        val transformed = Asyncify.transform(bytes, emptySet()).bytes
        assertNull(WitTool.validate(transformed))
        assertTrue(contains(transformed, globals(bytes)))
        val metered = Instrumentation.apply(bytes, setOf("w.wait"))
        assertNull(WitTool.validate(metered))
        assertTrue(contains(metered, globals(bytes)))
    }
}
