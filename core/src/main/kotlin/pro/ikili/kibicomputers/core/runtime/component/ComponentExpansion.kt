package pro.ikili.kibicomputers.core.runtime.component

import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.validation.LoadDeadline

/**
 * 入れ子を展開した後の、コアモジュールとコンポーネントのインスタンスの数を数える（#262）。
 *
 * コンポーネントは、中のコンポーネントを何度でもインスタンス化できる。A が B を2回、B が C を2回…と重ねると、
 * 宣言の数は少ないまま、実際のインスタンスは 2^深さ に増え、#263 のインスタンス化でメモリと時間を使い尽くす。
 * そこで、インスタンス化をなぞって（どのコンポーネントがどのインスタンスになるかだけを追い、関数や値は追わない）、
 * `core instance (instantiate ...)` の数と `instance (instantiate ...)` の数を数え、それぞれ
 * [ComponentLimits.maxExpandedCoreInstances]・[ComponentLimits.maxExpandedInstances] を超えたら断る
 * （core のインスタンスを持たないコンポーネントでも、インスタンスごとに resource の型などができるので、両方を数える）。
 *
 * コンポーネントとインスタンスは、インスタンス化の引数・export・alias で渡されるので、それも追う。
 * ホストから来るもの（トップレベルの import）は中身を持たないものとして扱う（ホストはコンポーネントを渡さない）。
 * 検証（[ComponentValidator]）に通った後で呼ぶ（型が正しいので、自分自身をインスタンス化し続けるような作りはない）。
 * それでもなぞる手間（[ComponentLimits.maxExpansionSteps]）と深さに上限を置く。手間は、なぞった宣言（型・canon も
 * 1 つずつ）の数で数えるので、展開した後の宣言の総数の上限にもなる。
 */
internal object ComponentExpansion {
    /** なぞるインスタンス化の入れ子の深さの上限 */
    private const val MAX_DEPTH = 64

    /** 展開した後の数 */
    class Counts(
        /** コアモジュールのインスタンスの数 */
        val coreInstances: Int,
        /** コンポーネントのインスタンス（`instance (instantiate ...)`）の数。一番外のコンポーネントは数えない */
        val instances: Int,
    )

    /** [root] をインスタンス化したときの、インスタンスの数。上限を超えたら [WasmLoadException] */
    @Throws(WasmLoadException::class)
    fun count(root: ParsedComponent, limits: ComponentLimits, deadline: LoadDeadline?): Counts {
        val walker = Walker(limits, deadline)
        try {
            walker.instantiate(root, null, emptyMap(), 0)
        } catch (e: StackOverflowError) {
            ComponentParser.fail("インスタンス化の入れ子が深すぎる")
        }
        return Counts(walker.coreInstances, walker.instances)
    }

    /** 定義されたコンポーネントと、それが定義された場所の環境（外側の alias で使う） */
    private class ComponentValue(val def: ParsedComponent, val env: Env?)

    /** インスタンスの export のうち、コンポーネントとインスタンスのもの */
    private class InstanceValue(val exports: Map<String, Any>)

    /** インスタンス化1回の添字空間（コンポーネントとインスタンスだけ。中身が分からないものは `null`） */
    private class Env(val parent: Env?) {
        val components = ArrayList<ComponentValue?>()
        val instances = ArrayList<InstanceValue?>()

        fun outer(count: Int): Env? {
            var e: Env? = this
            repeat(count) { e = e?.parent }
            return e
        }

        fun lookup(target: SortIdx): Any? = when (target.sort) {
            Sort.COMPONENT -> components.getOrNull(target.index)
            Sort.INSTANCE -> instances.getOrNull(target.index)
            else -> null
        }

        /** [sort] の空間に [value] を足す（コンポーネントとインスタンス以外は数えない） */
        fun push(sort: Sort, value: Any?) {
            when (sort) {
                Sort.COMPONENT -> components += value as? ComponentValue
                Sort.INSTANCE -> instances += value as? InstanceValue
                else -> Unit
            }
        }
    }

    private class Walker(val limits: ComponentLimits, val deadline: LoadDeadline?) {
        var coreInstances = 0
        var instances = 0
        private var steps = 0L

        /** 宣言を [n] 個なぞる */
        private fun step(n: Int = 1) {
            val before = steps
            steps += n
            if (steps > limits.maxExpansionSteps) {
                ComponentParser.fail("インスタンス化をなぞる手間が上限 ${limits.maxExpansionSteps} を超える")
            }
            if (before ushr 8 != steps ushr 8) deadline?.check("コンポーネントの展開の確認")
        }

        fun instantiate(def: ParsedComponent, lexical: Env?, args: Map<String, Any?>, depth: Int): InstanceValue {
            if (depth > MAX_DEPTH) ComponentParser.fail("インスタンス化の入れ子が深すぎる（上限 $MAX_DEPTH）")
            val env = Env(lexical)
            val exports = HashMap<String, Any>()
            for (section in def.sections) {
                step()
                when (section) {
                    is ComponentSection.CoreInstances -> for (item in section.items) {
                        step()
                        if (item is CoreInstanceExpr.Instantiate) {
                            coreInstances++
                            if (coreInstances > limits.maxExpandedCoreInstances) {
                                ComponentParser.fail(
                                    "展開した後のコアモジュールのインスタンスの数が上限 ${limits.maxExpandedCoreInstances} を超える",
                                )
                            }
                        }
                    }
                    is ComponentSection.Nested -> env.components += ComponentValue(section.component, env)
                    is ComponentSection.Imports -> for (item in section.items) {
                        step()
                        env.push(item.desc.sort, args[item.name])
                    }
                    is ComponentSection.Instances -> for (item in section.items) {
                        step()
                        env.instances += when (item) {
                            is InstanceExpr.Instantiate -> {
                                instances++
                                if (instances > limits.maxExpandedInstances) {
                                    ComponentParser.fail(
                                        "展開した後のコンポーネントのインスタンスの数が上限 ${limits.maxExpandedInstances} を超える",
                                    )
                                }
                                val target = env.components.getOrNull(item.component)
                                if (target == null) {
                                    null
                                } else {
                                    val a = item.args.associate { (name, idx) -> name to env.lookup(idx) }
                                    instantiate(target.def, target.env, a, depth + 1)
                                }
                            }
                            is InstanceExpr.FromExports -> InstanceValue(exportsOf(env, item.exports))
                        }
                    }
                    is ComponentSection.Aliases -> for (item in section.items) {
                        step()
                        val value = when (item) {
                            is Alias.InstanceExport -> env.instances.getOrNull(item.instance)?.exports?.get(item.name)
                            is Alias.Outer -> env.outer(item.count)?.lookup(SortIdx(item.sort, item.index))
                            is Alias.CoreInstanceExport -> null
                        }
                        env.push(item.sort, value)
                    }
                    is ComponentSection.Exports -> for (item in section.items) {
                        step()
                        val value = env.lookup(item.target)
                        if (value != null) exports[item.name] = value
                        // export も、その種類の添字空間に新しい番号を足す
                        env.push(item.target.sort, value)
                    }
                    // 型と canon も、インスタンスごとにできる（resource の型など）ので、1 つずつ手間に数える
                    is ComponentSection.CoreTypes -> step(section.items.size)
                    is ComponentSection.Types -> step(section.items.size)
                    is ComponentSection.Canons -> step(section.items.size)
                    is ComponentSection.Custom, is ComponentSection.CoreModule -> Unit
                }
            }
            return InstanceValue(exports)
        }

        private fun exportsOf(env: Env, items: List<Pair<String, SortIdx>>): Map<String, Any> {
            val out = HashMap<String, Any>()
            for ((name, idx) in items) {
                env.lookup(idx)?.let { out[name] = it }
            }
            return out
        }
    }
}
