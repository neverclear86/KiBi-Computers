package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * メインスレッドで行うカーネルの処理の、サーバー全体の tick ごとの予算（#356、ADR 0356）。
 * 時計は手で進めるもの（呼び出し・通知の中で進める）にして、結果を決まったものにする
 */
class MainThreadBudgetTest {
    private val limit = Duration.ofSeconds(30)

    /** 手で進める時計と、予算 [budgetNanos] のスケジューラ */
    private class Budgeted(budgetNanos: Long) : AutoCloseable {
        val clock = AtomicLong(1_000_000)
        val h = Harness(
            schedulerConfig = SchedulerConfig.builder().maxConcurrentMachines(4).maxBlockingProcesses(64).mainThreadBudgetNanos(budgetNanos).build(),
            nanoTime = { clock.get() },
        )
        val log: MutableList<String> = Collections.synchronizedList(ArrayList())

        fun machine(maxHostCallsPerTick: Int = 64): Machine = h.machine(host = { _, _, _ -> }, maxHostCallsPerTick = maxHostCallsPerTick)

        /** [m] に、実行すると時計を [cost] 進めて [name] を記録する呼び出しを積む */
        fun call(m: Machine, name: String, cost: Long) = m.submitHostCall(0) {
            clock.addAndGet(cost)
            log += name
            name
        }

        /** [m] に、配ると時計を [cost] 進めて [name] を記録する通知を積む */
        fun notify(m: Machine, name: String, cost: Long) = m.postHostCallback {
            clock.addAndGet(cost)
            log += name
        }

        /** 1 tick 進めて、その tick に記録したものを返す */
        fun tick(): List<String> {
            val before = log.size
            h.scheduler.tick()
            return log.subList(before, log.size).toList()
        }

        override fun close() = h.close()
    }

    @Test
    fun `既定の予算は5msで、1未満は断る`() {
        assertEquals(5_000_000L, SchedulerConfig.DEFAULT.mainThreadBudgetNanos)
        assertThrows(IllegalArgumentException::class.java) { SchedulerConfig.builder().mainThreadBudgetNanos(0) }
    }

    @Test
    fun `予算を超えた呼び出しは次のtickに回り、出した順は保たれる`() = timeout(limit) {
        Budgeted(100).use { b ->
            val m = b.machine()
            val futures = (1..5).map { b.call(m, "c$it", 40) }
            // 40・80・120: 3件目で予算を超えたので止める
            assertEquals(listOf("c1", "c2", "c3"), b.tick())
            assertEquals(1, b.h.scheduler.budgetCutTicks.get())
            assertEquals(listOf("c4", "c5"), b.tick())
            assertEquals(listOf("c1", "c2", "c3", "c4", "c5"), futures.map { it.get() })
            assertEquals(0, m.pendingHostCalls)
            // 空になったマシンは次の tick で見ない
            val visited = b.h.scheduler.tickVisitedMachines.get()
            assertEquals(emptyList<String>(), b.tick())
            assertEquals(visited, b.h.scheduler.tickVisitedMachines.get())
            assertEquals(1, b.h.scheduler.budgetCutTicks.get())
        }
    }

    @Test
    fun `予算を使い切る台が先頭に居座らず、マシンの間は巡回する`() = timeout(limit) {
        Budgeted(100).use { b ->
            val machines = listOf("a", "b", "c").associateWith { b.machine() }
            for ((name, m) in machines) for (i in 1..4) b.call(m, "$name$i", 60)
            // 1台目が予算を使い切ると、手を付けていない台が次の tick の先頭になり、使い切った台は後ろへ回る
            assertEquals(listOf("a1", "a2"), b.tick())
            assertEquals(listOf("b1", "b2"), b.tick())
            assertEquals(listOf("c1", "c2"), b.tick())
            assertEquals(listOf("a3", "a4"), b.tick())
            assertEquals(listOf("b3", "b4"), b.tick())
            assertEquals(listOf("c3", "c4"), b.tick())
            assertEquals(emptyList<String>(), b.tick())
            // 手を付けずに持ち越した台を数える（1〜5 tick 目に2台・2台・2台・2台・1台）
            assertEquals(9, b.h.scheduler.carriedMachines.get())
            // 打ち切ったのは1〜5 tick 目（6 tick 目は予算を超えたが、残りがないので数えない）
            assertEquals(5, b.h.scheduler.budgetCutTicks.get())
        }
    }

    @Test
    fun `1件で予算を超えても、毎tick1件は進む`() = timeout(limit) {
        Budgeted(1).use { b ->
            val x = b.machine()
            val y = b.machine()
            for (i in 1..2) {
                b.call(x, "x$i", 1_000)
                b.call(y, "y$i", 1_000)
            }
            assertEquals(listOf("x1"), b.tick())
            assertEquals(listOf("y1"), b.tick())
            assertEquals(listOf("x2"), b.tick())
            assertEquals(listOf("y2"), b.tick())
            assertEquals(emptyList<String>(), b.tick())
        }
    }

    @Test
    fun `予算を切ったら、1台の上限だけがかかる`() = timeout(limit) {
        Budgeted(Long.MAX_VALUE).use { b ->
            val m = b.machine(maxHostCallsPerTick = 3)
            val n = b.machine(maxHostCallsPerTick = 3)
            for (i in 1..4) {
                b.call(m, "m$i", 1_000_000_000)
                b.call(n, "n$i", 1_000_000_000)
            }
            assertEquals(listOf("m1", "m2", "m3", "n1", "n2", "n3"), b.tick())
            assertEquals(listOf("m4", "n4"), b.tick())
            assertEquals(0, b.h.scheduler.budgetCutTicks.get())
        }
    }

    @Test
    fun `1台の上限に達した台も後ろへ回り、手を付けていない台が先になる`() = timeout(limit) {
        Budgeted(100).use { b ->
            val a = b.machine(maxHostCallsPerTick = 1)
            val c = b.machine()
            val d = b.machine()
            b.call(a, "a1", 10)
            b.call(a, "a2", 10)
            b.call(c, "c1", 200)
            b.call(d, "d1", 10)
            // a は上限で止まり（10）、c が予算を超える（210）。d は手を付けずに次の tick の先頭へ。a と c は後ろへ
            assertEquals(listOf("a1", "c1"), b.tick())
            assertEquals(listOf("d1", "a2"), b.tick())
        }
    }

    @Test
    fun `通知は予算の半分までで、台の途中では切らず、ホスト呼び出しを飢えさせない`() = timeout(limit) {
        Budgeted(200).use { b ->
            val ms = (1..3).map { b.machine() }
            for ((i, m) in ms.withIndex()) {
                b.notify(m, "n${i + 1}a", 30)
                b.notify(m, "n${i + 1}b", 30)
            }
            b.call(ms[0], "c1", 10)
            b.call(ms[0], "c2", 10)
            // 通知: 1台目 60（台の途中では切らない）、2台目 120 で半分（100）を超えたので止める。3台目は持ち越し。
            // ホスト呼び出し: 130・140 で予算の内なので、両方
            assertEquals(listOf("n1a", "n1b", "n2a", "n2b", "c1", "c2"), b.tick())
            assertEquals(1, b.h.scheduler.budgetCutTicks.get())
            // 3台目を配り切った tick は、時間が半分を超えても打ち切りに数えない
            assertEquals(listOf("n3a", "n3b"), b.tick())
            assertEquals(1, b.h.scheduler.budgetCutTicks.get())
        }
    }

    @Test
    fun `配り切った・実行し切ったtickは、予算を超えても打ち切りに数えない`() = timeout(limit) {
        Budgeted(100).use { b ->
            val m = b.machine()
            // 通知: 1台だけで、予算の半分を大きく超えるが、残りはない
            b.notify(m, "n", 500)
            assertEquals(listOf("n"), b.tick())
            assertEquals(0, b.h.scheduler.budgetCutTicks.get())
            // ホスト呼び出し: 最後の1件で予算を超えるが、残りはない
            b.call(m, "c1", 10)
            b.call(m, "c2", 500)
            assertEquals(listOf("c1", "c2"), b.tick())
            assertEquals(0, b.h.scheduler.budgetCutTicks.get())
            assertEquals(0, b.h.scheduler.carriedMachines.get())
            // 残りがあれば数える
            b.call(m, "c3", 500)
            b.call(m, "c4", 1)
            assertEquals(listOf("c3"), b.tick())
            assertEquals(1, b.h.scheduler.budgetCutTicks.get())
        }
    }

    @Test
    fun `通知が毎tick予算を使い切っても、ホスト呼び出しは毎tick1件進む`() = timeout(limit) {
        Budgeted(100).use { b ->
            val m = b.machine()
            val other = b.machine()
            b.call(m, "c1", 1)
            b.call(m, "c2", 1)
            // 毎 tick、配るだけで予算を使い切る通知
            b.notify(other, "n1", 1_000)
            assertEquals(listOf("n1", "c1"), b.tick())
            b.notify(other, "n2", 1_000)
            assertEquals(listOf("n2", "c2"), b.tick())
            b.notify(other, "n3", 1_000)
            assertEquals(listOf("n3"), b.tick())
            // 1 tick 目だけ、c2 が残ったので数える。2・3 tick 目は、予算を使い切っても残りがない
            assertEquals(1, b.h.scheduler.budgetCutTicks.get())
        }
    }

    @Test
    fun `呼び出しの致命的なエラーでtickを抜けても、実行していた台と手を付けていない台の残りは後のtickで実行される`() = timeout(limit) {
        Budgeted(Long.MAX_VALUE).use { b ->
            val bad = b.machine()
            val good = b.machine()
            bad.submitHostCall(0) { throw OutOfMemoryError("test") }
            b.call(bad, "bad-after", 1)
            b.call(good, "good", 1)
            assertThrows(OutOfMemoryError::class.java) { b.h.scheduler.tick() }
            assertTrue(b.log.isEmpty())
            assertEquals(setOf("bad-after", "good"), b.tick().toSet())
            assertEquals(0, bad.pendingHostCalls)
            assertEquals(0, good.pendingHostCalls)
            // 印が立ったままになっていない（空を見て下ろし、また積める）
            b.call(bad, "bad-again", 1)
            b.call(good, "good-again", 1)
            assertEquals(setOf("bad-again", "good-again"), b.tick().toSet())
        }
    }

    @Test
    fun `通知の致命的なエラーでtickを抜けても、持ち越した台と投げた台の残りは後のtickで配られる`() = timeout(limit) {
        Budgeted(Long.MAX_VALUE).use { b ->
            val bad = b.machine()
            val good = b.machine()
            bad.postHostCallback { throw OutOfMemoryError("test") }
            b.notify(bad, "bad-after", 1)
            b.notify(good, "good", 1)
            assertThrows(OutOfMemoryError::class.java) { b.h.scheduler.tick() }
            assertTrue(b.log.isEmpty())
            assertEquals(setOf("bad-after", "good"), b.tick().toSet())
            b.notify(bad, "bad-again", 1)
            b.notify(good, "good-again", 1)
            assertEquals(setOf("bad-again", "good-again"), b.tick().toSet())
        }
    }

    @Test
    fun `予算で持ち越した通知も、印が合っていて二重に積まれない`() = timeout(limit) {
        Budgeted(2).use { b ->
            val x = b.machine()
            val y = b.machine()
            b.notify(x, "x1", 10)
            b.notify(y, "y1", 10)
            assertEquals(listOf("x1"), b.tick())
            // 持ち越している間に積んだ通知は、同じ台の1回の配布にまとまる（列には1回だけ載る）
            b.notify(y, "y2", 0)
            assertEquals(listOf("y1", "y2"), b.tick())
            assertEquals(emptyList<String>(), b.tick())
        }
    }

    @Test
    fun `持ち越しの列にいる間に止めた台は、回ってきたときに印が下り、起動し直した後の呼び出しをまた積める`() = timeout(limit) {
        Budgeted(100).use { b ->
            val x = b.machine()
            val y = b.machine()
            b.call(x, "x1", 200)
            val cancelled = listOf(b.call(y, "y1", 1), b.call(y, "y2", 1))
            // x が予算を使い切り、y は手を付けずに持ち越しの列に残る
            assertEquals(listOf("x1"), b.tick())
            assertEquals(2, y.pendingHostCalls)
            y.stop()
            assertTrue(cancelled.all { it.isCompletedExceptionally })
            assertEquals(0, y.pendingHostCalls)
            // 回ってきても何も実行せず、印を下ろす
            assertEquals(emptyList<String>(), b.tick())
            // 印が下りているので、積み直した呼び出しが列に載り、実行される
            val again = b.call(y, "y3", 1)
            assertEquals(listOf("y3"), b.tick())
            assertEquals("y3", again.get())
            assertEquals(0, y.pendingHostCalls)
        }
    }

    @Test
    fun `出力を止めている台は、予算で打ち切る tick をまたいでも1 tick に2回配られず、ほかの台を飢えさせない`() = timeout(limit) {
        Budgeted(100).use { b ->
            val held = b.machine()
            val p = b.machine()
            val q = b.machine()
            // 毎 tick、配るたびに自分を積み直す台（再開で時計を進めて、予算の半分を超える）
            held.holdConsoleOutput {
                b.clock.addAndGet(1_000)
                b.log += "held"
            }
            b.notify(p, "p", 1_000)
            b.notify(q, "q", 1_000)
            // 1 tick に1台ずつ。held は積み直しで次の tick の後ろに回り、p・q が先に配られる
            assertEquals(listOf("held"), b.tick())
            assertEquals(listOf("p"), b.tick())
            assertEquals(listOf("q"), b.tick())
            assertEquals(listOf("held"), b.tick())
            assertEquals(listOf("held"), b.tick())
            held.releaseConsoleOutput()
        }
    }

    @Test
    fun `致命的なエラーの時点で1台の上限に達していた台も、失われずに後のtickで実行される`() = timeout(limit) {
        Budgeted(Long.MAX_VALUE).use { b ->
            val capped = b.machine(maxHostCallsPerTick = 1)
            val bad = b.machine()
            val good = b.machine()
            b.call(capped, "capped-1", 1)
            b.call(capped, "capped-2", 1)
            bad.submitHostCall(0) { throw OutOfMemoryError("test") }
            b.call(bad, "bad-after", 1)
            b.call(good, "good", 1)
            // capped は上限に達して again に入った後で、bad が投げる
            assertThrows(OutOfMemoryError::class.java) { b.h.scheduler.tick() }
            assertEquals(listOf("capped-1"), b.log.toList())
            assertEquals(setOf("capped-2", "bad-after", "good"), b.tick().toSet())
            assertEquals(0, capped.pendingHostCalls)
            // 印が立ったままになっていない
            b.call(capped, "capped-again", 1)
            assertEquals(listOf("capped-again"), b.tick())
        }
    }
}
