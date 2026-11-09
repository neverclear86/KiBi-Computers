package pro.ikili.kibicomputers.core.persist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 書き込みのスレッド（`SaveWriter`。#84、ADR 0084） */
class SaveWriterTest {
    private val limit = Duration.ofSeconds(30)
    private val id = UUID.randomUUID()

    @Test
    fun `入れた順に別のスレッドで走り、flushが終わりを待つ`() = timeout(limit) {
        SaveWriter("test writer").use { writer ->
            val order = Collections.synchronizedList(ArrayList<Int>())
            val threads = Collections.synchronizedSet(HashSet<String>())
            repeat(100) { i ->
                assertTrue(writer.execute(id) {
                    order += i
                    threads += Thread.currentThread().name
                })
            }
            assertTrue(writer.flush(10_000))
            assertEquals((0 until 100).toList(), order.toList())
            assertEquals(setOf("test writer"), threads.toSet())
            assertNotEquals(Thread.currentThread().name, "test writer")
        }
    }

    @Test
    fun `仕事の例外でスレッドが死なない`() = timeout(limit) {
        SaveWriter().use { writer ->
            assertTrue(writer.execute(id) { throw IllegalStateException("わざと") })
            val done = CountDownLatch(1)
            assertTrue(writer.execute(id) { done.countDown() })
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertTrue(writer.flush(10_000))
        }
    }

    @Test
    fun `flushとcloseの上限、closeの後は入れられない`() = timeout(limit) {
        val writer = SaveWriter()
        val gate = CountDownLatch(1)
        assertTrue(writer.execute(id) { gate.await() })
        assertFalse(writer.flush(50), "終わっていない仕事があれば上限で false")
        assertFalse(writer.close(50), "走っている仕事があれば止まりきらない")
        assertFalse(writer.execute(id) {}, "閉じた後は断る")
        gate.countDown()
        assertTrue(writer.close(10_000))
        assertTrue(writer.flush(0))
    }

    @Test
    fun `closeは入っている仕事を終えてから止まる`() = timeout(limit) {
        val writer = SaveWriter()
        val gate = CountDownLatch(1)
        val ran = Collections.synchronizedList(ArrayList<Int>())
        writer.execute(id) { gate.await() }
        repeat(3) { i -> writer.execute(id) { ran += i } }
        gate.countDown()
        assertTrue(writer.close(10_000))
        assertEquals(listOf(0, 1, 2), ran.toList())
    }

    @Test
    fun `DIRECTは呼んだスレッドでその場で走る`() {
        var ranOn: Thread? = null
        assertTrue(SaveExecutor.DIRECT.execute(id) { ranOn = Thread.currentThread() })
        assertEquals(Thread.currentThread(), ranOn)
        assertTrue(SaveExecutor.DIRECT.flush(0))
    }
}
