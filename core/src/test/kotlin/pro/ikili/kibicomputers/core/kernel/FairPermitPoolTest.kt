package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import pro.ikili.kibicomputers.core.kernel.sched.FairShareGroup
import pro.ikili.kibicomputers.core.kernel.sched.PermitPool
import pro.ikili.kibicomputers.core.kernel.sched.WorkerPool
import java.time.Duration
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** 許可を公平の組（持ち主）ごとに公平に渡す（#738、ADR 0738） */
class FairPermitPoolTest {
    private val a = FairShareGroup.of(UUID(0, 1))
    private val b = FairShareGroup.of(UUID(0, 2))

    private class Fixture(permits: Int, perGroupLimit: Int = Int.MAX_VALUE) : AutoCloseable {
        val executor: ExecutorService = Executors.newCachedThreadPool { r -> KernelThreadsProbe.thread(r) }
        val workers = WorkerPool(1L shl 20, 1_000, executor)
        val pool = PermitPool(permits, workers, perGroupLimit = perGroupLimit)
        val order: MutableList<String> = Collections.synchronizedList(ArrayList())
        val ran = Semaphore(0)

        /** 動いたら名前を残すだけの仕事（許可はテストが返す） */
        fun task(name: String): Runnable = Runnable {
            order += name
            ran.release()
        }

        fun awaitRuns(n: Int) = assertTrue(ran.tryAcquire(n, 5, TimeUnit.SECONDS), "仕事が動かない: $order")

        override fun close() {
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
}
