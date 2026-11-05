package pro.ikili.kibicomputers.poc.oslanguage

import java.io.File

/** 測って表を出す（`build/bench/results.md` にも書く）。引数 `quick` で回数を減らす */
fun main(args: Array<String>) {
    val rounds = if ("quick" in args) 3 else 21
    val table = Bench.run(rounds)
    println(table)
    File("build/bench").mkdirs()
    File("build/bench/results.md").writeText(table)
}
