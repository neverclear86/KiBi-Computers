package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.sched.PermitPool
import pro.ikili.kibicomputers.core.kernel.sched.WorkerPool
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 許可を次へ渡した後に、ワーカーへ渡せなかったとき（#870）。許可は取り残しの列で持ったまま、後で渡し直す */
internal class PermitHandOffFailureTest {
    /** 指定した回数だけ `execute` を失敗させるワーカーの置き場（`unable to create native thread` を再現する） */
    private class FailingExecutor(private val delegate: ExecutorService) : ExecutorService by delegate {
        val failuresLeft = AtomicInteger()

        @Volatile
        var alwaysFail = false

        override fun execute(command: Runnable) {
            if (alwaysFail || failuresLeft.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                throw OutOfMemoryError("unable to create native thread")
            }
            delegate.execute(command)
        }
    }

    private class Fixture(permits: Int) : AutoCloseable {
        val executor = FailingExecutor(Executors.newCachedThreadPool { r -> KernelThreadsProbe.thread(r) })
        val workers = WorkerPool(1L shl 20, 1_000, executor)
        val pool = PermitPool(permits, workers)
        val order: MutableList<String> = Collections.synchronizedList(ArrayList())

        /** 動いたら名前を残し、許可を返す仕事 */
        fun task(name: String, done: CountDownLatch): Runnable = Runnable {
            order += name
            pool.release()
            done.countDown()
        }

        override fun close() {
            executor.alwaysFail = false
            workers.shutdown()
            workers.awaitTermination(5_000)
        }
    }

    private fun waitUntil(message: String, condition: () -> Boolean) = eventually(5_000, message, condition)

    @Test
    fun `releaseで次の仕事を渡せなくても投げず、渡し直すと後ろの仕事まで進んで許可が戻る`() = timeout(Duration.ofSeconds(30)) {
        Fixture(1).use { f ->
            val done = CountDownLatch(2)
            f.pool.acquire()
            f.pool.submit("a", f.task("a", done))
            f.pool.submit("b", f.task("b", done))
            assertEquals(2, f.pool.waiting)
            f.executor.failuresLeft.set(1)
            // 投げない（呼び手の CPU・登録の後始末を飛ばさない）
            f.pool.release()
            assertEquals(1, f.pool.strandedTasks, "許可を持ったまま取り残しの列に入る")
            assertEquals(0, f.pool.availablePermits(), "許可は取り残した仕事が持っている")
            assertEquals(1, f.pool.waiting)
            assertEquals(1L, f.pool.handOffFailures.get())
            f.pool.retryStranded()
            assertTrue(done.await(5, TimeUnit.SECONDS), "後ろの仕事が進まない")
            assertEquals(listOf("a", "b"), f.order.toList(), "順番を保つ")
            waitUntil("許可が戻らない") { f.pool.availablePermits() == 1 }
            assertEquals(0, f.pool.waiting)
            assertEquals(0, f.pool.strandedTasks)
        }
    }

    @Test
    fun `渡し直しも失敗したら先頭に戻して打ち切り、次のきっかけで渡す`() = timeout(Duration.ofSeconds(30)) {
        Fixture(1).use { f ->
            val done = CountDownLatch(1)
            f.pool.acquire()
            f.pool.submit("a", f.task("a", done))
            f.executor.failuresLeft.set(2)
            f.pool.release()
            f.pool.retryStranded()
            assertEquals(1, f.pool.strandedTasks, "渡せなければ戻す")
            assertEquals(2L, f.pool.handOffFailures.get())
            f.pool.retryStranded()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            waitUntil("許可が戻らない") { f.pool.availablePermits() == 1 }
            assertEquals(0, f.pool.strandedTasks)
        }
    }

    @Test
    fun `渡し終えた待ち手を外すときに次の仕事を渡せなくても投げず、後ろの仕事まで進む`() = timeout(Duration.ofSeconds(30)) {
        Fixture(1).use { f ->
            val done = CountDownLatch(2)
            val gone = Thread {}
            f.pool.leaveWaiterForTest(gone, granted = true)
            f.pool.submit("a", f.task("a", done))
            f.pool.submit("b", f.task("b", done))
            f.executor.failuresLeft.set(1)
            assertTrue(f.pool.abandonWaiter(gone))
            assertEquals(1, f.pool.strandedTasks)
            f.pool.retryStranded()
            assertTrue(done.await(5, TimeUnit.SECONDS), "後ろの仕事が進まない")
            assertEquals(listOf("a", "b"), f.order.toList())
            waitUntil("許可が戻らない") { f.pool.availablePermits() == 1 }
            assertEquals(0, f.pool.waiting)
            assertEquals(0, f.pool.strandedTasks)
        }
    }

    @Test
    fun `許可を受け取った直後に割り込まれて次へ渡すとき、渡せなくても割り込みの例外をそのまま投げる`() = timeout(Duration.ofSeconds(30)) {
        Fixture(1).use { f ->
            val done = CountDownLatch(1)
            f.pool.acquire()
            val thrown = AtomicReference<Throwable?>()
            val waiter = Thread {
                try {
                    f.pool.acquire()
                    f.pool.release()
                } catch (t: Throwable) {
                    thrown.set(t)
                }
            }
            waiter.isDaemon = true
            waiter.start()
            waitUntil("待ち手が並ばない") { f.pool.waiting == 1 }
            f.pool.submit("a", f.task("a", done))
            assertEquals(2, f.pool.waiting)
            f.executor.failuresLeft.set(1)
            // 割り込まれてロックを取り直す前に、許可を渡す（AQS の await は、割り込みが先なら、渡されていても例外を投げる）
            f.pool.withLockForTest {
                waiter.interrupt()
                waitUntil("待ち手がロックを待たない") { f.pool.hasQueuedThreadForTest(waiter) }
                f.pool.release()
            }
            waiter.join(5_000)
            assertTrue(thrown.get() is InterruptedException, "${thrown.get()}")
            assertEquals(1, f.pool.strandedTasks, "次の仕事は許可を持ったまま取り残しの列に入る")
            f.pool.retryStranded()
            assertTrue(done.await(5, TimeUnit.SECONDS), "後ろの仕事が進まない")
            waitUntil("許可が戻らない") { f.pool.availablePermits() == 1 }
            assertEquals(0, f.pool.waiting)
            assertEquals(0, f.pool.strandedTasks)
        }
    }

    @Test
    fun `例外で抜けたワーカーの続きを渡せなくても捨てず、渡し直すと進む`() = timeout(Duration.ofSeconds(30)) {
        Fixture(1).use { f ->
            val done = CountDownLatch(1)
            val ranA = CountDownLatch(1)
            f.pool.submit("a") {
                f.pool.submit("b", f.task("b", done))
                // b を今のワーカーの続きにしてから、例外で抜ける。続きを渡し直すところで失敗させる
                f.pool.release(inline = true)
                f.executor.failuresLeft.set(1)
                ranA.countDown()
                throw IllegalStateException("テストの失敗")
            }
            assertTrue(ranA.await(5, TimeUnit.SECONDS))
            waitUntil("続きが取り残しの列に入らない") { f.pool.strandedTasks == 1 }
            assertEquals(0, f.pool.availablePermits())
            f.pool.retryStranded()
            assertTrue(done.await(5, TimeUnit.SECONDS), "続きが進まない")
            waitUntil("許可が戻らない") { f.pool.availablePermits() == 1 }
            assertEquals(0, f.pool.strandedTasks)
        }
    }

    @Test
    fun `渡せなかった数は渡せたものだけを数える`() = timeout(Duration.ofSeconds(30)) {
        Fixture(1).use { f ->
            val done = CountDownLatch(1)
            f.pool.acquire()
            f.pool.submit("a", f.task("a", done))
            val before = f.workers.submitted.get()
            f.executor.failuresLeft.set(1)
            f.pool.release()
            assertEquals(before, f.workers.submitted.get(), "渡せなかったものは数えない")
            f.pool.retryStranded()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(before + 1, f.workers.submitted.get(), "渡し直しで1回だけ数える")
        }
    }

    // ---- マシンの段 ----

    private fun oneMachineConfig() = SchedulerConfig.builder().maxConcurrentMachines(1).maxBlockingProcesses(64).build()

    @Test
    fun `許可1つで次の台のstepを渡せなくても、tickだけで進んで許可が戻る`() = timeout(Duration.ofSeconds(30)) {
        val executor = FailingExecutor(Executors.newCachedThreadPool { r -> KernelThreadsProbe.thread(r) })
        Harness(schedulerConfig = oneMachineConfig(), executor = executor).use { h ->
            val permits = h.scheduler.permits
            val m1 = h.machine()
            val m2 = h.machine()
            val program = h.program(emptyModule())
            val entered = CountDownLatch(1)
            val hold = CountDownLatch(1)
            // 専用スレッドで許可を持って止まる（返すときは、ワーカーの続きにできないので execute で渡す）
            val holder = m1.spawnTest(
                program,
                doing {
                    entered.countDown()
                    check(hold.await(10, TimeUnit.SECONDS))
                },
            )
            h.tickUntilTrue("許可を持たない") { entered.count == 0L && permits.availablePermits() == 0 }
            val waiter = m2.spawnTest(program, exiting(0, ProcessKind.WAIT_POINT))
            h.tickUntilTrue("許可を待たない") { permits.waiting == 1 }
            executor.failuresLeft.set(1)
            hold.countDown()
            h.tickUntilTrue("終わらない") {
                m1.processState(holder)?.isTerminal == true && m2.processState(waiter)?.isTerminal == true
            }
            assertEquals(ProcessState.EXITED, m2.processState(waiter))
            h.tickUntilTrue("許可が戻らない") { permits.availablePermits() == 1 }
            assertEquals(0, permits.strandedTasks)
            assertTrue(permits.handOffFailures.get() >= 1, "渡せなかった")
            assertEquals(0, executor.failuresLeft.get())
        }
    }

    @Test
    fun `取り残されている間に止めると、KILLEDになり、許可は1回だけ返る`() = timeout(Duration.ofSeconds(30)) {
        val executor = FailingExecutor(Executors.newCachedThreadPool { r -> KernelThreadsProbe.thread(r) })
        Harness(schedulerConfig = oneMachineConfig(), executor = executor).use { h ->
            val permits = h.scheduler.permits
            val m1 = h.machine()
            val m2 = h.machine()
            val program = h.program(emptyModule())
            val entered = CountDownLatch(1)
            val hold = CountDownLatch(1)
            val holder = m1.spawnTest(
                program,
                doing {
                    entered.countDown()
                    check(hold.await(10, TimeUnit.SECONDS))
                },
            )
            h.tickUntilTrue("許可を持たない") { entered.count == 0L && permits.availablePermits() == 0 }
            val waiter = m2.spawnTest(program, exiting(0, ProcessKind.WAIT_POINT))
            h.tickUntilTrue("許可を待たない") { permits.waiting == 1 }
            executor.alwaysFail = true
            hold.countDown()
            h.tickUntilTrue("取り残しの列に入らない") { m1.processState(holder)?.isTerminal == true && permits.strandedTasks == 1 }
            assertEquals(0, permits.availablePermits(), "許可は取り残した step が持っている")
            m2.stop()
            h.tickUntilTrue("KILLED にならない") { m2.processState(waiter) == ProcessState.KILLED }
            assertTrue(m2.awaitTermination(5_000), "止まり切らない")
            executor.alwaysFail = false
            h.tickUntilTrue("許可が戻らない") { permits.strandedTasks == 0 && permits.availablePermits() == 1 }
            // 動き出した step は止められていると知って許可を返すだけ。二重に返らない
            repeat(20) { h.scheduler.tick() }
            Thread.sleep(50)
            assertEquals(1, permits.availablePermits(), "許可は1回だけ返る")
        }
    }
}
