package pro.ikili.kibicomputers.core.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.kernel.Harness
import java.time.Duration
import java.util.Collections
import java.util.ResourceBundle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import pro.ikili.kibicomputers.core.testing.bodyTimeout as timeout

/** デバイスのメソッドの失敗のログを間引く（#679、ADR 0065 の「追記（#679）」） */
class DeviceFailureLogTest {
    private val limit = Duration.ofSeconds(30)

    /** 拾った1行 */
    private data class Line(val level: System.Logger.Level, val message: String, val thrown: Throwable?)

    /**
     * 出したものを拾う [System.Logger]（[debug] で DEBUG を有効にする）。テストごとに別の名前の JUL のロガーに流して拾う
     * （core のテストでは System.Logger が JUL に流れる。DEBUG は FINE）
     */
    private class Recorder(debug: Boolean = false) {
        // JUL のロガーは弱参照で持たれるので、ここで持ったままにする
        private val jul: Logger = Logger.getLogger("kibicomputers.test.device-failure-${seq.incrementAndGet()}")
        val lines: MutableList<Line> = Collections.synchronizedList(ArrayList())
        val logger: System.Logger = System.getLogger(jul.name)

        init {
            jul.useParentHandlers = false
            jul.level = if (debug) Level.FINE else Level.INFO
            jul.addHandler(
                object : Handler() {
                    override fun publish(record: LogRecord) {
                        val level = if (record.level == Level.WARNING) System.Logger.Level.WARNING else System.Logger.Level.DEBUG
                        lines += Line(level, record.message, record.thrown)
                    }

                    override fun flush() = Unit

                    override fun close() = Unit
                },
            )
        }

        fun of(level: System.Logger.Level): List<Line> = lines.toList().filter { it.level == level }

        companion object {
            private val seq = AtomicInteger()
        }
    }

    private val warning = System.Logger.Level.WARNING
    private val debug = System.Logger.Level.DEBUG

    @Test
    fun `同じキーで1000回失敗してもWARNINGは1本でスタックトレースつき、間が過ぎると要約が1本出る`() {
        val rec = Recorder()
        val log = DeviceFailureLog(summaryIntervalTicks = 100, log = rec.logger)
        val bug = IllegalStateException("バグ")
        repeat(1000) { log.report(7, "addr-$it", "test:device", "bug", bug, now = 5) }
        val first = rec.of(warning)
        assertEquals(1, first.size, first.toString())
        assertTrue(first[0].message.startsWith("マシン 7 のデバイス addr-0（種類 test:device）のメソッド bug が例外を投げた"), first[0].message)
        assertEquals(bug, first[0].thrown)

        log.tick(104)
        assertEquals(1, rec.of(warning).size, "間が過ぎるまで要約は出ない")
        log.tick(105)
        val summary = rec.of(warning)
        assertEquals(2, summary.size)
        assertTrue(summary[1].message.contains("999 回"), summary[1].message)
        assertTrue(summary[1].message.contains("種類 test:device のメソッド bug（java.lang.IllegalStateException）999 回"), summary[1].message)

        // 繰り返しがなければ、もう出ない
        log.tick(10_000)
        assertEquals(2, rec.of(warning).size)
        // 次の繰り返しは、また間が過ぎてから
        log.report(7, "a", "test:device", "bug", bug, now = 20_000)
        log.tick(20_050)
        assertEquals(2, rec.of(warning).size)
        log.tick(20_100)
        assertEquals(3, rec.of(warning).size)
        assertTrue(rec.of(warning)[2].message.contains("1 回"))
    }

    @Test
    fun `閉じるときに、間が過ぎていない繰り返しの件数を出し、なければ何も出さない`() {
        val rec = Recorder()
        val log = DeviceFailureLog(summaryIntervalTicks = 1200, log = rec.logger)
        log.flushPending()
        assertTrue(rec.lines.isEmpty())
        repeat(5) { log.report(1, "a", "test:device", "bug", IllegalStateException(), now = 0) }
        log.tick(10)
        assertEquals(1, rec.of(warning).size, "間が過ぎるまで要約は出ない")
        log.flushPending()
        val w = rec.of(warning)
        assertEquals(2, w.size, w.toString())
        assertTrue(w[1].message.contains("種類 test:device のメソッド bug（java.lang.IllegalStateException）4 回"), w[1].message)
        // 出した分は数え直す
        log.flushPending()
        assertEquals(2, rec.of(warning).size)
    }

    @Test
    fun `スケジューラを閉じると、間が過ぎていない失敗の繰り返しの件数が出る`() = timeout(limit) {
        Captured().use { cap ->
            val h = Harness()
            val methods = DeviceMethods.builder()
                .direct("bug") { _, _ -> throw IllegalStateException("バグ") }
                .build()
            val machine = h.machine()
            val address = "00000000-0000-4000-8000-000000000679"
            machine.devices.attach(address, TestDevice(methods))
            repeat(10) {
                val r = DeviceDispatcher.resultOf(machine.devices.dispatcher.start(0, address, "bug", emptyList()).future)
                assertEquals(CallResult.Error(CallError.INTERNAL_ERROR), r)
            }
            assertEquals(1, cap.of(Level.WARNING).size)
            h.close()
            val warnings = cap.of(Level.WARNING)
            assertEquals(2, warnings.size, warnings.map { it.message }.toString())
            assertTrue(warnings[1].message.contains("9 回"), warnings[1].message)
        }
    }

    @Test
    fun `失敗がなければ要約は出ず、1回だけの失敗にも要約は出ない`() {
        val rec = Recorder()
        val log = DeviceFailureLog(summaryIntervalTicks = 10, log = rec.logger)
        log.tick(1_000)
        assertTrue(rec.lines.isEmpty())
        log.report(1, "a", "k", "m", RuntimeException(), now = 0)
        log.tick(1_000)
        assertEquals(1, rec.of(warning).size)
    }

    @Test
    fun `メソッド・例外のクラス・nullの値・種類が違えば、それぞれ1本ずつWARNINGが出る`() {
        val rec = Recorder()
        val log = DeviceFailureLog(log = rec.logger)
        repeat(3) {
            log.report(1, "a", "k", "m", IllegalStateException("x$it"), now = 0)
            log.report(1, "a", "k", "n", IllegalStateException(), now = 0)
            log.report(1, "a", "k", "m", IllegalArgumentException(), now = 0)
            log.report(1, "a", "k", "m", null, now = 0)
            log.report(1, "a", "k2", "m", null, now = 0)
        }
        val w = rec.of(warning)
        assertEquals(5, w.size, w.toString())
        val nulls = w.filter { it.message.contains("null の値を返した") }
        assertEquals(2, nulls.size)
        assertTrue(nulls.all { it.thrown == null })
    }

    @Test
    fun `キーが上限に達したら、上限のWARNINGを1回だけ出し、後は数だけを要約に出す`() {
        val rec = Recorder()
        val log = DeviceFailureLog(summaryIntervalTicks = 10, keyLimit = 64, log = rec.logger)
        repeat(64) { log.report(1, "a", "k", "m$it", null, now = 0) }
        assertEquals(64, rec.of(warning).size)
        repeat(100) { log.report(1, "a", "k", "over$it", null, now = 0) }
        val w = rec.of(warning)
        assertEquals(65, w.size)
        assertTrue(w[64].message.contains("上限（64 個）に達した"), w[64].message)
        // 覚えたキーの繰り返しは、今までどおり数える
        log.report(1, "a", "k", "m0", null, now = 0)
        log.tick(10)
        val summary = rec.of(warning).last().message
        assertTrue(summary.contains("101 回"), summary)
        assertTrue(summary.contains("組の数が上限に達した後の失敗 100 回"), summary)
    }

    @Test
    fun `繰り返しはDEBUGに台を出す（DEBUGが無効なら例外のtoStringを呼ばない）`() {
        val rec = Recorder(debug = true)
        val log = DeviceFailureLog(log = rec.logger)
        log.report(3, "a", "k", "m", RuntimeException("x"), now = 0)
        log.report(4, "b", "k", "m", RuntimeException("y"), now = 0)
        val d = rec.of(debug)
        assertEquals(1, d.size)
        assertTrue(d[0].message.startsWith("マシン 4 のデバイス b（種類 k）のメソッド m が例外を投げた: java.lang.RuntimeException: y"), d[0].message)

        val quiet = DeviceFailureLog(log = Recorder().logger)
        val hostile = object : RuntimeException() {
            override fun toString(): String = throw IllegalStateException("toString が投げた")
        }
        quiet.report(1, "a", "k", "m", hostile, now = 0)
        quiet.report(1, "a", "k", "m", hostile, now = 0)
    }

    @Test
    fun `ログに出す処理が投げても投げない（致命的なエラーだけ投げ直す）`() {
        val broken = object : System.Logger {
            override fun getName(): String = "broken"

            override fun isLoggable(level: System.Logger.Level): Boolean = true

            override fun log(level: System.Logger.Level, bundle: ResourceBundle?, msg: String?, thrown: Throwable?): Unit =
                throw IllegalStateException("ログが壊れた")

            override fun log(level: System.Logger.Level, bundle: ResourceBundle?, format: String?, vararg params: Any?): Unit =
                throw IllegalStateException("ログが壊れた")
        }
        val log = DeviceFailureLog(summaryIntervalTicks = 1, log = broken)
        repeat(3) { log.report(1, "a", "k", "m", RuntimeException(), now = it.toLong()) }
        log.tick(100)
    }

    @Test
    fun `複数のスレッドから同時に同じキーで失敗しても、WARNINGは1本で数は合う`() = timeout(limit) {
        val rec = Recorder()
        val log = DeviceFailureLog(summaryIntervalTicks = 10, log = rec.logger)
        val threads = 8
        val perThread = 500
        val start = CountDownLatch(1)
        val workers = List(threads) {
            Thread {
                start.await()
                repeat(perThread) { log.report(1, "a", "k", "m", IllegalStateException(), now = 0) }
            }.also { it.start() }
        }
        start.countDown()
        workers.forEach { it.join() }
        assertEquals(1, rec.of(warning).size)
        log.tick(10)
        val summary = rec.of(warning)
        assertEquals(2, summary.size)
        assertTrue(summary[1].message.contains("${threads * perThread - 1} 回"), summary[1].message)
    }

    /** `kibicomputers.device` のログを拾う（core のテストでは System.Logger が JUL に流れる） */
    private class Captured : AutoCloseable {
        private val logger: Logger = Logger.getLogger("kibicomputers.device")
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

        fun of(level: Level): List<LogRecord> = records.toList().filter { it.level == level }

        override fun close() {
            logger.removeHandler(handler)
            logger.level = oldLevel
        }
    }

    private class TestDevice(override val methods: DeviceMethods) : Device {
        override val kind: String = "test:failing"
    }

    @Test
    fun `直接とメインスレッドのメソッドをループで呼んでも、2台にまたがってもWARNINGは増えず、結果はinternal errorのまま`() = timeout(limit) {
        Captured().use { cap ->
            Harness().use { h ->
                val methods = DeviceMethods.builder()
                    .direct("bug") { _, _ -> throw IllegalStateException("バグ") }
                    .mainThread("main-bug") { _, _ -> throw IllegalStateException("バグ") }
                    .direct("null") { _, _ -> @Suppress("UNCHECKED_CAST") (arrayOfNulls<DeviceValue>(1) as Array<DeviceValue>) }
                    .build()
                val machines = List(2) { h.machine() }
                val addresses = List(2) { "00000000-0000-4000-8000-0000000006${79 + it}" }
                for (i in 0..1) machines[i].devices.attach(addresses[i], TestDevice(methods))
                for (round in 0 until 200) {
                    for (i in 0..1) {
                        val d = machines[i].devices.dispatcher
                        for (method in listOf("bug", "null")) {
                            val r = DeviceDispatcher.resultOf(d.start(0, addresses[i], method, emptyList()).future)
                            assertEquals(CallResult.Error(CallError.INTERNAL_ERROR), r)
                        }
                        val w = d.start(0, addresses[i], "main-bug", emptyList())
                        h.scheduler.tick()
                        assertTrue(w.future.isDone)
                        assertEquals(CallResult.Error(CallError.INTERNAL_ERROR), DeviceDispatcher.resultOf(w.future))
                    }
                }
                val warnings = cap.of(Level.WARNING)
                assertEquals(3, warnings.size, warnings.map { it.message }.toString())
                assertNotNull(warnings.single { it.message.contains("メソッド bug が例外") }.thrown)
                assertNotNull(warnings.single { it.message.contains("メソッド main-bug が例外") }.thrown)
                assertNull(warnings.single { it.message.contains("メソッド null がnull の値") }.thrown)
                // 繰り返しは DEBUG（JUL の FINE）
                assertEquals(200 * 2 * 3 - 3, cap.of(Level.FINE).size)
                // 要約は、1分（1,200 tick）が過ぎた後の tick の頭で1本出る
                repeat(DeviceFailureLog.SUMMARY_INTERVAL_TICKS.toInt()) { h.scheduler.tick() }
                val summary = cap.of(Level.WARNING).drop(3)
                assertEquals(1, summary.size, summary.map { it.message }.toString())
                assertTrue(summary[0].message.contains("${200 * 2 * 3 - 3} 回"), summary[0].message)
            }
        }
    }
}
