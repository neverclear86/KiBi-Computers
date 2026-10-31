package pro.ikili.kibicomputers.core.runtime

/**
 * ランタイムの境界で扱う WASM の値の型。
 *
 * ホストの関数と export の呼び出しでは、値をすべて `long` で受け渡す（Endive と同じ）。
 * - [I32]: 下位 32 ビット（符号拡張しても、しなくてもよい。読む側は `toInt()` する）
 * - [I64]: そのまま
 * - [F32]: `Float.floatToRawIntBits` の下位 32 ビット
 * - [F64]: `Double.doubleToRawLongBits`
 *
 * 参照型・`v128` はホストの関数の境界に出さない（カーネルのシステムコールは数値だけを使う）。
 * それらを使う関数は、型を [UNSUPPORTED] として見せる。
 */
public enum class WasmValueType {
    I32,
    I64,
    F32,
    F64,

    /** 参照型・`v128` など、この抽象では受け渡しできない型 */
    UNSUPPORTED,
}

/** 関数の型（引数と戻り値の型の並び）。 */
public class WasmFunctionType(params: List<WasmValueType>, results: List<WasmValueType>) {
    /** 引数の型 */
    public val params: List<WasmValueType> = params.toList()

    /** 戻り値の型 */
    public val results: List<WasmValueType> = results.toList()

    override fun equals(other: Any?): Boolean = other is WasmFunctionType && params == other.params && results == other.results

    override fun hashCode(): Int = 31 * params.hashCode() + results.hashCode()

    override fun toString(): String = "(${params.joinToString(", ")}) -> (${results.joinToString(", ")})"

    public companion object {
        /** 引数も戻り値もない関数の型 */
        @JvmField
        public val EMPTY: WasmFunctionType = WasmFunctionType(emptyList(), emptyList())

        /** [params] を受け取り [results] を返す関数の型。 */
        @JvmStatic
        public fun of(params: List<WasmValueType>, results: List<WasmValueType>): WasmFunctionType = WasmFunctionType(params, results)
    }
}

/** import・export されるものの種類。 */
public enum class WasmExternKind {
    FUNCTION,
    TABLE,
    MEMORY,
    GLOBAL,
    TAG,
}

/**
 * モジュールが求める import の1つ。
 *
 * @property functionType [kind] が [WasmExternKind.FUNCTION] のときの型。それ以外は `null`
 */
public class WasmImportDescriptor(
    public val module: String,
    public val name: String,
    public val kind: WasmExternKind,
    public val functionType: WasmFunctionType?,
) {
    override fun equals(other: Any?): Boolean = other is WasmImportDescriptor &&
        module == other.module &&
        name == other.name &&
        kind == other.kind &&
        functionType == other.functionType

    override fun hashCode(): Int = listOf(module, name, kind, functionType).hashCode()

    override fun toString(): String = "import $module.$name ($kind${functionType?.let { " $it" } ?: ""})"
}

/**
 * モジュールが出す export の1つ。
 *
 * @property functionType [kind] が [WasmExternKind.FUNCTION] のときの型。それ以外は `null`
 */
public class WasmExportDescriptor(
    public val name: String,
    public val kind: WasmExternKind,
    public val functionType: WasmFunctionType?,
) {
    override fun equals(other: Any?): Boolean = other is WasmExportDescriptor &&
        name == other.name &&
        kind == other.kind &&
        functionType == other.functionType

    override fun hashCode(): Int = listOf(name, kind, functionType).hashCode()

    override fun toString(): String = "export $name ($kind${functionType?.let { " $it" } ?: ""})"
}
