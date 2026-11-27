package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.runtime.FakeModule
import pro.ikili.kibicomputers.core.runtime.FakeWasmRuntime
import pro.ikili.kibicomputers.core.runtime.InterpreterReason
import pro.ikili.kibicomputers.core.runtime.InterpreterUse
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmRuntime
import java.time.Duration
import java.util.Collections
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * インタプリタで動くコードを持つプログラムの数え口（#931、ADR 0088 の「追記（#931）」）: プロセスを作るたびに台とスケジューラに数え、
 * 断った spawn は数えない。台ごとの DEBUG は1台・1理由につき1回。上位の台の選び方と、区間の増分の取り方
 */
class InterpreterCountsTest {
    private val limit = Duration.ofSeconds(60)

    /** [use] をインタプリタで動くコードとして持つ（`null` なら持たない）、ダミーの [Program] */
    private fun program(use: InterpreterUse?, tag: String): Program {
        val fake = FakeWasmRuntime()
        val bytes = "interpreted-$tag".toByteArray()
        fake.define(bytes, FakeModule.builder().build())
        val runtime = object : WasmRuntime by fake {
            override fun load(bytes: ByteArray): WasmModule = object : WasmModule by fake.load(bytes) {
                override val interpreterUse: InterpreterUse? = use
            }

            override fun load(bytes: ByteArray, timeLeftNanos: Long): WasmModule = load(bytes)
        }
        return Program.load(runtime, bytes)
    }

    private fun driver() = TestDriver(ProcessKind.BLOCKING) { StepResult.Exit(0) }

    /** `kibicomputers.machine` の DEBUG（JUL の FINE）を拾う（[HostFailureLogTest] と同じ） */
    private class Captured : AutoCloseable {
        private val logger: Logger = Logger.getLogger("kibicomputers.machine")
        private val oldLevel: Level? = logger.level
        val records: MutableList<LogRecord> = Collections.synchronizedList(ArrayList())
        private val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                records += record
            }

            override fun flush() = Unit

            override fun close() = Unit
        }

        init {
            logger.level = Level.ALL
            logger.addHandler(handler)
        }

        fun interpreted(m: Machine): List<LogRecord> =
            records.toList().filter { it.level == Level.FINE && it.message.startsWith("マシン ${m.id} で、インタプリタで動くコードを持つ") }

        override fun close() {
            logger.removeHandler(handler)
            logger.level = oldLevel
        }
    }

    @Test
    fun `インタプリタで動くコードを持つプログラムでプロセスを作るたびに、台とスケジューラに数える`() = timeout(limit) {
        Harness().use { h ->
            val large = program(InterpreterUse.functions(InterpreterReason.TOO_LARGE_FUNCTIONS, 3), "large")
            val simd = program(InterpreterUse.functions(InterpreterReason.SIMD_FUNCTIONS, 1), "simd")
            val plain = program(null, "plain")
            val a = h.machine()
            val b = h.machine()
            // 走らせると終わって刈り取られるが、数え口は残る。割り当ては止めておく（数えるのはプロセスを作ったとき）
            val holdA = a.quiesce()
            val holdB = b.quiesce()
            a.spawnTest(large, driver(), unmetered = true)
            a.spawnTest(large, driver(), unmetered = true)
            a.spawnTest(simd, driver(), unmetered = true)
            a.spawnTest(plain, driver(), unmetered = true)
            b.spawnTest(plain, driver(), unmetered = true)
            assertEquals(3L, a.interpretedProcessCount)
            assertEquals(setOf(InterpreterReason.SIMD_FUNCTIONS, InterpreterReason.TOO_LARGE_FUNCTIONS), a.interpretedReasons)
            assertEquals(simd.label, a.lastInterpretedProgram)
            assertEquals(0L, b.interpretedProcessCount)
            assertEquals(emptySet<InterpreterReason>(), b.interpretedReasons)
            assertNull(b.lastInterpretedProgram)
            assertEquals(2L, h.scheduler.interpretedProcessCount(InterpreterReason.TOO_LARGE_FUNCTIONS))
            assertEquals(1L, h.scheduler.interpretedProcessCount(InterpreterReason.SIMD_FUNCTIONS))
            assertEquals(0L, h.scheduler.interpretedProcessCount(InterpreterReason.WHOLE_MODULE))
            assertEquals(3L, h.scheduler.interpretedProcessTotal)
            val counts = InterpreterCounts.current(h.scheduler)
            assertEquals(2L, counts.processes(InterpreterReason.TOO_LARGE_FUNCTIONS))
            holdA.close()
            holdB.close()
        }
    }

    @Test
    fun `コンポーネントのように理由が複数あれば、プロセスは理由ごとに数え、合計は1つ`() = timeout(limit) {
        Harness().use { h ->
            val use = InterpreterUse.combine(
                listOf(InterpreterUse.wholeModule(InterpreterReason.V128_SIGNATURE), null, InterpreterUse.functions(InterpreterReason.SIMD_FUNCTIONS, 4)),
            )
            val m = h.machine()
            val hold = m.quiesce()
            m.spawnTest(program(use, "mixed"), driver(), unmetered = true)
            assertEquals(1L, m.interpretedProcessCount)
            assertEquals(1L, h.scheduler.interpretedProcessCount(InterpreterReason.V128_SIGNATURE))
            assertEquals(1L, h.scheduler.interpretedProcessCount(InterpreterReason.SIMD_FUNCTIONS))
            assertEquals(1L, h.scheduler.interpretedProcessTotal)
            hold.close()
        }
    }

    @Test
    fun `断ったspawnは数えない`() = timeout(limit) {
        Harness().use { h ->
            val large = program(InterpreterUse.functions(InterpreterReason.TOO_LARGE_FUNCTIONS, 3), "limit")
            val m = h.machine(maxProcesses = 1)
            val hold = m.quiesce()
            m.spawnTest(large, driver(), unmetered = true)
            assertThrows<ResourceLimitException> { m.spawnTest(large, driver(), unmetered = true) }
            assertEquals(1L, m.interpretedProcessCount)
            assertEquals(1L, h.scheduler.interpretedProcessTotal)
            hold.close()
        }
    }

    @Test
    fun `台ごとのDEBUGは、1台・1理由につき最初の1回だけ`() = timeout(limit) {
        Captured().use { log ->
            Harness().use { h ->
                val large = program(InterpreterUse.functions(InterpreterReason.TOO_LARGE_FUNCTIONS, 3), "debug-large")
                val simd = program(InterpreterUse.functions(InterpreterReason.SIMD_FUNCTIONS, 1), "debug-simd")
                val a = h.machine()
                val b = h.machine()
                val holdA = a.quiesce()
                val holdB = b.quiesce()
                repeat(5) { a.spawnTest(large, driver(), unmetered = true) }
                assertEquals(1, log.interpreted(a).size)
                assertTrue(log.interpreted(a).single().message.contains(large.label), log.interpreted(a).single().message)
                assertTrue(log.interpreted(a).single().message.contains("TOO_LARGE_FUNCTIONS"))
                // 新しい理由なら、同じ台でももう1回
                repeat(3) { a.spawnTest(simd, driver(), unmetered = true) }
                assertEquals(2, log.interpreted(a).size)
                // ほかの台は、その台の最初の1回を出す
                b.spawnTest(large, driver(), unmetered = true)
                assertEquals(1, log.interpreted(b).size)
                assertEquals(8L, a.interpretedProcessCount)
                holdA.close()
                holdB.close()
            }
        }
    }

    @Test
    fun `中のcoreモジュールの和は、理由をまとめて数を足す`() {
        assertNull(InterpreterUse.combine(listOf(null, null)))
        val use = InterpreterUse.combine(
            listOf(
                InterpreterUse.wholeModule(InterpreterReason.WHOLE_MODULE),
                InterpreterUse.functions(InterpreterReason.SIMD_FUNCTIONS, 2),
                InterpreterUse.functions(InterpreterReason.SIMD_FUNCTIONS, 5),
                null,
            ),
        )!!
        assertEquals(setOf(InterpreterReason.SIMD_FUNCTIONS, InterpreterReason.WHOLE_MODULE), use.reasons)
        assertEquals(1, use.wholeModules)
        assertEquals(7, use.functions)
        assertNull(InterpreterUse.functions(InterpreterReason.SIMD_FUNCTIONS, 0))
    }

    @Test
    fun `上位の台は回数の多い順に選び、同じ回数は並びの順、残りは台数だけ`() {
        val items = listOf("a" to 3L, "b" to 0L, "c" to 7L, "d" to 3L, "e" to 1L, "f" to 2L)
        val r = InterpreterRanking.top(items, limit = 3) { it.second }
        assertEquals(listOf("c", "a", "d"), r.top.map { it.item.first })
        assertEquals(listOf(7L, 3L, 3L), r.top.map { it.count })
        // 0 回の b は数えない
        assertEquals(2, r.others)
        assertEquals(5, r.machines)
        val none = InterpreterRanking.top(listOf("x" to 0L)) { it.second }
        assertEquals(0, none.machines)
        assertTrue(none.top.isEmpty())
    }

    @Test
    fun `数え口の区間の増分と要約`() {
        Harness().use { h ->
            val before = InterpreterCounts.current(h.scheduler)
            val m = h.machine()
            val hold = m.quiesce()
            m.spawnTest(program(InterpreterUse.wholeModule(InterpreterReason.WHOLE_MODULE), "delta"), driver(), unmetered = true)
            hold.close()
            val delta = InterpreterCounts.current(h.scheduler) - before
            assertEquals(1L, delta.processes(InterpreterReason.WHOLE_MODULE))
            // コンパイルの回数は JVM 全体なので、ほかのテストで動きうる。ここで読み込んだのはダミーなので、自分では増やさない
            assertEquals("WHOLE_MODULE 1", delta.processSummary())
            assertTrue(InterpreterCounts.ZERO.isZero)
            assertEquals("なし", InterpreterCounts.ZERO.compileSummary())
            assertEquals("なし", InterpreterCounts.formatReasons(emptySet()))
            assertEquals("SIMD_FUNCTIONS, WHOLE_MODULE", InterpreterCounts.formatReasons(setOf(InterpreterReason.WHOLE_MODULE, InterpreterReason.SIMD_FUNCTIONS)))
        }
    }
}
