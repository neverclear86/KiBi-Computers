package pro.ikili.kibicomputers.core.runtime.component.canon

import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonContext.Companion.u32

/** 平らにした値を順に読む（Canonical ABI の `CoreValueIter`）。値は `long` のビット列（`WasmValueType` と同じ） */
internal class FlatReader(private val values: LongArray, start: Int = 0) {
    var position: Int = start
        private set

    fun next(): Long {
        if (position >= values.size) throw IllegalArgumentException("平らにした値が足りない（${values.size} 個）")
        return values[position++]
    }

    /** [position] を [to] まで進める（variant の使わなかった場所を読み飛ばす） */
    fun skipTo(to: Int) {
        require(to in position..values.size) { "平らにした値が足りない（${values.size} 個）" }
        position = to
    }
}

/** 平らにした値を順に書く。i32・f32 は下位 32 ビットに入れ、上位は 0 にする */
internal class FlatWriter(capacity: Int) {
    private var values = LongArray(capacity)
    var size: Int = 0
        private set

    fun add(v: Long) {
        if (size == values.size) values = values.copyOf(maxOf(4, size * 2))
        values[size++] = v
    }

    fun addI32(v: Int) = add(v.toLong() and 0xffff_ffffL)

    fun toArray(): LongArray = if (size == values.size) values else values.copyOf(size)
}

/**
 * Canonical ABI の lift・lower の本体（#264。ADR 0264）。仕様（`definitions.py`）の `load`・`store`・`lift_flat`・
 * `lower_flat` を写したもの。型ごとの再帰は型の深さ（[CanonType.depth]）までで、上限は [CanonLimits.MAX_TYPE_DEPTH]。
 *
 * ゲストの値の誤りは [WasmTrapException]。ホストの値が型に合わないのはホストの誤りで、[IllegalArgumentException]。
 * メモリの場所は、呼ぶ前に確かめてあること（load・store は確かめない。仕様と同じ）
 */
internal object CanonAbi {
    private const val CANONICAL_NAN32: Int = 0x7fc00000
    private const val CANONICAL_NAN64: Long = 0x7ff8000000000000L

    // ---- 浮動小数点数（決定的なプロファイル。lift も lower も NaN は正規の NaN にする） ----

    fun canonicalF32(bits: Int): Float =
        java.lang.Float.intBitsToFloat(if (java.lang.Float.isNaN(java.lang.Float.intBitsToFloat(bits))) CANONICAL_NAN32 else bits)

    fun canonicalF64(bits: Long): Double =
        java.lang.Double.longBitsToDouble(if (java.lang.Double.isNaN(java.lang.Double.longBitsToDouble(bits))) CANONICAL_NAN64 else bits)

    fun f32Bits(v: Float): Int = if (v.isNaN()) CANONICAL_NAN32 else java.lang.Float.floatToRawIntBits(v)

    fun f64Bits(v: Double): Long = if (v.isNaN()) CANONICAL_NAN64 else java.lang.Double.doubleToRawLongBits(v)

    private fun char(cx: CanonContext, i: Long): CanonValue.Char {
        if (i >= 0x110000) CanonTraps.trap("char が範囲の外（$i）")
        if (i in 0xd800L..0xdfffL) CanonTraps.trap("char がサロゲート（$i）")
        return CanonValue.Char(i.toInt())
    }

    // ---- load ----

    /** [ptr]（確かめた場所）から [t] を読む */
    @Throws(WasmTrapException::class)
    fun load(cx: CanonContext, ptr: Long, t: CanonType): CanonValue {
        cx.budget.value()
        val m = cx.mem
        val a = ptr.toInt()
        return when (t) {
            CanonType.Bool -> if (m.readU8(a) != 0) CanonValue.TRUE else CanonValue.FALSE
            CanonType.U8 -> CanonValue.I32(m.readU8(a))
            CanonType.S8 -> CanonValue.I32(m.readU8(a).toByte().toInt())
            CanonType.U16 -> CanonValue.I32(readU16(cx, a))
            CanonType.S16 -> CanonValue.I32(readU16(cx, a).toShort().toInt())
            CanonType.U32, CanonType.S32 -> CanonValue.I32(m.readI32(a))
            CanonType.U64, CanonType.S64 -> CanonValue.I64(m.readI64(a))
            CanonType.F32 -> CanonValue.F32(canonicalF32(m.readI32(a)))
            CanonType.F64 -> CanonValue.F64(canonicalF64(m.readI64(a)))
            CanonType.Char -> char(cx, u32(m.readI32(a)))
            CanonType.Str -> CanonStrings.load(cx, m.readI32(a), m.readI32(a + 4))
            is CanonType.ListOf -> loadList(cx, m.readI32(a), m.readI32(a + 4), t.element)
            is CanonType.Fields -> {
                val types = t.fieldTypes
                CanonValue.Fields(List(types.size) { i -> load(cx, ptr + t.offsets[i], types[i]) })
            }
            is CanonType.Cases -> {
                val disc = when (t.discSize) {
                    1 -> m.readU8(a).toLong()
                    2 -> readU16(cx, a).toLong()
                    else -> u32(m.readI32(a))
                }
                if (disc >= t.caseTypes.size) CanonTraps.trap("判別子が範囲の外（$disc。場合は ${t.caseTypes.size} 個）")
                val ct = t.caseTypes[disc.toInt()]
                CanonValue.Case(disc.toInt(), ct?.let { load(cx, ptr + t.payloadOffset, it) })
            }
            is CanonType.Flags -> {
                val bits = when (t.size) {
                    1 -> m.readU8(a)
                    2 -> readU16(cx, a)
                    else -> m.readI32(a)
                }
                CanonValue.Flags(bits and t.mask)
            }
            is CanonType.Own -> CanonValue.Handle(cx.resources.liftOwn(t, m.readI32(a)))
            is CanonType.Borrow -> CanonValue.Handle(cx.resources.liftBorrow(t, m.readI32(a)))
        }
    }

    private fun readU16(cx: CanonContext, a: Int): Int = cx.mem.readU8(a) or (cx.mem.readU8(a + 1) shl 8)

    /** `load_list_from_range`: 長さ（バイト数の上限）→ align → 範囲の順に確かめてから読む */
    @Throws(WasmTrapException::class)
    fun loadList(cx: CanonContext, ptrBits: Int, lengthBits: Int, element: CanonType): CanonValue {
        val ptr = u32(ptrBits)
        val length = u32(lengthBits)
        val byteLength = length * element.size
        if (byteLength > CanonLimits.MAX_LIST_BYTE_LENGTH) CanonTraps.trap("list が大きすぎる（$length 個 × ${element.size} バイト）")
        cx.checkAligned(ptr, element.align, "list")
        cx.checkInMemory(ptr, byteLength, "list")
        if (element === CanonType.U8) {
            cx.budget.take(byteLength, 0)
            return CanonValue.Bytes(cx.mem.read(ptr.toInt(), byteLength.toInt()))
        }
        // 要素の数だけ先に引く（大きな ArrayList を確保する前に止める）。要素の値そのものは load が 1 つずつ数える
        cx.budget.take(byteLength, 0)
        val n = length.toInt()
        if (n.toLong() > cx.budget.maxValues - cx.budget.usedValues) CanonTraps.trap("1 回の呼び出しで写す値の数が上限（${cx.budget.maxValues}）を超えた")
        val out = ArrayList<CanonValue>(n)
        for (i in 0 until n) out += load(cx, ptr + i.toLong() * element.size, element)
        return CanonValue.Items(out)
    }

    // ---- store ----

    /** [v] を [ptr]（確かめた場所）に書く */
    @Throws(WasmTrapException::class)
    fun store(cx: CanonContext, v: CanonValue, t: CanonType, ptr: Long) {
        cx.budget.value()
        val m = cx.mem
        val a = ptr.toInt()
        when (t) {
            CanonType.Bool -> m.writeU8(a, if (expect<CanonValue.Bool>(v, t).value) 1 else 0)
            CanonType.U8, CanonType.S8 -> m.writeU8(a, int(v, t))
            CanonType.U16, CanonType.S16 -> {
                val x = int(v, t)
                m.writeU8(a, x)
                m.writeU8(a + 1, x ushr 8)
            }
            CanonType.U32, CanonType.S32 -> m.writeI32(a, int(v, t))
            CanonType.U64, CanonType.S64 -> m.writeI64(a, expect<CanonValue.I64>(v, t).value)
            CanonType.F32 -> m.writeI32(a, f32Bits(expect<CanonValue.F32>(v, t).value))
            CanonType.F64 -> m.writeI64(a, f64Bits(expect<CanonValue.F64>(v, t).value))
            CanonType.Char -> m.writeI32(a, expect<CanonValue.Char>(v, t).codePoint)
            CanonType.Str -> {
                val r = CanonStrings.store(cx, expect(v, t))
                cx.mem.writeI32(a, r[0].toInt())
                cx.mem.writeI32(a + 4, r[1].toInt())
            }
            is CanonType.ListOf -> {
                val r = storeList(cx, v, t)
                cx.mem.writeI32(a, r[0].toInt())
                cx.mem.writeI32(a + 4, r[1].toInt())
            }
            is CanonType.Fields -> {
                val fields = fieldsOf(v, t)
                for (i in fields.indices) store(cx, fields[i], t.fieldTypes[i], ptr + t.offsets[i])
            }
            is CanonType.Cases -> {
                val c = caseOf(v, t)
                when (t.discSize) {
                    1 -> m.writeU8(a, c.index)
                    2 -> {
                        m.writeU8(a, c.index)
                        m.writeU8(a + 1, c.index ushr 8)
                    }
                    else -> m.writeI32(a, c.index)
                }
                val ct = t.caseTypes[c.index]
                if (ct != null) store(cx, c.payload!!, ct, ptr + t.payloadOffset)
            }
            is CanonType.Flags -> {
                val bits = flagsOf(v, t)
                when (t.size) {
                    1 -> m.writeU8(a, bits)
                    2 -> {
                        m.writeU8(a, bits)
                        m.writeU8(a + 1, bits ushr 8)
                    }
                    else -> m.writeI32(a, bits)
                }
            }
            is CanonType.Own -> m.writeI32(a, cx.resources.lowerOwn(t, expect<CanonValue.Handle>(v, t).value))
            is CanonType.Borrow -> m.writeI32(a, cx.resources.lowerBorrow(t, expect<CanonValue.Handle>(v, t).value))
        }
    }

    /** `store_list_into_range`: 場所を取って要素を書き、`(ptr, len)` を返す */
    @Throws(WasmTrapException::class)
    fun storeList(cx: CanonContext, v: CanonValue, t: CanonType.ListOf): LongArray {
        val element = t.element
        if (element === CanonType.U8 && v is CanonValue.Bytes) {
            val bytes = v.bytes
            checkListSize(bytes.size.toLong(), 1)
            cx.budget.take(bytes.size.toLong(), 0)
            val ptr = cx.allocate(1, bytes.size.toLong())
            if (bytes.isNotEmpty()) cx.mem.write(ptr.toInt(), bytes)
            return longArrayOf(ptr, bytes.size.toLong())
        }
        val items = expect<CanonValue.Items>(v, t).items
        val byteLength = checkListSize(items.size.toLong(), element.size)
        cx.budget.take(byteLength, 0)
        val ptr = cx.allocate(element.align, byteLength)
        for ((i, e) in items.withIndex()) store(cx, e, element, ptr + i.toLong() * element.size)
        return longArrayOf(ptr, items.size.toLong())
    }

    private fun checkListSize(count: Long, elementSize: Int): Long {
        val byteLength = count * elementSize
        if (byteLength > CanonLimits.MAX_LIST_BYTE_LENGTH) CanonTraps.trap("list が大きすぎる（$count 個 × $elementSize バイト）")
        return byteLength
    }

    // ---- 平らにした値 ----

    /** [t]（[CanonType.flat] が `null` でないもの）を、平らにした値から lift する */
    @Throws(WasmTrapException::class)
    fun liftFlat(cx: CanonContext, r: FlatReader, t: CanonType): CanonValue {
        cx.budget.value()
        return when (t) {
            CanonType.Bool -> if (r.next().toInt() != 0) CanonValue.TRUE else CanonValue.FALSE
            CanonType.U8 -> CanonValue.I32(r.next().toInt() and 0xff)
            CanonType.S8 -> CanonValue.I32(r.next().toInt().toByte().toInt())
            CanonType.U16 -> CanonValue.I32(r.next().toInt() and 0xffff)
            CanonType.S16 -> CanonValue.I32(r.next().toInt().toShort().toInt())
            CanonType.U32, CanonType.S32 -> CanonValue.I32(r.next().toInt())
            CanonType.U64, CanonType.S64 -> CanonValue.I64(r.next())
            CanonType.F32 -> CanonValue.F32(canonicalF32(r.next().toInt()))
            CanonType.F64 -> CanonValue.F64(canonicalF64(r.next()))
            CanonType.Char -> char(cx, u32(r.next().toInt()))
            CanonType.Str -> {
                val p = r.next().toInt()
                CanonStrings.load(cx, p, r.next().toInt())
            }
            is CanonType.ListOf -> {
                val p = r.next().toInt()
                loadList(cx, p, r.next().toInt(), t.element)
            }
            is CanonType.Fields -> {
                val types = t.fieldTypes
                CanonValue.Fields(List(types.size) { i -> liftFlat(cx, r, types[i]) })
            }
            is CanonType.Cases -> {
                val payloadFlat = checkNotNull(t.payloadFlat) { "平らにできない型: $t" }
                val disc = u32(r.next().toInt())
                if (disc >= t.caseTypes.size) CanonTraps.trap("判別子が範囲の外（$disc。場合は ${t.caseTypes.size} 個）")
                val start = r.position
                val ct = t.caseTypes[disc.toInt()]
                // join した場所から読む（CoerceValueIter）。値は long のビット列で、i32・f32 を読む側は下位 32 ビットを取るので、
                // i64 → i32 の切り詰めと、i32 → f32・i64 → f64 の読み替えは、ビット列のまま読めば済む
                val payload = ct?.let { liftFlat(cx, r, it) }
                r.skipTo(start + payloadFlat.size)
                CanonValue.Case(disc.toInt(), payload)
            }
            is CanonType.Flags -> CanonValue.Flags(r.next().toInt() and t.mask)
            is CanonType.Own -> CanonValue.Handle(cx.resources.liftOwn(t, r.next().toInt()))
            is CanonType.Borrow -> CanonValue.Handle(cx.resources.liftBorrow(t, r.next().toInt()))
        }
    }

    /** [v] を [t]（[CanonType.flat] が `null` でないもの）として平らにして [w] に足す */
    @Throws(WasmTrapException::class)
    fun lowerFlat(cx: CanonContext, v: CanonValue, t: CanonType, w: FlatWriter) {
        cx.budget.value()
        when (t) {
            CanonType.Bool -> w.addI32(if (expect<CanonValue.Bool>(v, t).value) 1 else 0)
            CanonType.U8, CanonType.S8, CanonType.U16, CanonType.S16, CanonType.U32, CanonType.S32 -> w.addI32(int(v, t))
            CanonType.U64, CanonType.S64 -> w.add(expect<CanonValue.I64>(v, t).value)
            CanonType.F32 -> w.addI32(f32Bits(expect<CanonValue.F32>(v, t).value))
            CanonType.F64 -> w.add(f64Bits(expect<CanonValue.F64>(v, t).value))
            CanonType.Char -> w.addI32(expect<CanonValue.Char>(v, t).codePoint)
            CanonType.Str -> {
                val r = CanonStrings.store(cx, expect(v, t))
                w.add(r[0])
                w.add(r[1])
            }
            is CanonType.ListOf -> {
                val r = storeList(cx, v, t)
                w.add(r[0])
                w.add(r[1])
            }
            is CanonType.Fields -> {
                val fields = fieldsOf(v, t)
                for (i in fields.indices) lowerFlat(cx, fields[i], t.fieldTypes[i], w)
            }
            is CanonType.Cases -> {
                val payloadFlat = checkNotNull(t.payloadFlat) { "平らにできない型: $t" }
                val c = caseOf(v, t)
                w.addI32(c.index)
                val start = w.size
                val ct = t.caseTypes[c.index]
                // 値は上位を 0 にしたビット列で書くので、i32 → i64 の広げ方と、f32 → i32・f32/f64 → i64 の読み替えは、そのまま置けば済む
                if (ct != null) lowerFlat(cx, c.payload!!, ct, w)
                while (w.size < start + payloadFlat.size) w.add(0)
            }
            is CanonType.Flags -> w.addI32(flagsOf(v, t))
            is CanonType.Own -> w.addI32(cx.resources.lowerOwn(t, expect<CanonValue.Handle>(v, t).value))
            is CanonType.Borrow -> w.addI32(cx.resources.lowerBorrow(t, expect<CanonValue.Handle>(v, t).value))
        }
    }

    // ---- ホストの値の確かめ（ホストの誤りは IllegalArgumentException） ----

    private inline fun <reified V : CanonValue> expect(v: CanonValue, t: CanonType): V =
        v as? V ?: throw IllegalArgumentException("$t に ${v::class.simpleName} の値は置けない")

    private fun int(v: CanonValue, t: CanonType): Int {
        val x = expect<CanonValue.I32>(v, t).value
        val ok = when (t) {
            CanonType.U8 -> x in 0..0xff
            CanonType.S8 -> x in -0x80..0x7f
            CanonType.U16 -> x in 0..0xffff
            CanonType.S16 -> x in -0x8000..0x7fff
            else -> true
        }
        require(ok) { "$t の範囲の外: $x" }
        return x
    }

    private fun fieldsOf(v: CanonValue, t: CanonType.Fields): List<CanonValue> {
        val fields = expect<CanonValue.Fields>(v, t).fields
        require(fields.size == t.fieldTypes.size) { "$t のフィールドの数が違う（${fields.size}）" }
        return fields
    }

    private fun caseOf(v: CanonValue, t: CanonType.Cases): CanonValue.Case {
        val c = expect<CanonValue.Case>(v, t)
        require(c.index in t.caseTypes.indices) { "$t の場合の番号が範囲の外: ${c.index}" }
        require((t.caseTypes[c.index] == null) == (c.payload == null)) { "$t の場合 ${c.index} の中身の有無が違う" }
        return c
    }

    private fun flagsOf(v: CanonValue, t: CanonType.Flags): Int {
        val bits = expect<CanonValue.Flags>(v, t).bits
        require(bits and t.mask.inv() == 0) { "$t に宣言の外のビットがある: $bits" }
        return bits
    }
}
