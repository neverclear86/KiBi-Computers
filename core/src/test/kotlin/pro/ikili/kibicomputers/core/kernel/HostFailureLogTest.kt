package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pro.ikili.kibicomputers.core.device.SCREEN_ADDRESS
import pro.ikili.kibicomputers.core.device.ScreenDevice
import pro.ikili.kibicomputers.core.display.TextBuffer
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
 * スタックトレースはスケジューラ全体で、投げた場所ごとに最初の1回だけ。JVM の致命的なエラーは捨てずに投げる
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
    fun `同じ台の同じ例外の繰り返しはDEBUG、別の台の同じ場所はスタックトレースなしのWARNING`() = timeout(limit) {
        Captured().use { logs ->
            Harness().use { h ->
                val a = h.machine(host = QuietHost())
                val b = h.machine(host = QuietHost())
                val bug = Runnable { throw IllegalStateException("同じバグ") }
                a.postHostCallback(bug)
                h.scheduler.tick()
                a.postHostCallback(bug)
                h.scheduler.tick()
                assertEquals(1, logs.of(a, Level.WARNING).size)
                assertNotNull(logs.of(a, Level.WARNING).single().thrown)
                val debug = logs.of(a, Level.FINE).single()
                assertTrue(debug.message.contains("同じバグ"))
                assertNull(debug.thrown, "DEBUG にはスタックトレースを付けない")

                // 別の台の同じ種類・同じ場所
                b.postHostCallback(bug)
                h.scheduler.tick()
                assertEquals(1, logs.of(a, Level.WARNING).size)
                val other = logs.of(b, Level.WARNING).single()
                assertTrue(other.message.contains("同じバグ"))
                assertNull(other.thrown, "同じ場所のスタックトレースは、スケジューラ全体で最初の1回だけ")
            }
        }
    }

    @Test
    fun `同じ種類でも別の不具合なら、台が同じでも違っても、それぞれスタックトレース付きのWARNINGになる`() = timeout(limit) {
        Captured().use { logs ->
            Harness().use { h ->
                val a = h.machine(host = QuietHost())
                val b = h.machine(host = QuietHost())
                a.postHostCallback { throw IllegalStateException("バグA") }
                h.scheduler.tick()
                // 同じ台・同じ種類で、別の不具合（例外のクラスが違う）
                a.postHostCallback { throw IllegalArgumentException("バグB") }
                h.scheduler.tick()
                // 別の台で、別の場所から同じクラスの例外
                b.postHostCallback { throw IllegalStateException("バグC") }
                h.scheduler.tick()
                val warnings = logs.of(a, Level.WARNING) + logs.of(b, Level.WARNING)
                assertEquals(3, warnings.size, warnings.map { it.message }.toString())
                assertTrue(warnings.all { it.thrown != null }, "投げた場所が違えば、スタックトレースを付ける")
                assertTrue(logs.of(a, Level.FINE).isEmpty())
            }
        }
    }

    @Test
    fun `標準ライブラリの中で投げる例外でも、呼び出し元が違えばそれぞれスタックトレース付きのWARNINGになる`() = timeout(limit) {
        Captured().use { logs ->
            Harness().use { h ->
                val a = h.machine(host = QuietHost())
                val b = h.machine(host = QuietHost())
                val c = h.machine(host = QuietHost())
                val empty = ArrayList<Int>()
                // どちらも ArrayList.get → Objects.checkIndex の中で投げる（上3段は JDK の中で同じ）
                a.postHostCallback { empty[0] }
                h.scheduler.tick()
                b.postHostCallback { empty[1] }
                h.scheduler.tick()
                // String.charAt も Objects.checkIndex を通る（クラスは StringIndexOutOfBoundsException）
                c.postHostCallback { "".get(0) }
                h.scheduler.tick()
                val warnings = listOf(a, b, c).map { logs.of(it, Level.WARNING).single() }
                assertTrue(warnings.all { it.thrown != null }, "呼び出し元が違えば、スタックトレースを付ける")

                // 同じ呼び出し元からは、別の台でもスタックトレースを省く
                val bug = Runnable { empty[2] }
                val d = h.machine(host = QuietHost())
                val e = h.machine(host = QuietHost())
                d.postHostCallback(bug)
                h.scheduler.tick()
                e.postHostCallback(bug)
                h.scheduler.tick()
                assertNotNull(logs.of(d, Level.WARNING).single().thrown)
                val known = logs.of(e, Level.WARNING).single()
                assertNull(known.thrown)
                assertTrue(known.message.contains("記録済み"), known.message)
            }
        }
    }

    @Test
    fun `場所の記録が上限に達すると、新しい場所はスタックトレースなしで、上限に達したと書いたWARNINGになる`() = timeout(limit) {
        Captured().use { logs ->
            Harness().use { h ->
                // 記録を上限まで埋める（どの通知の種類とも重ならない種類で）
                var filled = 0
                while (true) {
                    val t = IllegalStateException("埋める")
                    t.stackTrace = arrayOf(StackTraceElement("test.Fill", "site$filled", "Fill.kt", filled))
                    val trace = h.scheduler.claimHostFailureTrace(1000, t)
                    if (trace == MachineScheduler.HostFailureTrace.LIMIT_REACHED) break
                    assertEquals(MachineScheduler.HostFailureTrace.NEW, trace)
                    filled++
                    assertTrue(filled <= 1000, "上限がない")
                }
                // 記録済みの場所は、上限に達した後も KNOWN
                val again = IllegalStateException("埋める")
                again.stackTrace = arrayOf(StackTraceElement("test.Fill", "site0", "Fill.kt", 0))
                assertEquals(MachineScheduler.HostFailureTrace.KNOWN, h.scheduler.claimHostFailureTrace(1000, again))

                val m = h.machine(host = QuietHost())
                m.postHostCallback { throw IllegalStateException("上限の後のバグ") }
                h.scheduler.tick()
                m.postHostCallback { throw IllegalArgumentException("上限の後の別のバグ") }
                h.scheduler.tick()
                val warning = logs.of(m, Level.WARNING).single()
                assertNull(warning.thrown, "上限に達した後の新しい場所は覚えられないので、スタックトレースを付けない")
                assertTrue(warning.message.contains("上限に達した"), warning.message)
                assertFalse(warning.message.contains("記録済み"), warning.message)
                // 同じ台・同じ種類の2つ目は DEBUG
                assertTrue(logs.of(m, Level.FINE).single().message.contains("上限の後の別のバグ"))
            }
        }
    }

    @Test
    fun `同じ台で例外のメッセージだけ変わっても、投げた場所が同じなら2回目はスタックトレースなしのDEBUGになる`() = timeout(limit) {
        Captured().use { logs ->
            Harness().use { h ->
                val a = h.machine(host = QuietHost())
                var n = 0
                val bug = Runnable { throw IllegalStateException("回 ${n++}") }
                a.postHostCallback(bug)
                h.scheduler.tick()
                a.postHostCallback(bug)
                h.scheduler.tick()
                assertEquals(1, logs.of(a, Level.WARNING).size)
                assertNull(logs.of(a, Level.FINE).single().thrown)
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

    @Test
    fun `致命的なエラーで抜けても、配っていないマシンと残りの通知は次のtickで配られる`() = timeout(limit) {
        Captured().use { _ ->
            Harness().use { h ->
                val log = Collections.synchronizedList(ArrayList<String>())
                val bad = h.machine(host = QuietHost())
                val good = h.machine(host = QuietHost())
                bad.postHostCallback { throw OutOfMemoryError("test") }
                bad.postHostCallback { log += "bad-after" }
                good.postHostCallback { log += "good" }
                assertThrows(OutOfMemoryError::class.java) { h.scheduler.tick() }
                assertTrue(log.isEmpty())
                h.scheduler.tick()
                assertEquals(setOf("bad-after", "good"), log.toSet())
                assertEquals(2, log.size)
                // 積み直した後も、通常どおり通知が積める（queuedForNotify が立ったままになっていない）
                good.postHostCallback { log += "good-again" }
                h.scheduler.tick()
                assertEquals(3, log.size)
            }
        }
    }

    @Test
    fun `復元の受け手が致命的なエラーを投げると、復元の印は閉じ、tickの外へ届き、ログには出ない`() = timeout(limit) {
        Captured().use { logs ->
            val store = MachineSaveStore(dir, MachineIdIndex(), "test")
            Harness().use { h ->
                val identity = store.assignNewId(MachineOwner("minecraft:overworld", 0, 64, 0))!!
                val m = h.machine(host = QuietHost())
                val config = BootConfig.builder().addBootDisk("none").build()
                val listener = RestoreListener { _, _, _ -> throw OutOfMemoryError("受け手") }
                assertTrue(m.restore(RestoreRequest(RestoreSource(store, identity.id, 5), config, listener)))
                var thrown: Throwable? = null
                eventually(10_000, "致命的なエラーが tick の外へ届かない") {
                    try {
                        h.scheduler.tick()
                    } catch (e: OutOfMemoryError) {
                        thrown = e
                    }
                    thrown != null
                }
                assertEquals("受け手", thrown!!.message)
                assertFalse(m.isRestoring, "受け手が致命的なエラーを投げても復元の印は閉じる")
                assertTrue(logs.of(m, Level.WARNING).isEmpty(), "致命的なエラーはログに出さない")
                m.close()
            }
        }
    }

    @Test
    fun `画面への書き込みが投げると、画面への書き込みの種類でWARNINGが出る`() = timeout(limit) {
        Captured().use { logs ->
            Harness().use { h ->
                val console = Collections.synchronizedList(ArrayList<String>())
                val m = h.machine(host = QuietHost { console += String(it) })
                val buffer = TextBuffer(20, 3)
                val screen = ScreenDevice(buffer, 2)
                assertTrue(m.devices.attach(SCREEN_ADDRESS, screen))
                // 画面のバッファを壊して、書き込みが投げるようにする
                val field = TextBuffer::class.java.getDeclaredField("codePoints")
                field.isAccessible = true
                val original = field.get(buffer)
                field.set(buffer, IntArray(0))
                try {
                    m.writeConsole("x".toByteArray())
                    h.scheduler.tick()
                } finally {
                    field.set(buffer, original)
                }
                val warning = logs.of(m, Level.WARNING).single()
                assertTrue(warning.message.contains("画面への書き込み"), warning.message)
                assertNotNull(warning.thrown)
                assertTrue(console.isEmpty(), "画面があれば接続層には来ない")
                m.devices.detach(SCREEN_ADDRESS)
            }
        }
    }

    @Test
    fun `出力を止めている相手の再開が投げると、出力の再開の種類でWARNINGが出る`() = timeout(limit) {
        Captured().use { logs ->
            Harness().use { h ->
                val m = h.machine(host = QuietHost())
                m.holdConsoleOutput { throw IllegalStateException("再開のバグ") }
                h.scheduler.tick()
                h.scheduler.tick()
                m.releaseConsoleOutput()
                val warning = logs.of(m, Level.WARNING).single()
                assertTrue(warning.message.contains("出力の再開"), warning.message)
                assertTrue(warning.message.contains("再開のバグ"))
                assertNotNull(warning.thrown)
                assertEquals(1, logs.of(m, Level.FINE).size, "止めている間は毎 tick 回るので、2回目からは DEBUG")
            }
        }
    }
}
