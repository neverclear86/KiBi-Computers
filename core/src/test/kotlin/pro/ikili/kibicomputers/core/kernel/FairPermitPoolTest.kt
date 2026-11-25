package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively
import pro.ikili.kibicomputers.core.kernel.sched.FairShareGroup
import pro.ikili.kibicomputers.core.kernel.sched.PermitPool
import pro.ikili.kibicomputers.core.kernel.sched.WorkerPool
import java.time.Duration
import java.util.Collections
import java.util.ResourceBundle
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 許可を公平の組（持ち主）ごとに公平に渡す（#738、ADR 0738） */
class FairPermitPoolTest {
    private val a = FairShareGroup.of(UUID(0, 1))
    private val b = FairShareGroup.of(UUID(0, 2))

    /** 何も出さないロガー（取り残しのスタックトレース付きの WARNING で、テストの出力を汚さない） */
    private object QuietLogger : System.Logger {
        override fun getName(): String = "test"

        override fun isLoggable(level: System.Logger.Level): Boolean = false

        override fun log(level: System.Logger.Level, bundle: ResourceBundle?, msg: String?, thrown: Throwable?) {}

        override fun log(level: System.Logger.Level, msg: String?) {}

        override fun log(level: System.Logger.Level, bundle: ResourceBundle?, format: String?, vararg params: Any?) {}
    }

    private class Fixture(permits: Int, perGroupLimit: Int = Int.MAX_VALUE) : AutoCloseable {
        /** ワーカーへの `execute` を失敗させられる（スレッドを作れないときの取り残し・直接の道の失敗。#629、#870） */
        val executor = FailingExecutor(Executors.newCachedThreadPool { r -> KernelThreadsProbe.thread(r) })
        val workers = WorkerPool(1L shl 20, 1_000, executor)
        val pool = PermitPool(permits, workers, log = QuietLogger, perGroupLimit = perGroupLimit)
        val order: MutableList<String> = Collections.synchronizedList(ArrayList())
        val ran = Semaphore(0)

        /** 動いたら名前を残すだけの仕事（許可はテストが返す） */
        fun task(name: String): Runnable = Runnable {
            order += name
            ran.release()
        }

        fun awaitRuns(n: Int) = assertTrue(ran.tryAcquire(n, 5, TimeUnit.SECONDS), "仕事が動かない: $order")

        override fun close() {
            executor.alwaysFail = false
            executor.failuresLeft.set(0)
            workers.shutdown()
            workers.awaitTermination(5_000)
            executor.shutdownNow()
        }
    }

    private fun timeout(block: () -> Unit) = assertTimeoutPreemptively(Duration.ofSeconds(30), block)

    @Test
    fun `待ち手の多い組がいても、ほかの組は1件ごとに順番が回ってくる`() = timeout {
        Fixture(1).use { f ->
            f.pool.acquire(a)
            for (i in 1..3) f.pool.submit("a$i", a, f.task("a$i"))
            f.pool.submit("b1", b, f.task("b1"))
            assertEquals(4, f.pool.waiting)
            f.pool.release(a)
            f.awaitRuns(1)
            for (i in 1..3) {
                // 動いた仕事の組に返す（a1 → b1 → a2 → a3）
                f.pool.release(if (f.order.last().startsWith("a")) a else b)
                f.awaitRuns(1)
            }
            assertEquals(listOf("a1", "b1", "a2", "a3"), f.order)
            f.pool.release(a)
            assertEquals(1, f.pool.availablePermits())
            assertEquals(0, f.pool.groupCount, "待ち手も貸し出しもない組は消える")
        }
    }

    @Test
    fun `組が1つだけなら、並んだ順に渡す`() = timeout {
        Fixture(1).use { f ->
            f.pool.acquire()
            for (i in 1..4) f.pool.submit("n$i", f.task("n$i"))
            repeat(4) {
                f.pool.release()
                f.awaitRuns(1)
            }
            assertEquals(listOf("n1", "n2", "n3", "n4"), f.order)
            f.pool.release()
            assertEquals(1, f.pool.availablePermits())
        }
    }

    @Test
    fun `上限の組は空きを取らず、その組の許可が返ったら同じreleaseの中で渡す`() = timeout {
        Fixture(2, perGroupLimit = 1).use { f ->
            f.pool.acquire(a)
            f.pool.submit("a1", a, f.task("a1"))
            // a は上限なので、許可が空いていても並ぶ
            assertEquals(1, f.pool.waiting)
            assertEquals(1, f.pool.availablePermits())
            // ほかの組は空きをすぐ取る
            f.pool.submit("b1", b, f.task("b1"))
            f.awaitRuns(1)
            assertEquals(listOf("b1"), f.order)
            assertEquals(0, f.pool.availablePermits())
            // a の許可が返ったら、a の待ち手に渡す（空きには戻さない）
            f.pool.release(a)
            f.awaitRuns(1)
            assertEquals(listOf("b1", "a1"), f.order)
            assertEquals(1, f.pool.heldBy(a))
            assertEquals(0, f.pool.availablePermits())
            f.pool.release(a)
            f.pool.release(b)
            assertEquals(2, f.pool.availablePermits())
            assertEquals(0, f.pool.groupCount)
        }
    }

    @Test
    fun `上限の組の許可を返したとき、ほかの組の待ち手があれば巡回の順に渡す`() = timeout {
        Fixture(1, perGroupLimit = 1).use { f ->
            f.pool.acquire(a)
            f.pool.submit("a1", a, f.task("a1"))
            f.pool.submit("b1", b, f.task("b1"))
            f.pool.release(a)
            f.awaitRuns(1)
            // a は上限だったので巡回の外にいた。b が先
            assertEquals(listOf("b1"), f.order)
            f.pool.release(b)
            f.awaitRuns(1)
            assertEquals(listOf("b1", "a1"), f.order)
            f.pool.release(a)
            assertEquals(1, f.pool.availablePermits())
        }
    }

    @Test
    fun `removeで外した組は巡回に残らず、表からも消える`() = timeout {
        Fixture(1).use { f ->
            f.pool.acquire(a)
            f.pool.submit("k", b, f.task("b"))
            assertTrue(f.pool.remove("k"))
            assertFalse(f.pool.remove("k"))
            assertEquals(0, f.pool.waiting)
            assertEquals(1, f.pool.groupCount, "貸し出し中の a だけが残る")
            f.pool.release(a)
            assertEquals(1, f.pool.availablePermits(), "外した仕事に許可を渡さない")
            assertEquals(0, f.pool.groupCount)
            assertTrue(f.order.isEmpty())
        }
    }

    @Test
    fun `待ちの途中で抜けた待ち手の許可は、借りた組から外して次へ渡す`() = timeout {
        Fixture(1).use { f ->
            val thread = Thread { }
            f.pool.leaveWaiterForTest(thread, granted = true, group = a)
            assertEquals(1, f.pool.heldBy(a))
            f.pool.submit("b1", b, f.task("b1"))
            assertTrue(f.pool.abandonWaiter(thread))
            f.awaitRuns(1)
            assertEquals(0, f.pool.heldBy(a))
            assertEquals(1, f.pool.heldBy(b))
            f.pool.release(b)
            assertEquals(1, f.pool.availablePermits())
            assertEquals(0, f.pool.groupCount)
        }
    }

    @Test
    fun `渡される前に抜けた待ち手は、組の列から外れる`() = timeout {
        Fixture(1).use { f ->
            f.pool.acquire(a)
            val thread = Thread { }
            f.pool.leaveWaiterForTest(thread, granted = false, group = b)
            assertEquals(1, f.pool.waiting)
            assertTrue(f.pool.abandonWaiter(thread))
            assertEquals(0, f.pool.waiting)
            f.pool.release(a)
            assertEquals(1, f.pool.availablePermits())
            assertEquals(0, f.pool.groupCount)
        }
    }

    @Test
    fun `スレッドの待ち手も組の巡回に入る`() = timeout {
        Fixture(1).use { f ->
            f.pool.acquire(a)
            f.pool.submit("a1", a, f.task("a1"))
            val got = Semaphore(0)
            val waiter = Thread {
                f.pool.acquire(b)
                f.order += "b-thread"
                got.release()
            }
            waiter.start()
            eventually(5_000, "b のスレッドが並ばない") { f.pool.waiting == 2 }
            f.pool.release(a)
            f.awaitRuns(1)
            assertEquals(listOf("a1"), f.order)
            f.pool.release(a)
            assertTrue(got.tryAcquire(5, TimeUnit.SECONDS))
            assertEquals(listOf("a1", "b-thread"), f.order)
            waiter.join(5_000)
            f.pool.release(b)
            assertEquals(1, f.pool.availablePermits())
        }
    }

    // 以下は ADR 0738「実装の規則」の組の扱いを、組を2つ以上・上限ありで確かめる（#898）。
    // 返す組を取り違えると、上限の組の待ち手が巡回に戻らずに止まり続けるので、許可を返す組と借りた組を別にして、取り違えたら落ちる形にする

    @Test
    fun `取り残した仕事の許可はその組に貸し出したまま数え、渡し直した仕事が返したら組の待ち手を巡回に戻す`() = timeout {
        val c = FairShareGroup.of(UUID(0, 3))
        Fixture(2, perGroupLimit = 1).use { f ->
            f.pool.acquire(b)
            f.pool.acquire(c)
            f.pool.submit("a1", a, f.task("a1"))
            assertEquals(1, f.pool.waiting)
            // b の許可を返すと a1 に渡るが、ワーカーに渡せずに取り残す（#870）
            f.executor.failuresLeft.set(1)
            f.pool.release(b)
            assertEquals(1, f.pool.strandedTasks)
            assertEquals(1, f.pool.heldBy(a), "取り残しの間も、渡した先の a に貸し出したまま")
            assertEquals(0, f.pool.heldBy(b), "返した b には数えない")
            assertEquals(0, f.pool.availablePermits())
            // 許可が空いても、a は上限のままなので a2 は並ぶ
            f.pool.release(c)
            assertEquals(1, f.pool.availablePermits())
            f.pool.submit("a2", a, f.task("a2"))
            assertEquals(1, f.pool.waiting)
            assertEquals(1, f.pool.availablePermits())
            // ほかの組が取って返しても、上限の a の待ち手には渡さない（a は巡回の外）
            f.pool.acquire(b)
            assertEquals(0, f.pool.availablePermits())
            f.pool.release(b)
            assertEquals(1, f.pool.waiting)
            assertEquals(1, f.pool.availablePermits())
            assertEquals(1, f.pool.heldBy(a))
            // 渡し直す（毎 tick）
            f.pool.tick(1)
            f.awaitRuns(1)
            assertEquals(listOf("a1"), f.order)
            assertEquals(0, f.pool.strandedTasks)
            assertEquals(1, f.pool.waiting, "a1 が返すまで a2 には渡さない")
            // 渡し直した a1 が a に返したら、a は同じ release の中で巡回に戻り、a2 に渡す
            f.pool.release(a)
            f.awaitRuns(1)
            assertEquals(listOf("a1", "a2"), f.order)
            assertEquals(1, f.pool.heldBy(a))
            assertEquals(0, f.pool.waiting)
            assertEquals(1, f.pool.availablePermits())
            f.pool.release(a)
            assertEquals(2, f.pool.availablePermits())
            assertEquals(0, f.pool.groupCount)
        }
    }

    @Test
    fun `submitOrStrandの直接の道で取り残した許可も、その組に貸し出したまま数える`() = timeout {
        Fixture(2, perGroupLimit = 1).use { f ->
            f.pool.acquire(b)
            f.executor.failuresLeft.set(1)
            // 許可を取ったその場でワーカーに渡せない。投げずに取り残す（#875）
            f.pool.submitOrStrand("a1", a, f.task("a1"))
            assertEquals(1, f.pool.strandedTasks)
            assertEquals(1, f.pool.heldBy(a), "取り残しの間も a に貸し出したまま")
            assertEquals(1, f.pool.heldBy(b))
            assertEquals(0, f.pool.availablePermits())
            // b の許可が返って空いても、上限の a の待ち手には渡さない
            f.pool.submitOrStrand("a2", a, f.task("a2"))
            assertEquals(1, f.pool.waiting)
            f.pool.release(b)
            assertEquals(1, f.pool.waiting)
            assertEquals(1, f.pool.availablePermits())
            assertEquals(1, f.pool.heldBy(a))
            // 渡し直した a1 が a に返したら、a2 に渡す
            f.pool.tick(1)
            f.awaitRuns(1)
            assertEquals(listOf("a1"), f.order)
            assertEquals(0, f.pool.strandedTasks)
            assertEquals(1, f.pool.waiting)
            f.pool.release(a)
            f.awaitRuns(1)
            assertEquals(listOf("a1", "a2"), f.order)
            assertEquals(1, f.pool.heldBy(a))
            assertEquals(1, f.pool.availablePermits())
            f.pool.release(a)
            assertEquals(2, f.pool.availablePermits())
            assertEquals(0, f.pool.groupCount)
        }
    }

    @Test
    fun `submitの直接の道でワーカーに渡せなかったら、取った組に返して投げる`() = timeout {
        Fixture(2, perGroupLimit = 1).use { f ->
            f.pool.acquire(b)
            f.executor.failuresLeft.set(1)
            assertThrows<OutOfMemoryError> { f.pool.submit("a1", a, f.task("a1")) }
            assertEquals(0, f.pool.heldBy(a), "取った a に返す（#629）")
            assertEquals(1, f.pool.heldBy(b), "ほかの組の数は変わらない")
            assertEquals(1, f.pool.availablePermits())
            assertEquals(0, f.pool.strandedTasks)
            assertEquals(1, f.pool.groupCount, "貸し出しの残る b だけが残る")
            // a は上限に達したまま残らず、次の仕事はすぐ取れる
            f.pool.submit("a2", a, f.task("a2"))
            f.awaitRuns(1)
            assertEquals(listOf("a2"), f.order)
            assertEquals(0, f.pool.waiting)
            assertEquals(1, f.pool.heldBy(a))
            assertEquals(0, f.pool.availablePermits())
            f.pool.release(a)
            f.pool.release(b)
            assertEquals(2, f.pool.availablePermits())
            assertEquals(0, f.pool.groupCount)
        }
    }

    @Test
    fun `acquireの待ちで渡された直後に割り込まれたら、借りた組から外して次へ渡す`() = timeout {
        Fixture(1, perGroupLimit = 1).use { f ->
            f.pool.acquire(b)
            val thrown = AtomicReference<Throwable?>()
            val waiter = Thread {
                try {
                    f.pool.acquire(a)
                    f.pool.release(a)
                } catch (t: Throwable) {
                    thrown.set(t)
                }
            }
            waiter.isDaemon = true
            waiter.start()
            eventually(5_000, "a のスレッドが並ばない") { f.pool.waiting == 1 }
            // a の列はスレッドの後ろに a2。b は上限なので、b1 は巡回の外で待つ
            f.pool.submit("a2", a, f.task("a2"))
            f.pool.submit("b1", b, f.task("b1"))
            assertEquals(3, f.pool.waiting)
            // 割り込まれてロックを取り直す前に、a のスレッドへ許可を渡す（a はそれで上限になり、巡回から外れる）。
            // AQS の await は、割り込みが先なら、渡されていても例外を投げる
            f.pool.withLockForTest {
                waiter.interrupt()
                eventually(5_000, "待ち手がロックを待たない") { f.pool.hasQueuedThreadForTest(waiter) }
                f.pool.release(b)
            }
            waiter.join(5_000)
            assertFalse(waiter.isAlive)
            assertTrue(thrown.get() is InterruptedException, "${thrown.get()}")
            // 渡された許可は a から外して次へ。巡回の順は b（release で戻った）→ a（割り込みで戻った）
            f.awaitRuns(1)
            assertEquals(listOf("b1"), f.order)
            assertEquals(0, f.pool.heldBy(a), "受け取らなかった許可を a から外す")
            assertEquals(1, f.pool.heldBy(b))
            assertEquals(1, f.pool.waiting)
            // a は巡回に戻っているので、b1 が返したら a2 に渡す
            f.pool.release(b)
            f.awaitRuns(1)
            assertEquals(listOf("b1", "a2"), f.order)
            assertEquals(1, f.pool.heldBy(a))
            assertEquals(0, f.pool.heldBy(b))
            f.pool.release(a)
            assertEquals(1, f.pool.availablePermits())
            assertEquals(0, f.pool.groupCount)
        }
    }

    @Test
    fun `acquireの待ちで渡される前に割り込まれたら、組の列から外れ、後ろの待ち手は巡回に残る`() = timeout {
        Fixture(1, perGroupLimit = 1).use { f ->
            f.pool.acquire(b)
            val thrown = AtomicReference<Throwable?>()
            val waiter = Thread {
                try {
                    f.pool.acquire(a)
                    f.pool.release(a)
                } catch (t: Throwable) {
                    thrown.set(t)
                }
            }
            waiter.isDaemon = true
            waiter.start()
            eventually(5_000, "a のスレッドが並ばない") { f.pool.waiting == 1 }
            f.pool.submit("a2", a, f.task("a2"))
            assertEquals(2, f.pool.waiting)
            waiter.interrupt()
            waiter.join(5_000)
            assertFalse(waiter.isAlive)
            assertTrue(thrown.get() is InterruptedException, "${thrown.get()}")
            assertEquals(1, f.pool.waiting, "スレッドだけが外れる")
            assertEquals(0, f.pool.heldBy(a))
            // a は巡回に残っているので、b が返したら a2 に渡す
            f.pool.release(b)
            f.awaitRuns(1)
            assertEquals(listOf("a2"), f.order)
            assertEquals(1, f.pool.heldBy(a))
            assertEquals(0, f.pool.availablePermits())
            f.pool.release(a)
            assertEquals(1, f.pool.availablePermits())
            assertEquals(0, f.pool.groupCount)
        }
    }
}
