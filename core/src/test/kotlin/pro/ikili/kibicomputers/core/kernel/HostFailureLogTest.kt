package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pro.ikili.kibicomputers.core.persist.MachineIdIndex
import pro.ikili.kibicomputers.core.persist.MachineOwner
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.core.persist.RestoreListener
import pro.ikili.kibicomputers.core.persist.RestoreOutcome
import pro.ikili.kibicomputers.core.persist.RestoreRequest
import pro.ikili.kibicomputers.core.persist.RestoreSource
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * `Machine.safely` が捨てる接続層の例外をログに出す（#674、ADR 0044）: 1台・1種類で最初の1回は WARNING、2回目からは DEBUG。
 * スタックトレースはスケジューラ全体で種類ごとに最初の1回だけ。JVM の致命的なエラーは捨てずに投げる
 */
class HostFailureLogTest {
    @TempDir
    lateinit var dir: Path

    private val limit = Duration.ofSeconds(30)

    /** `kibicomputers.machine` のログを拾う（core のテストでは System.Logger が JUL に流れる。DEBUG は FINE） */
    private class Captured : AutoCloseable {
        // JUL のロガーは弱参照で持たれるので、ここで持ったままにする
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

        /** マシン [m] についての、[level] のレコード */
        fun of(m: Machine, level: Level): List<LogRecord> =
            records.toList().filter { it.level == level && it.message.startsWith("マシン ${m.id} の") }

        override fun close() {
            logger.removeHandler(handler)
            logger.level = oldLevel
        }
    }

    private class QuietHost(private val console: ((ByteArray) -> Unit)? = null) : MachineHost {
        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            console?.invoke(bytes)
        }
    }

    @Test
    fun `投げても後の通知とほかのマシンに届き、最初の1回はWARNINGでマシンIDが入る`() = timeout(limit) {
        Captured().use { logs ->
            Harness().use { h ->
                val log = Collections.synchronizedList(ArrayList<String>())
                val bad = h.machine(host = QuietHost { throw IllegalStateException("接続層のバグ") })
                val good = h.machine(host = QuietHost { log += "good-console" })
                bad.writeConsole("x".toByteArray())
                bad.postHostCallback { throw IllegalStateException("処理のバグ") }
                bad.postHostCallback { log += "bad-callback" }
                good.writeConsole("y".toByteArray())
                h.scheduler.tick()
                assertEquals(listOf("bad-callback", "good-console"), log.toList())

                val warnings = logs.of(bad, Level.WARNING)
                assertEquals(2, warnings.size, "コンソール出力と処理で1件ずつ: ${warnings.map { it.message }}")
                val console = warnings.single { it.message.contains("コンソール出力") }
                assertTrue(console.message.contains("接続層のバグ"))
                assertNotNull(console.thrown, "スケジューラで最初の1回はスタックトレースを付ける")
                val callback = warnings.single { it.message.contains("postHostCallback") }
                assertTrue(callback.message.contains("処理のバグ"))
                assertTrue(logs.of(good, Level.WARNING).isEmpty())
            }
        }
    }

    @Test
    fun `同じ台・同じ種類の2回目はDEBUG、別の台はまたWARNING`() = timeout(limit) {
        Captured().use { logs ->
            Harness().use { h ->
                val a = h.machine(host = QuietHost())
                val b = h.machine(host = QuietHost())
                a.postHostCallback { throw IllegalStateException("1回目") }
                h.scheduler.tick()
                a.postHostCallback { throw IllegalStateException("2回目") }
                h.scheduler.tick()
                assertEquals(1, logs.of(a, Level.WARNING).size)
                val debug = logs.of(a, Level.FINE).single()
                assertTrue(debug.message.contains("2回目"))
                assertNull(debug.thrown, "DEBUG にはスタックトレースを付けない")

                // 別の台の同じ種類
                b.postHostCallback { throw IllegalStateException("別の台") }
                h.scheduler.tick()
                assertEquals(1, logs.of(a, Level.WARNING).size)
                val other = logs.of(b, Level.WARNING).single()
                assertTrue(other.message.contains("別の台"))
                assertNull(other.thrown, "同じ種類のスタックトレースは、スケジューラ全体で最初の1回だけ")
            }
        }
    }

    @Test
    fun `同じ台の別の種類はまたWARNINGになる`() = timeout(limit) {
        Captured().use { logs ->
            Harness().use { h ->
                val m = h.machine(host = QuietHost { throw IllegalStateException("出力のバグ") })
                m.postHostCallback { throw IllegalStateException("処理のバグ") }
                h.scheduler.tick()
                m.writeConsole("x".toByteArray())
                h.scheduler.tick()
                val warnings = logs.of(m, Level.WARNING)
                assertEquals(2, warnings.size, warnings.map { it.message }.toString())
                assertTrue(warnings.all { it.thrown != null }, "種類ごとに最初の1回はスタックトレースを付ける")
                assertTrue(logs.of(m, Level.FINE).isEmpty())
            }
        }
    }

    @Test
    fun `復元の受け手が投げても後の通知が配られ、復元の印が閉じ、受け手の種類で1件だけ出る`() = timeout(limit) {
        Captured().use { logs ->
            val store = MachineSaveStore(dir, MachineIdIndex(), "test")
            Harness().use { h ->
                val identity = store.assignNewId(MachineOwner("minecraft:overworld", 0, 64, 0))!!
                val m = h.machine(host = QuietHost())
                val after = Collections.synchronizedList(ArrayList<String>())
                val config = BootConfig.builder().addBootDisk("none").build()
                val listener = RestoreListener { machine, outcome, _ ->
                    assertEquals(RestoreOutcome.COLD_BOOT, outcome)
                    // 受け手より後に積んだ通知
                    machine.postHostCallback { after += "after" }
                    throw IllegalStateException("受け手のバグ")
                }
                assertTrue(m.restore(RestoreRequest(RestoreSource(store, identity.id, 5), config, listener)))
                eventually(10_000, "受け手より後の通知が届かない") {
                    h.scheduler.tick()
                    after.isNotEmpty()
                }
                assertFalse(m.isRestoring, "受け手が投げても復元の印は閉じる")
                val warnings = logs.of(m, Level.WARNING)
                assertEquals(1, warnings.size, warnings.map { it.message }.toString())
                assertTrue(warnings.single().message.contains("復元の受け手"))
                assertTrue(warnings.single().message.contains("受け手のバグ"))
                m.close()
            }
        }
    }

    @Test
    fun `JVMの致命的なエラーはtickの外まで届く`() = timeout(limit) {
        Captured().use { logs ->
            // 致命的なエラーを投げた後のスケジューラは、ほかのテストと共有しない
            Harness().use { h ->
                val m = h.machine(host = QuietHost())
                m.postHostCallback { throw OutOfMemoryError("test") }
                assertThrows(OutOfMemoryError::class.java) { h.scheduler.tick() }
                assertTrue(logs.of(m, Level.WARNING).isEmpty(), "致命的なエラーはログに出さない")
            }
        }
    }

    @Test
    fun `StackOverflowErrorは捨ててログに出す`() = timeout(limit) {
        Captured().use { logs ->
            Harness().use { h ->
                val m = h.machine(host = QuietHost())
                val after = Collections.synchronizedList(ArrayList<String>())
                m.postHostCallback { throw StackOverflowError("test") }
                m.postHostCallback { after += "after" }
                h.scheduler.tick()
                assertEquals(listOf("after"), after.toList())
                assertTrue(logs.of(m, Level.WARNING).single().message.contains("StackOverflowError"))
            }
        }
    }
}
