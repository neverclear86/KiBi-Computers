package pro.ikili.kibicomputers.core.persist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 冷えた起動のログの出し方（#88、ADR 0088）: MOD のバージョン違いは最初の1回だけ要約、ほかの理由は毎回 */
class ColdBootLogTest {
    @Test
    fun `MOD_VERSIONは最初の1回だけSUMMARYで、後はQUIET`() {
        val log = ColdBootLog()
        assertEquals(
            listOf(ColdBootLog.Kind.SUMMARY, ColdBootLog.Kind.QUIET, ColdBootLog.Kind.QUIET),
            List(3) { log.classify(ColdBootCause.MOD_VERSION) },
        )
    }

    @Test
    fun `ほかの理由とnullは、MOD_VERSIONの前後どちらでも毎回EACH`() {
        val log = ColdBootLog()
        val others = ColdBootCause.entries.filter { it != ColdBootCause.MOD_VERSION } + listOf<ColdBootCause?>(null)
        for (cause in others) assertEquals(ColdBootLog.Kind.EACH, log.classify(cause), "前: $cause")
        assertEquals(ColdBootLog.Kind.SUMMARY, log.classify(ColdBootCause.MOD_VERSION), "ほかの理由は要約の1回を使わない")
        for (cause in others) assertEquals(ColdBootLog.Kind.EACH, log.classify(cause), "後: $cause")
        assertEquals(ColdBootLog.Kind.QUIET, log.classify(ColdBootCause.MOD_VERSION))
    }

    @Test
    fun `作り直せば（サーバーの起動ごとに）また要約から始まる`() {
        val first = ColdBootLog()
        assertEquals(ColdBootLog.Kind.SUMMARY, first.classify(ColdBootCause.MOD_VERSION))
        assertEquals(ColdBootLog.Kind.QUIET, first.classify(ColdBootCause.MOD_VERSION))
        val second = ColdBootLog()
        assertEquals(ColdBootLog.Kind.SUMMARY, second.classify(ColdBootCause.MOD_VERSION))
    }
}
