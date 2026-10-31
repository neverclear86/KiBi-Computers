package pro.ikili.kibicomputers.poc.execthread

import java.io.File
import java.lang.management.ManagementFactory
import java.util.Locale

/** 比べる方式の1つ（表の1行）。 */
class Variant(val label: String, val create: (count: Int, fuelPerTick: Long, program: Program) -> Scheduler)

/** 測定の設定 */
class Budget(val warmupTicks: Int, val measureTicks: Int, val maxMeasureNanos: Long)

/** 1台が 1 tick に使う CPU 時間の目安（仮の値。燃料の最終値は #251） */
const val COMPUTER_MILLIS_PER_TICK: Double = 2.0

/** ワールドの処理（コンピューター以外）に使う時間。そこそこ重いサーバーを想定する */
const val BASE_WORK_NANOS: Long = 10_000_000

/** 方式 A で、全台で 1 tick に使ってよい時間の上限（ms） */
const val LOCKSTEP_CAP_MILLIS: Double = 10.0

/**
 * 測定の入口。
 *
 * - 引数なし: すべて測って Markdown の表を出す（`build/bench/results.md` にも書く）
 * - `quick`: 回数を減らして短く回す（動作確認用）
 */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val quick = args.firstOrNull() == "quick"
    val out = StringBuilder()
    fun emit(line: String = "") {
        println(line)
        out.append(line).append('\n')
    }
    val cores = Runtime.getRuntime().availableProcessors()
    val rt = ManagementFactory.getRuntimeMXBean()
    emit("## Java ${Runtime.version().feature()}（${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")}）")
    emit()
    emit("- OS: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}、CPU $cores スレッド")
    emit("- JVM 引数: ${rt.inputArguments.joinToString(" ")}")

    val nanosPerUnit = Workload.nanosPerUnit()
    val fuelPerTick = (COMPUTER_MILLIS_PER_TICK * 1e6 / nanosPerUnit).toLong()
    val capFuel = (LOCKSTEP_CAP_MILLIS * 1e6 / nanosPerUnit).toLong()
    emit("- 燃料 1 単位 = ${"%.2f".format(nanosPerUnit / 1000)} µs（xorshift 1000 回）。1台に 1 tick あたり $fuelPerTick 単位（約 $COMPUTER_MILLIS_PER_TICK ms）を配る")
    emit("- ワールドの処理（コンピューター以外）は 1 tick あたり ${BASE_WORK_NANOS / 1_000_000} ms")
    emit()

    val budget = if (quick) Budget(5, 20, 2_000_000_000) else Budget(20, 100, 6_000_000_000)

    // 1. CPU を使い続けるコンピューター
    val cpuVariants = listOf(
        Variant("A") { n, f, p -> LockstepScheduler(n, f, null, p) },
        Variant("A（全台で ${LOCKSTEP_CAP_MILLIS.toInt()} ms まで）") { n, f, p -> LockstepScheduler(n, f, capFuel, p) },
        Variant("B（同時に 2 台）") { n, f, p -> ThreadedScheduler(n, f, 2, OpTiming.IMMEDIATE, p) },
        Variant("B（同時に 8 台）") { n, f, p -> ThreadedScheduler(n, f, 8, OpTiming.IMMEDIATE, p) },
    )
    val counts = if (quick) listOf(1, 8, 32) else listOf(1, 8, 32, 128)
    emit("### 1. CPU を使い続けるコンピューター（終わらない計算）")
    emit()
    emit("MSPT はメインスレッドの tick の時間。計算量は、配った燃料のうち実際に使えた割合。")
    emit()
    emit("| 台数 | 方式 | MSPT 平均 | MSPT p95 | MSPT 最大 | TPS | 1台の燃料 / tick | 計算量 |")
    emit("| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |")
    for (n in counts) {
        for (v in cpuVariants) {
            val s = v.create(n, fuelPerTick, Program.CPU)
            val r = simulate(s, BASE_WORK_NANOS, budget.warmupTicks, budget.measureTicks, budget.maxMeasureNanos)
            val demanded = fuelPerTick.toDouble() * n * r.ticks
            emit(
                "| $n | ${v.label} | ${ms(r.tickNanos.mean())} | ${ms(r.tickNanos.percentile(0.95))} | ${ms(r.tickNanos.percentile(1.0))} | " +
                    "${"%.1f".format(r.tps)} | ${s.fuelPerComputer} | ${pct(r.fuelConsumed / demanded)} |",
            )
        }
    }
    emit()

    // 2. ワールドを操作し続けるコンピューター
    val worldVariants = listOf(
        Variant("A") { n, f, p -> LockstepScheduler(n, f, null, p) },
        Variant("B（${OpTiming.TICK_HEAD.label}）") { n, f, p -> ThreadedScheduler(n, f, 8, OpTiming.TICK_HEAD, p) },
        Variant("B（${OpTiming.IMMEDIATE.label}）") { n, f, p -> ThreadedScheduler(n, f, 8, OpTiming.IMMEDIATE, p) },
    )
    emit("### 2. ワールドを操作し続けるコンピューター（燃料 1 単位ごとにワールド操作を 1 回、同期で待つ）")
    emit()
    emit("待ち時間は、ワールド操作を頼んでから結果が返るまで。メイン（操作）は、メインスレッドがワールド操作の処理に使った時間（tick の外も含む）。")
    emit()
    emit("| 台数 | 方式 | MSPT 平均 | MSPT p95 | TPS | 1台の操作 / 秒 | 待ち時間 中央値 | 待ち時間 p95 | メイン（操作）/ tick |")
    emit("| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    for (n in if (quick) listOf(1, 8) else listOf(1, 8, 32)) {
        for (v in worldVariants) {
            val r = simulate(v.create(n, fuelPerTick, Program.WORLD), BASE_WORK_NANOS, budget.warmupTicks, budget.measureTicks, budget.maxMeasureNanos)
            emit(
                "| $n | ${v.label} | ${ms(r.tickNanos.mean())} | ${ms(r.tickNanos.percentile(0.95))} | ${"%.1f".format(r.tps)} | " +
                    "${"%.0f".format(r.worldOps / r.seconds / n)} | ${us(r.opLatencies.percentile(0.5))} | ${us(r.opLatencies.percentile(0.95))} | ${ms(r.mainOpNanosPerTick)} |",
            )
        }
    }
    emit()

    // 3. 保存のための一時停止
    emit("### 3. 方式 B で全台を止めるまでの時間（保存の前に行う）")
    emit()
    emit("燃料を十分に配って全台を動かし続け、止める依頼から全台が実行をやめるまで。方式 A では tick の外で全台が止まっているので 0。")
    emit()
    emit("| 台数 | 同時に動く台数 | 中央値 | p95 | 最大 |")
    emit("| ---: | ---: | ---: | ---: | ---: |")
    for (n in if (quick) listOf(8) else listOf(8, 32, 128)) {
        val permits = minOf(n, cores)
        val p = measurePause(n, permits, if (quick) 5 else 30)
        emit("| $n | $permits | ${us(p.percentile(0.5))} | ${us(p.percentile(0.95))} | ${us(p.percentile(1.0))} |")
    }
    emit()

    // 4. スタックの深さ
    emit("### 4. スレッドのスタックの大きさと、WASM の再帰の深さの上限")
    emit()
    emit("`recurse(n)`（1段あたりローカル変数がほぼない再帰）が動いた最大の n。既定は JVM の既定（MC のサーバースレッドもこれ）。")
    emit()
    emit("| スタック | 最大の深さ |")
    emit("| --- | ---: |")
    for (size in listOf(0L, 4L shl 20, 16L shl 20, 64L shl 20)) {
        emit("| ${if (size == 0L) "既定" else "${size shr 20} MB"} | ${maxDepth(size)} |")
    }
    emit()

    val file = File("build/bench/results.md")
    file.parentFile.mkdirs()
    file.writeText(out.toString())
    println("結果を ${file.absolutePath} に書いた")
}

/** 方式 B で [n] 台を動かし続け、全台を止めるまでの時間を [trials] 回測る。 */
fun measurePause(n: Int, permits: Int, trials: Int): LongBuffer {
    val scheduler = ThreadedScheduler(n, Long.MAX_VALUE / 4, permits, OpTiming.TICK_HEAD, Program.CPU)
    scheduler.start(World(Thread.currentThread()))
    val result = LongBuffer()
    try {
        scheduler.tick()
        sleepUntil(System.nanoTime() + 300_000_000)
        repeat(trials) {
            result.add(scheduler.pauseAll())
            scheduler.resumeAll()
            sleepUntil(System.nanoTime() + 30_000_000)
        }
    } finally {
        scheduler.close()
    }
    return result
}

/** スタックの大きさ [stackSize] のスレッドで、`recurse` が動く最大の深さを二分探索で調べる。 */
fun maxDepth(stackSize: Long): Int {
    fun works(depth: Int): Boolean {
        var ok = false
        val t = Thread(null, {
            ok = try {
                Workload.instantiate(Workload.FreeHost).export("recurse").apply(depth.toLong())
                true
            } catch (e: Throwable) {
                false
            }
        }, "depth", stackSize)
        t.start()
        t.join()
        return ok
    }
    var lo = 1
    var hi = 1000
    while (works(hi)) {
        lo = hi
        hi *= 2
        if (hi > 50_000_000) return lo
    }
    while (hi - lo > maxOf(1, lo / 100)) {
        val mid = lo + (hi - lo) / 2
        if (works(mid)) lo = mid else hi = mid
    }
    return lo
}

fun ms(nanos: Double): String = "%.2f ms".format(nanos / 1e6)

fun ms(nanos: Long): String = ms(nanos.toDouble())

fun us(nanos: Long): String = if (nanos >= 1_000_000) "%.1f ms".format(nanos / 1e6) else "%.1f µs".format(nanos / 1e3)

fun pct(ratio: Double): String = "%.0f%%".format(ratio * 100)
