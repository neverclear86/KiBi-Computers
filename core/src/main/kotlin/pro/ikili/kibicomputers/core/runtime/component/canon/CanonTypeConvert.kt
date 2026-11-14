package pro.ikili.kibicomputers.core.runtime.component.canon

import pro.ikili.kibicomputers.core.runtime.component.DefValType
import pro.ikili.kibicomputers.core.runtime.component.FuncType
import pro.ikili.kibicomputers.core.runtime.component.PrimValType
import pro.ikili.kibicomputers.core.runtime.component.ValType

/**
 * パースした型の定義（`defvaltype`・`functype`）を、Canonical ABI の型（[CanonType]・[CanonFunction]）にする（#264・#263）。
 *
 * 型の参照（添字）の引き方は呼ぶ側が決める（[Refs]）。1 つのコンポーネントの型の空間（[CanonTypeSpace]）と、リンクのときの
 * インスタンス化ごとの型の空間（`ComponentPlanner`）で同じ変換を使う。解決できない・まだ扱えない型は [Unresolved] を投げる
 */
internal object CanonTypeConvert {
    /** 型の参照を引く口 */
    interface Refs {
        /** [index] 番の値の型。引けなければ [Unresolved] を投げる */
        @Throws(Unresolved::class)
        fun value(index: Int): CanonType

        /** [index] 番の resource の識別子。resource でなければ [Unresolved] を投げる */
        @Throws(Unresolved::class)
        fun resource(index: Int): Int
    }

    /** 型を Canonical ABI の型にできない理由 */
    class Unresolved(reason: String) : Exception(reason, null, false, false)

    @Throws(Unresolved::class)
    fun valType(v: ValType, refs: Refs): CanonType = when (v) {
        is ValType.Primitive -> primitive(v.type)
        is ValType.Ref -> refs.value(v.typeIndex)
    }

    @Throws(Unresolved::class)
    fun primitive(p: PrimValType): CanonType = when (p) {
        PrimValType.BOOL -> CanonType.Bool
        PrimValType.S8 -> CanonType.S8
        PrimValType.U8 -> CanonType.U8
        PrimValType.S16 -> CanonType.S16
        PrimValType.U16 -> CanonType.U16
        PrimValType.S32 -> CanonType.S32
        PrimValType.U32 -> CanonType.U32
        PrimValType.S64 -> CanonType.S64
        PrimValType.U64 -> CanonType.U64
        PrimValType.F32 -> CanonType.F32
        PrimValType.F64 -> CanonType.F64
        PrimValType.CHAR -> CanonType.Char
        PrimValType.STRING -> CanonType.Str
        PrimValType.ERROR_CONTEXT -> throw Unresolved("error-context はまだ扱えない")
    }

    /** 値の型の定義。深すぎる型（[CanonLimits.MAX_TYPE_DEPTH]）も [Unresolved] */
    @Throws(Unresolved::class)
    fun defValType(t: DefValType, refs: Refs): CanonType = guarded {
        val type = when (t) {
            is DefValType.Primitive -> primitive(t.type)
            is DefValType.Record -> CanonType.Record(t.fields.map { it.name }, t.fields.map { valType(it.type, refs) })
            is DefValType.Variant -> CanonType.Variant(t.cases.map { it.name }, t.cases.map { c -> c.type?.let { valType(it, refs) } })
            is DefValType.ListOf -> {
                if (t.fixedLength != null) throw Unresolved("長さ固定の list はまだ扱えない")
                CanonType.ListOf(valType(t.element, refs))
            }
            is DefValType.MapOf -> throw Unresolved("map はまだ扱えない")
            is DefValType.Tuple -> CanonType.Tuple(t.types.map { valType(it, refs) })
            is DefValType.Flags -> CanonType.Flags(t.names)
            is DefValType.Enum -> CanonType.Enum(t.names)
            is DefValType.Option -> CanonType.Option(valType(t.type, refs))
            is DefValType.Result -> CanonType.Result(t.ok?.let { valType(it, refs) }, t.err?.let { valType(it, refs) })
            is DefValType.Own -> CanonType.Own(refs.resource(t.typeIndex))
            is DefValType.Borrow -> CanonType.Borrow(refs.resource(t.typeIndex))
            is DefValType.Stream -> throw Unresolved("stream はまだ扱えない（#266）")
            is DefValType.Future -> throw Unresolved("future はまだ扱えない（#266）")
        }
        if (type.depth > CanonLimits.MAX_TYPE_DEPTH) throw Unresolved("型が深すぎる（${type.depth}。上限 ${CanonLimits.MAX_TYPE_DEPTH}）")
        type
    }

    /** 関数の型 */
    @Throws(Unresolved::class)
    fun funcType(t: FuncType, refs: Refs): CanonFunction = guarded {
        if (t.async) throw Unresolved("async の関数はまだ扱えない（#266）")
        CanonFunction(t.params.map { valType(it.type, refs) }, t.result?.let { valType(it, refs) })
    }

    /** 型の大きさが Int を超える、flags の数が範囲の外など（[CanonType] の require）も [Unresolved] にする */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw Unresolved(e.message ?: "型を作れない")
    }
}
