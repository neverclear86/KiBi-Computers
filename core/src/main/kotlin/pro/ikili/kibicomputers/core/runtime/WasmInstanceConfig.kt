package pro.ikili.kibicomputers.core.runtime

import org.jetbrains.annotations.ApiStatus

/** ホストの関数を1つ、import の名前に結び付けたもの。 */
@ApiStatus.Internal
public class WasmHostImport(
    /** import のモジュール名 */
    public val module: String,
    /** import の名前 */
    public val name: String,
    /** 関数の型。モジュールの宣言と一致しなければ、インスタンスを作るときに [WasmLinkException] になる */
    public val type: WasmFunctionType,
    /** 呼ばれたときの処理 */
    public val function: WasmHostFunction,
) {
    override fun toString(): String = "host $module.$name $type"
}

/**
 * インスタンスを作るときの設定。[builder] で作る。
 *
 * ```java
 * WasmInstanceConfig config = WasmInstanceConfig.builder()
 *     .hostFunction("env", "log", type, (instance, args) -> new long[0])
 *     .fuel(10_000, (instance, remaining) -> remaining + 10_000)
 *     .memoryLimit(256, budget)
 *     .build();
 * ```
 */
@ApiStatus.Internal
public class WasmInstanceConfig private constructor(builder: Builder) {
    /** ホストの関数 */
    public val hostImports: List<WasmHostImport> = builder.hostImports.toList()

    /** 最初の燃料。燃料を計測しないモジュールでは使わない */
    public val initialFuel: Long = builder.initialFuel

    /** 燃料切れのときの処理。`null` なら、燃料切れで [WasmTrapException]（[WasmTrapKind.FUEL_EXHAUSTED]）になる */
    public val fuelHandler: WasmFuelHandler? = builder.fuelHandler

    /**
     * 線形メモリ1つあたりのページ数の上限（#39）。モジュールが宣言した上限より小さければ、こちらが上限になる。
     * 既定は [WasmMemory.MAX_PAGES]（WASM の 32 ビットのメモリの上限。制限なし）
     */
    public val maxMemoryPages: Int = builder.maxMemoryPages

    /** 線形メモリの確保を数える相手（#39）。`null` なら数えない */
    public val memoryBudget: WasmMemoryBudget? = builder.memoryBudget

    /**
     * テーブル1つの要素数の上限（#361）。モジュールが宣言した上限（なければ Endive の上限の 1,000 万）より小さければ、
     * こちらが上限になる。既定は [Int.MAX_VALUE]（ランタイムの上限のまま）
     */
    internal val maxTableElements: Int = builder.maxTableElements

    /** テーブルの確保を数える相手（#361）。`null` なら数えない */
    internal val tableBudget: WasmTableBudget? = builder.tableBudget

    /**
     * 共有しない線形メモリを、初めて書き込んだ 4 KiB の区画の数で数える（#407。[Builder.writtenMemoryLimit]）。
     * `true` なら [maxMemoryPages] はアドレス空間の上限で、[memoryBudget] は使わない
     */
    internal val writtenMemory: Boolean = builder.writtenMemory

    /** 書き込んだ 4 KiB の区画を数える相手（#407）。`null` なら数えない */
    internal val writtenMemoryBudget: WasmTableBudget? = builder.writtenMemoryBudget

    /** `memory.grow` を [writtenMemoryBudget] が断ったときに呼ぶ（#407。メモリ不足の表示に使う）。`null` なら呼ばない */
    internal val onMemoryGrowRefused: Runnable? = builder.onMemoryGrowRefused

    /**
     * 待機点への変換をかけたモジュールが退避する値の置き場所（#329）。変換したモジュール（[WasmModule.isAsyncified]）では必須で、
     * `null` のままインスタンスを作ると [WasmLinkException]。変換していないモジュールでは使わない
     */
    public val asyncStack: WasmAsyncStack? = builder.asyncStack

    /**
     * 燃料の残りを共有するモジュール（[WasmModule.sharesFuel]。#263）に渡す、共有の残り。`null` なら、共有するモジュールのインスタンスは
     * 作れない（[WasmLinkException]）
     */
    internal val sharedFuel: WasmSharedFuel? = builder.sharedFuel

    /** ほかのインスタンスの export を渡す import（#263。モジュール名と名前 → export） */
    internal val externImports: Map<Pair<String, String>, WasmExtern> = HashMap(builder.externImports)

    /** [module].[name] に渡す、ほかのインスタンスの export。なければ `null` */
    internal fun externImport(module: String, name: String): WasmExtern? = externImports[module to name]

    /** [module].[name] のホストの関数。なければ `null` */
    public fun hostImport(module: String, name: String): WasmHostImport? = hostImports.firstOrNull { it.module == module && it.name == name }

    /** [WasmInstanceConfig] を組み立てる。 */
    public class Builder internal constructor() {
        internal val hostImports = mutableListOf<WasmHostImport>()
        internal var initialFuel: Long = 0
        internal var fuelHandler: WasmFuelHandler? = null
        internal var maxMemoryPages: Int = WasmMemory.MAX_PAGES
        internal var memoryBudget: WasmMemoryBudget? = null
        internal var asyncStack: WasmAsyncStack? = null
        internal var maxTableElements: Int = Int.MAX_VALUE
        internal var tableBudget: WasmTableBudget? = null
        internal var writtenMemory: Boolean = false
        internal var writtenMemoryBudget: WasmTableBudget? = null
        internal var onMemoryGrowRefused: Runnable? = null
        internal var sharedFuel: WasmSharedFuel? = null
        internal val externImports = HashMap<Pair<String, String>, WasmExtern>()

        /** ホストの関数を足す。同じ名前を2回足すと [IllegalArgumentException]。 */
        public fun hostFunction(import: WasmHostImport): Builder = apply {
            require(hostImports.none { it.module == import.module && it.name == import.name }) { "${import.module}.${import.name} が2回登録された" }
            require(import.module to import.name !in externImports) { "${import.module}.${import.name} はほかのインスタンスの export として登録された" }
            hostImports += import
        }

        /** ホストの関数を足す。 */
        public fun hostFunction(module: String, name: String, type: WasmFunctionType, function: WasmHostFunction): Builder =
            hostFunction(WasmHostImport(module, name, type, function))

        /** 最初の燃料と、燃料切れのときの処理を決める。 */
        public fun fuel(initial: Long, handler: WasmFuelHandler?): Builder = apply {
            initialFuel = initial
            fuelHandler = handler
        }

        /**
         * 線形メモリの上限を決める（#39）。
         *
         * 最初のページ数が [maxPages] を超える、または [budget] が最初のページ数を確保させないときは、
         * インスタンスを作るときに [WasmLinkException] になる。実行中の `memory.grow`（と [WasmMemory.grow]）は、
         * [maxPages] を超えるか [budget] が断ると失敗する（-1 を返す。トラップではない）。
         *
         * @param maxPages メモリ1つあたりのページ数の上限（0〜[WasmMemory.MAX_PAGES]）
         * @param budget 確保を数える相手。`null` なら数えない
         */
        public fun memoryLimit(maxPages: Int, budget: WasmMemoryBudget?): Builder = apply {
            require(maxPages in 0..WasmMemory.MAX_PAGES) { "maxPages は 0〜${WasmMemory.MAX_PAGES}: $maxPages" }
            require(!writtenMemory) { "memoryLimit と writtenMemoryLimit は一緒に使えない" }
            maxMemoryPages = maxPages
            memoryBudget = budget
        }

        /**
         * 線形メモリを、初めて書き込んだ 4 KiB の区画の数で RAM として数える（#407。#370 で決定）。[memoryLimit] とは一緒に使えない。
         *
         * - ページ数（最初の大きさと `memory.grow` で増やした大きさ）は数えず、[maxPages] だけを上限にする（アドレス空間の上限）。
         * - 初めて書き込んだ 4 KiB ごとに [budget] で 4,096 バイトを数え、断られたらトラップ（[WasmTrapKind.OUT_OF_MEMORY]）にする。
         * - インスタンスを作るときにデータセグメントで書いた区画（ROM）は、書き換えるまで数えない。ゼロのままの区画も数えない。
         * - `memory.grow` は、増やした所の最初と最後の 4 KiB だけを先に数える。数えられなければ -1 にし、[onGrowRefused] を呼ぶ。
         * - 数えた分は、インスタンスを閉じるまで返さない（解放しても減らない）。
         *
         * 共有メモリ（threads）を宣言したモジュールは、インスタンスを作るときに [WasmLinkException] になる。
         *
         * @param maxPages メモリ1つあたりのページ数の上限（0〜[WasmMemory.MAX_PAGES]）
         * @param budget 書き込んだ区画を数える相手。`null` なら数えない
         * @param onGrowRefused `memory.grow` を [budget] が断ったときに呼ぶ（実行しているスレッドから。ロックを持たずに）
         */
        internal fun writtenMemoryLimit(maxPages: Int, budget: WasmTableBudget?, onGrowRefused: Runnable? = null): Builder = apply {
            require(maxPages in 0..WasmMemory.MAX_PAGES) { "maxPages は 0〜${WasmMemory.MAX_PAGES}: $maxPages" }
            require(memoryBudget == null) { "memoryLimit と writtenMemoryLimit は一緒に使えない" }
            maxMemoryPages = maxPages
            writtenMemory = true
            writtenMemoryBudget = budget
            onMemoryGrowRefused = onGrowRefused
        }

        /**
         * テーブルの上限を決める（#361）。
         *
         * 最初の要素数が [maxElements] を超える、または [budget] が最初の要素の分を確保させないときは、
         * インスタンスを作るときに [WasmLinkException] になる。実行中の `table.grow` は、[maxElements] を超えるか
         * [budget] が断ると失敗する（-1 を返す。トラップではない）。
         *
         * @param maxElements テーブル1つあたりの要素数の上限（0 以上）
         * @param budget 確保を数える相手。`null` なら数えない
         */
        internal fun tableLimit(maxElements: Int, budget: WasmTableBudget?): Builder = apply {
            require(maxElements >= 0) { "maxElements は 0 以上: $maxElements" }
            maxTableElements = maxElements
            tableBudget = budget
        }

        /**
         * import [module].[name] に、ほかのインスタンスの export [extern] を渡す（#263）。同じ名前を2回渡す、またはホストの関数と
         * 重なると [IllegalArgumentException]。種類と型が import の宣言と合わなければ、インスタンスを作るときに [WasmLinkException]
         */
        internal fun importExtern(module: String, name: String, extern: WasmExtern): Builder = apply {
            require(hostImports.none { it.module == module && it.name == name }) { "$module.$name はホストの関数として登録された" }
            require(externImports.put(module to name, extern) == null) { "$module.$name が2回登録された" }
        }

        /** 燃料の残りを共有するモジュールに渡す、共有の残りを決める（#263。[WasmInstanceConfig.sharedFuel]） */
        internal fun sharedFuel(fuel: WasmSharedFuel?): Builder = apply {
            sharedFuel = fuel
        }

        /** 待機点への変換をかけたモジュールが退避する値の置き場所を決める（#329。[WasmInstanceConfig.asyncStack]）。 */
        public fun asyncStack(stack: WasmAsyncStack?): Builder = apply {
            asyncStack = stack
        }

        /** 設定を作る。 */
        public fun build(): WasmInstanceConfig = WasmInstanceConfig(this)
    }

    public companion object {
        /** ホストの関数も燃料もない設定 */
        @JvmField
        public val EMPTY: WasmInstanceConfig = Builder().build()

        /** 設定を組み立て始める。 */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
