package pro.ikili.kibicomputers.core.testing

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.io.TempDir
import org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/** 止まったテストのスレッドダンプ（#927）の確かめ */
class ThreadDumpOnTimeoutTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `ダンプの全文にロックの持ち主と全フレームが載り、要約は頭の付いた行だけになる`() {
        val lock = ReentrantLock()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread({
            lock.lock()
            try {
                held.countDown()
                release.await()
            } finally {
                lock.unlock()
            }
        }, "dump-test-holder")
        holder.isDaemon = true
        holder.start()
        held.await()
        val waiter = Thread({ lock.lock(); lock.unlock() }, "dump-test-waiter")
        waiter.isDaemon = true
        waiter.start()
        try {
            awaitState(waiter, Thread.State.WAITING)
            val out = ByteArrayOutputStream()
            val file = PrintStream(out, true, "UTF-8").use { ThreadDumps.dump(dir, "a.b.SomeTest#stuck", "試し", waiter, it) }
            val text = file.readText()
            assertTrue(file.fileName.toString().startsWith("SomeTest-stuck-"), file.toString())
            assertTrue(text.contains("\"dump-test-waiter\""), text)
            assertTrue(text.contains("持ち主 \"dump-test-holder\""), text)
            assertTrue(text.contains("持っている同期器: java.util.concurrent.locks.ReentrantLock"), text)
            // 8 フレームで切らない（ThreadInfo.toString() の形ではない）
            assertFalse(text.contains("\t..."), text)
            assertTrue(text.contains("デッドロック（findDeadlockedThreads）: なし"), text)

            val summary = out.toString("UTF-8").lines().filter { it.isNotEmpty() }
            assertTrue(summary.all { it.startsWith(ThreadDumpOnTimeout.MARKER) }, summary.joinToString("\n"))
            assertTrue(summary.any { it.contains("\"dump-test-waiter\"") }, summary.joinToString("\n"))
            // 要約には、割り込まれるスレッドと KiBi- のスレッドだけを載せる
            assertFalse(summary.any { it.contains(": \"dump-test-holder\" #") }, summary.joinToString("\n"))
        } finally {
            release.countDown()
            holder.join(60_000)
            waiter.join(60_000)
        }
    }

    @Test
    fun `assertTimeoutPreemptively の時間切れを見分ける`() {
        val failure = runCatching { assertTimeoutPreemptively(Duration.ofMillis(50)) { Thread.sleep(10_000) } }.exceptionOrNull()!!
        assertTrue(ThreadDumpOnTimeout.isPreemptiveTimeout(failure), failure.toString())
        assertFalse(ThreadDumpOnTimeout.isPreemptiveTimeout(AssertionError("ふつうの失敗")))
    }

    @Test
    fun `保険のタイムアウトと assertTimeoutPreemptively の時間切れで、自動登録の拡張がダンプを書く`() {
        val request = LauncherDiscoveryRequestBuilder.request()
            .selectors(
                selectMethod(StuckFixture::class.java, "stuckUntilInterrupted"),
                selectMethod(StuckFixture::class.java, "stuckInPreemptive"),
            )
            .configurationParameter("junit.jupiter.conditions.deactivate", "org.junit.*DisabledCondition")
            .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
            .configurationParameter(
                "junit.jupiter.extensions.autodetection.include",
                ThreadDumpOnTimeout::class.java.name,
            )
            .configurationParameter(ThreadDumpOnTimeout.DIR_PROPERTY, dir.toString())
            .configurationParameter(ThreadDumpOnTimeout.SUMMARY_PROPERTY, "off")
            .build()
        val listener = SummaryGeneratingListener()
        LauncherFactory.create().execute(request, listener)
        val summary = listener.summary
        assertEquals(2L, summary.testsFailedCount, summary.failures.joinToString("\n") { it.exception.toString() })

        val files = dir.listDirectoryEntries().map { it.fileName.toString() }.sorted()
        assertEquals(2, files.size, files.toString())
        val interrupted = dir.resolve(files.single { it.startsWith("ThreadDumpOnTimeoutTest_StuckFixture-stuckUntilInterrupted-") }).readText()
        assertTrue(interrupted.contains("割り込む直前"), interrupted)
        assertTrue(interrupted.contains("持ち主 \"dump-fixture-holder\""), interrupted)
        assertTrue(interrupted.contains("StuckFixture.stuckUntilInterrupted"), interrupted)
        val preemptive = dir.resolve(files.single { it.startsWith("ThreadDumpOnTimeoutTest_StuckFixture-stuckInPreemptive-") }).readText()
        assertTrue(preemptive.contains("assertTimeoutPreemptively の時間切れ"), preemptive)
        assertTrue(preemptive.contains("\"dump-fixture-holder\""), preemptive)
    }

    private fun awaitState(thread: Thread, state: Thread.State) {
        // 期限なしで待つ（止まったら保険のタイムアウトが落とす。#916）
        while (thread.state != state) Thread.sleep(5)
    }

    /**
     * 入れ子の JUnit で回す、止まるテスト。ふつうの走査では @Disabled で飛ばし、上のテストが条件を外して回す。
     * スレッドの名前は `KiBi-` にしない（assertNoKernelThreads が数えるため）
     */
    @Disabled("ThreadDumpOnTimeoutTest が入れ子の JUnit で回す")
    class StuckFixture {
        private val lock = ReentrantLock()
        private val release = CountDownLatch(1)

        private var holder: Thread? = null

        // 拡張の afterTestExecution（assertTimeoutPreemptively の時間切れのダンプ）は @AfterEach より前に走るので、持ち主はここで放す
        @AfterEach
        fun releaseLock() {
            release.countDown()
            holder?.join(60_000)
        }

        private fun holdLock() {
            val held = CountDownLatch(1)
            val t = Thread({
                lock.lock()
                try {
                    held.countDown()
                    release.await(60, TimeUnit.SECONDS)
                } finally {
                    lock.unlock()
                }
            }, "dump-fixture-holder")
            t.isDaemon = true
            t.start()
            holder = t
            held.await()
        }

        @Test
        @Timeout(value = 300, unit = TimeUnit.MILLISECONDS)
        fun stuckUntilInterrupted() {
            holdLock()
            lock.lockInterruptibly()
        }

        @Test
        fun stuckInPreemptive() {
            holdLock()
            assertTimeoutPreemptively(Duration.ofMillis(300)) { lock.lockInterruptibly() }
        }
    }
}
