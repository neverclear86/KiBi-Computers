package pro.ikili.kibicomputers.core.runtime.component

import pro.ikili.kibicomputers.core.runtime.validation.ModuleLimits

/**
 * サーバーで読み込むコンポーネントの構造の上限（#262。#40 の [ModuleLimits] をコンポーネントに広げたもの）。
 * 値はどれも仮の値（確定は #251）。
 *
 * 小さなバイト列で大きな確保や長い処理をさせるのを、自前のパーサー（[ComponentParser]）が読むときに断る。
 * デコードした木のメモリは、種類ごとの数の上限ではなく、デコードする要素の総数（[maxDecodedItems]）で抑える
 * （1 バイトの要素ごとにオブジェクトができるので、入力のバイト数だけでは抑えられない）。
 * 数はどれも、入れ子のコンポーネントも含めたバイナリ全体の合計で数える。中のコアモジュールには、
 * それぞれ [moduleLimits] を当てる（`ModulePrescan`）。バイト数の上限と時間の上限は [moduleLimits] のものを使う
 * （実行ファイルの大きさの上限と同じ 16 MiB、検証の時間は 8 秒）。
 */
internal class ComponentLimits(
    /** 中のコアモジュール1つずつに当てる上限。バイト数と時間の上限は、コンポーネント全体にも当てる */
    val moduleLimits: ModuleLimits = ModuleLimits.DEFAULT,
    /** コアモジュールの数 */
    val maxCoreModules: Int = 1_000,
    /** 入れ子のコンポーネント（コンポーネントのセクション）の数 */
    val maxComponents: Int = 1_000,
    /** 入れ子の深さ（コンポーネントの中のコンポーネントの段数。一番外を 0 とする） */
    val maxNestingDepth: Int = 16,
    /** 型（core の型を含む）の数 */
    val maxTypes: Int = 100_000,
    /** import の数 */
    val maxImports: Int = 10_000,
    /** export の数 */
    val maxExports: Int = 10_000,
    /** alias の数 */
    val maxAliases: Int = 100_000,
    /** canon の数 */
    val maxCanons: Int = 100_000,
    /** インスタンスの宣言（コンポーネントとコアの両方）の数 */
    val maxInstances: Int = 10_000,
    /**
     * セクションの数（入れ子のコンポーネントも含めたバイナリ全体）。要素が 0 個のセクションは 3 バイトなので、
     * これがないと 16 MiB に約 559 万個並べられ、木と wasm-tools の検証が大きく重くなる
     */
    val maxSections: Int = 100_000,
    /** 1 つのベクター（record のフィールド、引数、canon の option など）の要素の数 */
    val maxVectorItems: Int = 100_000,
    /**
     * デコードする要素の総数（ベクターの要素とセクションの合計。入れ子のコンポーネントも含めたバイナリ全体）。
     * 木のメモリは、この数にほぼ比例する（1 要素あたり 20〜100 バイトほど。名前を持つものが重い）ので、
     * [maxSections] とあわせて、最悪でも数十 MiB に収まる値にする。ベクターとセクションを読む前に、その数を足して確かめる
     */
    val maxDecodedItems: Int = 500_000,
    /** 名前（import・export・フィールドの名前、カスタム セクションの名前）のバイト数 */
    val maxNameBytes: Int = 4096,
    /** 型の入れ子の深さ（コンポーネント型・インスタンス型・モジュール型の中の宣言の段数） */
    val maxTypeDepth: Int = 32,
    /**
     * 入れ子を展開した後の、コアモジュールのインスタンスの総数。コンポーネントを2回インスタンス化する作りを重ねると、
     * 宣言の数は少ないまま実際のインスタンスが指数的に増えるので、展開した数で数える（[ComponentExpansion]）
     */
    val maxExpandedCoreInstances: Int = 1_000,
    /**
     * 入れ子を展開した後の、コンポーネントのインスタンス（`instance (instantiate ...)`）の総数。core のインスタンスを
     * 持たないコンポーネントを重ねても、インスタンスごとに resource の型などができるので、こちらも数える
     */
    val maxExpandedInstances: Int = 1_000,
    /**
     * 展開を数えるときに見てよい宣言の数（[ComponentExpansion] の手間の上限）。型・canon も 1 つずつ数えるので、
     * 展開した後の宣言の総数の上限にもなる
     */
    val maxExpansionSteps: Int = 1_000_000,
    /**
     * wasm-tools の検証の手間の見積もり（[ComponentValidationCost]）の上限。検証の間は時間の上限を確かめられないので、
     * 大きな型の確認を何度もさせる形（型の確認の回数 × 型の大きさ）を、検証の前に断る。
     * 測定では 1 単位あたり 0.2〜3.5 マイクロ秒（Windows・JDK 25、暖めた後）で、既定の値なら最悪でも数秒に収まる。
     * 実際のツールチェーンのコンポーネントは、この 10 倍以上の余裕がある見込み（ADR 0262）
     */
    val maxValidationCost: Long = 2_000_000,
) {
    init {
        require(maxNestingDepth >= 0) { "maxNestingDepth は 0 以上: $maxNestingDepth" }
        require(maxTypeDepth > 0) { "maxTypeDepth は正: $maxTypeDepth" }
    }

    /** バイナリ全体のバイト数の上限 */
    val maxBytes: Int get() = moduleLimits.maxBytes

    companion object {
        /** 既定の上限 */
        val DEFAULT: ComponentLimits = ComponentLimits()
    }
}
