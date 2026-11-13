package pro.ikili.kibicomputers.core.runtime.component.canon

import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.component.Alias
import pro.ikili.kibicomputers.core.runtime.component.CanonOption
import pro.ikili.kibicomputers.core.runtime.component.ComponentSection
import pro.ikili.kibicomputers.core.runtime.component.ComponentTypeDef
import pro.ikili.kibicomputers.core.runtime.component.DefValType
import pro.ikili.kibicomputers.core.runtime.component.ExternDesc
import pro.ikili.kibicomputers.core.runtime.component.FuncType
import pro.ikili.kibicomputers.core.runtime.component.InstanceTypeDef
import pro.ikili.kibicomputers.core.runtime.component.ParsedComponent
import pro.ikili.kibicomputers.core.runtime.component.PrimValType
import pro.ikili.kibicomputers.core.runtime.component.ResourceType
import pro.ikili.kibicomputers.core.runtime.component.Sort
import pro.ikili.kibicomputers.core.runtime.component.ValType

/**
 * 1 つのコンポーネントの型の添字空間を、Canonical ABI の型（[CanonType]）に解決したもの（#264。ADR 0264）。
 *
 * 型は、バイナリで前に定義した型だけを参照できる（検証で確かめてある）ので、添字の順に 1 回ずつ解決する（再帰しない）。
 * 引くのは、このコンポーネントの型のセクション・型の import（`eq`・`sub resource`）・型の export・`alias outer`（外側の
 * 添字空間を渡したとき）まで。インスタンスの export の alias（`alias export`）と、インスタンス型・コンポーネント型の中の
 * 型はリンク（#263）で引くので、ここでは「解決できない」として持つ。
 *
 * 解決できない型・まだ扱えない型（stream・future・error-context・長さ固定の list・map）・深すぎる型も、ここでは断らず、
 * その型を求められたとき（canon の計画を作るとき。[valueType]・[function]）に [WasmLoadException] にする
 * （使わない型のために、コンポーネント全体を断らない）。不変なので、スレッドをまたいで共有してよい
 */
internal class CanonTypeSpace private constructor(private val entries: Array<Entry>) {
    /** 添字空間の 1 つ */
    private sealed interface Entry {
        class Value(val type: CanonType) : Entry
        class Func(val function: CanonFunction) : Entry

        /** resource の型。[id] はその resource を定義・import した添字（own・borrow が同じ resource を指すかの識別子） */
        class Resource(val id: Int) : Entry
        class Unresolved(val reason: String) : Entry
    }

    /** 型の数 */
    val size: Int get() = entries.size

    /** [index] 番の値の型 */
    @Throws(WasmLoadException::class)
    fun valueType(index: Int): CanonType = when (val e = entry(index)) {
        is Entry.Value -> e.type
        is Entry.Unresolved -> throw WasmLoadException("型 $index を Canonical ABI の型にできない: ${e.reason}")
        else -> throw WasmLoadException("型 $index は値の型ではない")
    }

    /** [index] 番の関数の型の計画 */
    @Throws(WasmLoadException::class)
    fun function(index: Int): CanonFunction = when (val e = entry(index)) {
        is Entry.Func -> e.function
        is Entry.Unresolved -> throw WasmLoadException("型 $index を Canonical ABI の型にできない: ${e.reason}")
        else -> throw WasmLoadException("型 $index は関数の型ではない")
    }

    /** [index] 番が resource の型なら、その識別子 */
    fun resourceId(index: Int): Int? = (entries.getOrNull(index) as? Entry.Resource)?.id

    private fun entry(index: Int): Entry =
        entries.getOrNull(index) ?: throw WasmLoadException("型の添字 $index が範囲の外（${entries.size} 個）")

    companion object {
        /**
         * [component] の型の添字空間を解決する。[outers] は外側のコンポーネントの添字空間（近い順。`alias outer` を引く）
         */
        @Throws(WasmLoadException::class)
        fun of(component: ParsedComponent, outers: List<CanonTypeSpace> = emptyList()): CanonTypeSpace {
            val b = Builder(outers)
            for (s in component.sections) {
                when (s) {
                    is ComponentSection.Types -> for (t in s.items) {
                        b.add(
                            when (t) {
                                is DefValType -> b.defValType(t)
                                is FuncType -> b.funcType(t)
                                is ResourceType -> Entry.Resource(b.next)
                                is ComponentTypeDef, is InstanceTypeDef -> Entry.Unresolved("コンポーネント型・インスタンス型")
                            },
                        )
                    }
                    is ComponentSection.Imports -> for (i in s.items) {
                        val d = i.desc
                        if (d is ExternDesc.Type) b.add(if (d.eqTypeIndex == null) Entry.Resource(b.next) else b.ref(d.eqTypeIndex))
                    }
                    is ComponentSection.Aliases -> for (a in s.items) {
                        if (a.sort != Sort.TYPE) continue
                        b.add(
                            when (a) {
                                is Alias.Outer -> b.outer(a.count, a.index)
                                else -> Entry.Unresolved("インスタンスの export の型（リンクで引く。#263）")
                            },
                        )
                    }
                    is ComponentSection.Exports -> for (e in s.items) {
                        if (e.target.sort == Sort.TYPE) b.add(b.ref(e.target.index))
                    }
                    else -> Unit
                }
            }
            val space = CanonTypeSpace(b.entries.toTypedArray())
            if (space.size != component.indexSpaceSize(Sort.TYPE)) {
                throw WasmLoadException("型の添字空間の数が合わない（${space.size} / ${component.indexSpaceSize(Sort.TYPE)}。内部の誤り）")
            }
            return space
        }
    }

    private class Builder(private val outers: List<CanonTypeSpace>) {
        val entries = ArrayList<Entry>()
        val next: Int get() = entries.size

        fun add(e: Entry) {
            entries += e
        }

        fun ref(index: Int): Entry = entries.getOrNull(index) ?: Entry.Unresolved("前にない型 $index を参照した")

        fun outer(count: Int, index: Int): Entry = when {
            count == 0 -> ref(index)
            count - 1 < outers.size -> outers[count - 1].entries.getOrNull(index) ?: Entry.Unresolved("外側の型 $index がない")
            else -> Entry.Unresolved("外側のコンポーネントの型（リンクで引く。#263）")
        }

        /** 値の型。解決できなければ [Unresolved] の理由を投げる */
        private fun valType(v: ValType): CanonType = when (v) {
            is ValType.Primitive -> primitive(v.type)
            is ValType.Ref -> when (val e = ref(v.typeIndex)) {
                is Entry.Value -> e.type
                is Entry.Unresolved -> throw Unresolved(e.reason)
                else -> throw Unresolved("型 ${v.typeIndex} は値の型ではない")
            }
        }

        private fun primitive(p: PrimValType): CanonType = when (p) {
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

        private fun resource(index: Int): Int = when (val e = ref(index)) {
            is Entry.Resource -> e.id
            is Entry.Unresolved -> throw Unresolved(e.reason)
            else -> throw Unresolved("型 $index は resource ではない")
        }

        fun defValType(t: DefValType): Entry = resolving {
            val type = when (t) {
                is DefValType.Primitive -> primitive(t.type)
                is DefValType.Record -> CanonType.Record(t.fields.map { it.name }, t.fields.map { valType(it.type) })
                is DefValType.Variant -> CanonType.Variant(t.cases.map { it.name }, t.cases.map { c -> c.type?.let { valType(it) } })
                is DefValType.ListOf -> {
                    if (t.fixedLength != null) throw Unresolved("長さ固定の list はまだ扱えない")
                    CanonType.ListOf(valType(t.element))
                }
                is DefValType.MapOf -> throw Unresolved("map はまだ扱えない")
                is DefValType.Tuple -> CanonType.Tuple(t.types.map { valType(it) })
                is DefValType.Flags -> CanonType.Flags(t.names)
                is DefValType.Enum -> CanonType.Enum(t.names)
                is DefValType.Option -> CanonType.Option(valType(t.type))
                is DefValType.Result -> CanonType.Result(t.ok?.let { valType(it) }, t.err?.let { valType(it) })
                is DefValType.Own -> CanonType.Own(resource(t.typeIndex))
                is DefValType.Borrow -> CanonType.Borrow(resource(t.typeIndex))
                is DefValType.Stream -> throw Unresolved("stream はまだ扱えない（#266）")
                is DefValType.Future -> throw Unresolved("future はまだ扱えない（#266）")
            }
            if (type.depth > CanonLimits.MAX_TYPE_DEPTH) throw Unresolved("型が深すぎる（${type.depth}。上限 ${CanonLimits.MAX_TYPE_DEPTH}）")
            Entry.Value(type)
        }

        fun funcType(t: FuncType): Entry = resolving {
            if (t.async) throw Unresolved("async の関数はまだ扱えない（#266）")
            Entry.Func(CanonFunction(t.params.map { valType(it.type) }, t.result?.let { valType(it) }))
        }

        /** 型を作る。解決できない・大きすぎるものは [Entry.Unresolved] にする */
        private inline fun resolving(block: () -> Entry): Entry = try {
            block()
        } catch (e: Unresolved) {
            Entry.Unresolved(e.message!!)
        } catch (e: IllegalArgumentException) {
            // 型の大きさが Int を超える、flags の数が範囲の外など（CanonType の require）
            Entry.Unresolved(e.message ?: "型を作れない")
        }
    }

    private class Unresolved(reason: String) : Exception(reason, null, false, false)
}

/**
 * canon の option（同期の関数の lift・lower が使うもの）。[memory]・[realloc]・[postReturn] は core の添字空間の番号
 * （実物を引いて [CanonContext] を作るのはリンク。#263）
 */
internal class CanonOptions(
    val encoding: CanonStringEncoding,
    val memory: Int?,
    val realloc: Int?,
    val postReturn: Int?,
) {
    companion object {
        /** [options] を読む。非同期（`async`・`callback`。#266）と GC の option はまだ扱えないので断る */
        @Throws(WasmLoadException::class)
        fun of(options: List<CanonOption>): CanonOptions {
            var encoding = CanonStringEncoding.UTF8
            var memory: Int? = null
            var realloc: Int? = null
            var postReturn: Int? = null
            for (o in options) {
                when (o) {
                    is CanonOption.StringEncoding ->
                        encoding = CanonStringEncoding.of(o.code) ?: throw WasmLoadException("知らない string-encoding（${o.code}）")
                    is CanonOption.Memory -> memory = o.coreMemory
                    is CanonOption.Realloc -> realloc = o.coreFunc
                    is CanonOption.PostReturn -> postReturn = o.coreFunc
                    CanonOption.Async, is CanonOption.Callback -> throw WasmLoadException("非同期の canon はまだ扱えない（#266）")
                    CanonOption.Gc, is CanonOption.CoreType -> throw WasmLoadException("GC の canon option は扱わない")
                }
            }
            return CanonOptions(encoding, memory, realloc, postReturn)
        }
    }
}
