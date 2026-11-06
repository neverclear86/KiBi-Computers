package pro.ikili.kibicomputers.core.wasi.kibi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * `core/src/main/wit/` の WIT を、wasm-tools（`wasm-tools component wit`。[WitTool]）で解析・解決できること（#75。ADR 0073・0075）。
 *
 * パッケージのディレクトリごとに、ほかの `kibi:*` のパッケージと、テストのリソースの `wit-stubs/`（`wasi:*` の最小のスタブ）を
 * `deps/` に置いて動かす（wit-parser の `deps/` の規則）。これで、予約語の識別子などの書き方の誤りと、
 * `use kibi:kernel/types@0.1.0.{error-code}` のようなパッケージをまたぐ参照の誤りを拾う。
 * `component wit` はパッケージの全 interface・全 world を解析するので、world ごとに分けない。
 */
class KibiWitParseTest {
    private val root = File("src/main/wit")

    /** `src/main/wit/` のパッケージのディレクトリ（後から足すパッケージも対象になる） */
    private fun packages(): List<File> =
        root.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.also { assertTrue(it.isNotEmpty(), "WIT のパッケージがない") }

    /** [dir] の中の `.wit` を、[prefix] の下の相対パス → 中身にする */
    private fun witFiles(dir: File, prefix: String): Map<String, ByteArray> =
        dir.walkTopDown().filter { it.isFile && it.extension == "wit" }
            .associate { "$prefix/${it.relativeTo(dir).invariantSeparatorsPath}" to it.readBytes() }

    /** テストのリソースの `wit-stubs/` の各パッケージ */
    private fun stubs(): List<File> {
        val url = checkNotNull(javaClass.getResource("/wit-stubs")) { "wit-stubs がテストのリソースにない" }
        return File(url.toURI()).listFiles { f -> f.isDirectory }.orEmpty().toList()
    }

    /** パッケージ [target]（`work/<名前>/` に置く）を、ほかのパッケージとスタブを `deps/` に置いて解析する */
    private fun parse(target: String, files: Map<String, ByteArray>, others: List<File>): WitTool.Result {
        val all = HashMap(files)
        for (o in others) all += witFiles(o, "$target/deps/${o.name}")
        for (s in stubs()) all += witFiles(s, "$target/deps/${s.name}")
        return WitTool.run(listOf("component", "wit", "${WitTool.WORK}/$target"), all)
    }

    @TestFactory
    fun `src_main_witのパッケージはすべてwasm-toolsで解析できる`(): List<DynamicTest> {
        val pkgs = packages()
        return pkgs.map { pkg ->
            DynamicTest.dynamicTest(pkg.name) {
                val r = parse(pkg.name, witFiles(pkg, pkg.name), pkgs - pkg)
                assertEquals(0, r.exitCode, "wasm-tools component wit ${pkg.name} が失敗した:\n${r.stderr}${r.stdout}")
                // 解決した結果（WIT のテキスト）にパッケージ名が出る
                assertTrue("package kibi:" in r.stdout, r.stdout)
            }
        }
    }

    @Test
    fun `予約語を引数名にしたWITは失敗する`() {
        // #75 のプランの第3版にあった誤り（from は WIT のキーワード）を、このテストが本当に拾えることの確認
        val wit = """
            package kibi:probe@0.1.0;

            interface fd {
                use kibi:kernel/types@0.1.0.{error-code};

                dup-to: func(from: u32, to: u32) -> result<_, error-code>;
            }
        """.trimIndent()
        val r = parse("kibi-probe", mapOf("kibi-probe/fd.wit" to wit.toByteArray()), packages())
        assertNotEquals(0, r.exitCode, "予約語の引数名が通ってしまった: ${r.stdout}")
        // 同じ WIT で引数名を逃がせば通る（失敗の理由が予約語であることの確認）
        val ok = parse("kibi-probe", mapOf("kibi-probe/fd.wit" to wit.replace("(from:", "(%from:").toByteArray()), packages())
        assertEquals(0, ok.exitCode, "%from でも失敗した:\n${ok.stderr}")
    }

    @Test
    fun `存在しないパッケージの型を参照したWITは失敗する`() {
        val wit = """
            package kibi:probe@0.1.0;

            interface x {
                use kibi:kernel/types@0.1.0.{no-such-type};
            }
        """.trimIndent()
        val r = parse("kibi-probe", mapOf("kibi-probe/x.wit" to wit.toByteArray()), packages())
        assertNotEquals(0, r.exitCode, "存在しない型の参照が通ってしまった: ${r.stdout}")
    }
}
