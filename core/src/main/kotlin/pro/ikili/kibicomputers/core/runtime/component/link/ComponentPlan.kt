package pro.ikili.kibicomputers.core.runtime.component.link

import pro.ikili.kibicomputers.core.runtime.WasmExternKind
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmImportDescriptor
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonFunction
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonStringEncoding
import pro.ikili.kibicomputers.core.runtime.component.canon.CoreFlat

// リンクの計画（#263。ADR 0263）。コンポーネントをインスタンス化したときに、どの core インスタンスを、どのモジュールから、
// どの import を渡して作るかと、canon の lift・lower がどの core の関数・メモリを使うかを、読み込みのとき（コンパイル用の
// スレッド）に 1 回だけ決めたもの。入れ子のコンポーネントのインスタンス化も、ここで平らに展開してある。
// 不変なので、`ProgramCache` で全マシンが共有してよい。プロセスごとの状態（インスタンス、may_leave、ハンドル表）は LinkedComponent が持つ。

/** core の添字空間の 1 つが指すもの */
internal sealed interface CoreRef {
    val kind: WasmExternKind

    /** 関数なら、その core の型 */
    val functionType: WasmFunctionType?

    /** [step] 番目に作る core インスタンスの export [name] */
    class Export(val step: Int, val name: String, override val kind: WasmExternKind, override val functionType: WasmFunctionType?) : CoreRef {
        override fun toString(): String = "core インスタンス $step の $name"
    }

    /** canon lower で作る関数（[ComponentPlan.lowers] の [lower] 番） */
    class Lowered(val lower: Int, override val functionType: WasmFunctionType) : CoreRef {
        override val kind: WasmExternKind get() = WasmExternKind.FUNCTION
        override fun toString(): String = "canon lower $lower"
    }

    /**
     * canon の `resource.new`・`resource.rep`・`resource.drop`（#265。ADR 0265）。[resource] は plan の resource の番号
     * （[ComponentPlan.resources] の添字）、[instance] はその canon を書いたコンポーネントのインスタンス（使うハンドル表の持ち主）
     */
    class ResourceOp(val op: Op, val resource: Int, val instance: Int) : CoreRef {
        enum class Op(val label: String) { NEW("resource.new"), REP("resource.rep"), DROP("resource.drop") }

        override val kind: WasmExternKind get() = WasmExternKind.FUNCTION
        override val functionType: WasmFunctionType get() = if (op == Op.DROP) I32_TO_NONE else I32_TO_I32
        override fun toString(): String = "canon ${op.label}（resource $resource）"
    }
}

/**
 * resource の型 1 つの記録（#265。ADR 0265）。[canonical] が同じなら同じ resource の型として照合する
 * （別のインターフェースの import が同じホストの resource に結び付いたときも、同じ番号になる）
 */
internal class ResourceInfo(val canonical: Int, val impl: ResourceImpl)

/** resource を実装したもの */
internal sealed interface ResourceImpl {
    /** ゲストが定義した resource。[instance] は実装したコンポーネントのインスタンスの番号、[dtor] は `(i32) -> ()` の core 関数 */
    class Guest(val instance: Int, val dtor: CoreRef?) : ResourceImpl

    /** ホストの resource（[HostResource.id] で同一性を決める） */
    class Host(val interfaceName: String, val name: String, val resource: HostResource) : ResourceImpl

    /** 実体のない resource（ホストの [HostInterface.fallback] のインスタンス型にだけある型。drop しても何も呼ばない） */
    data object Abstract : ResourceImpl
}

/** コンポーネントの関数 1 つ。[function] は型（lift・lower の計画） */
internal sealed interface FuncRef {
    val function: CanonFunction

    /** ホストの関数。[function] はゲストが宣言した型（ホストの型と同じ形であることは、リンクのときに確かめた） */
    class Host(val interfaceName: String, val name: String, val host: HostFunction, override val function: CanonFunction) : FuncRef

    /** canon lift した関数（[ComponentPlan.lifts] の [lift] 番） */
    class Lifted(val lift: Int, override val function: CanonFunction) : FuncRef
}

/** canon の option を、core の添字空間から引いたもの */
internal class CanonBinding(
    val encoding: CanonStringEncoding,
    val memory: CoreRef?,
    val realloc: CoreRef?,
    val postReturn: CoreRef?,
)

/** canon lift 1 つ。[instance] はその canon を書いたコンポーネントのインスタンスの番号（may_leave の持ち主） */
internal class LiftSpec(val core: CoreRef, val function: CanonFunction, val options: CanonBinding, val instance: Int)

/** canon lower 1 つ。[callee] を呼ぶ。[instance] はその canon を書いたコンポーネントのインスタンスの番号（呼ぶ側） */
internal class LowerSpec(val callee: FuncRef, val function: CanonFunction, val options: CanonBinding, val instance: Int)

/** core インスタンス 1 つの作り方。[imports] はモジュールの import（宣言の順）ごとに渡すもの。(module, name) は重ならない（#690） */
internal class CoreStep(val module: Int, val imports: List<Pair<WasmImportDescriptor, CoreRef>>)

/** 一番外のコンポーネントの export */
internal sealed interface ExportItem {
    class Func(val ref: FuncRef) : ExportItem
    class Instance(val exports: Map<String, ExportItem>) : ExportItem

    /** 型・モジュール・コンポーネント（ホストからは呼べない） */
    data object Other : ExportItem
}

/**
 * リンクの計画。
 *
 * @property modules 読み込んだ core モジュール（同じ中身は 1 つにまとめてある）
 * @property steps 作る core インスタンス（作る順。import は前の段の export だけを指す）
 * @property componentInstances コンポーネントのインスタンスの数（一番外を 0 番として数える）
 * @property resources resource の番号（[pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.Handle.resource]）→ 記録。0 番は使わない
 */
internal class ComponentPlan(
    val modules: List<WasmModule>,
    val steps: List<CoreStep>,
    val lifts: List<LiftSpec>,
    val lowers: List<LowerSpec>,
    val componentInstances: Int,
    val exports: Map<String, ExportItem>,
    val resources: List<ResourceInfo?> = emptyList(),
) {
    /** resource の番号 [resource] の記録（計画にない番号は内部の誤り） */
    fun resource(resource: Int): ResourceInfo =
        resources.getOrNull(resource) ?: throw IllegalStateException("resource $resource は計画にない（内部の誤り）")
}

/** canon の計画の core の型を、ランタイムの関数の型にする */
internal fun CanonFunction.CoreSignature.toWasm(): WasmFunctionType =
    WasmFunctionType(params.map(::wasmValueType), results.map(::wasmValueType))

private fun wasmValueType(f: CoreFlat): WasmValueType = when (f) {
    CoreFlat.I32 -> WasmValueType.I32
    CoreFlat.I64 -> WasmValueType.I64
    CoreFlat.F32 -> WasmValueType.F32
    CoreFlat.F64 -> WasmValueType.F64
}

internal val I32_TO_I32 = WasmFunctionType(listOf(WasmValueType.I32), listOf(WasmValueType.I32))
internal val I32_TO_NONE = WasmFunctionType(listOf(WasmValueType.I32), emptyList())
