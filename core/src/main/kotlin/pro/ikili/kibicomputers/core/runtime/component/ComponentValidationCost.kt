package pro.ikili.kibicomputers.core.runtime.component

import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import java.util.IdentityHashMap

/**
 * wasm-tools の検証にかかる時間の見積もり（#262）。検証は、見張り（`ValidationWatchdog`）が時間の上限（`LoadDeadline`）で
 * 割り込んで止める（#666）が、それでも1件でコンパイル用のスレッドを上限の時間まで占有できる。わかっている、時間が膨らむ形は、
 * 始める前に見積もりで、すぐ決定的に断る（見張りの前の、最初の防御）。
 *
 * 検証が、入力の大きさに対して大きく膨らむ形のうち、確かめて分かっているのは次の1つ:
 * 型の確認（渡す値の型が、受ける側の型の部分型か）を、同じ大きな型について何度もさせる。型の確認は、型を展開した
 * 大きさ（record のフィールド、関数の引数、インスタンス型の export を、参照している型の中までたどった数）に比例する。
 * 確認の回数は宣言の数で決まり、型の大きさは参照で掛け合わせられるので、宣言の数の上限（[ComponentLimits]）では
 * 抑えられない（例: export が 2,000 個のインスタンス型を受ける引数 100 個 × インスタンス化 100 回の 98 KB で CPU 約 22 秒、
 * フィールド 4,000 個の record を `(type (eq ..))` で受ける引数 100 個 × 100 回で約 13 秒）。そこで、ここで別に抑える。
 *
 * 見積もりは、型の確認ごとの、確かめる型の大きさの合計と、インスタンス化ごとの型の写し直しの大きさの合計と、
 * 覚えない型のたどり（自由変数と名前の確かめ）の大きさの合計（#955。[Info.scan]）と、インスタンスの export ごとの resource のパスの写し（#964）。
 *
 * - 型の確認: コンポーネントのインスタンス化の引数（種類を問わない）と、型を付けた export（`(export "x" (func $f) (func (type $t)))`）。
 *   それぞれ、渡すもの（export するもの）の型の大きさを足す（受ける側の型に合うなら、確認はその大きさまでで終わる）。
 *   wasmparser は型の確認を覚えない（同じ型の対でも、引数ごと・インスタンス化ごとに確かめ直す）ので、回数で掛ける
 * - 型の大きさ: wasmparser の `TypeInfo`（展開した大きさ）にならって、参照している型の大きさを足して数える。
 *   確かめる型の大きさには、100 万で止めない展開した大きさ（[Info.remapBound]）を使う。`MAX_WASM_TYPE_SIZE` = 100 万を超える型は
 *   wasmparser が型を定義したときに断るが、enum・flags の名前の数は wasmparser の型の大きさに入らず、型の確認は名前を1つずつ比べる
 *   （確認は覚えない）ので、名前の多い型を何段か入れ子にすると、wasmparser が断らないまま確認の手間が 100 万を超える
 *   （名前 6,000 個の enum を 300 個ずつ 2 段の tuple にして、record で受ける型を1回インスタンス化する 71 KB で CPU 約 27 秒。#935 のレビュー）。
 *   型の大きさの最大（[Estimate.maxTypeWeight]）は、今までどおり 100 万で止める。添字空間をなぞって参照を引き、
 *   引けないもの（入れ子のコンポーネントのインスタンスの export など）は、それまでに見た型の大きさの最大とする
 *   （参照できる型は、バイナリの中でそれより前に定義されているので、実際より小さくならない）。
 *   インスタンス化で作ったインスタンスの型は、そのコンポーネントの export だけ（import は引数で置き換わる。#751）。
 *   export し直したもの・alias で引いたもの・import したもの（コンポーネント型）のコンポーネントも同じ
 *   （export だけの型を引けないときは、import も含めたコンポーネントの型にする。大きい側）。
 *   型を付けた export は、wasmparser にならって、付けた型（渡した実物ではなく）を、その export の型とする
 * - 写し直し: wasmparser はインスタンス化のたびに、そのコンポーネントの import と export の型を写し直す（resource の置き換え。
 *   import は、引数ごとに受ける側の import の型を写し直すのにも使う）。
 *   写し直しは1回のインスタンス化の中で型ごとに覚える（`Remapping` の `types`）ので、展開した大きさではなく、
 *   import・export からたどれる、違う型の宣言の大きさ（[Info.local]）の合計で数える（#751）。wasmparser が型を新しく作り直す
 *   ところ（import・export したインスタンス・コンポーネント・型（`(type (eq ..))` を含む。型宣言の中のものも）、
 *   インスタンス化、インスタンスの export の alias）は、同じ宣言から来ても別の型として数える（[Info.content]。
 *   重なりを除かないので、実際より小さくならない）。展開した大きさ（[Info.remapBound]）は超えない。
 *   この展開した大きさは 100 万で止めない。enum・flags の名前の数は wasmparser の型の大きさに入らない（見積もりは
 *   1 + 名前の数で数える）ので、wasmparser が断らないまま、写し直す手間は 100 万を超えられる（名前 6,000 個の enum を
 *   6,000 回 eq で export する 100 KB の型で CPU 約 7 秒。#935 のレビュー）
 *   resource を定義するインスタンス型・コンポーネント型は、import・export の宣言のたびにも（インスタンス化とは別に）その型の
 *   export を新しい `Remapping` で全部写し直す（`prepare_instance_import`・`prepare_instance_export`）ので、宣言ごとに 1 回数える
 *
 * - 型のたどり（#955）: wasmparser は、次の 3 か所で、型を覚えずに（同じ宣言を共有していても、展開した大きさの分）たどる。
 *   どれも、インスタンス化も型の確認もないまま、宣言を共有して膨らむ型（`(tuple $t(k-1) $t(k-1))` を 17 段。展開すると
 *   約 52 万、宣言は 18 個）で検証を秒単位にできた（入れ子のコンポーネントごとに、その tuple を引数に持つ関数を import する
 *   120 個・6 KB で CPU 3.9 秒、入れ子のコンポーネントから `alias outer` で 480 回引く 2 KB で 2.8 秒。#935 のレビュー）。
 *   - コンポーネントの境界をまたぐ型の `alias outer`（`alias_type`）は、引いた型の自由変数（resource）をたどる
 *     （`free_variables_any_type_id`）。境界をまたぐのは、count 段外側の 1 つ内側がコンポーネントのとき（[Walker.aliasScan]）
 *   - コンポーネントとコンポーネント型の import・export は、宣言ごとに名前の確かめ（`validate_and_register_named_types`）と、
 *     終わりの自由変数のたどり（`finish`）で 2 回たどる（インスタンス型の中の export はどちらもしない。[Walker.externScan]）
 *   - canon は、型を覚えずにたどる（[Walker.canonScan]。canon の宣言ごとに、関数・値の型のたどる大きさを上限として数える）。
 *     lift・lower は core の関数の型を作るとき（`push_wasm_types`）に、variant・option・result の型のある case を case ごとに
 *     覚えずにたどる（型のある case を共有して入れ子にした variant は、flat の数がほとんど増えないまま展開した大きさの分。
 *     `realloc`・`memory` の指定によらない）。`realloc` がなければ、さらに ptr を含むか（`contains_ptr`）を、lift は引数で、
 *     lower は結果で、tuple・record・variant を `any` でたどって確かめる（中が全部 u32 でも打ち切られない）。
 *     `task.return`（結果の型。`memory` がなければ `contains_ptr` も）、`stream.read`・`future.read`（`realloc` がなければ要素の型）、
 *     `stream.write`・`future.write`（`memory` がなければ。確かめていない、大きい側）も数える
 *
 *   たどる大きさ [Info.scan] は展開した大きさだが、enum・flags の名前を数えない（wasmparser のたどりも名前を見ない）。
 *   variant は全 case を回るので、型を持たない case も 1 と数える（型の大きさ（`TypeInfo`）には入らないので、100 万の上限は抑えない）。
 *   インスタンス型・インスタンスは export だけ、コンポーネント型・コンポーネントは import と export をたどる。
 *   1 ノードの手間は、見積もりの単位（写し直し・型の確認）よりずっと小さいので、[SCAN_NODES_PER_UNIT] ノードを 1 と数える
 *   （切り上げ。ADR 0262 の「検証の時間の見積もり」の測定）
 *
 * - resource のパスの写し（#964。[Estimate.explicitCost]）: wasmparser は、インスタンスを export するたびに（export の並び
 *   （[InstanceExpr.FromExports]）の `instantiate_component_exports`、コンポーネントの export と型宣言の中のインスタンスの export の
 *   `prepare_instance_export`）、そのインスタンスの型の explicit な resource（`explicit_resources`）を 1 件ずつ、パスを 1 つ長くして写す。
 *   型の確認もインスタンス化もないので、resource を多く export するインスタンスを並びで何度も export する形は、前は見積もりが
 *   ほぼ 0 のまま検証が時間の上限に届いた（`(sub resource)` 2,000 個のインスタンスを 499 回 export する並び 40 個・174 KB。#935 のレビュー）。
 *   1 件を見積もりの 1（約 0.5 µs）と数え、パスの要素の写しは型のたどりのノードとして数える。インスタンスが持つ件数 [Info.explicit] は、
 *   export する resource の型と、export するインスタンスの件数の合計。wasmparser の表は resource の id を鍵にするので、同じ resource の
 *   集まり（[Info.explicitKey]）のインスタンスは 1 回だけ足す（並びを並びで何度も export しても掛け算にしない）。
 *   resource を新しく作るところ（resource を定義する型の宣言、インスタンス化）と、インスタンスの export の alias は、別の集まりとする（大きい側）
 *
 * 引けない型を最大とするので、実際より大きい側にずれる。
 * #666 で探した形（core のインスタンス化の引数、resource と canon）は、入力の大きさに比例するだけだった（ADR 0262）。
 * ただし canon は、型を共有して膨らむ型をたどる（lift・lower・`task.return`・stream・future の read・write）ので、上の 3 つ目として数える。
 * 型を付けた export（`(export "x" (component $c) (component (type $ct)))`）の確認も、渡す側の大きさしか数えない
 * （コンポーネントの部分型は import が反変で、確認の手間は受ける側の import の数に比例する）ので、見積もりが
 * 小さいまま検証が時間の上限に届く（受ける側の import 4,000 個 × 型を付けた export 5,000 個。#935 のレビュー。#963 で扱う）。
 * 見積もりにない形は、見張りが時間の上限で止める。
 *
 * 見積もりそのものの手間も抑える。写し直しの大きさをたどる手数（積んだ参照の数）を数え、見積もりの上限の [WORK_FACTOR] 倍を
 * 超えたら、たどるのをやめて断る（[Walker.flatOf]。同じ宣言の連なりを参照する、写して作った型が多い形は、
 * 型ごとにたどり直すので、見積もりを抑えないと入力の大きさの 2 乗の手間になる。N=C=49,000 の 1.29 MB で 270 秒。#935 のレビュー）。
 * 見積もりは見張りの外（`LoadDeadline` なし）で動くので、手数で決定的に止める。
 * `alias outer` の count は、入れ子の深さと比べずに読んだ値なので、添字空間を外にたどるのは入れ子の深さまでにする（[Scope.outer]）。
 */
internal object ComponentValidationCost {
    /** 型の大きさの上限。wasmparser は、これ（`MAX_WASM_TYPE_SIZE`）を超える型を、型を定義したときに断る */
    private const val MAX_TYPE_SIZE = 1_000_000L

    /** 写し直しの大きさをたどる入れ子の深さの上限。超えたら展開した大きさで数える（大きい側） */
    private const val MAX_FLAT_DEPTH = 64

    private const val SATURATED = Long.MAX_VALUE / 2

    /**
     * 写し直しの大きさをたどる手数の上限は、見積もりの上限のこの倍。手数は参照を 1 本ずつ積む数で、積むのは足した宣言の
     * 大きさ（[Info.local]）の中の参照だけなので、ふつうは見積もりの写し直しの分に収まる。入れ子の写して作った型が、
     * たどりの上限（展開した大きさ）をまたぐときだけ、その中をたどった分が見積もりに入りきらないので、余裕を持たせる
     */
    private const val WORK_FACTOR = 4L

    /**
     * 型のたどり（[Info.scan]）の、見積もりの 1 にあたるノードの数（#955）。wasmparser の自由変数・名前のたどりは 1 ノードが
     * 見積もりの単位（写し直し・型の確認）の十数分の 1 ほどの手間なので、この数のノードを 1 とする（ADR 0262 の測定）
     */
    private const val SCAN_NODES_PER_UNIT = 16L

    /**
     * 見積もり。[checkCount] は型の確認の回数、[maxTypeWeight] は型の大きさの最大、[cost] は見積もりの値（確かめる型の大きさと、
     * 写し直しの大きさと、型のたどりと、resource のパスの写しの合計）、[remapCost] はそのうちの写し直しの分、[scanCost] は
     * 型のたどりの分（#955）、[explicitCost] は resource のパスの写しの件数（#964。パスの要素の写しは [scanCost] に入る）。
     * [work] は、写し直しの大きさをたどった手数（積んだ参照の数。見積もりそのものの手間）。[check] は、これが見積もりの上限の
     * [WORK_FACTOR] 倍を超えたら、[cost] によらず手数で断る（そのときの [cost] は途中までの値）
     */
    class Estimate(
        val checkCount: Long,
        val maxTypeWeight: Long,
        val cost: Long,
        val remapCost: Long,
        val work: Long,
        val scanCost: Long,
        val explicitCost: Long,
    )

    private fun maxWorkOf(budget: Long): Long = if (budget > SATURATED / WORK_FACTOR) SATURATED else budget * WORK_FACTOR

    /** [root]（入れ子を含む）の見積もり */
    fun estimate(root: ParsedComponent): Estimate = estimate(root, SATURATED)

    /** [budget] を超えたら、写し直しの大きさをたどるのをやめる（見積もりは [budget] を超えたままになる） */
    private fun estimate(root: ParsedComponent, budget: Long): Estimate {
        val walker = Walker(budget, maxWorkOf(budget))
        walker.component(root, null)
        return Estimate(walker.checks, walker.maxSize, walker.cost, walker.remapCost, walker.work, walker.scanCost, walker.explicitCost)
    }

    /** 見積もりが [limits] の上限を超えていたら [WasmLoadException] */
    @Throws(WasmLoadException::class)
    fun check(root: ParsedComponent, limits: ComponentLimits) {
        val e = estimate(root, limits.maxValidationCost)
        val maxWork = maxWorkOf(limits.maxValidationCost)
        if (e.work > maxWork) {
            // 手数で止めたときは、見積もりの値は途中までのもの（上限を超えたかは分からない）なので、数字は手数を出す
            throw WasmLoadException(
                "コンポーネントの検証が重すぎる見込み（見積もりのために型をたどる手数が上限 $maxWork を超えた。" +
                    "見積もりの上限 ${limits.maxValidationCost}）",
            )
        }
        if (e.cost > limits.maxValidationCost) {
            throw WasmLoadException(
                "コンポーネントの検証が重すぎる見込み（型の確認 ${e.checkCount} 回、確かめる型の大きさと写し直しと型のたどりの合計 " +
                    "${e.cost}（型の大きさの最大 ${e.maxTypeWeight}、写し直し ${e.remapCost}、型のたどり ${e.scanCost}、" +
                    "resource のパスの写し ${e.explicitCost}）。" +
                    "上限 ${limits.maxValidationCost}）",
            )
        }
    }

    /**
     * 型（またはインスタンス・コンポーネント）の情報。[size] は展開した大きさ（コンポーネントは import も含む）。
     * インスタンス型・コンポーネント型と、インスタンス・コンポーネントは、export の名前ごとの情報 [exports] も持つ（alias で引くため）。
     *
     * 写し直しの大きさのために、宣言そのものの大きさ [local]（参照を 1 と数えたもの）と、参照している型 [refs]
     * （コンポーネントとインスタンス型・コンポーネント型は import と export の両方）を持つ。
     * [content] があるものは、wasmparser が [content] を写して新しく作る型（import・インスタンス化など）。
     * 同じ [content] から作ったものどうしでも、重なりを除かない
     */
    private class Info(
        val size: Long,
        val exports: Map<String, Info>? = null,
        val local: Long = size,
        val refs: List<Info> = emptyList(),
        val content: Info? = null,
        /**
         * 写し直しの大きさの上限（展開した大きさ。コンポーネントは import と export の両方）。[size] と違い、100 万で止めない
         * （enum・flags の名前の数は wasmparser の型の大きさに入らないので、wasmparser が断らないまま 100 万を超えられる。#935 のレビュー）
         */
        val remapBound: Long = size,
        /** コンポーネントだけ: インスタンス化でできるインスタンスの型（export だけ。import は引数で置き換わる） */
        val instanceView: Info? = null,
        /** resource を定義する宣言を含むか（`(sub resource)` を export・import するインスタンス型・コンポーネント型など） */
        val res: Boolean = false,
        /**
         * wasmparser が覚えずにたどる大きさ（#955）。展開した大きさだが、enum・flags の名前は数えない。インスタンス型・インスタンスは
         * export だけ、コンポーネント型・コンポーネントは import と export の合計。100 万で止めない
         */
        val scan: Long = 1,
        /**
         * インスタンス（インスタンス型）として export したときに、wasmparser が写す explicit な resource の数の上限
         * （`explicit_resources`。#964）。export する resource の型と、export するインスタンスの [explicit] の合計
         * （同じ [explicitKey] のものは 1 回だけ）。コンポーネント・コンポーネント型は、インスタンス化でできるインスタンスの分
         */
        val explicit: Long = 0,
        /** [explicit] のパスの長さの最大（#964） */
        val explicitDepth: Long = 0,
        /** resource の型か（`(sub resource)`・`(resource ..)` と、それを `eq`・alias・export で引いたもの。#964） */
        val isResource: Boolean = false,
        /**
         * [explicit] の resource の集まりの同一性（#964）。`null` なら [content]（なければ自分）。wasmparser が resource を
         * 新しく作るところ（resource を定義する型の import・export の宣言、インスタンス化）と、引き方で同じと言い切れないところ
         * （インスタンスの export の alias）は、新しいものにする（重なりを除かない。大きい側）
         */
        val resourceKey: Any? = null,
    ) {
        /** [explicit] の重なりを除く鍵 */
        val explicitKey: Any get() = resourceKey ?: content ?: this

        /** 写し直しの大きさ（まだなら -1） */
        var flat = -1L
    }

    /**
     * インスタンス・インスタンス型の export の、explicit な resource の数とパスの長さを積む（#964）。同じ鍵
     * （[Info.explicitKey]）のインスタンスは 1 回だけ足す（wasmparser の `explicit_resources` は resource の id を鍵にした表なので、
     * 同じ resource を何度入れても増えない。除かないと、並びを並びで何度も export する形で掛け算になり、断る側に大きく外れる）
     */
    private class ExplicitAcc {
        var count = 0L
        var depth = 0L
        private val seen = IdentityHashMap<Any, Unit>()

        /** export したもの [info]（種類 [sort]）を足す。インスタンスはその [Info.explicit]、resource の型は 1 件 */
        fun add(info: Info, sort: Sort) {
            if (sort == Sort.INSTANCE) {
                if (info.explicit == 0L || seen.put(info.explicitKey, Unit) != null) return
                count = minOf(count + info.explicit, SATURATED)
                depth = maxOf(depth, minOf(info.explicitDepth + 1, SATURATED))
            } else if (sort == Sort.TYPE && info.isResource) {
                count = minOf(count + 1, SATURATED)
                depth = maxOf(depth, 1)
            }
        }
    }

    /** 添字空間の持ち主の種類（wasmparser の `ComponentKind`） */
    private enum class ScopeKind { COMPONENT, COMPONENT_TYPE, INSTANCE_TYPE }

    /** 添字空間（型・関数・インスタンス・コンポーネント・core の型だけ。関数は、その型の情報）。引けないものは `null` */
    private class Scope(val parent: Scope?, val kind: ScopeKind) {
        val types = ArrayList<Info?>()
        val funcs = ArrayList<Info?>()
        val instances = ArrayList<Info?>()
        val components = ArrayList<Info?>()
        val coreTypes = ArrayList<Info?>()

        /**
         * [count] 段外側の添字空間（なければ `null`）。count は入れ子の深さと比べずに読んだ値（Int.MAX_VALUE まで）で、
         * 見積もりは wasm-tools の検証（深さを超える count を断る）より前に見張りの外で動くので、親が尽きたらすぐやめる
         * （入れ子の深さまでしかたどらない。count のまま回すと、count = 0x7fffffff の `alias outer` 1 つで約 90 ms、
         * 200 個・1.6 KB で 17 秒かかった。#935 のレビュー）
         */
        fun outer(count: Int): Scope? {
            var s: Scope? = this
            var i = 0
            while (i < count && s != null) {
                s = s.parent
                i++
            }
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

    /** [budget] は写し直しの大きさをたどるのをやめる見積もりの値、[maxWork] はたどる手数の上限 */
    private class Walker(private val budget: Long, private val maxWork: Long) {
        var checks = 0L

        /** 見積もりの値（確かめる型の大きさと、写し直しの大きさの合計） */
        var cost = 0L

        /** そのうちの写し直しの分 */
        var remapCost = 0L

        /** そのうちの型のたどりの分（[scanNodes] を [SCAN_NODES_PER_UNIT] で割って切り上げたもの。#955） */
        var scanCost = 0L

        /** 型のたどりのノードの数の合計 */
        private var scanNodes = 0L

        /** それまでに見た型の、たどる大きさの最大（引けない型の代わり） */
        private var maxScan = 1L

        private fun noteScan(n: Long): Long = n.also { if (it > maxScan) maxScan = it }

        /** wasmparser が覚えずにたどるノードを [nodes] 数える（[cost] には、[SCAN_NODES_PER_UNIT] で割って切り上げた分が入る） */
        private fun scanned(nodes: Long) {
            scanNodes = add(scanNodes, nodes)
            val units = (scanNodes + SCAN_NODES_PER_UNIT - 1) / SCAN_NODES_PER_UNIT
            if (units > scanCost) {
                cost = add(cost, units - scanCost)
                scanCost = units
            }
        }

        /**
         * コンポーネント・コンポーネント型の import・export の宣言 1 つ（型 [info]）。wasmparser は名前の確かめ
         * （`validate_and_register_named_types`。名前のない型だけ）と、終わりの自由変数のたどり（`finish`。全部）で、その型を
         * 2 回たどる。上限として 2 × [Info.scan] で数える。インスタンス型の中の export は、どちらもしない
         */
        fun externScan(s: Scope, info: Info) {
            if (s.kind != ScopeKind.INSTANCE_TYPE) scanned(add(info.scan, info.scan))
        }

        /**
         * 型の `alias outer` [a] で引いた型 [info]。count 段外側の 1 つ内側がコンポーネントなら（コンポーネントの境界をまたぐ）、
         * wasmparser は引いた型の自由変数をたどる（`alias_type`）。count = 0 と、型宣言から包んでいるものを引くとき
         * （1 つ内側が型宣言）はたどらない。深さを超える count は wasm-tools の検証が断るので数えない
         */
        fun aliasScan(s: Scope, a: Alias.Outer, info: Info?) {
            if (a.sort != Sort.TYPE || a.count < 1) return
            val inner = s.outer(a.count - 1) ?: return
            if (inner.kind != ScopeKind.COMPONENT || inner.parent == null) return
            scanned(info?.scan ?: maxScan)
        }

        /**
         * canon の定義 [item] で、wasmparser が覚えずにたどる型を数える（#955）。どれも、関数・値の型のたどる大きさ（[Info.scan]）を
         * 上限として、canon ごとに数える（引けない型は、それまでの最大）。
         * - lift・lower は、core の関数の型を作るとき（`ComponentFuncType::lower` の `push_wasm_types`）に、引数と結果の型をたどる。
         *   variant・option・result は、型のある case を case ごとに新しい `LoweredTypes` で覚えずにたどるので、型のある case を共有して
         *   入れ子にした variant は、flat の数がほとんど増えないまま展開した大きさの分たどる。`realloc`・`memory` の指定によらないので、
         *   いつも 1 回数える（tuple・record は flat の数の上限で打ち切られるが、ここでは区別しない。大きい側）
         * - そのうえ `realloc` がなければ、ptr を含むか（`contains_ptr`）を、lift は引数ごとに、lower は結果で、覚えずにたどって
         *   確かめる（variant は型のない case も全部回り、中が全部 u32 でも打ち切られない）。もう 1 回数える
         * - `task.return` は、結果の型で core の関数の型を作り（1 回）、`memory` がなければ `contains_ptr` でもたどる（もう 1 回）
         * - `stream.read`・`future.read` は、`realloc` がなければ要素の型を `contains_ptr` でたどる（stream・future の型で数える）。
         *   `stream.write`・`future.write` は wasmparser を読んで確かめていないが、`memory` がなければ同じ形の確かめがありうるので、
         *   同じに数える（大きい側）
         */
        private fun canonScan(s: Scope, item: Canon) {
            fun scanOf(info: Info?): Long = info?.scan ?: maxScan
            fun List<CanonOption>.noRealloc() = none { it is CanonOption.Realloc }
            fun List<CanonOption>.noMemory() = none { it is CanonOption.Memory }
            fun lowered(scan: Long, twice: Boolean) = scanned(if (twice) add(scan, scan) else scan)
            when (item) {
                is Canon.Lift -> lowered(scanOf(s.types.getOrNull(item.typeIndex)), item.options.noRealloc())
                is Canon.Lower -> lowered(scanOf(s.funcs.getOrNull(item.func)), item.options.noRealloc())
                is Canon.Builtin -> when (item.op) {
                    CanonBuiltin.TASK_RETURN -> when (val r = item.result) {
                        null -> Unit
                        is ValType.Primitive -> lowered(1, item.options.noMemory())
                        is ValType.Ref -> lowered(scanOf(s.types.getOrNull(r.typeIndex)), item.options.noMemory())
                    }
                    CanonBuiltin.STREAM_READ, CanonBuiltin.FUTURE_READ ->
                        if (item.options.noRealloc()) scanned(scanOf(item.typeIndex?.let { s.types.getOrNull(it) }))
                    CanonBuiltin.STREAM_WRITE, CanonBuiltin.FUTURE_WRITE ->
                        if (item.options.noMemory()) scanned(scanOf(item.typeIndex?.let { s.types.getOrNull(it) }))
                    else -> Unit
                }
                is Canon.ResourceNew, is Canon.ResourceDrop, is Canon.ResourceRep -> Unit
            }
        }

        private fun add(a: Long, b: Long): Long = minOf(a + b, SATURATED)

        /**
         * インスタンス化での、コンポーネント [target] の import と export の型の写し直しを数える（型の確認の回数には入れない）。
         * 引けなければ、それまでの最大
         */
        private fun remap(target: Info?) {
            val n = if (target == null) maxFull else flatOf(target, 0)
            cost = add(cost, n)
            remapCost = add(remapCost, n)
        }

        /** 型の確認を 1 回数える。確かめるのは [info] の型（引けなければ、それまでの最大） */
        private fun check(info: Info?) {
            checks++
            // 確かめる手間は、型を展開した大きさ。enum・flags の名前は wasmparser の型の大きさに入らない（確かめるときは
            // 1 つずつ比べる）ので、100 万で止めた [Info.size] ではなく、止めていない [Info.remapBound] で数える（#935 のレビュー）
            cost = add(cost, info?.remapBound ?: maxFull)
        }

        /** それまでに見た型の大きさの最大 */
        var maxSize = 1L

        /** それまでに見た型の、100 万で止めない展開した大きさの最大（引けない型の写し直しの上限） */
        private var maxFull = 1L

        private fun noteFull(full: Long): Long = full.also { if (it > maxFull) maxFull = it }

        /**
         * 大きさを記録する（上限で止める）。[maxFull] も [maxSize] を下回らないようにする（core の型（モジュール型など）は
         * これだけを通るので、引けない引数（core モジュールの引数は、添字空間をなぞらないので必ず引けない）の確認が、
         * それまでの型の大きさの最大より小さくならないように。#935 のレビュー）
         */
        private fun note(size: Long): Long {
            val s = minOf(size, MAX_TYPE_SIZE)
            if (s > maxSize) maxSize = s
            if (s > maxFull) maxFull = s
            return s
        }

        private fun sizeOf(info: Info?): Long = info?.size ?: maxSize

        private fun sum(a: Long, b: Long): Long = minOf(a + b, MAX_TYPE_SIZE)

        /** 引けない参照の代わり（それまでの最大の大きさの、中を持たない型） */
        private fun unknown(): Info =
            Info(
                maxSize, remapBound = maxFull, scan = maxScan, explicit = maxExplicit, explicitDepth = maxExplicitDepth,
                isResource = true,
            )

        /** それまでに見た [Info.explicit] の最大（引けないインスタンスの代わり。#964） */
        private var maxExplicit = 0L

        /** それまでに見た [Info.explicitDepth] の最大 */
        private var maxExplicitDepth = 0L

        /** そのうちの resource のパスの写しの分（#964。パスの要素の写しは [scanCost] に入る） */
        var explicitCost = 0L

        private fun noteExplicit(acc: ExplicitAcc) {
            if (acc.count > maxExplicit) maxExplicit = acc.count
            if (acc.depth > maxExplicitDepth) maxExplicitDepth = acc.depth
        }

        /**
         * インスタンス [info] を export する（export の並び・コンポーネントの export・型宣言の中のインスタンスの export）。
         * wasmparser は、そのたびにインスタンスの型の explicit な resource を 1 件ずつ、パスを 1 つ長くして写す
         * （`instantiate_component_exports`・`prepare_instance_export`。#964）。1 件を見積もりの 1 と数え（約 0.5 µs）、
         * パスの要素の写しを型のたどりのノードとして数える。引けなければ、それまでの最大
         */
        private fun exportInstance(info: Info?) {
            val n = info?.explicit ?: maxExplicit
            if (n == 0L) return
            val depth = info?.explicitDepth ?: maxExplicitDepth
            cost = add(cost, n)
            explicitCost = add(explicitCost, n)
            scanned(mul(n, depth))
        }

        private fun mul(a: Long, b: Long): Long = if (a != 0L && b > SATURATED / a) SATURATED else minOf(a * b, SATURATED)

        /** wasmparser が [info] を写して新しく作る型（コンポーネントは、インスタンス化でできるインスタンスの型も引き継ぐ） */
        private fun copied(info: Info?, fresh: Boolean = false): Info? = info?.let {
            Info(
                it.size, it.exports, content = it.content ?: it, remapBound = it.remapBound, instanceView = it.instanceView, res = it.res,
                scan = it.scan, explicit = it.explicit, explicitDepth = it.explicitDepth, isResource = it.isResource,
                resourceKey = if (fresh) Any() else it.explicitKey,
            )
        }

        /**
         * [info] を、新しい写し直しの中で写すときの大きさ。export からたどれる、違う宣言の [Info.local] の合計
         * （写して作った型は、その中を別に数えて足す）。展開した大きさ [Info.remapBound] は超えない。
         * 見積もりが上限を超えた後や、入れ子が深すぎるときは、たどらずに展開した大きさにする
         */
        private fun flatOf(info: Info, depth: Int): Long {
            val target = info.content ?: info
            if (target.flat >= 0) return target.flat
            if (cost > budget || depth > MAX_FLAT_DEPTH) return target.remapBound
            var total = target.local
            val seen = IdentityHashMap<Info, Unit>()
            if (!spend(target.refs.size)) return target.remapBound
            val stack = ArrayList<Info>(target.refs)
            while (stack.isNotEmpty() && total < target.remapBound) {
                // 積むときに数えたので、取り出すときは数えない（参照 1 本で 1 手）
                val n = stack.removeAt(stack.size - 1)
                if (seen.put(n, Unit) != null) continue
                if (n.content != null) {
                    total = add(total, flatOf(n, depth + 1))
                    // 中で手数が尽きたら、たどり直さずに止める
                    if (exhausted) return target.remapBound
                } else {
                    total = add(total, n.local)
                    // 上限に届いたら、その先は積まない（積んでも足さない）
                    if (total >= target.remapBound) break
                    if (!spend(n.refs.size)) return target.remapBound
                    stack.addAll(n.refs)
                }
            }
            val result = minOf(total, target.remapBound)
            target.flat = result
            return result
        }

        /** 写し直しの大きさをたどった手数（積んだ [Info.refs] の1つずつ） */
        var work = 0L

        /** 手数が上限 [maxWork] を超えたか */
        private var exhausted = false

        /**
         * 写し直しの大きさをたどる手数を [steps] 数える。見積もりそのものの手間を、手数の上限 [maxWork]（見積もりの上限の
         * [WORK_FACTOR] 倍）で抑える（写して作った型が多く、どれも同じ宣言の連なりを参照する形は、写して作った型ごとに
         * その連なりをたどり直すので、入力の大きさの 2 乗になる。#935 のレビュー）。手数が上限を超えたら、これ以上たどらない
         * （false。[check] は手数で断ったと分かるメッセージで断る）。
         * 手数は積んだ参照の数で、積むのは足した宣言の [Info.local]（参照を 1 と数える）の中の参照だけ（たどりの上限に届いた
         * 宣言の参照は積まない）なので、写し直しの見積もりにほぼ収まり、はみ出るのは入れ子の写して作った型がたどりの上限を
         * またぐ分だけ。上限を見積もりの上限の数倍にしているので、見積もりが上限に収まる形を手数で断ることはまずない
         * （同じ型への参照が多い宣言を、取り出すときにも数えていたときは、見積もりの約 2 倍になり、断っていた。#935 のレビュー）
         */
        private fun spend(steps: Int): Boolean {
            work += steps
            if (work <= maxWork) return true
            exhausted = true
            // これ以上たどらない（flatOf は cost > budget を見て、たどらずに展開した大きさを返す）
            cost = maxOf(cost, add(budget, 1))
            return false
        }

        /**
         * コンポーネント [c] をなぞり、その情報を返す。[Info.size] と [Info.remapBound] は import も含む。
         * export だけの型（インスタンス化でできるインスタンスの型）は [Info.instanceView]
         */
        fun component(c: ParsedComponent, parent: Scope?): Info {
            val s = Scope(parent, ScopeKind.COMPONENT)
            val exports = HashMap<String, Info>()
            // import と export の両方（コンポーネントを export・インスタンス化すると、wasmparser は import の型も写し直す）
            val refs = ArrayList<Info>()
            // export だけ（インスタンス化でできるインスタンスの型）
            val exportRefs = ArrayList<Info>()
            var size = 1L
            var exportSize = 1L
            // 100 万で止めない展開した大きさ（写し直しの上限）
            var full = 1L
            var exportFull = 1L
            // たどる大きさ（#955）
            var scan = 1L
            var exportScan = 1L
            // export の explicit な resource（#964）
            val explicitAcc = ExplicitAcc()
            for (section in c.sections) {
                when (section) {
                    is ComponentSection.Nested -> s.components += component(section.component, s)
                    is ComponentSection.CoreTypes -> for (t in section.items) s.coreTypes += Info(coreType(t))
                    is ComponentSection.Types -> for (t in section.items) s.types += defType(t, s)
                    // import の型も、このコンポーネントの型の大きさに入る（コンポーネントを引数に渡すと、import も逆向きに比べる）
                    is ComponentSection.Imports -> for (item in section.items) {
                        val info = extern(s, item.desc)
                        refs += info
                        size = sum(size, 1 + info.size)
                        full = add(full, 1 + info.remapBound)
                        scan = add(scan, info.scan)
                        externScan(s, info)
                    }
                    is ComponentSection.Aliases -> for (item in section.items) alias(s, item)
                    is ComponentSection.Instances -> for (item in section.items) {
                        s.instances += when (item) {
                            is InstanceExpr.Instantiate -> {
                                for ((_, arg) in item.args) check(s.lookup(arg))
                                val target = s.components.getOrNull(item.component)
                                // 引数がなくても、インスタンス化のたびに export の型を写し直す（resource の置き換え）
                                remap(target)
                                // できるインスタンスの型は、そのコンポーネントの export だけ（写して新しく作る）。
                                // export だけの型を引けないコンポーネントは、import も含めた型にする（大きい側）
                                val view = target?.let { it.instanceView ?: it.content ?: it }
                                // インスタンス化のたびに resource は新しくなる（#964。重なりを除かない）
                                view?.let {
                                    Info(
                                        it.size, it.exports, content = it.content ?: it, remapBound = it.remapBound, res = it.res,
                                        scan = it.scan, explicit = it.explicit, explicitDepth = it.explicitDepth, resourceKey = Any(),
                                    )
                                }
                            }
                            is InstanceExpr.FromExports -> fromExports(s, item.exports)
                        }
                    }
                    is ComponentSection.Exports -> for (item in section.items) {
                        val found = s.lookup(item.target)
                        // 型を付けた export は、付けた型の部分型かを確かめ、付けた型をその export の型にする（wasmparser と同じ）。
                        // 実物は同じ宣言を共有していても、付けた型は `(type (eq ..))` ごとに別の型になることがあるので、実物では数えない
                        val desc = item.desc
                        if (desc != null) check(found)
                        // 関数のほかは、export するときに型を写して新しく作る
                        val info = when {
                            desc != null -> descInfo(s, desc)
                            item.target.sort == Sort.FUNC -> found
                            else -> copied(found)
                        }
                        val ref = info ?: unknown()
                        // インスタンスの export は、その explicit な resource をこのコンポーネントの表に写す（#964）
                        val instance = item.target.sort == Sort.INSTANCE
                        if (instance) exportInstance(info)
                        explicitAcc.add(ref, item.target.sort)
                        if (info != null) exports[item.name] = info
                        refs += ref
                        exportRefs += ref
                        size = sum(size, 1 + sizeOf(info))
                        exportSize = sum(exportSize, 1 + sizeOf(info))
                        full = add(full, 1 + ref.remapBound)
                        exportFull = add(exportFull, 1 + ref.remapBound)
                        scan = add(scan, ref.scan)
                        exportScan = add(exportScan, ref.scan)
                        // 型を付けた export は、付けた型（wasmparser が entity にするもの）をたどる
                        externScan(s, ref)
                        // export も、その種類の添字空間に新しい番号を足す
                        s.push(item.target.sort, info)
                    }
                    // canon lift は関数を足す（その型）。ほかの canon は core の関数を足す
                    is ComponentSection.Canons -> for (item in section.items) {
                        // wasmparser が canon ごとに覚えずにたどる型を数える（#955。[canonScan]）
                        canonScan(s, item)
                        if (item is Canon.Lift) s.funcs += s.types.getOrNull(item.typeIndex)
                    }
                    // core のインスタンスとモジュールは、型の確認の見積もりには使わない
                    is ComponentSection.CoreInstances,
                    is ComponentSection.CoreModule, is ComponentSection.Custom,
                    -> Unit
                }
            }
            val res = refs.any { it.res }
            noteExplicit(explicitAcc)
            val view = Info(
                exportSize, exports, local = 1L + exportRefs.size, refs = exportRefs, remapBound = exportFull,
                res = exportRefs.any { it.res }, scan = exportScan, explicit = explicitAcc.count, explicitDepth = explicitAcc.depth,
            )
            return Info(
                size, exports, local = 1L + refs.size, refs = refs, remapBound = full, instanceView = view, res = res,
                scan = noteScan(scan), explicit = explicitAcc.count, explicitDepth = explicitAcc.depth,
            )
        }

        private fun fromExports(s: Scope, items: List<Pair<String, SortIdx>>): Info {
            val exports = HashMap<String, Info>()
            val refs = ArrayList<Info>()
            var size = 1L
            var full = 1L
            var scan = 1L
            val explicitAcc = ExplicitAcc()
            for ((name, idx) in items) {
                val info = s.lookup(idx)
                if (info != null) exports[name] = info
                val ref = info ?: unknown()
                refs += ref
                size = sum(size, 1 + sizeOf(info))
                full = add(full, 1 + ref.remapBound)
                scan = add(scan, ref.scan)
                // インスタンスの export は、そのたびに explicit な resource を全部写す（`instantiate_component_exports`。#964）
                val instance = idx.sort == Sort.INSTANCE
                if (instance) exportInstance(info)
                explicitAcc.add(ref, idx.sort)
            }
            noteExplicit(explicitAcc)
            return Info(
                size, exports, local = 1L + refs.size, refs = refs, remapBound = full, res = refs.any { it.res }, scan = noteScan(scan),
                explicit = explicitAcc.count, explicitDepth = explicitAcc.depth,
            )
        }

        private fun alias(s: Scope, a: Alias) {
            val info = when (a) {
                // インスタンスの export は、インスタンスごとに写した型なので、別の型として数える
                // resource の同一性は、引いたインスタンスによって違いうるので、新しいものにする（#964。大きい側）
                is Alias.InstanceExport -> copied(s.instances.getOrNull(a.instance)?.exports?.get(a.name), fresh = true)
                is Alias.Outer -> s.outer(a.count)?.lookup(SortIdx(a.sort, a.index)).also { aliasScan(s, a, it) }
                is Alias.CoreInstanceExport -> null
            }
            // 引けない型は、それまでの最大とする
            s.push(a.sort, info ?: if (a.sort == Sort.TYPE || a.sort == Sort.CORE_TYPE) unknown() else null)
        }

        /** import・export の記述 [desc] の型の情報。[s] の添字空間に番号を足す */
        private fun extern(s: Scope, desc: ExternDesc): Info {
            val info = descInfo(s, desc)
            s.push(desc.sort, info)
            return info
        }

        /**
         * import・export の宣言で、インスタンス型・コンポーネント型 [type] を写して作る。resource を定義する型は、wasmparser が
         * 宣言のたびにその型の export を新しい `Remapping` で全部写し直す（`prepare_instance_import`・`prepare_instance_export`）ので、
         * その分を数える（型宣言の中の宣言も、コンポーネントのインスタンス化とは別に 1 回ずつ。resource がなければ写し直さない）
         */
        private fun declared(type: Info?): Info? = copied(type, fresh = type?.res == true)?.also { if (it.res) remap(it) }

        /** 記述 [desc] の型の情報（添字空間には足さない） */
        private fun descInfo(s: Scope, desc: ExternDesc): Info =
            when (desc) {
                // `(sub resource)` は新しい resource（大きさ 1）。`(eq $t)` は、wasmparser が別の型の id を作る（同じ型を何度 eq で
                // import・export しても、別々に写し直す）ので、同じ型への参照ではなく写して作った別の型として数える
                is ExternDesc.Type ->
                    if (desc.eqTypeIndex == null) Info(1, res = true, isResource = true) else copied(s.types.getOrNull(desc.eqTypeIndex))
                is ExternDesc.Func -> s.types.getOrNull(desc.typeIndex)
                // インスタンス・コンポーネントは、import・export するときに型を写して新しく作る
                is ExternDesc.Instance -> declared(s.types.getOrNull(desc.typeIndex))
                is ExternDesc.Component -> declared(s.types.getOrNull(desc.typeIndex))
                is ExternDesc.Module -> s.coreTypes.getOrNull(desc.coreTypeIndex)
            } ?: unknown()

        /**
         * インスタンス型・コンポーネント型。大きさは import・export の型の大きさの合計。
         * コンポーネント型（[component]）は、インスタンス化でできるインスタンスの型（export だけ）も [Info.instanceView] に持つ
         */
        private fun declType(decls: List<TypeDecl>, parent: Scope, component: Boolean): Info {
            val s = Scope(parent, if (component) ScopeKind.COMPONENT_TYPE else ScopeKind.INSTANCE_TYPE)
            val exports = HashMap<String, Info>()
            val refs = ArrayList<Info>()
            val exportRefs = ArrayList<Info>()
            var size = 1L
            var exportSize = 1L
            var full = 1L
            var exportFull = 1L
            var scan = 1L
            var exportScan = 1L
            val explicitAcc = ExplicitAcc()
            for (d in decls) {
                when (d) {
                    is TypeDecl.CoreType -> s.coreTypes += Info(coreType(d.type))
                    is TypeDecl.Type -> s.types += defType(d.type, s)
                    is TypeDecl.AliasDecl -> alias(s, d.alias)
                    is TypeDecl.Import -> {
                        val info = extern(s, d.desc)
                        refs += info
                        size = sum(size, 1 + info.size)
                        full = add(full, 1 + info.remapBound)
                        scan = add(scan, info.scan)
                        externScan(s, info)
                    }
                    is TypeDecl.Export -> {
                        val info = extern(s, d.desc)
                        // インスタンスの export の宣言も、その explicit な resource をこの型の表に写す（`prepare_instance_export`。#964）
                        val instance = d.desc is ExternDesc.Instance
                        if (instance) exportInstance(info)
                        explicitAcc.add(info, d.desc.sort)
                        exports[d.name] = info
                        refs += info
                        exportRefs += info
                        size = sum(size, 1 + info.size)
                        exportSize = sum(exportSize, 1 + info.size)
                        full = add(full, 1 + info.remapBound)
                        exportFull = add(exportFull, 1 + info.remapBound)
                        scan = add(scan, info.scan)
                        exportScan = add(exportScan, info.scan)
                        externScan(s, info)
                    }
                }
            }
            noteExplicit(explicitAcc)
            val view = if (component) {
                Info(
                    exportSize, exports, local = 1L + exportRefs.size, refs = exportRefs, remapBound = exportFull,
                    res = exportRefs.any { it.res }, scan = exportScan, explicit = explicitAcc.count, explicitDepth = explicitAcc.depth,
                )
            } else {
                null
            }
            return Info(
                note(size), exports, local = 1L + refs.size, refs = refs, remapBound = noteFull(full), instanceView = view,
                res = refs.any { it.res },
                // インスタンス型は export だけ（import はない）、コンポーネント型は import と export
                scan = noteScan(scan), explicit = explicitAcc.count, explicitDepth = explicitAcc.depth,
            )
        }

        private fun defType(def: DefType, s: Scope): Info = when (def) {
            is InstanceTypeDef -> declType(def.decls, s, component = false)
            is ComponentTypeDef -> declType(def.decls, s, component = true)
            else -> valueType(def, s)
        }

        /** 値の型・関数型・resource。大きさは参照している型の中まで数え、宣言そのものの大きさは参照を 1 と数える */
        private fun valueType(def: DefType, s: Scope): Info {
            val refs = ArrayList<Info>()
            // 100 万で止めない展開した大きさ（写し直しの上限）。参照は、その先の展開した大きさを足す
            var full = 1L
            // たどる大きさ（#955）。enum・flags の名前は数えない
            var scan = 1L
            fun v(t: ValType?): Long = when (t) {
                null -> 0L
                is ValType.Primitive -> {
                    full = add(full, 1)
                    scan = add(scan, 1)
                    1L
                }
                is ValType.Ref -> {
                    val info = s.types.getOrNull(t.typeIndex) ?: unknown()
                    refs += info
                    full = add(full, info.remapBound)
                    scan = add(scan, info.scan)
                    info.size
                }
            }
            var n = 1L
            var local = 1L
            when (def) {
                is DefValType.Record -> for (f in def.fields) n = sum(n, v(f.type))
                is DefValType.Variant -> for (c in def.cases) {
                    full = add(full, 1)
                    // wasmparser のたどりは全 case を回す。型を持たない case も 1 と数える（型の大きさ（`TypeInfo`）には入らない）
                    scan = add(scan, 1)
                    n = sum(n, 1 + v(c.type))
                }
                is DefValType.Tuple -> for (t in def.types) n = sum(n, v(t))
                is DefValType.Flags -> {
                    full = add(full, def.names.size.toLong())
                    n = sum(n, def.names.size.toLong())
                }
                is DefValType.Enum -> {
                    full = add(full, def.names.size.toLong())
                    n = sum(n, def.names.size.toLong())
                }
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
            // 宣言そのものの大きさは、参照を 1 と数えたもの（参照の先は refs でたどる）
            when (def) {
                is DefValType.Record -> local += def.fields.size
                is DefValType.Variant -> local += def.cases.size
                is DefValType.Tuple -> local += def.types.size
                is DefValType.Flags -> local += def.names.size
                is DefValType.Enum -> local += def.names.size
                is FuncType -> local += def.params.size + 1
                else -> local += refs.size
            }
            val size = note(n)
            return Info(
                size, local = minOf(local, full).coerceAtLeast(1), refs = refs, remapBound = noteFull(full), scan = noteScan(scan),
                isResource = def is ResourceType,
            )
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
