package pro.ikili.kibicomputers.core.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuntimeSelfCheckTest {
    @Test
    fun `WASMのadd関数を呼び出せる`() {
        assertEquals(42, RuntimeSelfCheck.add(40, 2))
    }

    @Test
    fun `セルフチェックが成功する`() {
        assertTrue(RuntimeSelfCheck.isWorking())
    }

    @Test
    fun `失敗の例外から大きすぎる関数の番号を読める`() {
        assertTrue(RuntimeSelfCheck.readsTooLargeFunction())
    }

    @Test
    fun `コンポーネントの検証器を動かせる`() {
        assertTrue(RuntimeSelfCheck.validatesComponent())
    }
}
