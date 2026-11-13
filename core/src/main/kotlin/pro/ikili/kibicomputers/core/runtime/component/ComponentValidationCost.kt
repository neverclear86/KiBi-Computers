package pro.ikili.kibicomputers.core.runtime.component

import pro.ikili.kibicomputers.core.runtime.WasmLoadException

/**
 * wasm-tools の検証にかかる時間の見積もり（#262）。検証は、時間の上限（`LoadDeadline`）では途中で止まらない
 * （止められるのはスレッドの割り込みだけ。時間で止める形は #666）ので、始める前に、時間が膨らむ形を見積もりで断る。
 *
 * 検証が、入力の大きさに対して大きく膨らむ形のうち、確かめて分かっているのは次の1つ:
 * 型の確認（渡す値の型が、受ける側の型の部分型か）を、同じ大きな型について何度もさせる。型の確認は、型を展開した
 * 大きさ（record のフィールド、関数の引数、インスタンス型の export を、参照している型の中までたどった数）に比例する。
 * 確認の回数は宣言の数で決まり、型の大きさは参照で掛け合わせられるので、宣言の数の上限（[ComponentLimits]）では
 * 抑えられない（例: export が 2,000 個のインスタンス型を受ける引数 100 個 × インスタンス化 100 回の 98 KB で CPU 約 22 秒、
 * フィールド 4,000 個の record を `(type (eq ..))` で受ける引数 100 個 × 100 回で約 13 秒）。そこで、ここで別に抑える。
 *
 * 見積もりは、型の確認ごとの、確かめる型の大きさの合計。
 *
 * - 型の確認: コンポーネントのインスタンス化の引数（種類を問わない）と、型を付けた export（`(export "x" (func $f) (func (type $t)))`）。
 *   それぞれ、渡すもの（export するもの）の型の大きさを足す（受ける側の型に合うなら、確認はその大きさまでで終わる）
 * - 型の大きさ: wasmparser の `TypeInfo`（展開した大きさ。`MAX_WASM_TYPE_SIZE` = 100 万を超える型は、wasmparser が
 *   型を定義したときに断る）にならって、参照している型の大きさを足して数える。添字空間をなぞって参照を引き、
 *   引けないもの（入れ子のコンポーネントのインスタンスの export など）は、それまでに見た型の大きさの最大とする
 *   （参照できる型は、バイナリの中でそれより前に定義されているので、実際より小さくならない）
 *
 * 引けない型を最大とするので、実際より大きい側にずれる。
 * ほかの形の膨らみは、見つけていない（探すのは #666）。上限は、見つけたときに足す。
 */
internal object ComponentValidationCost {
    /** 型の大きさの上限。wasmparser は、これ（`MAX_WASM_TYPE_SIZE`）を超える型を、型を定義したときに断る */
    private const val MAX_TYPE_SIZE = 1_000_000L

    /** 見積もり。[checkCount] は型の確認の回数、[maxTypeWeight] は型の大きさの最大、[cost] は確かめる型の大きさの合計（見積もりの値） */
    class Estimate(val checkCount: Long, val maxTypeWeight: Long, val cost: Long)

    /** [root]（入れ子を含む）の見積もり */
    fun estimate(root: ParsedComponent): Estimate {
        val walker = Walker()
        walker.component(root, null)
        return Estimate(walker.checks, walker.maxSize, walker.cost)
    }

    /** 見積もりが [limits] の上限を超えていたら [WasmLoadException] */
    @Throws(WasmLoadException::class)
    fun check(root: ParsedComponent, limits: ComponentLimits) {
        val e = estimate(root)
        if (e.cost > limits.maxValidationCost) {
            throw WasmLoadException(
                "コンポーネントの検証が重すぎる見込み（型の確認 ${e.checkCount} 回、確かめる型の大きさの合計 ${e.cost}" +
                    "（最大 ${e.maxTypeWeight}）。上限 ${limits.maxValidationCost}）",
            )
        }
    }

    /**
     * 型（またはインスタンス・コンポーネント）の大きさ [size]。インスタンス型・コンポーネント型と、インスタンス・
     * コンポーネントは、export の名前ごとの情報 [exports] も持つ（alias で引くため）
     */
    private class Info(val size: Long, val exports: Map<String, Info>? = null)

    /** 添字空間（型・関数・インスタンス・コンポーネント・core の型だけ。関数は、その型の情報）。引けないものは `null` */
    private class Scope(val parent: Scope?) {
        val types = ArrayList<Info?>()
        val funcs = ArrayList<Info?>()
        val instances = ArrayList<Info?>()
        val components = ArrayList<Info?>()
        val coreTypes = ArrayList<Info?>()

        fun outer(count: Int): Scope? {
            var s: Scope? = this
            repeat(count) { s = s?.parent }
            return s
        }

        fun space(sort: Sort): ArrayList<Info?>? = when (sort) {
            Sort.TYPE -> types
            Sort.FUNC -> funcs
            Sort.INSTANCE -> instances
            Sort.COMPONENT -> components
            Sort.CORE_TYPE -> coreTypes
            else -> null
        }

        fun lookup(target: SortIdx): Info? = space(target.sort)?.getOrNull(target.index)

        /** [sort] の空間に [info] を足す（ほかの空間はなぞらない） */
        fun push(sort: Sort, info: Info?) {
            space(sort)?.add(info)
        }
    }

    private class Walker {
        var checks = 0L

        /** 確かめる型の大きさの合計 */
        var cost = 0L

        /** 型の確認を 1 回数える。確かめるのは [info] の型（引けなければ、それまでの最大） */
        private fun check(info: Info?) {
            checks++
            cost = minOf(cost + sizeOf(info), Long.MAX_VALUE / 2)
        }

        /** それまでに見た型の大きさの最大 */
        var maxSize = 1L

        /** 大きさを記録する（上限で止める） */
        private fun note(size: Long): Long {
            val s = minOf(size, MAX_TYPE_SIZE)
            if (s > maxSize) maxSize = s
            return s
        }

        private fun sizeOf(info: Info?): Long = info?.size ?: maxSize

        private fun sum(a: Long, b: Long): Long = minOf(a + b, MAX_TYPE_SIZE)

        /** コンポーネント [c] をなぞり、その export の情報を返す */
        fun component(c: ParsedComponent, parent: Scope?): Info {
            val s = Scope(parent)
            val exports = HashMap<String, Info>()
            var size = 1L
            for (section in c.sections) {
                when (section) {
                    is ComponentSection.Nested -> s.components += component(section.component, s)
                    is ComponentSection.CoreTypes -> for (t in section.items) s.coreTypes += Info(coreType(t))
                    is ComponentSection.Types -> for (t in section.items) s.types += defType(t, s)
                    is ComponentSection.Imports -> for (item in section.items) extern(s, item.desc)
                    is ComponentSection.Aliases -> for (item in section.items) alias(s, item)
                    is ComponentSection.Instances -> for (item in section.items) {
                        s.instances += when (item) {
                            is InstanceExpr.Instantiate -> {
                                for ((_, arg) in item.args) check(s.lookup(arg))
                                s.components.getOrNull(item.component)
                            }
                            is InstanceExpr.FromExports -> fromExports(s, item.exports)
                        }
                    }
                    is ComponentSection.Exports -> for (item in section.items) {
                        val info = s.lookup(item.target)
                        // 型を付けた export は、付けた型の部分型かを確かめる
                        if (item.desc != null) check(info)
                        if (info != null) exports[item.name] = info
                        size = sum(size, 1 + sizeOf(info))
                        // export も、その種類の添字空間に新しい番号を足す
                        s.push(item.target.sort, info)
                    }
                    // canon lift は関数を足す（その型）。ほかの canon は core の関数を足す
                    is ComponentSection.Canons -> for (item in section.items) {
                        if (item is Canon.Lift) s.funcs += s.types.getOrNull(item.typeIndex)
                    }
                    // core のインスタンスとモジュールは、型の確認の見積もりには使わない
                    is ComponentSection.CoreInstances,
                    is ComponentSection.CoreModule, is ComponentSection.Custom,
                    -> Unit
                }
            }
            return Info(size, exports)
        }

        private fun fromExports(s: Scope, items: List<Pair<String, SortIdx>>): Info {
            val exports = HashMap<String, Info>()
            var size = 1L
            for ((name, idx) in items) {
                val info = s.lookup(idx)
                if (info != null) exports[name] = info
                size = sum(size, 1 + sizeOf(info))
            }
            return Info(size, exports)
        }

        private fun alias(s: Scope, a: Alias) {
            val info = when (a) {
                is Alias.InstanceExport -> s.instances.getOrNull(a.instance)?.exports?.get(a.name)
                is Alias.Outer -> s.outer(a.count)?.lookup(SortIdx(a.sort, a.index))
                is Alias.CoreInstanceExport -> null
            }
            // 引けない型は、それまでの最大とする
            s.push(a.sort, info ?: if (a.sort == Sort.TYPE || a.sort == Sort.CORE_TYPE) Info(maxSize) else null)
        }

        /** import・export の記述 [desc] の型の情報。[s] の添字空間に番号を足す */
        private fun extern(s: Scope, desc: ExternDesc): Info {
            val info = when (desc) {
                // `(sub resource)` は新しい resource（大きさ 1）
                is ExternDesc.Type -> if (desc.eqTypeIndex == null) Info(1) else s.types.getOrNull(desc.eqTypeIndex)
                is ExternDesc.Func -> s.types.getOrNull(desc.typeIndex)
                is ExternDesc.Instance -> s.types.getOrNull(desc.typeIndex)
                is ExternDesc.Component -> s.types.getOrNull(desc.typeIndex)
                is ExternDesc.Module -> s.coreTypes.getOrNull(desc.coreTypeIndex)
            } ?: Info(maxSize)
            s.push(desc.sort, info)
            return info
        }

        /** インスタンス型・コンポーネント型。大きさは import・export の型の大きさの合計 */
        private fun declType(decls: List<TypeDecl>, parent: Scope): Info {
            val s = Scope(parent)
            val exports = HashMap<String, Info>()
            var size = 1L
            for (d in decls) {
                when (d) {
                    is TypeDecl.CoreType -> s.coreTypes += Info(coreType(d.type))
                    is TypeDecl.Type -> s.types += defType(d.type, s)
                    is TypeDecl.AliasDecl -> alias(s, d.alias)
                    is TypeDecl.Import -> size = sum(size, 1 + extern(s, d.desc).size)
                    is TypeDecl.Export -> {
                        val info = extern(s, d.desc)
                        exports[d.name] = info
                        size = sum(size, 1 + info.size)
                    }
                }
            }
            return Info(note(size), exports)
        }

        private fun defType(def: DefType, s: Scope): Info = when (def) {
            is InstanceTypeDef -> declType(def.decls, s)
            is ComponentTypeDef -> declType(def.decls, s)
            else -> Info(note(valueSize(def, s)))
        }

        private fun valueSize(def: DefType, s: Scope): Long {
            fun v(t: ValType?): Long = when (t) {
                null -> 0L
                is ValType.Primitive -> 1L
                is ValType.Ref -> sizeOf(s.types.getOrNull(t.typeIndex))
            }
            var n = 1L
            when (def) {
                is DefValType.Record -> for (f in def.fields) n = sum(n, v(f.type))
                is DefValType.Variant -> for (c in def.cases) n = sum(n, 1 + v(c.type))
                is DefValType.Tuple -> for (t in def.types) n = sum(n, v(t))
                is DefValType.Flags -> n = sum(n, def.names.size.toLong())
                is DefValType.Enum -> n = sum(n, def.names.size.toLong())
                is DefValType.ListOf -> n = sum(n, v(def.element))
                is DefValType.MapOf -> n = sum(n, sum(v(def.key), v(def.value)))
                is DefValType.Option -> n = sum(n, v(def.type))
                is DefValType.Result -> n = sum(n, sum(v(def.ok), v(def.err)))
                is DefValType.Stream -> n = sum(n, v(def.element))
                is DefValType.Future -> n = sum(n, v(def.element))
                is FuncType -> {
                    for (p in def.params) n = sum(n, v(p.type))
                    n = sum(n, v(def.result))
                }
                // own・borrow・resource は resource の同一性を比べるだけ
                is DefValType.Primitive, is DefValType.Own, is DefValType.Borrow, is ResourceType -> Unit
                is InstanceTypeDef, is ComponentTypeDef -> Unit
            }
            return n
        }

        /** core の型の大きさ（関数は引数と結果の数、モジュール型は宣言の数） */
        private fun coreType(t: CoreDefType): Long = note(
            when (t) {
                is CoreDefType.Func -> 1L + t.params.size + t.results.size
                is CoreDefType.Module -> {
                    var n = 1L
                    for (d in t.decls) n = sum(n, if (d is CoreModuleDecl.Type) coreType(d.type) else 1L)
                    n
                }
            },
        )
    }
}
