package pro.ikili.kibicomputers.core.runtime.component.canon

// Canonical ABI の値の型（#264。ADR 0264）。パーサーの木（DefValType・ValType.Ref）の添字を引き、
// 特殊化した型（tuple・enum・option・result）も残した形。大きさ・align・平らにした形は、作るときに 1 回だけ計算して持つ
// （型は葉から順に作るので、計算は再帰しない）。不変なので、スレッドをまたいで共有してよい。

/** 平らにした値（core の値）の型 */
internal enum class CoreFlat {
    I32,
    I64,
    F32,
    F64,
    ;

    companion object {
        /** variant の場合どうしで、同じ位置の型を合わせる（Canonical ABI の `join`） */
        fun join(a: CoreFlat, b: CoreFlat): CoreFlat = when {
            a == b -> a
            (a == I32 && b == F32) || (a == F32 && b == I32) -> I32
            else -> I64
        }
    }
}

/**
 * Canonical ABI の値の型。
 *
 * - [size]・[align]: 線形メモリに置くときの大きさと align（wasm32。ポインタは 4 バイト）
 * - [flat]: 平らにした形。[MAX_FLAT] 個を超えるなら `null`（数え上げはそこで打ち切る。超えたものは平らにして渡さない）
 * - [depth]: 入れ子の深さ（葉は 1）。lift・lower はこの深さだけ再帰するので、上限（[CanonLimits.MAX_TYPE_DEPTH]）で抑える
 *
 * 等しいかは参照で比べる（構造で比べるのは [sameShape]）
 */
internal sealed class CanonType {
    abstract val size: Int
    abstract val align: Int
    abstract val flat: List<CoreFlat>?
    abstract val depth: Int

    /** 大きさ・align・平らにした形が決まったプリミティブ */
    sealed class Primitive(
        override val size: Int,
        flat: CoreFlat,
        private val label: String,
    ) : CanonType() {
        override val align: Int get() = size
        override val flat: List<CoreFlat> = listOf(flat)
        override val depth: Int get() = 1
        override fun toString(): String = label
    }

    object Bool : Primitive(1, CoreFlat.I32, "bool")
    object S8 : Primitive(1, CoreFlat.I32, "s8")
    object U8 : Primitive(1, CoreFlat.I32, "u8")
    object S16 : Primitive(2, CoreFlat.I32, "s16")
    object U16 : Primitive(2, CoreFlat.I32, "u16")
    object S32 : Primitive(4, CoreFlat.I32, "s32")
    object U32 : Primitive(4, CoreFlat.I32, "u32")
    object S64 : Primitive(8, CoreFlat.I64, "s64")
    object U64 : Primitive(8, CoreFlat.I64, "u64")
    object F32 : Primitive(4, CoreFlat.F32, "f32")
    object F64 : Primitive(8, CoreFlat.F64, "f64")
    object Char : Primitive(4, CoreFlat.I32, "char")

    /** `string`（`{ ptr: u32, len: u32 }`） */
    object Str : CanonType() {
        override val size: Int get() = 8
        override val align: Int get() = 4
        override val flat: List<CoreFlat> = listOf(CoreFlat.I32, CoreFlat.I32)
        override val depth: Int get() = 1
        override fun toString(): String = "string"
    }

    /** `list<T>`（長さが決まっていないもの。`{ ptr: u32, len: u32 }`） */
    class ListOf(val element: CanonType) : CanonType() {
        override val size: Int get() = 8
        override val align: Int get() = 4
        override val flat: List<CoreFlat> get() = Str.flat
        override val depth: Int = element.depth + 1
        override fun toString(): String = "list<$element>"
    }

    /** record・tuple（フィールドを順に align に揃えて並べる） */
    sealed class Fields(val fieldTypes: List<CanonType>) : CanonType() {
        /** 各フィールドの、先頭からの位置 */
        val offsets: IntArray = IntArray(fieldTypes.size)
        final override val size: Int
        final override val align: Int
        final override val flat: List<CoreFlat>?
        final override val depth: Int

        init {
            var s = 0L
            var a = 1
            var d = 0
            for ((i, t) in fieldTypes.withIndex()) {
                s = alignTo(s, t.align)
                offsets[i] = checkSize(s)
                s += t.size
                a = maxOf(a, t.align)
                d = maxOf(d, t.depth)
            }
            align = a
            size = checkSize(alignTo(s, a))
            depth = d + 1
            flat = concatFlat(fieldTypes)
        }
    }

    /** `record` */
    class Record(val names: List<String>, types: List<CanonType>) : Fields(types) {
        init {
            require(names.size == types.size) { "フィールドの名前と型の数が違う" }
        }

        override fun toString(): String = names.indices.joinToString(", ", "record{", "}") { "${names[it]}: ${fieldTypes[it]}" }
    }

    /** `tuple` */
    class Tuple(types: List<CanonType>) : Fields(types) {
        override fun toString(): String = fieldTypes.joinToString(", ", "tuple<", ">")
    }

    /**
     * variant・enum・option・result（判別子と、場合ごとの中身。中身のない場合は `null`）。
     * 判別子の大きさは、場合の数が 256 以下なら 1、65536 以下なら 2、それより多ければ 4 バイト
     */
    sealed class Cases(val caseTypes: List<CanonType?>) : CanonType() {
        /** 判別子のバイト数 */
        val discSize: Int
        /** 中身の位置（判別子を、場合の中身の最大の align に揃えたもの） */
        val payloadOffset: Int

        /** 中身の平らにした形（場合どうしを [CoreFlat.join] で合わせたもの。判別子は含まない）。超えたら `null` */
        val payloadFlat: List<CoreFlat>?
        final override val size: Int
        final override val align: Int
        final override val flat: List<CoreFlat>?
        final override val depth: Int

        init {
            val n = caseTypes.size
            require(n > 0) { "場合が 1 つもない" }
            discSize = discriminantSize(n)
            var caseAlign = 1
            var caseSize = 0
            var d = 0
            var joined: MutableList<CoreFlat>? = ArrayList()
            for (t in caseTypes) {
                if (t == null) continue
                caseAlign = maxOf(caseAlign, t.align)
                caseSize = maxOf(caseSize, t.size)
                d = maxOf(d, t.depth)
                val f = t.flat
                if (f == null) {
                    joined = null
                } else if (joined != null) {
                    for ((i, ft) in f.withIndex()) {
                        if (i < joined.size) joined[i] = CoreFlat.join(joined[i], ft) else joined += ft
                    }
                }
            }
            payloadOffset = alignTo(discSize.toLong(), caseAlign).toInt()
            align = maxOf(discSize, caseAlign)
            size = checkSize(alignTo(payloadOffset.toLong() + caseSize, align))
            depth = d + 1
            payloadFlat = joined?.takeIf { it.size < MAX_FLAT }?.toList()
            flat = payloadFlat?.let { listOf(CoreFlat.I32) + it }
        }
    }

    /** `variant` */
    class Variant(val names: List<String>, types: List<CanonType?>) : Cases(types) {
        init {
            require(names.size == types.size) { "場合の名前と型の数が違う" }
        }

        override fun toString(): String =
            names.indices.joinToString(", ", "variant{", "}") { i -> caseTypes[i]?.let { "${names[i]}($it)" } ?: names[i] }
    }

    /** `enum`（中身のない variant） */
    class Enum(val names: List<String>) : Cases(arrayOfNulls<CanonType>(names.size).asList()) {
        override fun toString(): String = names.joinToString(", ", "enum{", "}")
    }

    /** `option<T>`（`none` が 0、`some` が 1） */
    class Option(val type: CanonType) : Cases(listOf(null, type)) {
        override fun toString(): String = "option<$type>"
    }

    /** `result<T, E>`（`ok` が 0、`error` が 1） */
    class Result(val ok: CanonType?, val err: CanonType?) : Cases(listOf(ok, err)) {
        override fun toString(): String = "result<${ok ?: "_"}, ${err ?: "_"}>"
    }

    /** `flags`（32 個まで。8 個までは 1 バイト、16 個までは 2 バイト、それより多ければ 4 バイト） */
    class Flags(val names: List<String>) : CanonType() {
        override val size: Int
        override val align: Int get() = size
        override val flat: List<CoreFlat> get() = I32_FLAT
        override val depth: Int get() = 1

        /** 宣言したビット */
        val mask: Int

        init {
            val n = names.size
            require(n in 1..MAX_FLAGS) { "flags は 1..$MAX_FLAGS 個（$n）" }
            size = if (n <= 8) 1 else if (n <= 16) 2 else 4
            mask = if (n == 32) -1 else (1 shl n) - 1
        }

        override fun toString(): String = names.joinToString(", ", "flags{", "}")
    }

    /**
     * resource のハンドル（`own<R>`・`borrow<R>`）。[resource] は resource の型を指す識別子（型の添字空間で、
     * その resource を定義・import した番号）。ハンドル表は #265 が持つので、lift・lower は [CanonResources] に任せる
     */
    sealed class Handle(val resource: Int) : CanonType() {
        override val size: Int get() = 4
        override val align: Int get() = 4
        override val flat: List<CoreFlat> get() = I32_FLAT
        override val depth: Int get() = 1
    }

    class Own(resource: Int) : Handle(resource) {
        override fun toString(): String = "own<$resource>"
    }

    class Borrow(resource: Int) : Handle(resource) {
        override fun toString(): String = "borrow<$resource>"
    }

    companion object {
        /** 平らにして渡す値の数の上限（同期の関数の `MAX_FLAT_PARAMS`）。型の [flat] もここで打ち切る */
        const val MAX_FLAT: Int = 16

        /** flags の数の上限 */
        const val MAX_FLAGS: Int = 32

        private val I32_FLAT = listOf(CoreFlat.I32)

        /** [n] 個の場合の判別子のバイト数（Canonical ABI の `discriminant_type`） */
        fun discriminantSize(n: Int): Int = when {
            n <= 0x100 -> 1
            n <= 0x10000 -> 2
            else -> 4
        }

        fun alignTo(v: Long, align: Int): Long = (v + align - 1) / align * align

        /** 型の大きさが `Int` に収まるか確かめる（収まらなければ [IllegalArgumentException]。読み込みの層が断る） */
        private fun checkSize(v: Long): Int {
            require(v <= Int.MAX_VALUE) { "型が大きすぎる（$v バイト）" }
            return v.toInt()
        }

        /** [types] の平らにした形をつなげる。[MAX_FLAT] 個を超えたら `null` */
        fun concatFlat(types: List<CanonType>): List<CoreFlat>? {
            val out = ArrayList<CoreFlat>()
            for (t in types) {
                val f = t.flat ?: return null
                if (out.size + f.size > MAX_FLAT) return null
                out += f
            }
            return out
        }

        /**
         * [a] と [b] が同じ形か（名前・場合・フィールドの並びと型、resource の識別子まで同じ）。
         * ホストが宣言した型と、ゲストの型が合うかを確かめるのに使う（呼ぶのはリンク。#263）。
         * 同じオブジェクトは中まで見ない（型は共有されるので、たどる手間はほぼ型の定義の数で済む）
         */
        fun sameShape(a: CanonType?, b: CanonType?): Boolean {
            if (a === b) return true
            if (a == null || b == null) return false
            return when (a) {
                is Primitive, Str -> false // プリミティブはオブジェクトが1つずつなので、参照が違えば違う
                is ListOf -> b is ListOf && sameShape(a.element, b.element)
                is Record -> b is Record && a.names == b.names && sameAll(a.fieldTypes, b.fieldTypes)
                is Tuple -> b is Tuple && sameAll(a.fieldTypes, b.fieldTypes)
                is Variant -> b is Variant && a.names == b.names && sameAll(a.caseTypes, b.caseTypes)
                is Enum -> b is Enum && a.names == b.names
                is Option -> b is Option && sameShape(a.type, b.type)
                is Result -> b is Result && sameShape(a.ok, b.ok) && sameShape(a.err, b.err)
                is Flags -> b is Flags && a.names == b.names
                is Own -> b is Own && a.resource == b.resource
                is Borrow -> b is Borrow && a.resource == b.resource
            }
        }

        private fun sameAll(a: List<CanonType?>, b: List<CanonType?>): Boolean =
            a.size == b.size && a.indices.all { sameShape(a[it], b[it]) }
    }
}
