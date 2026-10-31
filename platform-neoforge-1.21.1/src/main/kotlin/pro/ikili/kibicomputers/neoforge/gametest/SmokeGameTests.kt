package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.runtime.RuntimeSelfCheck

/**
 * 起動確認用の GameTest。`runGameTestServer` で実行される。
 *
 * テンプレート `kibicomputers:empty` は空の 3x3x3 構造物
 * （`data/kibicomputers/structure/empty.nbt`）。
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

    const val EMPTY: String = "empty"
}
