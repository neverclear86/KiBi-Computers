package pro.ikili.kibicomputers.core.runtime.component

import pro.ikili.kibicomputers.core.runtime.WasmLoadException

/**
 * wasm-tools の検証にかかる時間の見積もり（#262）。検証は途中で止められないので、始める前に、
 * 時間が膨らむ形を見積もりで断る。
 *
 * 検証が、入力の大きさに対して大きく膨らむ形のうち、確かめて分かっているのは次の1つ:
 * インスタンス化の引数ごとに、渡す値の型が受ける側の型の部分型かを確かめる。インスタンス型（export の宣言が N 個）の
 * 引数は、1 つにつき N に比例する。これが「インスタンス化の宣言の数 M × 引数の数 k × 型の宣言の数 N」に比例して膨らむ
 * （N=2,000・k=100・M=100 の 98 KB で CPU 約 22 秒。N=200 で約 2.9 秒）。宣言の数の上限（[ComponentLimits]）には
 * 掛け合わせた値が入らないので、ここで別に抑える。
 *
 * 見積もりは、引数の総数 × 型の重さの最大。型の重さは、インスタンス型・コンポーネント型の宣言の総数
 * （入れ子の型の中の宣言も含む）と、関数型の引数の数。どの引数も最大の型を受けるとして数えるので、実際より大きい側に
 * ずれる。これは展開した後ではなく宣言の数で数える（検証は宣言を 1 回ずつ見る）。
 * ほかの形の膨らみは、見つけていない。上限は、見つけたときに足す。
 */
internal object ComponentValidationCost {
    /** 見積もり。[argumentCount] は、コンポーネントのインスタンス化の引数の総数。[maxTypeWeight] は型の重さの最大 */
    class Estimate(val argumentCount: Long, val maxTypeWeight: Long) {
        /** 見積もりの値 */
        val cost: Long get() = argumentCount * maxTypeWeight
    }

    /** [root]（入れ子を含む）の見積もり */
    fun estimate(root: ParsedComponent): Estimate {
        var args = 0L
        var weight = 0L

        fun walk(c: ParsedComponent) {
            for (section in c.sections) {
                when (section) {
                    is ComponentSection.Nested -> walk(section.component)
                    is ComponentSection.Types -> for (def in section.items) {
                        if (def is InstanceTypeDef || def is ComponentTypeDef) weight = maxOf(weight, weightOf(def))
                    }
                    is ComponentSection.Instances -> for (item in section.items) {
                        if (item is InstanceExpr.Instantiate) args += item.args.size
                    }
                    else -> Unit
                }
            }
        }
        walk(root)
        return Estimate(args, weight)
    }

    /** 型の重さ。インスタンス型・コンポーネント型は宣言の総数（入れ子の型の中も含む）、関数型は引数の数 + 1 */
    private fun weightOf(def: DefType): Long = when (def) {
        is InstanceTypeDef -> declsWeight(def.decls)
        is ComponentTypeDef -> declsWeight(def.decls)
        is FuncType -> 1L + def.params.size
        else -> 1L
    }

    private fun declsWeight(decls: List<TypeDecl>): Long {
        var n = 0L
        for (d in decls) {
            n += if (d is TypeDecl.Type) 1L + weightOf(d.type) else 1L
        }
        return n
    }

    /** 見積もりが [limits] の上限を超えていたら [WasmLoadException] */
    @Throws(WasmLoadException::class)
    fun check(root: ParsedComponent, limits: ComponentLimits) {
        val e = estimate(root)
        if (e.cost > limits.maxValidationCost) {
            throw WasmLoadException(
                "コンポーネントの検証が重すぎる見込み（インスタンス化の引数 ${e.argumentCount} 個 × 型の重さ ${e.maxTypeWeight}" +
                    " = ${e.cost}。上限 ${limits.maxValidationCost}）",
            )
        }
    }
}
