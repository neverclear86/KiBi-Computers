package pro.ikili.kibicomputers.forge.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.runtime.RuntimeSelfCheck
import pro.ikili.kibicomputers.core.runtime.WasmExternKind
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime

/**
 * 起動確認用の GameTest。`runGameTestServer` で実行される。
 *
 * テンプレート `kibicomputers:empty` は空の 3x3x3 構造物
 * （1.20.1 では `data/kibicomputers/structures/empty.nbt`）。
 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object SmokeGameTests {
    @JvmStatic
    @GameTest(template = EMPTY)
    fun wasmRuntimeWorksOnServer(helper: GameTestHelper) {
        helper.assertTrue(RuntimeSelfCheck.add(40, 2) == 42, "WASM add(40, 2) should be 42")
        helper.succeed()
    }

    /** 同梱の lua.wasm（WASM の例外処理を使う実際のプログラム）を、ゲームの中でランタイムの抽象を通して読み込める（#35）。 */
    @JvmStatic
    @GameTest(template = EMPTY)
    fun bundledLuaWasmLoadsOnServer(helper: GameTestHelper) {
        val bytes = SmokeGameTests::class.java.getResourceAsStream("/kibicomputers/wasm/lua.wasm")?.use { it.readBytes() }
        helper.assertTrue(bytes != null, "lua.wasm should be bundled")
        val module = EndiveRuntime().load(bytes!!)
        helper.assertTrue(module.exports.any { it.name == "_start" && it.kind == WasmExternKind.FUNCTION }, "lua.wasm should export _start")
        helper.assertTrue(module.imports.any { it.module == "wasi_snapshot_preview1" && it.name == "fd_write" }, "lua.wasm should import fd_write")
        helper.succeed()
    }

    /**
     * 大きいモジュール（燃料の計測コードを挿入した lua.wasm）を、ローダーが同梱する ASM でランタイムコンパイラにかけられる（#344）。
     * 失敗すると黙ってインタプリタに切り替わり、遅くなるだけで気付けないため、ここで確かめる。
     */
    @JvmStatic
    @GameTest(template = EMPTY)
    fun bundledLuaWasmCompilesOnServer(helper: GameTestHelper) {
        helper.assertTrue(RuntimeSelfCheck.compilesBundledLua(), "lua.wasm should compile with the runtime compiler (not fall back to the interpreter)")
        helper.succeed()
    }

    const val EMPTY: String = "empty"
}
