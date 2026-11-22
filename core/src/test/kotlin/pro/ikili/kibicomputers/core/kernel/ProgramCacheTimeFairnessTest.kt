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

    /** 3 本目の経路（待っているもの全員が読み込み中）を、2 本以上のスレッドを入れ子の [runOne] で動かして通す */
    private fun allRunningScenario(groupOf1: FairShareGroup, groupOf2: FairShareGroup) {
        val cache = newCache()
        // 台 1 と台 2 が 3 件ずつ並ぶ。入れ子の runOne は、その時点で動いている読み込みの数だけ別のスレッドが取り出すのと同じ
        fun step(name: String, at: Double, next: Boolean): (ByteArray) -> Program = {
            order += name
            now = sec(at)
            if (next) runOne()
            program
        }
        val steps = listOf(
            Triple("x1", 1.0, true),
            Triple("y1", 2.0, true),
            Triple("y2", 3.5, true),
            Triple("x2", 4.0, true),
            Triple("x3", 5.0, true),
            Triple("y3", 5.5, false),
        )
        val loaders = steps.associate { (name, at, next) -> name to step(name, at, next) }
        for (name in listOf("x1", "x2", "x3")) cache.request(1, name, loaders.getValue(name), groupOf1)
        for (name in listOf("y1", "y2", "y3")) cache.request(2, name, loaders.getValue(name), groupOf2)
        runOne()
        assertEquals(0, tasks.size)
        // 1) x1（台 1）が始まりに、2) 記録のない台 2 の y1。
        // 3) 台 1 は 2 秒、台 2 は 1 秒読み込み中: 少ない台 2 の y2（巡回の先頭の台 1 ではない）。
        // 4) 台 1 は 1 件で 3.5 秒、台 2 は 2 件（1 秒と 2 秒に始めた）で 2.5 + 1.5 = 4 秒: 台 1 の x2
        // 5) 台 1 は 2 件（0 秒と 3.5 秒）で 4 + 0.5 = 4.5 秒、台 2 は 2 件で 3 + 2 = 5 秒: 台 1 の x3（巡回の先頭は台 2）
        assertEquals(listOf("x1", "y1", "y2", "x2", "x3", "y3"), order)
        // 終わったら、6 件を 2 台と組に付ける（台 1: 5.5 + 2 + 1.5 秒、台 2: 4.5 + 3.5 + 0.5 秒）。読み込み中の数は 0 に戻る
        assertEquals(sec(9.0), cache.debtOf(1, groupOf1))
        assertEquals(sec(8.5), cache.debtOf(2, groupOf2))
        if (groupOf1 == groupOf2) {
            assertEquals(sec(17.5), cache.debtOfGroup(groupOf1))
        } else {
            assertEquals(sec(9.0), cache.debtOfGroup(groupOf1))
            assertEquals(sec(8.5), cache.debtOfGroup(groupOf2))
        }
        assertEquals(0, cache.runningCount)
    }

    @Test
    fun `全員が読み込み中なら、読み込み中の経過時間を足して最も少ない台が選ばれる（同じ組）`() {
        allRunningScenario(FairShareGroup.NONE, FairShareGroup.NONE)
    }

    @Test
    fun `全員が読み込み中なら、読み込み中の経過時間を足して最も少ない組が選ばれる（別の組）`() {
        allRunningScenario(FairShareGroup.of(UUID(0, 1)), FairShareGroup.of(UUID(0, 2)))
    }

    @Test
    fun `重い読み込みの間にすぐ終わる読み込みをはさんでも、重い台は後に回る`() {
        val cache = newCache()
        cache.request(1, "h0", job("h0", 8.0, fail = true))
        runAll()
        // exec を受け取って次を頼むまでの間（50 ms）に、すぐ終わる読み込みを 1 件はさむ
        now += sec(0.05)
        cache.request(1, "q", job("q", 0.001))
        runAll()
        now += sec(0.05)
        cache.request(1, "h1", job("h1", 8.0, fail = true))
        cache.request(2, "light", job("light", 0.5))
        runAll()
        // 前の 1 件（0.001 秒）だけを見込みにすると、台 1 は軽い台と同じ扱いに戻って先に並ぶ
        assertEquals(listOf("h0", "q", "light", "h1"), order)
    }

    @Test
    fun `中くらいの読み込みを続けて見込みを減らそうとしても、重い台は後に回る`() {
        val cache = newCache()
        // 8 秒の後に 4・2・1 秒を、ほかに待っている台がいない間に読み込む（借りは毎回帳消しになり、最後の 1 秒だけ残る）
        for ((name, seconds) in listOf("h8" to 8.0, "h4" to 4.0, "h2" to 2.0, "h1" to 1.0)) {
            cache.request(1, name, job(name, seconds, fail = true))
            runAll()
        }
        assertEquals(sec(1.0), cache.debtOf(1))
        cache.request(1, "h8b", job("h8b", 8.0, fail = true))
        cache.request(2, "light", job("light", 0.5))
        runAll()
        // 見込みを直前の読み込みから減らすと、借り 1 秒 + 見込み 1 秒が 2 秒以下になり、台 1 が先に並ぶ。
        // 見込みは読み込みでは減らないので、8 秒のまま後に回る
        assertEquals(listOf("h8", "h4", "h2", "h1", "light", "h8b"), order)
    }

    @Test
    fun `組の借りも、組のどの台も頼まずにいた間だけ減る`() {
        val a = FairShareGroup.of(UUID(0, 1))
        val b = FairShareGroup.of(UUID(0, 2))
        val cache = newCache()
        cache.request(1, "heavy", job("heavy", 8.0, fail = true), a)
        runAll()
        assertEquals(sec(8.0), cache.debtOfGroup(a))
        // 10 秒、組 a のどの台も頼まずにいた: 組 a の別の台 2 が頼むと、組の借りが 1.25 秒減る（台 2 自身には記録がない）
        now += sec(10.0)
        cache.request(2, "a2", job("a2", 0.1), a)
        assertEquals(sec(6.75), cache.debtOfGroup(a))
        // 組 a がもう並んでいる間に、組 a の台 3 が頼んでも、組の借りは減らない（台 3 は待ち行列になかったので、台 3 の勘定だけが見られる）
        now += sec(10.0)
        cache.request(3, "a3", job("a3", 0.1), a)
        assertEquals(sec(6.75), cache.debtOfGroup(a))
        // 組 b は後から来たが、組 a は借りが残っているので後に回る
        cache.request(4, "b4", job("b4", 0.1), b)
        runAll()
        assertEquals(listOf("heavy", "b4", "a2", "a3"), order)
        // 組 a がもう一度 8 秒使ってから 112 秒頼まずにいれば、借りも見込み（8 秒から 2 秒）も減り、巡回の順に戻る
        cache.request(1, "heavy2", job("heavy2", 8.0, fail = true), a)
        runAll()
        order.clear()
        now += sec(112.0)
        cache.request(5, "a5", job("a5", 0.1), a)
        assertEquals(0, cache.debtOfGroup(a))
        cache.request(6, "b6", job("b6", 0.1), b)
        runAll()
        assertEquals(listOf("a5", "b6"), order)
    }

    @Test
    fun `減りきった組と、空になった組の持ち主の勘定は後始末で消える`() {
        val a = FairShareGroup.of(UUID(0, 1))
        val cache = newCache()
        // 組 a の台 1 と、持ち主なしの組の 62 台
        cache.request(1, "a1", job("a1", 0.01), a)
        (2L..63L).forEach { cache.request(it, "p$it", job("p$it", 0.01)) }
        runAll()
        // 63 台と、組 a・組 NONE
        assertEquals(65, cache.usageCount)
        assertEquals(2, cache.ownerLedgerCount)
        now += sec(3600.0)
        // 64 回目の読み込みで、減りきったものを消す。組 a の記録と、空になった組 a の持ち主の勘定も消える
        cache.request(64, "p64", job("p64", 0.01))
        runAll()
        assertEquals(2, cache.usageCount)
        assertEquals(0, cache.debtOfGroup(a))
        assertEquals(1, cache.ownerLedgerCount)
    }

    @Test
    fun `借りも見込みも残っている台は、後始末では消えず、順番が後始末の回数で変わらない`() {
        val cache = newCache()
        cache.request(1, "heavy", job("heavy", 8.0, fail = true))
        runAll()
        // 100 秒頼まずにいた（借り 8 秒 + 見込み 8 秒 = 16 秒に対して、減るのは 12.5 秒。見込みが 3.5 秒残る）
        now += sec(100.0)
        // 63 台の読み込みで、借りを付けた回数が 64 になる（後始末が走る）
        (2L..64L).forEach { cache.request(it, "p$it", job("p$it", 0.01)) }
        runAll()
        // 後始末が走った（記録のあった台のほとんどは消えたが、台 1 は残っている）
        assertTrue(cache.usageCount < 64)
        order.clear()
        cache.request(1, "c", job("c", 0.1))
        cache.request(100, "d", job("d", 0.1))
        runAll()
        // 台 1 は、見込み 3.5 秒が残っていて、しきい値（2 秒）より多いので、後から来た台 100 が先
        assertEquals(listOf("d", "c"), order)
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
