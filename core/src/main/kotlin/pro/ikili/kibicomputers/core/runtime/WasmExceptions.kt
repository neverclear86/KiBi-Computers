package pro.ikili.kibicomputers.core.runtime

/** ランタイムの抽象が投げる例外の基底。ランタイムの実装の例外は、これに包んで投げる。 */
public open class WasmException @JvmOverloads constructor(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** モジュールを読み込めない（WASM として壊れている、検証に通らない、対応していない機能を使う）。 */
public class WasmLoadException @JvmOverloads constructor(message: String, cause: Throwable? = null) : WasmException(message, cause)

/** インスタンスを作れない（import が足りない、型が合わない など）。 */
public class WasmLinkException @JvmOverloads constructor(message: String, cause: Throwable? = null) : WasmException(message, cause)

/**
 * ホストが線形メモリの範囲の外を読み書きしようとした。
 *
 * WASM の中の範囲外アクセスは [WasmTrapException]（[WasmTrapKind.MEMORY_OUT_OF_BOUNDS]）になる。
 * こちらはホストの関数がプレイヤーの渡したポインタを読むときのもので、カーネルはエラー（`EFAULT` など）として返せる。
 */
public class WasmMemoryAccessException(message: String) : WasmException(message)

/** トラップの種類。どの種類にも当てはまらないものは [OTHER]。 */
public enum class WasmTrapKind {
    /** `unreachable` を実行した */
    UNREACHABLE,

    /** 線形メモリの範囲の外を読み書きした */
    MEMORY_OUT_OF_BOUNDS,

    /** テーブルの範囲の外を参照した */
    TABLE_OUT_OF_BOUNDS,

    /** 整数を 0 で割った */
    INTEGER_DIVIDE_BY_ZERO,

    /** 整数の割り算が溢れた（`INT_MIN / -1`） */
    INTEGER_OVERFLOW,

    /** 浮動小数点数を整数に変換できなかった */
    INVALID_CONVERSION,

    /** 呼び出しが深すぎた */
    STACK_OVERFLOW,

    /** `call_indirect` の型が合わない、または要素が空 */
    INDIRECT_CALL,

    /** null の参照を使った */
    NULL_REFERENCE,

    /** 燃料が切れ、燃料切れの処理（[WasmFuelHandler]）がない */
    FUEL_EXHAUSTED,

    /** WASM の例外（`throw`）が誰にも捕まえられずに外に出た */
    UNCAUGHT_EXCEPTION,

    /** その他 */
    OTHER,
}

/** WASM の実行がトラップで止まった。 */
public class WasmTrapException @JvmOverloads constructor(
    /** トラップの種類 */
    public val kind: WasmTrapKind,
    message: String,
    cause: Throwable? = null,
) : WasmException(message, cause)
