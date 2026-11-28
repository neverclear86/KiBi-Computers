package pro.ikili.kibicomputers.core.runtime.validation

/**
 * サーバーで読み込む WASM モジュールの構造の上限（#40）。値はどれも仮の値（確定は #251）。
 *
 * 小さなバイト列で大きな確保や長い処理をさせる（「数が 40 億のベクター」「40 億個のローカル変数」など）のを、
 * ランタイムに渡す前（[ModulePrescan]）に断る。数の上限は、V8 や wasmtime が使う上限より小さめにしてある
 * （1,000 台のコンピューターが同時に読み込んでも困らない大きさ）。
 *
 * 線形メモリのページ数は、ここではなくインスタンスを作るときに #39 の上限で守る。テーブルの要素の分も、
 * インスタンスを作るときに RAM に数える（#361）。
 */
internal class ModuleLimits(
    /** モジュールのバイト数 */
    val maxBytes: Int = DEFAULT_MAX_BYTES,
    /** 型の数。rec グループの数と、rec グループの中の型を 1 つずつ数えた数の両方に当てる */
    val maxTypes: Int = 100_000,
    /** struct の型 1 つのフィールドの数（仮の値。確定は #251。V8・Endive の `WasmLimits` と同じ値。#1009） */
    val maxStructFields: Int = 10_000,
    /**
     * 全部の struct の型のフィールドの数の合計（仮の値。確定は #251。#1009）。型の検証・サブタイプの比較は型の大きさに比例するので、
     * 型の数とは別に抑える
     */
    val maxTotalStructFields: Int = 1_000_000,
    /** `array.new_fixed` 1 つの要素の数（即値。仮の値、確定は #251。V8・Endive の `WasmLimits` と同じ値。#1009） */
    val maxArrayNewFixed: Int = 10_000,
    /**
     * ランタイムの検証の手間のうち、型の大きさ・即値に比例する分の上限（仮の値。確定は #251。#1009）。関数の本体と全部の定数式の、
     * 具体的な struct の型を使う命令（`struct.new`・`struct.get`・`ref.null`・`ref.cast` など。スタックの型を作り直す `ref.as_non_null` などは
     * いちばん大きな struct の型）のフィールドの数と、`array.new_fixed` の要素の数の合計で数える（[ModulePrescan]）。
     *
     * Endive 1.2.0 の検証は、正しいモジュールで 1 単位あたり約 0.15 µs（読み込みで最大 4 回走るので、合わせて約 0.6 µs）、
     * オペランドが足りない正しくないモジュールでは 1 回目で断るが、1 単位あたり約 1.3 µs と約 800 バイトのヒープを使う。
     * 10^6 で、最悪でも約 1.3 秒（検証の時間の上限 [timeLimitNanos] の中）。手元では、10,000 フィールドの struct の `struct.new_default` を
     * 100 個（ちょうど 10^6）並べたモジュールの燃料ありの読み込みが、全体で約 0.4 秒だった。ヒープは、正しくないモジュールで
     * 1 回の読み込みに 800 MB 近く使いうる（Endive の検証が、足りないオペランドのたびに例外を積むため。GC の命令に限らない）
     */
    val maxGcValidationWork: Long = 1_000_000,
    /** import の数 */
    val maxImports: Int = 10_000,
    /** 定義する関数の数 */
    val maxFunctions: Int = 100_000,
    /** 関数1つの本体のバイト数 */
    val maxFunctionBodyBytes: Int = 4 * 1024 * 1024,
    /** 関数1つで宣言するローカル変数の数（引数は含まない） */
    val maxLocalsPerFunction: Int = 50_000,
    /** 定義するテーブルの数 */
    val maxTables: Int = 16,
    /**
     * テーブル1つの要素数。読み込むときは最初の要素数を確かめ、カーネルはインスタンスを作るときに、
     * これを `table.grow` で増やせる上限にもする（#361。宣言した上限がこれより大きければ縮める）
     */
    val maxTableElements: Int = 100_000,
    /** 定義するメモリの数 */
    val maxMemories: Int = 1,
    /** 定義するグローバルの数 */
    val maxGlobals: Int = 10_000,
    /** export の数 */
    val maxExports: Int = 10_000,
    /** 要素のセグメントの数 */
    val maxElementSegments: Int = 10_000,
    /** データのセグメントの数 */
    val maxDataSegments: Int = 10_000,
    /** 例外のタグの数 */
    val maxTags: Int = 10_000,
    /**
     * 検証（構造の確認・燃料の計測コードの挿入・ランタイムのパースと検証）にかけてよい時間（ナノ秒）。
     * 読み込むスレッドの CPU 時間で測る（測れない JVM では経過時間）。詳しくは [LoadDeadline]
     */
    val timeLimitNanos: Long = DEFAULT_TIME_LIMIT_NANOS,
) {
    init {
        require(maxBytes > 0) { "maxBytes は正: $maxBytes" }
        require(maxGcValidationWork >= 0) { "maxGcValidationWork は 0 以上: $maxGcValidationWork" }
        require(timeLimitNanos > 0) { "timeLimitNanos は正: $timeLimitNanos" }
    }

    companion object {
        /** モジュールのバイト数の既定の上限（16 MiB。実行ファイルの大きさの上限と同じ） */
        const val DEFAULT_MAX_BYTES: Int = 16 * 1024 * 1024

        /**
         * 検証の時間の既定の上限（8 秒。仮の値、確定は #251）。lua.wasm の読み込み（待機点への変換・燃料の計測コードの挿入・
         * パースと検証。#329）は、温まった JVM で約 0.6 秒、温まっていない JVM で 4 台を同時に読み込むと 1 台あたり約 3 秒かかる
         */
        const val DEFAULT_TIME_LIMIT_NANOS: Long = 8_000_000_000L

        /** 既定の上限 */
        val DEFAULT: ModuleLimits = ModuleLimits()
    }
}
