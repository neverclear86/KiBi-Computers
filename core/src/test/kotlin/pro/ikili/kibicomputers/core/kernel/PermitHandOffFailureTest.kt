package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.fail
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.sched.FairShareGroup
import pro.ikili.kibicomputers.core.kernel.sched.PermitPool
import pro.ikili.kibicomputers.core.kernel.sched.WorkerPool
import java.time.Duration
import java.util.Collections
import java.util.ResourceBundle
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 許可を次へ渡した後に、ワーカーへ渡せなかったとき（#870）。許可は取り残しの列で持ったまま、後で渡し直す */
internal class PermitHandOffFailureTest {
    /** 出したものを拾う [System.Logger] */
    private class RecordingLogger : System.Logger {
        data class Line(val level: System.Logger.Level, val message: String, val thrown: Throwable?)

        val lines: MutableList<Line> = Collections.synchronizedList(ArrayList())

        override fun getName(): String = "test"

        override fun isLoggable(level: System.Logger.Level): Boolean = true

        override fun log(level: System.Logger.Level, bundle: ResourceBundle?, msg: String?, thrown: Throwable?) {
            lines += Line(level, msg ?: "", thrown)
        }

        // 既定の log(level, msg) は params に null の配列を渡して、下の vararg の Kotlin の実装が NPE になるので、ここで受ける
        override fun log(level: System.Logger.Level, msg: String?) {
            lines += Line(level, msg ?: "", null)
        }

        override fun log(level: System.Logger.Level, bundle: ResourceBundle?, format: String?, vararg params: Any?) {
            lines += Line(level, format ?: "", null)
        }

        fun of(level: System.Logger.Level): List<Line> = lines.toList().filter { it.level == level }
    }

    /** [single] なら、ワーカーは1本だけ（渡した順に動く） */
    private class Fixture(
        permits: Int,
        single: Boolean = false,
        summaryIntervalTicks: Long = 1200,
        val log: RecordingLogger = RecordingLogger(),
    ) : AutoCloseable {
        val executor = FailingExecutor(
            if (single) {
                Executors.newSingleThreadExecutor { r -> KernelThreadsProbe.thread(r) }
            } else {
                Executors.newCachedThreadPool { r -> KernelThreadsProbe.thread(r) }
            },
        )
        val workers = WorkerPool(1L shl 20, 1_000, executor)
        val pool = PermitPool(permits, workers, summaryIntervalTicks, log)
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
            // submit の直接の道は、渡した後に retryStranded を呼ぶ。それが終わるまで a を動かさない
            // （先に a が抜けて b を取り残しの列に入れると、その retryStranded が b を渡し直してしまう）
            val submitted = CountDownLatch(1)
            f.pool.submit("a") {
                check(submitted.await(5, TimeUnit.SECONDS))
                f.pool.submit("b", f.task("b", done))
                // b を今のワーカーの続きにしてから、例外で抜ける。続きを渡し直すところで失敗させる
                f.pool.release(inline = true)
                f.executor.failuresLeft.set(1)
                ranA.countDown()
                throw IllegalStateException("テストの失敗")
            }
            submitted.countDown()
            assertTrue(ranA.await(5, TimeUnit.SECONDS))
            waitUntil("続きが取り残しの列に入らない") { f.pool.strandedTasks == 1 }
            assertEquals(0, f.pool.availablePermits())
            f.pool.retryStranded()
            assertTrue(done.await(5, TimeUnit.SECONDS), "続きが進まない")
            waitUntil("許可が戻らない") { f.pool.availablePermits() == 1 }
            assertEquals(0, f.pool.strandedTasks)
            // a と b の2つ。続きとして置いた時と、渡し直せた時で、b を二重に数えない
            waitUntil("数えない") { f.workers.submitted.get() >= 2L }
            Thread.sleep(50)
            assertEquals(2L, f.workers.submitted.get(), "続きを渡し直しても1回だけ数える")
        }
    }

    @Test
    fun `例外で抜けたワーカーの続きを、すぐ渡せたときも1回だけ数える`() = timeout(Duration.ofSeconds(30)) {
        Fixture(1).use { f ->
            val done = CountDownLatch(1)
            f.pool.submit("a") {
                f.pool.submit("b", f.task("b", done))
                f.pool.release(inline = true)
                throw IllegalStateException("テストの失敗")
            }
            assertTrue(done.await(5, TimeUnit.SECONDS), "続きが進まない")
            waitUntil("許可が戻らない") { f.pool.availablePermits() == 1 }
            waitUntil("数えない") { f.workers.submitted.get() >= 2L }
            Thread.sleep(50)
            assertEquals(2L, f.workers.submitted.get(), "a と b を1回ずつ")
        }
    }

    @Test
    fun `ワーカーの続きにした仕事も1回だけ数える`() = timeout(Duration.ofSeconds(30)) {
        Fixture(1).use { f ->
            val done = CountDownLatch(1)
            f.pool.submit("a") {
                f.pool.submit("b", f.task("b", done))
                f.pool.release(inline = true)
            }
            assertTrue(done.await(5, TimeUnit.SECONDS), "続きが進まない")
            assertEquals(2L, f.workers.submitted.get(), "a と b を1回ずつ")
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

    @Test
    fun `releaseで次の仕事を渡せたら、取り残していた仕事も渡し直す（tickを待たない）`() = timeout(Duration.ofSeconds(30)) {
        Fixture(2).use { f ->
            val done = CountDownLatch(2)
            f.pool.acquire()
            f.pool.acquire()
            f.pool.submit("a", f.task("a", done))
            f.pool.submit("b", f.task("b", done))
            f.executor.failuresLeft.set(1)
            f.pool.release()
            assertEquals(1, f.pool.strandedTasks, "a は取り残される")
            // b は渡せる。そのときに a も渡し直される（retryStranded も tick も呼ばない）
            f.pool.release()
            assertTrue(done.await(5, TimeUnit.SECONDS), "取り残しが渡し直されない")
            assertEquals(0, f.pool.strandedTasks)
            waitUntil("許可が戻らない") { f.pool.availablePermits() == 2 }
        }
    }

    @Test
    fun `submitでワーカーに渡せたら、取り残していた仕事も渡し直す（tickを待たない）`() = timeout(Duration.ofSeconds(30)) {
        Fixture(2).use { f ->
            val done = CountDownLatch(1)
            f.pool.acquire()
            f.pool.acquire()
            f.pool.submit("a", f.task("a", done))
            // release の渡しを失敗させ、a を取り残しの列に入れる
            f.executor.failuresLeft.set(1)
            f.pool.release()
            assertEquals(1, f.pool.strandedTasks)
            // 次の release は許可を空きに戻すだけ（ワーカーに渡していない）。渡し直しを試みない（失敗を積まない）
            f.executor.alwaysFail = true
            f.pool.release()
            f.executor.alwaysFail = false
            assertEquals(1, f.pool.strandedTasks, "a は取り残されたまま")
            assertEquals(1, f.pool.availablePermits())
            assertEquals(1L, f.pool.handOffFailures.get(), "許可を空きに戻しただけの release は渡し直さない")
            assertEquals(0, f.executor.failuresLeft.get())
            // 空いた許可で直接渡す。渡せたら a も渡し直される（retryStranded も tick も呼ばない）
            // b は許可を持ったまま止める（b が許可を返す release が渡し直すのではなく、submit が渡し直すことを確かめる）
            val gate = CountDownLatch(1)
            f.pool.submit("b") {
                f.order += "b"
                gate.await(10, TimeUnit.SECONDS)
                f.pool.release()
            }
            assertTrue(done.await(5, TimeUnit.SECONDS), "取り残しが渡し直されない")
            assertEquals(0, f.pool.strandedTasks)
            assertEquals(1, f.order.count { it == "a" })
            gate.countDown()
            waitUntil("許可が戻らない") { f.pool.availablePermits() == 2 }
        }
    }

    @Test
    fun `submitOrStrandは直接渡せなくても投げず、許可を持たせたまま取り残し、その場では渡し直さない`() = timeout(Duration.ofSeconds(30)) {
        Fixture(1).use { f ->
            val group = FairShareGroup.of(UUID(0, 1))
            val done = CountDownLatch(1)
            val task = Runnable {
                f.order += "a"
                f.pool.release(group)
                done.countDown()
            }
            // 1回だけ失敗させる。失敗の直後に渡し直していれば、2回目の execute が通って取り残しが消える
            f.executor.failuresLeft.set(1)
            assertDoesNotThrow { f.pool.submitOrStrand("a", group, task) }
            assertEquals(1, f.pool.strandedTasks, "許可を持ったまま取り残しの列に入る")
            assertEquals(0, f.pool.availablePermits(), "取った許可は返さない（取り残した仕事が持つ）")
            assertEquals(1, f.pool.heldBy(group), "借りた組の貸し出しに残る")
            assertEquals(0, f.pool.waiting)
            assertEquals(1L, f.pool.handOffFailures.get())
            assertTrue(f.order.isEmpty(), "失敗の直後には渡し直さない")
            // tick で渡し直すと動き、借りた組に許可が戻る
            f.pool.tick(1)
            assertTrue(done.await(5, TimeUnit.SECONDS), "tick で渡し直されない")
            waitUntil("許可が戻らない") { f.pool.availablePermits() == 1 }
            assertEquals(0, f.pool.heldBy(group))
            assertEquals(0, f.pool.strandedTasks)
            assertEquals(listOf("a"), f.order.toList(), "1回だけ動く")
        }
    }

    @Test
    fun `submitOrStrandでワーカーに渡せたら、取り残していた仕事も渡し直す（tickを待たない）`() = timeout(Duration.ofSeconds(30)) {
        Fixture(2).use { f ->
            val done = CountDownLatch(1)
            f.pool.acquire()
            f.pool.acquire()
            f.pool.submit("a", f.task("a", done))
            // release の渡しを失敗させ、a を取り残しの列に入れる
            f.executor.failuresLeft.set(1)
            f.pool.release()
            assertEquals(1, f.pool.strandedTasks)
            // 許可を空きに戻すだけの release（渡し直しは試みない）
            f.pool.release()
            assertEquals(1, f.pool.strandedTasks, "a は取り残されたまま")
            assertEquals(1, f.pool.availablePermits())
            // 空いた許可で直接渡す。渡せたら a も渡し直される（retryStranded も tick も呼ばない）
            // b は許可を持ったまま止める（b が許可を返す release ではなく、submitOrStrand が渡し直すことを確かめる）
            val gate = CountDownLatch(1)
            f.pool.submitOrStrand("b", FairShareGroup.NONE) {
                f.order += "b"
                gate.await(10, TimeUnit.SECONDS)
                f.pool.release()
            }
            assertTrue(done.await(5, TimeUnit.SECONDS), "取り残しが渡し直されない")
            assertEquals(0, f.pool.strandedTasks)
            assertEquals(1, f.order.count { it == "a" })
            gate.countDown()
            waitUntil("許可が戻らない") { f.pool.availablePermits() == 2 }
        }
    }

    @Test
    fun `取り残しが複数あっても、前から順に渡し直す（失敗したら先頭に戻す）`() = timeout(Duration.ofSeconds(30)) {
        Fixture(3, single = true).use { f ->
            val done = CountDownLatch(3)
            repeat(3) { f.pool.acquire() }
            f.pool.submit("a", f.task("a", done))
            f.pool.submit("b", f.task("b", done))
            f.pool.submit("c", f.task("c", done))
            // release ごとに次の仕事が渡せず、a・b・c の順に取り残しの列へ入る
            f.executor.failuresLeft.set(3)
            repeat(3) { f.pool.release() }
            assertEquals(3, f.pool.strandedTasks)
            assertEquals(3L, f.pool.handOffFailures.get())
            // 先頭の a が渡せなければ、a を先頭に戻して打ち切る（b・c を先に渡さない）
            f.executor.failuresLeft.set(1)
            f.pool.retryStranded()
            assertEquals(3, f.pool.strandedTasks, "渡せなければ戻す")
            assertEquals(4L, f.pool.handOffFailures.get())
            assertEquals(emptyList<String>(), f.order.toList())
            // 1回の呼び出しで、残りを前から渡し切る
            f.pool.retryStranded()
            assertEquals(0, f.pool.strandedTasks)
            assertTrue(done.await(5, TimeUnit.SECONDS), "全部進まない")
            assertEquals(listOf("a", "b", "c"), f.order.toList(), "先に取り残したものが先に動く")
            waitUntil("許可が戻らない") { f.pool.availablePermits() == 3 }
        }
    }

    // ---- 運用の側のログ ----

    @Test
    fun `取り残しの始まりに1回、続く間は要約、復旧で1回、再発でまたスタックトレース付きで出す`() = timeout(Duration.ofSeconds(30)) {
        Fixture(1, summaryIntervalTicks = 100).use { f ->
            val log = f.log
            val done = CountDownLatch(2)
            f.pool.acquire()
            f.pool.submit("a", f.task("a", done))
            f.pool.tick(1_000)
            f.executor.alwaysFail = true
            f.pool.release()
            val warnings = log.of(System.Logger.Level.WARNING)
            assertEquals(1, warnings.size, "始まりに1回")
            assertTrue(warnings[0].thrown is OutOfMemoryError, "スタックトレース付き")
            // 続く間: 毎 tick の渡し直しの失敗はログに出さず数える。間が過ぎるまで要約も出ない
            f.pool.tick(1_050)
            f.pool.tick(1_099)
            assertEquals(1, log.of(System.Logger.Level.WARNING).size)
            assertEquals(3L, f.pool.handOffFailures.get())
            f.pool.tick(1_100)
            val summary = log.of(System.Logger.Level.WARNING)
            assertEquals(2, summary.size, "間が過ぎたら要約")
            assertEquals(null, summary[1].thrown)
            assertTrue("取り残し 1 件" in summary[1].message && "4 回" in summary[1].message, summary[1].message)
            f.pool.tick(1_150)
            assertEquals(2, log.of(System.Logger.Level.WARNING).size, "要約の間は繰り返さない")
            // 復旧
            f.executor.alwaysFail = false
            f.pool.tick(1_151)
            assertEquals(0, f.pool.strandedTasks)
            assertEquals(1, log.of(System.Logger.Level.INFO).size, "復旧で1回")
            f.pool.tick(2_000)
            assertEquals(1, log.of(System.Logger.Level.INFO).size)
            assertEquals(2, log.of(System.Logger.Level.WARNING).size, "復旧後は要約を出さない")
            // 再発
            waitUntil("許可が戻らない") { f.pool.availablePermits() == 1 }
            f.pool.acquire()
            f.pool.submit("b", f.task("b", done))
            f.executor.failuresLeft.set(1)
            f.pool.release()
            val again = log.of(System.Logger.Level.WARNING)
            assertEquals(3, again.size, "再発はもう一度スタックトレース付きで出す")
            assertTrue(again[2].thrown is OutOfMemoryError)
            f.pool.retryStranded()
            assertTrue(done.await(5, TimeUnit.SECONDS))
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

    // ---- CPU の中からの受け渡し（#875） ----

    /** tick を進めずに待つ（取り残しが tick で渡し直される前に確かめる） */
    private fun waitWithoutTick(message: String, condition: () -> Boolean) = eventually(10_000, message) {
        Thread.sleep(1)
        condition()
    }

    /** 同じ台で、[release] を待ってから終わる待機点型の A と、CPU の列に並んだ B を作る */
    private class CpuHandOff(h: Harness) {
        val m = h.machine()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val program = h.program(emptyModule())
        val a = m.spawnTest(
            program,
            TestDriver(ProcessKind.WAIT_POINT) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                StepResult.Exit(0)
            },
        )
        var b = 0

        fun spawnWaiter(h: Harness) {
            h.tickUntilTrue("A が step に入らない") { entered.count == 0L }
            b = m.spawnTest(program, exiting(0, ProcessKind.WAIT_POINT))
            waitWithoutTick("B が CPU の列に並ばない") { m.cpu.waiting == 1 }
        }

        private fun waitWithoutTick(message: String, condition: () -> Boolean) = eventually(10_000, message) {
            Thread.sleep(1)
            condition()
        }
    }

    @Test
    fun `CPUを次のプロセスへ渡すときにワーカーへ渡せなくても投げず、tickだけで進んでCPUと許可が戻る`() = timeout(Duration.ofSeconds(30)) {
        val executor = FailingExecutor(Executors.newCachedThreadPool { r -> KernelThreadsProbe.thread(r) })
        Harness(executor = executor).use { h ->
            val permits = h.scheduler.permits
            val total = permits.availablePermits()
            assertTrue(total >= 2, "許可が空いている状態にする")
            val f = CpuHandOff(h)
            val group = f.m.fairShareGroup
            f.spawnWaiter(h)
            executor.failuresLeft.set(1)
            f.release.countDown()
            // tick を回す前: CPU の受け渡しで取り残された（A のワーカーに例外が漏れず、A は終わる）
            waitWithoutTick("取り残しの列に入らない") { permits.strandedTasks == 1 }
            waitWithoutTick("A が終わらない") { f.m.processState(f.a)?.isTerminal == true }
            assertEquals(ProcessState.EXITED, f.m.processState(f.a))
            assertEquals(1L, permits.handOffFailures.get(), "CPU の受け渡しで1回だけ失敗する")
            assertEquals(f.b, f.m.cpu.currentOwner?.pid, "CPU は取り残した B が持ち主のまま")
            assertTrue(f.m.state != MachineState.CRASHED, "マシンが落ちた")
            assertEquals(1, permits.heldBy(group), "取り残した B は許可を持ったまま（#738）")
            // tick だけで進む
            h.tickUntilTrue("B が終わらない") { f.m.processState(f.b)?.isTerminal == true }
            assertEquals(ProcessState.EXITED, f.m.processState(f.b))
            h.tickUntilTrue("CPU と許可が戻らない") {
                f.m.cpu.currentOwner == null && permits.availablePermits() == total && permits.heldBy(group) == 0
            }
            assertEquals(0, permits.strandedTasks)
            assertEquals(1L, permits.handOffFailures.get())
            assertEquals(0, executor.failuresLeft.get())
            f.m.stop()
        }
    }

    @Test
    fun `CPUの受け渡しで取り残されている間に止めると、KILLEDになり、CPUと許可は1回だけ返る`() = timeout(Duration.ofSeconds(30)) {
        val executor = FailingExecutor(Executors.newCachedThreadPool { r -> KernelThreadsProbe.thread(r) })
        Harness(executor = executor).use { h ->
            val permits = h.scheduler.permits
            val total = permits.availablePermits()
            val f = CpuHandOff(h)
            f.spawnWaiter(h)
            executor.alwaysFail = true
            f.release.countDown()
            waitWithoutTick("取り残しの列に入らない") { permits.strandedTasks == 1 && f.m.processState(f.a)?.isTerminal == true }
            assertEquals(f.b, f.m.cpu.currentOwner?.pid)
            f.m.stop()
            h.tickUntilTrue("KILLED にならない") { f.m.processState(f.b) == ProcessState.KILLED }
            assertTrue(f.m.awaitTermination(5_000), "止まり切らない")
            assertNull(f.m.cpu.currentOwner, "止めたら CPU は次へ渡る")
            executor.alwaysFail = false
            h.tickUntilTrue("許可が戻らない") { permits.strandedTasks == 0 && permits.availablePermits() == total }
            // 動き出した step は止められていると知って許可を返すだけ。二重に返らない
            repeat(20) { h.scheduler.tick() }
            Thread.sleep(50)
            assertEquals(total, permits.availablePermits(), "許可は1回だけ返る")
            assertNull(f.m.cpu.currentOwner)
        }
    }

    @Test
    fun `起こすときにワーカーへ渡せなくても投げず、tickだけで進んでCPUと許可が戻る`() = timeout(Duration.ofSeconds(30)) {
        val executor = FailingExecutor(Executors.newCachedThreadPool { r -> KernelThreadsProbe.thread(r) })
        Harness(executor = executor).use { h ->
            val permits = h.scheduler.permits
            val total = permits.availablePermits()
            val m = h.machine()
            val gate = Gate(m)
            val pid = m.spawnTest(h.program(emptyModule()), gated(gate, kind = ProcessKind.WAIT_POINT))
            h.tickUntilTrue("イベント待ちにならない") {
                m.processState(pid) == ProcessState.WAITING_EVENT && m.cpu.currentOwner == null && permits.availablePermits() == total
            }
            executor.failuresLeft.set(1)
            assertDoesNotThrow { gate.open() }
            assertDoesNotThrow {
                h.tickUntilTrue("終わらない") { m.processState(pid)?.isTerminal == true }
            }
            assertEquals(ProcessState.EXITED, m.processState(pid))
            assertEquals(1L, permits.handOffFailures.get())
            h.tickUntilTrue("CPU と許可が戻らない") { m.cpu.currentOwner == null && permits.availablePermits() == total }
            assertEquals(0, permits.strandedTasks)
            m.stop()
        }
    }


    @Test
    fun `stepの途中で起こされ、登録を外すときにワーカーへ渡せなくても、登録の後始末まで進み、tickだけで進んで戻る`() = timeout(Duration.ofSeconds(30)) {
        val executor = FailingExecutor(Executors.newCachedThreadPool { r -> KernelThreadsProbe.thread(r) })
        Harness(executor = executor).use { h ->
            val permits = h.scheduler.permits
            val total = permits.availablePermits()
            val m = h.machine()
            val gate = Gate(m)
            val fired = AtomicBoolean()
            // 待ちを登録して CPU・許可を返した step の、登録を外す前（rt.thread が残っている間）に起こす。
            // 登録を外すときに、保留した起こし（pendingSchedule）が cpu.enqueueThreadless で次の step を渡すが、そこで失敗させる。
            // 失敗は tick の渡し直しでも続ける（tick を回しても、取り残しの列に残ったまま確かめられるように）。
            // 最初の step（読み込みを待って返す。state は CREATED のまま）では起こさない
            h.scheduler.hooks.beforeUnregister = { machine, pid ->
                if (machine === m && machine.processState(pid) == ProcessState.WAITING_EVENT && fired.compareAndSet(false, true)) {
                    executor.alwaysFail = true
                    gate.open()
                }
            }
            val pid = m.spawnTest(h.program(emptyModule()), gated(gate, kind = ProcessKind.WAIT_POINT))
            h.tickUntilTrue("取り残しの列に入らない") { fired.get() && permits.strandedTasks == 1 }
            // unregister が投げずに最後まで進んだ
            val rt = m.runtime(pid) ?: fail("実行時の部分がない")
            assertTrue(
                m.lock.withLockValue { rt.thread == null && !m.hasPendingSchedulesLocked() },
                "登録の後始末が飛ばされた（スレッドが残っている・保留が残っている）",
            )
            assertTrue(permits.handOffFailures.get() >= 1L, "登録を外すときの受け渡しで失敗する")
            assertEquals(pid, m.cpu.currentOwner?.pid, "CPU は取り残した step の持ち主のまま")
            assertEquals(ProcessState.RUNNABLE, m.processState(pid))
            assertTrue(m.state != MachineState.CRASHED, "マシンが落ちた")
            // 渡せない間は tick を回しても進まず、取り残しのまま
            repeat(5) { h.scheduler.tick() }
            assertEquals(1, permits.strandedTasks)
            assertEquals(ProcessState.RUNNABLE, m.processState(pid))
            // 渡せるようになれば、tick だけで進む
            executor.alwaysFail = false
            h.tickUntilTrue("終わらない") { m.processState(pid)?.isTerminal == true }
            assertEquals(ProcessState.EXITED, m.processState(pid))
            h.tickUntilTrue("CPU と許可が戻らない") { m.cpu.currentOwner == null && permits.availablePermits() == total }
            assertEquals(0, permits.strandedTasks)
            // 登録（registeredThreads）の数が合っている。外し損ねていれば、プロセスが終わっても待ち終わらない
            assertTrue(m.awaitTermination(5_000), "登録したスレッドが残っている")
            h.scheduler.hooks.beforeUnregister = null
        }
    }
}
