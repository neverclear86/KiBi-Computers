package pro.ikili.kibicomputers.core.runtime.component.canon

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.runtime.FakeMemory
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.Bool
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.F32
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.F64
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.Flags
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.ListOf
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.Option
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.Record
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.Result
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.S16
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.S8
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.Str
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.Tuple
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.U16
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.U32
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.U64
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.U8
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.Variant
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue.Case
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue.Fields
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue.I32
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue.I64
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue.Items

/**
 * Canonical ABI の lift・lower（#264）。仕様（component-model の `design/mvp/canonical-abi/run_tests.py`。Apache-2.0）の
 * 検査のうち、同期の値の受け渡しに当たるものを写して、型ごとの境界を足したもの
 */
class CanonAbiTest {
    private val memory: WasmMemory = FakeMemory(4)
    private val realloc = RecordingRealloc(memory)
    private val cx = canonContext(memory, realloc = realloc)

    private fun variant(vararg types: CanonType?) = Variant(types.indices.map { "c$it" }, types.toList())
    private fun record(vararg types: CanonType) = Record(types.indices.map { "f$it" }, types.toList())

    private fun liftFlat(t: CanonType, vararg values: Long, c: CanonContext = cx): CanonValue {
        val r = FlatReader(values)
        val v = CanonAbi.liftFlat(c, r, t)
        assertEquals(values.size, r.position) { "平らにした値を読みきっていない" }
        return v
    }

    private fun lowerFlat(t: CanonType, v: CanonValue, c: CanonContext = cx): LongArray =
        FlatWriter(4).also { CanonAbi.lowerFlat(c, v, t, it) }.toArray()

    private fun trap(kind: WasmTrapKind = WasmTrapKind.OTHER, block: () -> Unit): WasmTrapException {
        val e = assertThrows<WasmTrapException> { block() }
        assertEquals(kind, e.kind) { e.message }
        return e
    }

    private fun u32(v: Int) = v.toLong() and 0xffff_ffffL

    @Test
    fun `整数は上位のビットを捨て、bool は 0 以外が true`() {
        assertEquals(I32(0xff), liftFlat(U8, 0x1ff))
        assertEquals(I32(-1), liftFlat(S8, 0xff))
        assertEquals(I32(0xffff), liftFlat(U16, 0x1ffff))
        assertEquals(I32(-1), liftFlat(S16, 0xffff))
        assertEquals(I32(-1), liftFlat(U32, 0xffff_ffffL))
        assertEquals(CanonValue.TRUE, liftFlat(Bool, 2))
        assertEquals(CanonValue.FALSE, liftFlat(Bool, 0))
        // lower は上位を 0 にした 32 ビットで書く（s8 の -1 は 0xffffffff）
        assertArrayEquals(longArrayOf(0xffff_ffffL), lowerFlat(S8, I32(-1)))
        assertArrayEquals(longArrayOf(-1L), lowerFlat(U64, I64(-1)))
        assertThrows<IllegalArgumentException> { lowerFlat(U8, I32(256)) }
    }

    @Test
    fun `char はサロゲートと 0x110000 以上をトラップにする`() {
        assertEquals(CanonValue.Char(0x10ffff), liftFlat(CanonType.Char, 0x10ffff))
        assertEquals(CanonValue.Char(0xd7ff), liftFlat(CanonType.Char, 0xd7ff))
        trap { liftFlat(CanonType.Char, 0xd800) }
        trap { liftFlat(CanonType.Char, 0xdfff) }
        trap { liftFlat(CanonType.Char, 0x110000) }
        trap { liftFlat(CanonType.Char, 0xffff_ffffL) }
        memory.writeI32(0, 0xdc00)
        trap { CanonAbi.load(cx, 0, CanonType.Char) }
    }

    @Test
    fun `NaN は lift も lower も正規の NaN にする`() {
        val f = liftFlat(F32, 0x7fc0_0001L) as CanonValue.F32
        assertEquals(0x7fc00000, java.lang.Float.floatToRawIntBits(f.value))
        val d = liftFlat(F64, 0x7ff0_0000_0000_0001L) as CanonValue.F64
        assertEquals(0x7ff8000000000000L, java.lang.Double.doubleToRawLongBits(d.value))
        assertArrayEquals(longArrayOf(0x7fc00000L), lowerFlat(F32, CanonValue.F32(java.lang.Float.intBitsToFloat(0xffc00001.toInt()))))
        assertArrayEquals(longArrayOf(0x7ff8000000000000L), lowerFlat(F64, CanonValue.F64(java.lang.Double.longBitsToDouble(-1L))))
        // NaN でない値はビットのまま
        assertArrayEquals(longArrayOf(u32(java.lang.Float.floatToRawIntBits(-0.0f))), lowerFlat(F32, CanonValue.F32(-0.0f)))
        memory.writeI32(0, 0x7f800001)
        assertEquals(0x7fc00000, java.lang.Float.floatToRawIntBits((CanonAbi.load(cx, 0, F32) as CanonValue.F32).value))
    }

    @Test
    fun `variant は join した場所から読み、余りは 0 で埋める`() {
        val fv = variant(F32, U64)
        val bits = java.lang.Float.floatToRawIntBits(1.5f)
        assertArrayEquals(longArrayOf(0, u32(bits)), lowerFlat(fv, Case(0, CanonValue.F32(1.5f))))
        assertArrayEquals(longArrayOf(1, -1), lowerFlat(fv, Case(1, I64(-1))))
        // i64 の場所の上位は捨てて f32 として読む
        assertEquals(Case(0, CanonValue.F32(1.5f)), liftFlat(fv, 0, (0xdeadbeefL shl 32) or u32(bits)))
        val dv = variant(F64, F32)
        assertEquals(Case(1, CanonValue.F32(1.5f)), liftFlat(dv, 1, u32(bits)))
        val d = java.lang.Double.doubleToRawLongBits(2.5)
        assertArrayEquals(longArrayOf(0, d), lowerFlat(dv, Case(0, CanonValue.F64(2.5))))
        // i64 の場所から u32 を読むときは下位 32 ビット
        val uv = variant(U32, U64)
        assertEquals(Case(0, I32(7)), liftFlat(uv, 0, (1L shl 40) or 7))
        // 中身のない場合と、短い場合の余り
        val tv = variant(Tuple(listOf(U32, U32)), null, U8)
        assertArrayEquals(longArrayOf(1, 0, 0), lowerFlat(tv, Case(1, null)))
        assertArrayEquals(longArrayOf(2, 9, 0), lowerFlat(tv, Case(2, I32(9))))
        assertEquals(Case(2, I32(9)), liftFlat(tv, 2, 9, 12345))
        // 中身が複数の値の組み合わせ
        val mv = variant(Tuple(listOf(U8, F64)), Tuple(listOf(F32, U32)))
        assertArrayEquals(longArrayOf(1, u32(bits), 5), lowerFlat(mv, Case(1, Fields(listOf(CanonValue.F32(1.5f), I32(5))))))
        assertEquals(Case(1, Fields(listOf(CanonValue.F32(1.5f), I32(5)))), liftFlat(mv, 1, u32(bits), 5))
    }

    @Test
    fun `判別子が範囲の外ならトラップ`() {
        trap { liftFlat(Option(U8), 2, 0) }
        trap { liftFlat(CanonType.Enum(listOf("a", "b", "c")), 3) }
        trap { liftFlat(Result(null, null), 0xffff_ffffL) }
        memory.writeU8(0, 2)
        trap { CanonAbi.load(cx, 0, Option(U32)) }
        memory.writeU8(0, 1)
        assertEquals(CanonValue.err(null), CanonAbi.load(cx, 0, Result(null, null)))
    }

    @Test
    fun `flags は宣言の外のビットを捨てる`() {
        val f9 = Flags(List(9) { "f$it" })
        assertEquals(CanonValue.Flags(0x1ff), liftFlat(f9, 0xffff_ffffL))
        memory.writeI32(0, -1)
        assertEquals(CanonValue.Flags(0x1ff), CanonAbi.load(cx, 0, f9))
        assertEquals(CanonValue.Flags(-1), CanonAbi.load(cx, 0, Flags(List(32) { "f$it" })))
        CanonAbi.store(cx, CanonValue.Flags(0x101), f9, 8)
        assertEquals(0x01, memory.readU8(8))
        assertEquals(0x01, memory.readU8(9))
        assertThrows<IllegalArgumentException> { lowerFlat(f9, CanonValue.Flags(0x200)) }
    }

    @Test
    fun `record と variant をメモリに書いて読める`() {
        val t = record(U8, U64, Option(Str), ListOf(U16), variant(null, S16, F64))
        val v = Fields(
            listOf(
                I32(200),
                I64(Long.MIN_VALUE),
                CanonValue.some(CanonValue.str("こんにちは")),
                Items(listOf(I32(1), I32(0xffff))),
                Case(2, CanonValue.F64(-3.25)),
            ),
        )
        assertEquals(56, t.size)
        CanonAbi.store(cx, v, t, 0x100)
        assertEquals(200, memory.readU8(0x100))
        assertEquals(Long.MIN_VALUE, memory.readI64(0x108))
        assertEquals(1, memory.readU8(0x110))
        assertEquals(v, CanonAbi.load(cx, 0x100, t))
        // realloc は外から内へ、宣言の順に呼ぶ（文字列 → list）
        assertEquals(listOf(RecordingRealloc.Call(0, 0, 1, 15), RecordingRealloc.Call(0, 0, 2, 4)), realloc.calls)
    }

    @Test
    fun `list は長さ・align・範囲の順に確かめる`() {
        // 長さ 0 でも align を確かめる
        trap { CanonAbi.loadList(cx, 2, 0, U32) }
        assertEquals(Items(emptyList()), CanonAbi.loadList(cx, 4, 0, U32))
        // バイト数の上限は align より先
        trap { CanonAbi.loadList(cx, 1, 0x2000_0000, U64) }.also { assertTrue(it.message!!.contains("大きすぎる")) }
        // u32 の最大の長さでもあふれない
        trap { CanonAbi.loadList(cx, 0, -1, U8) }.also { assertTrue(it.message!!.contains("大きすぎる")) }
        trap(WasmTrapKind.MEMORY_OUT_OF_BOUNDS) { CanonAbi.loadList(cx, (memory.byteSize - 4).toInt(), 2, U32) }
        assertEquals(Items(listOf(I32(0))), CanonAbi.loadList(cx, (memory.byteSize - 4).toInt(), 1, U32))
        // ptr が上位のビットを持っても u32 として扱う
        trap(WasmTrapKind.MEMORY_OUT_OF_BOUNDS) { CanonAbi.loadList(cx, -8, 1, U32) }
        // list<u8> はまとめて写す
        memory.write(0x40, byteArrayOf(1, 2, 3))
        val b = CanonAbi.loadList(cx, 0x40, 3, U8) as CanonValue.Bytes
        assertArrayEquals(byteArrayOf(1, 2, 3), b.bytes)
    }

    @Test
    fun `list を lower すると realloc で取って書く`() {
        val r = CanonAbi.storeList(cx, Items(listOf(I64(1), I64(2))), ListOf(U64))
        assertEquals(RecordingRealloc.Call(0, 0, 8, 16), realloc.calls.single())
        assertEquals(2L, r[1])
        assertEquals(2L, memory.readI64(r[0].toInt() + 8))
        realloc.calls.clear()
        // 長さ 0 でも呼ぶ
        CanonAbi.storeList(cx, CanonValue.Bytes(ByteArray(0)), ListOf(U8))
        assertEquals(RecordingRealloc.Call(0, 0, 1, 0), realloc.calls.single())
    }

    @Test
    fun `realloc が返した場所を確かめる`() {
        realloc.override = 2
        trap { CanonAbi.storeList(cx, Items(listOf(I32(1))), ListOf(U32)) }
        realloc.override = (memory.byteSize - 4).toInt()
        trap(WasmTrapKind.MEMORY_OUT_OF_BOUNDS) { CanonAbi.storeList(cx, Items(listOf(I32(1), I32(2))), ListOf(U32)) }
        realloc.override = -4
        trap(WasmTrapKind.MEMORY_OUT_OF_BOUNDS) { CanonAbi.storeList(cx, Items(listOf(I32(1))), ListOf(U32)) }
        // realloc がない
        trap { CanonAbi.storeList(canonContext(memory, realloc = null), Items(emptyList()), ListOf(U32)) }
    }

    @Test
    fun `realloc の中でメモリが増えても、増えた後の大きさで確かめる`() {
        val m = FakeMemory(1)
        val grow = CanonRealloc { _, _, _, _ ->
            m.grow(1)
            0x10000
        }
        val c = canonContext(m, realloc = grow)
        val r = CanonAbi.storeList(c, CanonValue.Bytes(byteArrayOf(9)), ListOf(U8))
        assertEquals(0x10000L, r[0])
        assertEquals(9, m.readU8(0x10000))
    }

    @Test
    fun `同じ領域を指す要素で予算を超えたらトラップ`() {
        // 1 KiB の文字列 1 つを、20,000 個の要素がすべて指す（線形メモリは 4 ページ）
        val n = 20_000
        val strAt = 0x30000
        memory.write(strAt, ByteArray(1024) { 'a'.code.toByte() })
        for (i in 0 until n) {
            memory.writeI32(0x100 + i * 8, strAt)
            memory.writeI32(0x104 + i * 8, 1024)
        }
        val budget = CanonBudget()
        val e = trap { CanonAbi.loadList(canonContext(memory, budget = budget), 0x100, n, Str) }
        assertTrue(e.message!!.contains("バイト数")) { e.message }
        assertTrue(budget.usedBytes <= CanonLimits.DEFAULT_MAX_BYTES)
        // 値の数の上限（要素を作る前に止める）
        trap { CanonAbi.loadList(canonContext(memory, budget = CanonBudget(maxValues = 10)), 0x100, 11, U64) }
        // 予算の中なら読める
        val ok = CanonAbi.loadList(canonContext(memory), 0x100, 100, Str) as Items
        assertEquals(100, ok.items.size)
    }

    @Test
    fun `resource は差し込んだ口で lift・lower する`() {
        val res = object : CanonResources {
            override fun liftOwn(type: CanonType.Own, handle: Int): Int = handle * 10 + type.resource
            override fun liftBorrow(type: CanonType.Borrow, handle: Int): Int = -handle
            override fun lowerOwn(type: CanonType.Own, value: Int): Int = value + 1
            override fun lowerBorrow(type: CanonType.Borrow, value: Int): Int = value + 2
        }
        val c = canonContext(memory, resources = res)
        assertEquals(CanonValue.Handle(73), liftFlat(CanonType.Own(3), 7, c = c))
        assertEquals(CanonValue.Handle(-7), liftFlat(CanonType.Borrow(3), 7, c = c))
        assertArrayEquals(longArrayOf(6), lowerFlat(CanonType.Own(0), CanonValue.Handle(5), c))
        trap { liftFlat(CanonType.Own(0), 1) }
    }
}
