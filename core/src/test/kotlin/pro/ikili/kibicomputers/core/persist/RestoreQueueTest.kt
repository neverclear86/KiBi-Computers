package pro.ikili.kibicomputers.core.persist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 復元の順番待ち（#85）: tick ごとの台数の上限、重ねて積まない、書き込みの終わっていない台の後回しと期限 */
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
}
