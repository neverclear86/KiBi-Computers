package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.opentest4j.AssertionFailedError
import pro.ikili.kibicomputers.core.machine.MachineManager
import pro.ikili.kibicomputers.core.runtime.FakeModule
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/** テストの期限なしの待ち（#916。TestDrivers.kt の eventually・awaitOrFail など）と、Harness の後始末の振る舞い */
@Timeout(10, unit = TimeUnit.MINUTES) // 止まったときの保険（合否の基準ではない。#916）
class UnboundedWaitTest {
    /**
     * [block] を、このスレッドが待ちに入ったら（WAITING・TIMED_WAITING になったら）割り込むようにして呼ぶ。
     *
     * 割り込み役の終わりは、割り込みを受けても待ち続け、受けたフラグは戻して待つ（[failOnInterrupt] がフラグを立て直しているので、
     * ふつうの `join()` だと割り込み役がまだ生きている間はその場で投げ、元の失敗を置き換える）
     */
    private fun <T> interruptedWhileWaiting(block: () -> T): T {
        val target = Thread.currentThread()
        val interrupter = Thread {
            while (target.state != Thread.State.WAITING && target.state != Thread.State.TIMED_WAITING) Thread.sleep(1)
            target.interrupt()
        }
        interrupter.start()
        try {
            return block()
        } finally {
            var interrupted = Thread.interrupted()
            while (true) {
                try {
                    interrupter.join()
                    break
                } catch (e: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) target.interrupt()
        }
    }

    /**
     * 標準出力に出た行（[block] の間）。[failOnInterrupt]・[Harness.close] の報告（[SLOW_WAIT_MARKER] の行）をわざと出すテストは、
     * これで捕まえる（通った回にも core の `test` タスクがその行をコンソールに写し、本当の遅れ・止まりの印を埋もれさせるため）
     */
    private fun capturedLines(block: () -> Unit): List<String> = capturedLinesWith { block() }

    /** [capturedLines] で、[block] が捕まえている出力そのもの（途中の行を読む別スレッドの同期に使う）も受け取る */
    private fun capturedLinesWith(block: (ByteArrayOutputStream) -> Unit): List<String> {
        val out = ByteArrayOutputStream()
        val old = System.out
        System.setOut(PrintStream(out, true, "UTF-8"))
        try {
            block(out)
        } finally {
            System.setOut(old)
        }
        return out.toString("UTF-8").lines().filter { it.isNotEmpty() }
    }

    @Test
    fun `eventuallyは閾値を過ぎても落ちず、遅れを報告して待ち続ける`() {
        val start = System.nanoTime()
        val lines = capturedLines { eventually(1, "まだそろわない") { System.nanoTime() - start > 50_000_000L } }
        assertEquals(1, lines.size, "遅れは 1 回だけ報告する: $lines")
        assertTrue(lines[0].startsWith("$SLOW_WAIT_MARKER: ") && lines[0].endsWith("まだそろわない"), lines[0])
    }

    @Test
    fun `eventuallyは割り込まれたら説明を付けて落ち、割り込みのフラグを立て直す`() {
        val lines = capturedLines {
            try {
                val e = assertThrows<AssertionError> { interruptedWhileWaiting { eventually(60_000, "そろわない") { false } } }
                assertTrue(e.message!!.endsWith("そろわない"), e.message)
                assertTrue(e.cause is InterruptedException)
                assertTrue(Thread.currentThread().isInterrupted, "フラグを立て直す")
            } finally {
                Thread.interrupted()
            }
        }
        assertTrue(lines.any { it.contains("そろわない") }, lines.toString())
    }

    @Test
    fun `failOnInterruptは値を返し、割り込まれたらフラグを立て直して説明を付けて落ちる`() {
        assertEquals("ok", failOnInterrupt({ "使わない" }) { "ok" })
        capturedLines {
            try {
                val e = assertThrows<AssertionError> {
                    failOnInterrupt({ "眠り" }) {
                        Thread.currentThread().interrupt()
                        Thread.sleep(60_000)
                    }
                }
                assertTrue(e.message!!.endsWith("眠り"), e.message)
                assertTrue(e.cause is InterruptedException)
                assertTrue(Thread.currentThread().isInterrupted, "フラグを立て直す")
            } finally {
                Thread.interrupted()
            }
        }
    }

    @Test
    fun `awaitOrFail・joinOrFail・getOrFail・awaitTerminationOrFailは期限なしで待ち、割り込まれたら説明を付けて落ちる`() {
        val latch = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val other = Thread({ waiting.await() }, "UnboundedWaitTest-other")
        other.isDaemon = true
        other.start()
        val pool = Executors.newSingleThreadExecutor()
        pool.execute { waiting.await() }
        pool.shutdown()
        try {
            val cases = listOf<Pair<String, () -> Unit>>(
                "ラッチ" to { latch.awaitOrFail("ラッチ") },
                "相手" to { other.joinOrFail("相手") },
                "結果" to { CompletableFuture<Unit>().getOrFail("結果") },
                "プール" to { pool.awaitTerminationOrFail("プール") },
            )
            capturedLines {
                for ((message, wait) in cases) {
                    try {
                        val e = assertThrows<AssertionError> { interruptedWhileWaiting { wait() } }
                        assertTrue(e.message!!.contains(message), e.message)
                        assertTrue(e.cause is InterruptedException, message)
                        assertTrue(Thread.currentThread().isInterrupted, "$message: フラグを立て直す")
                    } finally {
                        Thread.interrupted()
                    }
                }
                // joinOrFail は、相手のスレッドの名前とスタックを付ける
                try {
                    val e = assertThrows<AssertionError> { interruptedWhileWaiting { other.joinOrFail() } }
                    assertTrue(e.message!!.contains("UnboundedWaitTest-other") && e.message!!.contains("at "), e.message)
                } finally {
                    Thread.interrupted()
                }
            }
            // 開いていれば、そのまま返る
            latch.countDown()
            latch.awaitOrFail()
            waiting.countDown()
            other.joinOrFail()
            pool.awaitTerminationOrFail()
            assertEquals("ok", CompletableFuture.completedFuture("ok").getOrFail())
        } finally {
            waiting.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `割り込みのフラグが立ったまま呼んだ待ちは、フラグを下ろして待ち、フラグを戻す`() {
        val gate = CountDownLatch(1)
        val ender = Thread {
            Thread.sleep(100)
            gate.countDown()
        }
        ender.start()
        try {
            Thread.currentThread().interrupt()
            gate.awaitOrFail("門")
            assertEquals(0L, gate.count, "その場で投げずに待つ")
            assertTrue(Thread.currentThread().isInterrupted, "フラグを戻す")
            ender.joinOrFail("門を開けるスレッド")
            assertFalse(ender.isAlive, "その場で投げずに待つ")
            assertTrue(Thread.currentThread().isInterrupted, "フラグを戻す")
            val pool = Executors.newSingleThreadExecutor()
            pool.execute { Thread.sleep(100) }
            pool.shutdown()
            pool.awaitTerminationOrFail()
            assertTrue(pool.isTerminated, "その場で投げずに待つ")
            assertTrue(Thread.currentThread().isInterrupted, "フラグを戻す")
        } finally {
            Thread.interrupted()
            ender.joinOrFail()
        }
    }

    @Test
    fun `割り込まれた後の待ちは、待ち切れなくても投げずに報告して返す`() {
        var called = false
        assertFalse(cleanupWaitIfInterrupted({ "使わない" }, 50) { called = true; true }, "フラグがなければ何もしない")
        assertFalse(called)
        try {
            Thread.currentThread().interrupt()
            val lines = capturedLines {
                assertTrue(cleanupWaitIfInterrupted({ "終わらない相手" }, 50) { CountDownLatch(1).await(it, TimeUnit.MILLISECONDS) })
            }
            assertEquals(1, lines.size, lines.toString())
            assertTrue(lines[0].startsWith("$SLOW_WAIT_MARKER: ") && lines[0].endsWith("終わらない相手"), lines[0])
            assertTrue(Thread.currentThread().isInterrupted, "フラグを戻す")
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `assertNoKernelThreadsは、スレッドが残っていれば期限で落ちる`() {
        val release = CountDownLatch(1)
        val leak = Thread({ release.await() }, "UnboundedWaitTest-leak")
        leak.isDaemon = true
        leak.start()
        try {
            val e = assertThrows<AssertionError> { assertNoKernelThreads(100, "UnboundedWaitTest-leak") }
            assertTrue(e.message!!.contains("UnboundedWaitTest-leak"), e.message)
        } finally {
            release.countDown()
            leak.joinOrFail()
        }
        assertNoKernelThreads(prefix = "UnboundedWaitTest-leak")
    }

    /** 起動の途中（init を作る前のフック）でスレッドを止めた [Harness] を作る。止まったら [reached] が開く */
    private class GatedBoot(firstCloseWaitMillis: Long = 10_000) {
        val h = Harness(firstCloseWaitMillis = firstCloseWaitMillis)
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = AtomicBoolean()

        init {
            h.scheduler.hooks.beforeInitExec = { _ ->
                reached.countDown()
                try {
                    release.await()
                } catch (e: InterruptedException) {
                    interrupted.set(true)
                    throw e
                }
            }
            val bytes = "unbounded-wait-init".toByteArray()
            h.runtime.define(bytes, FakeModule.builder().fuelMetered().export("_start", VOID) { _, _ -> LongArray(0) }.build())
            assertTrue(h.machine().boot(BootConfig.builder().firmwareProgram(bytes).build()))
            reached.awaitOrFail("起動がフックに来ない")
        }
    }

    @Test
    fun `Harnessのcloseは、最初にreleaseOnCloseの後始末を呼び、門で止めたスレッドを放してから止める`() {
        for (interruptedBefore in listOf(false, true)) {
            val g = GatedBoot()
            var schedulerClosedFirst: Boolean? = null
            g.h.releaseOnClose {
                schedulerClosedFirst = g.h.scheduler.isClosed
                g.release.countDown()
            }
            try {
                // 割り込まれてから close に入っても（保険のタイムアウトの後）、フラグを下ろして待ち、後始末を済ませる
                if (interruptedBefore) Thread.currentThread().interrupt()
                g.h.close()
                assertTrue(Thread.currentThread().isInterrupted == interruptedBefore, "フラグを元に戻す（$interruptedBefore）")
            } finally {
                Thread.interrupted()
            }
            assertEquals(false, schedulerClosedFirst, "スケジューラを止める前に呼ぶ")
            assertFalse(g.interrupted.get(), "門を開けて放す（割り込まない）")
            assertNoKernelThreads()
        }
    }

    @Test
    fun `Harnessのcloseは、終わらなければKiBiのスレッドに割り込んで放し、失敗は残す`() {
        for (interruptedBefore in listOf(false, true)) {
            val g = GatedBoot(firstCloseWaitMillis = 200)
            var failure: AssertionError? = null
            val lines = capturedLines {
                try {
                    if (interruptedBefore) Thread.currentThread().interrupt()
                    val e = assertThrows<AssertionError> { g.h.close() }
                    failure = e
                    assertTrue(e.message!!.contains("スケジューラが終わらない"), "割り込みで待ちを飛ばさない（$interruptedBefore）: $e")
                    assertTrue(Thread.currentThread().isInterrupted == interruptedBefore, "フラグを元に戻す（$interruptedBefore）")
                } finally {
                    Thread.interrupted()
                    g.release.countDown()
                }
            }
            // 門で止まった起動用のスレッド（KiBi-boot-*。プールのスレッド）を、報告と失敗の本文の「残ったスレッド」に名前とスタックで載せる
            val message = failure!!.message!!
            assertTrue(lines.any { it.contains("KiBi-boot-") }, "報告に残ったスレッドを載せる（$interruptedBefore）: $lines")
            assertTrue(message.contains("KiBi-boot-") && message.contains("at "), "失敗の本文に残ったスレッドを載せる（$interruptedBefore）: $message")
            assertTrue(g.interrupted.get(), "門で止まったスレッドに割り込む（$interruptedBefore）")
            assertNoKernelThreads()
        }
    }

    @Test
    fun `Harnessのcloseの割り込みは、自分のスケジューラのスレッドだけに及ぶ`() {
        val unrelatedInterrupted = AtomicBoolean()
        val stop = CountDownLatch(1)
        val unrelated = Thread({
            try {
                stop.await()
            } catch (e: InterruptedException) {
                unrelatedInterrupted.set(true)
            }
        }, "KiBi-unrelated")
        unrelated.isDaemon = true
        unrelated.start()
        val g = GatedBoot(firstCloseWaitMillis = 200)
        try {
            capturedLines { assertThrows<AssertionError> { g.h.close() } }
            assertTrue(g.interrupted.get(), "自分のスケジューラのスレッドには割り込む")
            // 割り込みはその場で届くので、少し待っても何も起きないことを見る（待ちは否定の確認のためだけ）
            Thread.sleep(200)
            assertTrue(unrelated.isAlive && !unrelatedInterrupted.get(), "名前が KiBi- で始まるだけの無関係なスレッドには割り込まない")
        } finally {
            g.release.countDown()
            stop.countDown()
            unrelated.joinOrFail()
        }
        assertNoKernelThreads()
    }

    @Test
    fun `failOnInterruptは、割り込まれたときに説明を標準出力にも出す`() {
        try {
            val lines = capturedLines {
                assertThrows<AssertionError> {
                    failOnInterrupt({ "出る説明" }) {
                        Thread.currentThread().interrupt()
                        Thread.sleep(60_000)
                    }
                }
            }
            assertTrue(lines.isNotEmpty() && lines.all { it.startsWith("$SLOW_WAIT_MARKER: ") }, lines.toString())
            assertTrue(lines.any { it.contains("出る説明") }, lines.toString())
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `assertTimeoutPreemptivelyが先に切れても、止まった待ちの説明は標準出力に残る`() {
        val out = ByteArrayOutputStream()
        val old = System.out
        System.setOut(PrintStream(out, true, "UTF-8"))
        try {
            assertThrows<AssertionFailedError> {
                assertTimeoutPreemptively(Duration.ofMillis(100)) { eventually(60_000, "先に切れた待ち") { false } }
            }
            // 捨てられた実行スレッドが、割り込みを受けて説明を出すのを待つ
            eventually { out.toString("UTF-8").contains("先に切れた待ち") }
        } finally {
            System.setOut(old)
        }
        val lines = out.toString("UTF-8").lines().filter { it.startsWith("$SLOW_WAIT_MARKER: ") }
        assertTrue(lines.any { it.contains("先に切れた待ち") }, lines.toString())
    }

    @Test
    fun `awaitRepeatedlyは、閾値を過ぎても落ちずに1回だけ報告して、終わるまで繰り返す`() {
        // 終わる条件は回数で決める（経過時間の上限だと、テストのスレッドが止まったときに 1 回目で終わって、繰り返しも報告も見えなくなる）
        var calls = 0
        val lines = capturedLines {
            awaitRepeatedly({ "まだ終わらない相手" }, reportAfterMillis = 1) { millis ->
                calls++
                Thread.sleep(minOf(millis, 10))
                calls >= 3
            }
        }
        assertTrue(calls > 1, "繰り返す: $calls")
        assertEquals(1, lines.size, lines.toString())
        assertTrue(lines[0].startsWith("$SLOW_WAIT_MARKER: ") && lines[0].endsWith("まだ終わらない相手"), lines[0])
        // 終わっていれば、報告せずにそのまま返る
        assertEquals(emptyList<String>(), capturedLines { awaitRepeatedly({ "使わない" }, reportAfterMillis = 1) { true } })
    }

    @Test
    fun `awaitRepeatedlyは、割り込まれたら説明を付けて落ち、割り込みを握りつぶすAPIでも空回りしない`() {
        val never = CountDownLatch(1)
        // 投げる API（Machine.awaitTermination など）と、握りつぶしてフラグを立て直し false を返す API（SaveWriter.close など）
        val cases = listOf<Pair<String, (Long) -> Boolean>>(
            "投げる相手" to { millis -> never.await(millis, TimeUnit.MILLISECONDS) },
            "握りつぶす相手" to { millis ->
                try {
                    never.await(millis, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
            },
        )
        capturedLines {
            for ((message, timedWait) in cases) {
                try {
                    val e = assertThrows<AssertionError> { interruptedWhileWaiting { awaitRepeatedly({ message }, timedWait = timedWait) } }
                    assertTrue(e.message!!.contains(message), e.message)
                    assertTrue(e.cause is InterruptedException, message)
                    assertTrue(Thread.currentThread().isInterrupted, "$message: フラグを立て直す")
                } finally {
                    Thread.interrupted()
                }
            }
        }
    }

    @Test
    fun `awaitRepeatedlyは、待たずにfalseを返すAPIでもCPUを回し続けない`() {
        // 閉じていない MachineScheduler.awaitTermination のように、その場で false を返す相手。各回の後に寝るので、呼ぶ回数は時間で抑えられる
        // 時間は最初の呼び出しから測る（呼び出しの前にテストのスレッドが止まっても、1 回目で終わらない）
        var first = 0L
        var calls = 0
        capturedLines {
            awaitRepeatedly({ "その場で返る相手" }) {
                if (++calls == 1) first = System.nanoTime()
                System.nanoTime() - first > 100_000_000L
            }
        }
        assertTrue(calls in 2..1_000, "呼んだ回数: $calls")
    }

    @Test
    fun `awaitRepeatedlyは、割り込みのフラグが立ったまま呼ばれたら、期限つきで待ってフラグを戻す`() {
        val gate = CountDownLatch(1)
        val ender = Thread {
            Thread.sleep(100)
            gate.countDown()
        }
        ender.start()
        try {
            Thread.currentThread().interrupt()
            awaitRepeatedly({ "門" }) { gate.await(it, TimeUnit.MILLISECONDS) }
            assertEquals(0L, gate.count, "その場で投げずに待つ")
            assertTrue(Thread.currentThread().isInterrupted, "フラグを戻す")
            // 待ち切れなくても投げずに報告して返す（元の失敗の説明を置き換えない）
            val lines = capturedLines { awaitRepeatedly({ "終わらない相手" }) { false } }
            assertTrue(lines.isNotEmpty() && lines[0].startsWith("$SLOW_WAIT_MARKER: "), lines.toString())
            assertTrue(Thread.currentThread().isInterrupted, "フラグを戻す")
        } finally {
            Thread.interrupted()
            ender.joinOrFail()
        }
    }

    @Test
    fun `awaitTerminationOrFailとshutdownOrFailは、止めたマシンとスケジューラの終わりを待つ`() {
        val h = Harness()
        val m = h.machine()
        m.spawnTest(h.program(emptyModule()), runDriver())
        h.tickUntilTrue("止まらない") { m.state == MachineState.STOPPED }
        m.awaitTerminationOrFail()
        h.close()
        h.scheduler.awaitTerminationOrFail()
        val manager = MachineManager<String>()
        manager.shutdownOrFail()
        manager.shutdownOrFail() // 2 回呼んでもよい
    }

    @Test
    fun `awaitAcquirableは、生きている持ち主が放すまで待ち、遅れは持ち主のスタックを付けて報告する`() {
        val lock = ReentrantLock()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = Thread({
            lock.lock()
            try {
                held.countDown()
                release.await()
            } finally {
                lock.unlock()
            }
        }, "UnboundedWaitTest-owner")
        owner.start()
        try {
            held.awaitOrFail()
            assertEquals("UnboundedWaitTest-owner", lockOwnerName(lock))
            // 放す役は、遅れの報告が出てから放す（時間で決めると、テストのスレッドが止まったときに、報告の前に放して取れてしまう）
            val lines = capturedLinesWith { out ->
                val releaser = Thread {
                    while (!out.toString("UTF-8").contains(SLOW_WAIT_MARKER)) Thread.sleep(5)
                    release.countDown()
                }
                releaser.start()
                try {
                    lock.awaitAcquirable("持ち主が放さない", reportAfterMillis = 1)
                } finally {
                    release.countDown()
                    releaser.joinOrFail()
                }
            }
            assertFalse(lock.isLocked, "取れたらすぐ放す")
            assertTrue(lines.isNotEmpty() && lines.all { it.startsWith("$SLOW_WAIT_MARKER: ") }, lines.toString())
            assertTrue(lines[0].contains("持ち主が放さない"), lines[0])
            assertTrue(lines.any { it.contains("UnboundedWaitTest-owner") }, lines.toString())
            assertEquals(null, lockOwnerName(lock))
            // 空いていれば、報告せずにすぐ返る
            assertEquals(emptyList<String>(), capturedLines { lock.awaitAcquirable(reportAfterMillis = 1) })
        } finally {
            release.countDown()
            owner.joinOrFail()
        }
    }

    @Test
    fun `awaitAcquirableは、持ち主がロックを持ったまま死んでいれば、停止としてすぐ落ちる`() {
        val lock = ReentrantLock()
        val owner = Thread({ lock.lock() }, "UnboundedWaitTest-dead-owner")
        owner.start()
        owner.joinOrFail()
        val e = assertThrows<AssertionError> { lock.awaitAcquirable("持ったまま死んだ", reportAfterMillis = 1) }
        assertTrue(e.message!!.contains("停止") && e.message!!.contains("UnboundedWaitTest-dead-owner"), e.message)
        assertTrue(e.message!!.contains("持ったまま死んだ"), e.message)
    }
}
