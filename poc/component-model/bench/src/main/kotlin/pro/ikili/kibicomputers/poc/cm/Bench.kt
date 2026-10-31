package pro.ikili.kibicomputers.poc.cm

import com.fasterxml.jackson.databind.ObjectMapper
import run.endive.cm.corpus.ComponentCorpus
import run.endive.cm.parser.ComponentParser
import run.endive.cm.parser.ComponentValidationException
import run.endive.cm.runtime.ComponentStore
import run.endive.cm.tools.ComponentValidate
import run.endive.cm.tools.ComponentValidateException
import run.endive.cm.types.WasmComponent
import run.endive.cm.wasi.p2.WasiPreview2
import run.endive.compiler.InterpreterFallback
import run.endive.compiler.MachineFactoryCompiler
import run.endive.cm.runtime.ComponentLinker
import pro.ikili.kibicomputers.poc.fuel.FuelInjector
import run.endive.runtime.Instance
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong

// #260 の測定。endive-cm が KiBi Computers の要件を満たせるかを、実際に動かして確かめる。
//
// 1. Java 17 で動くか
// 2. 実際のツールチェーン（Rust wasm32-wasip2）のコンポーネントが動くか、その時間
// 3. 燃料計測: コアインスタンスの Builder を差し替えて命令数を数えられるか・上限で止められるか。
//    #28 で決めた計測コードの挿入がコンポーネントの中で使えるか。ランタイムコンパイラ（#29）で動くか
// 4. 中断: スレッド割り込みで、待機中（wasi:io/poll）のゲストを止められるか
// 5. 状態の取り出し: 実行後にコアインスタンスの線形メモリに手が届くか
// 6. 非同期（p3 の土台になる canon: async lift、task.return、stream、waitable-set）に対応しているか
// 7. 依存の大きさ

private const val SPEC = ".rs.json"

fun main() {
    println("== 1. Java")
    println("java.version=${System.getProperty("java.version")} vendor=${System.getProperty("java.vendor")}")

    println()
    println("== 2. コーパス（Rust wasm32-wasip2）を WasiPreview2 で実行")
    val programs = ComponentCorpus.entries().filter { it.endsWith(SPEC) }.map { it.removeSuffix(SPEC) }
    println("program               size[KiB]  parse[ms]  validate[ms]  run[ms]  result")
    var passed = 0
    for (name in programs) {
        val r = runCorpus(name)
        if (r.ok) passed++
        println(
            "%-20s %10.1f %10.1f %13.1f %8.1f  %s".format(
                name, r.sizeKiB, r.parseMs, r.validateMs, r.runMs, if (r.ok) "ok" else "NG ${r.detail}",
            ),
        )
    }
    println("passed $passed / ${programs.size}")

    // 2 回目以降（JIT が温まった状態）の時間
    val warm = (1..20).map { runCorpus("hello-stdout") }
    println(
        "hello-stdout warm (20 回の中央値): parse=%.2fms run=%.2fms".format(
            warm.map { it.parseMs }.sorted()[10],
            warm.map { it.runMs }.sorted()[10],
        ),
    )

    println()
    println("== 3. 燃料計測（ComponentStore.withCoreInstances + ExecutionListener）")
    val hello = parse(ComponentCorpus.readBytes("hello-stdout.rs.wasm"), validate = false)
    val counted = AtomicLong()
    run(hello, ComponentStore.withCoreInstances { module ->
        Instance.builder(module).withUnsafeExecutionListener { _, _ -> counted.incrementAndGet() }
    })
    println("hello-stdout の実行命令数: ${counted.get()}")
    val limit = counted.get() / 2
    val spent = AtomicLong()
    val stopped = runCatching {
        run(hello, ComponentStore.withCoreInstances { module ->
            Instance.builder(module).withUnsafeExecutionListener { _, _ ->
                if (spent.incrementAndGet() > limit) throw OutOfFuel()
            }
        })
    }
    println("上限 $limit 命令で実行: ${describe(stopped)}")

    // 燃料は #28 で「読み込み時に WASM へ計測コードを挿入する」（A）に決まっている。
    // コンポーネントの中のコアモジュールに挿入して動かせるか
    val injected = rewriteCoreModules(ComponentCorpus.readBytes("hello-stdout.rs.wasm")) {
        FuelInjector.inject(it).bytes
    }
    val injectedRun = runCatching { run(parse(injected, validate = false), ComponentStore()) }
    println("計測コードを挿入したコンポーネント（kibi.fuel の import が増える）: ${describe(injectedRun)}")

    println()
    println("== 3b. ランタイムコンパイラ（#29 の決定）でコアモジュールを動かす")
    val compiledRun = runCatching {
        run(hello, ComponentStore.withCoreInstances { m ->
            Instance.builder(MachineFactoryCompiler.builder(m).withInterpreterFallback(InterpreterFallback.WARN).compileModule())
        })
    }
    println("hello-stdout をコンパイラで実行: ${describe(compiledRun)}")
    val interpMs = median { run(hello, ComponentStore()) }
    println("hello-stdout 実行時間の中央値（インタプリタ）: %.2fms".format(interpMs))

    println()
    println("== 4. 中断（待機中のゲストへのスレッド割り込み）")
    // stdin-echo に、いつまでもデータが来ない stdin を渡して、ゲストを wasi:io の待ちで止めておく
    val echo = parse(ComponentCorpus.readBytes("stdin-echo.rs.wasm"), validate = false)
    var interrupted: Result<Int>? = null
    val worker = Thread {
        interrupted = runCatching { run(echo, ComponentStore(), neverEndingStdin = true) }
    }
    worker.start()
    Thread.sleep(300)
    val t0 = System.nanoTime()
    worker.interrupt()
    worker.join(5_000)
    println(
        "割り込みから停止まで %.1fms: %s".format((System.nanoTime() - t0) / 1e6, interrupted?.let(::describe) ?: "止まらない"),
    )

    println()
    println("== 5. 状態の取り出し（実行後のコアインスタンス）")
    ComponentStore().use { store ->
        run(hello, store, close = false)
        val cores = store.coreInstances()
        val memories = cores.mapNotNull { runCatching { it.memory() }.getOrNull() }.distinct()
        println(
            "コアインスタンス ${cores.size} 個、線形メモリ ${memories.size} 個、合計 ${memories.sumOf { it.pages() }} ページ",
        )
        println("グローバル数: ${cores.map { it.globalCount() }}")
    }

    println()
    println("== 6. 非同期（WASI p3 の土台）: canon の対応状況")
    for ((label, wat) in AsyncProbes.all) {
        val result = runCatching {
            val component = parse(Wat.toWasm(wat), validate = false)
            ComponentStore().use { store ->
                val instance = ComponentLinker.builder().build().instantiate(store, component)
                instance.export("run").apply().toList()
            }
        }
        println("%-28s %s".format(label, describe(result)))
    }

    println()
    println("== 7. 依存の大きさ（実行時クラスパスの jar）")
    val jars = System.getProperty("java.class.path").split(File.pathSeparator).map(::File).filter { it.isFile }
    val groups = linkedMapOf<String, Long>()
    for (jar in jars) {
        val group = when {
            jar.name.startsWith("cm-") || jar.path.contains("endive-cm-") -> "endive-cm: ${jar.name}"
            jar.name.startsWith("netty-") -> "netty (wasi:sockets)"
            jar.name.startsWith("wasm-tools-") -> "run.endive:wasm-tools (validate 用)"
            jar.name.startsWith("runtime-") || jar.name.startsWith("wasm-") ||
                jar.name.startsWith("log-") || jar.name.startsWith("wasi-") -> "run.endive (既存)"
            else -> "other: ${jar.name}"
        }
        groups[group] = (groups[group] ?: 0) + jar.length()
    }
    for ((group, bytes) in groups) println("%-45s %8.1f KiB".format(group, bytes / 1024.0))
}

private class OutOfFuel : RuntimeException("out of fuel")

private class CorpusResult(
    val sizeKiB: Double,
    val parseMs: Double,
    val validateMs: Double,
    val runMs: Double,
    val ok: Boolean,
    val detail: String,
)

private fun runCorpus(name: String): CorpusResult {
    val spec = ObjectMapper().readTree(ComponentCorpus.open(name + SPEC))
    val bytes = ComponentCorpus.readBytes("$name.rs.wasm")

    var t = System.nanoTime()
    val component = parse(bytes, validate = false)
    val parseMs = (System.nanoTime() - t) / 1e6

    t = System.nanoTime()
    val validateOk = runCatching { ComponentValidate.validate(ByteArrayInputStream(bytes), "cm-implements") }
    val validateMs = (System.nanoTime() - t) / 1e6

    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val builder = WasiPreview2.builder()
        .withArguments(listOf(name) + spec.path("args").map { it.asText() })
        .withStdout(stdout)
        .withStderr(stderr)
    for ((k, v) in spec.path("env").properties()) builder.withEnvironment(k, v.asText())
    val root = if (spec.hasNonNull("root")) copyFixture(name) else null
    if (root != null) builder.withDirectory("/", root.toPath())

    t = System.nanoTime()
    val exit = builder.build().use { wasi -> ComponentStore().use { wasi.run(it, component) } }
    val runMs = (System.nanoTime() - t) / 1e6
    root?.deleteRecursively()

    val problems = buildList {
        if (validateOk.isFailure) add("validate: ${validateOk.exceptionOrNull()?.message}")
        if (exit != spec.path("exit_code").asInt(0)) add("exit=$exit")
        if (spec.has("stdout") && spec["stdout"].asText() != stdout.toString(Charsets.UTF_8)) add("stdout")
        if (spec.has("stderr") && spec["stderr"].asText() != stderr.toString(Charsets.UTF_8)) add("stderr")
    }
    return CorpusResult(bytes.size / 1024.0, parseMs, validateMs, runMs, problems.isEmpty(), problems.joinToString())
}

private fun copyFixture(name: String): File {
    val dir = Files.createTempDirectory("cm-poc").toFile()
    val prefix = "$name.rs.root/"
    for (entry in ComponentCorpus.entries().filter { it.startsWith(prefix) }) {
        val target = dir.resolve(entry.removePrefix(prefix))
        target.parentFile.mkdirs()
        target.writeBytes(ComponentCorpus.readBytes(entry))
    }
    return dir
}

private fun parse(bytes: ByteArray, validate: Boolean): WasmComponent =
    ComponentParser.builder()
        .withValidation(validate)
        .withValidator { wasm ->
            try {
                ComponentValidate.validate(wasm, "cm-implements")
            } catch (e: ComponentValidateException) {
                throw ComponentValidationException(e.message, e)
            }
        }
        .build()
        .parse { ByteArrayInputStream(bytes) }

private fun run(
    component: WasmComponent,
    store: ComponentStore,
    close: Boolean = true,
    neverEndingStdin: Boolean = false,
): Int {
    val builder = WasiPreview2.builder().withStdout(ByteArrayOutputStream()).withStderr(ByteArrayOutputStream())
    if (neverEndingStdin) builder.withStdin(NeverEndingInput)
    return builder.build().use { wasi ->
        try {
            wasi.run(store, component)
        } finally {
            if (close) store.close()
        }
    }
}

/** 読むといつまでも待つ入力 */
private object NeverEndingInput : java.io.InputStream() {
    override fun read(): Int {
        Thread.sleep(Long.MAX_VALUE)
        return -1
    }
}

private fun median(block: () -> Unit): Double {
    repeat(5) { block() }
    return (1..15).map {
        val t = System.nanoTime()
        block()
        (System.nanoTime() - t) / 1e6
    }.sorted()[7]
}

private fun describe(result: Result<*>): String =
    result.fold(
        { "最後まで実行（戻り値 $it）" },
        { e ->
            var c: Throwable? = e
            val chain = buildList {
                while (true) {
                    val t = c ?: break
                    add(t.javaClass.simpleName + ": " + t.message?.lineSequence()?.firstOrNull())
                    c = t.cause
                }
            }
            "停止 ← ${chain.joinToString(" ← ")}"
        },
    )
