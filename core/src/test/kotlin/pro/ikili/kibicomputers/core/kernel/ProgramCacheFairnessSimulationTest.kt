package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.ProgramCache
import pro.ikili.kibicomputers.core.kernel.sched.FairShareGroup
import pro.ikili.kibicomputers.core.runtime.TestModules
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.util.PriorityQueue
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.random.Random

/**
 * 1,000 台規模で、重い台が 8 秒の読み込み（時間切れの失敗）を順に出し続けたときの、軽い台の読み込みの待ち時間（#750、ADR 0750）。
 * コンパイル用のスレッド 1 本を、差し替えた時計の上の離散事象のシミュレーションで動かす（実時間はかからない）。
 * 結果の表は標準出力に出す（ADR 0750 の表はこれを写したもの）
 */
class ProgramCacheFairnessSimulationTest {
    private val program: Program = Program.load(EndiveRuntime(), TestModules.ADD)

    /** 軽い台の待ち（秒）。p50・p99・最大は [STEADY_FROM] 秒より後に頼んだもの、[firstMax] は最初からの最大 */
    private class Stats(val p50: Double, val p99: Double, val max: Double, val firstMax: Double, val heavyLoads: Int)

    private class Event(val at: Long, val seq: Long, val action: () -> Unit)

    /**
     * [machines] 台のうち [heavy] 台が重い台。軽い台は合わせて毎秒 [lightRate] 件、0.3〜0.9 秒の読み込みを出す（ポアソン到着）。
     * [playerGroups] なら、重い台は 1 台ずつ別のプレイヤーの組、軽い台は 50 人の組に分ける。そうでなければ全部が持ち主なしの組
     */
    private fun simulate(
        threshold: Long,
        heavy: Int,
        playerGroups: Boolean,
        quickBetween: Boolean = false,
        machines: Int = 1000,
        lightRate: Double = 0.5,
        seconds: Int = 1800,
    ): Stats {
        var now = 0L
        val tasks = ArrayDeque<Runnable>()
        val cache = ProgramCache(16, Long.MAX_VALUE, Executor { tasks.addLast(it) }, nanoTime = { now }, debtThresholdNanos = threshold)
        val events = PriorityQueue<Event>(compareBy<Event> { it.at }.thenBy { it.seq })
        var seq = 0L
        val end = seconds * NANOS
        val steadyFrom = STEADY_FROM * NANOS
        val waits = ArrayList<Long>()
        var firstMax = 0L
        var heavyLoads = 0
        var serial = 0
        fun group(id: Long): FairShareGroup = when {
            !playerGroups -> FairShareGroup.NONE
            id < heavy -> FairShareGroup.of(UUID(1, id))
            else -> FairShareGroup.of(UUID(0, id % 50))
        }

        /** 読み込み1件。途中に来る事象は、時計をその時刻にして動かす */
        fun loader(durationNanos: Long, fail: Boolean, requestedAt: Long?): (ByteArray) -> Program = {
            if (requestedAt != null) {
                firstMax = maxOf(firstMax, now - requestedAt)
                if (requestedAt >= steadyFrom) waits += now - requestedAt
            }
            val finish = now + durationNanos
            while (events.isNotEmpty() && events.peek().at <= finish) {
                val e = events.poll()
                now = e.at
                e.action()
            }
            now = finish
            if (fail) throw WasmLoadException("時間切れ")
            program
        }

        fun requestHeavy(id: Long) {
            if (now >= end) return
            val f = cache.load(id, group(id), true, "heavy-${serial++}".toByteArray(), true, emptySet(), loader(8 * NANOS, true, null))
            f.whenComplete { _, _ ->
                heavyLoads++
                if (!quickBetween) {
                    // 失敗を受け取ったらすぐ次を出す（exec を1つずつ順に繰り返す）
                    requestHeavy(id)
                    return@whenComplete
                }
                // 失敗を受け取って 50 ms 後に、すぐ終わる読み込み（1 ms）を 1 件はさみ、その 50 ms 後に次の重い読み込みを出す
                events += Event(now + GAP, seq++) {
                    val q = cache.load(id, group(id), true, "quick-${serial++}".toByteArray(), true, emptySet(), loader(QUICK, false, null))
                    q.whenComplete { _, _ -> events += Event(now + GAP, seq++) { requestHeavy(id) } }
                }
            }
        }
        val random = Random(750)
        var t = 0L
        while (true) {
            t += (-kotlin.math.ln(1 - random.nextDouble()) / lightRate * NANOS).toLong()
            if (t >= end) break
            val id = heavy + random.nextLong(machines.toLong() - heavy)
            val duration = ((0.3 + 0.6 * random.nextDouble()) * NANOS).toLong()
            val at = t
            events += Event(at, seq++) {
                cache.load(id, group(id), true, "light-${serial++}".toByteArray(), true, emptySet(), loader(duration, false, at))
            }
        }
        (0L until heavy).forEach { id -> events += Event(0, seq++) { requestHeavy(id) } }
        while (true) {
            if (tasks.isNotEmpty()) {
                tasks.removeFirst().run()
                continue
            }
            val e = events.poll() ?: break
            now = e.at
            e.action()
        }
        waits.sort()
        fun pct(p: Double) = waits[((waits.size - 1) * p).toInt()] / 1e9
        return Stats(pct(0.5), pct(0.99), waits.last() / 1e9, firstMax / 1e9, heavyLoads)
    }

    @Test
    fun `重い台が読み込み続けても、軽い台の待ちは読み込み中の1件の残りほどに収まる`() {
        val rows = StringBuilder()
        rows.appendLine("| 組 | 重い台 | 方式 | 軽い台の待ち p50 | p99 | 最大 | 最大（最初から） | 重い台の読み込み数 |")
        rows.appendLine("| --- | --- | --- | --- | --- | --- | --- | --- |")
        val failures = ArrayList<String>()
        for (playerGroups in listOf(false, true)) {
            for (k in listOf(0, 1, 5, 20)) {
                val old = simulate(Long.MAX_VALUE, k, playerGroups)
                val fair = simulate(ProgramCache.DEFAULT_DEBT_THRESHOLD_NANOS, k, playerGroups)
                val label = if (playerGroups) "重い台ごとに別の人" else "全部持ち主なし"
                for ((name, s) in listOf("件数の巡回" to old, "時間の公平さ" to fair)) {
                    rows.appendLine("| $label | $k | $name | %.2f s | %.2f s | %.2f s | %.2f s | %d |".format(s.p50, s.p99, s.max, s.firstMax, s.heavyLoads))
                }
                val tag = "$label k=$k"
                if (k == 0) {
                    // 重い台がいなければ、件数の巡回と同じ
                    if (fair.p99 != old.p99 || fair.firstMax != old.firstMax) failures += "$tag: 重い台がいないのに変わった"
                    continue
                }
                // 1 件は止められないので、待ちは「読み込み中の1件（8 秒）」と、先に並んだ軽い読み込みの分まで
                if (fair.max > 16.0) failures += "$tag: 最大 ${fair.max} 秒"
                if (fair.p99 > old.p99) failures += "$tag: p99 が悪くなった（${fair.p99} > ${old.p99}）"
                // 重い台も、軽い台がいない間は読み込む（スレッドを遊ばせない）
                if (fair.heavyLoads < old.heavyLoads * 0.8) failures += "$tag: 重い台の読み込み数 ${fair.heavyLoads} / ${old.heavyLoads}"
            }
        }
        println(rows)
        assertTrue(failures.isEmpty(), (failures + rows.toString()).joinToString(System.lineSeparator()))
    }

    @Test
    fun `重い読み込みの間にすぐ終わる読み込みをはさんでも、軽い台の待ちは読み込み中の1件の残りほどに収まる`() {
        val rows = StringBuilder()
        rows.appendLine("| 組 | 重い台 | 方式 | 軽い台の待ち p50 | p99 | 最大 | 最大（最初から） | 重い台の読み込み数 |")
        rows.appendLine("| --- | --- | --- | --- | --- | --- | --- | --- |")
        val failures = ArrayList<String>()
        for (playerGroups in listOf(false, true)) {
            for (k in listOf(5, 20)) {
                val old = simulate(Long.MAX_VALUE, k, playerGroups, quickBetween = true)
                val fair = simulate(ProgramCache.DEFAULT_DEBT_THRESHOLD_NANOS, k, playerGroups, quickBetween = true)
                val label = if (playerGroups) "重い台ごとに別の人" else "全部持ち主なし"
                for ((name, s) in listOf("件数の巡回" to old, "時間の公平さ" to fair)) {
                    rows.appendLine("| $label | $k | $name | %.2f s | %.2f s | %.2f s | %.2f s | %d |".format(s.p50, s.p99, s.max, s.firstMax, s.heavyLoads))
                }
                val tag = "$label k=$k"
                // 件数の巡回と違い、重い台の数に比例して伸びない（読み込み中の 1 件と、先に並んだ軽い読み込みの分まで）
                if (fair.max > 16.0) failures += "$tag: 最大 ${fair.max} 秒"
                if (fair.p99 > old.p99) failures += "$tag: p99 が悪くなった（${fair.p99} > ${old.p99}）"
                if (fair.heavyLoads < old.heavyLoads * 0.8) failures += "$tag: 重い台の読み込み数 ${fair.heavyLoads} / ${old.heavyLoads}"
            }
        }
        println(rows)
        assertTrue(failures.isEmpty(), (failures + rows.toString()).joinToString(System.lineSeparator()))
    }

    private companion object {
        const val NANOS: Long = 1_000_000_000L

        /** 失敗を受け取ってから次を頼むまでの間（50 ms） */
        const val GAP: Long = 50_000_000L

        /** すぐ終わる読み込みの時間（1 ms） */
        const val QUICK: Long = 1_000_000L

        /** 定常とみなす時刻（秒）。重い台の最初の 1 件は、使った時間がまだないので軽い台と同じ扱いになる */
        const val STEADY_FROM: Long = 300
    }
}
