package pro.ikili.kibicomputers.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `pro.ikili.kibicomputers.api` 以外のトップレベルの `public` な宣言に、`@ApiStatus.Internal` が付いているかを
 * ソースから確かめるテスト（#366）。
 *
 * 注釈は CLASS 保持でリフレクションから見えず、Kotlin の `internal` もバイトコードでは `public` になるので、
 * ソースの行で見る。トップレベルの宣言は列 0 から書く（Kotlin 公式スタイル）前提。
 * 入れ子の宣言は、外側の型の印でまとめて内部扱いになるので見ない。
 */
class InternalMarkerTest {
    private val sourceRoot = File("src/main/kotlin")

    @Test
    fun publicDeclarationsOutsideApiAreMarkedInternal() {
        val files = kotlinFiles()
        assertTrue(files.isNotEmpty(), "ソースが見つからない: ${sourceRoot.absolutePath}")

        val missing = mutableListOf<String>()
        val wrong = mutableListOf<String>()
        var checked = 0
        for (file in files) {
            val inApi = file.invariantSeparatorsPath.contains("/pro/ikili/kibicomputers/api/")
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                if (!TOP_LEVEL_PUBLIC.containsMatchIn(line)) return@forEachIndexed
                checked++
                val marked = annotationsAbove(lines, index).any { it.startsWith(MARKER) }
                val where = "${file.relativeTo(sourceRoot).invariantSeparatorsPath}:${index + 1}: $line"
                if (inApi && marked) wrong += where
                if (!inApi && !marked) missing += where
            }
        }
        assertTrue(checked > 0, "トップレベルの public な宣言が見つからない")
        assertEquals(emptyList<String>(), missing, "api 以外の public に @ApiStatus.Internal を付ける")
        assertEquals(emptyList<String>(), wrong, "api の宣言に @ApiStatus.Internal を付けない")
    }

    private fun kotlinFiles(): List<File> =
        sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    /** [index] の行のすぐ上に、列 0 から続く注釈の行。 */
    private fun annotationsAbove(lines: List<String>, index: Int): List<String> {
        val result = mutableListOf<String>()
        var i = index - 1
        while (i >= 0 && lines[i].startsWith("@")) {
            result += lines[i]
            i--
        }
        return result
    }

    private companion object {
        const val MARKER = "@ApiStatus.Internal"

        /** 列 0 から始まる `public` な宣言。2次コンストラクターの `public constructor` は除く。 */
        val TOP_LEVEL_PUBLIC = Regex("""^public (?!constructor\b)""")
    }
}
