package pro.ikili.kibicomputers.poc.fuel

import run.endive.compiler.InterpreterFallback
import run.endive.compiler.MachineFactoryCompiler
import run.endive.wasm.Parser
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** CPU ベンチマークの1件（#26 と同じ）。 */
class CpuCase(val name: String, val function: String, val args: LongArray)

val cpuCases = listOf(
    CpuCase("fib(27) 再帰", "fib", longArrayOf(27)),
    CpuCase("sieve(2,000,000)", "sieve", longArrayOf(2_000_000)),
    CpuCase("matmul 128x128 (f64)", "matmul", longArrayOf(128)),
    CpuCase("crc32 64KB x4", "crc32", longArrayOf(65536, 4)),
)

val luaCases = listOf("fib", "tables", "strings")

private const val UNLIMITED = Long.MAX_VALUE / 2

/** 比べる組み合わせ。B はコンパイルした実行では数えられない（数は 0 のまま）ので、速度の表には入れない */
val combos = listOf(
    Runtime.INTERPRETER to Metering.NONE,
    Runtime.INTERPRETER to Metering.INJECTED_GLOBAL,
    Runtime.INTERPRETER to Metering.INJECTED,
    Runtime.INTERPRETER to Metering.LISTENER,
    Runtime.COMPILER to Metering.NONE,
    Runtime.COMPILER to Metering.INJECTED_GLOBAL,
    Runtime.COMPILER to Metering.INJECTED,
)

/**
 * 測定の入口。引数なしならすべて測って Markdown の表を出す（`build/bench/results.md` にも書く）。
 * `quick` で回数を減らす（動作確認用）。
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val quick = args.firstOrNull() == "quick"
    val out = StringBuilder()
    fun emit(line: String = "") {
        println(line)
        out.append(line).append('\n')
    }
    val rt = ManagementFactory.getRuntimeMXBean()
    emit("## Java ${java.lang.Runtime.version().feature()}（${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")}）")
    emit()
    emit("- OS: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}、CPU ${java.lang.Runtime.getRuntime().availableProcessors()} スレッド")
    emit("- JVM 引数: ${rt.inputArguments.joinToString(" ")}")
    emit()

    val budget = if (quick) Budget(100, 200, 1) else Budget(1500, 3000, 3)

    // 1. 挿入の費用
    emit("### 1. 挿入（方式 A）の費用")
    emit()
    emit("| WASM | 残りの置き場所 | 大きさ（前 → 後） | 挿入の時間 | 引く箇所 | 確かめる箇所 | 確かめる間の最大命令数 | ランタイムコンパイルの時間（前 → 後） |")
    emit("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |")
    for (wasm in Wasm.entries) for (storage in FuelInjector.Storage.entries) {
        val injectNanos = measure(budget) { FuelInjector.inject(wasm.bytes, storage) }
        val r = FuelInjector.inject(wasm.bytes, storage)
        val compileBefore = measure(Budget(0, 0, if (quick) 1 else 3)) { compile(wasm.bytes) }
        val compileAfter = measure(Budget(0, 0, if (quick) 1 else 3)) { compile(r.bytes) }
        emit(
            "| ${wasm.name.lowercase()} | ${if (storage == FuelInjector.Storage.LOCAL) "ローカル" else "グローバル"} | ${kb(wasm.bytes.size)} → ${kb(r.bytes.size)}（+${pct(r.bytes.size.toDouble() / wasm.bytes.size - 1)}） | ${ms(injectNanos)} | " +
                "${r.charges} | ${r.checks} | ${r.maxUnchecked} | ${ms(compileBefore)} → ${ms(compileAfter)} |",
        )
    }
    emit()

    // 2. 速度（燃料は十分に配る）
    emit("### 2. 実行時間（1回あたりの中央値。カッコ内は同じランタイムで計測なしとの比）")
    emit()
    emit("| ベンチマーク | " + combos.joinToString(" | ") { "${it.first.label} / ${it.second.label}" } + " |")
    emit("| --- |" + combos.joinToString("") { " ---: |" })
    val engines = Wasm.entries.associateWith { wasm -> combos.associateWith { (r, m) -> Engine(wasm, r, m) } }
    val used = LinkedHashMap<String, MutableMap<Pair<Runtime, Metering>, Long>>()
    for (case in cpuCases) {
        val results = combos.map { combo ->
            val engine = engines.getValue(Wasm.BENCH).getValue(combo)
            val meter = FuelMeter()
            val instance = engine.newInstance(meter)
            var value = 0L
            val nanos = measure(budget) {
                engine.restart(instance, meter, UNLIMITED)
                value = instance.call(case.function, *case.args)
                used.getOrPut(case.name) { LinkedHashMap() }[combo] = engine.used(instance, meter)
            }
            Triple(combo, nanos, value)
        }
        check(results.map { it.third }.distinct().size == 1) { "${case.name}: 結果が方式で違う $results" }
        emit("| ${case.name} | " + results.joinToString(" | ") { (combo, nanos, _) -> "${ms(nanos)}（${ratio(nanos, base(results, combo.first))}）" } + " |")
    }
    val scripts = listOf("（空のスクリプト）" to "") + luaCases.map { "$it.lua" to luaScript(it) }
    for ((name, script) in scripts) {
        val results = combos.map { combo ->
            val engine = engines.getValue(Wasm.LUA).getValue(combo)
            var stdout = ""
            val nanos = measure(budget) {
                val meter = FuelMeter().also { it.start(UNLIMITED) }
                val (o, u) = engine.runLua(script, meter)
                stdout = o
                used.getOrPut("lua $name") { LinkedHashMap() }[combo] = u
            }
            Triple(combo, nanos, stdout)
        }
        check(results.map { it.third }.distinct().size == 1) { "$name: 出力が方式で違う" }
        emit("| lua $name | " + results.joinToString(" | ") { (combo, nanos, _) -> "${ms(nanos)}（${ratio(nanos, base(results, combo.first))}）" } + " |")
    }
    emit()

    // 3. 数えた燃料（ランタイムを替えても同じか）
    emit("### 3. 数えた燃料（命令数）")
    emit()
    val bCompiler = Engine(Wasm.BENCH, Runtime.COMPILER, Metering.LISTENER)
    emit("| ベンチマーク | A（ローカル）/ インタプリタ | A（ローカル）/ ランタイムコンパイラ | A（グローバル）/ 両方 | B / インタプリタ | B / ランタイムコンパイラ | A ÷ B |")
    emit("| --- | ---: | ---: | ---: | ---: | ---: | ---: |")
    for ((name, m) in used) {
        val a1 = m.getValue(Runtime.INTERPRETER to Metering.INJECTED)
        val a2 = m.getValue(Runtime.COMPILER to Metering.INJECTED)
        val g1 = m.getValue(Runtime.INTERPRETER to Metering.INJECTED_GLOBAL)
        val g2 = m.getValue(Runtime.COMPILER to Metering.INJECTED_GLOBAL)
        val b1 = m.getValue(Runtime.INTERPRETER to Metering.LISTENER)
        val b2 = cpuCases.find { it.name == name }?.let { c ->
            val meter = FuelMeter().also { it.start(UNLIMITED) }
            val instance = bCompiler.newInstance(meter)
            instance.call(c.function, *c.args)
            bCompiler.used(instance, meter).toString()
        } ?: "（同じく 0）"
        emit("| $name | $a1 | $a2${if (a1 == a2) "（同じ）" else "（違う）"} | ${if (g1 == g2) "$g1" else "$g1 / $g2（違う）"} | $b1 | $b2 | ${"%.4f".format(a1.toDouble() / b1)} |")
    }
    emit()

    // 4. 燃料切れでの中断と再開
    emit("### 4. 燃料切れでの中断と再開（fib(27)）")
    emit()
    emit("燃料を小分けに配り、切れるたびにホストの関数（A は `kibi.fuel`、B はリスナー）が次の分を返す。「止める」は、別スレッドで動かし、燃料切れのたびにスレッドを止めて、メインスレッドが 1 ms ごとに次の分を配る（#27 の方式 B と同じ形）。")
    emit()
    emit("| 方式 | 1回に配る燃料 | 燃料切れの回数 | 行き過ぎの最大 | 実行時間 | 合計の燃料（小分けにしないときと同じか） |")
    emit("| --- | ---: | ---: | ---: | ---: | --- |")
    val fib = cpuCases.first()
    for (combo in listOf(Runtime.INTERPRETER to Metering.INJECTED, Runtime.INTERPRETER to Metering.LISTENER, Runtime.COMPILER to Metering.INJECTED_GLOBAL, Runtime.COMPILER to Metering.INJECTED)) {
        val engine = engines.getValue(Wasm.BENCH).getValue(combo)
        val total = used.getValue(fib.name).getValue(combo)
        for (slice in listOf(1_000_000L, 10_000L, 100L)) {
            val meter = FuelMeter { it + slice }
            val instance = engine.newInstance(meter.also { it.start(slice) })
            val nanos = measure(budget) {
                engine.restart(instance, meter, slice)
                instance.call(fib.function, *fib.args)
            }
            val u = engine.used(instance, meter)
            emit("| ${combo.first.label} / ${combo.second.label} | $slice | ${meter.exhaustions} | ${meter.maxOvershoot} | ${ms(nanos)} | $u（${if (u == total) "同じ" else "違う"}） |")
        }
        // スレッドを止めて再開する
        val slice = 1_000_000L
        val ticks = Semaphore(0)
        val meter = FuelMeter {
            check(ticks.tryAcquire(10, TimeUnit.SECONDS))
            it + slice
        }
        val instance = engine.newInstance(meter.also { it.start(slice) })
        val t0 = System.nanoTime()
        val worker = Thread { instance.call(fib.function, *fib.args) }
        worker.start()
        while (worker.isAlive) {
            ticks.release()
            worker.join(1)
        }
        val u = engine.used(instance, meter)
        emit("| ${combo.first.label} / ${combo.second.label}（止める） | $slice | ${meter.exhaustions} | ${meter.maxOvershoot} | ${ms(System.nanoTime() - t0)}（1 ms ごとに配る） | $u（${if (u == total) "同じ" else "違う"}） |")
    }
    emit()

    val file = File("build/bench/results.md")
    file.parentFile.mkdirs()
    file.writeText(out.toString())
    println("結果を ${file.absolutePath} に書いた")
}

private fun base(results: List<Triple<Pair<Runtime, Metering>, Long, *>>, runtime: Runtime): Long =
    results.first { it.first == (runtime to Metering.NONE) }.second

private fun compile(bytes: ByteArray) {
    MachineFactoryCompiler.builder(Parser.parse(bytes)).withInterpreterFallback(InterpreterFallback.FAIL).compileModule()
}

class Budget(val warmupMillis: Long, val measureMillis: Long, val minRuns: Int)

/** [Budget.warmupMillis] 回し続けてから、[Budget.measureMillis] かつ [Budget.minRuns] 回以上測り、中央値（ナノ秒）を返す。 */
fun measure(budget: Budget, body: () -> Unit): Long {
    val warmEnd = System.nanoTime() + budget.warmupMillis * 1_000_000
    var warmRuns = 0
    while (warmRuns < 1 || System.nanoTime() < warmEnd) {
        body()
        warmRuns++
    }
    val samples = ArrayList<Long>()
    val end = System.nanoTime() + budget.measureMillis * 1_000_000
    while (samples.size < budget.minRuns || System.nanoTime() < end) {
        val t0 = System.nanoTime()
        body()
        samples += System.nanoTime() - t0
    }
    samples.sort()
    return samples[samples.size / 2]
}

fun ms(nanos: Long): String = when {
    nanos >= 100_000_000 -> "%.0f ms".format(nanos / 1e6)
    nanos >= 1_000_000 -> "%.1f ms".format(nanos / 1e6)
    else -> "%.0f µs".format(nanos / 1e3)
}

fun kb(bytes: Int): String = "%.0f KB".format(bytes / 1024.0)

fun pct(x: Double): String = "%.0f%%".format(x * 100)

/** 基準に対して何倍遅いか */
fun ratio(value: Long, base: Long): String = if (value == base) "1x" else "%.2fx".format(value.toDouble() / base)
