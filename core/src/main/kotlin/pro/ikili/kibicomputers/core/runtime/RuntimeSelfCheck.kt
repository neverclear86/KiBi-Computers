package pro.ikili.kibicomputers.core.runtime

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.core.runtime.component.ComponentLoader
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentHost
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentProgram
import pro.ikili.kibicomputers.core.runtime.component.link.LinkEnv
import pro.ikili.kibicomputers.core.runtime.component.link.LinkedComponent
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

    /**
     * コンポーネントの検証器（wasm-tools を WASM にしたもの。#262）を、最小のコンポーネント（ヘッダーだけ）に当てて、
     * 読み込んで動かせたなら `true`。検証器の jar（`run.endive:wasi`・`wasm-tools`）が実行環境から見えるかを GameTest で確かめるために使う。
     * 重い（初回は 1 秒ほど）ので、起動時には呼ばない。
     *
     * @throws WasmLoadException 検証器を読み込めない、または最小のコンポーネントが通らない
     */
    @JvmStatic
    @Throws(WasmLoadException::class)
    public fun validatesComponent(): Boolean {
        val empty = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x0d, 0x00, 0x01, 0x00)
        return ComponentLoader.load(empty).root.sections.isEmpty()
    }

    /**
     * 2 つの core インスタンス（`n` が `m` の `add` を import する）をつなぎ、`twice(a, b) = (a + b) + b` を lift したコンポーネント。
     * `wasm-tools parse` で次から作った: `(core module ${'$'}m (func (export "add") ...)) (core instance ${'$'}i (instantiate ${'$'}m))
     * (core module ${'$'}n (import "m" "add" ...) (func (export "twice") ...)) (core instance ${'$'}j (instantiate ${'$'}n (with "m" (instance ${'$'}i))))
     * (func (export "twice") (param "a" u32) (param "b" u32) (result u32) (canon lift (core func ${'$'}j "twice")))`
     */
    private val TWICE_COMPONENT: ByteArray = intArrayOf(
        0x00, 0x61, 0x73, 0x6d, 0x0d, 0x00, 0x01, 0x00, 0x01, 0x34, 0x00, 0x61, 0x73, 0x6d, 0x01, 0x00,
        0x00, 0x00, 0x01, 0x07, 0x01, 0x60, 0x02, 0x7f, 0x7f, 0x01, 0x7f, 0x03, 0x02, 0x01, 0x00, 0x07,
        0x07, 0x01, 0x03, 0x61, 0x64, 0x64, 0x00, 0x00, 0x0a, 0x09, 0x01, 0x07, 0x00, 0x20, 0x00, 0x20,
        0x01, 0x6a, 0x0b, 0x00, 0x09, 0x04, 0x6e, 0x61, 0x6d, 0x65, 0x00, 0x02, 0x01, 0x6d, 0x02, 0x04,
        0x01, 0x00, 0x00, 0x00, 0x01, 0x4e, 0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, 0x01, 0x07,
        0x01, 0x60, 0x02, 0x7f, 0x7f, 0x01, 0x7f, 0x02, 0x09, 0x01, 0x01, 0x6d, 0x03, 0x61, 0x64, 0x64,
        0x00, 0x00, 0x03, 0x02, 0x01, 0x00, 0x07, 0x09, 0x01, 0x05, 0x74, 0x77, 0x69, 0x63, 0x65, 0x00,
        0x01, 0x0a, 0x0e, 0x01, 0x0c, 0x00, 0x20, 0x00, 0x20, 0x01, 0x10, 0x00, 0x20, 0x01, 0x10, 0x00,
        0x0b, 0x00, 0x11, 0x04, 0x6e, 0x61, 0x6d, 0x65, 0x00, 0x02, 0x01, 0x6e, 0x01, 0x06, 0x01, 0x00,
        0x03, 0x61, 0x64, 0x64, 0x02, 0x08, 0x01, 0x00, 0x01, 0x01, 0x01, 0x6d, 0x12, 0x00, 0x07, 0x0b,
        0x01, 0x40, 0x02, 0x01, 0x61, 0x79, 0x01, 0x62, 0x79, 0x00, 0x79, 0x06, 0x0b, 0x01, 0x00, 0x00,
        0x01, 0x01, 0x05, 0x74, 0x77, 0x69, 0x63, 0x65, 0x08, 0x06, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x0b, 0x0b, 0x01, 0x00, 0x05, 0x74, 0x77, 0x69, 0x63, 0x65, 0x01, 0x00, 0x00, 0x00, 0x25, 0x0e,
        0x63, 0x6f, 0x6d, 0x70, 0x6f, 0x6e, 0x65, 0x6e, 0x74, 0x2d, 0x6e, 0x61, 0x6d, 0x65, 0x01, 0x09,
        0x00, 0x11, 0x02, 0x00, 0x01, 0x6d, 0x01, 0x01, 0x6e, 0x01, 0x09, 0x00, 0x12, 0x02, 0x00, 0x01,
        0x69, 0x01, 0x01, 0x6a,
    ).let { ints -> ByteArray(ints.size) { ints[it].toByte() } }

    /**
     * コンポーネントを読み込み、リンクして（燃料の計測コードを共有の形で挿入し、core インスタンスどうしをつなぐ。#263）、
     * `twice(40, 1)` が 42 なら `true`。検証器とランタイムコンパイラ・リンカーが実行環境で動くかを GameTest で確かめるために使う。
     * 重い（初回は 1 秒ほど）ので、起動時には呼ばない。
     *
     * @throws WasmLoadException 読み込めない
     * @throws WasmTrapException 実行がトラップした
     */
    @JvmStatic
    @Throws(WasmLoadException::class, WasmTrapException::class)
    public fun linksComponent(): Boolean {
        val program = ComponentProgram.load(EndiveRuntime(), TWICE_COMPONENT, ComponentHost.NONE, metered = true)
        return LinkedComponent.instantiate(program.plan, LinkEnv.NONE, WasmSharedFuel(1_000_000)).use { c ->
            val twice = checkNotNull(c.function("twice")) { "twice がない" }
            c.call(twice, listOf(CanonValue.I32(40), CanonValue.I32(1))) == CanonValue.I32(42)
        }
    }

    private const val LUA_RESOURCE: String = "/kibicomputers/wasm/lua.wasm"

    /** ランタイムが動くなら `true`。例外は呼び出し側に伝えず `false` を返す。 */
    @JvmStatic
    public fun isWorking(): Boolean = runCatching { add(1, 2) == 3 }.getOrDefault(false)
}
