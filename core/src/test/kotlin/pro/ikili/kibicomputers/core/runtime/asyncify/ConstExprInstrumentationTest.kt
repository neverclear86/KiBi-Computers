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
import pro.ikili.kibicomputers.core.runtime.WasmStateException
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentHost
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.fuel.FuelInjector
import pro.ikili.kibicomputers.core.wasi.kibi.WitTool
import run.endive.wasm.InvalidException
import run.endive.wasm.MalformedException
import run.endive.wasm.Parser
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

    /**
     * 止まりうる関数 `run` そのものが、待ち点をまたがずに v128 のグローバルを読み書きする（`global.get`・`global.set` だけ）モジュール。
     * 1つ目の待ちの前に `$m = $v`、2つの待ちの間に `$w = $m` と `$m = $z`。巻き直しで前の `global.set` をやり直したり飛ばし損ねたりすると、
     * `lanes`（`$w` の lane 1 と `$m` の lane 3）が変わる
     */
    private val v128SuspendWat = """
        (module
          (import "w" "wait" (func ${'$'}wait (result i32)))
          (global ${'$'}v v128 (v128.const i32x4 11 22 33 44))
          (global ${'$'}z v128 (v128.const i32x4 1000 2000 3000 4000))
          (global ${'$'}m (mut v128) (v128.const i32x4 0 0 0 0))
          (global ${'$'}w (mut v128) (v128.const i32x4 0 0 0 0))
          (func (export "lanes") (result i32)
            (i32.add (i32x4.extract_lane 1 (global.get ${'$'}w)) (i32x4.extract_lane 3 (global.get ${'$'}m))))
          (func (export "run") (result i32)
            (local ${'$'}s i32)
            (global.set ${'$'}m (global.get ${'$'}v))
            (local.set ${'$'}s (call ${'$'}wait))
            (global.set ${'$'}w (global.get ${'$'}m))
            (global.set ${'$'}m (global.get ${'$'}z))
            (i32.add (local.get ${'$'}s) (i32.add (call ${'$'}wait) (i32.const 7)))))
    """.trimIndent()

    @Test
    fun `止まりうる関数が待ち点をまたがずにv128のグローバルを読み書きしても、巻き戻しと巻き直しで正しく動く`() {
        val bytes = WitTool.wat(v128SuspendWat)
        // `$w` は 22（1つ目の待ちの前の `$m = $v`）、`$m` は 4000（2つの待ちの間の `$m = $z`）
        val expected = 10L to 4022L
        val interpreter = EndiveRuntime(compile = false)
        val r = Asyncify.transform(bytes, setOf("w.wait"))
        assertEquals(1, r.instrumented)
        assertNull(WitTool.validate(r.bytes))
        // コンパイルあり（v128 を使う関数はインタプリタに回る）と、インタプリタだけ
        assertEquals(expected, drive(runtime.load(r.bytes)), "コンパイルあり")
        assertEquals(expected, drive(interpreter.load(r.bytes)), "インタプリタだけ")
        // 計測あり（挿入 → 変換）
        val applied = Instrumentation.applyDetailed(bytes, setOf("w.wait"))
        assertTrue(applied.fuelYield, "${applied.fuelYieldRejection}")
        assertNull(WitTool.validate(applied.bytes))
        assertEquals(expected, drive(runtime.load(applied.bytes)), "計測あり・コンパイルあり")
        assertEquals(expected, drive(interpreter.load(applied.bytes)), "計測あり・インタプリタだけ")
        // 変換しないものと同じ答え
        assertEquals(expected, plain(runtime.load(bytes)))
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
    fun `定数式に使えない0xFDの命令は、変換できない作りではなく、Endiveの検証が断る`() {
        // v128.const（12）を i8x16.shuffle（13。即値の長さは同じ 16 バイト）に書き換える。定数式の写しに届く前に、最初の Parser.parse が断る
        val bad = v128Bytes.copyOf().also { it[v128ConstAt(it)] = 0x0D }
        val fuel = assertThrows<WasmLoadException> { FuelInjector.inject(bad) }
        assertTrue(fuel.message!!.startsWith("燃料の計測コードを挿入できない: "), fuel.message)
        assertTrue(fuel.message!!.contains("constant expression required"), fuel.message)
        assertTrue(fuel.cause is InvalidException, "${fuel.cause}")
        val async = assertThrows<WasmLoadException> { Asyncify.transform(bad, setOf("w.wait")) }
        assertTrue(async.message!!.startsWith("WASM として読めない: "), async.message)
        assertTrue(async.message!!.contains("constant expression required"), async.message)
        assertTrue(async.cause is InvalidException, "${async.cause}")
        // 計測ありの読み込みは、最初の段（挿入）で断る。変換できない作り（AsyncifyUnsupported）としては報告しない
        val loaded = assertThrows<WasmLoadException> { Program.loadMetered(runtime, bad, null, setOf("w.wait")) }
        assertNull(Program.unsuspendableReason(loaded), loaded.message)
        assertTrue(loaded.message!!.contains("constant expression required"), loaded.message)
        // 即値の途中で切れたもの
        val cut = WitTool.wat("(module (global v128 (v128.const i64x2 0 0)))")
        val at = v128ConstAt(cut)
        val (start, end) = section(cut, 6)
        check(end == cut.size)
        val truncated = cut.copyOf(at + 5)
        // セクションの大きさを、切った後に合わせる（1 バイトの LEB）
        truncated[start - 1] = (truncated.size - start).toByte()
        val truncatedFuel = assertThrows<WasmLoadException> { FuelInjector.inject(truncated) }
        assertTrue(truncatedFuel.cause is MalformedException, "${truncatedFuel.cause}")
        val truncatedAsync = assertThrows<WasmLoadException> { Asyncify.transform(truncated, emptySet()) }
        assertTrue(truncatedAsync.message!!.startsWith("WASM として読めない: "), truncatedAsync.message)
        assertTrue(truncatedAsync.cause is MalformedException, "${truncatedAsync.cause}")
    }

    /**
     * テーブルなどを持たず、要素セグメントを 1 つだけ持つモジュール。[flag] は要素セグメントの形式（5 = パッシブ、7 = 宣言）、
     * 要素の型は funcref、項目は [item]（終わりの 0x0B を含む定数式）が 1 つ。WAT では書けない（正しくない）ので、バイト列で作る
     */
    private fun elemModule(flag: Int, item: ByteArray): ByteArray {
        val content = b(1, flag, 0x70, 1) + item
        check(content.size < 0x80)
        return b(0x00, 0x61, 0x73, 0x6D, 1, 0, 0, 0, 9, content.size) + content
    }

    @Test
    fun `パッシブ・宣言の要素セグメントの定数式に使えない命令は、検証を通って写しに届き、写しが正しくないWASMとして断る`() {
        // Endive 1.2.0 の検証は、パッシブ・宣言の要素セグメント（flag 5・7）の初期値を確かめない。写しが断る
        val items = mapOf(
            // (elem funcref (item (i32.const 0) (drop) (ref.null func)))
            "定数式の命令 0x1A は定数式に使えない" to b(0x41, 0x00, 0x1A, 0xD0, 0x70, 0x0B),
            // i8x16.swizzle
            "定数式の命令 0xFD 14 は定数式に使えない" to b(0xFD, 14, 0x0B),
        )
        for (flag in listOf(5, 7)) {
            for ((text, item) in items) {
                val bytes = elemModule(flag, item)
                assertTrue(WitTool.validate(bytes) != null, "テストの入力が正しい WASM になっている（flag $flag: $text）")
                // 検証は通る（Endive の穴。#893）
                Parser.parse(bytes)
                val name = "flag $flag: $text"
                val fuel = assertThrows<WasmLoadException>(name) { FuelInjector.inject(bytes) }
                assertEquals(text, fuel.message, name)
                assertNull(fuel.cause, name)
                // 計測なしの変換も、変換できない作り（AsyncifyUnsupported）ではなく、正しくない WASM として断る（止まりうる関数の有無によらない）
                for (waits in listOf(emptySet(), setOf("w.wait"))) {
                    val async = assertThrows<WasmLoadException>("$name, $waits") { Asyncify.transform(bytes, waits) }
                    assertEquals(text, async.message, "$name, $waits")
                    assertNull(async.cause, "$name, $waits")
                }
                // 計測ありの読み込み（exec の経路）
                val loaded = assertThrows<WasmLoadException>(name) { Program.loadMetered(runtime, bytes, null, setOf("w.wait")) }
                assertTrue(loaded.message!!.contains(text), "$name: ${loaded.message}")
                assertNull(Program.unsuspendableReason(loaded), "$name: ${loaded.message}")
            }
        }
    }

    // ---- 定数式の写しの各分岐（検証が先に断る形もあるので、写しを直接呼んでも確かめる） ----

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun asyncifyCopy(bytes: ByteArray): ByteArray {
        val sink = Sink()
        Reader(bytes, 0, bytes.size).copyConstExpr(sink) { it }
        return sink.toByteArray()
    }

    private fun fuelCopy(bytes: ByteArray): ByteArray {
        val sink = FuelInjector.Sink()
        FuelInjector.Reader(bytes, 0, bytes.size).copyConstExpr(sink, { it }, { it })
        return sink.toByteArray()
    }

    /** 即値の 16 バイト（1, 2, …, 16） */
    private val lanes16 = ByteArray(16) { (it + 1).toByte() }

    @Test
    fun `定数式の写しは、v128_constとGCの提案の命令を、バイト列のまま写す`() {
        val cases = mapOf(
            "v128.const" to b(0xFD, 0x0C) + lanes16 + b(0x0B),
            "v128.const（正規でない LEB のサブの opcode）" to b(0xFD, 0x8C, 0x00) + lanes16 + b(0x0B),
            "struct.new" to b(0xFB, 0x00, 0x03, 0x41, 0x01, 0x0B),
            "struct.new_default" to b(0xFB, 0x01, 0x03, 0x0B),
            "array.new" to b(0xFB, 0x06, 0x02, 0x41, 0x01, 0x41, 0x03, 0x0B),
            "array.new_default" to b(0xFB, 0x07, 0x02, 0x41, 0x02, 0x0B),
            "array.new_fixed" to b(0xFB, 0x08, 0x02, 0x03, 0x41, 0x01, 0x41, 0x02, 0x41, 0x03, 0x0B),
            "any.convert_extern" to b(0xFB, 0x1A, 0x0B),
            "extern.convert_any" to b(0xFB, 0x1B, 0x0B),
            "ref.i31" to b(0xFB, 0x1C, 0x0B),
            "型の番号が 2 バイトの LEB" to b(0xFB, 0x00, 0x80, 0x01, 0x0B),
            "続けて書いた命令" to b(0xFD, 0x0C) + lanes16 + b(0xFB, 0x1C, 0x41, 0x05, 0xFB, 0x1C, 0x0B),
        )
        for ((name, bytes) in cases) {
            assertArrayEquals(bytes, asyncifyCopy(bytes), "Asyncify: $name")
            assertArrayEquals(bytes, fuelCopy(bytes), "FuelInjector: $name")
        }
    }

    private fun assertRejected(name: String, bytes: ByteArray, text: String) {
        val a = assertThrows<WasmLoadException>("Asyncify: $name") { asyncifyCopy(bytes) }
        assertTrue(a.message!!.contains(text), "Asyncify: $name: ${a.message}")
        assertNull(a.cause, "Asyncify: $name")
        val f = assertThrows<WasmLoadException>("FuelInjector: $name") { fuelCopy(bytes) }
        assertTrue(f.message!!.contains(text), "FuelInjector: $name: ${f.message}")
        assertNull(f.cause, "FuelInjector: $name")
    }

    @Test
    fun `定数式の写しは、定数式に使えない命令を、WASMとして正しくないものとして断る`() {
        // 0xFD のサブの opcode が 12 でない（i8x16.shuffle。即値の長さは同じ 16 バイト）
        assertRejected("0xFD 13", b(0xFD, 0x0D) + lanes16 + b(0x0B), "定数式の命令 0xFD 13 は定数式に使えない")
        // v128.const の即値が 16 バイトに足りない（終わりがちょうど 15 バイト後）
        assertRejected("即値が足りない", b(0xFD, 0x0C) + lanes16.copyOf(15), "セクションの終わりを超えて読んだ")
        // 0xFB のサブの opcode が定数式のもの以外（struct.get）
        assertRejected("0xFB 2", b(0xFB, 0x02, 0x00, 0x00, 0x0B), "定数式の命令 0xFB 2 は定数式に使えない")
        // 0xFB 9（array.new_data）も、この写しは受け付けない（定数式の命令ではない）
        assertRejected("0xFB 9", b(0xFB, 0x09, 0x00, 0x00, 0x0B), "定数式の命令 0xFB 9 は定数式に使えない")
        // 最後の else（nop・unreachable・i32.add でない算術など）
        assertRejected("0x01", b(0x01, 0x0B), "定数式の命令 0x01 は定数式に使えない")
        assertRejected("0x00", b(0x00, 0x0B), "定数式の命令 0x00 は定数式に使えない")
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

    /** 型の定義（struct・array）を持たない GC の定数式（`ref.i31`・`any.convert_extern`・`extern.convert_any`）のモジュール */
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

    /**
     * 止まりうる関数（`run`）と、型を持たない参照（anyref・externref・i31ref）のグローバル・要素セグメントを、GC の提案の定数式で初期化するモジュール。
     * `(ref i31)` のような型付きの参照型のグローバルは持たない（持つと、止まりうる関数があるときは変換が断る。下のテスト）
     */
    private val gcSuspendWat = """
        (module
          (import "w" "wait" (func ${'$'}wait (result i32)))
          (global ${'$'}i i31ref (ref.i31 (i32.const 5)))
          (global ${'$'}ex externref (extern.convert_any (ref.null any)))
          (global ${'$'}an anyref (any.convert_extern (ref.null extern)))
          (elem ${'$'}e i31ref (item (ref.i31 (i32.const 9))))
          (func (export "lanes") (result i32) (i31.get_s (global.get ${'$'}i)))
          (func (export "run") (result i32)
            (i32.add (i32.add (call ${'$'}wait) (call ${'$'}wait)) (i32.const 7))))
    """.trimIndent()

    @Test
    fun `待機点への変換は、止まりうる関数がなくても、GCの提案の定数式の命令もそのまま写す`() {
        // struct・array の型の定義を持つもの（gcWat）も、止まりうる関数がなければ断らない（#891）
        for (wat in listOf(gcLiteWat, gcWat)) {
            val bytes = WitTool.wat(wat)
            val transformed = Asyncify.transform(bytes, emptySet()).bytes
            assertNull(WitTool.validate(transformed))
            assertTrue(contains(transformed, globals(bytes)))
            val metered = Instrumentation.apply(bytes, setOf("w.wait"))
            assertNull(WitTool.validate(metered))
            assertTrue(contains(metered, globals(bytes)))
        }
    }

    @Test
    fun `struct・arrayの型を持ち止まりうる関数がないモジュールは、計測あり・変換ありで読み込めて、コンパイルありで動く`() {
        val bytes = WitTool.wat(gcWat)
        // exec の経路（挿入 → 変換。待つ import は使っていない）
        val p = Program.loadMetered(runtime, bytes, null, setOf("w.wait"))
        assertTrue(p.module.isAsyncified)
        assertTrue(p.module.isFuelMetered)
        // ref.i31 の 5 + array.new_fixed の長さ 2
        p.module.instantiate(WasmInstanceConfig.builder().fuel(1L shl 40, null).asyncStack(Stack()).build()).use {
            assertEquals(7L, it.call("f").single())
        }
        // 計測も変換もしない、製品の EndiveRuntime（コンパイルあり）の読み込み（simdFunctions が struct・array の型で落ちない）
        runtime.load(bytes).instantiate(WasmInstanceConfig.builder().build()).use { assertEquals(7L, it.call("f").single()) }
        // 同じ入力からは同じバイト列
        assertArrayEquals(Instrumentation.apply(bytes, setOf("w.wait")), Instrumentation.apply(bytes.copyOf(), setOf("w.wait")))
    }

    /**
     * 止まりうる関数（`run`）と、struct・array の型、型を持たない参照（structref・anyref・arrayref・eqref）のグローバル・要素セグメントを
     * `struct.new` などで初期化するモジュール。`lanes` は `ref.i31` の 5、struct のフィールドの 1、array の長さ 2、`struct.new_default` の 0 を足す
     */
    private val gcObjectSuspendWat = """
        (module
          (type ${'$'}s (struct (field i32)))
          (type ${'$'}a (array i32))
          (import "w" "wait" (func ${'$'}wait (result i32)))
          (global ${'$'}i i31ref (ref.i31 (i32.const 5)))
          (global ${'$'}st structref (struct.new ${'$'}s (i32.const 1)))
          (global ${'$'}sd anyref (struct.new_default ${'$'}s))
          (global ${'$'}ar arrayref (array.new_fixed ${'$'}a 2 (i32.const 1) (i32.const 2)))
          (global ${'$'}an eqref (array.new ${'$'}a (i32.const 1) (i32.const 3)))
          (elem ${'$'}e structref (item (struct.new ${'$'}s (i32.const 9))))
          (func (export "lanes") (result i32)
            (i32.add
              (i32.add (i31.get_s (global.get ${'$'}i)) (struct.get ${'$'}s 0 (ref.cast (ref ${'$'}s) (global.get ${'$'}st))))
              (i32.add (array.len (global.get ${'$'}ar)) (struct.get ${'$'}s 0 (ref.cast (ref ${'$'}s) (global.get ${'$'}sd))))))
          (func (export "run") (result i32)
            (i32.add (i32.add (call ${'$'}wait) (call ${'$'}wait)) (i32.const 7))))
    """.trimIndent()

    @Test
    fun `止まりうる関数があっても、struct_newなどの定数式と型を持たない参照のグローバルはそのまま写し、巻き戻しと巻き直しで正しく動く`() {
        val bytes = WitTool.wat(gcObjectSuspendWat)
        val interpreter = EndiveRuntime(compile = false)
        val r = Asyncify.transform(bytes, setOf("w.wait"))
        assertEquals(1, r.instrumented)
        assertNull(WitTool.validate(r.bytes))
        assertTrue(contains(r.bytes, globals(bytes)))
        assertEquals(10L to 8L, drive(runtime.load(r.bytes)), "コンパイルあり")
        assertEquals(10L to 8L, drive(interpreter.load(r.bytes)), "インタプリタだけ")
        // 計測あり（挿入 → 変換）
        val applied = Instrumentation.apply(bytes, setOf("w.wait"))
        assertNull(WitTool.validate(applied))
        assertTrue(contains(applied, globals(bytes)))
        assertEquals(10L to 8L, drive(runtime.load(applied)), "計測あり・コンパイルあり")
        assertEquals(10L to 8L, drive(interpreter.load(applied)), "計測あり・インタプリタだけ")
        // 共有の燃料の形（コンポーネントの core モジュール）
        val shared = Asyncify.transform(FuelInjector.inject(bytes, sharedGlobal = true).bytes, setOf("w.wait")).bytes
        assertNull(WitTool.validate(shared))
        assertEquals(10L to 8L, drive(runtime.load(shared), sharedFuel = true))
        // 変換しないものと同じ答え
        assertEquals(10L to 8L, plain(runtime.load(bytes)))
        // 同じ入力からは同じバイト列
        assertArrayEquals(applied, Instrumentation.apply(bytes.copyOf(), setOf("w.wait")))
    }

    @Test
    fun `recグループの後ろの番号の関数の型を、止まりうる関数が使っても変換できる`() {
        // 型の番号: $s = 0、$f = 1、$w = 2（rec グループの数は 2、平らにした型の数は 3）
        val bytes = WitTool.wat(
            """
            (module
              (rec (type ${'$'}s (struct (field i32))) (type ${'$'}f (func (result i32))))
              (type ${'$'}w (func (result i32)))
              (import "w" "wait" (func ${'$'}wait (type ${'$'}w)))
              (table 1 funcref)
              (elem (i32.const 0) func ${'$'}g)
              (func ${'$'}g (type ${'$'}f) (call ${'$'}wait))
              (func (export "lanes") (result i32) (struct.get ${'$'}s 0 (struct.new ${'$'}s (i32.const 3))))
              (func (export "run") (result i32)
                (i32.add (i32.add (call_indirect (type ${'$'}f) (i32.const 0)) (call ${'$'}wait)) (i32.const 7))))
            """.trimIndent(),
        )
        check(Parser.parse(bytes).typeSection().let { it.typeCount() < it.subTypeCount() })
        val r = Asyncify.transform(bytes, setOf("w.wait"))
        // $g と run
        assertEquals(2, r.instrumented)
        assertNull(WitTool.validate(r.bytes))
        assertEquals(10L to 3L, drive(runtime.load(r.bytes)))
        val applied = Instrumentation.apply(bytes, setOf("w.wait"))
        assertNull(WitTool.validate(applied))
        assertEquals(10L to 3L, drive(runtime.load(applied)))
    }

    @Test
    fun `止まりうる関数があって、struct・arrayの型と型付きの参照型のグローバルを持つと、型を追えないために断る`() {
        val typed = gcObjectSuspendWat.replace("(global ${'$'}st structref", "(global ${'$'}st (ref ${'$'}s)")
        check(typed != gcObjectSuspendWat)
        val bytes = WitTool.wat(typed)
        val e = assertThrows<AsyncifyUnsupported> { Asyncify.transform(bytes, setOf("w.wait")) }
        assertTrue(e.message!!.contains("型付きの参照型"), e.message)
        assertTrue(!e.message!!.contains("Exception"), e.message)
        val loaded = assertThrows<WasmLoadException> { Program.loadMetered(runtime, bytes, null, setOf("w.wait")) }
        val reason = Program.unsuspendableReason(loaded)
        assertTrue(reason?.contains("型付きの参照型") == true, loaded.message)
        assertTrue(!reason!!.contains("Exception"), reason)
        // 止まりうる関数がなければ断らない（#384）
        assertNull(WitTool.validate(Asyncify.transform(bytes, emptySet()).bytes))
    }

    @Test
    fun `製品のEndiveRuntimeで、struct・arrayの命令を動かせる`() {
        val bytes = WitTool.wat(
            """
            (module
              (type ${'$'}s (struct (field (mut i32)) (field i64)))
              (type ${'$'}a (array (mut i32)))
              (func (export "f") (result i32)
                (local ${'$'}x (ref null ${'$'}s))
                (local ${'$'}y (ref null ${'$'}a))
                (local.set ${'$'}x (struct.new ${'$'}s (i32.const 4) (i64.const 6)))
                (struct.set ${'$'}s 0 (local.get ${'$'}x) (i32.add (struct.get ${'$'}s 0 (local.get ${'$'}x)) (i32.const 1)))
                (local.set ${'$'}y (array.new ${'$'}a (i32.const 2) (i32.const 3)))
                (array.set ${'$'}a (local.get ${'$'}y) (i32.const 1) (i32.const 10))
                (i32.add
                  (i32.add (struct.get ${'$'}s 0 (local.get ${'$'}x)) (i32.wrap_i64 (struct.get ${'$'}s 1 (local.get ${'$'}x))))
                  (i32.add (array.get ${'$'}a (local.get ${'$'}y) (i32.const 1)) (array.len (local.get ${'$'}y))))))
            """.trimIndent(),
        )
        assertTrue(EndiveRuntime.simdFunctions(Parser.parse(bytes))!!.isEmpty())
        // 5 + 6 + 10 + 3
        for (rt in listOf(runtime, EndiveRuntime(compile = false))) {
            rt.load(bytes).instantiate(WasmInstanceConfig.builder().build()).use { assertEquals(24L, it.call("f").single()) }
        }
    }

    @Test
    fun `v128のフィールド・要素を持つstruct・arrayの型があると、モジュール全体をインタプリタで動かす`() {
        val struct = WitTool.wat(
            """
            (module
              (type ${'$'}s (struct (field v128) (field i32)))
              (func (export "f") (result i32)
                (i32x4.extract_lane 2 (struct.get ${'$'}s 0 (struct.new ${'$'}s (v128.const i32x4 1 2 3 4) (i32.const 0))))))
            """.trimIndent(),
        )
        val array = WitTool.wat(
            """
            (module
              (type ${'$'}a (array (mut v128)))
              (func (export "f") (result i32)
                (i32x4.extract_lane 2 (array.get ${'$'}a (array.new ${'$'}a (v128.const i32x4 1 2 3 4) (i32.const 1)) (i32.const 0)))))
            """.trimIndent(),
        )
        for (bytes in listOf(struct, array)) {
            assertNull(EndiveRuntime.simdFunctions(Parser.parse(bytes)))
            runtime.load(bytes).instantiate(WasmInstanceConfig.builder().build()).use { assertEquals(3L, it.call("f").single()) }
        }
        // v128 のない struct・array の型は、コンパイルの対象から外さない
        assertTrue(EndiveRuntime.simdFunctions(Parser.parse(WitTool.wat(gcWat)))!!.isEmpty())
    }

    @Test
    fun `止まりうる関数があっても、型を持たない参照のグローバルのGCの定数式はそのまま写し、巻き戻しと巻き直しで正しく動く`() {
        val bytes = WitTool.wat(gcSuspendWat)
        val r = Asyncify.transform(bytes, setOf("w.wait"))
        assertEquals(1, r.instrumented)
        assertNull(WitTool.validate(r.bytes))
        assertTrue(contains(r.bytes, globals(bytes)))
        assertEquals(10L to 5L, drive(runtime.load(r.bytes)))
        // 計測あり（挿入 → 変換）
        val applied = Instrumentation.apply(bytes, setOf("w.wait"))
        assertNull(WitTool.validate(applied))
        assertEquals(10L to 5L, drive(runtime.load(applied)))
    }

    @Test
    fun `止まりうる関数があって型付きの参照型（ref i31）のグローバルを持つと、定数式ではなく型を追えないために断る`() {
        val bytes = WitTool.wat(gcSuspendWat.replace("(global \$i i31ref", "(global \$i (ref i31)"))
        val e = assertThrows<AsyncifyUnsupported> { Asyncify.transform(bytes, setOf("w.wait")) }
        assertTrue(e.message!!.contains("型付きの参照型"), e.message)
        // 止まりうる関数がなければ、そのまま写す（#384）
        assertNull(WitTool.validate(Asyncify.transform(bytes, emptySet()).bytes))
    }

    // ---- 止まりうる関数の本体の GC の命令（#891） ----

    /** `run` は止まりうる（`w.wait` を呼ぶ）。[body] は `run` の本体（結果は i32）。`lanes` は 0 */
    private fun gcSuspendModule(body: String): ByteArray = WitTool.wat(
        """
        (module
          (type ${'$'}s (struct (field i32)))
          (type ${'$'}a (array i32))
          (import "w" "wait" (func ${'$'}wait (result i32)))
          (func ${'$'}use (param structref i32) (result i32) (i32.add (local.get 1) (ref.is_null (local.get 0))))
          (func (export "lanes") (result i32) (i32.const 0))
          (func (export "run") (result i32) $body))
        """.trimIndent(),
    )

    @Test
    fun `止まりうる関数の、待ち点を含むブロックの直下にGCの命令があると、対応していない命令として断る`() {
        for (body in listOf(
            // 待ち点の後
            "(drop (call \$wait)) (struct.get \$s 0 (struct.new \$s (i32.const 1)))",
            // 待ち点の前
            "(drop (struct.new \$s (i32.const 1))) (call \$wait)",
            // array の命令、待ち点を含む if の then の直下
            "(if (result i32) (i32.const 1) (then (drop (call \$wait)) (array.len (array.new_default \$a (i32.const 2)))) (else (i32.const 0)))",
        )) {
            val bytes = gcSuspendModule(body)
            val e = assertThrows<AsyncifyUnsupported>(body) { Asyncify.transform(bytes, setOf("w.wait")) }
            assertTrue(e.message!!.contains("SIMD・アトミック・GC などの拡張"), e.message)
            assertTrue(!e.message!!.contains("Exception"), e.message)
            // 計測あり（挿入 → 変換）でも、変換できない作りとして断る（読み込みの検証の失敗にしない）
            val loaded = assertThrows<WasmLoadException> { Program.loadMetered(runtime, bytes, null, setOf("w.wait")) }
            val reason = Program.unsuspendableReason(loaded)
            assertTrue(reason?.contains("GC などの拡張") == true, loaded.message)
            assertTrue(!reason!!.contains("Exception"), reason)
            // 止まりうる関数がなければ、そのまま写す（#384）
            assertNull(WitTool.validate(Asyncify.transform(bytes, emptySet()).bytes))
        }
    }

    @Test
    fun `止まりうる関数の、待ち点を含まない入れ子のブロックの中のGCの命令は、そのまま写して巻き戻しと巻き直しで正しく動く`() {
        val bytes = gcSuspendModule(
            """
            (i32.add
              (i32.add
                (i32.add (call ${'$'}wait) (block (result i32) (struct.get ${'$'}s 0 (struct.new ${'$'}s (i32.const 3)))))
                (call ${'$'}wait))
              (block (result i32) (array.len (array.new ${'$'}a (i32.const 0) (i32.const 4)))))
            """.trimIndent(),
        )
        // 1 + 3 + 2 + 4
        val r = Asyncify.transform(bytes, setOf("w.wait"))
        assertEquals(1, r.instrumented)
        assertNull(WitTool.validate(r.bytes))
        assertEquals(10L to 0L, drive(runtime.load(r.bytes)), "コンパイルあり")
        assertEquals(10L to 0L, drive(EndiveRuntime(compile = false).load(r.bytes)), "インタプリタだけ")
        val applied = Instrumentation.apply(bytes, setOf("w.wait"))
        assertNull(WitTool.validate(applied))
        assertEquals(10L to 0L, drive(runtime.load(applied)), "計測あり")
        assertEquals(10L to 0L, plain(runtime.load(bytes)))
    }

    /** `run` を 1 回呼んで、`w.wait` で巻き戻す。呼び出しが投げた例外（なければ `null`）を返す */
    private fun unwindOnce(module: WasmModule): Throwable? {
        val builder = WasmInstanceConfig.builder()
            .hostFunction("w", "wait", WasmFunctionType.of(emptyList(), listOf(WasmValueType.I32))) { instance, _ ->
                instance.asyncState = WasmAsyncState.UNWINDING
                longArrayOf(0)
            }
            .asyncStack(Stack())
        if (module.isFuelMetered) builder.fuel(1L shl 40, null)
        return module.instantiate(builder.build()).use { instance -> runCatching { instance.call("run") }.exceptionOrNull() }
    }

    @Test
    fun `structrefのローカルが待ち点をまたぐとき、nullなら巻き戻して巻き直せ、nullでなければ巻き戻しで止まる`() {
        fun module(init: String) = gcSuspendModule(
            """
            (local ${'$'}x structref)
            (local.set ${'$'}x $init)
            (i32.add (i32.add (call ${'$'}wait) (call ${'$'}wait)) (ref.is_null (local.get ${'$'}x)))
            """.trimIndent(),
        )
        // null: 巻き戻しの検査を通る（null のまま戻す）ので、巻き直せる。1 + 2 + 1
        val r = Asyncify.transform(module("(block (result structref) (ref.null struct))"), setOf("w.wait"))
        assertNull(WitTool.validate(r.bytes))
        assertEquals(4L to 0L, drive(runtime.load(r.bytes)))
        assertEquals(4L to 0L, drive(EndiveRuntime(compile = false).load(r.bytes)))
        // null でない: 参照は保存できないので、巻き戻しの中の unreachable で止まる
        val o = Asyncify.transform(module("(block (result structref) (struct.new ${'$'}s (i32.const 1)))"), setOf("w.wait"))
        assertNull(WitTool.validate(o.bytes))
        for (rt in listOf(runtime, EndiveRuntime(compile = false))) {
            val e = unwindOnce(rt.load(o.bytes))
            assertTrue(e is WasmTrapException, "巻き戻しで止まらなかった: $e")
        }
    }

    @Test
    fun `structrefの値がオペランドスタックに載ったまま待つときも、nullなら巻き直せ、nullでなければ巻き戻しで止まる`() {
        fun module(value: String) = gcSuspendModule("(i32.add (call ${'$'}use $value (call ${'$'}wait)) (call ${'$'}wait))")
        // use(null, 1) = 1 + 1、+ 2
        val r = Asyncify.transform(module("(block (result structref) (ref.null struct))"), setOf("w.wait"))
        assertNull(WitTool.validate(r.bytes))
        assertEquals(4L to 0L, drive(runtime.load(r.bytes)))
        val o = Asyncify.transform(module("(block (result structref) (struct.new ${'$'}s (i32.const 1)))"), setOf("w.wait"))
        assertNull(WitTool.validate(o.bytes))
        val e = unwindOnce(runtime.load(o.bytes))
        assertTrue(e is WasmTrapException, "巻き戻しで止まらなかった: $e")
    }

    @Test
    fun `ref_nullの型の番号（struct・arrayの型）の値が待ち点をまたぐと、正しくないモジュールを出さずに断る`() {
        // 型の番号の ref.null $s は、略記のバイトを持たないので一時ローカルの型にできない
        val crossing = gcSuspendModule("(i32.add (call ${'$'}use (ref.null ${'$'}s) (call ${'$'}wait)) (call ${'$'}wait))")
        val e = assertThrows<AsyncifyUnsupported> { Asyncify.transform(crossing, setOf("w.wait")) }
        assertTrue(e.message!!.contains("型付きの参照型"), e.message)
        assertTrue(!e.message!!.contains("Exception"), e.message)
        val loaded = assertThrows<WasmLoadException> { Program.loadMetered(runtime, crossing, null, setOf("w.wait")) }
        val reason = Program.unsuspendableReason(loaded)
        assertTrue(reason?.contains("型付きの参照型") == true, loaded.message)
        assertTrue(!reason!!.contains("Exception"), reason)
        // 待ち点をまたがなければ（すぐ捨てる・待ち点の前に使い切る）、そのまま写して動く
        val dropped = gcSuspendModule("(drop (ref.null ${'$'}s)) (i32.add (call ${'$'}wait) (call ${'$'}wait))")
        val r = Asyncify.transform(dropped, setOf("w.wait"))
        assertNull(WitTool.validate(r.bytes))
        assertEquals(3L to 0L, drive(runtime.load(r.bytes)))
        val used = gcSuspendModule("(i32.add (call ${'$'}use (ref.null ${'$'}s) (i32.const 5)) (i32.add (call ${'$'}wait) (call ${'$'}wait)))")
        val u = Asyncify.transform(used, setOf("w.wait"))
        assertNull(WitTool.validate(u.bytes))
        // use(null, 5) = 6、+ 1 + 2
        assertEquals(9L to 0L, drive(runtime.load(u.bytes)))
    }

    /** struct・array の型を持つ core モジュールを中に持つコンポーネントは、検証で断られる（ADR 0757。#891 で読めるようになったのは p1 の core モジュールだけ） */
    @Test
    fun `struct・arrayの型を持つcoreモジュールを中に持つコンポーネントは、検証で断られる`() {
        val component = WitTool.wat("(component (core module" + gcWat.removePrefix("(module").removeSuffix(")") + "))")
        assertTrue(Program.isComponent(component))
        val e = assertThrows<WasmLoadException> {
            Program.loadExecutable(runtime, component, null, true, emptySet(), ComponentHost.NONE)
        }
        assertNull(Program.unsuspendableReason(e), "待機点への変換の断りではなく、検証の断りのはず: ${e.message}")
        // 同じ core モジュールは、p1 としては読める
        val p1 = Program.loadExecutable(runtime, WitTool.wat(gcWat), null, true, emptySet(), ComponentHost.NONE)
        assertTrue(p1.module.isFuelMetered)
    }

    // ---- 保存（captureState） ----

    @Test
    fun `GCのオブジェクトを初期値に持つグローバル・要素セグメントは、不変でも保存の写しの対象として見つける`() {
        fun found(wat: String) = EndiveRuntime.gcObjectInitializer(Parser.parse(WitTool.wat(wat)))
        assertEquals("グローバル 1", found(gcWat))
        assertEquals(
            "グローバル 0",
            found(
                """
                (module
                  (type ${'$'}s (struct (field (mut i32))))
                  (global ${'$'}g (ref ${'$'}s) (struct.new ${'$'}s (i32.const 1)))
                  (func (export "f")))
                """.trimIndent(),
            ),
        )
        assertEquals(
            "グローバル 0",
            found(
                """
                (module
                  (type ${'$'}a (array (mut i32)))
                  (global ${'$'}g (ref ${'$'}a) (array.new_default ${'$'}a (i32.const 2)))
                  (func (export "f")))
                """.trimIndent(),
            ),
        )
        assertEquals(
            "要素セグメント 0",
            found(
                """
                (module
                  (type ${'$'}s (struct (field (mut i32))))
                  (elem ${'$'}e (ref null ${'$'}s) (item (struct.new ${'$'}s (i32.const 1))))
                  (func (export "f")))
                """.trimIndent(),
            ),
        )
        // オブジェクトを作らない GC の定数式（ref.i31 など）と、v128 のグローバルは数えない
        assertNull(found(gcLiteWat))
        assertNull(found(v128Wat))
    }

    @Test
    fun `GCのオブジェクトを初期値に持つグローバル・要素セグメントがあると、実際のインスタンスのcaptureStateが断る`() {
        // 製品の経路（コンパイルあり）と、インタプリタだけのランタイムの両方（#891）
        for (rt in listOf(runtime, EndiveRuntime(compile = false))) assertGcCaptureRejected(rt)
    }

    private fun assertGcCaptureRejected(interpreter: EndiveRuntime) {
        val global = """
            (module
              (memory (export "memory") 1)
              (type ${'$'}s (struct (field (mut i32))))
              (global ${'$'}g (ref ${'$'}s) (struct.new ${'$'}s (i32.const 1)))
              (func (export "bump") (result i32)
                (struct.set ${'$'}s 0 (global.get ${'$'}g) (i32.add (struct.get ${'$'}s 0 (global.get ${'$'}g)) (i32.const 1)))
                (struct.get ${'$'}s 0 (global.get ${'$'}g))))
        """.trimIndent()
        interpreter.load(WitTool.wat(global)).instantiate(WasmInstanceConfig.builder().build()).use {
            assertEquals(2L, it.call("bump").single())
            val e = assertThrows<WasmStateException> { it.captureState() }
            assertEquals("GC のオブジェクトを初期値に持つグローバル 0", e.message)
        }
        val element = """
            (module
              (type ${'$'}a (array (mut i32)))
              (elem ${'$'}e (ref null ${'$'}a) (item (array.new_default ${'$'}a (i32.const 2))))
              (func (export "f")))
        """.trimIndent()
        interpreter.load(WitTool.wat(element)).instantiate(WasmInstanceConfig.builder().build()).use {
            val e = assertThrows<WasmStateException> { it.captureState() }
            assertEquals("GC のオブジェクトを初期値に持つ要素セグメント 0", e.message)
        }
        // オブジェクトを作らない GC の定数式（ref.i31 など）だけなら断らない
        interpreter.load(WitTool.wat(gcLiteWat)).instantiate(WasmInstanceConfig.builder().build()).use {
            assertEquals(5L, it.call("f").single())
            it.captureState()
        }
    }

    @Test
    fun `不変のv128のグローバルだけなら、保存の写しを取れ、グローバルは写さない（戻すのは MachineRestoreBranchesTest）`() {
        val wat = """
            (module
              (memory (export "memory") 1)
              (global ${'$'}v v128 (v128.const i32x4 11 22 33 44))
              (func (export "lane") (result i32) (i32x4.extract_lane 2 (global.get ${'$'}v))))
        """.trimIndent()
        runtime.load(WitTool.wat(wat)).instantiate(WasmInstanceConfig.builder().build()).use {
            assertEquals(33L, it.call("lane").single())
            assertTrue(it.captureState().globals.isEmpty(), "不変のグローバルは写さない")
        }
    }
}
