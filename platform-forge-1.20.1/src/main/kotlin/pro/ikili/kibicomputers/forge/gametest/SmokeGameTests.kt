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

    /**
     * コンパイルの失敗の例外から大きすぎる関数の番号を読む処理（#446）が使う ASM のクラスを、ゲームの実行環境（ローダーの ASM）から解決できる。
     * この参照は SIMD を使うモジュールのコンパイルが失敗したときにしか解決されないので、ここで確かめる。
     */
    @JvmStatic
    @GameTest(template = EMPTY)
    fun tooLargeFunctionReadableOnServer(helper: GameTestHelper) {
        helper.assertTrue(RuntimeSelfCheck.readsTooLargeFunction(), "the too-large function should be read from the ASM exception")
        helper.succeed()
    }

    /**
     * コンポーネントの検証器（wasm-tools を WASM にしたもの）を、ゲームの実行環境で読み込んで動かせる（#262）。
     * 検証器の jar（`run.endive:wasi`・`wasm-tools`）の同梱と実行時クラスパスの配線が合っているかを確かめる。
     */
    @JvmStatic
    @GameTest(template = EMPTY)
    fun componentValidatorWorksOnServer(helper: GameTestHelper) {
        helper.assertTrue(RuntimeSelfCheck.validatesComponent(), "the component validator should load and accept a minimal component")
        helper.succeed()
    }

    /**
     * コンポーネントを読み込み、core インスタンスどうしをつないで（燃料のグローバルを共有して）呼べる（#263）。
     * 検証器・燃料の計測コードの挿入・ランタイムコンパイラ・リンカーが、ゲームの実行環境で一緒に動くかを確かめる。
     */
    @JvmStatic
    @GameTest(template = EMPTY)
    fun componentLinksOnServer(helper: GameTestHelper) {
        helper.assertTrue(RuntimeSelfCheck.linksComponent(), "a component with two linked core instances should run")
        helper.succeed()
    }

    /**
     * p2 のコマンドのコンポーネントを `exec` と同じ読み方（`wasi:*` のホストを渡して読み込む）で読み込み、既定のドライバーの選び方で
     * p2 のドライバーを選べて、インスタンス化して `run` を呼ぶと `ok` が返る。あわせて `wasi:*` のホストを組み立てる（WIT の読み取りと
     * 各インターフェースの実装との照合）。このコンポーネントは import を持たないので、組み立ては明示的に呼んで確かめる（#687）。
     * 検証器・リンカー・ホストのクラスが、ゲームの実行環境で読めるかを確かめる。実物の `wasi:*` の import を呼ぶところは確かめない
     * （`core` の単体テストが受け持つ）。
     */
    @JvmStatic
    @GameTest(template = EMPTY)
    fun componentCommandLoadsOnServer(helper: GameTestHelper) {
        helper.assertTrue(RuntimeSelfCheck.loadsComponentCommand(), "a p2 command component should load for exec and get the p2 driver")
        helper.succeed()
    }

    /**
     * 製品の jar（Jar-in-Jar で Endive を同梱したもの）から読んだときに、Endive のクラス（ランタイム・コンパイラ・WASI・wasm-tools）が
     * 開発環境のクラスパスではなく、jar に同梱したもの（FML がモジュールとして読む）から来ている（#775）。
     *
     * `runJarGameTestServer`（`kibicomputers.jarTest=true`）でだけ確かめる。開発環境の run では Endive を実行時クラスパスから読むので、
     * 何もせずに成功にする。jar の run で、ほかの GameTest（`componentCommandLoadsOnServer` など）が通っても、Endive がクラスパスから
     * 読まれていたら同梱を確かめたことにならないため、その偽の合格を防ぐ。
     */
    @JvmStatic
    @GameTest(template = EMPTY)
    fun bundledLibrariesComeFromJarInJar(helper: GameTestHelper) {
        if (!java.lang.Boolean.getBoolean("kibicomputers.jarTest")) {
            helper.succeed()
            return
        }
        // wasm-tools の jar は module-info を持たない（自動モジュール。名前は FML が付ける）ので、名前は見ない
        val classes = listOf<Pair<String, String?>>(
            "run.endive.runtime.Instance" to "run.endive.runtime",
            "run.endive.compiler.MachineFactoryCompiler" to "run.endive.compiler",
            "run.endive.wasi.WasiPreview1" to "run.endive.wasi",
            "run.endive.tools.wasm.WasmToolsModule" to null,
        )
        for ((name, module) in classes) {
            val c = Class.forName(name, false, SmokeGameTests::class.java.classLoader)
            val where = "${c.module.name} ${c.classLoader} ${c.protectionDomain?.codeSource?.location}"
            org.slf4j.LoggerFactory.getLogger("kibicomputers.jarTest").info("{}: {}", name, where)
            // クラスパス（無名モジュール・アプリケーションのクラスローダー）ではなく、MOD の jar の META-INF/jarjar/ から読んだ名前付きモジュール
            helper.assertTrue(c.module.isNamed && (module == null || c.module.name == module), "$name should be in a named module ${module ?: ""}: $where")
            val location = c.protectionDomain?.codeSource?.location?.toString().orEmpty()
            helper.assertTrue("/META-INF/jarjar/" in location, "$name should be loaded from a jar bundled with Jar-in-Jar: $where")
            helper.assertTrue(c.classLoader !== ClassLoader.getSystemClassLoader(), "$name should not come from the class path: $where")
        }
        helper.assertTrue(RuntimeSelfCheck.loadsComponentCommand(), "a p2 command component should load from the bundled jars")
        helper.succeed()
    }

    const val EMPTY: String = "empty"
}
