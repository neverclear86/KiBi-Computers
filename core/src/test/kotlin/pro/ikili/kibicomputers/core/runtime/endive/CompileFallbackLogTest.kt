package pro.ikili.kibicomputers.core.runtime.endive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import pro.ikili.kibicomputers.core.kernel.joinOrFail

/** インタプリタに回したことのログを、理由ごとに最初の1回だけ WARNING にする（#680、ADR 0088 の「追記（#680）」） */
@Timeout(10, unit = TimeUnit.MINUTES) // 止まったときの保険（合否の基準ではない。#916）
class CompileFallbackLogTest {
    /** 出したものを拾う [System.Logger]（テストごとに別の名前の JUL のロガーに流す。DEBUG は FINE） */
    private class Recorder(debug: Boolean) {
        // JUL のロガーは弱参照で持たれるので、ここで持ったままにする
        private val jul: Logger = Logger.getLogger("kibicomputers.test.compile-fallback-${seq.incrementAndGet()}")
        val lines: MutableList<Pair<Level, String>> = Collections.synchronizedList(ArrayList())
        val logger: System.Logger = System.getLogger(jul.name)

        init {
            jul.useParentHandlers = false
            jul.level = if (debug) Level.FINE else Level.INFO
            jul.addHandler(
                object : Handler() {
                    override fun publish(record: LogRecord) {
                        lines += record.level to record.message
                    }

                    override fun flush() = Unit

                    override fun close() = Unit
                },
            )
        }

        fun of(level: Level): List<String> = lines.toList().filter { it.first == level }.map { it.second }

        companion object {
            private val seq = AtomicInteger()
        }
    }

    @Test
    fun `理由ごとに最初の1回だけWARNINGで、2回目からはDEBUGに何回目かを添えて出す`() {
        val rec = Recorder(debug = true)
        val log = CompileFallbackLog(rec.logger)
        log.report(CompileFallbackLog.Reason.TOO_LARGE_FUNCTIONS) { "関数 [3]" }
        log.report(CompileFallbackLog.Reason.TOO_LARGE_FUNCTIONS) { "関数 [4]" }
        log.report(CompileFallbackLog.Reason.WHOLE_MODULE) { "全体" }
        log.report(CompileFallbackLog.Reason.TOO_LARGE_FUNCTIONS) { "関数 [5]" }
        val warn = rec.of(Level.WARNING)
        assertEquals(2, warn.size, "$warn")
        assertTrue(warn[0].startsWith("関数 [3]"), warn[0])
        assertTrue(warn[1].startsWith("全体"), warn[1])
        assertEquals(listOf("関数 [4]（同じ理由で 2 回目）", "関数 [5]（同じ理由で 3 回目）"), rec.of(Level.FINE))
        assertEquals(3L, log.count(CompileFallbackLog.Reason.TOO_LARGE_FUNCTIONS))
        assertEquals(1L, log.count(CompileFallbackLog.Reason.WHOLE_MODULE))
        assertEquals(0L, log.count(CompileFallbackLog.Reason.V128_SIGNATURE))
    }

    @Test
    fun `DEBUGが無効なら、2回目からは本文を作らない`() {
        val rec = Recorder(debug = false)
        val log = CompileFallbackLog(rec.logger)
        var built = 0
        repeat(5) { log.report(CompileFallbackLog.Reason.V128_SIGNATURE) { built++; "v128" } }
        assertEquals(1, built)
        assertEquals(1, rec.lines.size)
        assertEquals(5L, log.count(CompileFallbackLog.Reason.V128_SIGNATURE))
    }

    @Test
    fun `同時に初めて報告しても、WARNINGは1本だけ`() {
        val rec = Recorder(debug = false)
        val log = CompileFallbackLog(rec.logger)
        val threads = 8
        val start = CountDownLatch(1)
        val workers = (0 until threads).map {
            Thread {
                start.await()
                repeat(100) { log.report(CompileFallbackLog.Reason.SIMD_WITH_EXCEPTIONS) { "simd" } }
            }.also(Thread::start)
        }
        start.countDown()
        workers.forEach { it.joinOrFail("報告するスレッドが終わらない") }
        assertEquals(1, rec.of(Level.WARNING).size)
        assertEquals(threads * 100L, log.count(CompileFallbackLog.Reason.SIMD_WITH_EXCEPTIONS))
    }
}
