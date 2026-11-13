package pro.ikili.kibicomputers.core.runtime.component.canon

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.runtime.FakeMemory
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonStringEncoding.LATIN1_UTF16
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonStringEncoding.UTF16
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonStringEncoding.UTF8
import pro.ikili.kibicomputers.core.runtime.component.canon.RecordingRealloc.Call

/** `string` の 3 つの符号化の lift・lower と、realloc の呼び方（#264。仕様の `store_string_into_range`） */
class CanonStringsTest {
    private val memory = FakeMemory(1)
    private val realloc = RecordingRealloc(memory)
    private val tag = CanonLimits.UTF16_TAG

    private fun cx(e: CanonStringEncoding) = canonContext(memory, e, realloc)

    private fun store(e: CanonStringEncoding, v: CanonValue.Str): Pair<ByteArray, Long> {
        val r = CanonStrings.store(cx(e), v)
        val units = r[1]
        val bytes = when (e) {
            UTF8 -> units
            UTF16 -> 2 * units
            LATIN1_UTF16 -> if (units and tag != 0L) 2 * (units xor tag) else units
        }
        return memory.read(r[0].toInt(), bytes.toInt()) to units
    }

    private fun trap(block: () -> Unit) {
        val e = assertThrows<WasmTrapException> { block() }
        assertEquals(WasmTrapKind.OTHER, e.kind) { e.message }
    }

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun `UTF-8 へは正確な大きさで 1 回だけ取る`() {
        val (b, n) = store(UTF8, CanonValue.str("aé"))
        assertArrayEquals("aé".toByteArray(), b)
        assertEquals(3L, n)
        assertEquals(listOf(Call(0, 0, 1, 3)), realloc.calls)
    }

    @Test
    fun `UTF-8 から UTF-16 へは最悪の大きさで取り、短ければ縮める`() {
        val (b, n) = store(UTF16, CanonValue.str("aé"))
        assertArrayEquals(bytes(0x61, 0, 0xe9, 0), b)
        assertEquals(2L, n)
        assertEquals(Call(0, 0, 2, 6), realloc.calls[0])
        assertEquals(Call(realloc.calls[1].old, 6, 2, 4), realloc.calls[1])
        realloc.calls.clear()
        store(UTF16, CanonValue.str("ab"))
        assertEquals(listOf(Call(0, 0, 2, 4)), realloc.calls)
    }

    @Test
    fun `UTF-8 から latin1+utf16 へ、すべて latin1 なら縮める`() {
        val (b, n) = store(LATIN1_UTF16, CanonValue.str("aé"))
        assertArrayEquals(bytes(0x61, 0xe9), b)
        assertEquals(2L, n)
        assertEquals(Call(0, 0, 2, 3), realloc.calls[0])
        assertEquals(3, realloc.calls[1].oldSize)
        assertEquals(2, realloc.calls[1].newSize)
        assertEquals(2, realloc.calls.size)
    }

    @Test
    fun `UTF-8 から latin1+utf16 へ、2^8 以上の文字を見たら UTF-16 に広げて縮める`() {
        val (b, n) = store(LATIN1_UTF16, CanonValue.str("éあ"))
        assertArrayEquals(bytes(0xe9, 0, 0x42, 0x30), b)
        assertEquals(2L or tag, n)
        // UTF-8 で 5 バイト: 5 で取り、10 に広げ、4 に縮める
        assertEquals(listOf(5, 10, 4), realloc.calls.map { it.newSize })
        assertEquals(listOf(0, 5, 10), realloc.calls.map { it.oldSize })
    }

    @Test
    fun `UTF-16 で lift したものを UTF-8 へは、ASCII でない文字を見たら広げて縮める`() {
        memory.write(0x100, bytes(0x61, 0, 0xe9, 0))
        val s = CanonStrings.load(cx(UTF16), 0x100, 2)
        assertEquals("aé", s.value)
        realloc.calls.clear()
        val (b, n) = store(UTF8, s)
        assertArrayEquals("aé".toByteArray(), b)
        assertEquals(3L, n)
        // 元は 2 単位: 2 で取り、最悪の 6 に広げ、3 に縮める
        assertEquals(listOf(2, 6, 3), realloc.calls.map { it.newSize })
    }

    @Test
    fun `latin1+utf16 どうしは、latin1 なら写し、UTF-16 で latin1 だけなら詰めて縮める`() {
        memory.write(0x100, bytes(0x61, 0xe9))
        val latin = CanonStrings.load(cx(LATIN1_UTF16), 0x100, 2)
        assertEquals("aé", latin.value)
        realloc.calls.clear()
        val (b1, n1) = store(LATIN1_UTF16, latin)
        assertArrayEquals(bytes(0x61, 0xe9), b1)
        assertEquals(2L, n1)
        assertEquals(listOf(Call(0, 0, 2, 2)), realloc.calls)

        memory.write(0x200, bytes(0x61, 0, 0xe9, 0))
        val wide = CanonStrings.load(cx(LATIN1_UTF16), 0x200, (2L or tag).toInt())
        realloc.calls.clear()
        val (b2, n2) = store(LATIN1_UTF16, wide)
        assertArrayEquals(bytes(0x61, 0xe9), b2)
        assertEquals(2L, n2)
        assertEquals(listOf(4, 2), realloc.calls.map { it.newSize })

        memory.write(0x300, bytes(0x42, 0x30))
        val kana = CanonStrings.load(cx(LATIN1_UTF16), 0x300, (1L or tag).toInt())
        realloc.calls.clear()
        val (_, n3) = store(LATIN1_UTF16, kana)
        assertEquals(1L or tag, n3)
        assertEquals(listOf(2), realloc.calls.map { it.newSize })
        // latin1 の値を UTF-16 へ
        realloc.calls.clear()
        val (b4, n4) = store(UTF16, latin)
        assertArrayEquals(bytes(0x61, 0, 0xe9, 0), b4)
        assertEquals(2L, n4)
        assertEquals(listOf(Call(0, 0, 2, 4)), realloc.calls)
    }

    @Test
    fun `latin1 で lift したものを UTF-8 へは、最悪の大きさ（単位数の 2 倍）で広げて縮める`() {
        memory.write(0x100, bytes(0x61, 0xe9))
        val latin = CanonStrings.load(cx(LATIN1_UTF16), 0x100, 2)
        realloc.calls.clear()
        val (b, n) = store(UTF8, latin)
        assertArrayEquals("aé".toByteArray(), b)
        assertEquals(3L, n)
        // 元は 2 単位: 2 で取り、最悪の 4 に広げ、3 に縮める
        assertEquals(listOf(2, 4, 3), realloc.calls.map { it.newSize })
        assertEquals(listOf(0, 2, 4), realloc.calls.map { it.oldSize })
        assertEquals(listOf(1, 1, 1), realloc.calls.map { it.align })
        // ASCII だけなら、取ったまま（広げも縮めもしない）
        memory.write(0x200, bytes(0x61, 0x62))
        val ascii = CanonStrings.load(cx(LATIN1_UTF16), 0x200, 2)
        realloc.calls.clear()
        val (b2, n2) = store(UTF8, ascii)
        assertArrayEquals("ab".toByteArray(), b2)
        assertEquals(2L, n2)
        assertEquals(listOf(Call(0, 0, 1, 2)), realloc.calls)
    }

    @Test
    fun `UTF-16 で lift したものを UTF-16 へは、元と同じ大きさで 1 回だけ取って写す`() {
        memory.write(0x100, "a😀é".toByteArray(Charsets.UTF_16LE))
        val s = CanonStrings.load(cx(UTF16), 0x100, 4)
        assertEquals("a😀é", s.value)
        realloc.calls.clear()
        val (b, n) = store(UTF16, s)
        assertArrayEquals("a😀é".toByteArray(Charsets.UTF_16LE), b)
        assertEquals(4L, n)
        assertEquals(listOf(Call(0, 0, 2, 8)), realloc.calls)
    }

    @Test
    fun `UTF-16 で lift したものを latin1+utf16 へは、単位数で取り、縮めない`() {
        // すべて latin1: 2 単位で取り、そのまま（UTF-8 のバイト数ではなく単位数で数えるので、縮める realloc は起きない）
        memory.write(0x100, "aé".toByteArray(Charsets.UTF_16LE))
        val latin = CanonStrings.load(cx(UTF16), 0x100, 2)
        realloc.calls.clear()
        val (b, n) = store(LATIN1_UTF16, latin)
        assertArrayEquals(bytes(0x61, 0xe9), b)
        assertEquals(2L, n)
        assertEquals(listOf(Call(0, 0, 2, 2)), realloc.calls)
        // 2^8 以上の文字がある: 2 で取り、最悪の 4 に広げる。UTF-16 で 4 バイトなので縮めない
        memory.write(0x200, "éあ".toByteArray(Charsets.UTF_16LE))
        val wide = CanonStrings.load(cx(UTF16), 0x200, 2)
        realloc.calls.clear()
        val (b2, n2) = store(LATIN1_UTF16, wide)
        assertArrayEquals(bytes(0xe9, 0, 0x42, 0x30), b2)
        assertEquals(2L or tag, n2)
        assertEquals(listOf(2, 4), realloc.calls.map { it.newSize })
        assertEquals(listOf(0, 2), realloc.calls.map { it.oldSize })
    }

    @Test
    fun `4 バイトの文字とサロゲートの対`() {
        val (b, n) = store(UTF16, CanonValue.str("😀"))
        assertArrayEquals("😀".toByteArray(Charsets.UTF_16LE), b)
        assertEquals(2L, n)
        memory.write(0x100, "😀".toByteArray())
        assertEquals("😀", CanonStrings.load(cx(UTF8), 0x100, 4).value)
    }

    @Test
    fun `ホストの文字列の対になっていないサロゲートは U+FFFD にする`() {
        val s = CanonValue.str("a\uD800b")
        assertEquals("a�b", s.value)
        assertEquals(5L, s.taggedCodeUnits)
        assertEquals("😀", CanonValue.str("😀").value)
    }

    @Test
    fun `正しくない符号化はトラップ`() {
        memory.write(0x100, bytes(0xc3, 0x28))
        trap { CanonStrings.load(cx(UTF8), 0x100, 2) }
        // UTF-8 のサロゲート（CESU）
        memory.write(0x100, bytes(0xed, 0xa0, 0x80))
        trap { CanonStrings.load(cx(UTF8), 0x100, 3) }
        // UTF-16 の対になっていないサロゲート
        memory.write(0x100, bytes(0x00, 0xd8, 0x61, 0x00))
        trap { CanonStrings.load(cx(UTF16), 0x100, 2) }
        memory.write(0x100, bytes(0x00, 0xdc))
        trap { CanonStrings.load(cx(LATIN1_UTF16), 0x100, (1L or tag).toInt()) }
    }

    @Test
    fun `長さ・align・範囲の順に確かめる`() {
        // UTF-16 は align 2（長さ 0 でも）
        trap { CanonStrings.load(cx(UTF16), 1, 0) }
        trap { CanonStrings.load(cx(LATIN1_UTF16), 1, 0) }
        assertEquals("", CanonStrings.load(cx(UTF8), 1, 0).value)
        // 長さの上限は align より先
        val e = assertThrows<WasmTrapException> { CanonStrings.load(cx(UTF16), 1, 1 shl 27) }
        assertEquals(WasmTrapKind.OTHER, e.kind)
        // 長さの上限も align も OTHER なので、メッセージで見分ける
        assertTrue(e.message!!.contains("長すぎる")) { e.message }
        val oob = assertThrows<WasmTrapException> { CanonStrings.load(cx(UTF8), 0xfff0, 0x20) }
        assertEquals(WasmTrapKind.MEMORY_OUT_OF_BOUNDS, oob.kind)
    }

    @Test
    fun `lower でも、写すバイト数を realloc の前に予算から引く`() {
        fun budgeted(e: CanonStringEncoding, maxBytes: Long) =
            canonContext(memory, e, realloc, budget = CanonBudget(maxBytes = maxBytes))
        val utf16Lifted = CanonValue.Str("ab", UTF16, 2)
        val latin1Lifted = CanonValue.Str("ab", LATIN1_UTF16, 2L or tag)
        // (送り先の符号化, 値, 引く大きさ): 写す（copy）、UTF-8 へ広げる最悪の大きさ（toUtf8）、UTF-16 の最悪の大きさ、
        // latin1+utf16 の最悪の大きさ、UTF-16 で書いてから詰める大きさ
        val cases = listOf(
            Triple(UTF8, CanonValue.str("abcd"), 4L),
            Triple(UTF8, utf16Lifted, 6L),
            Triple(UTF16, CanonValue.str("abc"), 6L),
            Triple(LATIN1_UTF16, CanonValue.str("abc"), 6L),
            Triple(LATIN1_UTF16, latin1Lifted, 4L),
        )
        for ((e, v, need) in cases) {
            realloc.calls.clear()
            val ex = assertThrows<WasmTrapException> { CanonStrings.store(budgeted(e, need - 1), v) }
            assertEquals(WasmTrapKind.OTHER, ex.kind)
            assertTrue(ex.message!!.contains("バイト数")) { "$e $v: ${ex.message}" }
            assertTrue(realloc.calls.isEmpty()) { "$e $v" }
            CanonStrings.store(budgeted(e, need), v)
        }
        // 文字列 1 つも値 1 つとして数える
        val noValues = canonContext(memory, UTF8, realloc, budget = CanonBudget(maxValues = 0))
        trap { CanonStrings.store(noValues, CanonValue.str("a")) }
    }
}
