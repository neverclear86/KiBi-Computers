package pro.ikili.kibicomputers.core.runtime

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.endive.isCompiled
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1

/**
 * WASMランタイム（Endive）が読み込めて実行できるかを確かめる最小のチェック。
 *
 * 起動時のログ出力と、開発環境・配布 jar で依存が正しく同梱されているかの確認に使う。
 */
@ApiStatus.Internal
public object RuntimeSelfCheck {
    /**
     * `(module (func (export "add") (param i32 i32) (result i32) local.get 0 local.get 1 i32.add))`
     */
    private val ADD_MODULE: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
        // type section: (i32, i32) -> i32
        0x01, 0x07, 0x01, 0x60, 0x02, 0x7f, 0x7f, 0x01, 0x7f,
        // function section
        0x03, 0x02, 0x01, 0x00,
        // export section: "add"
        0x07, 0x07, 0x01, 0x03, 0x61, 0x64, 0x64, 0x00, 0x00,
        // code section
        0x0a, 0x09, 0x01, 0x07, 0x00, 0x20, 0x00, 0x20, 0x01, 0x6a, 0x0b,
    )

    /** WASMの `add` 関数で [a] + [b] を計算して返す。 */
    @JvmStatic
    public fun add(a: Int, b: Int): Int {
        // ランタイムの抽象（#34）を通して呼ぶ
        val module = EndiveRuntime().load(ADD_MODULE)
        return module.instantiate(WasmInstanceConfig.EMPTY).use { it.call("add", a.toLong(), b.toLong())[0].toInt() }
    }

    /**
     * 同梱の `lua.wasm`（大きいモジュール）に、`exec` と同じく燃料の計測コードの挿入と p1 の待機点への変換をかけて読み込み、
     * ランタイムコンパイラでコンパイルできた（インタプリタに切り替わらなかった）なら `true`（#344・#341）。
     *
     * コンパイラは実行時の ASM でクラスを作るので、ローダーが同梱する ASM の版によっては大きい関数で落ち、
     * 黙ってインタプリタに切り替わる（ログに警告が出るだけ）。それを GameTest で確かめるために使う。重い（1 秒ほど）。
     *
     * @throws WasmLoadException 読み込めない
     * @throws IllegalStateException `lua.wasm` が同梱されていない
     */
    @JvmStatic
    @Throws(WasmLoadException::class)
    public fun compilesBundledLua(): Boolean {
        val lua = checkNotNull(RuntimeSelfCheck::class.java.getResourceAsStream(LUA_RESOURCE)) { "$LUA_RESOURCE が同梱されていない" }
            .use { it.readBytes() }
        return isCompiled(EndiveRuntime().load(Instrumentation.apply(lua, WasiP1.WAIT_IMPORTS)))
    }

    private const val LUA_RESOURCE: String = "/kibicomputers/wasm/lua.wasm"

    /** ランタイムが動くなら `true`。例外は呼び出し側に伝えず `false` を返す。 */
    @JvmStatic
    public fun isWorking(): Boolean = runCatching { add(1, 2) == 3 }.getOrDefault(false)
}
