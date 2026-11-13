package pro.ikili.kibicomputers.core.runtime.component.canon

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.runtime.FakeMemory
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.component.CanonOption
import pro.ikili.kibicomputers.core.runtime.component.ComponentLoader
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.ListOf
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.Record
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.Str
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.U32
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.U8
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue.Fields
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue.I32
import pro.ikili.kibicomputers.core.runtime.component.canon.CoreFlat.I32 as FI32
import pro.ikili.kibicomputers.core.wasi.kibi.WitTool

/** 関数の lift・lower の計画（#264）と、コンポーネントの型の解決 */
class CanonFunctionTest {
    private val memory = FakeMemory(1)
    private val realloc = RecordingRealloc(memory)
    private val cx = canonContext(memory, realloc = realloc)

    private fun trap(kind: WasmTrapKind = WasmTrapKind.OTHER, block: () -> Unit) {
        val e = assertThrows<WasmTrapException> { block() }
        assertEquals(kind, e.kind) { e.message }
    }

    @Test
    fun `引数が 16 個までなら平らに、17 個ならポインタで渡す`() {
        val f16 = CanonFunction(List(16) { U8 }, null)
        assertEquals(List(16) { FI32 }, f16.lowerSignature.params)
        val f17 = CanonFunction(List(17) { U8 }, null)
        assertTrue(f17.paramsByPointer)
        assertEquals(listOf(FI32), f17.lowerSignature.params)
        assertEquals(listOf(FI32), f17.liftSignature.params)
        // ホスト → ゲスト: realloc で tuple の場所を取って書く
        val values = List(17) { I32(it + 1) }
        val core = f17.lowerParams(cx, values)
        assertEquals(RecordingRealloc.Call(0, 0, 1, 17), realloc.calls.single())
        // ゲスト → ホスト: 渡された場所から読む
        assertEquals(values, f17.liftParams(cx, core))
    }

    @Test
    fun `結果が 1 個を超えるなら、lower は最後の引数の書き先に、lift は返した場所から読む`() {
        val f = CanonFunction(listOf(U32), Str)
        assertTrue(f.resultByPointer)
        assertEquals(CanonFunction.CoreSignature(listOf(FI32, FI32), emptyList()), f.lowerSignature)
        assertEquals(CanonFunction.CoreSignature(listOf(FI32), listOf(FI32)), f.liftSignature)
        assertEquals(listOf(I32(7)), f.liftParams(cx, longArrayOf(7, 0x100)))
        assertArrayEquals(LongArray(0), f.lowerResult(cx, CanonValue.str("hi"), longArrayOf(7, 0x100)))
        val p = memory.readI32(0x100)
        assertEquals(2, memory.readI32(0x104))
        assertEquals("hi", String(memory.read(p, 2)))
        // export がその場所を返したら、同じ値として読める
        assertEquals(CanonValue.str("hi"), f.liftResult(cx, longArrayOf(0x100)))
        // 結果が 1 個なら平らに返す
        val g = CanonFunction(emptyList(), U32)
        assertArrayEquals(longArrayOf(5), g.lowerResult(cx, I32(5), LongArray(0)))
        assertEquals(I32(5), g.liftResult(cx, longArrayOf(5)))
        assertNull(CanonFunction(emptyList(), null).liftResult(cx, LongArray(0)))
    }

    @Test
    fun `結果の書き先は書くときに確かめ、だめなら realloc を呼ぶ前にトラップ`() {
        val f = CanonFunction(emptyList(), Str)
        trap { f.lowerResult(cx, CanonValue.str("x"), longArrayOf(0x102)) }
        trap(WasmTrapKind.MEMORY_OUT_OF_BOUNDS) { f.lowerResult(cx, CanonValue.str("x"), longArrayOf(memory.byteSize - 4)) }
        assertTrue(realloc.calls.isEmpty())
        // export が返した結果の場所も確かめる
        trap { f.liftResult(cx, longArrayOf(0x102)) }
        trap(WasmTrapKind.MEMORY_OUT_OF_BOUNDS) { f.liftResult(cx, longArrayOf(-4)) }
    }

    @Test
    fun `引数の場所の align と範囲を確かめる`() {
        val f = CanonFunction(listOf(U32) + List(16) { U8 }, null)
        trap { f.liftParams(cx, longArrayOf(2)) }
        trap(WasmTrapKind.MEMORY_OUT_OF_BOUNDS) { f.liftParams(cx, longArrayOf(memory.byteSize - 8)) }
    }

    @Test
    fun `平らにした引数をすべて lift してから返す`() {
        val rec = Record(listOf("a", "b"), listOf(U32, Str))
        val f = CanonFunction(listOf(rec, ListOf(U8)), null)
        assertEquals(List(5) { FI32 }, f.lowerSignature.params)
        memory.write(0x10, "ab".toByteArray())
        memory.write(0x20, byteArrayOf(1, 2))
        val args = f.liftParams(cx, longArrayOf(9, 0x10, 2, 0x20, 2))
        assertEquals(Fields(listOf(I32(9), CanonValue.str("ab"))), args[0])
        assertArrayEquals(byteArrayOf(1, 2), (args[1] as CanonValue.Bytes).bytes)
        // 2 つ目の引数の誤りは、1 つ目を読んだ後のトラップ
        trap(WasmTrapKind.MEMORY_OUT_OF_BOUNDS) { f.liftParams(cx, longArrayOf(9, 0x10, 2, 0xfffe, 4)) }
    }

    @Test
    fun `canon の option を読み、非同期と GC は断る`() {
        val o = CanonOptions.of(listOf(CanonOption.StringEncoding(1), CanonOption.Memory(0), CanonOption.Realloc(3), CanonOption.PostReturn(4)))
        assertEquals(CanonStringEncoding.UTF16, o.encoding)
        assertEquals(0, o.memory)
        assertEquals(3, o.realloc)
        assertEquals(4, o.postReturn)
        assertEquals(CanonStringEncoding.UTF8, CanonOptions.of(emptyList()).encoding)
        assertEquals(CanonStringEncoding.LATIN1_UTF16, CanonOptions.of(listOf(CanonOption.StringEncoding(2))).encoding)
        assertThrows<WasmLoadException> { CanonOptions.of(listOf(CanonOption.Async)) }
        assertThrows<WasmLoadException> { CanonOptions.of(listOf(CanonOption.Callback(1))) }
        assertThrows<WasmLoadException> { CanonOptions.of(listOf(CanonOption.Gc)) }
    }

    @Test
    fun `コンポーネントの型の添字空間を解決する`() {
        val bytes = WitTool.wat(
            """
            (component
              (type ${'$'}r (resource (rep i32)))
              (type ${'$'}rec (record (field "a" u32) (field "b" string)))
              (type ${'$'}v (variant (case "x" u8) (case "y" f32) (case "z")))
              (type ${'$'}o (own ${'$'}r))
              (type ${'$'}l (list ${'$'}rec))
              (type ${'$'}f (func (param "p" ${'$'}rec) (param "q" ${'$'}v) (param "h" ${'$'}o) (result ${'$'}l)))
              (type ${'$'}s (stream u8))
              (import "e" (type ${'$'}e (eq ${'$'}rec)))
              (import "t" (type ${'$'}t (sub resource)))
              (type (borrow ${'$'}t))
              (type (list ${'$'}s))
              (type (func async))
            )
            """.trimIndent(),
        )
        val space = CanonTypeSpace.of(ComponentLoader.load(bytes, policy = { null }).root)
        assertEquals(12, space.size)
        val rec = space.valueType(1)
        assertEquals(12 to 4, rec.size to rec.align)
        assertSame(rec, space.valueType(7))
        assertSame(rec, (space.valueType(4) as ListOf).element)
        assertEquals(listOf(FI32, FI32), space.valueType(2).flat)
        assertEquals(0, (space.valueType(3) as CanonType.Own).resource)
        assertEquals(0, space.resourceId(0))
        assertEquals(8, (space.valueType(9) as CanonType.Borrow).resource)
        val f = space.function(5)
        assertEquals(List(7) { FI32 }, f.lowerSignature.params)
        assertEquals(emptyList<CoreFlat>(), f.lowerSignature.results)
        assertEquals(List(6) { FI32 }, f.liftSignature.params)
        assertEquals(listOf(FI32), f.liftSignature.results)
        // まだ扱えない型は、求めたときに断る（それを使う型も）
        assertTrue(assertThrows<WasmLoadException> { space.valueType(6) }.message!!.contains("#266"))
        assertThrows<WasmLoadException> { space.valueType(10) }
        assertTrue(assertThrows<WasmLoadException> { space.function(11) }.message!!.contains("#266"))
        assertThrows<WasmLoadException> { space.valueType(0) }
        assertThrows<WasmLoadException> { space.function(1) }
        assertThrows<WasmLoadException> { space.valueType(12) }
    }

    @Test
    fun `wasm-tools が通す一番深い型の鎖も、上限の中で解決できる`() {
        // wasmparser は型の入れ子を 100 段までにしている（それより深いと検証で落ちる）。上限 MAX_TYPE_DEPTH も同じ深さ
        val n = 99
        fun chain(count: Int) = buildString {
            append("(component (type (option u8))")
            for (i in 1 until count) append(" (type (option ").append(i - 1).append("))")
            append(")")
        }
        val space = CanonTypeSpace.of(ComponentLoader.load(WitTool.wat(chain(n))).root)
        val deepest = space.valueType(n - 1)
        assertEquals(CanonLimits.MAX_TYPE_DEPTH, deepest.depth)
        // 一番深い型も、スタックを使い果たさずに受け渡せる
        var v: CanonValue = I32(7)
        repeat(n) { v = CanonValue.some(v) }
        val fn = CanonFunction(listOf(deepest), null)
        assertEquals(listOf(v), fn.liftParams(cx, fn.lowerParams(cx, listOf(v))))
        val e = assertThrows<WasmLoadException> { ComponentLoader.load(WitTool.wat(chain(n + 1))) }
        assertTrue(e.message!!.contains("too deep")) { e.message }
    }
}
