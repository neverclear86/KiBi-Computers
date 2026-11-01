package pro.ikili.kibicomputers.poc.threadlesswait

import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.management.ObjectName

/**
 * #316 の測定。`./gradlew :poc:threadless-wait:run`（`-Pbench.args=quick` で台数と回数を減らす）。
 *
 * 1. 変換（方式 A）の大きさと時間
 * 2. 変換による速度の差（待たない計算）
 * 3. 1回の待ち（入力を渡して次の待ちに戻るまで）の時間
 * 4. N 台が入力待ちのときのスレッド・メモリ・tick の負荷と、全台を起こす時間（方式 A・B・C）
 */
fun main(args: Array<String>) {
    val quick = "quick" in args
    val out = StringBuilder()
    fun line(s: String = "") {
        println(s)
        out.append(s).append('\n')
    }
    val rt = Runtime.getRuntime()
    line("# #316 測定結果")
    line("")
    line("- Java ${System.getProperty("java.version")} / ${System.getProperty("os.name")} ${System.getProperty("os.arch")} / ${rt.availableProcessors()} スレッド / ヒープ ${rt.maxMemory() shr 20} MiB")
    line("- 仮想スレッド: ${if (ThreadKind.virtualAvailable) "使える" else "使えない"}")
    line("")

    transformSection(::line)
    speedSection(::line, quick)
    waitCycleSection(::line, quick)
    val n = if (quick) 200 else 1000
    for (program in WasmProgram.entries) scaleSection(::line, program, n, quick)

    val file = File("build/bench/results.md")
    file.parentFile.mkdirs()
    file.writeText(out.toString())
    println("書き出し: ${file.absolutePath}")
}

private fun median(xs: List<Double>): Double = xs.sorted()[xs.size / 2]

private fun timeMs(block: () -> Unit): Double {
    val t = System.nanoTime()
    block()
    return (System.nanoTime() - t) / 1e6
}

private fun transformSection(line: (String) -> Unit) {
    line("## 1. 変換（方式 A）")
    line("")
    line("| プログラム | 関数 | 変換した関数 | 止まりうる呼び出し | コード（前→後） | WASM 全体（前→後） | 変換の時間 | 退避するローカルの最大 | コンパイル（前→後） |")
    line("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    for (p in WasmProgram.entries) {
        val r = p.asyncified
        repeat(3) { Asyncify.transform(p.bytes, P1Kernel.ASYNC_IMPORTS) }
        val t = median((0 until 7).map { timeMs { Asyncify.transform(p.bytes, P1Kernel.ASYNC_IMPORTS) } })
        val cOrig = timeMs { LoadedModule(p.bytes, Mode.RUNTIME_COMPILER) }
        val cAsync = timeMs { LoadedModule(r.bytes, Mode.RUNTIME_COMPILER) }
        line(
            "| ${p.name.lowercase()} | ${r.functions} | ${r.instrumented} | ${r.sites} | " +
                "${r.codeBefore / 1024} → ${r.codeAfter / 1024} KiB（+${"%.0f".format((r.codeAfter - r.codeBefore) * 100.0 / r.codeBefore)}%） | " +
                "${p.bytes.size / 1024} → ${r.bytes.size / 1024} KiB | ${"%.1f".format(t)} ms | ${r.maxLocals} | " +
                "${"%.0f".format(cOrig)} → ${"%.0f".format(cAsync)} ms |",
        )
    }
    line("")
}

/** 待たずに最後まで動かす（標準入力は閉じておく）。経過時間（ms） */
private fun runOnce(program: WasmProgram, async: Boolean, mode: Mode, args: List<String>): Pair<Double, String> {
    return if (async) {
        val p = AsyncProcess(program, mode, args)
        p.kernel.closeStdin()
        val t = timeMs { check(p.step() == ProcState.EXITED) }
        t to p.kernel.stdoutText()
    } else {
        val k = P1Kernel(args, WaitStyle.BLOCK)
        k.closeStdin()
        val inst = LoadedModule.of(program, false, mode).instantiate(k)
        val t = timeMs {
            try {
                inst.export("_start").apply()
            } catch (e: Throwable) {
                if (generateSequence(e) { it.cause }.none { it is ProcExit }) throw e
            }
        }
        t to k.stdoutText()
    }
}

private fun speedSection(line: (String) -> Unit, quick: Boolean) {
    line("## 2. 変換による速度の差（待たない計算）")
    line("")
    line("中央値。`prompt bench` は関数ポインタ経由の呼び出しと qsort（どちらも変換の対象）。Lua はフィボナッチと文字列・テーブルの処理（`luaV_execute` などが変換の対象）。")
    line("")
    line("| プログラム | 実行方式 | 変換なし | 変換あり | 比 |")
    line("| --- | --- | ---: | ---: | ---: |")
    val luaScript = "local function fib(n) if n < 2 then return n end return fib(n-1) + fib(n-2) end " +
        "local t = {} for i = 1, %n do t[#t+1] = string.format('%d:%s', i, tostring(fib(10))) end " +
        "print(fib(%d), #table.concat(t, ','))"
    for (mode in Mode.entries) {
        val interp = mode == Mode.INTERPRETER
        val cases = listOf(
            "prompt bench" to Pair(WasmProgram.PROMPT, listOf("prompt", "bench", if (interp) "300000" else "3000000")),
            "lua" to Pair(WasmProgram.LUA, listOf("lua", "-e", luaScript.replace("%d)", "${if (interp) 18 else 25})").replace("%n", if (interp) "500" else "20000"))),
        )
        for ((name, c) in cases) {
            val (program, args) = c
            val reps = if (quick) 2 else 5
            val (_, o1) = runOnce(program, false, mode, args)
            val (_, o2) = runOnce(program, true, mode, args)
            check(o1 == o2) { "出力が違う: $o1 / $o2" }
            val a = median((0 until reps).map { runOnce(program, false, mode, args).first })
            val b = median((0 until reps).map { runOnce(program, true, mode, args).first })
            line("| $name | ${mode.label} | ${"%.0f".format(a)} ms | ${"%.0f".format(b)} ms | ${"%.2f".format(b / a)} |")
        }
    }
    line("")
}

private fun waitCycleSection(line: (String) -> Unit, quick: Boolean) {
    line("## 3. 1回の待ちの時間（1台。1行渡して次の待ちに戻るまで）")
    line("")
    line("C はスレッドの中で待つ（渡すスレッドと動かすスレッドが別）。A は渡したスレッドがそのまま再開する。退避は待ちに入ったときの値の数 × 8 バイト。")
    line("")
    line("| プログラム | 実行方式 | C（ブロッキング） | A（変換） | A の退避 |")
    line("| --- | --- | ---: | ---: | ---: |")
    val iters = if (quick) 300 else 2000
    val cases = listOf(
        "prompt" to Pair(WasmProgram.PROMPT, listOf("prompt")),
        "prompt deep 100" to Pair(WasmProgram.PROMPT, listOf("prompt", "deep", "100")),
        "lua -i" to Pair(WasmProgram.LUA, listOf("lua", "-i")),
    )
    val input = { p: WasmProgram -> if (p == WasmProgram.LUA) "x = (x or 0) + 1\n" else "hello\n" }
    for (mode in Mode.entries) {
        for ((name, c) in cases) {
            val (program, args) = c
            // C
            val bp = BlockingProcess(program, mode, args, ThreadKind.PLATFORM)
            val sem = java.util.concurrent.Semaphore(0)
            bp.kernel.onWait = { sem.release() }
            bp.start()
            sem.acquire()
            repeat(iters / 4) {
                bp.feed(input(program))
                sem.acquire()
            }
            val tc = timeMs {
                repeat(iters) {
                    bp.feed(input(program))
                    sem.acquire()
                }
            } / iters
            bp.kernel.closeStdin()
            // A
            val ap = AsyncProcess(program, mode, args)
            ap.step()
            var frames = 0
            ap.kernel.onWait = { frames = maxOf(frames, ap.kernel.frameTop) }
            repeat(iters / 4) { ap.feed(input(program)) }
            val ta = timeMs { repeat(iters) { ap.feed(input(program)) } } / iters
            line("| $name | ${mode.label} | ${"%.1f".format(tc * 1000)} µs | ${"%.1f".format(ta * 1000)} µs | ${frames * 8} B |")
        }
    }
    line("")
}

private fun threadStacksCommittedKb(): Long {
    return try {
        val r = ManagementFactory.getPlatformMBeanServer().invoke(
            ObjectName("com.sun.management:type=DiagnosticCommand"),
            "vmNativeMemory",
            arrayOf<Any>(arrayOf("summary")),
            arrayOf(Array<String>::class.java.name),
        ) as String
        val m = Regex("""-\s+Thread \(reserved=(\d+)KB, committed=(\d+)KB\)""").find(r) ?: return -1
        m.groupValues[2].toLong()
    } catch (e: Exception) {
        -1
    }
}

private fun heapUsedMb(): Long {
    repeat(3) { System.gc() }
    Thread.sleep(200)
    val rt = Runtime.getRuntime()
    return (rt.totalMemory() - rt.freeMemory()) shr 20
}

/** ごみを JIT に消されないよう、逃がす先 */
@Volatile private var garbage: Array<ByteArray?> = arrayOfNulls(16)

private class TickStats(val mspt: Double, val msptMax: Double, val gcMs: Long, val gcCount: Long)

/** 模擬サーバー: 20 TPS で、1 tick に 5 ms の計算と 32 MiB のごみ（若い世代の GC を起こす）を [seconds] 秒 */
private fun simulateTicks(seconds: Int): TickStats {
    val gcs = ManagementFactory.getGarbageCollectorMXBeans()
    val gc0 = gcs.sumOf { it.collectionTime }
    val gcN0 = gcs.sumOf { it.collectionCount }
    val times = ArrayList<Double>()
    var sink = 0L
    val ticks = seconds * 20
    var next = System.nanoTime()
    repeat(ticks) {
        val t0 = System.nanoTime()
        val until = t0 + 5_000_000
        while (System.nanoTime() < until) sink += sink xor System.nanoTime()
        repeat(512) { garbage[(it + ticks) and 15] = ByteArray(64 * 1024) }
        times += (System.nanoTime() - t0) / 1e6
        next += 50_000_000
        val sleep = next - System.nanoTime()
        if (sleep > 0) Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt())
    }
    if (sink == 42L) println()
    return TickStats(times.average(), times.max(), gcs.sumOf { it.collectionTime } - gc0, gcs.sumOf { it.collectionCount } - gcN0)
}

private fun scaleSection(line: (String) -> Unit, program: WasmProgram, n: Int, quick: Boolean) {
    val mode = Mode.RUNTIME_COMPILER
    val args = if (program == WasmProgram.LUA) listOf("lua", "-i") else listOf("prompt")
    val input = if (program == WasmProgram.LUA) "x = (x or 0) + 1\n" else "hello\n"
    line("## 4. ${n} 台が入力待ち（${program.name.lowercase()}、${mode.label}）")
    line("")
    line("差は、何も動かしていないときからの増分。tick は模擬サーバー（20 TPS、1 tick に 5 ms の計算と 32 MiB のごみ）を ${if (quick) 2 else 5} 秒。起こす時間は、全台に1行渡して全台が次の待ちに戻るまで。")
    line("A の再開は固定のワーカー（CPU のスレッド数の半分）で行う。A のスレッドの増分はこのワーカー（台数によらない）。B の増分は仮想スレッドを載せるキャリア（ForkJoinPool。CPU のスレッド数まで）")
    line("")
    line("| 方式 | 待ちに入るまで | スレッド | スレッドのスタック（確保済み） | ヒープ | MSPT 平均 / 最大 | GC（回数 / 合計） | 全台を起こす |")
    line("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    val threads = ManagementFactory.getThreadMXBean()
    val seconds = if (quick) 2 else 5
    LoadedModule.of(program, false, mode)
    LoadedModule.of(program, true, mode)
    val baseHeap = heapUsedMb()
    val baseThreads = threads.threadCount
    val baseStack = threadStacksCommittedKb()
    val base = simulateTicks(seconds)
    line("| （なし） | - | $baseThreads | ${baseStack / 1024} MiB | $baseHeap MiB | ${"%.1f".format(base.mspt)} / ${"%.1f".format(base.msptMax)} ms | ${base.gcCount} / ${base.gcMs} ms | - |")

    val methods = buildList {
        add("C: プラットフォームスレッド" to ThreadKind.PLATFORM)
        if (ThreadKind.virtualAvailable) add("B: 仮想スレッド" to ThreadKind.VIRTUAL)
        add("A: 変換（スレッドなし）" to null)
    }
    for ((label, kind) in methods) {
        val latch = CountDownLatch(n)
        val pool = Executors.newFixedThreadPool(maxOf(1, Runtime.getRuntime().availableProcessors() / 2))
        val blocking = ArrayList<BlockingProcess>()
        val async = ArrayList<AsyncProcess>()
        val tStart = timeMs {
            if (kind != null) {
                repeat(n) {
                    val p = BlockingProcess(program, mode, args, kind)
                    p.kernel.onWait = { latch.countDown() }
                    blocking += p
                    p.start()
                }
            } else {
                repeat(n) {
                    val p = AsyncProcess(program, mode, args)
                    p.kernel.onWait = { latch.countDown() }
                    async += p
                    pool.execute { p.step() }
                }
            }
            check(latch.await(10, TimeUnit.MINUTES))
        }
        val heap = heapUsedMb() - baseHeap
        val th = threads.threadCount - baseThreads
        val stack = threadStacksCommittedKb() - baseStack
        val ticks = simulateTicks(seconds)
        val wake = CountDownLatch(n)
        blocking.forEach { p -> p.kernel.onWait = { wake.countDown() } }
        async.forEach { p -> p.kernel.onWait = { wake.countDown() } }
        val tWake = timeMs {
            blocking.forEach { it.feed(input) }
            async.forEach { p -> pool.execute { p.feed(input) } }
            check(wake.await(10, TimeUnit.MINUTES))
        }
        line(
            "| $label | ${"%.0f".format(tStart)} ms | +$th | +${stack / 1024} MiB | +$heap MiB | " +
                "${"%.1f".format(ticks.mspt)} / ${"%.1f".format(ticks.msptMax)} ms | ${ticks.gcCount} / ${ticks.gcMs} ms | ${"%.0f".format(tWake)} ms |",
        )
        // 片付け
        blocking.forEach { it.kernel.closeStdin() }
        blocking.forEach { it.thread!!.join(60_000) }
        pool.shutdown()
        pool.awaitTermination(1, TimeUnit.MINUTES)
        blocking.clear()
        async.clear()
        heapUsedMb()
    }
    line("")
    if (!quick) Unit
}
