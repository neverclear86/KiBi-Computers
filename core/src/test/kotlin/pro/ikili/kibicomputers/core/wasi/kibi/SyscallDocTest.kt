package pro.ikili.kibicomputers.core.wasi.kibi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1Kibi
import java.io.File

/**
 * システムコール仕様書（`docs/syscalls/{ja,en}/`。#79）の表が、実装と WIT からずれていないこと。
 *
 * 文書の中の `<!-- check:… -->` から `<!-- /check -->` までの表と、`// <パッケージ>/<ファイル>.wit` で始まる ```wit のブロックを読む:
 * - `check:p1-functions`: p1 の関数の一覧が [WasiP1.specFunctions] と一致し、`NOSYS` と書いたものがちょうど実装していないもの
 * - `check:error-codes`: `error-code` の判別子・名前・p1 の errno が `types.wit` と `Errno` の並びと一致
 * - `check:imports <モジュール>`: `kibi:*` の p1 の import の表（関数名・core の型・`cabi_realloc` が要るか・止まりうるか）が
 *   [WasiP1Kibi] と [WasiP1.WAIT_IMPORTS] と一致し、全体で出している関数をちょうど覆う
 * - ```wit のブロック: コメントと空行を除いて、`core/src/main/wit/` の元のファイルと一致し、p1 で使う WIT をすべて載せている
 */
class SyscallDocTest {
    private val root = File("../docs/syscalls")
    private val langs = listOf("ja", "en")

    private fun pages(lang: String): List<File> =
        File(root, lang).listFiles { f -> f.extension == "md" }!!.sortedBy { it.name }.also { assertTrue(it.isNotEmpty(), "$lang に文書がない") }

    /** [tag] の印から `<!-- /check -->` までの、表の行のセル（見出しと区切りの行を除く） */
    private fun checked(lang: String, tag: String): List<Pair<String, List<List<String>>>> {
        val out = ArrayList<Pair<String, List<List<String>>>>()
        val begin = Regex("""^<!-- check:${Regex.escape(tag)}(?: (\S+))? -->$""")
        for (page in pages(lang)) {
            var arg: String? = null
            var rows: MutableList<List<String>>? = null
            var header = 0
            for (line in page.readLines()) {
                val m = begin.find(line)
                if (m != null) {
                    arg = m.groupValues[1]
                    rows = ArrayList()
                    header = 0
                    continue
                }
                val r = rows ?: continue
                if (line == "<!-- /check -->") {
                    out += "${page.name} ${arg.orEmpty()}".trim() to r
                    rows = null
                    continue
                }
                if (!line.startsWith("|")) continue
                // 見出しと区切りの2行を飛ばす
                if (header < 2) {
                    header++
                    continue
                }
                r += line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
            }
            assertEquals(null, rows, "${page.path}: check の終わりがない")
        }
        return out
    }

    private fun code(cell: String): String {
        assertTrue(cell.startsWith("`") && cell.endsWith("`") && cell.length > 2, "`…` でない: $cell")
        return cell.substring(1, cell.length - 1)
    }

    @Test
    fun `p1の関数の表は仕様の全関数で、NOSYSは実装していないものだけ`() {
        for (lang in langs) {
            val tables = checked(lang, "p1-functions")
            assertEquals(1, tables.size, "$lang: p1 の関数の表は1つ")
            val rows = tables.single().second
            val names = rows.map { code(it[0]) }
            assertEquals(names.size, names.toSet().size, "$lang: 重複がある")
            assertEquals(WasiP1.specFunctions, names.toSet(), lang)
            for (row in rows) {
                val name = code(row[0])
                assertEquals(name !in WasiP1.implemented, "NOSYS" in row[1], "$lang: $name の対応の欄（${row[1]}）")
            }
        }
    }

    @Test
    fun `error-codeの表はtypes_witとErrnoの並びと一致する`() {
        val errnos = Errno.entries.filter { it != Errno.SUCCESS }.sortedBy { it.code }
        for (lang in langs) {
            val tables = checked(lang, "error-codes")
            assertEquals(1, tables.size, "$lang: error-code の表は1つ")
            val rows = tables.single().second
            assertEquals(KibiErrors.NAMES.size, rows.size, lang)
            rows.forEachIndexed { i, row ->
                assertEquals(i.toString(), row[0], "$lang: 判別子")
                assertEquals(KibiErrors.NAMES[i], code(row[1]), "$lang: 判別子 $i の名前")
                assertEquals(errnos[i].code.toString(), row[2], "$lang: 判別子 $i の errno")
                val p1Name = if (errnos[i] == Errno.TOOBIG) "2BIG" else errnos[i].name
                assertEquals(p1Name, code(row[3]), "$lang: 判別子 $i の p1 の名前")
            }
        }
    }

    @Test
    fun `kibiのimportの表は出している関数と型と一致する`() {
        val expected = WasiP1Kibi.functions.toSet()
        for (lang in langs) {
            val seen = ArrayList<Pair<String, String>>()
            for ((where, rows) in checked(lang, "imports")) {
                val module = where.substringAfter(' ', "")
                assertTrue(module.startsWith(WasiP1Kibi.PREFIX), "$lang $where: モジュール名がない")
                for (row in rows) {
                    val name = code(row[0])
                    val fn = module to name
                    seen += fn
                    val type = WasiP1Kibi.typeOf(module, name)
                    assertTrue(type != null, "$lang: 出していない関数 $module.$name")
                    val (params, results) = parseSignature(code(row[1]))
                    assertEquals(type!!.params, params, "$lang: $module.$name の引数")
                    assertEquals(type.results, results, "$lang: $module.$name の戻り値")
                    assertEquals(WasiP1Kibi.needsRealloc(module, name), row[2] != "—", "$lang: $module.$name の cabi_realloc の欄")
                    assertEquals("$module.$name" in WasiP1.WAIT_IMPORTS, row[3] != "—", "$lang: $module.$name の待つの欄")
                }
            }
            assertEquals(seen.size, seen.toSet().size, "$lang: 重複がある")
            assertEquals(expected, seen.toSet(), "$lang: 表の関数")
        }
    }

    /** `(a: i32, b: i64) -> ()`・`() -> i32` を読む（引数の名前は捨てる） */
    private fun parseSignature(sig: String): Pair<List<WasmValueType>, List<WasmValueType>> {
        val m = Regex("""^\((.*)\) -> (\(\)|i32|i64)$""").find(sig) ?: throw AssertionError("型の書き方が違う: $sig")
        val params = m.groupValues[1].takeIf { it.isNotBlank() }?.split(",")?.map { p ->
            val t = p.substringAfter(':', "").trim()
            valueType(t) ?: throw AssertionError("引数の型がない: $p（$sig）")
        } ?: emptyList()
        val results = listOfNotNull(valueType(m.groupValues[2]))
        return params to results
    }

    private fun valueType(t: String): WasmValueType? = when (t) {
        "i32" -> WasmValueType.I32
        "i64" -> WasmValueType.I64
        else -> null
    }

    @Test
    fun `WITのブロックは元のファイルと一致し、p1で使うWITをすべて載せている`() {
        val witRoot = File("src/main/wit")
        val p1Wit = witRoot.walkTopDown()
            .filter { it.isFile && it.extension == "wit" && it.name != "world.wit" && !Regex("""-(poll|async|streams)\.wit$""").containsMatchIn(it.name) }
            .map { it.relativeTo(witRoot).invariantSeparatorsPath }
            .toSet()
        assertTrue(p1Wit.isNotEmpty())
        for (lang in langs) {
            val seen = HashSet<String>()
            for (page in pages(lang)) {
                val text = textOf(page)
                for (block in Regex("""```wit\n(.*?)```""", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
                    val lines = block.groupValues[1].lines()
                    val path = lines.first().removePrefix("// ").trim()
                    val source = File(witRoot, path)
                    assertTrue(source.isFile, "$lang ${page.name}: WIT のブロックの元がない（$path）")
                    assertTrue(seen.add(path), "$lang: $path を2回載せている")
                    assertEquals(normalize(source.readLines()), normalize(lines.drop(1)), "$lang ${page.name}: $path")
                }
            }
            assertEquals(p1Wit, seen, "$lang: 載せた WIT")
        }
    }

    /** 改行を LF にそろえて読む（Windows で CRLF に変えて取り出しても通るように） */
    private fun textOf(f: File): String = f.readLines().joinToString("\n", postfix = "\n")

    /** コメントと空行を除き、行末の空白を落とす */
    private fun normalize(lines: List<String>): List<String> = lines.map { it.substringBefore("//").trimEnd() }.filter { it.isNotBlank() }

    @Test
    fun `日本語と英語で同じページがある`() {
        assertEquals(pages("ja").map { it.name }, pages("en").map { it.name })
        for (lang in langs) {
            for (page in pages(lang)) {
                val text = textOf(page)
                assertTrue(Regex("""\A---\ntitle: .+\ndescription: .+\n---\n""").containsMatchIn(text), "$lang ${page.name}: frontmatter")
                // 相対リンクの先があること
                for (link in Regex("""\]\(\./([a-z0-9-]+\.md)""").findAll(text)) {
                    assertTrue(File(root, "$lang/${link.groupValues[1]}").isFile, "$lang ${page.name}: リンク先がない ${link.groupValues[1]}")
                }
            }
        }
    }
}
