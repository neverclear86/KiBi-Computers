package pro.ikili.kibicomputers.core.persist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * 復元の順番待ち（#85）: tick ごとの台数の上限、重ねて積まない、書き込みの終わっていない台の後回しと期限。
 * 始める処理が投げても残りの台を始め、ログは投げた場所ごとに間引く（#785）
 */
class RestoreQueueTest {
    @TempDir
    lateinit var dir: Path

    private val config = MachineSaveConfig(restoresPerTick = 2, restoreWriteWaitMillis = 100)

    @Test
    fun `積んだ順にtickごとの上限まで始め、重ねて積まない`() {
        val queue = RestoreQueue<String>()
        for (name in listOf("a", "b", "a", "c")) queue.offer(name, null, 0)
        assertEquals(3, queue.size)
        val started = ArrayList<Pair<String, Boolean>>()
        assertEquals(2, queue.tick(0, null, config) { t, timedOut -> started += t to timedOut })
        assertEquals(listOf("a" to false, "b" to false), started)
        assertTrue(queue.remove("c"))
        assertFalse(queue.remove("c"))
        assertEquals(0, queue.tick(0, null, config) { t, _ -> started += t to false })
        assertEquals(0, queue.size)
    }

    @Test
    fun `書き込みの終わっていない台は後回しにし、期限を過ぎたら冷えた起動として始める`() {
        val executor = ManualSaveExecutor()
        val store = MachineSaveStore(dir, MachineIdIndex(), "test", executor)
        val self = MachineOwner("o", 0, 0, 0)
        val identity = store.assignNewId(self)!!
        // 書き込みの仕事を溜めたままにして、その ID の書き込みを残す
        val source = object : MachineSnapshotSource {
            override val deltaBase: DeltaBase? get() = null

            override fun build(id: UUID, generation: Long, modVersion: String): MachineSnapshot =
                MachineSnapshot(id, generation, modVersion, listOf(SnapshotSection(SnapshotSection.KERNEL, 1, ByteArray(16))))

            override fun committed(id: UUID, generation: Long) {}
        }
        store.captureAndSubmit(identity, self, { _, _ -> source }, null)
        val queue = RestoreQueue<String>()
        queue.offer("busy", identity.id, 0)
        queue.offer("free", UUID.randomUUID(), 0)
        val started = ArrayList<Pair<String, Boolean>>()
        assertEquals(1, queue.tick(0, store, config) { t, timedOut -> started += t to timedOut })
        assertEquals(listOf("free" to false), started)
        assertEquals(0, queue.tick(TimeUnit.MILLISECONDS.toNanos(50), store, config) { t, timedOut -> started += t to timedOut })
        assertEquals(1, queue.tick(TimeUnit.MILLISECONDS.toNanos(100), store, config) { t, timedOut -> started += t to timedOut })
        assertEquals("busy" to true, started.last())
        executor.runAll()
        assertTrue(store.awaitWrites(identity.id, 0))
    }

    /** `kibicomputers.persist` のログを拾う（core のテストでは System.Logger が JUL に流れる。DEBUG は FINE） */
    private class Captured : AutoCloseable {
        // JUL のロガーは弱参照で持たれるので、ここで持ったままにする
        private val logger: Logger = Logger.getLogger("kibicomputers.persist")
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

        /** 順番待ちの復元の開始についての、[level] のレコード */
        fun of(level: Level): List<LogRecord> =
            records.toList().filter { it.level == level && it.message.startsWith("順番待ちの復元の開始") }

        override fun close() {
            logger.removeHandler(handler)
            logger.level = oldLevel
        }
    }

    private fun boom(): Nothing = throw IllegalStateException("boom")

    private fun otherBoom(): Nothing = throw IllegalStateException("other")

    private fun limit(perTick: Int) = MachineSaveConfig(restoresPerTick = perTick, restoreWriteWaitMillis = 100)

    @Test
    fun `1台が投げても残りの台を始め、投げた台は積み直さない`() {
        Captured().use { log ->
            val queue = RestoreQueue<String>()
            for (name in listOf("a", "b", "c", "d")) queue.offer(name, null, 0)
            val started = ArrayList<String>()
            val starter = RestoreQueue.Starter<String> { t, _ ->
                if (t == "b") boom()
                started += t
            }
            // 投げた台も上限の枠を使う
            assertEquals(3, queue.tick(0, null, limit(3), starter))
            assertEquals(listOf("a", "c"), started)
            assertFalse("b" in queue)
            assertEquals(1, queue.size)
            assertEquals(1, queue.tick(0, null, config, starter))
            assertEquals(listOf("a", "c", "d"), started)
            assertEquals(0, queue.size)
            val warnings = log.of(Level.WARNING)
            assertEquals(1, warnings.size)
            assertNotNull(warnings[0].thrown)
        }
    }

    @Test
    fun `同じ場所で多数の台が投げてもWARNINGは1件で、別の場所はもう1件になり、clearの後はまた出る`() {
        Captured().use { log ->
            val queue = RestoreQueue<Int>()
            for (i in 0 until 100) queue.offer(i, null, 0)
            val many = limit(1000)
            assertEquals(100, queue.tick(0, null, many) { _, _ -> boom() })
            assertEquals(1, log.of(Level.WARNING).size)
            assertEquals(99, log.of(Level.FINE).size)

            queue.offer(100, null, 0)
            queue.tick(0, null, many) { _, _ -> otherBoom() }
            assertEquals(2, log.of(Level.WARNING).size)
            assertNotNull(log.of(Level.WARNING)[1].thrown)

            queue.clear()
            queue.offer(101, null, 0)
            queue.tick(0, null, many) { _, _ -> boom() }
            assertEquals(3, log.of(Level.WARNING).size)
        }
    }

    @Test
    fun `場所の数が上限に達したら、スタックトレースなしのWARNINGを1回出して、以後はDEBUG`() {
        Captured().use { log ->
            val queue = RestoreQueue<Int>()
            for (i in 0 until 64 + 3) queue.offer(i, null, 0)
            // 場所ごとに別のスタック（スタックトレースを差し替える）
            queue.tick(0, null, limit(1000)) { i, _ ->
                val e = IllegalStateException("site $i")
                e.stackTrace = arrayOf(StackTraceElement("test.Site$i", "run", "Site.kt", i + 1))
                throw e
            }
            val warnings = log.of(Level.WARNING)
            assertEquals(65, warnings.size)
            assertTrue(warnings.take(64).all { it.thrown != null })
            assertNull(warnings[64].thrown)
            assertEquals(2, log.of(Level.FINE).size)
        }
    }

    @Test
    fun `致命的なエラーは投げ、まだ始めていない台だけを列に戻す`() {
        val queue = RestoreQueue<String>()
        for (name in listOf("a", "b", "c")) queue.offer(name, null, 0)
        val started = ArrayList<String>()
        val oom = OutOfMemoryError("test")
        val thrown = assertThrows(OutOfMemoryError::class.java) {
            queue.tick(0, null, limit(3)) { t, _ ->
                if (t == "b") throw oom
                started += t
            }
        }
        assertSame(oom, thrown)
        assertEquals(listOf("a"), started)
        assertFalse("a" in queue)
        assertFalse("b" in queue)
        assertTrue("c" in queue)
        assertEquals(1, queue.tick(0, null, limit(3)) { t, _ -> started += t })
        assertEquals(listOf("a", "c"), started)
    }

    @Test
    fun `致命的なエラーで戻した台は、書き込み待ちの期限が元の積んだ時刻から数えられる`() {
        val executor = ManualSaveExecutor()
        val store = MachineSaveStore(dir, MachineIdIndex(), "test", executor)
        val self = MachineOwner("o", 0, 0, 0)
        val identity = store.assignNewId(self)!!
        val source = object : MachineSnapshotSource {
            override val deltaBase: DeltaBase? get() = null

            override fun build(id: UUID, generation: Long, modVersion: String): MachineSnapshot =
                MachineSnapshot(id, generation, modVersion, listOf(SnapshotSection(SnapshotSection.KERNEL, 1, ByteArray(16))))

            override fun committed(id: UUID, generation: Long) {}
        }
        val queue = RestoreQueue<String>()
        queue.offer("first", null, 0)
        queue.offer("waiting", identity.id, 0)
        // 書き込みはまだないので、2台とも始める枠に入る
        assertThrows(OutOfMemoryError::class.java) {
            queue.tick(0, store, config) { t, _ -> if (t == "first") throw OutOfMemoryError("test") }
        }
        assertTrue("waiting" in queue)
        // 戻した後に書き込みを溜める: 期限は元の積んだ時刻（0）から数える
        store.captureAndSubmit(identity, self, { _, _ -> source }, null)
        val started = ArrayList<Pair<String, Boolean>>()
        assertEquals(1, queue.tick(TimeUnit.MILLISECONDS.toNanos(100), store, config) { t, timedOut -> started += t to timedOut })
        assertEquals(listOf("waiting" to true), started)
        executor.runAll()
    }

    @Test
    fun `スタックの溢れは致命的でないので、捨てて続ける`() {
        Captured().use { log ->
            val queue = RestoreQueue<String>()
            for (name in listOf("a", "b")) queue.offer(name, null, 0)
            val started = ArrayList<String>()
            val count = queue.tick(0, null, config) { t, _ ->
                if (t == "a") throw StackOverflowError()
                started += t
            }
            assertEquals(2, count)
            assertEquals(listOf("b"), started)
            assertEquals(1, log.of(Level.WARNING).size)
        }
    }

    @Test
    fun `例外のtoStringが投げても、tickは投げずに続ける`() {
        val queue = RestoreQueue<String>()
        for (name in listOf("a", "b")) queue.offer(name, null, 0)
        val started = ArrayList<String>()
        val nasty = object : RuntimeException() {
            override fun toString(): String = throw IllegalStateException("toString")
        }
        val count = queue.tick(0, null, config) { t, _ ->
            if (t == "a") throw nasty
            started += t
        }
        assertEquals(2, count)
        assertEquals(listOf("b"), started)
    }
}
