package pro.ikili.kibicomputers.core.wasi.p2

import org.junit.jupiter.api.Assertions.assertEquals
import pro.ikili.kibicomputers.core.wasi.kibi.WitTool
import java.io.File

/**
 * `src/main/wit/` の p2 の world（`imports-p2`）をすべて import する、中身のないコンポーネントを wasm-tools で作る（#685）。
 * `kibi:*` の WIT を、ホストが読む形（`wasm-tools component wit` が書き出す `package … { … }`）にするのと、
 * p2 のゲストと同じ形の import を持つコンポーネントでリンクを確かめるのに使う
 */
internal object KibiP2WitTool {
    /** p2 のゲストが import する world（パッケージ → world の名前） */
    val P2_WORLDS: List<String> = listOf(
        "kibi:kernel/imports-p2@0.1.0",
        "kibi:io/imports-p2@0.1.0",
        "kibi:process/imports-p2@0.1.0",
        "kibi:fs/imports-p2@0.1.0",
        "kibi:device/imports-p2@0.1.0",
        "kibi:computer/imports-p2@0.1.0",
    )

    private val root = File("src/main/wit")

    /** [extraWorld] の中身（`import …;` など）を足した world `all` の WIT と、`src/main/wit` を deps にした仮想 FS */
    private fun files(extraWorld: String): Map<String, ByteArray> {
        val includes = P2_WORLDS.joinToString("\n") { "  include $it;" }
        val world = "package test:all;\n\nworld all {\n$includes\n$extraWorld\n}\n\n" + WasiP2Wit.TEXT
        val out = HashMap<String, ByteArray>()
        out["w/all.wit"] = world.toByteArray()
        for (dir in root.listFiles { f -> f.isDirectory }.orEmpty()) {
            for (f in dir.listFiles { f -> f.extension == "wit" }.orEmpty()) out["w/deps/${dir.name}/${f.name}"] = f.readBytes()
        }
        // p3 の WIT（`*-async`）が参照する wasi:* の 0.3 は、テストのスタブで解決する（p2 の部分は WasiP2Wit.TEXT が持つ）
        val stub = File(checkNotNull(javaClass.getResource("/wit-stubs/wasi-filesystem-0.3.0")).toURI())
        for (f in stub.listFiles { f -> f.extension == "wit" }.orEmpty()) out["w/deps/wasi-filesystem-0.3.0/${f.name}"] = f.readBytes()
        return out
    }

    /** p2 の world をすべて import する、中身のない（`--dummy`）コンポーネント */
    val dummy: ByteArray by lazy {
        val e = WitTool.run(
            listOf("component", "embed", "--dummy", "--world", "all", "${WitTool.WORK}/w", "-o", "work/m.wasm"),
            files(""),
            listOf("m.wasm"),
        )
        assertEquals(0, e.exitCode, e.stderr)
        val c = WitTool.run(listOf("component", "new", "work/m.wasm", "-o", "work/c.wasm"), mapOf("m.wasm" to e.outputs.getValue("m.wasm")), listOf("c.wasm"))
        assertEquals(0, c.exitCode, c.stderr)
        c.outputs.getValue("c.wasm")
    }

    /** [dummy] の WIT のうち、`package kibi:` の塊だけを並べたもの（ホストの [KibiP2Wit.TEXT] と比べる） */
    val kibiText: String by lazy {
        val r = WitTool.run(listOf("component", "wit", "work/c.wasm"), mapOf("c.wasm" to dummy))
        assertEquals(0, r.exitCode, r.stderr)
        packages(r.stdout).filter { it.startsWith("package kibi:") }.joinToString("\n") { it.trimEnd() + "\n" }
    }

    /** `package … { … }` の塊に分ける（中括弧の深さで区切る） */
    fun packages(text: String): List<String> {
        val out = ArrayList<String>()
        var i = text.indexOf("package ")
        while (i >= 0) {
            val open = text.indexOf('{', i)
            val semi = text.indexOf(';', i)
            if (open < 0 || (semi in 0 until open)) {
                // `package root:component;` のような宣言だけの行
                i = text.indexOf("\npackage ", i + 1).let { if (it < 0) -1 else it + 1 }
                continue
            }
            var depth = 0
            var j = open
            while (j < text.length) {
                if (text[j] == '{') depth++
                if (text[j] == '}' && --depth == 0) break
                j++
            }
            out += text.substring(i, j + 1)
            i = text.indexOf("\npackage ", j).let { if (it < 0) -1 else it + 1 }
        }
        return out
    }
}
