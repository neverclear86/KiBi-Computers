package pro.ikili.kibicomputers.core.runtime

/** ホストの関数を1つ、import の名前に結び付けたもの。 */
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
     * 待機点への変換をかけたモジュールが退避する値の置き場所（#329）。変換したモジュール（[WasmModule.isAsyncified]）では必須で、
     * `null` のままインスタンスを作ると [WasmLinkException]。変換していないモジュールでは使わない
     */
    public val asyncStack: WasmAsyncStack? = builder.asyncStack

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

        /** ホストの関数を足す。同じ名前を2回足すと [IllegalArgumentException]。 */
        public fun hostFunction(import: WasmHostImport): Builder = apply {
            require(hostImports.none { it.module == import.module && it.name == import.name }) { "${import.module}.${import.name} が2回登録された" }
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
            maxMemoryPages = maxPages
            memoryBudget = budget
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
