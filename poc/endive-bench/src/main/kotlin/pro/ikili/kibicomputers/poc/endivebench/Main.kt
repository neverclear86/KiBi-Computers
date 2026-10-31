package pro.ikili.kibicomputers.poc.endivebench

import java.io.File
import java.lang.management.ManagementFactory
import java.util.Locale
import kotlin.system.exitProcess

/** CPU ベンチマークの1件。[expect] は戻り値（i32 はそのまま、f64 は四捨五入した整数）。 */
class CpuCase(val name: String, val function: String, val args: LongArray, val expect: Long, val f64: Boolean = false)

val cpuCases = listOf(
    CpuCase("fib(27) 再帰", "fib", longArrayOf(27), 196418),
    CpuCase("sieve(2,000,000)", "sieve", longArrayOf(2_000_000), 148933),
    CpuCase("matmul 128x128 (f64)", "matmul", longArrayOf(128), 0, f64 = true),
    CpuCase("crc32 64KB x4", "crc32", longArrayOf(65536, 4), 0),
)

/** CPU ベンチマークを呼んで、結果を確かめた値を返す。 */
fun CpuCase.run(instance: run.endive.runtime.Instance): Long {
    val raw = instance.call(function, *args)
    return if (f64) Math.round(java.lang.Double.longBitsToDouble(raw)) else raw and 0xFFFFFFFFL
}

val luaCases = listOf("fib", "tables", "strings")

/**
 * 測定の入口。
 *
 * - 引数なし: すべて測って Markdown の表を出す（`build/bench/results-java<N>.md` にも書く）
 * - `memory <WASM> <方式>`: 新しい JVM の中で、インスタンス生成の初回の時間とメモリを測る（親から呼ぶ）
 * - `recompile <WASM>`: 新しい JVM の中で、ランタイムコンパイルを繰り返したときの Metaspace を測る（親から呼ぶ）
 * - `quick`: 回数を減らして短く回す（動作確認用）
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    when (args.firstOrNull()) {
        "memory" -> {
            println(MemoryProbe.run(Wasm.valueOf(args[1]), Mode.valueOf(args[2])).encode())
            exitProcess(0)
        }
        "recompile" -> {
            println(MemoryProbe.recompile(Wasm.valueOf(args[1])).encode())
            exitProcess(0)
        }
        else -> runAll(quick = args.firstOrNull() == "quick")
    }
}

private fun runAll(quick: Boolean) {
    val javaVersion = Runtime.version().feature()
    val out = StringBuilder()
    fun emit(line: String = "") {
        println(line)
        out.append(line).append('\n')
    }
    val rt = ManagementFactory.getRuntimeMXBean()
    emit("## Java $javaVersion（${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")}）")
    emit()
    emit("- OS: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}、CPU ${Runtime.getRuntime().availableProcessors()} スレッド")
    emit("- JVM 引数: ${rt.inputArguments.joinToString(" ")}")
    emit("- Redline: ${redlineStatus()}")
    emit()

    val budget = if (quick) Budget(warmupMillis = 200, measureMillis = 300, minRuns = 2) else Budget(warmupMillis = 3000, measureMillis = 3000, minRuns = 5)

    // 1. 準備とインスタンス生成（初回は新しい JVM で測る）
    emit("### 準備・インスタンス生成とメモリ（それぞれ新しい JVM で測定）")
    emit()
    emit("| WASM | 方式 | 準備（初回） | 生成（初回） | 生成（2回目以降） | Metaspace 増 | ヒープ増（準備） | ヒープ増（1インスタンス） |")
    emit("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |")
    for (wasm in Wasm.entries) {
        for (mode in Mode.entries) {
            if (mode == Mode.REDLINE && wasm != Wasm.BENCH) {
                emit("| ${wasm.name.lowercase()} | ${mode.label} | 不可（例外処理に未対応） | | | | | |")
                continue
            }
            val p = MemoryProbe.inChildJvm(wasm, mode)
            emit(
                "| ${wasm.name.lowercase()} | ${mode.label} | ${ms(p.prepareNanos)} | ${ms(p.firstInstanceNanos)} | ${ms(p.warmInstanceNanos)} | " +
                    "${mb(p.metaspaceBytes)} | ${mb(p.prepareHeapBytes)} | ${mb(p.perInstanceHeapBytes)} |",
            )
        }
    }
    emit()
    emit("ランタイムコンパイラで同じモジュールを毎回コンパイルし直したとき（参照を捨てて GC した後）:")
    emit()
    for (wasm in Wasm.entries) {
        val r = MemoryProbe.recompileInChildJvm(wasm)
        emit("- ${wasm.name.lowercase()}: 1回あたり ${ms(r.compileNanos)}、Metaspace は 10 回で +${mb(r.metaspaceAfter10)}、GC 後 +${mb(r.metaspaceAfterGc)}")
    }
    emit()

    // 2. CPU ベンチマーク（同じインスタンスで繰り返し呼ぶ）
    emit("### CPU ベンチマーク（1回あたりの中央値。カッコ内はインタプリタ比）")
    emit()
    val cpuModes = Mode.entries.filter { Wasm.BENCH.supports(it) }
    emit("| ベンチマーク | " + cpuModes.joinToString(" | ") { it.label } + " |")
    emit("| --- |" + cpuModes.joinToString("") { " ---: |" })
    val engines = cpuModes.associateWith { Engine(Wasm.BENCH, it).newInstance() }
    for (case in cpuCases) {
        val times = cpuModes.map { mode ->
            val instance = engines.getValue(mode)
            measure(budget) {
                val v = case.run(instance)
                check(case.expect == 0L || v == case.expect) { "${case.name} on $mode: $v != ${case.expect}" }
            }
        }
        val base = times[0]
        emit("| ${case.name} | " + times.joinToString(" | ") { "${ms(it)}（${ratio(base, it)}）" } + " |")
    }
    emit()

    // 3. Lua（毎回インスタンスを作って `lua -` を最後まで動かす）
    emit("### Lua（lua.wasm、毎回インスタンスを作って最後まで実行。1回あたりの中央値）")
    emit()
    val luaModes = Mode.entries.filter { Wasm.LUA.supports(it) }
    emit("| スクリプト | " + luaModes.joinToString(" | ") { it.label } + " |")
    emit("| --- |" + luaModes.joinToString("") { " ---: |" })
    val luaEngines = luaModes.associateWith { Engine(Wasm.LUA, it) }
    val scripts = listOf("（空のスクリプト）" to "") + luaCases.map { "$it.lua" to luaScript(it) }
    for ((name, script) in scripts) {
        var expected: String? = null
        val times = luaModes.map { mode ->
            val engine = luaEngines.getValue(mode)
            measure(budget) {
                val stdout = engine.runLua(script)
                if (expected == null) expected = stdout else check(stdout == expected) { "$name on $mode: $stdout != $expected" }
            }
        }
        val base = times[0]
        emit("| $name | " + times.joinToString(" | ") { "${ms(it)}（${ratio(base, it)}）" } + " |")
    }
    emit()

    // 4. 終わらないループを Thread.interrupt() で止められるか（実行時間の制限の前提）。
    // 止まらないスレッドが残るので最後に測る
    emit("### 割り込み")
    emit()
    emit("終わらないループ（`spin`）を `Thread.interrupt()` で止めるまでの時間（2 秒待って止まらなければ「止まらない」）:")
    emit()
    for (mode in cpuModes) {
        val nanos = interruptLatency(engines.getValue(mode))
        emit("- ${mode.label}: ${if (nanos == null) "止まらない" else ms(nanos)}")
    }
    emit()

    val file = File("build/bench/results-java$javaVersion.md")
    file.parentFile.mkdirs()
    file.writeText(out.toString())
    println("結果を ${file.absolutePath} に書いた")
}

private fun redlineStatus(): String {
    val provider = pro.ikili.kibicomputers.poc.endivebench.aot.Bench.nativeProvider()
    return if (provider.isPresent) "ネイティブで実行（${provider.get().javaClass.simpleName}）" else "このプラットフォームでは使えない（バイトコードに戻る）"
}

/**
 * 別のスレッドで `spin` を呼び、100 ms 後に割り込む。止まるまでのナノ秒を返す（2 秒で止まらなければ null）。
 * 止まらなかったスレッドはデーモンなので、プロセスの終了を妨げない。
 */
fun interruptLatency(instance: run.endive.runtime.Instance): Long? {
    val stopped = java.util.concurrent.CountDownLatch(1)
    val thread = Thread {
        try {
            instance.call("spin")
        } catch (_: Throwable) {
            // 割り込みで止まると例外になる
        } finally {
            stopped.countDown()
        }
    }
    thread.isDaemon = true
    thread.start()
    Thread.sleep(100)
    val t0 = System.nanoTime()
    thread.interrupt()
    return if (stopped.await(2, java.util.concurrent.TimeUnit.SECONDS)) System.nanoTime() - t0 else null
}

class Budget(val warmupMillis: Long, val measureMillis: Long, val minRuns: Int)

/** [warmupMillis] 回し続けてから、[measureMillis] かつ [minRuns] 回以上測り、中央値（ナノ秒）を返す。 */
fun measure(budget: Budget, body: () -> Unit): Long {
    val warmEnd = System.nanoTime() + budget.warmupMillis * 1_000_000
    var warmRuns = 0
    while (warmRuns < 2 || System.nanoTime() < warmEnd) {
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

fun mb(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 0 -> "%.0f KB".format(bytes / 1024.0)
    else -> "~0"
}

fun ratio(base: Long, value: Long): String = if (value == base) "1x" else "%.1fx".format(base.toDouble() / value)
