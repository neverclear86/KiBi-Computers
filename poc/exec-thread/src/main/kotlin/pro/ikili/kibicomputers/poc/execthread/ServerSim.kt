package pro.ikili.kibicomputers.poc.execthread

/** サーバーの tick の長さ（20 TPS） */
const val TICK_NANOS: Long = 50_000_000

/** 1回の模擬サーバーの実行の結果。 */
class SimResult(
    /** 測った tick の時間（メインスレッドの tick の中の処理、ナノ秒） */
    val tickNanos: LongBuffer,
    /** 実際の TPS */
    val tps: Double,
    /** 測定中に使われた燃料の合計 */
    val fuelConsumed: Long,
    /** 測定中に配った燃料の合計（全台が使い切れば [fuelConsumed] と同じ） */
    val fuelOffered: Long,
    /** ワールド操作の数（測定中） */
    val worldOps: Long,
    /** ワールド操作の待ち時間（全台の合計） */
    val opLatencies: LongBuffer,
    /** メインスレッドがワールド操作に使った時間の、1 tick あたりの平均（ナノ秒） */
    val mainOpNanosPerTick: Double,
    /** 測定の時間（秒） */
    val seconds: Double,
    /** 測った tick の数 */
    val ticks: Int,
)

/**
 * MC のサーバーの tick の繰り返しを模したもの。
 *
 * 1 tick は「ワールドの処理（[baseWorkNanos] だけ CPU を使う）→ スケジューラの tick」。50 ms より早く終われば次の tick まで待ち
 * （方式 B では、この間にワールド操作を処理できる）、遅れたらすぐ次の tick に入る（MC と同じく、遅れを取り戻すための早回しはしない）。
 */
fun simulate(
    scheduler: Scheduler,
    baseWorkNanos: Long,
    warmupTicks: Int,
    measureTicks: Int,
    maxMeasureNanos: Long,
): SimResult {
    val world = World(Thread.currentThread())
    scheduler.start(world)
    try {
        var next = System.nanoTime()
        repeat(warmupTicks) { next = oneTick(scheduler, baseWorkNanos, next, null) }

        for (c in scheduler.computers) c.recording = true
        val fuelBefore = scheduler.computers.sumOf { it.consumed }
        val opsBefore = world.ops
        val mainOpsBefore = scheduler.mainOpNanos
        val ticks = LongBuffer()
        val begin = System.nanoTime()
        var count = 0
        while (count < measureTicks && System.nanoTime() - begin < maxMeasureNanos) {
            next = oneTick(scheduler, baseWorkNanos, next, ticks)
            count++
        }
        val end = System.nanoTime()
        for (c in scheduler.computers) c.recording = false
        val fuel = scheduler.computers.sumOf { it.consumed } - fuelBefore
        val ops = world.ops - opsBefore
        val mainOps = scheduler.mainOpNanos - mainOpsBefore
        scheduler.computers.firstNotNullOfOrNull { it.failure }?.let { throw IllegalStateException("コンピューター ${it.message}", it) }

        scheduler.close()
        val latencies = LongBuffer()
        for (c in scheduler.computers) latencies.addAll(c.opLatencies)
        return SimResult(
            tickNanos = ticks,
            tps = count / ((end - begin) / 1e9),
            fuelConsumed = fuel,
            fuelOffered = scheduler.fuelPerComputer * scheduler.computers.size * count,
            worldOps = ops,
            opLatencies = latencies,
            mainOpNanosPerTick = mainOps.toDouble() / count,
            seconds = (end - begin) / 1e9,
            ticks = count,
        )
    } finally {
        scheduler.close()
    }
}

/** 1 tick 進めて、次の tick を始める時刻を返す */
private fun oneTick(scheduler: Scheduler, baseWorkNanos: Long, start: Long, record: LongBuffer?): Long {
    val tickStart = maxOf(start, System.nanoTime())
    val workEnd = tickStart + baseWorkNanos
    while (System.nanoTime() < workEnd) Thread.onSpinWait()
    scheduler.tick()
    val tickEnd = System.nanoTime()
    record?.add(tickEnd - tickStart)
    val next = maxOf(tickStart + TICK_NANOS, tickEnd)
    scheduler.idle(next)
    return next
}
