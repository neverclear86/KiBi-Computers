package io.github.neverclear86.kibicomputers.core.runtime

import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.Parser

/**
 * WASMランタイム（Chicory）が読み込めて実行できるかを確かめる最小のチェック。
 *
 * 起動時のログ出力と、開発環境・配布 jar で依存が正しく同梱されているかの確認に使う。
 */
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
        val module = Parser.parse(ADD_MODULE)
        val instance = Instance.builder(module).build()
        val result = instance.export("add").apply(a.toLong(), b.toLong())
        return result[0].toInt()
    }

    /** ランタイムが動くなら `true`。例外は呼び出し側に伝えず `false` を返す。 */
    @JvmStatic
    public fun isWorking(): Boolean = runCatching { add(1, 2) == 3 }.getOrDefault(false)
}
