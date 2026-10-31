package pro.ikili.kibicomputers.poc.playerexec

import pro.ikili.kibicomputers.poc.fuel.Budget
import pro.ikili.kibicomputers.poc.fuel.FuelInjector
import pro.ikili.kibicomputers.poc.fuel.Wasm
import pro.ikili.kibicomputers.poc.fuel.kb
import pro.ikili.kibicomputers.poc.fuel.measure
import pro.ikili.kibicomputers.poc.fuel.ms
import run.endive.compiler.InterpreterFallback
import run.endive.compiler.MachineFactoryCompiler
import run.endive.runtime.Instance
import run.endive.wasm.Parser
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.util.Locale

private val bench = Program.of(Wasm.BENCH)
private val lua = Program.of(Wasm.LUA)

/** 変種の番号。測定ごとに新しい番号を使い、「サーバーが初めて見る WASM」を作る */
private var nextVariant = 1000

private fun fresh(p: Program): Program = p.variant(nextVariant++)

/**
 * 測定の入口。引数なしならすべて測って Markdown の表を出す（`build/bench/results.md` にも書く）。
 * `quick` で回数と台数を減らす（動作確認用）。`child ...` は子の JVM の中での1件の測定。
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    if (args.firstOrNull() == "child") {
        child(args.drop(1))
        return
    }
    val quick = args.firstOrNull() == "quick"
    val out = StringBuilder()
    fun emit(line: String = "") {
        println(line)
        out.append(line).append('\n')
    }
    val rt = ManagementFactory.getRuntimeMXBean()
    emit("## Java ${java.lang.Runtime.version().feature()}（${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")}）")
    emit("")
    emit("- OS: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}、CPU ${java.lang.Runtime.getRuntime().availableProcessors()} スレッド")
    emit("- JVM 引数: ${rt.inputArguments.joinToString(" ")}")
    emit("- C の閾値: 燃料 ${PlayerRuntime.DEFAULT_HOT_FUEL / 1_000_000}M 命令（WASM ごとの合計）。燃料は ${Launch.SLICE / 1_000_000}M ずつ配る")
    emit("")

    loadCost(quick, ::emit)
    firstLaunch(quick, ::emit)
    repeatedLaunch(quick, ::emit)
    reentry(quick, ::emit)
    childScenarios(quick, ::emit)

    val file = File("build/bench/results.md")
    file.parentFile.mkdirs()
    file.writeText(out.toString())
    println("（${file.absolutePath} に書いた）")
}

// 1. 読み込みの費用

private fun loadCost(quick: Boolean, emit: (String) -> Unit) {
    emit("### 1. 読み込みの費用（同じ JVM でウォームアップした後の中央値）")
    emit("")
    emit("| WASM | 大きさ（挿入後） | 挿入 | パース | コンパイル | コンパイル（Endive のキャッシュに当たったとき） | 生成（インタプリタ） | 生成（コンパイル済み） |")
    emit("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    val budget = if (quick) Budget(0, 0, 1) else Budget(1500, 3000, 5)
    for (p in listOf(bench, lua)) {
        val injected = FuelInjector.inject(p.bytes).bytes
        val inject = measure(budget) { FuelInjector.inject(p.bytes) }
        val parse = measure(budget) { Parser.parse(injected) }
        val compile = measure(budget) { compile(injected, null) }
        val cache = MapCache()
        compile(injected, cache)
        val cached = measure(budget) { compile(injected, cache) }
        PlayerRuntime(Policy.INTERPRETER).use { r ->
            val lm = r.load(p)
            val interp = measure(budget) { r.launch(lm, imports(p)) }
            PlayerRuntime(Policy.COMPILER).use { c ->
                val clm = c.load(p)
                c.compileAsync(clm).join()
                val comp = measure(budget) { c.launch(clm, imports(p)) }
                emit("| ${p.name} | ${kb(injected.size)} | ${ms(inject)} | ${ms(parse)} | ${ms(compile)} | ${ms(cached)}（キャッシュ ${kb(cache.bytes.toInt())}） | ${ms(interp)} | ${ms(comp)} |")
            }
        }
    }
    emit("")
}

private fun compile(bytes: ByteArray, cache: MapCache?) {
    val builder = MachineFactoryCompiler.builder(Parser.parse(bytes)).withInterpreterFallback(InterpreterFallback.WARN)
    if (cache != null) builder.withCache(cache)
    val compiled = builder.compileModule()
    // 生成したクラスを実際に読み込ませる
    Instance.builder(compiled).withStart(false).withImportValues(importValues(bytes)).build()
}

/** 生成だけ測るための import（中身は使わない） */
private fun imports(p: Program) = if (p.name.startsWith("lua")) run.endive.wasi.WasiPreview1.builder().build().toHostFunctions().toList() else emptyList()

private fun importValues(bytes: ByteArray): run.endive.runtime.ImportValues {
    val b = run.endive.runtime.ImportValues.builder()
    if (bytes.size > 100_000) b.addFunction(*run.endive.wasi.WasiPreview1.builder().build().toHostFunctions())
    b.addFunction(
        run.endive.runtime.HostFunction(
            FuelInjector.IMPORT_MODULE,
            FuelInjector.IMPORT_NAME,
            run.endive.wasm.types.FunctionType.of(listOf(run.endive.wasm.types.ValType.I64), listOf(run.endive.wasm.types.ValType.I64)),
        ) { _, a -> longArrayOf(a[0] + 1_000_000) },
    )
    return b.build()
}

// 2. 初めての WASM の起動

private class Job(val label: String, val program: Program, val run: PlayerRuntime.(Program) -> RunResult)

private val firstJobs = listOf(
    Job("lua: print('hi')", lua) { runLua(it, Scripts.TINY) },
    Job("lua: fib(18)", lua) { runLua(it, Scripts.SMALL) },
    Job("lua: fib(23)", lua) { runLua(it, Scripts.HEAVY) },
    Job("bench: fib(10)", bench) { runBench(it, "fib", 10) },
    Job("bench: sieve(2,000,000)", bench) { runBench(it, "sieve", 2_000_000) },
)

private fun firstLaunch(quick: Boolean, emit: (String) -> Unit) {
    emit("### 2. 起動から終わるまでの時間（読み込み・コンパイル待ちを含む。中央値）")
    emit("")
    emit("「初めて」は、サーバーが初めて見る WASM（毎回ハッシュの違う変種）。「2回目」は同じ WASM をもう一度起動したとき（B と C はコンパイル済み）。")
    emit("")
    emit("| プログラム | A | B（初めて） | B（2回目） | C（初めて） | C（閾値を超えた後） |")
    emit("| --- | ---: | ---: | ---: | ---: | ---: |")
    val reps = if (quick) 1 else 5
    // ウォームアップ（Endive と JIT）
    for (policy in Policy.entries) {
        PlayerRuntime(policy, hotFuel = 0).use { r -> repeat(if (quick) 1 else 3) { firstJobs.forEach { j -> j.run(r, fresh(j.program)) } } }
    }
    for (j in firstJobs) {
        fun median(f: () -> Long) = (0 until reps).map { f() }.sorted()[reps / 2]
        val a = median { PlayerRuntime(Policy.INTERPRETER).use { r -> j.run(r, fresh(j.program)).nanos } }
        val bFirst = median { PlayerRuntime(Policy.COMPILER).use { r -> j.run(r, fresh(j.program)).nanos } }
        val bAgain = median {
            PlayerRuntime(Policy.COMPILER).use { r ->
                val p = fresh(j.program)
                j.run(r, p)
                j.run(r, p).nanos
            }
        }
        val cFirst = median { PlayerRuntime(Policy.TIERED).use { r -> j.run(r, fresh(j.program)).nanos } }
        val cHot = median {
            PlayerRuntime(Policy.TIERED, hotFuel = 0).use { r ->
                val p = fresh(j.program)
                j.run(r, p)
                r.awaitCompiles()
                j.run(r, p).also { check(it.tier == "コンパイル") }.nanos
            }
        }
        emit("| ${j.label} | ${ms(a)} | ${ms(bFirst)} | ${ms(bAgain)} | ${ms(cFirst)} | ${ms(cHot)} |")
    }
    emit("")
}

// 3. 同じ WASM を続けて起動する

private fun repeatedLaunch(quick: Boolean, emit: (String) -> Unit) {
    emit("### 3. 同じ WASM を続けて起動したときの、1回ずつの時間（初めて見る WASM から）")
    emit("")
    val launches = if (quick) 3 else 6
    emit("| プログラム | 方式 | " + (1..launches).joinToString(" | ") { "${it}回目" } + " | 合計 |")
    emit("| --- | --- |" + " ---: |".repeat(launches + 1))
    for (j in listOf(firstJobs[1], firstJobs[2], firstJobs[0])) {
        for (policy in Policy.entries) {
            PlayerRuntime(policy).use { r ->
                val p = fresh(j.program)
                val results = (1..launches).map {
                    val res = j.run(r, p)
                    // C のコンパイルは別スレッドなので、終わるまで少し待ってから次を起動する（実際には次の起動までに時間がある）
                    Thread.sleep(if (policy == Policy.TIERED) 300 else 0)
                    res
                }
                val cells = results.joinToString(" | ") { "${ms(it.nanos)}${if (it.tier == "インタプリタ" && policy == Policy.TIERED) "（I）" else ""}" }
                emit("| ${j.label} | ${policy.label} | $cells | ${ms(results.sumOf { it.nanos })} |")
            }
        }
    }
    emit("")
    emit("（I）は C でインタプリタで動いた回。")
    emit("")
}

// 4. 1つのインスタンスで export を続けて呼ぶ（常駐して、ホストからイベントごとに呼ばれる形）

private fun reentry(quick: Boolean, emit: (String) -> Unit) {
    emit("### 4. 1つのインスタンスで export を続けて呼んだときの、1回ずつの時間（sieve(2,000,000)。初めて見る WASM から）")
    emit("")
    val calls = if (quick) 3 else 6
    emit("| 方式 | " + (1..calls).joinToString(" | ") { "${it}回目" } + " |")
    emit("| --- |" + " ---: |".repeat(calls))
    for (policy in Policy.entries) {
        PlayerRuntime(policy).use { r ->
            val t0 = System.nanoTime()
            val launch = r.launch(r.load(fresh(bench)))
            val startNanos = System.nanoTime() - t0
            val cells = (1..calls).map {
                val s = System.nanoTime()
                launch.instance.export("sieve").apply(2_000_000)
                launch.finish()
                val n = System.nanoTime() - s + if (it == 1) startNanos else 0
                Thread.sleep(if (policy == Policy.TIERED) 300 else 0)
                "${ms(n)}（${launch.tier.take(1).let { c -> if (c == "イ") "I" else "C" }}）"
            }
            emit("| ${policy.label} | ${cells.joinToString(" | ")} |")
        }
    }
    emit("")
    emit("1回目は読み込み（B はコンパイル待ち）を含む。（I）はインタプリタ、（C）はコンパイルしたコードで動いた回。")
    emit("")
}

// 5. 子の JVM での測定（Metaspace と CPU の総量）

private class Scenario(val key: String, val label: String, val body: (PlayerRuntime, Int) -> Unit)

private val scenarios = listOf(
    Scenario("distinct", "別々の lua.wasm（変種）を N 個、1回ずつ起動（print('hi')）") { r, n -> repeat(n) { r.runLua(lua.variant(it), Scripts.TINY) } },
    Scenario("same", "同じ lua.wasm を N 回起動（print('hi')）") { r, n -> repeat(n) { r.runLua(lua, Scripts.TINY) } },
    Scenario("short", "短いコマンド: 10 個の WASM を N 回ずつ（print('hi')）") { r, n -> repeat(n) { repeat(10) { i -> r.runLua(lua.variant(i), Scripts.TINY) } } },
    Scenario("repeat", "少し計算: N 個の WASM を 5 回ずつ（fib(18)）") { r, n -> repeat(5) { repeat(n) { i -> r.runLua(lua.variant(i), Scripts.SMALL) } } },
    Scenario("heavy", "重い計算: N 個の WASM を 1 回ずつ（fib(23)）") { r, n -> repeat(n) { r.runLua(lua.variant(it), Scripts.HEAVY) } },
)

private class ChildResult(val wallNanos: Long, val cpuNanos: Long, val compileCpuNanos: Long, val jitNanos: Long, val compiles: Int, val metaspace: Long, val metaspaceAfterRelease: Long, val heap: Long) {
    fun encode() = "RESULT $wallNanos,$cpuNanos,$compileCpuNanos,$jitNanos,$compiles,$metaspace,$metaspaceAfterRelease,$heap"

    companion object {
        fun decode(line: String): ChildResult {
            val v = line.removePrefix("RESULT ").split(',').map(String::toLong)
            return ChildResult(v[0], v[1], v[2], v[3], v[4].toInt(), v[5], v[6], v[7])
        }
    }
}

private fun runtimeFor(name: String, cache: MapCache?): PlayerRuntime = when (name) {
    "A" -> PlayerRuntime(Policy.INTERPRETER)
    "B" -> PlayerRuntime(Policy.COMPILER)
    "B-cache" -> PlayerRuntime(Policy.COMPILER, cache = cache)
    "B-noshare" -> PlayerRuntime(Policy.COMPILER, share = false)
    "C" -> PlayerRuntime(Policy.TIERED)
    else -> error(name)
}

private fun child(args: List<String>) {
    val scenario = scenarios.first { it.key == args[0] }
    val n = args[2].toInt()
    // Endive と Lua のクラスの読み込み・JIT の分を外すため、別の WASM で一度動かしておく
    PlayerRuntime(Policy.COMPILER).use { it.runLua(lua.variant(999_999), Scripts.TINY) }
    PlayerRuntime(Policy.INTERPRETER).use { it.runLua(lua.variant(999_998), Scripts.TINY) }
    // B-cache: サーバーの再起動の後を模す。前に同じ WASM をコンパイルしたときの Endive のキャッシュだけが残っている
    val cache = if (args[1] == "B-cache") MapCache().also { c -> PlayerRuntime(Policy.COMPILER, cache = c).use { scenario.body(it, n) } } else null
    val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
    // 先に GC して、ウォームアップで作ったクラスを解放してから測る
    val heap0 = heapAfterGc()
    val meta0 = metaspace()
    val r = runtimeFor(args[1], cache)
    val jit = ManagementFactory.getCompilationMXBean()
    val jit0 = jit.totalCompilationTime
    val cpu0 = os.processCpuTime
    val t0 = System.nanoTime()
    scenario.body(r, n)
    r.awaitCompiles()
    val wall = System.nanoTime() - t0
    val cpu = os.processCpuTime - cpu0
    val jitMillis = jit.totalCompilationTime - jit0
    val heap1 = heapAfterGc()
    val meta1 = metaspace()
    r.unloadAll()
    r.close()
    heapAfterGc()
    val meta2 = metaspace()
    println(ChildResult(wall, cpu, r.stats.compileCpuNanos.get(), jitMillis * 1_000_000, r.stats.compiles.get(), meta1 - meta0, meta2 - meta0, heap1 - heap0).encode())
}

private fun childScenarios(quick: Boolean, emit: (String) -> Unit) {
    emit("### 5. サーバー全体の CPU と Metaspace（1件ごとに新しい JVM。コンピューター1台で順に起動）")
    emit("")
    emit("CPU はプロセス全体（JIT・GC・コンパイルのスレッドを含む）。「うちコンパイル」は Endive のコンパイラのスレッドの CPU 時間、「HotSpot の JIT」は JVM の JIT がコンパイルに使った時間の合計（`CompilationMXBean`。複数のスレッドの合計）。C で閾値を超えて頼んだコンパイルは、終わるまで待って数えた。「解放後」は、WASM の共有をやめて GC した後の Metaspace の増え。B-noshare は同じ WASM でもコンピューターごとに読み込み・コンパイルする。B-cache はサーバーの再起動の後を模したもの（前にコンパイルしたときの Endive のキャッシュ＝生成したクラスのバイト列だけが残っている。ヒープ増にキャッシュの分を含む）。")
    emit("")
    emit("| シナリオ | N | 方式 | 経過時間 | CPU（プロセス） | うちコンパイル | HotSpot の JIT | コンパイル回数 | Metaspace 増 | 解放後 | ヒープ増（読み込んだ WASM） |")
    emit("| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    val runs = buildList {
        val ns = if (quick) listOf(3) else listOf(1, 10, 30)
        for (n in ns) for (p in listOf("A", "B", "C")) add(Triple("distinct", p, n))
        add(Triple("distinct", "B-cache", if (quick) 3 else 10))
        for (p in listOf("B", "B-noshare")) add(Triple("same", p, if (quick) 3 else 10))
        for (s in listOf("short", "repeat", "heavy")) for (p in listOf("A", "B", "C")) add(Triple(s, p, if (quick) 1 else if (s == "short") 10 else 4))
    }
    for ((s, p, n) in runs) {
        val r = ChildResult.decode(childJvm("child", s, p, n.toString()).lines().first { it.startsWith("RESULT ") })
        val label = scenarios.first { it.key == s }.label
        emit("| $label | $n | $p | ${ms(r.wallNanos)} | ${ms(r.cpuNanos)} | ${ms(r.compileCpuNanos)} | ${ms(r.jitNanos)} | ${r.compiles} | ${mb(r.metaspace)} | ${mb(r.metaspaceAfterRelease)} | ${mb(r.heap)} |")
    }
    emit("")
}

private fun mb(bytes: Long): String = "%.1f MB".format(bytes / 1024.0 / 1024.0)

private fun metaspace(): Long = ManagementFactory.getMemoryPoolMXBeans()
    .filter { it.type == MemoryType.NON_HEAP && it.name == "Metaspace" }
    .sumOf { it.usage.used }

private fun heapAfterGc(): Long {
    val runtime = java.lang.Runtime.getRuntime()
    repeat(3) {
        System.gc()
        Thread.sleep(50)
    }
    return runtime.totalMemory() - runtime.freeMemory()
}

private fun childJvm(vararg args: String): String {
    val java = ProcessHandle.current().info().command().orElse("java")
    val jvmArgs = ManagementFactory.getRuntimeMXBean().inputArguments.filter { !it.startsWith("-agentlib") && !it.startsWith("-javaagent") }
    val command = listOf(java) + jvmArgs + listOf("-cp", System.getProperty("java.class.path"), "pro.ikili.kibicomputers.poc.playerexec.MainKt") + args
    val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "子の JVM が失敗した: $command\n$output" }
    return output
}
