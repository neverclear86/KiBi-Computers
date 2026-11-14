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
// 不変なので、`ProgramCache` で全マシンが共有してよい。プロセスごとの状態（インスタンス、may_leave）は LinkedComponent が持つ。

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

    /** 呼ばれたらトラップするだけの組み込み（resource の canon。ハンドル表は #265） */
    class Trapping(override val functionType: WasmFunctionType, val reason: String) : CoreRef {
        override val kind: WasmExternKind get() = WasmExternKind.FUNCTION
        override fun toString(): String = "組み込み（$reason）"
    }
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
 */
internal class ComponentPlan(
    val modules: List<WasmModule>,
    val steps: List<CoreStep>,
    val lifts: List<LiftSpec>,
    val lowers: List<LowerSpec>,
    val componentInstances: Int,
    val exports: Map<String, ExportItem>,
)

/** canon の計画の core の型を、ランタイムの関数の型にする */
internal fun CanonFunction.CoreSignature.toWasm(): WasmFunctionType =
    WasmFunctionType(params.map(::wasmValueType), results.map(::wasmValueType))

private fun wasmValueType(f: CoreFlat): WasmValueType = when (f) {
    CoreFlat.I32 -> WasmValueType.I32
    CoreFlat.I64 -> WasmValueType.I64
    CoreFlat.F32 -> WasmValueType.F32
    CoreFlat.F64 -> WasmValueType.F64
}
