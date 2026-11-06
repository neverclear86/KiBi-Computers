package pro.ikili.kibicomputers.poc.oslanguage

import java.io.File

/**
 * 測って表を出す（`build/bench/results.md` にも書く）。引数 `quick` で回数を減らす。
 * 引数 `profile` なら、関数ごとの燃料の内訳を出す（[Profiler]。`build/bench/profile.md`）
 */
fun main(args: Array<String>) {
    File("build/bench").mkdirs()
    if ("profile" in args) {
        // `profile <ディレクトリ>` なら、そのディレクトリの WASM（CI の成果物の experiments）を比べる
        val dir = args.getOrNull(args.indexOf("profile") + 1)?.let(::File)
        val table = Profiler.run(if (dir != null) Profiler.directoryVariants(dir) else Profiler.defaultVariants())
        println(table)
        File("build/bench/profile.md").writeText(table)
        return
    }
    val rounds = if ("quick" in args) 3 else 21
    val table = Bench.run(rounds)
    println(table)
    File("build/bench/results.md").writeText(table)
}
