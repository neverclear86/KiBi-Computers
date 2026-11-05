package pro.ikili.kibicomputers.poc.oslanguage

import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.runtime.Instrumentation
import pro.ikili.kibicomputers.core.runtime.WasmExternKind
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.validation.ModuleLimits
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1
import java.security.MessageDigest

/** 比べる言語 */
internal enum class Language(val label: String, val suffix: String) {
    ZIG("Zig", "zig"),
    MOONBIT("MoonBit", "moonbit"),
}

/** 比べるプログラム（両方の言語で同じ振る舞い） */
internal val PROGRAMS: List<String> = listOf("echo", "cat", "ls", "shell", "kmem")

/** リソースの `.wasm`。ビルドしていなければ `null`（MoonBit は CI か `kibi.moonbit` のときだけビルドする） */
internal fun wasm(program: String, language: Language): ByteArray? =
    Bench::class.java.getResourceAsStream("/kibicomputers/poc/os-language/$program-${language.suffix}.wasm")?.use { it.readBytes() }

/** ビルドしてある言語 */
internal fun availableLanguages(): List<Language> = Language.entries.filter { l -> PROGRAMS.all { wasm(it, l) != null } }

/** 測るシナリオ。[stdin] と [files]（根の直下）を渡して [program] を [args] で動かす */
internal class Scenario(
    val label: String,
    val program: String,
    val args: List<String>,
    val stdin: ByteArray = ByteArray(0),
    val files: Map<String, ByteArray> = emptyMap(),
)

internal object Bench {
    /** 256 KiB の、行のあるテキスト */
    private val text: ByteArray = buildString {
        var i = 0
        while (length < 256 * 1024) append("line ${i++} the quick brown fox jumps over the lazy dog\n")
    }.toByteArray(Charsets.US_ASCII).copyOf(256 * 1024)

    /** シェルに渡す 2,000 行（空行・タブ・長い語を混ぜる） */
    private val shellInput: ByteArray = buildString {
        for (i in 0 until 2_000) {
            when (i % 4) {
                0 -> append("ls -l /tmp/dir$i\n")
                1 -> append("  echo\thello   world $i  \n")
                2 -> append("\n")
                else -> append("cat ${"x".repeat(200)} /d/file$i\n")
            }
        }
    }.toByteArray(Charsets.US_ASCII)

    private val dirFiles: Map<String, ByteArray> = (0 until 200).associate { "file-%03d.txt".format(it) to ByteArray(it) }

    val scenarios: List<Scenario> = listOf(
        Scenario("echo（引数 3 つ）", "echo", listOf("echo", "hello", "kibi", "world")),
        Scenario("cat（256 KiB のファイル）", "cat", listOf("cat", "/big.txt"), files = mapOf("big.txt" to text)),
        Scenario("cat（256 KiB の標準入力）", "cat", listOf("cat"), stdin = text),
        Scenario("ls（200 ファイル）", "ls", listOf("ls", "/"), files = dirFiles),
        Scenario("shell（2,000 行）", "shell", listOf("shell"), stdin = shellInput),
        Scenario("kmem（kibi の import）", "kmem", listOf("kmem")),
    )

    private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).take(4).joinToString("") { "%02x".format(it) }

    private fun kb(bytes: Long): String = "%.1f KB".format(bytes / 1024.0)

    private fun ms(nanos: Long): String = "%.3f ms".format(nanos / 1e6)

    /** 測って、Markdown の表を返す。[rounds] は時間を測る回数（中央値を出す。1回目はウォームアップとして捨てる） */
    fun run(rounds: Int, languages: List<Language> = availableLanguages()): String = buildString {
        val runtime = EndiveRuntime()
        appendLine("# 標準 OS の実装言語の比較（#32）")
        appendLine()
        appendLine("- `./gradlew :poc:os-language:run` の出力。${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}、${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        appendLine("- 言語: ${languages.joinToString(" / ") { it.label }}。時間は $rounds 回の中央値（1回目はウォームアップとして捨てる）。実行方式はランタイムコンパイラ")
        appendLine()

        appendLine("## モジュール")
        appendLine()
        appendLine("変換後: 燃料の計測コードの挿入と待機点への変換（`Machine.exec` と同じ。#38・#329）をかけた後。燃料待ち点: 燃料切れも待ち点にできたか（#339 の段1）")
        appendLine()
        appendLine("| プログラム | 言語 | サイズ | 変換後 | 燃料待ち点 | 読み込み（変換・コンパイル） | import |")
        appendLine("| --- | --- | ---: | ---: | --- | ---: | --- |")
        for (p in PROGRAMS) {
            for (l in languages) {
                val bytes = wasm(p, l)!!
                val applied = Instrumentation.applyDetailed(bytes, WasiP1.WAIT_IMPORTS)
                val loads = (0..rounds).map {
                    val t = System.nanoTime()
                    Program.loadMetered(runtime, bytes, ModuleLimits.DEFAULT, WasiP1.WAIT_IMPORTS)
                    System.nanoTime() - t
                }.drop(1)
                val imports = runtime.load(bytes).imports.filter { it.kind == WasmExternKind.FUNCTION }.joinToString(", ") { "${it.module}.${it.name}" }
                appendLine(
                    "| $p | ${l.label} | ${bytes.size} B | ${applied.bytes.size} B | ${if (applied.fuelYield) "できる" else "できない: ${applied.fuelYieldRejection}"} | " +
                        "${ms(median(loads))} | $imports |",
                )
            }
        }
        appendLine()

        appendLine("## 燃料と速さ（カーネルを通さない最小の WASI ホスト）")
        appendLine()
        appendLine("燃料の計測コードだけを挿入して Endive で直接動かす（待機点への変換はかけない。燃料は変換の前に挿入するので、カーネルの上と同じ量）。")
        appendLine("生成: インスタンスを作る時間（MoonBit は start 関数の初期化を含む）。実行: `_start` の呼び出し。出力: 標準出力の SHA-256 の先頭")
        appendLine()
        appendLine("| シナリオ | 言語 | 終了コード | 燃料 | 生成 | 実行 | 出力 |")
        appendLine("| --- | --- | ---: | ---: | ---: | ---: | --- |")
        for (s in scenarios) {
            for (l in languages) {
                val module = MiniWasi.load(runtime, wasm(s.program, l)!!)
                val results = (0..rounds).map { MiniWasi(s.args, s.stdin, s.files).run(module) }
                val last = results.last()
                val timed = results.drop(1)
                appendLine(
                    "| ${s.label} | ${l.label} | ${last.exitCode} | ${"%,d".format(last.fuel)} | ${ms(median(timed.map { it.instantiateNanos }))} | " +
                        "${ms(median(timed.map { it.runNanos }))} | `${sha(last.stdout)}` (${last.stdout.size} B) |",
                )
            }
        }
        appendLine()

        appendLine("## カーネルの上（待機点型で動くか、入力を待つまでに書き換える RAM）")
        appendLine()
        appendLine("RAM: 入力を待っているときに書き換えた 4 KiB の区画（#407。RAM に数える）。ROM: データセグメントで書いた区画（数えない）。宣言: 線形メモリの大きさ")
        appendLine()
        appendLine("| プログラム | 言語 | 種類 | 燃料待ち点 | RAM | ROM | 宣言 | 入力の後 |")
        appendLine("| --- | --- | --- | --- | ---: | ---: | ---: | --- |")
        KernelRun().use { k ->
            for (l in languages) {
                val w = k.runUntilInput(wasm("shell", l)!!, "echo  a\tb\n")
                appendLine(
                    "| shell | ${l.label} | ${w.kind} | ${if (w.fuelYield) "できる" else "できない"} | ${w.writtenChunks * 4} KiB | ${w.romChunks * 4} KiB | " +
                        "${w.declaredPages * 64} KiB | `${w.afterInput.trim().replace("\n", "⏎")}` |",
                )
            }
            for (p in listOf("echo", "cat", "ls", "kmem")) {
                for (l in languages) {
                    val args = when (p) {
                        "echo" -> listOf("hello", "kibi")
                        "cat" -> listOf("/d/a.txt")
                        "ls" -> listOf("/d")
                        else -> emptyList()
                    }
                    val r = k.run(wasm(p, l)!!, args, mapOf("a.txt" to "alpha\n".toByteArray(), "b.txt" to "beta\n".toByteArray()))
                    appendLine(
                        "| $p | ${l.label} | ${r.kind} | ${if (r.fuelYield) "できる" else "できない"} | - | - | - | " +
                            "`${r.console.trim().replace("\n", "⏎")}`（${r.state}, ${r.exitCode}） |",
                    )
                }
            }
        }
    }
}
