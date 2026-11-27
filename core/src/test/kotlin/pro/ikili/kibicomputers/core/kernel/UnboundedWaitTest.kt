package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** テストの期限なしの待ち（#916。TestDrivers.kt の eventually・awaitOrFail など）の振る舞い */
@Timeout(10, unit = TimeUnit.MINUTES) // 止まったときの保険（合否の基準ではない。#916）
class UnboundedWaitTest {
    /** [block] を、[delayMillis] 後にこのスレッドへ割り込むようにして呼ぶ */
    private fun <T> interruptedAfter(delayMillis: Long, block: () -> T): T {
        val target = Thread.currentThread()
        val interrupter = Thread {
            Thread.sleep(delayMillis)
            target.interrupt()
        }
        interrupter.start()
        try {
            return block()
        } finally {
            interrupter.join()
        }
    }

    @Test
    fun `eventuallyは閾値を過ぎても落ちず、遅れを報告して待ち続ける`() {
        val start = System.nanoTime()
        val out = ByteArrayOutputStream()
        val old = System.out
        System.setOut(PrintStream(out, true, "UTF-8"))
        try {
            eventually(1, "まだそろわない") { System.nanoTime() - start > 50_000_000L }
        } finally {
            System.setOut(old)
        }
        val lines = out.toString("UTF-8").lines().filter { it.isNotEmpty() }
        assertEquals(1, lines.size, "遅れは 1 回だけ報告する: $lines")
        assertTrue(lines[0].startsWith("$SLOW_WAIT_MARKER: ") && lines[0].endsWith("まだそろわない"), lines[0])
    }

    @Test
    fun `eventuallyは割り込まれたら説明を付けて落ち、割り込みのフラグを立て直す`() {
        try {
            val e = assertThrows<AssertionError> { interruptedAfter(50) { eventually(60_000, "そろわない") { false } } }
            assertTrue(e.message!!.endsWith("そろわない"), e.message)
            assertTrue(e.cause is InterruptedException)
            assertTrue(Thread.currentThread().isInterrupted, "フラグを立て直す")
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `awaitOrFail・joinOrFail・getOrFailは期限なしで待ち、割り込まれたら説明を付けて落ちる`() {
        val latch = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val other = Thread({ waiting.await() }, "UnboundedWaitTest-other")
        other.isDaemon = true
        other.start()
        try {
            val cases = listOf<Pair<String, () -> Unit>>(
                "ラッチ" to { latch.awaitOrFail("ラッチ") },
                "相手" to { other.joinOrFail("相手") },
                "結果" to { CompletableFuture<Unit>().getOrFail("結果") },
            )
            for ((message, wait) in cases) {
                try {
                    val e = assertThrows<AssertionError> { interruptedAfter(50) { wait() } }
                    assertTrue(e.message!!.contains(message), e.message)
                    assertTrue(Thread.currentThread().isInterrupted, "$message: フラグを立て直す")
                } finally {
                    Thread.interrupted()
                }
            }
            // joinOrFail は、相手のスレッドの名前とスタックを付ける
            try {
                val e = assertThrows<AssertionError> { interruptedAfter(50) { other.joinOrFail() } }
                assertTrue(e.message!!.contains("UnboundedWaitTest-other") && e.message!!.contains("at "), e.message)
            } finally {
                Thread.interrupted()
            }
            // 開いていれば、そのまま返る
            latch.countDown()
            latch.awaitOrFail()
            waiting.countDown()
            other.joinOrFail()
            assertEquals("ok", CompletableFuture.completedFuture("ok").getOrFail())
        } finally {
            waiting.countDown()
        }
    }
}
