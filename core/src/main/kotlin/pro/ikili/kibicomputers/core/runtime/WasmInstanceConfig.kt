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

    /** [module].[name] のホストの関数。なければ `null` */
    public fun hostImport(module: String, name: String): WasmHostImport? = hostImports.firstOrNull { it.module == module && it.name == name }

    /** [WasmInstanceConfig] を組み立てる。 */
    public class Builder internal constructor() {
        internal val hostImports = mutableListOf<WasmHostImport>()
        internal var initialFuel: Long = 0
        internal var fuelHandler: WasmFuelHandler? = null

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
