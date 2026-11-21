package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Locale

/** OS の既定の言語の正規化と検査（#661） */
class BootLanguageTest {
    @Test
    fun `Minecraftの言語コードをPOSIXの形に直す`() {
        assertEquals("ja_JP.UTF-8", BootLanguage.fromMinecraft("ja_jp"))
        assertEquals("en_US.UTF-8", BootLanguage.fromMinecraft("en_us"))
        assertEquals("zh_TW.UTF-8", BootLanguage.fromMinecraft("zh_tw"))
        assertEquals("ja_JP.UTF-8", BootLanguage.fromMinecraft("JA_JP"), "大文字・小文字は区別しない")
        assertEquals("tok.UTF-8", BootLanguage.fromMinecraft("tok"), "地域なし")
        assertNull(BootLanguage.fromMinecraft("esan_us"), "言語は 3 文字まで")
        assertEquals("es_419.UTF-8", BootLanguage.fromMinecraft("es_419"), "地域は数字も受け付ける")
        assertEquals("en_UD.UTF-8", BootLanguage.fromMinecraft("en_ud"))
    }

    @Test
    fun `形の合わないものは捨てる`() {
        for (raw in listOf(null, "", "j", "ja_", "_jp", "ja_j", "ja_jpxx", "ja-jp", "ja_jp_x", "../x", "a=b", "ja\u0000jp", "ja_jp\n", "ja jp", "日本_jp", "x".repeat(64))) {
            assertNull(BootLanguage.fromMinecraft(raw), raw.toString())
        }
    }

    @Test
    fun `既定のロケールに左右されない`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("fi_FI.UTF-8", BootLanguage.fromMinecraft("FI_fi"))
            assertEquals("it_IT.UTF-8", BootLanguage.fromMinecraft("IT_IT"))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `検査はPOSIXの形だけを受け付ける`() {
        for (ok in listOf("ja_JP.UTF-8", "tok.UTF-8", "es_419.UTF-8")) assertTrue(BootLanguage.isValid(ok), ok)
        for (ng in listOf(null, "", "ja_jp", "ja_jp.UTF-8", "ja_JP", "ja_JP.utf8", "C", "POSIX", "LANG=ja", "ja_JP.UTF-8\u0000", "jajp_JPN.UTF-8x")) {
            assertFalse(BootLanguage.isValid(ng), ng.toString())
        }
        assertTrue("abc_ABC.UTF-8".length <= BootLanguage.MAX_LENGTH)
    }

    @Test
    fun `BootConfigのlanguageは検査に合うものだけを受け付け、toStringに出る`() {
        assertNull(BootConfig.builder().build().language)
        assertNull(BootConfig.builder().language(null).build().language)
        val config = BootConfig.builder().language("ja_JP.UTF-8").build()
        assertEquals("ja_JP.UTF-8", config.language)
        assertTrue(config.toString().contains("language=ja_JP.UTF-8"), config.toString())
        for (ng in listOf("", "ja_jp", "LANG=x", "a\u0000b")) {
            assertThrows<IllegalArgumentException>(ng) { BootConfig.builder().language(ng) }
        }
    }
}
