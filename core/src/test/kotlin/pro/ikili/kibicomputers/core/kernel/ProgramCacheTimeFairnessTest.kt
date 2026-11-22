package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.ProgramCache
import pro.ikili.kibicomputers.core.kernel.sched.FairShareGroup
import pro.ikili.kibicomputers.core.runtime.TestModules
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * コンパイル用のスレッドを、件数だけでなく使った時間でも公平にする（#750、ADR 0750）。
 * 手で回す Executor と差し替えた時計で、決まった順に動かす（読み込みは時計を進めるだけ）
 */
class ProgramCacheTimeFairnessTest {
    private val program: Program = Program.load(EndiveRuntime(), TestModules.ADD)

    /** 時計（ナノ秒） */
    private var now = 0L

    /** 渡された「次の1つを読み込む」仕事（手で1つずつ動かす） */
    private val tasks = ArrayDeque<Runnable>()
    private val manual = Executor { tasks.addLast(it) }

    /** 読み込んだ順（[job] の名前） */
    private val order = ArrayList<String>()

    private fun newCache(threshold: Long = ProgramCache.DEFAULT_DEBT_THRESHOLD_NANOS): ProgramCache = ProgramCache(
        100,
        Long.MAX_VALUE,
        manual,
        maxPendingPerOwner = 8,
        nanoTime = { now },
        debtThresholdNanos = threshold,
    )

    /** [seconds] 秒かかる読み込み。[fail] なら [WasmLoadException]（時間切れの失敗の代わり）。[during] は読み込みの途中で動かす */
    private fun job(name: String, seconds: Double, fail: Boolean = false, during: () -> Unit = {}): (ByteArray) -> Program = {
        order += name
        now += (seconds * 1e9).toLong()
        during()
        if (fail) throw WasmLoadException("時間切れ: $name")
        program
    }

    private fun ProgramCache.request(owner: Long, name: String, loader: (ByteArray) -> Program, group: FairShareGroup = FairShareGroup.NONE): CompletableFuture<Program> =
        load(owner, group, true, name.toByteArray(), true, emptySet(), loader)

    private fun runOne() = tasks.removeFirst().run()

    private fun runAll() {
        while (tasks.isNotEmpty()) runOne()
    }

    private fun sec(s: Double): Long = (s * 1e9).toLong()

    @Test
    fun `重い台が何台も読み込み続けても、軽い台は読み込み中の1件の後すぐに読み込まれる`() {
        fun scenario(threshold: Long): List<String> {
            order.clear()
            now = 0
            val cache = newCache(threshold)
            // 重い台 1〜3 が 8 秒の読み込み（時間切れ）を 1 件ずつ終え、台 3 の読み込み中に、3 台が次を出してから軽い台 10 が頼む
            cache.request(1, "h1-0", job("h1-0", 8.0, fail = true))
            cache.request(2, "h2-0", job("h2-0", 8.0, fail = true))
            cache.request(
                3,
                "h3-0",
                job("h3-0", 8.0, fail = true) {
                    (1L..3L).forEach { cache.request(it, "h$it-1", job("h$it-1", 8.0, fail = true)) }
                    cache.request(10, "light", job("light", 0.5))
                },
            )
            runAll()
            return order.toList()
        }
        // 件数の巡回だけ（しきい値なし）なら、軽い台は重い台 3 件（24 秒）の後
        assertEquals(listOf("h1-0", "h2-0", "h3-0", "h1-1", "h2-1", "h3-1", "light"), scenario(Long.MAX_VALUE))
        // 時間の公平さ: 借りのある重い台は後に回り、軽い台は読み込み中の1件の後すぐ。重い台どうしは借りの少ない順
        assertEquals(listOf("h1-0", "h2-0", "h3-0", "light", "h1-1", "h2-1", "h3-1"), scenario(ProgramCache.DEFAULT_DEBT_THRESHOLD_NANOS))
    }

    @Test
    fun `軽い台がいなければ、借りの多い台もすぐに読み込む`() {
        val cache = newCache()
        repeat(5) { n ->
            cache.request(1, "h$n", job("h$n", 8.0, fail = true))
            assertEquals(1, tasks.size)
            runOne()
        }
        assertEquals(5, order.size)
        // 待っているのが 1 台だけなら、取り出すたびに帳消しになる（借りは最後の 1 件の分だけ）
        assertEquals(sec(8.0), cache.debtOf(1))
    }

    @Test
    fun `頼まずにいた間だけ借りが減り、減れば巡回の順に戻る`() {
        val cache = newCache()
        cache.request(1, "heavy", job("heavy", 8.0, fail = true))
        runAll()
        assertEquals(sec(8.0), cache.debtOf(1))
        // 10 秒頼まずにいた（借りは 1.25 秒減って 6.75 秒）: 先に並んでも、後から来た台が先
        now += sec(10.0)
        cache.request(1, "a", job("a", 0.1))
        assertEquals(sec(6.75), cache.debtOf(1))
        cache.request(2, "b", job("b", 0.1))
        runAll()
        assertEquals(listOf("heavy", "b", "a"), order)
        // もう一度 8 秒使ってから 112 秒頼まずにいれば、借り（8 秒）は消え、前の読み込みの時間の見込みも 8 秒から 2 秒に減り、
        // 巡回の順に戻る（48 秒では借りが 2 秒残り、見込みと合わせて 10 秒なので、まだ後に回る）
        cache.request(1, "heavy2", job("heavy2", 8.0, fail = true))
        runAll()
        assertEquals(sec(8.0), cache.debtOf(1))
        order.clear()
        now += sec(112.0)
        cache.request(1, "c", job("c", 0.1))
        assertEquals(0, cache.debtOf(1))
        cache.request(2, "d", job("d", 0.1))
        runAll()
        assertEquals(listOf("c", "d"), order)
    }

    @Test
    fun `頼み直し続ける台の借りは減らず、待っているものが全員借りていれば帳消しにする`() {
        val cache = newCache()
        // 台 1 は 8 秒、台 2 は 4 秒の読み込みを、読み込み中に次を頼む（頼まずにいた時間がないので借りは減らない）
        cache.request(1, "h1-0", job("h1-0", 8.0, fail = true) { cache.request(1, "h1-1", job("h1-1", 8.0, fail = true)) })
        cache.request(2, "h2-0", job("h2-0", 4.0, fail = true) { cache.request(2, "h2-1", job("h2-1", 4.0, fail = true)) })
        runOne()
        runOne()
        assertEquals(sec(8.0), cache.debtOf(1))
        assertEquals(sec(4.0), cache.debtOf(2))
        // 待っているのは 2 台とも借りのある台。少ない方（4 秒）を帳消しにして、借り 0 の台 2 が先（台 1 は 4 秒で 2 秒より多い）
        runOne()
        assertEquals(sec(4.0), cache.debtOf(1))
        assertEquals("h2-1", order.last())
        runAll()
        assertEquals(listOf("h1-0", "h2-0", "h2-1", "h1-1"), order)
    }

    @Test
    fun `失敗でもVMのエラーでも借りを付ける`() {
        val cache = newCache()
        cache.request(1, "fail", job("fail", 3.0, fail = true))
        runOne()
        assertEquals(sec(3.0), cache.debtOf(1))
        val oom: (ByteArray) -> Program = { now += sec(2.0); throw OutOfMemoryError("テスト") }
        val f = cache.request(2, "oom", oom)
        assertThrows<OutOfMemoryError> { runOne() }
        assertTrue(f.isCompletedExceptionally)
        assertEquals(sec(2.0), cache.debtOf(2))
        assertEquals(0, cache.loadingCount)
    }

    @Test
    fun `借りは取り出した時点の持ち主に付け、相乗りした台には付けない`() {
        val cache = newCache()
        // 台 1 の読み込みに台 2 が相乗りし、台 1 が待つのをやめる（始まる前なので台 2 に移る。#383）
        val first = cache.request(1, "moved", job("moved", 4.0))
        val riding = cache.request(2, "moved", job("moved", 4.0))
        first.cancel(false)
        // 台 3 の読み込みに台 4 が相乗りする
        cache.request(3, "shared", job("shared", 3.0))
        cache.request(4, "shared", job("shared", 3.0))
        runAll()
        assertTrue(riding.isDone)
        assertEquals(0, cache.debtOf(1))
        assertEquals(sec(4.0), cache.debtOf(2))
        assertEquals(sec(3.0), cache.debtOf(3))
        assertEquals(0, cache.debtOf(4))
    }

    @Test
    fun `始まった後に持ち主が待つのをやめても、始めた持ち主に付ける`() {
        val cache = newCache()
        lateinit var mine: CompletableFuture<Program>
        mine = cache.request(1, "x", job("x", 5.0) { mine.cancel(false) })
        cache.request(2, "x", job("x", 5.0))
        runAll()
        assertEquals(sec(5.0), cache.debtOf(1))
        assertEquals(0, cache.debtOf(2))
    }

    @Test
    fun `組にも借りを付け、借りの多い持ち主の組は後に回る`() {
        val a = FairShareGroup.of(UUID(0, 1))
        val b = FairShareGroup.of(UUID(0, 2))
        val cache = newCache()
        // 持ち主 a の台 1 が重い読み込みをした後、a の別の台 2 と、b の台 3 が並ぶ（a が先）
        cache.request(1, "heavy", job("heavy", 8.0, fail = true), a)
        runAll()
        assertEquals(sec(8.0), cache.debtOfGroup(a))
        cache.request(2, "a2", job("a2", 0.1), a)
        cache.request(3, "b3", job("b3", 0.1), b)
        runAll()
        // 台 2 自身に借りはないが、組 a の借りで後に回る
        assertEquals(listOf("heavy", "b3", "a2"), order)
    }

    @Test
    fun `スレッドが2本でも、読み込み中の組がほかの組の番を取らない`() {
        val a = FairShareGroup.of(UUID(0, 1))
        val b = FairShareGroup.of(UUID(0, 2))
        fun scenario(threshold: Long): List<String> {
            order.clear()
            now = 0
            tasks.clear()
            val cache = newCache(threshold)
            // 1 本目が a の台 1 を読み込んでいる間に、b の台 3 が頼み、2 本目のスレッドが次を取る
            cache.request(1, "a1", job("a1", 1.0, fail = true) {
                cache.request(3, "b3", job("b3", 0.1), b)
                // 2 本目のスレッド（1 本目は読み込み中）
                runOne()
            }, a)
            cache.request(2, "a2", job("a2", 8.0, fail = true), a)
            runAll()
            return order.toList()
        }
        // 読み込み中の組は、ほかに選べる組があれば選ばない（巡回の順なら a の台 2 が先。件数の巡回だけにしても同じ）
        assertEquals(listOf("a1", "b3", "a2"), scenario(ProgramCache.DEFAULT_DEBT_THRESHOLD_NANOS))
        assertEquals(listOf("a1", "b3", "a2"), scenario(Long.MAX_VALUE))
    }

    @Test
    fun `減りきった借りは消え、閉じたら全部消える`() {
        val cache = newCache()
        (1L..63L).forEach { cache.request(it, "p$it", job("p$it", 0.01)) }
        runAll()
        // 63 台と組 NONE
        assertEquals(64, cache.usageCount)
        now += sec(3600.0)
        // 64 回目の読み込みで、減りきったものを消す
        cache.request(64, "p64", job("p64", 0.01))
        runAll()
        assertEquals(2, cache.usageCount)
        cache.close()
        assertEquals(0, cache.usageCount)
    }
}
