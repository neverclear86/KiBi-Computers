package pro.ikili.kibicomputers.core.runtime.component.link

import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonFunction
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue

/**
 * ホスト（カーネル）が出すインターフェースの一覧（#263）。コンポーネントのトップレベルの import（インターフェースの名前）を、
 * リンクのとき（読み込みのスレッド）に引く。
 *
 * `wasi:*` の実物は `WasiP2Host`（版の受け付け方とホストの一覧は ADR 0267）。`kibi:*` は `KibiP2Host`（ADR 0685）。カーネルは両方をまとめた `KernelComponentHost` を使う。
 * テストは、宣言に合わせたホストを渡してよい
 *
 * スレッド（#687。ADR 0263）: `exec` で使うホスト（`KernelDrivers.COMPONENT_HOST`）は、サーバー全体で 1 つの固定のもので、
 * 状態を持たない。ホストの解決は読み込みのとき（コンパイル用のスレッド）に 1 回だけ行い、その結果（[HostFunctionBody]・
 * [HostResourceDrop]）は計画（[ComponentPlan]）に入って `ProgramCache` で全マシンが共有する。そのため、
 *
 * - [interfaceOf] は、どのスレッドから何度呼んでも同じ答えを返す（マシンやデバイスの構成で変えない。`ProgramCache` のキーは
 *   ホストを含まないので、変えるとリンクの失敗が覚えられて別のマシンにも返る）
 * - 関数の本体・dtor は、多数のプロセスのスレッドから同時に呼ばれる。プロセスの状態は呼び出しの文脈（[ComponentCall.env]）から
 *   引き、ホストの中に可変の状態を持たない。デバイスがあるかどうかも、リンクのときではなく呼び出しのときに見る
 */
internal fun interface ComponentHost {
    /** import の名前 [name]（`ns:pkg/iface@版`）のインターフェース。出さないなら `null` */
    fun interfaceOf(name: String): HostInterface?

    companion object {
        /** 何も出さないホスト */
        val NONE: ComponentHost = ComponentHost { null }

        /** [interfaces] を名前の完全一致で引くホスト */
        fun of(vararg interfaces: HostInterface): ComponentHost {
            val map = interfaces.associateBy { it.name }
            require(map.size == interfaces.size) { "同じ名前のインターフェースが2つある" }
            return ComponentHost { map[it] }
        }
    }
}

/**
 * ホストのインターフェース 1 つ。[functions] は関数の名前 → 関数、[resources] は resource の型の名前 → ホストの resource
 * （[HostResource.id] は、ホストの関数の型の own・borrow が指す番号。ホスト全体で一意にする）。
 *
 * ゲストの import のインスタンス型は、この部分型でなければならない（ゲストが宣言した関数・resource がすべてあり、関数の型が同じ）。
 * ホストにだけあるものは見ない。
 *
 * [fallback] があれば、[functions] にない関数は、ゲストが宣言した型のまま [fallback] に頼む（`null` なら [functions] と同じく
 * ない扱い）。[resources] にない resource は、そのインスタンス型のためだけの新しい resource にする（ホストの関数からは指せない）。
 * まだ実装していない関数を「呼ぶとトラップ」にしてリンクを通すときや、テストでゲストの宣言に合わせたホストを作るときに使う
 */
internal class HostInterface(
    val name: String,
    val functions: Map<String, HostFunction>,
    val resources: Map<String, HostResource> = emptyMap(),
    val fallback: HostFallback? = null,
)

/** [HostInterface.fallback]。[member] の、ゲストが宣言した型 [type] の関数の処理。出さないなら `null` */
internal fun interface HostFallback {
    fun function(member: String, type: CanonFunction): HostFunctionBody?
}

/**
 * ホストの resource 1 つ（#265。ADR 0265）。[id] はホスト全体で一意にし、ホストの関数の型の own・borrow はこの番号を指す。
 * [drop] は、ゲストが own を `resource.drop` で捨てたときの処理（`null` なら何もしない）。
 *
 * ゲストのハンドル表が持つのは「ハンドルの番号 → rep」の写しだけで、rep が指す実体（fd・カーネルのオブジェクトなど）は
 * ホスト（カーネル）の側の、プロセスの終わりに必ず閉じるものに持たせる。プロセスが終わる・トラップしたときに、
 * ゲストの表に残ったハンドルの [drop] は呼ばない
 */
internal class HostResource(val id: Int, val drop: HostResourceDrop? = null)

/**
 * ホストの resource の dtor（#265）。ゲストが own を `resource.drop` したときに、プロセスのスレッドで、ゲストの core が
 * 呼んだ組み込みの中から呼ばれる（[ComponentCall.functionName] は `[resource-drop]<名前>`）。
 *
 * 待たない・すぐ戻る。カーネルロックが要るなら自分で短く取る。状態は持たず、[rep] で引く。
 * 計画を共有する全マシンのプロセスのスレッドから同時に呼ばれるので、スレッド安全にする（[ComponentHost]）。
 * ゲストの入力の誤りは [WasmTrapException]
 */
internal fun interface HostResourceDrop {
    @Throws(WasmTrapException::class)
    fun drop(call: ComponentCall, rep: Int)
}

/**
 * ホストの関数 1 つ。[type] はホストの宣言した型（own・borrow はホストの resource の識別子を指す）。
 * [wait] は、その関数がプロセスを待たせうるか、待つときに巻き戻せるか（#757。待機点への変換に使う）
 */
internal class HostFunction(val type: CanonFunction, val wait: HostWait = HostWait.NONE, val body: HostFunctionBody)

/**
 * ホストの関数の待ち方（#757。ADR 0757）。コンポーネントを待機点型（`WAIT_POINT`）で動かせるかの判定と、
 * 変換で止まりうるとみなす import を決めるのに使う
 */
internal enum class HostWait {
    /** 待たない */
    NONE,

    /**
     * 待ちうるが、巻き戻して待てる（待つ前に、結果の resource を作らず、own の引数を受けない。巻き直しで同じ引数でやり直す）。
     * 変換したコンポーネントでは止まりうる import になる
     */
    UNWIND,

    /**
     * 待ちうるが、巻き戻せない（スレッドを持って待つ）。これを import するコンポーネントは待機点に変換しない（`exec` では `NOEXEC`。#844。
     * 直接読み込んだものは `BLOCKING` で動かす）。実物のホスト（`KernelDrivers.COMPONENT_HOST`）にはない（#800）
     */
    BLOCK,
}

/**
 * ホストの関数の処理（#263）。引数は lift した後の値（全引数の lift の後に呼ぶ。#616）、戻り値は結果の値（結果のない関数は `null`）。
 *
 * プロセスのスレッドで、ゲストの canon lower の呼び出しの中から呼ばれる。ゲストのメモリには触らない（値は写してある）。
 * 例外は `kibi:computer/memory` の `commit`（#777。ADR 0777）だけで、[ComponentCall.callerMemory] の区画を数える（中身は読み書きしない）。
 * その参照は、呼び出しの外に持ち出さない。
 * 計画を共有する全マシンのプロセスのスレッドから同時に呼ばれるので、状態を持たず（プロセスの状態は [ComponentCall.env] から引く）、
 * スレッド安全にする（[ComponentHost]）。
 * ゲストの入力の誤りは [WasmTrapException]、ホストの誤り（型に合わない値を返したなど）は [IllegalArgumentException]
 *
 * own・borrow の値（[CanonValue.Handle]）は、ハンドルの番号ではなく rep（#265）:
 * - 引数の own は、ゲストの表から外して受けたもの。所有はホストに移る（閉じる責任もホスト）
 * - 引数の borrow は、この呼び出しの中でだけ使える（呼び出しの外に持ち出さない）
 * - 結果の own は、ゲストの表に新しいハンドルとして足す
 */
internal fun interface HostFunctionBody {
    @Throws(WasmTrapException::class)
    fun call(call: ComponentCall, args: List<CanonValue>): CanonValue?
}

/** ホストの関数の呼び出し 1 回の文脈 */
internal class ComponentCall(
    /** 呼んだコンポーネントのインスタンス（プロセスに 1 つ）。この中からその [LinkedComponent.call] は呼べない（[IllegalStateException]。#691） */
    val component: LinkedComponent,
    /** インスタンス化のときに渡した環境（カーネルなら、プロセスの口を持つ） */
    val env: LinkEnv,
    /** 呼ばれたインターフェースの名前 */
    val interfaceName: String,
    /** 呼ばれた関数の名前 */
    val functionName: String,
    /**
     * canon lower した関数を import した core インスタンスの 0 番の線形メモリ（自分で定義したもの。#777。ADR 0777）。
     * ランタイムが import を結んだインスタンスを呼び手として渡すので、wit-component が作る形（main が直接 import する）では呼んだインスタンスと一致するが、
     * import した関数を export し直して別の core インスタンスが呼ぶと、呼んだインスタンスではなく import したインスタンスのメモリになる。
     * 呼び手が core インスタンスでない（lift した関数の中身・realloc・post-return として呼ばれた）、メモリを定義していない、
     * resource の dtor の呼び出しのときは `null`。この呼び出しの中でだけ使う
     */
    val callerMemory: WasmMemory? = null,
)
